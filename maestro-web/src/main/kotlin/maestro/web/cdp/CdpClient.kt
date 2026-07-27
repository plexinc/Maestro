import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Descriptor for a CDP target (an open tab/page).
 */
@Serializable
data class CdpTarget(
    val id: String,
    val title: String,
    val url: String,
    val webSocketDebuggerUrl: String? = null,
)

/**
 * A simple client for Chrome DevTools Protocol (CDP).
 *
 * Connects via HTTP to list targets and via WebSocket
 * to evaluate JS expressions with full JSON serialization.
 */
class CdpClient(
    private val host: String = "localhost",
    private val port: Int = 9222
) {
    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json()
        }
        install(WebSockets)
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val idCounter = AtomicInteger(1)
    private val evalMutex = Mutex()

    /**
     * Fetches the list of open CDP targets (tabs/pages).
     */
    suspend fun listTargets(): List<CdpTarget> {
        val endpoint = "http://$host:$port/json"
        val response = httpClient.get(endpoint).bodyAsText()

        return json.decodeFromString(response)
    }

    /**
     * Resolves a single target to drive, in priority order:
     *  1. explicit [targetId] (the CDP target/tile id),
     *  2. a target whose url equals or contains [urlMatch] (e.g. the flow's APP_ID),
     *  3. the first target (legacy launched-Chrome behavior).
     */
    suspend fun resolveTarget(targetId: String? = null, urlMatch: String? = null): CdpTarget {
        val targets = listTargets()
        if (targets.isEmpty()) error("No CDP targets available at $host:$port")

        targetId?.let { id ->
            return targets.firstOrNull { it.id == id }
                ?: error("No CDP target with id '$id'. Available: ${targets.map { "${it.id} -> ${it.url}" }}")
        }

        urlMatch?.let { u ->
            targets.firstOrNull { it.url == u }?.let { return it }
            targets.firstOrNull { it.url.contains(u) }?.let { return it }
        }

        return targets.first()
    }

    /**
     * Evaluates a JS expression on the given target, serializing the result via JSON.stringify.
     *
     * @param expression JS code to evaluate.
     * @param target The CDP target descriptor.
     * @return A JSON string of the evaluated result.
     */
    suspend fun evaluate(expression: String, target: CdpTarget): String {
        val wsUrl = target.webSocketDebuggerUrl ?: error("Target ${target.id} has no WebSocket debugger URL")

        // The idea here is that we return JSON object as a String. That makes it much easier to handle
        // as passing objects between JS and outside world would require many round-trips to query the values
        // from the browser.
        val wrapped = """
            JSON.stringify((() => {
                try { return $expression }
                catch(e) { return { __cdpError: e.toString() } }
            })())
        """.trimIndent()

        val exprJson = Json.encodeToString(JsonPrimitive(wrapped))
        val messageId = idCounter.getAndIncrement()
        val payload = """
            {
                "id":$messageId,
                "method":"Runtime.evaluate",
                "params":{"expression":$exprJson,"awaitPromise":true}
            }
        """.trimIndent()

        return evalMutex.withLock {
            httpClient.webSocketSession {
                url(wsUrl)
            }.use { session ->
                session.send(Frame.Text(payload))

                val text = session.waitForMessage(messageId)

                // Parse JSON
                val root = json.parseToJsonElement(text).jsonObject
                val resultObj = root["result"]?.jsonObject
                    ?.get("result")?.jsonObject
                    ?: error("Invalid CDP response: $text")

                val raw: String = resultObj["value"]?.jsonPrimitive?.content
                    ?: ""

                if (raw.isEmpty()) {
                    return@use ""
                }

                // Check for JS error
                val parsed = json.parseToJsonElement(raw)
                if (parsed is JsonObject && parsed.jsonObject.containsKey("__cdpError")) {
                    val err = parsed.jsonObject["__cdpError"]?.jsonPrimitive?.content
                    error("JS error: $err")
                }
                return@use raw
            }
        }
    }

    suspend fun captureScreenshot(target: CdpTarget): ByteArray {
        val messageId = idCounter.getAndIncrement()

        // Request the screenshot
        val payload = """
            {
                "id": $messageId,
                "method": "Page.captureScreenshot",
                "params": {
                    "format": "png",
                    "quality": 100
                }
            }
        """.trimIndent()

        // Open WS, send & await
        val wsUrl = target.webSocketDebuggerUrl ?: error("Target ${target.id} has no WebSocket debugger URL")

        return httpClient.webSocketSession { url(wsUrl) }
            .use { session ->
                session.send(Frame.Text(payload))

                val text = session.waitForMessage(messageId)

                val data = Json.parseToJsonElement(text)
                    .jsonObject["result"]!!.jsonObject["data"]!!.jsonPrimitive.content

                return@use Base64.getDecoder().decode(data)
            }
    }

    suspend fun openUrl(url: String, target: CdpTarget) {
        // Send a CDP command to open a new tab with the specified URL
        val messageId = idCounter.getAndIncrement()
        val payload = """
            {
                "id": $messageId,
                "method": "Page.navigate",
                "params": {
                    "url": "$url"
                }
            }
        """.trimIndent()

        httpClient.webSocketSession { url(target.webSocketDebuggerUrl ?: error("Target ${target.id} has no WebSocket debugger URL")) }
            .use { session ->
                session.send(Frame.Text(payload))

                session.waitForMessage(messageId)
            }
    }

    suspend fun clearDataForOrigin(origin: String, storageTypes: String, target: CdpTarget) {
        val messageId = idCounter.getAndIncrement()
        val originJson = Json.encodeToString(JsonPrimitive(origin))
        val storageTypesJson = Json.encodeToString(JsonPrimitive(storageTypes))
        val payload = """
            {
                "id": $messageId,
                "method": "Storage.clearDataForOrigin",
                "params": {
                    "origin": $originJson,
                    "storageTypes": $storageTypesJson
                }
            }
        """.trimIndent()

        evalMutex.withLock {
            httpClient.webSocketSession { url(target.webSocketDebuggerUrl ?: error("Target ${target.id} has no WebSocket debugger URL")) }
                .use { session ->
                    session.send(Frame.Text(payload))

                    val text = session.waitForMessage(messageId)
                    val root = json.parseToJsonElement(text).jsonObject
                    if (root["error"] != null) {
                        error("CDP error: ${root["error"]}")
                    }
                }
        }
    }

    /**
     * Dispatches a synthetic keyboard event to the target (Input.dispatchKeyEvent).
     * Used in attach mode where there is no Selenium session to route keys through.
     */
    suspend fun dispatchKeyEvent(
        target: CdpTarget,
        type: String,
        key: String? = null,
        code: String? = null,
        windowsVirtualKeyCode: Int? = null,
        text: String? = null,
    ) {
        val params = buildJsonObject {
            put("type", type)
            key?.let { put("key", it) }
            code?.let { put("code", it) }
            windowsVirtualKeyCode?.let {
                put("windowsVirtualKeyCode", it)
                put("nativeVirtualKeyCode", it)
            }
            text?.let { put("text", it) }
        }
        sendCommand("Input.dispatchKeyEvent", params, target)
    }

    /**
     * Dispatches a synthetic mouse event to the target (Input.dispatchMouseEvent).
     */
    suspend fun dispatchMouseEvent(
        target: CdpTarget,
        type: String,
        x: Int,
        y: Int,
        button: String = "none",
        clickCount: Int = 0,
    ) {
        val params = buildJsonObject {
            put("type", type)
            put("x", x)
            put("y", y)
            put("button", button)
            put("clickCount", clickCount)
        }
        sendCommand("Input.dispatchMouseEvent", params, target)
    }

    private suspend fun sendCommand(method: String, params: JsonObject, target: CdpTarget): String {
        val messageId = idCounter.getAndIncrement()
        val paramsJson = Json.encodeToString(JsonObject.serializer(), params)
        val payload = """{"id":$messageId,"method":"$method","params":$paramsJson}"""

        return evalMutex.withLock {
            httpClient.webSocketSession { url(target.webSocketDebuggerUrl ?: error("Target ${target.id} has no WebSocket debugger URL")) }
                .use { session ->
                    session.send(Frame.Text(payload))
                    session.waitForMessage(messageId)
                }
        }
    }

    private suspend fun DefaultClientWebSocketSession.waitForMessage(messageId: Int): String {
        for (frame in incoming) {
            if (frame is Frame.Text) {
                val text = frame.readText()
                if (text.contains("\"id\":$messageId")) {
                    return text
                }
            }
        }
        error("No message with id $messageId received")
    }

    private suspend fun <R> DefaultClientWebSocketSession.use(block: suspend (DefaultClientWebSocketSession) -> R): R {
        return try {
            block(this)
        } finally {
            close()
        }
    }

}

suspend fun main() {
    val client = CdpClient("localhost", 9222)
    val targets = client.listTargets()
    println("Available pages: $targets")

    // Resolve the target the same way the driver does in attach mode: by URL match
    // (e.g. the flow's APP_ID), falling back to the first target.
    val page = client.resolveTarget(urlMatch = System.getenv("CDP_TARGET_URL"))
    println("Resolved target: ${page.id} -> ${page.url}")

    val json = client.evaluate("1+1", page)
    println("Result: $json")

    val screenshot = client.captureScreenshot(page)
    println("Screenshot captured, size: ${screenshot.size} bytes")

    // Save screenshot to file or process as needed
    File("local/screenshot.png").writeBytes(screenshot)
}
