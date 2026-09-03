package util

import com.google.common.truth.Truth.assertThat
import maestro.utils.TempFileHandler
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.TimeUnit

class LocalSimulatorUtilsScreenRecordingTest {

    private val simulatorUtils = LocalSimulatorUtils(TempFileHandler())

    /** A recorder that ignores the stop signal and holds a child process, like a stuck simctl. */
    private fun stuckRecorder(): Process = ProcessBuilder(
        "bash", "-c", "trap '' INT TERM; sleep 300 & sleep 300",
    ).redirectInput(ProcessBuilder.Redirect.PIPE).start()

    @Test
    fun `force-terminates a recorder that ignores the stop signal instead of blocking`() {
        val process = stuckRecorder()
        val recording = LocalSimulatorUtils.ScreenRecording(process, Files.createTempFile("rec", ".mov").toFile())
        // wait for the child to exist, so the descendant kill is actually exercised
        val children = waitForDescendants(process)
        assertThat(children).isNotEmpty()

        val file = simulatorUtils.stopScreenRecording(recording, timeout = Duration.ofSeconds(1))

        assertThat(file).isEqualTo(recording.file)
        assertThat(process.isAlive).isFalse()
        assertThat(children.filter { it.isAlive }).isEmpty()
        recording.file.delete()
    }

    @Test
    fun `returns the recording normally when the recorder stops on its own`() {
        val process = ProcessBuilder("bash", "-c", "cat").redirectInput(ProcessBuilder.Redirect.PIPE).start()
        val recording = LocalSimulatorUtils.ScreenRecording(process, File("does-not-exist.mov"))

        simulatorUtils.stopScreenRecording(recording, timeout = Duration.ofSeconds(30))

        assertThat(process.isAlive).isFalse()
        assertThat(process.exitValue()).isEqualTo(0)
    }

    private fun waitForDescendants(process: Process): List<ProcessHandle> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val descendants = process.toHandle().descendants().toList()
            if (descendants.isNotEmpty()) return descendants
            Thread.sleep(50)
        }
        return emptyList()
    }
}
