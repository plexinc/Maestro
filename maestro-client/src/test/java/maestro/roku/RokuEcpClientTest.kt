package maestro.roku

import com.google.common.truth.Truth.assertThat
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class RokuEcpClientTest {

    private fun withServer(block: (MockWebServer, RokuEcpClient) -> Unit) {
        val server = MockWebServer()
        server.start()
        val client = RokuEcpClient(
            host = server.hostName,
            ecpPort = server.port,
            keypressDelayMs = 0,
        )
        try {
            block(server, client)
        } finally {
            client.close()
            server.shutdown()
        }
    }

    // The failure mode this guards: recent Roku OS answers input commands with 403 when
    // ECP access isn't Permissive while /query/app-ui keeps working, so a swallowed POST
    // means the hierarchy reads fine, every keypress no-ops, and the flow passes green.
    @Test
    fun `a rejected input command fails instead of warning`() {
        withServer { server, client ->
            server.enqueue(MockResponse().setResponseCode(403))

            val error = assertThrows<RokuEcpClient.RokuEcpException> { client.sendKeypress("Select") }

            assertThat(error.statusCode).isEqualTo(403)
            assertThat(error).hasMessageThat().contains("403")
            assertThat(error).hasMessageThat().contains("Permissive")
        }
    }

    // OkHttp only throws on transport failures, so a status has to be read off the
    // response — retrying a 4xx just re-sends what the device already rejected.
    @Test
    fun `a 4xx is reported without retrying`() {
        withServer { server, client ->
            server.enqueue(MockResponse().setResponseCode(404))

            assertThrows<RokuEcpClient.RokuEcpException> { client.sendKeypress("Down") }

            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test
    fun `a 5xx is retried and the final status is reported`() {
        withServer { server, client ->
            repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }

            val error = assertThrows<RokuEcpClient.RokuEcpException> { client.sendKeypress("Down") }

            assertThat(error.statusCode).isEqualTo(503)
            assertThat(server.requestCount).isEqualTo(3)
        }
    }

    @Test
    fun `a transient failure is retried and then succeeds`() {
        withServer { server, client ->
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(MockResponse().setResponseCode(200))

            client.sendKeypress("Up")

            assertThat(server.requestCount).isEqualTo(2)
        }
    }

    // Queries stay tolerant: callers poll them and report an unavailable hierarchy
    // themselves, so a failed read is null rather than a thrown flow failure.
    @Test
    fun `a failed query returns null`() {
        withServer { server, client ->
            server.enqueue(MockResponse().setResponseCode(403))

            assertThat(client.getActiveApp()).isNull()
        }
    }

    // ECP delivers path segments literally: a form-encoded "+" would type a plus
    // sign, so spaces must be percent-encoded.
    @Test
    fun `encodes literal keypress path segments with percent-encoded spaces`() {
        assertThat(RokuEcpClient.encodePathSegment("LIT_ ")).isEqualTo("LIT_%20")
        assertThat(RokuEcpClient.encodePathSegment("LIT_a")).isEqualTo("LIT_a")
        assertThat(RokuEcpClient.encodePathSegment("LIT_&")).isEqualTo("LIT_%26")
        assertThat(RokuEcpClient.encodePathSegment("LIT_+")).isEqualTo("LIT_%2B")
    }

    // Real challenge shape from the Roku dev web server (Roku OS 14).
    @Test
    fun `parses the dev server digest challenge`() {
        val params = RokuEcpClient.parseDigestChallenge(
            """qop="auth", realm="rokudev", nonce="1787436926""""
        )
        assertThat(params["qop"]).isEqualTo("auth")
        assertThat(params["realm"]).isEqualTo("rokudev")
        assertThat(params["nonce"]).isEqualTo("1787436926")
    }
}
