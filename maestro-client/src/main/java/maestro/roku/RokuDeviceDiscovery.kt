package maestro.roku

import org.slf4j.LoggerFactory
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI

/**
 * Discovers Roku devices on the local network.
 *
 * Two discovery methods, both best-effort:
 * 1. `MAESTRO_ROKU_HOST` — a manually pinned device IP/hostname (primary; always checked).
 * 2. SSDP multicast (`M-SEARCH` with `ST: roku:ecp`) — opt-in via `MAESTRO_ROKU_DISCOVERY=true`,
 *    because the scan multicasts on the LAN and adds ~1s to every device listing.
 */
object RokuDeviceDiscovery {

    private val logger = LoggerFactory.getLogger(RokuDeviceDiscovery::class.java)

    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val SSDP_TIMEOUT_MS = 1000
    private const val PROBE_TIMEOUT_MS = 500

    data class DiscoveredDevice(
        val host: String,
        val modelName: String,
        val friendlyName: String,
        val serialNumber: String,
        val softwareVersion: String,
    )

    /** All Roku devices reachable right now: the pinned env-var host plus any SSDP hits. */
    fun discoverDevices(): List<DiscoveredDevice> {
        val hosts = linkedSetOf<String>()

        System.getenv("MAESTRO_ROKU_HOST")?.takeIf { it.isNotBlank() }?.let { hosts.add(it) }

        if (System.getenv("MAESTRO_ROKU_DISCOVERY")?.lowercase() in setOf("1", "true", "yes")) {
            hosts.addAll(ssdpScan())
        }

        return hosts.mapNotNull { host -> describe(host) }
    }

    /** Resolve a host into a described device, or null (with a warning) if unreachable. */
    private fun describe(host: String): DiscoveredDevice? {
        if (!isPortOpen(host, RokuEcpClient.DEFAULT_ECP_PORT)) {
            logger.warn("Roku device at $host is not reachable on port ${RokuEcpClient.DEFAULT_ECP_PORT}")
            return null
        }
        val client = RokuEcpClient(host = host)
        return try {
            val info = client.getDeviceInfo()
            DiscoveredDevice(
                host = host,
                modelName = info?.modelName ?: "Roku Device",
                friendlyName = info?.friendlyName ?: host,
                serialNumber = info?.serialNumber ?: "",
                softwareVersion = info?.softwareVersion ?: "",
            )
        } finally {
            client.close()
        }
    }

    /** Quick TCP probe so an offline pinned host fails in ~500ms, not a full HTTP timeout. */
    private fun isPortOpen(host: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /** SSDP M-SEARCH for `roku:ecp` targets; returns the responding hosts. */
    private fun ssdpScan(): Set<String> {
        val request = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("ST: roku:ecp\r\n")
            append("MX: 1\r\n")
            append("\r\n")
        }.toByteArray()

        val hosts = linkedSetOf<String>()
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = SSDP_TIMEOUT_MS
                socket.send(DatagramPacket(request, request.size, InetAddress.getByName(SSDP_ADDRESS), SSDP_PORT))

                val buffer = ByteArray(2048)
                val deadline = System.currentTimeMillis() + SSDP_TIMEOUT_MS
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        break
                    }
                    val response = String(packet.data, 0, packet.length)
                    parseSsdpLocation(response)?.let { hosts.add(it) }
                }
            }
        } catch (e: Exception) {
            logger.warn("SSDP scan for Roku devices failed", e)
        }
        return hosts
    }

    /** Extract the device host from an SSDP response's `LOCATION: http://<ip>:8060/` header. */
    internal fun parseSsdpLocation(response: String): String? {
        val location = response.lineSequence()
            .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
            ?.substringAfter(":")
            ?.trim()
            ?: return null
        return try {
            URI(location).host
        } catch (e: Exception) {
            null
        }
    }
}
