package com.hikari.app.lnreader

import android.content.Context
import com.hikari.app.HikariApp
import com.hikari.app.data.AppStore
import com.hikari.app.data.Cs3Repo
import com.hikari.app.data.Cs3RepoPlugin
import com.hikari.app.data.ExtensionNsfw
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import com.hikari.app.data.RepoKind
import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Install/uninstall of LNReader novel sources, plus first-run seeding of the
 * official plugin index so the feature works out of the box.
 *
 * An LNReader repo is the published plugin index (`plugins.min.json` — a bare
 * JSON array of `{id, name, site, lang, version, url, iconUrl}`, where `url`
 * is the compiled plugin JS). A `github.com/owner/repo` page resolves to its
 * index through the GitHub API (see [loadRepoPlugins]) — pasting the app repo
 * (`lnreader/lnreader`) lands on the plugins repo, since the app repo itself
 * carries no plugins and that is the URL users actually have.
 *
 * Like every other engine, nothing is pre-installed for the user: the index
 * is seeded so the Extensions screen's LNReader folder is never empty, and
 * which sources are installed is the user's choice. Installed rows are
 * [ProviderType.MANGA] providers with an `lnreader|` id prefix (see
 * [LnReaderProvider]), so the manga tab, the detail screen and the novel
 * reader all work unchanged.
 */
object LnReaderPluginManager {

    const val PREFIX = "lnreader|"

    fun pluginsDir(context: Context): File =
        File(context.filesDir, "lnreader/plugins").apply { mkdirs() }

    fun pluginDir(context: Context, safeId: String): File =
        File(pluginsDir(context), safe(safeId))

    fun scriptFile(context: Context, safeId: String): File =
        File(pluginDir(context, safeId), "plugin.js")

    fun dirOf(config: ProviderConfig): File =
        pluginDir(HikariApp.instance, config.id.removePrefix(PREFIX))

