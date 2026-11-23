import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import util.ApplePlatform
import util.IOSDeviceType
import xcuitest.installer.Context
import xcuitest.installer.IOSBuildProductsExtractor
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class ApplePlatformBuildProductsTest {

    @Test
    fun `finds the tvOS runner in a build for a physical Apple TV`(@TempDir dir: Path) {
        val products = dir.resolve("Products").createDirectories()
        val target = dir.resolve("target").createDirectories()
        products.resolve("maestro-driver-tvos_maestro-driver-tvos_appletvos27.1-arm64.xctestrun").writeText("xctestrun")
        products.resolve("Debug-appletvos/maestro-driver-tvosUITests-Runner.app").createDirectories()
            .resolve("Info.plist").writeText("plist")

        val buildProducts = IOSBuildProductsExtractor(
            target = target,
            context = Context.CLI,
            deviceType = IOSDeviceType.REAL,
            platform = ApplePlatform.TVOS,
        ).extract(products.toString())

        assertThat(buildProducts.uiRunnerPath.name).isEqualTo("maestro-driver-tvosUITests-Runner.app")
    }

    @Test
    fun `finds the runner in each bundled simulator driver`(@TempDir dir: Path) {
        for (platform in ApplePlatform.entries) {
            val buildProducts = IOSBuildProductsExtractor(
                target = dir.resolve(platform.name).createDirectories(),
                context = Context.CLI,
                deviceType = IOSDeviceType.SIMULATOR,
                platform = platform,
            ).extract(platform.simulatorDriverDirectory)

            assertThat(buildProducts.uiRunnerPath.isDirectory).isTrue()
        }
    }
}
