package maestro.cli.update

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.cli.api.CliVersion
import maestro.cli.util.EnvUtils
import maestro.cli.util.EnvUtils.CLI_VERSION
import maestro.utils.HttpClient
import okhttp3.Request
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import maestro.cli.util.ChangeLogUtils
import maestro.cli.util.ChangeLog

object Updates {
    private val DEFAULT_THREAD_FACTORY = Executors.defaultThreadFactory()
    private val EXECUTOR = Executors.newCachedThreadPool {
        DEFAULT_THREAD_FACTORY.newThread(it).apply { isDaemon = true }
    }

    private var future: CompletableFuture<CliVersion?>? = null
    private var changelogFuture: CompletableFuture<List<String>>? = null

    fun fetchUpdatesAsync() {
        getFuture()
    }

    fun fetchChangelogAsync() {
        getChangelogFuture()
    }

    fun checkForUpdates(): CliVersion? {
        // Disable update check, when MAESTRO_DISABLE_UPDATE_CHECK is set to "true" e.g. when installed by a package manager. e.g. nix
        if (System.getenv("MAESTRO_DISABLE_UPDATE_CHECK")?.toBoolean() == true) {
            return null
        }
        return try {
            getFuture().get(3, TimeUnit.SECONDS)
        } catch (e: Exception) {
            return null
        }
    }

    fun getChangelog(): List<String>? {
        // Disable update check, when MAESTRO_DISABLE_UPDATE_CHECK is set to "true" e.g. when installed by a package manager. e.g. nix
        if (System.getenv("MAESTRO_DISABLE_UPDATE_CHECK")?.toBoolean() == true) {
            return null
        }
        return try {
            getChangelogFuture().get(3, TimeUnit.SECONDS)
        } catch (e: Exception) {
            return null
        }
    }

    private fun fetchUpdates(): CliVersion? {
        if (CLI_VERSION == null) {
            return null
        }

        val latestCliVersion = fetchLatestVersion() ?: return null

        return if (latestCliVersion > CLI_VERSION) {
            latestCliVersion
        } else {
            null
        }
    }

    // Plex fork: resolve the latest published version straight from the plexinc/Maestro
    // GitHub Releases, parsing the `cli-<major.minor.patch.build>` tag. No proxy/API.
    private fun fetchLatestVersion(): CliVersion? {
        val request = Request.Builder()
            .url("https://api.github.com/repos/${EnvUtils.GITHUB_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        val body = HttpClient.build("Updates").newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string() ?: return null
        }
        val tag = jacksonObjectMapper().readTree(body).get("tag_name")?.asText() ?: return null
        return CliVersion.parse(tag.removePrefix("cli-"))
    }

    private fun fetchChangelog(): ChangeLog {
        if (CLI_VERSION == null) {
            return null
        }
        // CHANGELOG sections are keyed on Maestro's major.minor.patch, so match on
        // the base version rather than the full major.minor.patch.build.
        val version = fetchUpdates()?.baseVersion ?: return null
        val content = ChangeLogUtils.fetchContent()
        return ChangeLogUtils.formatBody(content, version)
    }

    @Synchronized
    private fun getFuture(): CompletableFuture<CliVersion?> {
        var future = this.future
        if (future == null) {
            future = CompletableFuture.supplyAsync(this::fetchUpdates, EXECUTOR)!!
            this.future = future
        }
        return future
    }

    @Synchronized
    private fun getChangelogFuture(): CompletableFuture<List<String>> {
        var changelogFuture = this.changelogFuture
        if (changelogFuture == null) {
            changelogFuture = CompletableFuture.supplyAsync(this::fetchChangelog, EXECUTOR)!!
            this.changelogFuture = changelogFuture
        }
        return changelogFuture
    }
}
