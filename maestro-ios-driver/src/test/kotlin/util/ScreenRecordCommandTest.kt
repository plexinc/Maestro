package util

import com.google.common.truth.Truth.assertThat
import maestro.utils.TempFileHandler
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/** Covers which display `simctl io recordVideo` is pointed at: tvOS only has an external one. */
class ScreenRecordCommandTest {

    private val simulatorUtils = LocalSimulatorUtils(TempFileHandler())

    @Test
    fun `tvOS records the external display`() {
        assertThat(simulatorUtils.displayEnvironment(isTvOS = true))
            .containsExactly("RECORDING_DISPLAY", "external")
        assertThat(recordVideoArgs(isTvOS = true)).contains("--display external")
    }

    @Test
    fun `iOS keeps simctl's default display`() {
        assertThat(simulatorUtils.displayEnvironment(isTvOS = false)).isEmpty()
        assertThat(recordVideoArgs(isTvOS = false)).doesNotContain("--display")
    }

    /** Runs the real screenrecord.sh against a stub xcrun and returns the command line it saw. */
    private fun recordVideoArgs(isTvOS: Boolean): String {
        val script = LocalSimulatorUtils::class.java.getResourceAsStream("/screenrecord.sh")!!
            .bufferedReader().readText()
        val dir = Files.createTempDirectory("screenrecord").toFile()
        val argsFile = File(dir, "args.txt")
        File(dir, "xcrun").apply {
            writeText("#!/bin/bash\necho \"\$@\" > '${argsFile.path}'\nexec sleep 60\n")
            setExecutable(true)
        }

        val builder = ProcessBuilder("bash", "-c", script).redirectInput(ProcessBuilder.Redirect.PIPE)
        builder.environment().apply {
            put("PATH", "${dir.path}:${get("PATH")}")
            put("DEVICE_ID", "device-id")
            put("RECORDING_PATH", File(dir, "recording.mov").path)
            putAll(simulatorUtils.displayEnvironment(isTvOS))
        }
        val process = builder.redirectErrorStream(true).start()
        try {
            assertThat(process.inputStream.bufferedReader().readLine()).isEqualTo("RECORDING_STARTED")
            return argsFile.readText().trim()
        } finally {
            simulatorUtils.stopScreenRecording(
                LocalSimulatorUtils.ScreenRecording(process, File(dir, "recording.mov")),
                timeout = java.time.Duration.ofSeconds(10),
            )
            dir.deleteRecursively()
        }
    }
}
