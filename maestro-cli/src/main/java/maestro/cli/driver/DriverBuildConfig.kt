package maestro.cli.driver

import maestro.cli.api.CliVersion
import util.ApplePlatform

data class DriverBuildConfig(
    val teamId: String,
    val sourceCodePath: String = "driver/ios",
    val sourceCodeRoot: String = System.getProperty("user.home"),
    val destination: String = "generic/platform=iphoneos",
    val architectures: String = "arm64",
    val configuration: String = "Debug",
    val cliVersion: CliVersion?,
    val platform: ApplePlatform = ApplePlatform.IOS,
)