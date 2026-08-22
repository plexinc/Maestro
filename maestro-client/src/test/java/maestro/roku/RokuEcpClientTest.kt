package maestro.roku

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

internal class RokuEcpClientTest {

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
