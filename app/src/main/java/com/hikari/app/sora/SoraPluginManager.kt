package com.hikari.app.sora

import android.content.Context
import com.hikari.app.HikariApp
import com.hikari.app.data.Cs3Repo
import com.hikari.app.data.Cs3RepoPlugin
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import com.hikari.app.data.RepoKind
import com.hikari.app.data.AppStore
import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Install/uninstall of Sora modules, plus first-run seeding of the Sora
 * module repos so the feature works out of the box.
 *
 * A Sora repo is either ONE module manifest (a `{sourceName, scriptUrl, …}`
 * JSON file like `streamex.json`), an INDEX (`modules.json`) pointing at
 * many manifests (see [repoPlugins]), or a `github.com/owner/repo` page,
 * which is resolved to its manifests through the GitHub API (see
 * [loadRepoPlugins]) — some repos publish no index at all. Only video
 * modules install: manga/novel modules are listed so the repo reads complete,
 * but installing one fails with the reason — there is no novel reader yet,
 * and manga modules speak a different call shape the manga engine does not
 * run.
 *
 * Like every other engine, nothing is pre-installed for the user: the repos
 * are seeded so the Extensions screen's Sora folders are never empty, and
 * which modules are installed is the user's choice.
 */
object SoraPluginManager {

    fun modulesDir(context: Context): File =
        File(context.filesDir, "sora/modules").apply { mkdirs() }

    fun moduleDir(context: Context, safeId: String): File =
        File(modulesDir(context), safe(safeId))

    fun scriptFile(context: Context, safeId: String): File =
        File(moduleDir(context, safeId), "module.js")

    fun manifestFile(context: Context, safeId: String): File =
        File(moduleDir(context, safeId), "manifest.json")

    fun dirOf(config: ProviderConfig): File =
        moduleDir(HikariApp.instance, config.id.removePrefix("sora|"))

    private fun safe(name: String): String =
        name.replace(Regex("[^A-Za-z0-9_.-]"), "_").ifBlank { "module" }.take(80)

    const val MAX_BYTES = 2 * 1024 * 1024

    /** The built-in Sora module repositories. */
    val DEFAULT_REPOS = listOf(
        Triple(
            "https://raw.githubusercontent.com/xdfkenny/Sora-Modules/main/modules.json",
            "Sora Modules",
            "Community Sora modules (anime, films & series)",
        ),
        Triple(
            "https://raw.githubusercontent.com/justbbcr/streamex/main/streamex.json",
            "StreameX",
            "French anime/shows/movies Sora module",
        ),
        Triple(
            "https://github.com/CPRmichel/sora-movie2k-module",
            "Movie2K modules",
            "German movies & shows Sora modules (Movie2K, Kinoger, Moflix)",
        ),
    )

    /** Adds the default repos once. Non-fatal on any failure. */
    suspend fun seedDefaults(context: Context, store: AppStore) {
        for ((url, name, desc) in DEFAULT_REPOS) {
            runCatching { store.seedCs3Repo(Cs3Repo(url, name, desc, RepoKind.SORA)) }
        }
    }

    /**
     * The installable entries of a Sora repo listing.
     *
     * Two shapes: an INDEX (`{modules: [{name, iconUrl, manifestUrl, …}]}`)
     * and a bare MODULE MANIFEST (`{sourceName, scriptUrl, type, …}` — the
     * repo IS the module, e.g. `streamex.json`). A module entry's `url` is
     * its MANIFEST url; the script is resolved at install/test time so a
     * listing never pays for every module's script up front.
     */
    fun repoPlugins(indexText: String, indexUrl: String): List<Cs3RepoPlugin> {
        val root = runCatching { JSONObject(indexText) }.getOrNull() ?: return emptyList()
        val out = LinkedHashMap<String, Cs3RepoPlugin>()
        val modules = root.optJSONArray("modules")
        if (modules != null) {
            for (i in 0 until modules.length()) {
                val o = modules.optJSONObject(i) ?: continue
                val manifest = o.optString("manifestUrl").trim().ifBlank { continue }
                val name = o.optString("name").trim()
                    .ifBlank { manifest.substringAfterLast('/').substringBeforeLast('.') }
                    .ifBlank { continue }
                out[manifest] = Cs3RepoPlugin(
                    name = name,
                    description = listOfNotNull(
                        o.optString("category").trim().ifBlank { null },
                        o.optString("note").trim().ifBlank { null },
                    ).joinToString(" · ").ifBlank { "Sora module" },
                    url = manifest,
                    iconUrl = o.optString("iconUrl").ifBlank { null },
                    version = 1,
                    nsfw = com.hikari.app.data.ExtensionNsfw.repoEntryNsfw(o),
                )
            }
            return out.values.toList()
        }
        singlePlugin(root, indexUrl)?.let { out[it.url] = it }
        return out.values.toList()
    }

