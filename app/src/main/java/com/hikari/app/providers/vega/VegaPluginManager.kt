package com.hikari.app.providers.vega

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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/**
 * Install/uninstall of Vega providers, plus first-run seeding of the official
 * Vega repository.
 *
 * A Vega provider repository publishes a **bare JSON array** at `manifest.json`
 * — every entry is one provider — and the provider's code lives in
 * `dist/<value>/{catalog,posts,meta,stream,episodes,settings}.js`. There is no
 * single downloadable artifact: an install fetches whichever of those six files
 * exist (a movie-only provider has no episodes.js, `moviesApi` has only
 * stream.js) and keeps them in one folder per provider:
 *
 *   filesDir/vega/providers/<value>/posts.js
 *   filesDir/vega/providers/<value>/stream.js
 *   …
 *
 * The folder path is stored in the provider's `url` and the manifest entry's
 * dist URL in `extra`, exactly how every other engine records its source, so
 * uninstall and the provenance label work the same way they do for a
 * CloudStream/Hikari/Nuvio extension.
 */
object VegaPluginManager {

    /** Ceiling for one provider file. The published files are 1–40 KB; anything
     *  past this is not a provider. */
    const val MAX_BYTES = 2 * 1024 * 1024

    /** The six module names a provider may publish. */
    val FILE_NAMES = listOf("catalog", "posts", "meta", "stream", "episodes", "settings")

    /** The built-in Vega provider repositories (manifest.json, a bare array). */
    val DEFAULT_REPOS = listOf(
        Triple(
            "https://raw.githubusercontent.com/Zenda-Cross/vega-providers/main/manifest.json",
            "Vega Providers",
            "The official Vega repo (AniKoto, 4KHDHub, Showbox, NetflixMirror, …)",
        ),
    )

    fun providersDir(context: Context): File =
        File(context.filesDir, "vega/providers").apply { mkdirs() }

    fun providerDir(context: Context, value: String): File =
        File(providersDir(context), safe(value))

    fun fileOf(context: Context, value: String, name: String): File =
        File(providerDir(context, value), "$name.js")

    private fun safe(name: String): String =
        name.replace(Regex("[^A-Za-z0-9_.-]"), "_").ifBlank { "provider" }

    /** Adds the official Vega repo once, so the folder is never empty. No
     *  provider is pre-installed — there are 50+ and which ones work changes
     *  with the sites, so the user picks. Non-fatal on failure. */
    suspend fun seedDefaults(context: Context, store: AppStore) {
        for ((url, name, desc) in DEFAULT_REPOS) {
            runCatching { store.addCs3Repo(Cs3Repo(url, name, desc, RepoKind.VEGA)) }
        }
    }

