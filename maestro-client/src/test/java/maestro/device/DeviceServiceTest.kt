package maestro.device

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import util.DeviceCtlProcess
import util.LocalIOSDevice
import java.io.File

internal class DeviceServiceTest {

    @TempDir
    lateinit var avdHome: File

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Creates <avdHome>/<avdName>.avd/config.ini with the given content. */
    private fun writeConfigIni(avdName: String, content: String) {
        val dir = File(avdHome, "$avdName.avd").also { it.mkdirs() }
        File(dir, "config.ini").writeText(content)
    }

    private fun List<AvdInfo>.named(name: String) = find { it.name == name }

    // -------------------------------------------------------------------------
    // Happy-path
    // -------------------------------------------------------------------------

    @Test
    fun `single AVD with all fields and config ini present`() {
        writeConfigIni(
            "Pixel_6_API_34",
            "image.sysdir.1=system-images/android-34/google_apis/arm64-v8a/\n"
        )

        val output = """
            Available Android Virtual Devices:
                Name: Pixel_6_API_34
              Device: pixel_6 (Google Pixel 6)
                Path: /home/user/.android/avd/Pixel_6_API_34.avd
              Target: Google APIs (Google Inc.)
                      Based on: Android 14.0 ("UpsideDownCake") Tag/ABI: google_apis/arm64-v8a
                Skin: pixel_6
              Sdcard: 512M
        """.trimIndent()

        val result = DeviceService.parseAvdInfo(output, avdHome)

        assertThat(result).hasSize(1)
        assertThat(result.named("Pixel_6_API_34")).isEqualTo(AvdInfo(name = "Pixel_6_API_34", model = "pixel_6", os = "android-34"))
    }

    @Test
    fun `multiple AVDs are all parsed`() {
        writeConfigIni("Pixel_6_API_34", "image.sysdir.1=system-images/android-34/google_apis/arm64-v8a/\n")
        writeConfigIni("Pixel_7_API_33", "image.sysdir.1=system-images/android-33/google_apis/x86_64/\n")

        val output = """
            Available Android Virtual Devices:
                Name: Pixel_6_API_34
              Device: pixel_6 (Google Pixel 6)
                Path: /home/user/.android/avd/Pixel_6_API_34.avd
              Target: Google APIs (Google Inc.)
                      Based on: Android 14.0 Tag/ABI: google_apis/arm64-v8a
            ---------
                Name: Pixel_7_API_33
              Device: pixel_7 (Google Pixel 7)
                Path: /home/user/.android/avd/Pixel_7_API_33.avd
              Target: Google APIs (Google Inc.)
                      Based on: Android 13.0 Tag/ABI: google_apis/x86_64
        """.trimIndent()

        val result = DeviceService.parseAvdInfo(output, avdHome)

        assertThat(result).hasSize(2)
        assertThat(result.named("Pixel_6_API_34")).isEqualTo(AvdInfo(name = "Pixel_6_API_34", model = "pixel_6", os = "android-34"))
        assertThat(result.named("Pixel_7_API_33")).isEqualTo(AvdInfo(name = "Pixel_7_API_33", model = "pixel_7", os = "android-33"))
    }

    // -------------------------------------------------------------------------
    // Model (Device:) field edge cases
    // -------------------------------------------------------------------------

    @Test
    fun `Device field with no parenthetical description uses full token as model`() {
        writeConfigIni("Pixel_6_API_34", "image.sysdir.1=system-images/android-34/google_apis/arm64-v8a/\n")

        val output = """
            Available Android Virtual Devices:
                Name: Pixel_6_API_34
              Device: pixel_6
        """.trimIndent()

        val result = DeviceService.parseAvdInfo(output, avdHome)

        assertThat(result.named("Pixel_6_API_34")).isEqualTo(AvdInfo(name = "Pixel_6_API_34", model = "pixel_6", os = ""))
    }

    @Test
    fun `missing Device field results in empty model string`() {
        writeConfigIni("Pixel_6_API_34", "image.sysdir.1=system-images/android-34/google_apis/arm64-v8a/\n")

        val output = """
            Available Android Virtual Devices:
                Name: Pixel_6_API_34
                Path: /home/user/.android/avd/Pixel_6_API_34.avd
        """.trimIndent()

        val result = DeviceService.parseAvdInfo(output, avdHome)

        assertThat(result.named("Pixel_6_API_34")).isEqualTo(AvdInfo(name = "Pixel_6_API_34", model = "", os = "android-34"))
    }

