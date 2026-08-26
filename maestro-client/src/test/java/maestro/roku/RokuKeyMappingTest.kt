package maestro.roku

import com.google.common.truth.Truth.assertThat
import maestro.KeyCode
import maestro.SwipeDirection
import org.junit.jupiter.api.Test

internal class RokuKeyMappingTest {

    @Test
    fun `maps the D-pad and activation keys`() {
        assertThat(RokuKeyMapping.toEcpKey(KeyCode.REMOTE_UP)).isEqualTo("Up")
        assertThat(RokuKeyMapping.toEcpKey(KeyCode.REMOTE_DOWN)).isEqualTo("Down")
        assertThat(RokuKeyMapping.toEcpKey(KeyCode.REMOTE_LEFT)).isEqualTo("Left")
        assertThat(RokuKeyMapping.toEcpKey(KeyCode.REMOTE_RIGHT)).isEqualTo("Right")
        assertThat(RokuKeyMapping.toEcpKey(KeyCode.REMOTE_CENTER)).isEqualTo("Select")
        assertThat(RokuKeyMapping.toEcpKey(KeyCode.ENTER)).isEqualTo("Select")
        assertThat(RokuKeyMapping.toEcpKey(KeyCode.BACK)).isEqualTo("Back")
        assertThat(RokuKeyMapping.toEcpKey(KeyCode.HOME)).isEqualTo("Home")
    }

    @Test
    fun `maps the Roku-specific remote keys`() {
        assertThat(RokuKeyMapping.toEcpKey(KeyCode.REMOTE_INFO)).isEqualTo("Info")
        assertThat(RokuKeyMapping.toEcpKey(KeyCode.REMOTE_REPLAY)).isEqualTo("InstantReplay")
        assertThat(RokuKeyMapping.toEcpKey(KeyCode.REMOTE_SEARCH)).isEqualTo("Search")
        assertThat(RokuKeyMapping.toEcpKey(KeyCode.REMOTE_MENU)).isEqualTo("Info")
    }

    // A swipe reveals what lies on the far side of the drag, so on a focus-driven UI
    // every direction inverts — matching Vega, where scrollVertical() is swipe(UP).
    @Test
    fun `swipe directions invert into D-pad keys`() {
        assertThat(RokuKeyMapping.toEcpKey(SwipeDirection.UP)).isEqualTo("Down")
        assertThat(RokuKeyMapping.toEcpKey(SwipeDirection.DOWN)).isEqualTo("Up")
        assertThat(RokuKeyMapping.toEcpKey(SwipeDirection.LEFT)).isEqualTo("Right")
        assertThat(RokuKeyMapping.toEcpKey(SwipeDirection.RIGHT)).isEqualTo("Left")
    }

    @Test
    fun `unsupported keys return null`() {
        assertThat(RokuKeyMapping.toEcpKey(KeyCode.TAB)).isNull()
        assertThat(RokuKeyMapping.toEcpKey(KeyCode.TV_INPUT)).isNull()
        assertThat(RokuKeyMapping.toEcpKey(KeyCode.LOCK)).isNull()
    }
}
