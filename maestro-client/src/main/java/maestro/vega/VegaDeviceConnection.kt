package maestro.vega

import org.slf4j.LoggerFactory

/**
 * On-device operations for a single Vega target, all routed through [VegaCli].
 *
 * The automation toolkit that serves the view hierarchy (device port 8383) only
 * attaches when the flag file exists *at app launch*, so [ensureToolkitEnabled] must
 * be called before launching the app under test.
 */
class VegaDeviceConnection(
    val serial: String,
    val cli: VegaCli = VegaCli(serial),
) {
    fun shell(command: String): String = cli.shell(command)

    fun copyFrom(remotePath: String, localFile: java.io.File) = cli.copyFrom(remotePath, localFile)

    /** Idempotently create the toolkit enable flag (read at app launch). */
    fun ensureToolkitEnabled() {
        try {
            shell("touch $TOOLKIT_ENABLE_FLAG")
        } catch (e: Exception) {
            logger.warn("Failed to enable Vega automation toolkit flag", e)
        }
    }

    /**
     * Device screen size via `inputd-cli get_screen_size`. This is also the developer-mode
     * gate: with dev mode off the on-device shell service is down and no "<W> x <H>" prints.
     */
    fun screenSize(): Pair<Int, Int>? {
        val out = shell("inputd-cli get_screen_size").trim()
        val match = SCREEN_SIZE_RE.find(out) ?: return null
        val (w, h) = match.destructured
        return w.toInt() to h.toInt()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(VegaDeviceConnection::class.java)
        private const val TOOLKIT_ENABLE_FLAG = "/tmp/automation-toolkit.enable"
        private val SCREEN_SIZE_RE = Regex("(\\d+)\\s*x\\s*(\\d+)")
    }
}