    // -------------------------------------------------------------------------
    // OS / config.ini edge cases
    // -------------------------------------------------------------------------

    @Test
    fun `config ini missing results in empty OS string`() {
        // No config.ini written for this AVD

        val output = """
            Available Android Virtual Devices:
                Name: No_Config_AVD
              Device: pixel_6 (Google Pixel 6)
                Path: /home/user/.android/avd/No_Config_AVD.avd
        """.trimIndent()

        val result = DeviceService.parseAvdInfo(output, avdHome)

        assertThat(result.named("No_Config_AVD")).isEqualTo(AvdInfo(name = "No_Config_AVD", model = "pixel_6", os = ""))
    }

    @Test
    fun `config ini present but lacks image sysdir line results in empty OS string`() {
        writeConfigIni("AVD_No_Sysdir", "hw.ramSize=2048\ntarget=android-34\n")

        // Path: line triggers the config.ini fallback branch
        val output = """
            Available Android Virtual Devices:
                Name: AVD_No_Sysdir
              Device: pixel_6 (Google Pixel 6)
                Path: /home/user/.android/avd/AVD_No_Sysdir.avd
        """.trimIndent()

        val result = DeviceService.parseAvdInfo(output, avdHome)

        assertThat(result.named("AVD_No_Sysdir")).isEqualTo(AvdInfo(name = "AVD_No_Sysdir", model = "pixel_6", os = ""))
    }

    @Test
    fun `config ini sysdir with no android-XX segment results in empty OS string`() {
        writeConfigIni("AVD_Weird_Sysdir", "image.sysdir.1=custom-images/vendor-image/arm64-v8a/\n")

        // Path: line triggers the config.ini fallback branch
        val output = """
            Available Android Virtual Devices:
                Name: AVD_Weird_Sysdir
              Device: pixel_6 (Google Pixel 6)
                Path: /home/user/.android/avd/AVD_Weird_Sysdir.avd
        """.trimIndent()

        val result = DeviceService.parseAvdInfo(output, avdHome)

        assertThat(result.named("AVD_Weird_Sysdir")).isEqualTo(AvdInfo(name = "AVD_Weird_Sysdir", model = "pixel_6", os = ""))
    }

    @Test
    fun `config ini sysdir with android-XX at a non-first segment is still found`() {
        writeConfigIni(
            "Nested_AVD",
            "image.sysdir.1=sdk/system-images/android-30/google_apis_playstore/x86/\n"
        )

        // A non-Name/non-Device line (e.g. Path:) is required to trigger the
        // config.ini fallback branch — it only fires on "other" lines.
        val output = """
            Available Android Virtual Devices:
                Name: Nested_AVD
              Device: pixel_4 (Google Pixel 4)
                Path: /home/user/.android/avd/Nested_AVD.avd
        """.trimIndent()

        val result = DeviceService.parseAvdInfo(output, avdHome)

        assertThat(result.named("Nested_AVD")).isEqualTo(AvdInfo(name = "Nested_AVD", model = "pixel_4", os = "android-30"))
    }

    // -------------------------------------------------------------------------
    // Empty / degenerate input
    // -------------------------------------------------------------------------

    @Test
    fun `empty output returns empty list`() {
        val result = DeviceService.parseAvdInfo("", avdHome)
        assertThat(result).isEmpty()
    }

    @Test
    fun `output with no AVDs returns empty list`() {
        val output = "Available Android Virtual Devices:\n"
        val result = DeviceService.parseAvdInfo(output, avdHome)
        assertThat(result).isEmpty()
    }

    @Test
    fun `Name line with no subsequent Device or config ini still saved with empty values`() {
        // AVD block containing only a Name: line — no Device:, no config.ini
        val output = """
            Available Android Virtual Devices:
                Name: Bare_AVD
        """.trimIndent()

        val result = DeviceService.parseAvdInfo(output, avdHome)

        assertThat(result).hasSize(1)
        assertThat(result.named("Bare_AVD")).isEqualTo(AvdInfo(name = "Bare_AVD", model = "", os = ""))
    }

