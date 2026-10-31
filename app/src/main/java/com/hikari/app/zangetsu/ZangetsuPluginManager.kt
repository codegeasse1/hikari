package com.hikari.app.zangetsu

import android.content.Context
import com.hikari.app.HikariApp
import com.hikari.app.data.AppStore
import com.hikari.app.data.Cs3Repo
import com.hikari.app.data.Cs3RepoPlugin
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import com.hikari.app.data.RepoKind
import com.hikari.app.data.SourceUrls
import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Install/uninstall of Zangetsu providers, plus first-run seeding of the
 * official Zangetsu repository.
 *
 * A Zangetsu repository publishes an `index.json` manifest —
 * `{name, description, sources: [{id, name, version, type, lang, file, logo,
 * nsfw}]}` — where `file`/`logo` resolve against the manifest URL. Each
 * source is ONE self-contained `.js` scraper, kept as one folder per
 * provider:
 *
 *   filesDir/zangetsu/providers/<id>/provider.js
 *   filesDir/zangetsu/providers/<id>/meta.json
 *
 * The folder path is stored in the provider's `url` and the source file URL
 * in `extra`, exactly how every other engine records its source, so
 * uninstall and the provenance label work the same way they do for a
 * CloudStream/Hikari/Nuvio/Vega extension.
 */
object ZangetsuPluginManager {

    /** Ceiling for one provider file. The published files are 14–36 KB. */
    const val MAX_BYTES = 2 * 1024 * 1024

    /** The built-in Zangetsu provider repositories (index.json manifests). */
    val DEFAULT_REPOS = listOf(
        Triple(
            "https://raw.githubusercontent.com/Spyou/zangetsu-providers/main/index.json",
            "Zangetsu Providers",
            "The official Zangetsu repo (HiAnime, HDHub4u, VegaMovies, …)",
        ),
    )

    fun providersDir(context: Context): File =
        File(context.filesDir, "zangetsu/providers").apply { mkdirs() }

    fun providerDir(context: Context, id: String): File =
        File(providersDir(context), safe(id))

    fun scriptFile(context: Context, id: String): File =
        File(providerDir(context, id), "provider.js")

    fun metaFile(context: Context, id: String): File =
        File(providerDir(context, id), "meta.json")

    private fun safe(name: String): String =
        name.replace(Regex("[^A-Za-z0-9_.-]"), "_").ifBlank { "provider" }

    /** Adds the official Zangetsu repo once, so the folder is never empty. No
     *  provider is pre-installed — the user picks. Non-fatal on failure. */
    suspend fun seedDefaults(context: Context, store: AppStore) {
        for ((url, name, desc) in DEFAULT_REPOS) {
            runCatching { store.seedCs3Repo(Cs3Repo(url, name, desc, RepoKind.ZANGETSU)) }
        }
    }

    private fun resolveUrl(manifestUrl: String, ref: String): String {
        val r = ref.trim()
        if (r.isBlank()) return ""
        if (r.startsWith("http://") || r.startsWith("https://")) return r
        val base = manifestUrl.trim().trimEnd('/').substringBeforeLast('/')
        return "$base/${r.trimStart('/')}"
    }

    /**
     * The sources a Zangetsu manifest lists. Entries carry their install URL
     * (the absolute `.js` file), icon, version/type/lang line and the repo's
     * own nsfw flag.
     */
    fun repoPlugins(manifestText: String, manifestUrl: String): List<Cs3RepoPlugin> {
        val root = runCatching { JSONObject(manifestText) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("sources") ?: return emptyList()
        val out = LinkedHashMap<String, Cs3RepoPlugin>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").trim()
            if (id.isBlank()) continue
            val file = resolveUrl(manifestUrl, o.optString("file"))
            if (file.isBlank()) continue
            val name = o.optString("name").trim().ifBlank { id }
            val version = o.optString("version").trim()
            val type = o.optString("type").trim()
            val lang = o.optString("lang").trim()
            val desc = listOfNotNull(
                if (version.isNotBlank()) "v$version" else null,
                if (type.isNotBlank()) type else null,
                if (lang.isNotBlank()) lang else null,
            ).joinToString(" · ")
            out[file] = Cs3RepoPlugin(
                name = name,
                description = desc,
                url = file,
                iconUrl = resolveUrl(manifestUrl, o.optString("logo")).ifBlank { null },
                version = version.takeWhile { it.isDigit() }.toIntOrNull() ?: 1,
                tvTypes = emptyList(),
            )
        }
        return out.values.toList()
    }

    /** The manifest entry behind an installed provider (for type/nsfw). */
    fun readMeta(context: Context, id: String): JSONObject =
        runCatching { JSONObject(metaFile(context, id).readText()) }.getOrDefault(JSONObject())

