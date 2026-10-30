import com.google.common.truth.Truth.assertThat
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.net.ServerSocket

class CdpClientTest {

    @Test
    fun `reports Chrome's close reason, but no page data, when the socket closes before the reply`() {
        val port = freePort()
        val server = embeddedServer(CIO, port = port) {
            install(WebSockets)
            routing {
                webSocket("/devtools/page/A") {
                    incoming.receive()
                    send(Frame.Text("""{"method":"Runtime.consoleAPICalled","params":{"args":[{"value":"secret-token"}]}}"""))
                    send(Frame.Text("""{"method":"Inspector.detached","params":{"reason":"target_closed"}}"""))
                    close(CloseReason(CloseReason.Codes.GOING_AWAY, "target closed"))
                }
            }
        }.start(wait = false)

        val result = try {
            runCatching { runBlocking { CdpClient().evaluate("1", target(port)) } }
        } finally {
            server.stop(0, 0)
        }

        val error = result.exceptionOrNull()
        assertThat(error).isInstanceOf(CdpReplyLostException::class.java)
        assertThat(error).hasMessageThat().contains("target closed")
        assertThat(error).hasMessageThat().contains("Inspector.detached (target_closed)")
        assertThat(error).hasMessageThat().contains("Runtime.consoleAPICalled")
        assertThat(error).hasMessageThat().doesNotContain("secret-token")
    }

    @Test
    fun `returns the reply to its own command`() {
        val port = freePort()
        val server = embeddedServer(CIO, port = port) {
            install(WebSockets)
            routing {
                webSocket("/devtools/page/A") {
                    val request = (incoming.receive() as Frame.Text).readText()
                    val id = Json.parseToJsonElement(request).jsonObject["id"]!!.jsonPrimitive.int
                    send(Frame.Text("""{"method":"Runtime.consoleAPICalled","params":{}}"""))
                    send(Frame.Text("""{"id":$id,"result":{"result":{"type":"string","value":"2"}}}"""))
                }
            }
        }.start(wait = false)

        val result = try {
            runBlocking { CdpClient().evaluate("1+1", target(port)) }
        } finally {
            server.stop(0, 0)
        }

        assertThat(result).isEqualTo("2")
    }

    private fun target(port: Int) = CdpTarget(
        id = "A",
        title = "",
        url = "",
        webSocketDebuggerUrl = "ws://127.0.0.1:$port/devtools/page/A",
    )

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
