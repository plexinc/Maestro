package maestro.orchestra.workspace

import maestro.orchestra.error.InvalidFlowFile
import maestro.orchestra.yaml.YamlCommandReader
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * Resolves `file:` references inside flows. A path starting with `@` is an alias
 * of the form `@name/rest`, where `name` maps to a directory declared under
 * `paths:` in the nearest `config.yaml`. Everything else keeps the historical
 * behavior: resolved relative to the flow file's own directory.
 */
object FlowPathResolver {

    private const val ALIAS_PREFIX = '@'
    private val CONFIG_FILE_NAMES = listOf("config.yaml", "config.yml")

    fun resolve(flowPath: Path, requestedPath: String): Path {
        if (requestedPath.startsWith(ALIAS_PREFIX)) {
            return resolveAlias(flowPath, requestedPath)
        }
        val path = flowPath.fileSystem.getPath(requestedPath)
        return if (path.isAbsolute) {
            path
        } else {
            flowPath.resolveSibling(path).toAbsolutePath().normalize()
        }
    }

    private fun resolveAlias(flowPath: Path, requestedPath: String): Path {
        val body = requestedPath.substring(1)
        val separator = body.indexOf('/')
        val alias = if (separator >= 0) body.substring(0, separator) else body
        val remainder = if (separator >= 0) body.substring(separator + 1) else ""

        val configPath = findWorkspaceConfig(flowPath)
            ?: throw InvalidFlowFile(
                "Path alias '@$alias' used in ${flowPath.toUri()} but no config.yaml was found in any parent " +
                    "directory. Declare aliases under `paths:` in a workspace config.yaml.",
                flowPath
            )

        val paths = YamlCommandReader.readWorkspaceConfig(configPath).paths ?: emptyMap()

        val target = paths[alias]
            ?: throw InvalidFlowFile(
                "Unknown path alias '@$alias' referenced in ${flowPath.toUri()}. " +
                    "Known aliases in ${configPath.toUri()}: ${paths.keys.sorted()}",
                flowPath
            )

        val configDir = configPath.toAbsolutePath().parent
        val targetDir = configDir.resolve(target).normalize()
        if (!targetDir.exists() || !targetDir.isDirectory()) {
            throw InvalidFlowFile(
                "Path alias '@$alias' points to '${targetDir.toUri()}', which is not an existing directory.",
                flowPath
            )
        }

        return targetDir.resolve(remainder).toAbsolutePath().normalize()
    }

    private fun findWorkspaceConfig(flowPath: Path): Path? {
        var dir = flowPath.toAbsolutePath().parent
        while (dir != null) {
            CONFIG_FILE_NAMES.forEach { name ->
                val candidate = dir!!.resolve(name)
                if (candidate.exists()) return candidate
            }
            dir = dir.parent
        }
        return null
    }
}
