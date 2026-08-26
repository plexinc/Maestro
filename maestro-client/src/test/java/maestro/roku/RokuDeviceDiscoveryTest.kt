package maestro.roku

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

internal class RokuDeviceDiscoveryTest {

    // Real SSDP M-SEARCH response shape for `ST: roku:ecp`.
    @Test
    fun `parses the device host from an SSDP response`() {
        val response = listOf(
            "HTTP/1.1 200 OK",
            "Cache-Control: max-age=3600",
            "ST: roku:ecp",
            "Location: http://192.168.1.114:8060/",
            "USN: uuid:roku:ecp:X00000AB12CD",
            "",
            "",
        ).joinToString("\r\n")

        assertThat(RokuDeviceDiscovery.parseSsdpLocation(response)).isEqualTo("192.168.1.114")
    }

    @Test
    fun `returns null when the response has no location header`() {
        val response = "HTTP/1.1 200 OK\r\nST: roku:ecp\r\n\r\n"
        assertThat(RokuDeviceDiscovery.parseSsdpLocation(response)).isNull()
    }
}