    /** One bare-manifest repo IS one module (see [repoPlugins]). */
    fun singlePlugin(manifest: JSONObject, manifestUrl: String): Cs3RepoPlugin? {
        val script = manifest.optString("scriptUrl").trim()
        if (script.isBlank()) return null
        val name = manifest.optString("sourceName").trim().ifBlank { return null }
        val type = manifest.optString("type").trim()
        val desc = listOfNotNull(
            manifest.optString("language").trim().ifBlank { null },
            type.ifBlank { null },
            manifest.optString("version").trim().ifBlank { null }?.let { "v$it" },
        ).joinToString(" · ").ifBlank { "Sora module" }
        return Cs3RepoPlugin(
            name = name,
            description = desc,
            url = manifestUrl,
            iconUrl = manifest.optString("iconUrl").ifBlank { null },
            version = manifest.optString("version").substringBefore('.').toIntOrNull() ?: 1,
            nsfw = com.hikari.app.data.ExtensionNsfw.repoEntryNsfw(manifest),
        )
    }

    /** True when the manifest at [manifestUrl] is a video module. */
    fun manifestType(manifestText: String): String =
        runCatching { JSONObject(manifestText).optString("type").trim() }.getOrDefault("")

    private val videoHints = listOf("anime", "movie", "show", "film", "serie", "drama", "tv", "video", "cartoon")
    private val textHints = listOf("manga", "manhwa", "manhua", "novel", "comic", "book")

    fun isVideoType(type: String): Boolean {
        val t = type.lowercase()
        if (t.isBlank()) return false
        if (textHints.any { t.contains(it) }) return false
        return videoHints.any { t.contains(it) }
    }

    private fun looksLikeHtml(text: String): Boolean {
        val t = text.trimStart().take(64).lowercase()
        return t.startsWith("<!doctype") || t.startsWith("<html") || t.startsWith("<head")
    }

    /** A `github.com/owner/repo…` page split into owner + repo, or null. */
    fun githubRepoOf(url: String): Pair<String, String>? {
        val m = Regex("^https?://(?:www\\.)?github\\.com/([^/\\s]+)/([^/\\s?#]+)")
            .find(url.trim()) ?: return null
        val repo = m.groupValues[2].removeSuffix(".git")
        if (m.groupValues[1].isBlank() || repo.isBlank()) return null
        return m.groupValues[1] to repo
    }

    private fun githubRawFileUrl(url: String): String? {
        val m = Regex("^https?://(?:www\\.)?github\\.com/([^/\\s]+)/([^/\\s?#]+)/(?:blob|raw)/([^\\s?#]+)")
            .find(url.trim()) ?: return null
        return "https://raw.githubusercontent.com/${m.groupValues[1]}/" +
            "${m.groupValues[2].removeSuffix(".git")}/${m.groupValues[3]}"
    }

    private const val GITHUB_API = "https://api.github.com"
    private const val MAX_GITHUB_MANIFESTS = 30

    /**
     * The installable entries of whatever [url] names — a manifest, an index,
     * or a `github.com/owner/repo` page — plus the URL to store for refreshes.
     * The stored URL is the page itself for GitHub repos (their manifests are
     * re-discovered on every refresh), otherwise the document that served.
     */
    suspend fun loadRepoPlugins(url: String): Result<Pair<List<Cs3RepoPlugin>, String>> =
        withContext(Dispatchers.IO) {
            val t = url.trim()
            val direct = Http.fetchStringRobust(t).getOrNull()
                ?.takeIf { !looksLikeHtml(it) }
            if (direct != null) {
                val plugins = repoPlugins(direct, t)
                if (plugins.isNotEmpty()) return@withContext Result.success(plugins to t)
                return@withContext Result.failure(Exception("That file lists no Sora sources"))
            }
            githubRawFileUrl(t)?.let { raw ->
                val body = Http.fetchStringRobust(raw).getOrNull()
                    ?.takeIf { !looksLikeHtml(it) }
                if (body != null) {
                    val plugins = repoPlugins(body, raw)
                    if (plugins.isNotEmpty()) return@withContext Result.success(plugins to raw)
                }
            }
            val gh = githubRepoOf(t)
                ?: return@withContext Result.failure(Exception("Could not fetch repo: $t"))
            resolveGithubRepo(gh.first, gh.second).map { it to t }
        }

