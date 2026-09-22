package com.hikari.app.skystream

import android.content.Context
import com.hikari.app.HikariApp
import com.hikari.app.data.AppStore
import com.hikari.app.data.Cs3Repo
import com.hikari.app.data.Cs3RepoPlugin
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import com.hikari.app.data.RepoKind
import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Install/uninstall of SkyStream extensions (`.sky` files), plus first-run
 * seeding of the community SkyStream repositories.
 *
 * A `.sky` is a plain zip containing at least `plugin.js` (the ES module the
 * engine runs) and `plugin.json` (packageName/name/version/baseUrl — assigned
 * to `globalThis.manifest` before the source runs, which some plugins need).
 * Both parts are extracted into one directory per plugin so the runtime can
 * read them straight off disk:
 *
 *   filesDir/skystream/plugins/<packageName>/plugin.js
 *   filesDir/skystream/plugins/<packageName>/plugin.json
 *
 * The extracted `plugin.js` path is stored in the provider's `url` (exactly how
 * NUVIO providers work), and the repo/`plugins` entry it came from goes to
 * `extra` so uninstall can remove every plugin of that source.
 */
object SkyStreamPluginManager {

    const val MAX_BYTES = 5 * 1024 * 1024

    fun pluginsDir(context: Context): File =
        File(context.filesDir, "skystream/plugins").apply { mkdirs() }

    fun pluginDir(context: Context, packageName: String): File =
        File(pluginsDir(context), safe(packageName))

    fun scriptFile(context: Context, packageName: String): File =
        File(pluginDir(context, packageName), "plugin.js")

    private fun safe(name: String): String =
        name.replace(Regex("[^A-Za-z0-9_.-]"), "_").ifBlank { "plugin" }

    /** The built-in SkyStream extension repositories (repo.json). */
    val DEFAULT_REPOS = listOf(
        Triple(
            "https://raw.githubusercontent.com/akashdh11/skystream-plugins/main/repo.json",
            "SkyStream official",
            "The official SkyStream plugin repo (Akash's providers)",
        ),
        Triple(
            "https://raw.githubusercontent.com/rougegz/SkystreamPlugins/main/repo.json",
            "Rougegz SkyStream",
            "Community SkyStream plugins",
        ),
    )

    /**
     * Adds the default SkyStream repos once. No plugins are pre-installed —
     * the official set is ~36 extensions and the user picks what they want
     * (unlike nuvio, a single SkyStream plugin is a whole site, so a silent
     * bulk install would be hostile). Non-fatal on any failure.
     */
    suspend fun seedDefaults(context: Context, store: AppStore) {
        for ((url, name, desc) in DEFAULT_REPOS) {
            runCatching { store.addCs3Repo(Cs3Repo(url, name, desc, RepoKind.SKYSTREAM)) }
        }
    }