    @Test
    fun `a connected physical Apple TV is listed as a tvOS device`(@TempDir dir: File) {
        val devicectlOutput = File(dir, "devicectl.json").apply {
            writeText(
                """
                {
                  "result" : {
                    "devices" : [
                      {
                        "connectionProperties" : { "transportType" : "localNetwork", "tunnelIPAddress" : "fd5a:76a0:eeec::1", "tunnelState" : "connected" },
                        "deviceProperties" : { "name" : "Bedroom", "osVersionNumber" : "18.6" },
                        "hardwareProperties" : { "deviceType" : "appleTV", "platform" : "tvOS", "productType" : "AppleTV14,1", "udid" : "00008110-000A1B2C3D4E5F60" },
                        "identifier" : "1D7A0F5C-63B2-5E1A-9C44-2B8E0D6F7A31"
                      },
                      {
                        "connectionProperties" : { "transportType" : "wired", "tunnelIPAddress" : "fdcd:3155:df16::1", "tunnelState" : "connected" },
                        "deviceProperties" : { "name" : "iPhone", "osVersionNumber" : "18.4.1" },
                        "hardwareProperties" : { "deviceType" : "iPhone", "platform" : "iOS", "productType" : "iPhone15,2", "udid" : "00008120-0014485601E3C01E" },
                        "identifier" : "6986451F-A2FF-48DE-A70E-45E06E1F1446"
                      }
                    ]
                  }
                }
                """.trimIndent()
            )
        }
        val deviceCtlProcess = mockk<DeviceCtlProcess>()
        every { deviceCtlProcess.devicectlDevicesOutput() } returns devicectlOutput

        val (appleTV, iPhone) = DeviceService.listAppleConnectedDevices(LocalIOSDevice(deviceCtlProcess))

        assertThat(appleTV.platform).isEqualTo(Platform.TVOS)
        assertThat(appleTV.deviceSpec.platform).isEqualTo(Platform.TVOS)
        assertThat(appleTV.deviceType).isEqualTo(Device.DeviceType.REAL)
        assertThat(iPhone.platform).isEqualTo(Platform.IOS)
        assertThat(iPhone.deviceSpec.platform).isEqualTo(Platform.IOS)
    }

    @Test
    fun `a running simulator that devicectl lists is not a physical device`(@TempDir dir: File) {
        val devicectlOutput = File(dir, "devicectl.json").apply {
            writeText(
                """
                {
                  "result" : {
                    "devices" : [
                      {
                        "connectionProperties" : { "transportType" : "localNetwork", "tunnelIPAddress" : "fd5a:76a0:eeec::1", "tunnelState" : "connected" },
                        "deviceProperties" : { "name" : "Bedroom", "osVersionNumber" : "18.6" },
                        "hardwareProperties" : { "deviceType" : "appleTV", "platform" : "tvOS", "reality" : "physical", "udid" : "00008110-000A1B2C3D4E5F60" },
                        "identifier" : "1D7A0F5C-63B2-5E1A-9C44-2B8E0D6F7A31"
                      },
                      {
                        "connectionProperties" : { "transportType" : "sameMachine", "tunnelState" : "connected" },
                        "deviceProperties" : { "name" : "Apple TV 4K (3rd generation)", "osVersionNumber" : "26.1" },
                        "hardwareProperties" : { "deviceType" : "appleTV", "platform" : "tvOS", "reality" : "simulated", "udid" : "1FC2FD96-9572-45FB-9F6F-47A288608705" },
                        "identifier" : "1FC2FD96-9572-45FB-9F6F-47A288608705"
                      }
                    ]
                  }
                }
                """.trimIndent()
            )
        }
        val deviceCtlProcess = mockk<DeviceCtlProcess>()
        every { deviceCtlProcess.devicectlDevicesOutput() } returns devicectlOutput

        val devices = DeviceService.listAppleConnectedDevices(LocalIOSDevice(deviceCtlProcess))

        assertThat(devices.map { it.instanceId }).containsExactly("00008110-000A1B2C3D4E5F60")
    }

}
