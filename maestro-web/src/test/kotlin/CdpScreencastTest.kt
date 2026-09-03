import com.google.common.truth.Truth.assertThat
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Drives startScreencast against a stand-in DevTools socket; Chrome is not involved. */
class CdpScreencastTest {

    private val frameData = byteArrayOf(1, 2, 3)

    @Test
    fun `streams frames to the consumer and acks each one`() {
        val serverMessages = ConcurrentLinkedQueue<String>()
        val bothAcked = CountDownLatch(2)
        val port = freePort()

        val server = embeddedServer(CIO, port = port) {
            install(WebSockets)
            routing {
                webSocket("/devtools/page/A") {
                    for (frame in incoming) {
                        val text = (frame as? Frame.Text)?.readText() ?: continue
                        serverMessages += text
                        if (text.contains("Page.startScreencast")) {
                            repeat(2) { sendScreencastFrame(sessionId = it) }
                        }
                        if (text.contains("Page.screencastFrameAck")) bothAcked.countDown()
                    }
                }
            }
        }.start(wait = false)

        val received = ConcurrentLinkedQueue<ByteArray>()
        val target = CdpTarget(
            id = "A",
            title = "",
            url = "",
            webSocketDebuggerUrl = "ws://127.0.0.1:$port/devtools/page/A",
        )

        val screencast = runBlocking { CdpClient().startScreencast(target, onFrame = { received += it }) }
        try {
            assertThat(bothAcked.await(10, TimeUnit.SECONDS)).isTrue()
        } finally {
            screencast.close()
            server.stop(0, 0)
        }

        assertThat(received.map { it.toList() }).containsExactly(frameData.toList(), frameData.toList())
        // Page.enable precedes the screencast start, and every delivered frame is acked.
        assertThat(serverMessages.first()).contains("Page.enable")
        assertThat(serverMessages.count { it.contains("Page.screencastFrameAck") }).isEqualTo(2)
    }

    @Test
    fun `surfaces a connection failure instead of hanging`() {
        val target = CdpTarget(
            id = "A",
            title = "",
            url = "",
            webSocketDebuggerUrl = "ws://127.0.0.1:${freePort()}/devtools/page/A",
        )

        val result = runCatching { runBlocking { CdpClient().startScreencast(target) {} } }

        assertThat(result.isFailure).isTrue()
    }

    private suspend fun DefaultWebSocketServerSession.sendScreencastFrame(sessionId: Int) {
        val data = Base64.getEncoder().encodeToString(frameData)
        send(
            Frame.Text(
                """{"method":"Page.screencastFrame","params":{"data":"$data","sessionId":"$sessionId"}}"""
            )
        )
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
