package util

import java.nio.file.Path

enum class ApplePlatform(
    sdk: String,
    val scheme: String,
    private val destinationPlatform: String,
    val simulatorDriverDirectory: String,
) {
    IOS(
        sdk = "iphoneos",
        scheme = "maestro-driver-ios",
        destinationPlatform = "iOS",
        simulatorDriverDirectory = "driver-iPhoneSimulator",
    ),
    TVOS(
        sdk = "appletvos",
        scheme = "maestro-driver-tvos",
        destinationPlatform = "tvOS",
        simulatorDriverDirectory = "driver-appletvSimulator",
    );

    val buildDirectory = "maestro-$sdk-driver-build"
    val derivedDataPath = "driver-$sdk"
    val hostZip = "$scheme.zip"
    val runnerZip = "${scheme}UITests-Runner.zip"
    val runnerAppName = "${scheme}UITests-Runner.app"

    fun destination(deviceId: String) = "platform=$destinationPlatform,id=$deviceId"

    fun productsDirectory(root: Path): Path =
        root.resolve(buildDirectory).resolve(derivedDataPath).resolve("Build").resolve("Products")
}
