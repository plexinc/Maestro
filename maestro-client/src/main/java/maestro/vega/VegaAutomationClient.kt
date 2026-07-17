package maestro.vega

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Base64

/**
 * Talks to the on-device automation toolkit (the accessibility server Amazon's Appium
 * Vega driver uses), which serves JSON-RPC on device TCP port 8383.
 *
 * Vega is not adb, so there is no host-side `adb forward`. Instead we run `curl` on the
 * device (`vega … run-cmd`), writing the JSON-RPC response to a device file with `curl -o`
 * and pulling it to the host with `copy-from`. Routing through a file (rather than the
 * command's stdout) is required: `run-cmd` truncates large stdout — a full-screen
 * `takeScreenshot` PNG (~1 MB) and a deep `getPageSource` tree both exceed that limit.
 */
class VegaAutomationClient(
    private val connection: VegaDeviceConnection,
) {
    private val mapper = jacksonObjectMapper()

    /** Current screen's page-source XML. */
    fun getPageSource(): String {
        val result = call("getPageSource")
        return if (result.isTextual) result.asText() else result.toString()
    }

    /** Current screen as PNG bytes (base64 in the RPC result). */
    fun getScreenshot(): ByteArray {
        val result = call("takeScreenshot")
        if (!result.isTextual) {
            throw VegaToolkitUnavailableException("takeScreenshot returned no image data")
        }
        return Base64.getDecoder().decode(result.asText())
    }

    /** POST a parameterless JSON-RPC [method] to the toolkit and return its `result` node. */
    private fun call(method: String): JsonNode {
        val devicePath = "/tmp/maestro-vega-$method.json"
        val payload = """{"jsonrpc":"2.0","id":1,"method":"$method","params":{}}"""
        connection.shell(
            "curl -s -X POST -H 'Content-Type: application/json' -d '$payload' " +
                "-o $devicePath http://127.0.0.1:$TOOLKIT_PORT/jsonrpc"
        )

        val hostFile = File.createTempFile("maestro-vega-$method", ".json")
        try {
            connection.copyFrom(devicePath, hostFile)
            if (hostFile.length() == 0L) {
                throw VegaToolkitUnavailableException(
                    "Empty response from the Vega automation toolkit (method=$method). The toolkit " +
                        "attaches at app launch after the enable flag is set — relaunch the app under test."
                )
            }
            val node = try {
                mapper.readTree(hostFile)
            } catch (e: Exception) {
                throw VegaToolkitUnavailableException("Malformed toolkit response for $method", e)
            }
            node.get("error")?.let { throw VegaToolkitUnavailableException("Toolkit error for $method: $it") }
            return node.get("result")
                ?: throw VegaToolkitUnavailableException("Toolkit response for $method had no result")
        } finally {
            hostFile.delete()
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(VegaAutomationClient::class.java)
        private const val TOOLKIT_PORT = 8383
    }
}

class VegaToolkitUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)
