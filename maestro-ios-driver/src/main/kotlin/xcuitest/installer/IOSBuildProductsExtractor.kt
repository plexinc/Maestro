package xcuitest.installer

import maestro.utils.TempFileHandler
import org.rauschig.jarchivelib.ArchiverFactory
import org.slf4j.LoggerFactory
import util.ApplePlatform
import util.IOSDeviceType
import java.io.File
import java.io.FileNotFoundException
import java.net.URI
import java.nio.file.FileSystem
import java.nio.file.FileSystemAlreadyExistsException
import java.nio.file.FileSystemNotFoundException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.io.path.isRegularFile

data class BuildProducts(val xctestRunPath: File, val uiRunnerPath: File)

enum class Context {
    CLI,
    CLOUD
}

class IOSBuildProductsExtractor(
    private val target: Path,
    private val context: Context,
    private val deviceType: IOSDeviceType,
    private val platform: ApplePlatform = ApplePlatform.IOS,
) {

    companion object {
        private val LOGGER = LoggerFactory.getLogger(IOSBuildProductsExtractor::class.java)
    }

    fun extract(sourceDirectory: String): BuildProducts {
        LOGGER.info("[Start] Writing build products")
        writeBuildProducts(sourceDirectory)
        LOGGER.info("[Done] Writing build products")

        LOGGER.info("[Start] Writing ${platform.runnerZip} app")
        extractZipToApp(platform.runnerZip)
        LOGGER.info("[Done] Writing ${platform.runnerZip} app")

        LOGGER.info("[Start] Writing ${platform.hostZip} app")
        extractZipToApp(platform.hostZip)
        LOGGER.info("[Done] Writing ${platform.hostZip} app")

        val targetFile = target.toFile()
        val xctestRun = targetFile.walkTopDown().firstOrNull { it.extension == "xctestrun" }
            ?: throw FileNotFoundException("xctestrun config does not exist")
        val uiRunner = targetFile.walkTopDown().firstOrNull { it.name == platform.runnerAppName }
            ?: throw FileNotFoundException("ui test runner does not exist: ${platform.runnerAppName}")

        return BuildProducts(
            xctestRunPath = xctestRun,
            uiRunnerPath = uiRunner
        )
    }

    private fun extractZipToApp(appFileName: String) {
        val appZip = target.toFile().walk().firstOrNull { it.name == appFileName && it.extension == "zip" }
            ?: run {
                LOGGER.info("zip extension not present in the target directory, skipping unzipping operation.")
                return
            }

        try {
            ArchiverFactory.createArchiver(appZip).apply {
                extract(appZip, appZip.parentFile)
            }
        } finally {
            Files.delete(appZip.toPath())
        }
    }

    private fun writeBuildProducts(sourceDirectory: String) {
        val uri = when  {
            deviceType == IOSDeviceType.SIMULATOR -> {
                LocalXCTestInstaller::class.java.classLoader.getResource(sourceDirectory)?.toURI()
                    ?: throw IllegalArgumentException("Resource not found: $sourceDirectory")
            }
            context == Context.CLI && deviceType == IOSDeviceType.REAL -> {
                Paths.get(sourceDirectory).toUri()
            }
            else ->  {
                LocalXCTestInstaller::class.java.classLoader.getResource(sourceDirectory)?.toURI()
                    ?: throw IllegalArgumentException("Resource not found: $sourceDirectory")
            }
        }

        val sourcePath = if (uri.scheme == "jar") {
            when (deviceType) {
                IOSDeviceType.REAL -> {
                    Paths.get(uri)
                }
                IOSDeviceType.SIMULATOR -> {
                    val fs = try {
                        FileSystems.getFileSystem(uri)
                    } catch (e: FileSystemNotFoundException) {
                        uri.getOrCreateFileSystem()
                    }
                    fs.getPath(sourceDirectory)
                }
            }
        } else {
            Paths.get(uri)
        }

        Files.walk(sourcePath).use { paths ->
            paths.filter { it.isRegularFile() }.forEach { file ->
                val relative = sourcePath.relativize(file)
                val targetPath = target.resolve(relative.toString())

                Files.createDirectories(targetPath.parent)
                Files.copy(file, targetPath, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun URI.getOrCreateFileSystem(): FileSystem {
        return try {
            FileSystems.newFileSystem(this, emptyMap<String, Any>())
        } catch (e: FileSystemAlreadyExistsException) {
            FileSystems.getFileSystem(this)
        }
    }
}