    suspend fun install(context: Context, plugin: Cs3RepoPlugin): Result<Int> = withContext(Dispatchers.IO) {
        val fileUrl = plugin.url.trim()
        if (fileUrl.isBlank()) {
            return@withContext Result.failure(Exception("This entry has no provider file"))
        }
        val id = fileUrl.trimEnd('/').substringAfterLast('/').removeSuffix(".js").trim()
            .replace(Regex("[^A-Za-z0-9_.-]"), "_").ifBlank {
                return@withContext Result.failure(Exception("This entry has no provider name"))
            }
        val bytes = runCatching { Http.fetchBytesCancellable(fileUrl) }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            return@withContext Result.failure(
                Exception("Could not download this provider — its repo did not serve the file")
            )
        }
        if (bytes.size > MAX_BYTES) {
            return@withContext Result.failure(Exception("A provider file is too large (max ${MAX_BYTES / 1024 / 1024}MB)"))
        }
        val dir = providerDir(context, id)
        val wrote = runCatching {
            dir.mkdirs()
            val f = File(dir, "provider.js")
            f.setWritable(true)
            f.writeBytes(bytes)
            val mf = File(dir, "meta.json")
            mf.setWritable(true)
            mf.writeText(
                JSONObject()
                    .put("id", id)
                    .put("name", plugin.name.ifBlank { id })
                    .put("icon", plugin.iconUrl ?: "")
                    .toString(),
            )
            true
        }.getOrDefault(false)
        if (!wrote) {
            return@withContext Result.failure(Exception("Could not write the provider to storage"))
        }
        val verdict = ZangetsuRuntime.validate(context, dir, id)
        if (verdict != "OK") {
            runCatching { dir.deleteRecursively() }
            val detail = if (verdict.startsWith("ERR:")) verdict.removePrefix("ERR:").take(300)
            else "it doesn't export getVideoSources plus a listing"
            return@withContext Result.failure(Exception("Not a valid Zangetsu provider: $detail"))
        }
        // Capture the source's own site root for the verification globe
        // (a file read later, never an engine boot).
        runCatching {
            val payload = ZangetsuRuntime.call(dir, id, "getInfo", "[]")
            val data = JSONObject(payload).optJSONObject("data")
            val base = data?.optString("baseUrl").orEmpty()
            if (base.startsWith("http")) {
                val mf = File(dir, "meta.json")
                val meta = JSONObject(mf.readText())
                mf.writeText(meta.put("baseUrl", base).toString())
            }
        }
        HikariApp.instance.store.addProvider(
            ProviderConfig(
                id = "zangetsu|$id",
                name = plugin.name.ifBlank { id },
                type = ProviderType.ZANGETSU,
                url = dir.absolutePath,
                iconUrl = plugin.iconUrl,
                extra = fileUrl,
            )
        )
        HikariApp.instance.providers.refresh()
        runCatching { com.hikari.app.data.MetaCache.bumpEpisodesEpoch() }
        Result.success(1)
    }

    /** The provider files on disk for [config], falling back to the canonical
     *  location when the stored path no longer resolves. */
    fun dirOf(config: ProviderConfig): File {
        val stored = File(config.url)
        if (stored.isDirectory) return stored
        val canonical = providerDir(HikariApp.instance, config.id.substringAfter("zangetsu|", ""))
        return if (canonical.isDirectory) canonical else stored
    }

    /** The provider's site root (from its own getInfo, captured at install)
     *  for the verification globe — a file read, never an engine boot. */
    fun siteUrlOf(config: ProviderConfig): String? {
        if (config.type != ProviderType.ZANGETSU) return null
        return runCatching { JSONObject(metaFile(HikariApp.instance, config.id.substringAfter("zangetsu|", "")).readText()).optString("baseUrl") }
            .getOrNull()?.takeIf { it.startsWith("http") }
    }

    /** Removes every ZANGETSU provider installed from [sourceUrl]. Returns how
     *  many rows were removed. */
    suspend fun uninstall(context: Context, sourceUrl: String): Int {
        val store = HikariApp.instance.store
        val wanted = SourceUrls.matchKeys(sourceUrl)
        val mine = store.providers().filter { p ->
            p.type == ProviderType.ZANGETSU && (
                p.extra == sourceUrl ||
                    (p.extra != null && SourceUrls.matchKeys(p.extra).any { it in wanted })
                )
        }
        if (mine.isEmpty()) return 0
        val ids = mine.map { it.id }.toSet()
        store.updateProviders { list -> list.filterNot { it.id in ids } }
        HikariApp.instance.providers.refresh()
        withContext(Dispatchers.IO) {
            val keep = store.providers().filter { it.type == ProviderType.ZANGETSU }
                .map { File(it.url).absolutePath }
                .toSet()
            mine.forEach { cfg ->
                val dir = File(cfg.url)
                if (dir.absolutePath !in keep) runCatching { dir.deleteRecursively() }
            }
        }
        return mine.size
    }

    /** Whether the provider's folder is gone or has no script left. */
    fun fileMissing(config: ProviderConfig): Boolean {
        if (config.type != ProviderType.ZANGETSU) return false
        val dir = File(config.url)
        if (!dir.isDirectory) return true
        return !File(dir, "provider.js").isFile
    }
}
