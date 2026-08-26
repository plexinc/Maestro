package maestro.drivers

import maestro.Capability
import maestro.DeviceInfo
import maestro.Driver
import maestro.KeyCode
import maestro.Point
import maestro.ScreenRecording
import maestro.SwipeDirection
import maestro.TreeNode
import maestro.ViewHierarchy
import maestro.device.DeviceOrientation
import maestro.device.Platform
import maestro.roku.RokuAppUIParser
import maestro.roku.RokuEcpClient
import maestro.roku.RokuKeyMapping
import okio.Sink
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.math.abs

/**
 * Maestro driver for Roku devices, reached over the network via the External Control
 * Protocol (ECP — an HTTP REST API on device port 8060). Roku has no on-device driver
 * process; every operation is a stateless ECP call:
 *  - view hierarchy from `/query/app-ui` (SceneGraph XML, dev-mode channels only),
 *  - input via `/keypress/<key>` (D-pad focus navigation — there is no touch),
 *  - text via character-by-character `LIT_` keypresses,
 *  - screenshots via the dev web server's `/plugin_inspect` (digest auth with the
 *    developer-mode password, `MAESTRO_ROKU_PASSWORD`),
 *  - app lifecycle via `/launch/<channelId>`.
 *
 * Requires developer mode and "Control by mobile apps" network access set to Permissive.
 */
