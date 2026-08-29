package com.hikari.app.nuvio

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
import java.io.File

/**
 * Install/uninstall of Nuvio JS scrapers, plus first-run seeding of the three
 * canonical Nuvio provider repos and a few pre-installed providers so the
 * feature works out of the box.
 */
object NuvioPluginManager {

    fun scrapersDir(context: Context): File =
        File(context.filesDir, "nuvio/scrapers").apply { mkdirs() }

    fun scraperFile(context: Context, providerId: String): File =
        File(scrapersDir(context), providerId.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".js")

    const val MAX_BYTES = 5 * 1024 * 1024

    /** The three well-known Nuvio provider repositories (manifest.json). */
    val DEFAULT_REPOS = listOf(
        Triple(
            "https://raw.githubusercontent.com/tapframe/nuvio-providers/main/manifest.json",
            "Yoru's Nuvio Repo",
            "English nuvio providers (vixsrc, moviebox, showbox, …)",
        ),
        Triple(
            "https://raw.githubusercontent.com/Gowaru/gowaru-nuvio-providers/main/manifest.json",
            "Gowaru's Nuvio Repo",
            "Nuvio providers by Gowaru (French-oriented)",
        ),
        Triple(
            "https://raw.githubusercontent.com/phisher98/phisher-nuvio-providers/main/manifest.json",
            "Phisher's Nuvio Repo",
            "Nuvio providers by Phisher98",
        ),
    )

    /** Curated providers pre-installed on first run (from Yoru's repo). */
    private val SEED_PROVIDERS = listOf("vixsrc", "moviebox", "showbox")

    /** Adds the default repos once and pre-installs a few providers so nuvio
     *  sources are available immediately. Non-fatal on any failure. */
    suspend fun seedDefaults(context: Context, store: AppStore) {
        for ((url, name, desc) in DEFAULT_REPOS) {
            runCatching { store.addCs3Repo(Cs3Repo(url, name, desc, RepoKind.NUVIO)) }
        }
        // Only pre-install providers on the very first run (no NUVIO provider
        // installed yet) — afterwards the user curates their own set.
        if (store.providers().any { it.type == ProviderType.NUVIO }) return
        val manifest = runCatching { Http.getString(DEFAULT_REPOS[0].first) }.getOrNull()
            ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        val base = DEFAULT_REPOS[0].first.substringBeforeLast('/')
        val scrapers = runCatching { manifest.getJSONArray("scrapers") }.getOrNull() ?: return
        for (i in 0 until scrapers.length()) {
            val o = runCatching { scrapers.getJSONObject(i) }.getOrNull() ?: continue
            val name = o.optString("name")
            if (name.isBlank() || !SEED_PROVIDERS.any { name.equals(it, true) }) continue
            val filename = o.optString("filename")
            if (filename.isBlank()) continue
            val codeUrl = "$base/$filename"
            val bytes = runCatching { Http.fetchBytesRobust(codeUrl) }.getOrNull() ?: continue
            val rawName = filename.substringAfterLast('/')
            runCatching { installScraper(context, bytes, rawName, codeUrl, o.optString("logo").ifBlank { null }) }
        }
    }

    /** Writes a scraper JS file and registers it as a NUVIO provider. The
     *  provider code is validated inside the runtime before being accepted. */
    suspend fun installScraper(
        context: Context,
        bytes: ByteArray,
        rawName: String,
        sourceUrl: String? = null,
        iconUrl: String? = null,
    ): Result<Int> = withContext(Dispatchers.IO) {
        if (bytes.size > MAX_BYTES) {
            return@withContext Result.failure(Exception("File too large (max 5MB)"))
        }
        val clean = rawName.substringAfterLast('/').ifBlank { "provider.js" }
            .let { if (it.endsWith(".js", true)) it else "$it.js" }
        val file = File(scrapersDir(context), clean)
        file.setWritable(true)
        val wrote = runCatching { file.writeBytes(bytes) }
        if (wrote.isFailure) {
            return@withContext Result.failure(
                Exception("Could not write scraper file: ${wrote.exceptionOrNull()?.message}")
            )
        }
        val source = runCatching { file.readText() }.getOrNull()
        if (source.isNullOrBlank()) {
            file.delete()
            return@withContext Result.failure(Exception("Scraper file is empty"))
        }
        // Load the module in a pooled WebView to confirm it exports getStreams.
        val verdict = NuvioRuntime.validate(context, source)
        if (!verdict.startsWith("OK")) {
            file.delete()
            val detail = if (verdict.startsWith("ERR:")) verdict.removePrefix("ERR:").take(300)
            else "no getStreams export found"
            return@withContext Result.failure(
                Exception("Not a valid nuvio provider: $detail")
            )
        }
        val id = "nuvio|" + clean.hashCode()
        HikariApp.instance.store.addProvider(
            ProviderConfig(
                id = id,
                name = clean.removeSuffix(".js"),
                type = ProviderType.NUVIO,
                url = file.absolutePath,
                iconUrl = iconUrl,
                extra = sourceUrl ?: clean,
            )
        )
        HikariApp.instance.providers.refresh()
        Result.success(1)
    }

    /** Removes every NUVIO provider that came from [sourceUrl] (and its file). */
    suspend fun uninstallScraper(context: Context, sourceUrl: String) {
        val store = HikariApp.instance.store
        val all = store.providers()
        val paths = all.filter { it.type == ProviderType.NUVIO && it.extra == sourceUrl }
            .map { it.url }.toSet()
        store.saveProviders(all.filter { it.type != ProviderType.NUVIO || it.extra != sourceUrl })
        HikariApp.instance.providers.refresh()
        withContext(Dispatchers.IO) {
            val remaining = store.providers().map { it.url }.toSet()
            val base = context.filesDir.absolutePath
            paths.forEach { p ->
                if (p.startsWith(base) && p !in remaining) runCatching { File(p).delete() }
            }
        }
    }

    /** Whether the provider's scraper file still exists on disk. */
    fun fileMissing(config: ProviderConfig): Boolean =
        config.type == ProviderType.NUVIO &&
            (config.url.isBlank() || !File(config.url).exists())

    /** Builds a Cs3RepoPlugin for a repo listing entry (manifest `scrapers`
     *  array). baseUrl is the manifest URL minus the /manifest.json suffix. */
    fun repoPlugin(o: JSONObject, baseUrl: String): Cs3RepoPlugin? {
        val name = o.optString("name").ifBlank { return null }
        val filename = o.optString("filename").ifBlank { return null }
        val versionStr = o.optString("version")
        val version = versionStr.takeWhile { it.isDigit() }.toIntOrNull()
            ?: if (versionStr.isNotBlank()) 1 else 1
        val types = runCatching { o.getJSONArray("supportedTypes") }.getOrNull()
            ?.let { a -> (0 until a.length()).mapNotNull { i -> a.optString(i).ifBlank { null } } }
            ?: emptyList()
        return Cs3RepoPlugin(
            name = name,
            description = o.optString("description"),
            url = "$baseUrl/$filename",
            iconUrl = o.optString("logo").ifBlank { null },
            version = version,
            tvTypes = types,
        )
    }
}