    /** The source's own site, as captured at install (see [install]). Sync —
     *  a small local JSON read, safe wherever the globe button resolves. */
    fun siteUrlOf(config: ProviderConfig): String? {
        if (!LnReaderProvider.isLnReaderId(config.id)) return null
        val site = runCatching {
            JSONObject(File(dirOf(config), "meta.json").readText()).optString("site")
        }.getOrNull()?.trim()
        return site?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    /**
     * The source's own site, backfilling it from the plugin when an older
     * install predates the capture. Suspend — runs the plugin once, then the
     * capture is stored and [siteUrlOf] serves it.
     */
    suspend fun resolveSite(config: ProviderConfig): String? {
        siteUrlOf(config)?.let { return it }
        if (!LnReaderProvider.isLnReaderId(config.id)) return null
        val file = File(config.url)
        if (!file.exists()) return null
        val site = LnReaderRuntime.siteUrl(file, config.id.removePrefix(PREFIX)).trim()
        if (!(site.startsWith("http://") || site.startsWith("https://"))) return null
        runCatching {
            val meta = File(dirOf(config), "meta.json")
            val o = runCatching { JSONObject(meta.readText()) }.getOrNull() ?: JSONObject()
            o.put("site", site)
            meta.writeText(o.toString())
        }
        return site
    }

    fun safe(name: String): String =
        name.replace(Regex("[^A-Za-z0-9_.-]"), "_").ifBlank { "source" }.take(80)

    const val MAX_BYTES = 2 * 1024 * 1024

    /** The official LNReader plugin index (verified live: bare array). The
     *  versioned path may move with the next major plugin release — pasting
     *  the repo page re-resolves it (see [loadRepoPlugins]). */
    val DEFAULT_REPOS = listOf(
        Triple(
            "https://raw.githubusercontent.com/lnreader/lnreader-plugins/plugins/v3.0.0/.dist/plugins.min.json",
            "LNReader Plugins",
            "Community light-novel sources (LNReader plugins)",
        ),
    )

    /** Adds the default index once. Non-fatal on any failure. */
    suspend fun seedDefaults(context: Context, store: AppStore) {
        for ((url, name, desc) in DEFAULT_REPOS) {
            runCatching { store.seedCs3Repo(Cs3Repo(url, name, desc, RepoKind.LNREADER)) }
        }
    }

    /**
     * The installable entries of an LNReader plugin index: a bare JSON array
     * of `{id, name, site, lang, version, url, iconUrl}`. Relative `url`s
     * resolve against the index file, like the app does.
     */
    fun repoPlugins(indexText: String, indexUrl: String): List<Cs3RepoPlugin> {
        val arr = runCatching { JSONArray(indexText) }.getOrNull() ?: return emptyList()
        val base = indexUrl.trim().substringBeforeLast('/')
        val out = LinkedHashMap<String, Cs3RepoPlugin>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").trim()
            if (id.isBlank()) continue
            val rawUrl = o.optString("url").trim()
            if (rawUrl.isBlank()) continue
            val url = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) rawUrl
            else "$base/${rawUrl.trimStart('/')}"
            if (!url.endsWith(".js", ignoreCase = true)) continue
            val name = o.optString("name").trim().ifBlank { id }
            val lang = o.optString("lang").trim()
            val versionStr = o.optString("version").trim()
            val version = versionStr.takeWhile { it.isDigit() }.toIntOrNull()
                ?: if (versionStr.isNotBlank()) 1 else 1
            val desc = listOfNotNull(
                lang.ifBlank { null },
                versionStr.ifBlank { null }?.let { "v$it" },
            ).joinToString(" · ").ifBlank { "LNReader source" }
            out[url] = Cs3RepoPlugin(
                name = name,
                description = desc,
                url = url,
                iconUrl = o.optString("iconUrl").ifBlank { null },
                version = version,
                nsfw = ExtensionNsfw.repoEntryNsfw(o),
            )
        }
        return out.values.toList()
    }

    /** True when [text] parses as an LNReader plugin index (bare array with
     *  `{id, name, url}` entries) — the content sniff for pasted links. */
    fun looksLikeLnReader(text: String): Boolean {
        val arr = runCatching { JSONArray(text.trim()) }.getOrNull() ?: return false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id").isNotBlank() &&
                o.optString("name").isNotBlank() &&
                o.optString("url").isNotBlank()
            ) return true
        }
        return false
    }

    private fun looksLikeHtml(text: String): Boolean {
        val t = text.trimStart().take(64).lowercase()
        return t.startsWith("<!doctype") || t.startsWith("<html") || t.startsWith("<head")
    }

    private fun jsDelivrMirror(url: String): String? {
        val m = Regex("^https://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$")
            .find(url.trim()) ?: return null
        return "https://cdn.jsdelivr.net/gh/${m.groupValues[1]}/${m.groupValues[2]}@${m.groupValues[3]}/${m.groupValues[4]}"
    }

    private fun fetchText(url: String, readSec: Long = 20): String? {
        for (c in listOfNotNull(url, jsDelivrMirror(url))) {
            val b = Http.fetchStringRobust(c, emptyMap(), readSec).getOrNull()
            if (b != null && b.isNotBlank() && !looksLikeHtml(b)) return b
        }
        return null
    }

    /** A `github.com/owner/repo…` page split into owner + repo, or null. */
    fun githubRepoOf(url: String): Pair<String, String>? {
        val m = Regex("^https?://(?:www\\.)?github\\.com/([^/\\s]+)/([^/\\s?#]+)")
            .find(url.trim()) ?: return null
        val repo = m.groupValues[2].removeSuffix(".git")
        if (m.groupValues[1].isBlank() || repo.isBlank()) return null
        return m.groupValues[1] to repo
    }

    private const val GITHUB_API = "https://api.github.com"

    /**
     * The installable entries of whatever [url] names — the index file
     * itself, or a `github.com/owner/repo` page — plus the URL to store for
     * refreshes. The stored URL is the page itself for GitHub repos (their
     * index is re-discovered on every refresh, so a versioned path never
     * rots), otherwise the file that served.
     */
    suspend fun loadRepoPlugins(url: String): Result<Pair<List<Cs3RepoPlugin>, String>> =
        withContext(Dispatchers.IO) {
            val t = url.trim()
            if (t.endsWith(".json", ignoreCase = true)) {
                val body = fetchText(t)
                    ?: return@withContext Result.failure(Exception("Could not fetch repo: $t"))
                val plugins = repoPlugins(body, t)
                if (plugins.isNotEmpty()) return@withContext Result.success(plugins to t)
                return@withContext Result.failure(Exception("That file lists no LNReader sources"))
            }
            val gh = githubRepoOf(t)
                ?: return@withContext Result.failure(Exception("Could not fetch repo: $t"))
            // The app repo carries no plugins — its sibling does — and it is
            // the URL users actually have, so it resolves there (said plainly
            // in the folder, not silently).
            val (owner, repo) =
                if (gh.first.equals("lnreader", ignoreCase = true) && gh.second.equals("lnreader", ignoreCase = true)) {
                    "lnreader" to "lnreader-plugins"
                } else gh
            resolveGithubRepo(owner, repo).map { it to t }
        }

    /** Finds a GitHub repo's plugin index: known raw paths first, then a
     *  recursive tree search for `plugins.min.json`. */
    suspend fun resolveGithubRepo(owner: String, repo: String): Result<List<Cs3RepoPlugin>> {
        for (branch in listOf("plugins", "main", "master")) {
            for (path in listOf("plugins.min.json", ".dist/plugins.min.json", "v3.0.0/.dist/plugins.min.json")) {
                val raw = "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"
                val body = fetchText(raw, 10) ?: continue
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
                    val raws = (0 until arr.length())
                        .mapNotNull { arr.optJSONObject(it)?.optString("path")?.trim() }
                        .filter { it.endsWith("plugins.min.json", ignoreCase = true) }
                        .sortedBy { it.count { c -> c == '/' } }
                        .take(5)
                        .map { "https://raw.githubusercontent.com/$owner/$repo/$branch/$it" }
                    for (raw in raws) {
                        val body = fetchText(raw, 10) ?: continue
                        val plugins = repoPlugins(body, raw)
                        if (plugins.isNotEmpty()) return Result.success(plugins)
                    }
                    continue
                }
            }
        }
        return Result.failure(Exception("Could not fetch repo: no LNReader index in $owner/$repo"))
    }

    /**
     * Downloads a plugin's JS and registers it as an LNReader (MANGA)
     * provider. The file must validate as an LNReader source first — a web
     * page or some other engine's script fails with the reason instead of a
     * broken provider row.
     */
    suspend fun install(context: Context, plugin: Cs3RepoPlugin): Result<Int> =
        withContext(Dispatchers.IO) {
            val bytes = runCatching {
                Http.fetchBytesCancellable(plugin.url, mapOf("User-Agent" to Http.UA), 60)
            }.getOrNull()
            if (bytes == null || bytes.isEmpty() || bytes.size > MAX_BYTES) {
                return@withContext Result.failure(Exception("Could not download the source script"))
            }
            val source = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
            if (source.isNullOrBlank()) {
                return@withContext Result.failure(Exception("The source script is empty"))
            }
            if (looksLikeHtml(source)) {
                return@withContext Result.failure(Exception("That link serves a web page, not a script"))
            }
            val verdict = LnReaderRuntime.validate(source)
            if (verdict != "OK") {
                val detail = if (verdict.startsWith("ERR:")) verdict.removePrefix("ERR:").take(200)
                else "it does not export the LNReader entry points"
                return@withContext Result.failure(Exception("Not a valid LNReader source: $detail"))
            }
            val rawId = plugin.url.substringAfterLast('/').substringBeforeLast('.')
                .ifBlank { plugin.name }
            val safeId = safe(rawId)
            val id = PREFIX + safeId
            val dir = pluginDir(context, safeId)
            runCatching { dir.mkdirs() }
            val wrote = runCatching {
                val script = File(dir, "plugin.js")
                script.writeText(source)
                // The globe/WebView button opens this, so capture it while the
                // plugin is already at hand (older installs backfill it on
                // first use — see [resolveSite]).
                val site = LnReaderRuntime.siteUrl(script, safeId).trim()
                JSONObject()
                    .put("name", plugin.name)
                    .put("remoteUrl", plugin.url)
                    .put("iconUrl", plugin.iconUrl ?: "")
                    .put("site", site)
                    .put("at", System.currentTimeMillis())
                    .let { File(dir, "meta.json").writeText(it.toString()) }
                true
            }.getOrDefault(false)
            if (!wrote) {
                return@withContext Result.failure(Exception("Could not write the source to storage"))
            }
            HikariApp.instance.store.addProvider(
                ProviderConfig(
                    id = id,
                    name = plugin.name,
                    type = ProviderType.MANGA,
                    url = File(dir, "plugin.js").absolutePath,
                    iconUrl = plugin.iconUrl,
                    extra = plugin.url,
                )
            )
            HikariApp.instance.providers.refresh()
            // A new source build may change nothing cached (chapters are
            // keyed by path), but a reinstall over a changed site deserves a
            // fresh episode epoch like every other engine gets.
            runCatching { com.hikari.app.data.MetaCache.bumpEpisodesEpoch() }
            Result.success(1)
        }

    /**
     * Removes every LNReader source installed from [pluginUrl] (and its
     * files). Returns how many were removed.
     */
    suspend fun uninstall(context: Context, pluginUrl: String): Int {
        val store = HikariApp.instance.store
        val mine = store.providers().filter {
            it.type == ProviderType.MANGA &&
                LnReaderProvider.isLnReaderId(it.id) &&
                it.extra == pluginUrl
        }
        if (mine.isEmpty()) return 0
        store.updateProviders { list ->
            list.filterNot {
                it.type == ProviderType.MANGA &&
                    LnReaderProvider.isLnReaderId(it.id) &&
                    it.extra == pluginUrl
            }
        }
        HikariApp.instance.providers.refresh()
        withContext(Dispatchers.IO) {
            val keep = store.providers()
                .filter { it.type == ProviderType.MANGA && LnReaderProvider.isLnReaderId(it.id) }
                .map { it.url }.toSet()
            mine.map { it.url }.distinct().forEach { path ->
                if (path !in keep) {
                    runCatching { File(path).delete() }
                    runCatching {
                        val dir = File(path).parentFile
                        if (dir != null && dir.name != "plugins" &&
                            (dir.listFiles()?.isEmpty() != false)
                        ) dir.delete()
                    }
                }
            }
        }
        return mine.size
    }

    /** Whether the source's script file still exists on disk. */
    fun fileMissing(config: ProviderConfig): Boolean =
        config.type == ProviderType.MANGA &&
            LnReaderProvider.isLnReaderId(config.id) &&
            (config.url.isBlank() || !File(config.url).exists())
}
