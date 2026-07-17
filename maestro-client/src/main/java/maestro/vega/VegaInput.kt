package maestro.vega

import maestro.KeyCode
import maestro.Point
import org.slf4j.LoggerFactory

/**
 * Input injection for Vega via the stock on-device `inputd-cli`, run over `vega … shell`.
 *
 * `inputd-cli` drives the real remote/navigation input the Cartesian focus engine acts
 * on. It requires the device's **developer mode** to be on (otherwise the on-device
 * dev-shell service that hosts it is down and every command fails); [ensureInputAvailable]
 * probes for this and raises an actionable error.
 */
class VegaInput(
    private val connection: VegaDeviceConnection,
) {
    private var inputChecked = false

    /** Probe the input channel once; raises an actionable error if dev mode is off. */
    fun ensureInputAvailable() {
        if (inputChecked) return
        val size = try {
            connection.screenSize()
        } catch (e: Exception) {
            null
        }
        if (size == null) {
            throw VegaInputUnavailableException(
                "Vega input is unavailable: `inputd-cli` could not be reached over the device shell, " +
                    "which means the VVD's developer mode is off. Enable it (`vsm developer-mode enable`, " +
                    "e.g. via `vega device shell`) and retry."
            )
        }
        inputChecked = true
    }

    fun pressKey(code: KeyCode) {
        val keyName = KEY_CODES[code]
            ?: throw IllegalArgumentException("KeyCode $code is not supported on Vega")
        buttonPress(keyName)
    }

    fun buttonPress(keyName: String) {
        ensureInputAvailable()
        connection.shell("inputd-cli button_press $keyName")
        settle()
    }

    fun tap(point: Point) {
        ensureInputAvailable()
        connection.shell("inputd-cli touch ${point.x} ${point.y}")
        settle()
    }

    fun longPress(point: Point) {
        ensureInputAvailable()
        // Vega expresses a long press via the hold duration on a touch.
        connection.shell("inputd-cli touch ${point.x} ${point.y} --holdDuration $LONG_PRESS_MS")
        settle()
    }

    fun swipe(start: Point, end: Point, durationMs: Long) {
        ensureInputAvailable()
        connection.shell("inputd-cli swipe ${start.x} ${start.y} ${end.x} ${end.y} --interval $durationMs")
        settle()
    }

    fun inputText(text: String) {
        require(!text.contains('\n') && !text.contains('\r')) {
            "Vega keyboard text must not contain newlines"
        }
        ensureInputAvailable()
        connection.shell("inputd-cli send_text ${shellQuote(text)}")
        settle()
    }

    fun eraseText(charactersToErase: Int) {
        ensureInputAvailable()
        repeat(charactersToErase) {
            connection.shell("inputd-cli button_press KEY_BACKSPACE")
        }
        settle()
    }

    private fun settle() {
        // Give the focus engine time to keep up (CI's software renderer is slow).
        Thread.sleep(SETTLE_MS)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(VegaInput::class.java)
        private const val SETTLE_MS = 300L
        private const val LONG_PRESS_MS = 1000L

        // KeyCode -> Linux input KEY_ name accepted by `inputd-cli button_press`.
        // Verified names: select is KEY_ENTER (KEY_SELECT is a no-op), home is
        // KEY_HOMEPAGE (KEY_HOME is inert), back/escape both map to KEY_BACK.
        private val KEY_CODES: Map<KeyCode, String> = mapOf(
            KeyCode.ENTER to "KEY_ENTER",
            KeyCode.BACKSPACE to "KEY_BACKSPACE",
            KeyCode.BACK to "KEY_BACK",
            KeyCode.ESCAPE to "KEY_BACK",
            KeyCode.HOME to "KEY_HOMEPAGE",
            KeyCode.VOLUME_UP to "KEY_VOLUMEUP",
            KeyCode.VOLUME_DOWN to "KEY_VOLUMEDOWN",
            KeyCode.REMOTE_UP to "KEY_UP",
            KeyCode.REMOTE_DOWN to "KEY_DOWN",
            KeyCode.REMOTE_LEFT to "KEY_LEFT",
            KeyCode.REMOTE_RIGHT to "KEY_RIGHT",
            KeyCode.REMOTE_CENTER to "KEY_ENTER",
            KeyCode.REMOTE_PLAY_PAUSE to "KEY_PLAYPAUSE",
            KeyCode.REMOTE_STOP to "KEY_STOP",
            KeyCode.REMOTE_NEXT to "KEY_NEXTSONG",
            KeyCode.REMOTE_PREVIOUS to "KEY_PREVIOUSSONG",
            KeyCode.REMOTE_REWIND to "KEY_REWIND",
            KeyCode.REMOTE_FAST_FORWARD to "KEY_FASTFORWARD",
            KeyCode.REMOTE_MENU to "KEY_MENU",
        )

        /** Single-quote a string for a POSIX device shell, escaping embedded quotes. */
        internal fun shellQuote(value: String): String {
            return "'" + value.replace("'", "'\\''") + "'"
        }
    }
}

class VegaInputUnavailableException(message: String) : Exception(message)