    /**
     * Discovers a GitHub repo's Sora manifests without a published index: a
     * root `modules.json`/`index.json`/`sora.json` first, then every `*.json`
     * in the recursive file tree (one API call), then a one-level contents
     * walk as a fallback.
     */
    suspend fun resolveGithubRepo(owner: String, repo: String): Result<List<Cs3RepoPlugin>> {
        for (branch in listOf("main", "master")) {
            for (name in listOf("modules.json", "index.json", "sora.json")) {
                val raw = "https://raw.githubusercontent.com/$owner/$repo/$branch/$name"
                val body = Http.fetchStringRobust(raw, emptyMap(), 10).getOrNull()
                    ?.takeIf { !looksLikeHtml(it) } ?: continue
                val plugins = repoPlugins(body, raw)
                if (plugins.isNotEmpty()) return Result.success(plugins)
            }
            val treeBody = Http.fetchStringRobust(
                "$GITHUB_API/repos/$owner/$repo/git/trees/$branch?recursive=1",
                mapOf("Accept" to "application/vnd.github+json"),
                20,
            ).getOrNull()?.takeIf { !looksLikeHtml(it) }
            if (treeBody != null) {
                val tree = runCatching { JSONObject(treeBody) }.getOrNull()
                val arr = tree?.optJSONArray("tree")
                if (arr != null && tree?.optBoolean("truncated", false) != true) {
                    val skip = setOf("modules.json", "index.json", "sora.json")
                    val raws = (0 until arr.length())
                        .mapNotNull { arr.optJSONObject(it)?.optString("path")?.trim() }
                        .filter { it.endsWith(".json", ignoreCase = true) }
                        .filter { it.substringAfterLast('/').lowercase() !in skip }
                        .sortedBy { it.count { c -> c == '/' } }
                        .take(MAX_GITHUB_MANIFESTS)
                        .map { "https://raw.githubusercontent.com/$owner/$repo/$branch/$it" }
                    val found = fetchManifests(raws)
                    if (found.isNotEmpty()) return Result.success(found)
                    continue
                }
            }
            val walked = walkGithubContents(owner, repo, branch)
            if (walked.isNotEmpty()) return Result.success(walked)
        }
        return Result.failure(Exception("Could not fetch repo: no Sora manifests in $owner/$repo"))
    }

    private fun fetchManifests(rawUrls: List<String>): List<Cs3RepoPlugin> {
        val out = LinkedHashMap<String, Cs3RepoPlugin>()
        for (raw in rawUrls) {
            val body = Http.fetchStringRobust(raw, emptyMap(), 10).getOrNull()
                ?.takeIf { !looksLikeHtml(it) } ?: continue
            val manifest = runCatching { JSONObject(body) }.getOrNull() ?: continue
            singlePlugin(manifest, raw)?.let { out[it.url] = it }
        }
        return out.values.toList()
    }

    private fun walkGithubContents(owner: String, repo: String, branch: String): List<Cs3RepoPlugin> {
        val root = Http.fetchStringRobust(
            "$GITHUB_API/repos/$owner/$repo/contents?ref=$branch",
            mapOf("Accept" to "application/vnd.github+json"),
            20,
        ).getOrNull()?.takeIf { !looksLikeHtml(it) } ?: return emptyList()
        val entries = runCatching { JSONArray(root) }.getOrNull() ?: return emptyList()
        val raws = ArrayList<String>()
        val dirs = ArrayList<String>()
        for (i in 0 until entries.length()) {
            val o = entries.optJSONObject(i) ?: continue
            val name = o.optString("name")
            val kind = o.optString("type")
            if (kind == "file" && name.endsWith(".json", ignoreCase = true)) {
                raws += "https://raw.githubusercontent.com/$owner/$repo/$branch/$name"
            } else if (kind == "dir") {
                dirs += name
            }
        }
        for (dir in dirs.take(30)) {
            val listing = Http.fetchStringRobust(
                "$GITHUB_API/repos/$owner/$repo/contents/$dir?ref=$branch",
                mapOf("Accept" to "application/vnd.github+json"),
                20,
            ).getOrNull()?.takeIf { !looksLikeHtml(it) } ?: continue
            val files = runCatching { JSONArray(listing) }.getOrNull() ?: continue
            for (i in 0 until files.length()) {
                val name = files.optJSONObject(i)?.optString("name") ?: continue
                if (files.optJSONObject(i)?.optString("type") == "file" &&
                    name.endsWith(".json", ignoreCase = true) &&
                    raws.size < MAX_GITHUB_MANIFESTS
                ) {
                    raws += "https://raw.githubusercontent.com/$owner/$repo/$branch/$dir/$name"
                }
            }
        }
        return fetchManifests(raws.take(MAX_GITHUB_MANIFESTS))
    }