    /**
     * The providers a Vega manifest lists. The manifest is a bare array of
     * `{display_name, value, version, icon, disabled, hasSettings}`; `disabled`
     * entries are the repo owner's tombstone for a provider that stopped
     * working, so they are skipped rather than offered. `url` is the provider's
     * dist folder (the install path appends `<name>.js` to it).
     */
    fun repoPlugins(manifestText: String, manifestUrl: String): List<Cs3RepoPlugin> {
        val arr = runCatching { JSONArray(manifestText) }.getOrNull() ?: return emptyList()
        val base = manifestUrl.trim().trimEnd('/').substringBeforeLast('/')
        val out = LinkedHashMap<String, Cs3RepoPlugin>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optBoolean("disabled", false)) continue
            val value = o.optString("value").trim()
            if (value.isBlank()) continue
            val name = o.optString("display_name").trim().ifBlank { value }
            val versionText = o.optString("version").trim()
            val version = versionText.takeWhile { it.isDigit() }.toIntOrNull()
                ?: if (versionText.isNotBlank()) 1 else 1
            val icon = o.optString("icon")
                .ifBlank { o.optString("iconUrl") }
                .ifBlank { o.optString("logo") }
                .ifBlank { null }
            val url = "$base/dist/$value"
            out[url] = Cs3RepoPlugin(
                name = name,
                description = if (versionText.isNotBlank()) "v$versionText" else "",
                url = url,
                iconUrl = icon,
                version = version,
                tvTypes = emptyList(),
            )
        }
        return out.values.toList()
    }

    /**
     * Downloads one provider's files (in parallel — the repo is on GitHub and
     * the six files are independent) and installs them.
     *
     * Only files that really exist are kept: a 404 is the normal answer for the
     * five providers with no episodes.js and for `moviesApi`, which publishes
     * only stream.js. The install succeeds as long as the provider exports
     * something usable — [VegaRuntime.validate] decides that by loading its
     * stream/posts/meta module.
     */
    suspend fun install(context: Context, plugin: Cs3RepoPlugin): Result<Int> = withContext(Dispatchers.IO) {
        val value = valueOf(plugin.url)
        if (value.isBlank()) {
            return@withContext Result.failure(Exception("This entry has no provider name"))
        }
        val dist = plugin.url.trimEnd('/')
        val fetched = coroutineScope {
            FILE_NAMES.map { name ->
                async { name to runCatching { Http.fetchBytesCancellable("$dist/$name.js") }.getOrNull() }
            }.map { it.await() }
        }
        val files = fetched.filter { (_, bytes) -> bytes != null && bytes.isNotEmpty() }
            .associate { (name, bytes) -> name to bytes!! }
        if (files.isEmpty()) {
            return@withContext Result.failure(
                Exception("Could not download this provider — its repo did not serve any of its files")
            )
        }
        if (files.values.any { it.size > MAX_BYTES }) {
            return@withContext Result.failure(Exception("A provider file is too large (max ${MAX_BYTES / 1024 / 1024}MB)"))
        }
        if (files.keys.none { it == "stream" || it == "posts" || it == "meta" }) {
            return@withContext Result.failure(Exception("Not a Vega provider (no posts/stream/meta module)"))
        }
        val dir = providerDir(context, value)
        val wrote = runCatching {
            dir.mkdirs()
            // A re-install replaces the provider: drop the previous files first
            // so a module that is gone from the repo does not linger.
            dir.listFiles()?.forEach { f ->
                if (f.name.endsWith(".js")) f.setWritable(true)
            }
            FILE_NAMES.forEach { name ->
                val f = File(dir, "$name.js")
                val bytes = files[name]
                if (bytes == null) {
                    if (f.exists()) f.delete()
                } else {
                    f.setWritable(true)
                    f.writeBytes(bytes)
                }
            }
            true
        }.getOrDefault(false)
        if (!wrote) {
            return@withContext Result.failure(Exception("Could not write the provider to storage"))
        }
        val verdict = VegaRuntime.validate(context, dir, value, value)
        if (!verdict.startsWith("OK")) {
            runCatching { dir.deleteRecursively() }
            val detail = if (verdict.startsWith("ERR:")) verdict.removePrefix("ERR:").take(300)
            else "it doesn't export any of getPosts/getStream/getMeta"
            return@withContext Result.failure(Exception("Not a valid Vega provider: $detail"))
        }
        HikariApp.instance.store.addProvider(
            ProviderConfig(
                id = "vega|$value",
                name = plugin.name.ifBlank { value },
                type = ProviderType.VEGA,
                url = dir.absolutePath,
                iconUrl = plugin.iconUrl,
                extra = plugin.url,
            )
        )
        HikariApp.instance.providers.refresh()
        Result.success(1)
    }

    /** The provider files on disk for [config], falling back to the canonical
     *  location when the stored path no longer resolves (a restored backup, a
     *  moved data dir). */
    fun dirOf(config: ProviderConfig): File {
        val stored = File(config.url)
        if (stored.isDirectory) return stored
        val canonical = providerDir(HikariApp.instance, valueOf(config.id.substringAfter("vega|", "")))
        return if (canonical.isDirectory) canonical else stored
    }

    /** Removes every VEGA provider installed from [sourceUrl] (and its folder
     *  once nothing references it). Returns how many rows were removed, so a
     *  caller never reports a successful uninstall of something that wasn't
     *  installed. */
    suspend fun uninstall(context: Context, sourceUrl: String): Int {
        val store = HikariApp.instance.store
        val wanted = com.hikari.app.data.SourceUrls.matchKeys(sourceUrl)
        val mine = store.providers().filter { p ->
            p.type == ProviderType.VEGA && (
                p.extra == sourceUrl ||
                    (p.extra != null && com.hikari.app.data.SourceUrls.matchKeys(p.extra).any { it in wanted })
                )
        }
        if (mine.isEmpty()) return 0
        val ids = mine.map { it.id }.toSet()
        // Locked read-modify-write: see [AppStore.updateProviders].
        store.updateProviders { list -> list.filterNot { it.id in ids } }
        HikariApp.instance.providers.refresh()
        withContext(Dispatchers.IO) {
            val keep = store.providers().filter { it.type == ProviderType.VEGA }
                .map { File(it.url).absolutePath }
                .toSet()
            mine.forEach { cfg ->
                val dir = File(cfg.url)
                if (dir.absolutePath !in keep) runCatching { dir.deleteRecursively() }
            }
        }
        return mine.size
    }

    /** Whether the provider's folder is gone or has no modules left. */
    fun fileMissing(config: ProviderConfig): Boolean {
        if (config.type != ProviderType.VEGA) return false
        val dir = File(config.url)
        if (!dir.isDirectory) return true
        return dir.listFiles()?.none { it.isFile && it.name.endsWith(".js") } ?: true
    }

    private fun valueOf(distUrl: String): String =
        distUrl.trimEnd('/').substringAfterLast('/').trim()
}
