package maestro.vega

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

internal class VegaCliTest {

    // Real `vega device list` output shape (SDK 0.23.9106): a header line, then one
    // `<selector> : <profile> - <arch> - <os> - <hostname>` line per device. The `-d`
    // selector is the first field, not the trailing hostname.
    @Test
    fun `parses the selector from a virtual device listing`() {
        val output = """
            Found the following device:
            VirtualDevice : tv - aarch64 - OS - amazon-e511f30b6e4af62e
        """.trimIndent()

        val devices = VegaCli.parseDeviceList(output)

        assertThat(devices).hasSize(1)
        assertThat(devices[0].serial).isEqualTo("VirtualDevice")
        assertThat(devices[0].isVirtual).isTrue()
    }

    @Test
    fun `ignores the header and blank lines`() {
        val output = "No devices found"
        assertThat(VegaCli.parseDeviceList(output)).isEmpty()
    }
}
