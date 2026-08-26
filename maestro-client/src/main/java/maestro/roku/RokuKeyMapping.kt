package maestro.roku

import maestro.KeyCode
import maestro.SwipeDirection

/**
 * Maps Maestro [KeyCode] values to Roku ECP key strings.
 * Based on the Roku External Control Protocol key names.
 * Reference: roku-test-automation ECP.ts Key enum.
 */
object RokuKeyMapping {

    private val mapping = mapOf(
        KeyCode.REMOTE_UP to "Up",
        KeyCode.REMOTE_DOWN to "Down",
        KeyCode.REMOTE_LEFT to "Left",
        KeyCode.REMOTE_RIGHT to "Right",
        KeyCode.REMOTE_CENTER to "Select",
        KeyCode.ENTER to "Select",
        KeyCode.BACK to "Back",
        KeyCode.ESCAPE to "Back",
        KeyCode.BACKSPACE to "Backspace",
        KeyCode.HOME to "Home",
        KeyCode.REMOTE_PLAY_PAUSE to "Play",
        KeyCode.REMOTE_FAST_FORWARD to "Fwd",
        KeyCode.REMOTE_REWIND to "Rev",
        KeyCode.REMOTE_STOP to "Play", // Roku uses Play as a toggle
        KeyCode.REMOTE_NEXT to "Fwd",
        KeyCode.REMOTE_PREVIOUS to "Rev",
        KeyCode.REMOTE_MENU to "Info", // The * (options) button is Roku's menu
        KeyCode.REMOTE_INFO to "Info",
        KeyCode.REMOTE_REPLAY to "InstantReplay",
        KeyCode.REMOTE_SEARCH to "Search",
        KeyCode.POWER to "PowerOff",
        KeyCode.VOLUME_UP to "VolumeUp",
        KeyCode.VOLUME_DOWN to "VolumeDown",
        KeyCode.REMOTE_BUTTON_A to "A",
        KeyCode.REMOTE_BUTTON_B to "B",
    )

    fun toEcpKey(keyCode: KeyCode): String? = mapping[keyCode]

    /**
     * D-pad key for a swipe. A swipe drags the content, so it reveals what lies on the
     * far side: swiping up brings up what is *below*, which on a focus-driven UI is a
     * move down. Every direction therefore inverts. Matches the touch platforms, where
     * `scrollVertical()` is `swipe(UP)` (`VegaDriver`), and web, where swiping up
     * increases `scrollY`.
     */
    fun toEcpKey(swipeDirection: SwipeDirection): String = when (swipeDirection) {
        SwipeDirection.UP -> "Down"
        SwipeDirection.DOWN -> "Up"
        SwipeDirection.LEFT -> "Right"
        SwipeDirection.RIGHT -> "Left"
    }

    fun supportedKeyCodes(): Set<KeyCode> = mapping.keys
}
