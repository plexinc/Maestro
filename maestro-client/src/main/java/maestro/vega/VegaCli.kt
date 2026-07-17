package maestro.vega

import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

/** A device reported by `vega device list`. */
data class VegaDevice(
    val serial: String,
    val description: String,
    val isVirtual: Boolean,
)

data class VegaCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val isSuccess get() = exitCode == 0
    fun output() = (stdout + "\n" + stderr).trim()
}

/**
 * Thin wrapper over Amazon's Vega developer CLI. Vega is not Android, so we drive it
 * through the SDK's own binary (`vega`/`kepler`) rather than adb — this is the only
 * supported channel and keeps the same code path working for physical Fire TVs.
 *
 * Command surface verified against Vega SDK 0.23.9106:
 *  - `vega device list` prints `Found the following device(s):` then one
 *    `<selector> : <profile> - <arch> - <os> - <hostname>` line per device; the
 *    `-d` selector is the first field (e.g. `VirtualDevice`), NOT the hostname.
 *  - on-device commands run via `vega device run-cmd -d <sel> -c '<cmd>'` (there is
 *    no `shell -c`; plain `shell` is interactive).
 *  - `-d` is a per-subcommand option, so it follows the subcommand name.
 */
class VegaCli(
    private val serial: String? = null,
    binary: String? = null,
) {
    private val binary: String = binary ?: resolveBinary()

    /** Run a raw CLI invocation and capture output. */
    fun exec(vararg args: String, timeoutSeconds: Long = 120): VegaCommandResult {
        val parts = listOf(binary) + args
        logger.info("Running Vega CLI: $parts")
        val process = ProcessBuilder(parts)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()

        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("Vega CLI command timed out: $parts")
        }
        return VegaCommandResult(process.exitValue(), out, err)
    }

    /** Build `device <subcommand> [-d serial] <rest…>` — `-d` follows the subcommand. */
    private fun deviceArgs(subcommand: String, vararg rest: String): Array<String> {
        val selector = serial?.let { listOf("-d", it) } ?: emptyList()
        return (listOf("device", subcommand) + selector + rest).toTypedArray()
    }

    private fun deviceExec(subcommand: String, vararg rest: String, timeoutSeconds: Long = 120): VegaCommandResult {
        return exec(*deviceArgs(subcommand, *rest), timeoutSeconds = timeoutSeconds)
    }

    fun listDevices(): List<VegaDevice> {
        val result = exec("device", "list")
        if (!result.isSuccess) {
            logger.warn("`vega device list` failed: ${result.output()}")
            return emptyList()
        }
        return parseDeviceList(result.stdout)
    }

    /** Run a shell command on the device and return its stdout (via `run-cmd -c`). */
    fun shell(command: String): String {
        val result = deviceExec("run-cmd", "-c", command)
        if (!result.isSuccess) {
            throw IllegalStateException("Vega run-cmd failed: ${result.output()}")
        }
        return result.stdout
    }

    /** Copy a device file to the host via `copy-from` (a file transfer, no stdout limit). */
    fun copyFrom(remotePath: String, localFile: File) {
        deviceExec("copy-from", "-s", remotePath, "-o", localFile.absolutePath).also {
            if (!it.isSuccess) throw IllegalStateException("Failed to copy $remotePath from device: ${it.output()}")
        }
    }

    fun launchApp(appId: String) {
        deviceExec("launch-app", "-a", appId).also {
            if (!it.isSuccess) throw IllegalStateException("Failed to launch $appId: ${it.output()}")
        }
    }

    fun terminateApp(appId: String) {
        deviceExec("terminate-app", "-a", appId).also {
            if (!it.isSuccess) logger.warn("Failed to terminate $appId: ${it.output()}")
        }
    }

    fun installApp(vpkgPath: String) {
        deviceExec("install-app", "-p", vpkgPath, timeoutSeconds = 300).also {
            if (!it.isSuccess) throw IllegalStateException("Failed to install $vpkgPath: ${it.output()}")
        }
    }

    fun listInstalledApps(): Set<String> {
        val result = deviceExec("installed-apps")
        if (!result.isSuccess) return emptySet()
        return result.stdout.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains('.') && !it.contains(' ') }
            .toSet()
    }

    /** Start streaming device logs into [outputFile]; caller owns the returned process. */
    fun startLogStream(outputFile: File): Process {
        return ProcessBuilder(listOf(binary) + deviceArgs("start-log-stream"))
            .redirectOutput(outputFile)
            .redirectErrorStream(true)
            .start()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(VegaCli::class.java)

        // Device lines look like: `VirtualDevice : tv - aarch64 - OS - amazon-<hostname>`.
        // The `-d` selector is the first field (before " : "), not the trailing hostname.
        internal fun parseDeviceList(output: String): List<VegaDevice> {
            return output.lines()
                .map { it.trim() }
                .filter { it.contains(" : ") }
                .mapNotNull { line ->
                    val serial = line.substringBefore(" : ").trim()
                    if (serial.isEmpty()) return@mapNotNull null
                    VegaDevice(
                        serial = serial,
                        description = line,
                        isVirtual = serial.equals("VirtualDevice", ignoreCase = true) ||
                            serial.equals("Simulator", ignoreCase = true) ||
                            line.contains("Virtual", ignoreCase = true),
                    )
                }
        }

        private fun resolveBinary(): String {
            System.getenv("MAESTRO_VEGA_CLI")?.let { if (it.isNotBlank()) return it }
            val candidates = listOf("vega", "vda", "kepler")
            for (candidate in candidates) {
                if (isOnPath(candidate)) return candidate
            }
            // Fall back to `vega`; the first invocation surfaces a clear "command not found".
            return "vega"
        }

        private fun isOnPath(command: String): Boolean {
            return try {
                val which = if (System.getProperty("os.name").startsWith("Windows")) "where" else "which"
                ProcessBuilder(which, command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor() == 0
            } catch (e: Exception) {
                false
            }
        }
    }
}