class RokuDriver(
    private val host: String,
    private val password: String = System.getenv("MAESTRO_ROKU_PASSWORD") ?: "",
    private val keypressDelayMs: Long = 100,
) : Driver {

    private lateinit var ecpClient: RokuEcpClient
    private var deviceInfo: RokuEcpClient.RokuDeviceInfo? = null
    private var shutdown = false

    override fun name(): String = "Roku Device ($host)"

    override fun open() {
        ecpClient = RokuEcpClient(
            host = host,
            password = password,
            keypressDelayMs = keypressDelayMs,
        )

        if (!ecpClient.isReachable()) {
            throw IllegalStateException(
                "Cannot connect to Roku device at $host. " +
                    "Ensure the device is on the same network and developer mode is enabled."
            )
        }

        deviceInfo = ecpClient.getDeviceInfo()
        logger.info("Connected to Roku device: ${deviceInfo?.friendlyName} (${deviceInfo?.modelName})")
        shutdown = false
    }

    override fun close() {
        if (::ecpClient.isInitialized) {
            ecpClient.close()
        }
        shutdown = true
    }

    override fun deviceInfo(): DeviceInfo {
        val info = deviceInfo ?: ecpClient.getDeviceInfo()
            ?: throw IllegalStateException("Failed to get Roku device info")

        return DeviceInfo(
            platform = Platform.ROKU,
            widthPixels = info.widthPixels,
            heightPixels = info.heightPixels,
            widthGrid = info.widthPixels,
            heightGrid = info.heightPixels,
        )
    }

    override fun launchApp(appId: String, launchArguments: Map<String, Any>) {
        // Maestro's launchApp is a cold launch. ECP has no terminate endpoint and the
        // launch call resumes an already-running channel with its state intact, so exit
        // to the home screen first to force a restart from the channel's initial state.
        if (ecpClient.isActiveApp(appId)) {
            ecpClient.sendKeypress("Home")
            val exitDeadline = System.currentTimeMillis() + EXIT_TIMEOUT_MS
            while (ecpClient.isActiveApp(appId) && System.currentTimeMillis() < exitDeadline) {
                Thread.sleep(200)
            }
        }

        val stringParams = launchArguments.mapValues { it.value.toString() }
        ecpClient.launchChannel(appId, stringParams)

        // Wait for the channel to become the active app
        val startTime = System.currentTimeMillis()
        var appActive = false
        while (System.currentTimeMillis() - startTime < LAUNCH_TIMEOUT_MS) {
            if (ecpClient.isActiveApp(appId)) {
                appActive = true
                break
            }
            Thread.sleep(200)
        }
        if (!appActive) {
            // Returning here would leave the flow asserting against whatever screen the
            // device happens to be showing, failing later with an unrelated message.
            throw IllegalStateException(
                "Roku channel $appId did not become the active app within ${LAUNCH_TIMEOUT_MS}ms. " +
                    "Check the channel id (a sideloaded channel is `dev`) and that the channel is installed."
            )
        }

        // Wait for the app UI to render (a SceneGraph screen with child nodes)
        while (System.currentTimeMillis() - startTime < LAUNCH_TIMEOUT_MS) {
            val doc = ecpClient.getAppUI()
            if (doc != null) {
                val screens = doc.documentElement?.getElementsByTagName("screen")
                val screen = if (screens != null && screens.length > 0) {
                    screens.item(0) as? org.w3c.dom.Element
                } else null
                if (screen != null && screen.childNodes.length > 0) {
                    logger.info("App $appId UI is ready")
                    return
                }
            }
            Thread.sleep(500)
        }
        logger.warn("App $appId launched but UI may not be fully rendered")
    }

    override fun stopApp(appId: String) {
        // Roku has no "stop app" ECP endpoint — pressing Home exits the channel
        ecpClient.sendKeypress("Home")
    }

    override fun killApp(appId: String) {
        stopApp(appId)
    }

    override fun clearAppState(appId: String) {
        // No supported "clear data" primitive; re-sideloading the channel is the documented reset
        logger.warn("clearAppState is not supported on Roku; reinstall the channel to reset state")
    }

    override fun clearKeychain() {
        // Not applicable on Roku.
    }

    override fun tap(point: Point) {
        // Roku is D-pad based; there is no coordinate tap. Select activates the focused element.
        logger.debug("tap() called on Roku — sending Select keypress (Roku is D-pad based)")
        ecpClient.sendKeypress("Select")
    }

    override fun longPress(point: Point) {
        // Some Roku apps respond to a held Select key.
        ecpClient.sendKeyDown("Select")
        Thread.sleep(LONG_PRESS_MS)
        ecpClient.sendKeyUp("Select")
    }

    override fun pressKey(code: KeyCode) {
        val ecpKey = RokuKeyMapping.toEcpKey(code)
            ?: throw UnsupportedOperationException("KeyCode $code is not supported on Roku")
        ecpClient.sendKeypress(ecpKey)
    }

    override fun contentDescriptor(excludeKeyboardElements: Boolean): TreeNode {
        val document = ecpClient.getAppUI()
        if (document == null) {
            logger.warn(
                "Failed to get app UI from Roku device at $host. " +
                    "View hierarchy will be empty — assertVisible and other element lookups will fail. " +
                    "Check ECP network access (Settings > System > Advanced > Control by mobile apps > Permissive)."
            )
            return TreeNode(
                attributes = mutableMapOf(),
                children = emptyList(),
                clickable = false,
                enabled = true,
                focused = false,
                checked = null,
                selected = null,
            )
        }

        val info = deviceInfo
        return if (info != null) {
            RokuAppUIParser.parse(document, info.widthPixels, info.heightPixels)
        } else {
            RokuAppUIParser.parse(document)
        }
    }

    override fun scrollVertical() {
        // Simulate scroll by moving focus down
        repeat(SWIPE_KEY_PRESSES) {
            ecpClient.sendKeypress("Down")
        }
    }

    override fun isKeyboardVisible(): Boolean {
        // No reliable keyboard-visibility signal; text input targets the focused field via LIT_.
        return false
    }

    override fun swipe(start: Point, end: Point, durationMs: Long) {
        // Translate swipe direction into D-pad presses
        val dx = end.x - start.x
        val dy = end.y - start.y

        val key = when {
            abs(dy) > abs(dx) && dy < 0 -> "Up"
            abs(dy) > abs(dx) && dy >= 0 -> "Down"
            dx < 0 -> "Left"
            else -> "Right"
        }

        repeat(SWIPE_KEY_PRESSES) {
            ecpClient.sendKeypress(key)
        }
    }

    override fun swipe(swipeDirection: SwipeDirection, durationMs: Long) {
        val key = when (swipeDirection) {
            SwipeDirection.UP -> "Up"
            SwipeDirection.DOWN -> "Down"
            SwipeDirection.LEFT -> "Left"
            SwipeDirection.RIGHT -> "Right"
        }
        repeat(SWIPE_KEY_PRESSES) {
            ecpClient.sendKeypress(key)
        }
    }

    override fun swipe(elementPoint: Point, direction: SwipeDirection, durationMs: Long) {
        swipe(direction, durationMs)
    }

    override fun backPress() {
        ecpClient.sendKeypress("Back")
    }

    override fun inputText(text: String) {
        ecpClient.sendText(text)
    }

    override fun openLink(link: String, appId: String?, autoVerify: Boolean, browser: Boolean) {
        // Roku deep links are launch parameters on the channel
        if (appId != null) {
            ecpClient.launchChannel(appId, mapOf("contentId" to link))
        } else {
            throw UnsupportedOperationException("openLink without appId is not supported on Roku")
        }
    }

    override fun hideKeyboard() {
        ecpClient.sendKeypress("Back")
    }

    override fun takeScreenshot(out: Sink, compressed: Boolean) {
        ecpClient.takeScreenshot(out)
    }

    override fun startScreenRecording(out: Sink): ScreenRecording {
        throw UnsupportedOperationException("Screen recording is not supported on Roku")
    }

    override fun setLocation(latitude: Double, longitude: Double) {
        throw UnsupportedOperationException("setLocation is not supported on Roku")
    }

    override fun setOrientation(orientation: DeviceOrientation) {
        // no-op: TVs don't rotate.
    }

    // Roku exposes no theme state over ECP; channels draw their own UI.
    override fun isDarkModeEnabled(): Boolean = false

    override fun setDarkMode(enabled: Boolean) {
        throw UnsupportedOperationException("setDarkMode is not supported on Roku")
    }

    override fun eraseText(charactersToErase: Int) {
        repeat(charactersToErase) {
            ecpClient.sendKeypress("Backspace")
        }
    }

    override fun setProxy(host: String, port: Int) {
        throw UnsupportedOperationException("setProxy is not supported on Roku")
    }

    override fun resetProxy() {
        // Nothing to reset.
    }

    override fun isShutdown(): Boolean = shutdown

    override fun waitUntilScreenIsStatic(timeoutMs: Long): Boolean {
        // Screenshot diffing needs the dev password and is slow on Roku, so poll
        // /query/app-ui instead: when the XML stops changing, the screen is static.
        val startTime = System.currentTimeMillis()
        var previousXml: String? = null

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val currentXml = ecpClient.getAppUIRaw()
            if (currentXml != null && currentXml == previousXml) {
                return true
            }
            previousXml = currentXml
            Thread.sleep(SCREEN_POLL_INTERVAL_MS)
        }
        logger.debug("Screen did not become static within ${timeoutMs}ms")
        return false
    }

    override fun waitForAppToSettle(
        initialHierarchy: ViewHierarchy?,
        appId: String?,
        timeoutMs: Int?,
    ): ViewHierarchy? {
        val timeout = timeoutMs?.toLong() ?: SETTLE_TIMEOUT_MS
        waitUntilScreenIsStatic(minOf(timeout, SETTLE_TIMEOUT_MS))
        return null
    }

    override fun capabilities(): List<Capability> = emptyList()

    override fun setPermissions(appId: String, permissions: Map<String, String>) {
        // no-op: Roku has no runtime-permission grant primitive.
    }

    override fun addMedia(mediaFiles: List<File>) {
        throw UnsupportedOperationException("addMedia is not supported on Roku")
    }

    override fun isAirplaneModeEnabled(): Boolean = false

    override fun setAirplaneMode(enabled: Boolean) {
        throw UnsupportedOperationException("Airplane mode is not supported on Roku")
    }

    companion object {
        private val logger = LoggerFactory.getLogger(RokuDriver::class.java)
        private const val LAUNCH_TIMEOUT_MS = 10_000L
        private const val EXIT_TIMEOUT_MS = 5_000L
        private const val LONG_PRESS_MS = 1_000L
        private const val SETTLE_TIMEOUT_MS = 3_000L
        private const val SCREEN_POLL_INTERVAL_MS = 300L
        private const val SWIPE_KEY_PRESSES = 5
    }
}
