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

    /** The built-in Nuvio provider repositories (manifest.json). */
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
        Triple(
            "https://plugin.eclipsia.dpdns.org/manifest.json",
            "Eclipsia",
            "Eclipsia nuvio providers (HDHub4u, VegaMovies, AnimeWorld, …)",
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
        // Vendored fix: replace known-broken upstream providers with the
        // patched JS shipped in assets (see PROVIDER_PATCHES). The original
        // bytes are passed so the patch is content-gated (a same-named but
        // different build is never clobbered).
        val effective = patchedBytes(sourceUrl, bytes) ?: bytes
        val clean = rawName.substringAfterLast('/').ifBlank { "provider.js" }
            .let { if (it.endsWith(".js", true)) it else "$it.js" }
        val file = File(scrapersDir(context), clean)
        file.setWritable(true)
        val wrote = runCatching { file.writeBytes(effective) }
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

    /**
     * Vendored fixes for upstream nuvio providers that are broken at the
     * source (their repo owners are unreachable/slow to fix). Each entry maps
     * a URL fragment of the provider's `sourceUrl` to a patched JS file shipped
     * in assets/nuvio/patches/. When a provider matching a fragment is
     * installed (or re-patched on launch), the patched file is used instead of
     * the upstream bytes.
     *
     * vornix.js  — MoviesDrive: hubcloud download links use a newer button
     *              layout ("Download [Server: 10Gbps]" → pixel.hubcloud.cx)
     *              that the upstream filter never matched, so no links were
     *              ever extracted.
     * streamflix.js — the upstream provider returns only the dead wasabisys
     *              premium bucket (NoSuchBucket) and requires a WebSocket
     *              (which hikari's QuickJS runtime does not have) for TV; the
     *              patched build probes every server, drops dead ones and uses
     *              deterministic TV paths.
     * 4khdhub.js — upstream's Chrome/91 desktop User-Agent is rejected by the
     *              site's Cloudflare firewall ("Access denied"), so every title
     *              came back with no sources. The patch sends a modern mobile
     *              browser UA plus browser Accept/Accept-Language/Referer.
     *              CAREFUL: `4khdhub.js` also exists in All-in-One-Nuvio, but
     *              that repo ships a completely different (newer, obfuscated)
     *              provider that already returns the required Referer via
     *              `behaviorHints.proxyHeaders.request`. The old patch matched
     *              by filename alone and CLOBBERED that working provider with
     *              the Yoru build, whose streams carry no headers — which is
     *              exactly why 4KHDHub "just skipped" without playing. So this
     *              patch is content-gated to the Yoru/TVVVV upstream (it must
     *              contain "4khdhub.click") and source-gated to Yoru's repo;
     *              a provider from any other repo is left untouched, and an
     *              already-clobbered one is RESTORED from its source URL.
     * zevran.js  — the VAplayer API only answers when the Referer/Origin is the
     *              player EMBED page (nextgencloudfabric.com/embed/…), not the
     *              bare site root upstream sent; the patch also falls back to
     *              the tmdb id and attaches the play-headers.
     */
    private data class ProviderPatch(
        val fragment: String,
        val asset: String,
        /** Substring the SOURCE URL must contain for the patch to be allowed. */
        val sourceMustContain: String? = null,
        /** Substring the ORIGINAL upstream bytes must contain for the patch to
         *  apply — a fingerprint of the exact build the patch was written for. */
        val contentMustContain: String? = null,
    )

    private val PROVIDER_PATCHES = listOf(
        ProviderPatch("vornix.js", "nuvio/patches/vornix.js"),
        ProviderPatch("streamflix.js", "nuvio/patches/streamflix.js"),
        ProviderPatch(
            fragment = "4khdhub.js",
            asset = "nuvio/patches/4khdhub.js",
            sourceMustContain = "tapframe/nuvio-providers",
            contentMustContain = "4khdhub.click",
        ),
        ProviderPatch("zevran.js", "nuvio/patches/zevran.js"),
    )

    private fun patchFor(sourceUrl: String?): ProviderPatch? {
        val url = sourceUrl ?: return null
        return PROVIDER_PATCHES.firstOrNull {
            url.endsWith(it.fragment, ignoreCase = true) ||
                url.contains("/${it.fragment}", ignoreCase = true)
        }
    }

    private fun assetBytes(asset: String): ByteArray? = runCatching {
        HikariApp.instance.assets.open(asset).use { it.readBytes() }
    }.getOrNull()

    private fun patchedBytes(sourceUrl: String?, original: ByteArray? = null): ByteArray? {
        val patch = patchFor(sourceUrl) ?: return null
        // Content guard: never replace a DIFFERENT build that merely shares the
        // filename (see the 4khdhub note above).
        val marker = patch.contentMustContain
        if (marker != null) {
            val text = original?.let { runCatching { String(it, Charsets.UTF_8) }.getOrNull() }
            if (text == null || !text.contains(marker)) return null
        }
        return assetBytes(patch.asset)
    }

    /** Applies the vendored patches to ALREADY-INSTALLED providers whose file
     *  on disk still matches the (broken) upstream bytes — i.e. providers the
     *  user installed before the patch shipped — AND restores providers whose
     *  file was clobbered by a patch that never should have matched them (the
     *  4KHDHub filename collision). Rewrites the file in place and re-validates
     *  so the fix takes effect without a reinstall. */
    suspend fun applyPatchesToInstalled(context: Context) {
        val store = HikariApp.instance.store
        val targets = store.providers().filter { it.type == ProviderType.NUVIO }
        for (cfg in targets) {
            val patch = patchFor(cfg.extra) ?: continue
            val patched = assetBytes(patch.asset) ?: continue
            val file = File(cfg.url)
            if (!file.exists()) continue
            val current = runCatching { file.readBytes() }.getOrNull() ?: continue
            val sourceText = runCatching { String(current, Charsets.UTF_8) }.getOrNull()
            val isPatched = sourceText != null && sourceText.contains("Hikari patched build")
            if (isPatched) {
                if (current.contentEquals(patched)) {
                    // File is already the vendored build — but is this provider
                    // even supposed to have it? If its source repo isn't the one
                    // the patch targets, put the correct upstream back.
                    val expectedSource = patch.sourceMustContain
                    if (expectedSource != null && cfg.extra?.contains(expectedSource) != true) {
                        val restored = withContext(Dispatchers.IO) {
                            cfg.extra?.let { url -> runCatching { Http.fetchBytesRobust(url) }.getOrNull() }
                        }
                        if (restored != null && restored.isNotEmpty() &&
                            !String(restored, Charsets.UTF_8).contains("Hikari patched build") &&
                            NuvioRuntime.validate(context, String(restored, Charsets.UTF_8)).startsWith("OK")
                        ) {
                            runCatching {
                                file.setWritable(true)
                                file.writeBytes(restored)
                            }
                        }
                    }
                }
                continue
            }
            // Upstream on disk — patch only when it's the build this patch is
            // for (content guard), then re-validate before keeping it.
            val guarded = patchedBytes(
                cfg.extra,
                current,
            ) ?: continue
            if (!runCatching {
                    file.setWritable(true)
                    file.writeBytes(guarded)
                    true
                }.getOrDefault(false)) continue
            val verdict = NuvioRuntime.validate(context, String(guarded, Charsets.UTF_8))
            if (!verdict.startsWith("OK")) {
                // Revert on validation failure — never ship a broken file.
                runCatching { file.writeBytes(current) }
            }
        }
    }

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
            // Some manifests (e.g. Eclipsia) put a FULL absolute URL in
            // `filename` rather than a relative path — joining that with
            // baseUrl would produce a garbage double URL whose 404 body
            // ("Not found.") fails validation with "expecting ';'". Use the
            // filename as-is when it's already absolute.
            url = if (filename.startsWith("http://") || filename.startsWith("https://")) filename
            else "$baseUrl/$filename",
            // `logo` is the documented field (usually a Google-favicon URL for
            // the scraper's site), but manifests in the wild also use
            // `iconUrl`/`icon` — reading only `logo` left those rows on the
            // placeholder glyph.
            iconUrl = o.optString("logo")
                .ifBlank { o.optString("iconUrl") }
                .ifBlank { o.optString("icon") }
                .ifBlank { null },
            version = version,
            tvTypes = types,
        )
    }
}
