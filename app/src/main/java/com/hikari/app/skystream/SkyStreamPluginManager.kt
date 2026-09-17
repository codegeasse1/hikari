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
            HikariApp.instance.store.addProvider(
                ProviderConfig(
                    id = "sky|" + packageName,
                    name = displayName,
                    type = ProviderType.SKYSTREAM,
                    url = jsFile.absolutePath,
                    iconUrl = iconUrl ?: manifest.optString("iconUrl").ifBlank { null },
                    extra = sourceUrl ?: packageName,
                )
            )
            HikariApp.instance.providers.refresh()
            Result.success(1)
        }
    }

    /** Removes every SKYSTREAM provider that came from [sourceUrl] (+ its dir). */
    suspend fun uninstall(context: Context, sourceUrl: String) {
        val store = HikariApp.instance.store
        val all = store.providers()
        val mine = all.filter { it.type == ProviderType.SKYSTREAM && it.extra == sourceUrl }
        if (mine.isEmpty()) return
        store.saveProviders(all.filterNot { it.type == ProviderType.SKYSTREAM && it.extra == sourceUrl })
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
    }

    /** Whether the provider's plugin.js still exists on disk. */
    fun fileMissing(config: ProviderConfig): Boolean =
        config.type == ProviderType.SKYSTREAM &&
            (config.url.isBlank() || !File(config.url).exists())

    private val iconCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * A SkyStream extension whose repo entry carried no icon: use the one its own
     * `plugin.json` declares, else the `baseUrl` site's favicon (the same Google
     * favicon service the CloudStream plugins use). Never throws.
     */
    fun iconFallback(config: ProviderConfig): String? {
        iconCache[config.id]?.let { return it }
        val result = runCatching {
            val json = File(pluginDir(HikariApp.instance, config.id.removePrefix("sky|")), "plugin.json")
            if (!json.exists()) return@runCatching null
            val o = runCatching { JSONObject(json.readText()) }.getOrNull() ?: return@runCatching null
            val declared = o.optString("iconUrl")
                .ifBlank { o.optString("logo").ifBlank { o.optString("icon") } }
            if (declared.startsWith("http")) return@runCatching declared
            val base = o.optString("baseUrl").takeIf { it.startsWith("http") }
                ?: return@runCatching null
            val host = java.net.URI(base).host ?: return@runCatching null
            "https://www.google.com/s2/favicons?domain=$host&sz=64"
        }.getOrNull()
        // ConcurrentHashMap forbids null values — only cache hits.
        if (result != null) iconCache[config.id] = result
        return result
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
        return Cs3RepoPlugin(
            name = name,
            description = o.optString("description"),
            url = url,
            iconUrl = o.optString("iconUrl").ifBlank { o.optString("logo").ifBlank { null } },
            version = version,
            tvTypes = types,
        )
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
