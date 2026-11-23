package util

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

enum class IOSDeviceType {
    REAL,
    SIMULATOR
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class DeviceCtlResponse(
    val result: Result
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Result(
        val devices: List<Device>
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Device(
        val identifier: String,
        val deviceProperties: DeviceProperties?,
        val hardwareProperties: HardwareProperties?,
        val connectionProperties: ConnectionProperties,
    ) {
        val platform: ApplePlatform
            get() = if (hardwareProperties?.platform == "tvOS") ApplePlatform.TVOS else ApplePlatform.IOS

        fun driverAddress(): String? {
            if (platform != ApplePlatform.TVOS) return null
            return connectionProperties.tunnelIPAddress
                ?: error("No CoreDevice tunnel to Apple TV ${hardwareProperties?.udid}: `xcrun devicectl list devices` must show it as connected")
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ConnectionProperties(
        val tunnelState: String,
        val tunnelIPAddress: String?,
    ) {
        companion object {
            const val CONNECTED  = "connected"
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class DeviceProperties(
        val name: String?,
        val osVersionNumber: String?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class HardwareProperties(
        val udid: String?,
        val platform: String?,
    )
}