    /**
     * Unpacks a `.sky` zip, validates the plugin inside a fresh engine, and
     * registers it as a SKYSTREAM provider. Returns failure (with a reason the
     * UI can show) when the bytes aren't a SkyStream plugin or the plugin
     * doesn't export the four callbacks.
     */
    suspend fun install(
        context: Context,
        bytes: ByteArray,
        sourceUrl: String? = null,
        iconUrl: String? = null,
    ): Result<Int> {
        if (bytes.size > MAX_BYTES) {
            return Result.failure(Exception("File too large (max ${MAX_BYTES / 1024 / 1024}MB)"))
        }
        val parts = withContext(Dispatchers.IO) { unzip(bytes) }
            ?: return Result.failure(Exception("Not a SkyStream extension (unreadable .sky archive)"))
        val js = parts.js
            ?: return Result.failure(Exception("Not a SkyStream extension (no plugin.js in the .sky)"))
        val manifest = parts.manifest
            ?: return Result.failure(Exception("Not a SkyStream extension (no plugin.json in the .sky)"))
        val packageName = manifest.optString("packageName")
            .ifBlank { manifest.optString("id") }
            .ifBlank { fallbackName(sourceUrl) }
            .trim()
        if (packageName.isBlank()) {
            return Result.failure(Exception("Extension has no package name"))
        }
        val displayName = manifest.optString("name").ifBlank { packageName }

        return withContext(Dispatchers.IO) {
            val dir = pluginDir(context, packageName)
            runCatching { dir.mkdirs() }
            val jsFile = File(dir, "plugin.js")
            val jsonFile = File(dir, "plugin.json")
            val wrote = runCatching {
                jsFile.setWritable(true)
                jsFile.writeBytes(js)
                jsonFile.writeText(manifest.toString())
                true
            }.getOrDefault(false)
            if (!wrote) {
                return@withContext Result.failure(Exception("Could not write the extension to storage"))
            }
            val verdict = SkyStreamRuntime.validate(context, packageName, jsFile)
            if (!verdict.startsWith("OK")) {
                runCatching { dir.deleteRecursively() }
                val detail = if (verdict.startsWith("ERR:")) verdict.removePrefix("ERR:").take(300)
                else "it doesn't export getHome/search/load/loadStreams"
                return@withContext Result.failure(Exception("Not a valid SkyStream extension: $detail"))
            }
            val icon = iconUrl
                ?: manifest.optString("iconUrl")
                    .ifBlank { manifest.optString("logo") }
                    .ifBlank { manifest.optString("icon") }
                    .ifBlank { null }
                // The .sky format declares no icon at all, so the real logo
                // lives in the extension's first addon manifest. Resolving it
                // HERE means the installed row keeps its real icon forever
                // instead of falling back to the site favicon on every launch.
                ?: addonIcon(manifest)
            HikariApp.instance.store.addProvider(
                ProviderConfig(
                    id = "sky|" + packageName,
                    name = displayName,
                    type = ProviderType.SKYSTREAM,
                    url = jsFile.absolutePath,
                    iconUrl = icon,
                    extra = sourceUrl ?: packageName,
                )
            )
            HikariApp.instance.providers.refresh()
            Result.success(1)
        }
    }

    /** Removes every SKYSTREAM provider that came from [sourceUrl] (+ its dir).
     *  Returns how many installed providers were actually removed — 0 when the
     *  source was not installed — so the caller never reports a success for an
     *  uninstall that removed nothing. */
    suspend fun uninstall(context: Context, sourceUrl: String): Int {
        val store = HikariApp.instance.store
        val all = store.providers()
        val mine = all.filter { it.type == ProviderType.SKYSTREAM && it.extra == sourceUrl }
        if (mine.isEmpty()) return 0
        // Locked read-modify-write: see [AppStore.updateProviders]. Rebuilding
        // from the snapshot read above would drop anything installed meanwhile.
        store.updateProviders { list ->
            list.filterNot { it.type == ProviderType.SKYSTREAM && it.extra == sourceUrl }
        }
        HikariApp.instance.providers.refresh()
        withContext(Dispatchers.IO) {
            val keep = store.providers().filter { it.type == ProviderType.SKYSTREAM }
                .map { File(it.url).parentFile?.absolutePath }
                .toSet()
            mine.forEach { cfg ->
                val parent = File(cfg.url).parentFile ?: return@forEach
                if (parent.absolutePath !in keep) runCatching { parent.deleteRecursively() }
            }
        }
        return mine.size
    }

    /** Whether the provider's plugin.js still exists on disk. */
    fun fileMissing(config: ProviderConfig): Boolean =
        config.type == ProviderType.SKYSTREAM &&
            (config.url.isBlank() || !File(config.url).exists())

    /**
     * The site a SkyStream extension actually reads: the first real entry in its
     * plugin.json `domains`, else its `baseUrl`. These extensions have no URL
     * field of their own (a provider's `url` holds the local plugin.js path), so
     * this is the only way the Home screen's WebView button can be pointed at
     * the right place. Null when the manifest declares nothing usable.
     * BLOCKING (reads plugin.json off disk).
     */
    fun siteUrlOf(config: ProviderConfig): String? =
        siteUrlOf(scriptFile(HikariApp.instance, config.id.removePrefix("sky|")))

