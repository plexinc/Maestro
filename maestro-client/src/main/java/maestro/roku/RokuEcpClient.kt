package maestro.roku

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Sink
import okio.buffer
import org.slf4j.LoggerFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * HTTP client for the Roku External Control Protocol (ECP). All Roku device
 * communication goes through a REST API on device port 8060; screenshots go through
 * the developer web server on port 80 (digest auth with the dev-mode password).
 *
 * Requires the device to be in developer mode with ECP network access set to
 * "Permissive" (recent Roku OS versions return 403 on input commands otherwise).
 *
 * Reference: roku-test-automation ECP.ts / RokuDevice.ts.
 */
class RokuEcpClient(
    val host: String,
    val password: String = "",
    private val ecpPort: Int = DEFAULT_ECP_PORT,
    private val keypressDelayMs: Long = 100,
    private val maxRetries: Int = 3,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val digestLock = Any()
    private var digestNonce: String? = null
    private var digestNonceCount = 0

    private val authClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .authenticator { _, response ->
            if (response.request.header("Authorization") != null) {
                null // Already tried auth, give up
            } else {
                buildDigestAuthRequest(response)
            }
        }
        .build()

    private val baseUrl get() = "http://$host:$ecpPort"

    // --- Key Input ---

    fun sendKeypress(key: String) {
        ecpPost("keypress/${encodePathSegment(key)}")
        if (keypressDelayMs > 0) {
            Thread.sleep(keypressDelayMs)
        }
    }

    fun sendKeyDown(key: String) {
        ecpPost("keydown/${encodePathSegment(key)}")
    }

    fun sendKeyUp(key: String) {
        ecpPost("keyup/${encodePathSegment(key)}")
    }

    /** Types text character-by-character via ECP `LIT_` keypresses. */
    fun sendText(text: String) {
        for (char in text) {
            ecpPost("keypress/${encodePathSegment("LIT_$char")}")
            if (keypressDelayMs > 0) {
                Thread.sleep(keypressDelayMs)
            }
        }
    }

    // --- App Lifecycle ---

    /**
     * Launches a channel with the caller's parameters and nothing else. No
     * `RTA_LAUNCH` flag: it asks a roku-test-automation channel not to restart, which
     * is the opposite of the cold launch [maestro.drivers.RokuDriver.launchApp]
     * guarantees by exiting to Home first — and on any other channel it is an
     * unexpected parameter arriving alongside the deep link the flow asked for.
     */
    fun launchChannel(channelId: String, params: Map<String, String> = emptyMap()) {
        val queryString = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        val path = if (queryString.isEmpty()) "launch/$channelId" else "launch/$channelId?$queryString"
        ecpPost(path)
    }

    fun getActiveApp(): ActiveApp? {
        val doc = ecpGetXml("query/active-app") ?: return null
        val root = doc.documentElement
        val children = root.childNodes

        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE && node.nodeName == "app") {
                val element = node as Element
                return ActiveApp(
                    id = element.getAttribute("id"),
                    title = element.textContent?.trim() ?: "",
                    type = element.getAttribute("type"),
                    version = element.getAttribute("version"),
                )
            }
        }
        return null
    }

    fun isActiveApp(channelId: String): Boolean {
        return getActiveApp()?.id == channelId
    }

    // --- Device Info ---

    fun getDeviceInfo(): RokuDeviceInfo? {
        val doc = ecpGetXml("query/device-info") ?: return null
        val root = doc.documentElement
        val fields = mutableMapOf<String, String>()

        val children = root.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                fields[node.nodeName] = node.textContent?.trim() ?: ""
            }
        }

        return RokuDeviceInfo(
            modelName = fields["model-name"] ?: "Unknown",
            modelNumber = fields["model-number"] ?: "",
            serialNumber = fields["serial-number"] ?: "",
            softwareVersion = fields["software-version"] ?: "",
            uiResolution = fields["ui-resolution"] ?: "1080p",
            friendlyName = fields["friendly-device-name"] ?: fields["device-name"] ?: "",
        )
    }

    // --- View Hierarchy ---

    fun getAppUI(): Document? {
        return ecpGetXml("query/app-ui")
    }

    fun getAppUIRaw(): String? {
        val request = Request.Builder()
            .url("$baseUrl/query/app-ui")
            .get()
            .build()

        val response = try {
            executeWithRetry(request)
        } catch (e: IOException) {
            logger.warn("ECP GET query/app-ui failed: {}", e.message)
            return null
        }

        return try {
            response.body?.string()
        } catch (e: Exception) {
            logger.warn("Failed to read app-ui response", e)
            null
        } finally {
            response.close()
        }
    }

    // --- Screenshot ---

    /**
     * Captures a screenshot from the Roku device. Two-step process:
     * 1. POST /plugin_inspect to generate the screenshot (requires digest auth)
     * 2. GET /pkgs/dev.jpg (or .png) to download it
     *
     * The dev server acknowledges the generation POST before the capture file is
     * written (observed on Roku OS 14), so the download polls until the file's ETag
     * differs from the pre-generation one; on timeout the current file is used (a
     * re-capture of an unchanged screen can legitimately produce identical bytes).
     *
     * Reference: roku-test-automation RokuDevice.ts.
     */
    fun takeScreenshot(out: Sink) {
        val previousEtags = SCREENSHOT_FORMATS.associateWith { screenshotEtag(it) }
        generateScreenshot()

        val deadline = System.currentTimeMillis() + SCREENSHOT_TIMEOUT_MS
        while (true) {
            val timedOut = System.currentTimeMillis() >= deadline
            for (format in SCREENSHOT_FORMATS) {
                // Cache-bust with a timestamp so no intermediary replays an old capture
                val request = Request.Builder()
                    .url("http://$host/pkgs/dev.$format?time=${System.currentTimeMillis()}")
                    .get()
                    .build()

                try {
                    authClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val etag = response.header("ETag")
                            val previous = previousEtags[format]
                            val isFresh = previous == null || etag == null || etag != previous
                            if (isFresh || timedOut) {
                                if (!isFresh) {
                                    logger.debug("Screenshot ETag unchanged after ${SCREENSHOT_TIMEOUT_MS}ms; using current capture")
                                }
                                response.body?.let { body ->
                                    val bufferedOut = out.buffer()
                                    bufferedOut.writeAll(body.source())
                                    bufferedOut.flush()
                                }
                                return
                            }
                        }
                    }
                } catch (e: IOException) {
                    logger.debug("Failed to download screenshot as $format", e)
                }
            }

            if (timedOut) break
            Thread.sleep(SCREENSHOT_POLL_INTERVAL_MS)
        }

        throw IOException(
            "Failed to capture screenshot from Roku device at $host. " +
                "Screenshots require the developer-mode password (MAESTRO_ROKU_PASSWORD)."
        )
    }

    /** ETag of the current capture file, or null if none exists (or the server omits it). */
    private fun screenshotEtag(format: String): String? {
        val request = Request.Builder()
            .url("http://$host/pkgs/dev.$format")
            .head()
            .build()
        return try {
            authClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.header("ETag") else null
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun generateScreenshot() {
        val url = "http://$host/plugin_inspect"

        // The dev server only executes the form action when the multipart body arrives
        // on an already-authorized request (curl's --digest behavior: an empty-body
        // probe collects the challenge, then the form is sent with Authorization
        // attached up front). Sending the body on the unauthenticated request and
        // retrying via an OkHttp authenticator gets a 200 whose action silently never
        // ran — so the handshake is done explicitly here.
        val probe = Request.Builder()
            .url(url)
            .post("".toRequestBody(null))
            .build()

        val challenge = try {
            client.newCall(probe).execute().use { response ->
                if (response.code == 401) response.header("WWW-Authenticate") else null
            }
        } catch (e: IOException) {
            throw IOException("Screenshot generation request to Roku device at $host failed", e)
        }

        // Multipart form matches the Roku dev web server's expected format. Two quirks,
        // both verified against Roku OS 14 hardware: the empty `archive` field is
        // required (without it the form handler silently does nothing), and parts must
        // carry ONLY a Content-Disposition header — the server's parser ignores parts
        // with a per-part Content-Length, which OkHttp's MultipartBody always adds.
        // So the body is assembled by hand, curl-style.
        val boundary = "----MaestroRokuFormBoundary${System.nanoTime()}"
        val body = buildString {
            append("--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"mysubmit\"\r\n\r\n")
            append("Screenshot\r\n")
            append("--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"archive\"\r\n\r\n")
            append("\r\n")
            append("--$boundary--\r\n")
        }.toRequestBody("multipart/form-data; boundary=$boundary".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(body)
            .apply {
                challenge
                    ?.let { buildDigestHeader(it, "POST", "/plugin_inspect") }
                    ?.let { header("Authorization", it) }
            }
            .build()

        val responseBody = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException(
                        "Screenshot generation failed (HTTP ${response.code}). " +
                            "Check the developer-mode password (MAESTRO_ROKU_PASSWORD)."
                    )
                }
                response.body?.string() ?: ""
            }
        } catch (e: IOException) {
            throw IOException("Screenshot generation request to Roku device at $host failed", e)
        }

        // The dev server reports the result inside the returned page (RTA pattern);
        // anything else means no fresh capture was written to /pkgs/dev.jpg.
        if (!responseBody.contains("Screenshot ok")) {
            logger.debug("plugin_inspect did not confirm: {}", responseBody.replace("\n", " ").take(300))
            throw IOException(
                "Roku device at $host did not confirm the screenshot " +
                    "(requires a sideloaded dev channel in the foreground)."
            )
        }
    }

    // --- Connectivity ---

    fun isReachable(): Boolean {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/")
                .get()
                .build()
            client.newCall(request).execute().close()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        authClient.dispatcher.executorService.shutdown()
        authClient.connectionPool.evictAll()
    }

    // --- Digest Auth ---

    private fun buildDigestAuthRequest(response: Response): Request? {
        val challengeHeader = response.header("WWW-Authenticate") ?: return null
        val authValue = buildDigestHeader(
            challengeHeader = challengeHeader,
            method = response.request.method,
            uri = response.request.url.encodedPath,
        ) ?: return null

        return response.request.newBuilder()
            .header("Authorization", authValue)
            .build()
    }

    /** RFC 2617: `nc` counts requests sent with one nonce, restarting at 1 for a new one. */
    private fun nextNonceCount(nonce: String): Int = synchronized(digestLock) {
        if (nonce != digestNonce) {
            digestNonce = nonce
            digestNonceCount = 0
        }
        ++digestNonceCount
    }

    private fun buildDigestHeader(challengeHeader: String, method: String, uri: String): String? {
        if (!challengeHeader.startsWith("Digest ", ignoreCase = true)) return null

        val params = parseDigestChallenge(challengeHeader.removePrefix("Digest ").removePrefix("digest "))
        val realm = params["realm"] ?: return null
        val nonce = params["nonce"] ?: return null
        val qop = params["qop"]

        val nc = String.format("%08x", nextNonceCount(nonce))
        val cnonce = String.format("%08x", System.nanoTime())

        val ha1 = md5Hex("$DEV_USERNAME:$realm:$password")
        val ha2 = md5Hex("$method:$uri")

        val digestResponse = if (qop != null) {
            md5Hex("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
        } else {
            md5Hex("$ha1:$nonce:$ha2")
        }

        return buildString {
            append("Digest username=\"$DEV_USERNAME\"")
            append(", realm=\"$realm\"")
            append(", nonce=\"$nonce\"")
            append(", uri=\"$uri\"")
            if (qop != null) {
                append(", qop=$qop")
                append(", nc=$nc")
                append(", cnonce=\"$cnonce\"")
            }
            append(", response=\"$digestResponse\"")
        }
    }

    // --- Internal HTTP helpers ---

    /**
     * Issues a state-changing ECP call (input, launch). Throws on failure: an ECP
     * command that never reached the device must fail the flow rather than let it
     * continue asserting against a screen no keypress ever touched.
     */
    private fun ecpPost(path: String) {
        val request = Request.Builder()
            .url("$baseUrl/$path")
            .post("".toRequestBody("text/plain".toMediaType()))
            .build()

        executeWithRetry(request).close()
    }

    private fun ecpGetXml(path: String): Document? {
        val request = Request.Builder()
            .url("$baseUrl/$path")
            .get()
            .build()

        // Queries stay tolerant: callers treat a null document as "hierarchy
        // unavailable" and poll or report it themselves.
        val response = try {
            executeWithRetry(request)
        } catch (e: IOException) {
            logger.warn("ECP GET $path failed: {}", e.message)
            return null
        }

        return try {
            val bytes = response.body?.bytes() ?: return null
            parseXml(bytes)
        } catch (e: Exception) {
            logger.warn("Failed to parse XML from $path", e)
            null
        } finally {
            response.close()
        }
    }

    /**
     * Parses ECP XML. [DocumentBuilderFactory] is not thread-safe for
     * `newDocumentBuilder()` and the driver polls the hierarchy from more than one
     * thread, so the shared factory is only touched under a lock.
     */
    private fun parseXml(bytes: ByteArray): Document {
        val builder = synchronized(documentBuilderFactory) { documentBuilderFactory.newDocumentBuilder() }
        return builder.parse(ByteArrayInputStream(bytes))
    }

    /**
     * Executes [request], retrying transport failures and 5xx responses. Throws
     * [RokuEcpException] once the attempts are spent, carrying the HTTP status: OkHttp
     * returns a [Response] for every status and only throws on transport failures, so a
     * status the device rejected (403 when ECP access isn't Permissive) is the failure
     * detail that matters most and must survive into the error.
     */
    private fun executeWithRetry(request: Request): Response {
        var lastFailure: RokuEcpException? = null

        for (attempt in 1..maxRetries) {
            val failure = try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    return response
                }
                val code = response.code
                response.close()
                RokuEcpException("ECP request to ${request.url} failed with HTTP $code.${hintFor(code)}", code)
            } catch (e: IOException) {
                RokuEcpException("ECP request to ${request.url} failed: ${e.message}", cause = e)
            }

            lastFailure = failure
            // 4xx is the device's verdict on this request — a retry re-sends what it
            // already rejected, so report it now instead of after three round trips.
            val code = failure.statusCode
            if (code != null && code in 400..499) break

            if (attempt < maxRetries) {
                logger.debug("{} (attempt {}/{}). Retrying.", failure.message, attempt, maxRetries)
                Thread.sleep(RETRY_BACKOFF_MS)
            }
        }

        throw lastFailure ?: RokuEcpException("ECP request to ${request.url} failed")
    }

    /** HTTP status the device answered with, or null for a transport failure. */
    class RokuEcpException(
        message: String,
        val statusCode: Int? = null,
        cause: Throwable? = null,
    ) : IOException(message, cause)

    data class ActiveApp(
        val id: String,
        val title: String,
        val type: String,
        val version: String,
    )

    data class RokuDeviceInfo(
        val modelName: String,
        val modelNumber: String,
        val serialNumber: String,
        val softwareVersion: String,
        val uiResolution: String,
        val friendlyName: String,
    ) {
        val widthPixels: Int get() = if (uiResolution.contains("1080")) 1920 else 1280
        val heightPixels: Int get() = if (uiResolution.contains("1080")) 1080 else 720
    }

    companion object {
        private val logger = LoggerFactory.getLogger(RokuEcpClient::class.java)
        // The XML is read off the network, so the parser takes no DTDs or external
        // entities. Hardened once here; see parseXml for the locking.
        private val documentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }

        const val DEFAULT_ECP_PORT = 8060
        private const val DEV_USERNAME = "rokudev"
        private const val RETRY_BACKOFF_MS = 50L

        /** Setup advice for the statuses a misconfigured device actually returns. */
        internal fun hintFor(statusCode: Int): String = when (statusCode) {
            403 -> " The device is refusing ECP commands: set Settings > System > Advanced system " +
                "settings > Control by mobile apps > Network access to \"Permissive\"."
            401 -> " Check the developer-mode password (MAESTRO_ROKU_PASSWORD)."
            else -> ""
        }

        private val SCREENSHOT_FORMATS = listOf("jpg", "png")
        private const val SCREENSHOT_TIMEOUT_MS = 10_000L
        private const val SCREENSHOT_POLL_INTERVAL_MS = 250L

        /** Percent-encode a URL path segment. URLEncoder alone form-encodes a space as
         * `+`, which ECP would deliver as a literal plus (`LIT_+` types "+", not " "). */
        internal fun encodePathSegment(value: String): String {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        }

        internal fun parseDigestChallenge(header: String): Map<String, String> {
            val params = mutableMapOf<String, String>()
            val regex = Regex("""(\w+)=(?:"([^"]*)"|([\w/]+))""")
            for (match in regex.findAll(header)) {
                val key = match.groupValues[1]
                val value = match.groupValues[2].ifEmpty { match.groupValues[3] }
                params[key] = value
            }
            return params
        }

        internal fun md5Hex(input: String): String {
            val digest = MessageDigest.getInstance("MD5")
            return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}
