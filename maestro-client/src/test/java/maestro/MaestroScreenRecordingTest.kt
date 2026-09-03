package maestro

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import maestro.utils.ScreenRecordingUnsupported
import okio.Sink
import okio.blackholeSink
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MaestroScreenRecordingTest {

    @Test
    fun `a recorder that fails to start does not disable later recordings`() {
        var closed = false
        val recording = object : ScreenRecording {
            override fun close() {
                closed = true
            }
        }
        val driver = mockk<Driver>(relaxed = true)
        every { driver.startScreenRecording(any<Sink>()) } throws
            ScreenRecordingUnsupported("Roku") andThen recording

        val maestro = Maestro(driver)

        assertThrows<ScreenRecordingUnsupported> {
            runBlocking { maestro.startScreenRecording(blackholeSink()) }
        }
        // The failed attempt must not leave the session marked as "already recording",
        // which would silently turn every later recording into a no-op.
        runBlocking { maestro.startScreenRecording(blackholeSink()) }.close()
        io.mockk.verify(exactly = 2) { driver.startScreenRecording(any<Sink>()) }
        assertThat(closed).isTrue()
    }

    @Test
    fun `a second recording while one is in progress is a no-op`() {
        val driver = mockk<Driver>(relaxed = true)
        every { driver.startScreenRecording(any<Sink>()) } returns object : ScreenRecording {
            override fun close() = Unit
        }

        val maestro = Maestro(driver)
        val first = runBlocking { maestro.startScreenRecording(blackholeSink()) }
        val second = runBlocking { maestro.startScreenRecording(blackholeSink()) }

        assertThat(first).isNotSameInstanceAs(second)
        io.mockk.verify(exactly = 1) { driver.startScreenRecording(any<Sink>()) }
    }
}