    /**
     * Downloads a module manifest + script and registers it as a SORA
     * provider. Only video modules install — manga/novel modules fail with
     * the reason instead of a broken provider row.
     */
    suspend fun install(context: Context, plugin: Cs3RepoPlugin): Result<Int> =
        withContext(Dispatchers.IO) {
            val manifestText = runCatching {
                Http.fetchStringRobust(plugin.url, emptyMap(), 30).getOrThrow()
            }.getOrElse {
                return@withContext Result.failure(Exception("Could not download module manifest"))
            }
            val manifest = runCatching { JSONObject(manifestText) }.getOrElse {
                return@withContext Result.failure(Exception("Module manifest is not JSON"))
            }
            val type = manifest.optString("type").trim()
            if (!isVideoType(type)) {
                return@withContext Result.failure(
                    Exception(
                        if (type.isBlank()) "Not a Sora module manifest"
                        else "Only video (anime, movies, shows) modules install — “${plugin.name}” is “$type”"
                    )
                )
            }
            val scriptUrl = manifest.optString("scriptUrl").trim()
            if (scriptUrl.isBlank()) {
                return@withContext Result.failure(Exception("Module manifest has no scriptUrl"))
            }
            val bytes = runCatching {
                Http.fetchBytesCancellable(scriptUrl, mapOf("User-Agent" to Http.UA), 60)
            }.getOrNull()
            if (bytes == null || bytes.isEmpty() || bytes.size > MAX_BYTES) {
                return@withContext Result.failure(Exception("Could not download module script"))
            }
            val source = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
            if (source.isNullOrBlank()) {
                return@withContext Result.failure(Exception("Module script is empty"))
            }
            val verdict = SoraRuntime.validate(context, source)
            if (verdict != "OK") {
                val detail = if (verdict.startsWith("ERR:")) verdict.removePrefix("ERR:").take(200)
                else "it does not export the Sora entry points"
                return@withContext Result.failure(Exception("Not a valid Sora module: $detail"))
            }
            val name = manifest.optString("sourceName").trim().ifBlank { plugin.name }
            val id = "sora|" + safe(
                name.ifBlank { scriptUrl.substringAfterLast('/').substringBeforeLast('.') }
            )
            val dir = moduleDir(context, id.removePrefix("sora|"))
            runCatching { dir.mkdirs() }
            val wrote = runCatching {
                File(dir, "module.js").writeText(source)
                File(dir, "manifest.json").writeText(manifestText)
                true
            }.getOrDefault(false)
            if (!wrote) {
                return@withContext Result.failure(Exception("Could not write the module to storage"))
            }
            HikariApp.instance.store.addProvider(
                ProviderConfig(
                    id = id,
                    name = name,
                    type = ProviderType.SORA,
                    url = File(dir, "module.js").absolutePath,
                    iconUrl = manifest.optString("iconUrl").ifBlank { plugin.iconUrl },
                    extra = plugin.url,
                )
            )
            HikariApp.instance.providers.refresh()
            // A new extension build may change provider-opaque episode payloads — retire cached ones (see MetaCache).
            runCatching { com.hikari.app.data.MetaCache.bumpEpisodesEpoch() }
            Result.success(1)
        }

    /**
     * Removes every SORA provider installed from [pluginUrl] (and its files).
     * Returns how many were removed.
     */
    suspend fun uninstall(context: Context, pluginUrl: String): Int {
        val store = HikariApp.instance.store
        val mine = store.providers().filter { it.type == ProviderType.SORA && it.extra == pluginUrl }
        if (mine.isEmpty()) return 0
        store.updateProviders { list ->
            list.filterNot { it.type == ProviderType.SORA && it.extra == pluginUrl }
        }
        HikariApp.instance.providers.refresh()
        withContext(Dispatchers.IO) {
            val keep = store.providers().filter { it.type == ProviderType.SORA }.map { it.url }.toSet()
            mine.map { it.url }.distinct().forEach { path ->
                if (path !in keep) {
                    runCatching { File(path).delete() }
                    runCatching {
                        val dir = File(path).parentFile
                        if (dir != null && dir.name != "modules" &&
                            (dir.listFiles()?.isEmpty() != false)
                        ) dir.delete()
                    }
                }
            }
        }
        return mine.size
    }

    /** Whether the module's script file still exists on disk. */
    fun fileMissing(config: ProviderConfig): Boolean =
        config.type == ProviderType.SORA &&
            (config.url.isBlank() || !File(config.url).exists())
}