    fun siteUrlOf(scriptFile: File): String? {
        val o = manifestOf(scriptFile) ?: return null
        runCatching { o.getJSONArray("domains") }.getOrNull()?.let { a ->
            for (i in 0 until a.length()) {
                hostUrl(a.optString(i))?.let { return it }
            }
        }
        return hostUrl(o.optString("baseUrl"))
    }

    /** The bare host of [siteUrlOf] — the key the Cloudflare records use. */
    fun siteHostOf(scriptFile: File): String? = siteUrlOf(scriptFile)
        ?.let { runCatching { java.net.URI(it).host?.lowercase() }.getOrNull() }

    /** The plugin.json sitting next to [scriptFile], or null. */
    private fun manifestOf(scriptFile: File): JSONObject? = runCatching {
        val dir = scriptFile.parentFile ?: return@runCatching null
        val f = File(dir, "plugin.json")
        if (!f.exists()) return@runCatching null
        JSONObject(f.readText())
    }.getOrNull()

    /**
     * A `https://host/` URL for a manifest-declared site, or null when the entry
     * is empty/relative/not a real hostname. Placeholder hosts are refused for
     * the same reason [iconHostOf] refuses them — the community listings ship
     * `https://stremio-hub.local` for most of their entries.
     */
    private fun hostUrl(raw: String): String? {
        val t = raw.trim()
        if (t.isBlank()) return null
        val withScheme = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.startsWith("//") -> "https:$t"
            else -> "https://$t"
        }
        val host = runCatching { java.net.URI(withScheme).host?.lowercase() }.getOrNull() ?: return null
        if (host.isBlank() || !host.contains(".")) return null
        if (PLACEHOLDER_HOSTS.any { host.contains(it) }) return null
        return "https://$host/"
    }

    private val iconCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * A SkyStream extension whose repo entry carried no icon: the logo its
     * first addon manifest declares (the `.sky` format has no icon field of its
     * own — the Stremio manifest is the only place a real logo exists), else a
     * declared `iconUrl`/`logo`/`icon`, else the site's favicon (the same
     * Google favicon service the CloudStream plugins use). Never throws —
     * BLOCKING on the addon-manifest lookup, so call it off the main thread.
     */
    fun iconFallback(config: ProviderConfig): String? {
        iconCache[config.id]?.let { return it }
        val result = runCatching {
            val json = File(pluginDir(HikariApp.instance, config.id.removePrefix("sky|")), "plugin.json")
            if (!json.exists()) return@runCatching null
            val o = runCatching { JSONObject(json.readText()) }.getOrNull() ?: return@runCatching null
            val declared = o.optString("iconUrl")
                .ifBlank { o.optString("logo") }
                .ifBlank { o.optString("icon") }
            when {
                declared.startsWith("http") -> return@runCatching declared
                declared.startsWith("//") -> return@runCatching "https:$declared"
                else -> {}
            }
            addonIcon(o)?.let { return@runCatching it }
            val host = iconHostOf(o) ?: return@runCatching null
            "https://www.google.com/s2/favicons?domain=$host&sz=64"
        }.getOrNull()
        // ConcurrentHashMap forbids null values — only cache hits.
        if (result != null) iconCache[config.id] = result
        return result
    }

    /**
     * The real logo of a `.sky`, read from the manifest of its FIRST Stremio
     * addon: the `.sky`'s plugin.json declares no icon field at all, but it
     * does list `addons`, and those manifests carry a `logo` (e.g. dramayo →
     * `https://dramayo.stream/static/dramayo.svg`). Relative addon URLs (and a
     * relative logo path) are resolved against the plugin's `baseUrl`. Returns
     * null when there is no usable addon/logo — never throws. BLOCKING.
     */
    private fun addonIcon(manifest: JSONObject): String? {
        val addons = runCatching { manifest.getJSONArray("addons") }.getOrNull() ?: return null
        if (addons.length() == 0) return null
        val base = manifest.optString("baseUrl").takeIf { it.startsWith("http") }
        for (i in 0 until addons.length()) {
            val raw = addons.optString(i).ifBlank { null } ?: continue
            val url = when {
                raw.startsWith("//") -> "https:$raw"
                raw.startsWith("http://") || raw.startsWith("https://") -> raw
                base != null -> runCatching { java.net.URI("$base/").resolve(raw).toString() }.getOrNull()
                else -> null
            } ?: continue
            val logo = runCatching {
                JSONObject(Http.getString(url) ?: "").let { m ->
                    m.optString("logo").ifBlank { m.optString("icon") }.ifBlank { null }
                }
            }.getOrNull() ?: continue
            return runCatching { java.net.URI(url).resolve(logo).toString() }.getOrNull()
        }
        return null
    }

    /**
     * A `.sky` entry from a repo listing (`plugins` array, a `pluginLists`
     * file, or a nested `repos` file). `url` is the absolute .sky download;
     * some repos instead give only a `baseUrl`, in which case the caller's
     * base is joined with a `<packageName>.sky` filename.
     */
    fun repoPlugin(o: JSONObject, baseUrl: String = ""): Cs3RepoPlugin? {
        val packageName = o.optString("packageName").ifBlank { o.optString("id") }
        val name = o.optString("name").ifBlank { packageName }
        if (name.isBlank()) return null
        val raw = o.optString("url")
        val url = when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.isNotBlank() && baseUrl.isNotBlank() -> "$baseUrl/$raw"
            raw.isNotBlank() -> raw
            baseUrl.isNotBlank() && packageName.isNotBlank() -> "$baseUrl/$packageName.sky"
            else -> return null
        }
        val versionStr = o.optString("version")
        val version = versionStr.takeWhile { it.isDigit() }.toIntOrNull()
            ?: if (versionStr.isNotBlank()) 1 else 1
        val types = runCatching { o.getJSONArray("categories") }.getOrNull()
            ?.let { a -> (0 until a.length()).mapNotNull { i -> a.optString(i).ifBlank { null } } }
            ?: runCatching { o.getJSONArray("types") }.getOrNull()
                ?.let { a -> (0 until a.length()).mapNotNull { i -> a.optString(i).ifBlank { null } } }
            ?: emptyList()
        // SkyStream listings carry a Stremio `addons` array; the first addon's
        // manifest `logo` is the extension's real icon (the .sky's own
        // plugin.json declares no icon field at all). Captured here so the row
        // can resolve it lazily — see ExtensionIcons.forRepoPlugin.
        val addons = runCatching { o.getJSONArray("addons") }.getOrNull()
            ?.let { a -> (0 until a.length()).mapNotNull { i -> a.optString(i).ifBlank { null } } }
            ?: emptyList()
        return Cs3RepoPlugin(
            name = name,
            description = o.optString("description"),
            url = url,
            iconUrl = o.optString("iconUrl")
                .ifBlank { o.optString("logo") }
                .ifBlank { o.optString("icon") }
                .ifBlank { null },
            version = version,
            tvTypes = types,
            iconManifest = addons.firstOrNull(),
            iconHost = iconHostOf(o),
            nsfw = com.hikari.app.data.ExtensionNsfw.repoEntryNsfw(o, types),
        )
    }

    /**
     * Placeholder hosts SkyStream listings publish in `baseUrl` — the real
     * extension repo ships `https://stremio-hub.local` for 20 of its 25
     * entries. A favicon lookup against one of those can never resolve, so
     * they must never be used as an icon source (they would produce a broken
     * image request per row, all of them useless).
     */
    private val PLACEHOLDER_HOSTS = listOf(
        ".local", ".invalid", ".test", ".example", "localhost", "stremio-hub",
    )

    /**
     * The extension's own site host, used as the LAST-resort icon source: the
     * first real entry in `domains`, else the `baseUrl`/`url` host, with
     * placeholder hosts (see [PLACEHOLDER_HOSTS]) skipped. Null when the
     * listing names only a placeholder — better no icon than a guaranteed 404.
     */
    private fun iconHostOf(o: JSONObject): String? {
        val candidates = mutableListOf<String>()
        runCatching { o.getJSONArray("domains") }.getOrNull()?.let { a ->
            for (i in 0 until a.length()) {
                a.optString(i).ifBlank { null }?.let { candidates.add(it) }
            }
        }
        o.optString("baseUrl").ifBlank { null }?.let { candidates.add(it) }
        o.optString("url").ifBlank { null }?.let { candidates.add(it) }
        for (c in candidates) {
            val host = runCatching {
                val withScheme = if (c.startsWith("http")) c else "https://$c"
                java.net.URI(withScheme).host?.lowercase()
            }.getOrNull() ?: continue
            if (host.isBlank() || !host.contains(".")) continue
            if (PLACEHOLDER_HOSTS.any { host.contains(it) }) continue
            return host
        }
        return null
    }

    /**
     * Resolves what the user typed in "Add SkyStream source". Mirrors the
     * official app: a full URL is used as-is, a bare host/path gets an https://
     * prefix, `skystream://host/path` share links are unwrapped, and a bare
     * shortcode is looked up under cutt.ly's `sky-` namespace (following the
     * redirect to the real repo.json). Returns null when nothing resolves.
     */
    suspend fun resolveRepoUrl(raw: String): String? = withContext(Dispatchers.IO) {
        val text = raw.trim()
        if (text.isBlank()) return@withContext null
        if (text.startsWith("http://") || text.startsWith("https://")) return@withContext text
        val scheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://(.+)$").find(text)
        if (scheme != null) return@withContext "https://" + scheme.groupValues[1]
        if (text.contains("/") || Regex("^[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)+").containsMatchIn(text)) {
            return@withContext "https://$text"
        }
        if (!Regex("^[a-zA-Z0-9!_-]+$").matches(text)) return@withContext null
        for (candidate in listOf("https://cutt.ly/sky-$text", "https://cutt.ly/$text")) {
            val resolved = runCatching {
                val resp = Http.get(candidate)
                resp.use { r -> if (r.isSuccessful) r.request.url.toString() else null }
            }.getOrNull()
            if (!resolved.isNullOrBlank() &&
                !resolved.contains("cutt.ly/404") &&
                !resolved.trimEnd('/').endsWith("cutt.ly")
            ) return@withContext resolved
        }
        null
    }

    /** The `.sky` bytes plus its plugin.json, or null when the zip doesn't look
     *  like a SkyStream extension. Entry lookup is suffix-based, so a zip that
     *  nests its files in a folder (`plugin/x/plugin.js`) still works. */
    private class Parts(val js: ByteArray?, val manifest: JSONObject?)

    private fun unzip(bytes: ByteArray): Parts? {
        var js: ByteArray? = null
        var jsDepth = Int.MAX_VALUE
        var json: JSONObject? = null
        var jsonDepth = Int.MAX_VALUE
        val zin = runCatching { ZipInputStream(ByteArrayInputStream(bytes)) }.getOrNull()
            ?: return null
        try {
            zin.use { z ->
                while (true) {
                    val e = runCatching { z.nextEntry }.getOrNull() ?: break
                    if (e.isDirectory) continue
                    val norm = e.name.replace('\\', '/').trimStart('/')
                    val lower = norm.lowercase()
                    val depth = norm.count { it == '/' }
                    val isJs = lower == "plugin.js" || lower.endsWith("/plugin.js")
                    val isJson = lower == "plugin.json" || lower.endsWith("/plugin.json")
                    if (!isJs && !isJson) continue
                    val data = z.readBytes()
                    if (data.isEmpty()) continue
                    if (isJs && depth < jsDepth) {
                        js = data
                        jsDepth = depth
                    }
                    if (isJson && depth < jsonDepth) {
                        json = runCatching {
                            JSONObject(String(data, Charsets.UTF_8))
                        }.getOrNull()
                        jsonDepth = depth
                    }
                }
            }
        } catch (e: Throwable) {
            return null
        }
        return Parts(js, json)
    }

    private fun fallbackName(sourceUrl: String?): String {
        val file = sourceUrl?.substringBefore('?')?.substringAfterLast('/') ?: return ""
        return file.removeSuffix(".sky").removeSuffix(".zip")
    }
}
