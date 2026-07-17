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
import maestro.device.CapturedDeviceArtifact
import maestro.device.DeviceOrientation
import maestro.device.Platform
import maestro.utils.ScreenshotUtils
import maestro.vega.VegaAutomationClient
import maestro.vega.VegaCli
import maestro.vega.VegaDeviceConnection
import maestro.vega.VegaInput
import maestro.vega.VegaPageSourceParser
import okio.Sink
import okio.buffer
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Maestro driver for Amazon Vega (Fire TV) devices. Vega is a Linux/React Native OS
 * (not Android), reached through Amazon's `vega`/`vda` CLI:
 *  - view hierarchy from the on-device automation toolkit (JSON-RPC on port 8383),
 *  - input via the stock `inputd-cli` (D-pad `button_press`, `touch`, `swipe`, `send_text`),
 *  - screenshots via `gwsi-tool-screenshooter`,
 *  - app lifecycle via `vega device …`.
 *
 * v1 targets the Vega Virtual Device (VVD); physical Fire TV Sticks reuse the same path.
 */
class VegaDriver(
    private val connection: VegaDeviceConnection,
    private val cli: VegaCli = connection.cli,
    private val automationClient: VegaAutomationClient = VegaAutomationClient(connection),
    private val input: VegaInput = VegaInput(connection),
) : Driver {

    private var screenSize: Pair<Int, Int>? = null

    override fun name(): String = "Vega Device (${connection.serial})"

    override fun open() {
        connection.ensureToolkitEnabled()
    }

    override fun close() {
        // Nothing to tear down: the CLI is stateless and owns no long-lived session.
    }

    override fun deviceInfo(): DeviceInfo {
        val (width, height) = resolveScreenSize()
        return DeviceInfo(
            platform = Platform.VEGA,
            widthPixels = width,
            heightPixels = height,
            widthGrid = width,
            heightGrid = height,
        )
    }

    private fun resolveScreenSize(): Pair<Int, Int> {
        return screenSize ?: (runCatching { connection.screenSize() }.getOrNull() ?: (1920 to 1080))
            .also { screenSize = it }
    }

    override fun launchApp(appId: String, launchArguments: Map<String, Any>) {
        // Maestro's launchApp is a cold launch: terminate first so a singleton app
        // restarts from its initial state instead of just being brought to the front.
        cli.terminateApp(appId)
        // The toolkit reads the enable flag at launch, so set it before (re)launching.
        connection.ensureToolkitEnabled()
        cli.launchApp(appId)
    }

    override fun stopApp(appId: String) {
        cli.terminateApp(appId)
    }

    override fun killApp(appId: String) {
        stopApp(appId)
    }

    override fun clearAppState(appId: String) {
        // No supported "clear data" primitive in v1; reinstall is the documented reset.
        logger.warn("clearAppState is not supported on Vega; reinstall the app to reset state")
    }

    override fun clearKeychain() {
        // Not applicable on Vega.
    }

    override fun tap(point: Point) = input.tap(point)

    override fun longPress(point: Point) = input.longPress(point)

    override fun pressKey(code: KeyCode) = input.pressKey(code)

    override fun contentDescriptor(excludeKeyboardElements: Boolean): TreeNode {
        val xml = automationClient.getPageSource()
        return VegaPageSourceParser.parse(xml)
    }

    override fun scrollVertical() {
        swipe(SwipeDirection.UP, 400)
    }

    override fun isKeyboardVisible(): Boolean {
        // No reliable keyboard-visibility signal on Vega; text input targets the focused field.
        return false
    }

    override fun swipe(start: Point, end: Point, durationMs: Long) = input.swipe(start, end, durationMs)

    override fun swipe(swipeDirection: SwipeDirection, durationMs: Long) {
        val (width, height) = resolveScreenSize()
        val cx = width / 2
        val cy = height / 2
        val dx = width / 4
        val dy = height / 4
        val (start, end) = when (swipeDirection) {
            SwipeDirection.UP -> Point(cx, cy + dy) to Point(cx, cy - dy)
            SwipeDirection.DOWN -> Point(cx, cy - dy) to Point(cx, cy + dy)
            SwipeDirection.LEFT -> Point(cx + dx, cy) to Point(cx - dx, cy)
            SwipeDirection.RIGHT -> Point(cx - dx, cy) to Point(cx + dx, cy)
        }
        input.swipe(start, end, durationMs)
    }

    override fun swipe(elementPoint: Point, direction: SwipeDirection, durationMs: Long) {
        val (width, height) = resolveScreenSize()
        val dx = width / 4
        val dy = height / 4
        val end = when (direction) {
            SwipeDirection.UP -> Point(elementPoint.x, elementPoint.y - dy)
            SwipeDirection.DOWN -> Point(elementPoint.x, elementPoint.y + dy)
            SwipeDirection.LEFT -> Point(elementPoint.x - dx, elementPoint.y)
            SwipeDirection.RIGHT -> Point(elementPoint.x + dx, elementPoint.y)
        }
        input.swipe(elementPoint, end, durationMs)
    }

    override fun backPress() = input.pressKey(KeyCode.BACK)

    override fun inputText(text: String) = input.inputText(text)

    override fun openLink(link: String, appId: String?, autoVerify: Boolean, browser: Boolean) {
        throw UnsupportedOperationException("openLink is not supported on Vega")
    }

    override fun hideKeyboard() {
        // Dismiss any on-screen keyboard with a back press; harmless otherwise.
        input.pressKey(KeyCode.BACK)
    }

    override fun takeScreenshot(out: Sink, compressed: Boolean) {
        val bytes = automationClient.getScreenshot()
        out.buffer().use { sink -> sink.write(bytes) }
    }

    override fun startScreenRecording(out: Sink): ScreenRecording {
        throw UnsupportedOperationException("Screen recording is not supported on Vega")
    }

    override fun setLocation(latitude: Double, longitude: Double) {
        throw UnsupportedOperationException("setLocation is not supported on Vega")
    }

    override fun setOrientation(orientation: DeviceOrientation) {
        // no-op: Vega/Fire TV is landscape-only.
    }

    override fun eraseText(charactersToErase: Int) = input.eraseText(charactersToErase)

    override fun setProxy(host: String, port: Int) {
        throw UnsupportedOperationException("setProxy is not supported on Vega")
    }

    override fun resetProxy() {
        // Nothing to reset.
    }

    override fun isShutdown(): Boolean = false

    override fun waitUntilScreenIsStatic(timeoutMs: Long): Boolean {
        return ScreenshotUtils.waitUntilScreenIsStatic(timeoutMs, SCREENSHOT_DIFF_THRESHOLD, this)
    }

    override fun waitForAppToSettle(initialHierarchy: ViewHierarchy?, appId: String?, timeoutMs: Int?): ViewHierarchy? {
        return ScreenshotUtils.waitForAppToSettle(initialHierarchy, this, timeoutMs)
    }

    override fun capabilities(): List<Capability> = emptyList()

    override fun setPermissions(appId: String, permissions: Map<String, String>) {
        // no-op: Vega has no runtime-permission grant primitive in v1.
    }

    override fun addMedia(mediaFiles: List<File>) {
        throw UnsupportedOperationException("addMedia is not supported on Vega")
    }

    override fun isAirplaneModeEnabled(): Boolean = false

    override fun setAirplaneMode(enabled: Boolean) {
        throw UnsupportedOperationException("Airplane mode is not supported on Vega")
    }

    companion object {
        private val logger = LoggerFactory.getLogger(VegaDriver::class.java)
        private const val SCREENSHOT_DIFF_THRESHOLD = 0.005
    }
}
