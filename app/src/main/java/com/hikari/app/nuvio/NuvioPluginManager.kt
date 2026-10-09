package com.hikari.app.nuvio

import android.content.Context
import com.hikari.app.HikariApp
import com.hikari.app.data.AppStore
import com.hikari.app.data.Cs3Repo
import com.hikari.app.data.Cs3RepoPlugin
import com.hikari.app.data.Logs
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import com.hikari.app.data.RepoKind
import com.hikari.app.data.SourceUrls
import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Install/uninstall of Nuvio JS scrapers, plus first-run seeding of the Nuvio
 * provider repos so the feature works out of the box.
 *
 * The app no longer installs any provider FOR the user: the repos are seeded (so
 * the Extensions screen's Nuvio folders are never empty and a working scraper is
 * one tap away), but which scrapers are installed is the user's choice.
 * [removeFormerlySeededProviders] takes back the ones earlier builds put there.
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
        Triple(
            "https://raw.githubusercontent.com/D3adlyRocket/Anime-Nuvio/refs/heads/main/manifest.json",
            "All-in-One-Anime",
            "Anime nuvio providers (AllAnime, AniKai, AnimePahe, Anime-Sama, …)",
        ),
        Triple(
            "https://raw.githubusercontent.com/D3adlyRocket/Hindi-Nuvio/refs/heads/main/manifest.json",
            "Hindi-Nuvio",
            "Hindi/Indian nuvio providers (4KHDHub, VegaMovies, HDMovie2, …)",
        ),
    )

    /**
     * The providers earlier builds installed FOR the user on first run — until
     * 0.10.48 [seedDefaults] fetched Yoru's manifest and installed the three
     * named in `SEED_PROVIDERS`. The owner's instruction is that the app must not
     * put providers in front of the user, so the seeding is gone (the repos are
     * still seeded) and [removeFormerlySeededProviders] takes back what the old
     * build left on an install that already has them.
     *
     * `dahmermovies.js` is here even though the last build's list named only
     * three: it is one of Yoru's scrapers and shows up installed in the report
     * the owner sent, so an earlier build seeded it and it has to go with the
     * rest.
     */
    private val FORMERLY_SEEDED = setOf(
        "vixsrc.js",
        "moviebox.js",
        "showbox.js",
        "dahmermovies.js",
    )

    /** The repo the old seeding read, as [SourceUrls.fileKey] spells it. */
    private const val SEED_REPO = "tapframe/nuvio-providers"

    /** Adds the default repos once, so nuvio sources have somewhere to come
     *  from. Non-fatal on any failure. */
    suspend fun seedDefaults(context: Context, store: AppStore) {
        for ((url, name, desc) in DEFAULT_REPOS) {
            runCatching { store.seedCs3Repo(Cs3Repo(url, name, desc, RepoKind.NUVIO)) }
        }
    }

    /**
     * Removes the Nuvio providers earlier builds pre-installed (see
     * [FORMERLY_SEEDED]), once per install. Repos are left alone — only the
     * scrapers go, and only a row that is BOTH from Yoru's repo under one of
     * the four names AND still sitting on the bare file the old seeder wrote.
     * A reinstall lands on a per-source stamped file name (see
     * [installScraper]), so user installs never match the second condition.
     *
     * Matched on the source FILE, not the display name: MovieBox (`moviebox.js`)
     * also ships in the Hindi-Nuvio and All-in-One bundles, and a copy the
     * user installed from one of those is theirs to keep (different repo, no
     * match — and a stamped local file, no match either).
     */
    suspend fun removeFormerlySeededProviders(context: Context) {
        val store = HikariApp.instance.store
        // `getOrDefault(true)`: if the flag cannot be read, do nothing rather
        // than run a destructive sweep on a guess — the next launch will ask
        // again.
        if (runCatching { store.nuvioSeedCleaned() }.getOrDefault(true)) return
        val targets = store.providers().filter { cfg ->
            if (cfg.type != ProviderType.NUVIO) return@filter false
            val key = SourceUrls.fileKey(cfg.extra ?: "") ?: return@filter false
            if (!key.startsWith("$SEED_REPO/")) return@filter false
            val seededName = key.substringAfterLast('/')
            if (seededName !in FORMERLY_SEEDED) return@filter false
            // Only the file the old seeder itself wrote (bare name): installs
            // land on per-source stamped names now, so a user's reinstall is
            // immune to this sweep by construction — only a leftover of the
            // old seeding is ever taken back.
            File(cfg.url).name == seededName
        }
        if (targets.isNotEmpty()) {
            val ids = targets.map { it.id }.toSet()
            val paths = targets.map { it.url }.toSet()
            // Locked read-modify-write: see [AppStore.updateProviders].
            store.updateProviders { list -> list.filterNot { it.id in ids } }
            HikariApp.instance.providers.refresh()
            withContext(Dispatchers.IO) {
                val remaining = store.providers().map { it.url }.toSet()
                val base = context.filesDir.absolutePath
                paths.forEach { p ->
                    if (p.startsWith(base) && p !in remaining) runCatching { File(p).delete() }
                }
            }
            Logs.log(
                "Providers",
                "removed ${targets.size} provider(s) an older build installed for you: " +
                    targets.joinToString { it.name },
            )
        }
        runCatching { store.markNuvioSeedCleaned() }
    }

    /** Writes a scraper JS file and registers it as a NUVIO provider. The
     *  provider code is validated before anything installed is touched, and
     *  the file carries a per-source stamp in its name (like the CS3/Hiki
     *  installers): two repos' providers often share a file name, and without
     *  the stamp the second install overwrote the first's file. */
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
        val base = rawName.substringAfterLast('/').ifBlank { "provider" }
            .let { if (it.endsWith(".js", true)) it.dropLast(3) else it }
            .trim().ifBlank { "provider" }
        // Per-source file identity: two repos publishing `moviebox.js` must
        // not share one file (the second install overwrote the first, and
        // uninstalling either deleted the other's code). A stamped name also
        // never matches the formerly-seeded sweep's bare file names, so a
        // reinstall is immune to that sweep by construction.
        val clean = if (sourceUrl.isNullOrBlank()) "$base.js"
        else "$base-${shortHash(SourceUrls.canonical(sourceUrl))}.js"
        val dir = scrapersDir(context)
        val file = File(dir, clean)
        // Validate BEFORE touching the installed file: the old code overwrote
        // it first and deleted it when the new bytes failed validation, so a
        // failed update destroyed the working extension.
        val tmp = File(dir, "$clean.tmp")
        tmp.setWritable(true)
        val wrote = runCatching { tmp.writeBytes(effective) }
        if (wrote.isFailure) {
            runCatching { tmp.delete() }
            return@withContext Result.failure(
                Exception("Could not write scraper file: ${wrote.exceptionOrNull()?.message}")
            )
        }
        val source = runCatching { tmp.readText() }.getOrNull()
        if (source.isNullOrBlank()) {
            runCatching { tmp.delete() }
            return@withContext Result.failure(Exception("Scraper file is empty"))
        }
        // Load the module in a pooled WebView to confirm it exports getStreams.
        val verdict = NuvioRuntime.validate(context, source)
        if (!verdict.startsWith("OK")) {
            runCatching { tmp.delete() }
            val detail = if (verdict.startsWith("ERR:")) verdict.removePrefix("ERR:").take(300)
            else "no getStreams export found"
            return@withContext Result.failure(
                Exception("Not a valid nuvio provider: $detail")
            )
        }
        file.setWritable(true)
        var committed = tmp.renameTo(file)
        if (!committed) {
            runCatching { file.delete() }
            committed = tmp.renameTo(file)
        }
        runCatching { tmp.delete() }
        if (!committed) {
            return@withContext Result.failure(Exception("Could not install scraper file"))
        }
        val id = "nuvio|" + clean.hashCode()
        HikariApp.instance.store.addProvider(
            ProviderConfig(
                id = id,
                name = base,
                type = ProviderType.NUVIO,
                url = file.absolutePath,
                iconUrl = iconUrl,
                extra = sourceUrl ?: clean,
            )
        )
        HikariApp.instance.providers.refresh()
        // One source must never leave two copies behind: an install that
        // follows a pre-stamp-era file (bare name) drops that old row and its
        // file once nothing references it — the CS3 path does the same.
        if (!sourceUrl.isNullOrBlank()) {
            val wanted = SourceUrls.matchKeys(sourceUrl).toSet()
            val stale = HikariApp.instance.store.providers().filter {
                it.type == ProviderType.NUVIO && it.url != file.absolutePath &&
                    it.extra != null && SourceUrls.matchKeys(it.extra).any { k -> k in wanted }
            }
            if (stale.isNotEmpty()) {
                val stalePaths = stale.map { it.url }.toSet()
                HikariApp.instance.store.updateProviders { list ->
                    list.filterNot { it.type == ProviderType.NUVIO && it.url in stalePaths }
                }
                HikariApp.instance.providers.refresh()
                val keep = HikariApp.instance.store.providers().map { it.url }.toSet()
                val root = context.filesDir.absolutePath
                stalePaths.forEach { p ->
                    if (p.startsWith(root) && p !in keep) {
                        runCatching { File(p).delete() }
                    }
                }
            }
        }
        Result.success(1)
    }

    /** A short, stable, filename-safe digest — the per-source stamp (mirrors
     *  the Extensions screen's own). */
    private fun shortHash(s: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        return md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(8)
    }

    /** Removes every NUVIO provider that came from [sourceUrl] (and its file).
     *  Returns how many installed providers were actually removed — 0 when the
     *  source was not installed — so the caller never reports a success for an
     *  uninstall that removed nothing. */
    suspend fun uninstallScraper(context: Context, sourceUrl: String): Int {
        val store = HikariApp.instance.store
        val all = store.providers()
        val targets = all.filter { it.type == ProviderType.NUVIO && it.extra == sourceUrl }
        val paths = targets.map { it.url }.toSet()
        // Locked read-modify-write: see [AppStore.updateProviders]. A rebuild
        // from the snapshot read above would drop anything installed meanwhile.
        store.updateProviders { list ->
            list.filter { it.type != ProviderType.NUVIO || it.extra != sourceUrl }
        }
        HikariApp.instance.providers.refresh()
        withContext(Dispatchers.IO) {
            val remaining = store.providers().map { it.url }.toSet()
            val base = context.filesDir.absolutePath
            paths.forEach { p ->
                if (p.startsWith(base) && p !in remaining) runCatching { File(p).delete() }
            }
        }
        return targets.size
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

    internal fun patchedBytes(sourceUrl: String?, original: ByteArray? = null): ByteArray? {
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
            nsfw = com.hikari.app.data.ExtensionNsfw.repoEntryNsfw(o, types),
        )
    }
}
