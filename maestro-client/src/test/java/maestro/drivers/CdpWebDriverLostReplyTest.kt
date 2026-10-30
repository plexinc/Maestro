package maestro.drivers

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicInteger

class CdpWebDriverLostReplyTest {

    private val server = MockWebServer()
    private val evaluations = AtomicInteger()

    private var dropped: (evaluation: Int) -> Boolean = { false }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `deviceInfo retries an evaluation whose reply was lost`() {
        dropped = { it == 1 }
        val driver = openDriver()

        val info = driver.deviceInfo()

        assertThat(info.widthPixels).isEqualTo(1024)
        assertThat(info.heightPixels).isEqualTo(768)
    }

    @Test
    fun `deviceInfo keeps the last window size when every reply is lost`() {
        val driver = openDriver()
        driver.deviceInfo()
        dropped = { true }

        val info = driver.deviceInfo()

        assertThat(info.widthPixels).isEqualTo(1024)
        assertThat(info.heightPixels).isEqualTo(768)
    }

    @Test
    fun `deviceInfo fails with a clear error when it has never read the window size`() {
        dropped = { true }
        val driver = openDriver()

        val error = assertThrows<IllegalStateException> { driver.deviceInfo() }

        assertThat(error).hasMessageThat().contains("Could not read the window size")
    }

    private fun openDriver(): CdpWebDriver {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/json" -> MockResponse().setBody(
                    """[{"id":"A","title":"","url":"http://app","type":"page",""" +
                        """"webSocketDebuggerUrl":"ws://${server.hostName}:${server.port}/devtools/page/A"}]"""
                )
                "/devtools/page/A" -> MockResponse().withWebSocketUpgrade(DevToolsPage())
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()

        return CdpWebDriver(
            isStudio = false,
            screenSize = null,
            cdpUrl = "http://${server.hostName}:${server.port}",
        ).apply { open() }
    }

    private inner class DevToolsPage : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (dropped(evaluations.incrementAndGet())) {
                webSocket.cancel()
                return
            }

            val request = jacksonObjectMapper().readTree(text)
            val expression = request["params"]["expression"].asText()
            val value = if ("[window.innerWidth, window.innerHeight]" in expression) "[1024,768]" else "null"
            webSocket.send("""{"id":${request["id"].asInt()},"result":{"result":{"type":"string","value":"$value"}}}""")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = Unit
    }
}
