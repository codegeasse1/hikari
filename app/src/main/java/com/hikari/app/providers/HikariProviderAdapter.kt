package com.hikari.app.providers

import com.hikari.app.data.CatalogRef
import com.hikari.app.data.Episode
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.StreamSource
import com.hikari.app.data.SubtitleSource
import com.hikari.app.hiki.HikariPluginManager
import com.hikari.app.hiki.HikariRuntime
import com.hikari.ext.HikariCatalog
import com.hikari.ext.HikariEpisode
import com.hikari.ext.HikariMedia
import com.hikari.ext.HikariMediaType
import com.hikari.ext.HikariProvider
import com.hikari.ext.HikariStream

/**
 * Adapts a [HikariProvider] (bundled or loaded from a .hiki jar) to the app's
 * [ContentProvider] interface, so extensions plug into Home, search, detail
 * and the player exactly like Stremio addons and CloudStream plugins.
 */
class HikariProviderAdapter(override val config: ProviderConfig) : ContentProvider {

    @Volatile
    private var loadedProvider: HikariProvider? = null
    @Volatile
    private var providerResolved = false

    companion object {
        /** Per-provider last-resort message (native .hiki failures + the
         *  app-wide extraction passes) - shown in the Detail screen's "no
         *  sources" panel so HIKARI providers aren't a silent wall of mystery. */
        val streamErrors = java.util.concurrent.ConcurrentHashMap<String, String>()

        /**
         * Per-provider reason a `.hiki` extension could not serve its catalog.
         *
         * Home's empty state used to have no HIKARI entry in its failure chain
         * at all, so ANY `.hiki` catalog that came back empty fell through to
         * "Nothing came back from this extension. Retry, or open its site in
         * the WebView …" — a message that points at the extension's website and
         * at a Cloudflare verification page, neither of which is ever the
         * reason for a bundled-plugin extension. The real reasons are local
         * (the extension archive is missing its plugin, the plugin's load()
         * threw, it registered nothing, its home page is empty) and the
         * extension itself now says which one it is (see the bridge's
         * IllegalStateException), so they are recorded here and shown instead.
         *
         * Cleared as soon as the same call succeeds.
         */
        val catalogErrors = java.util.concurrent.ConcurrentHashMap<String, String>()
    }

    /** The extension's provider, resolved on first access. Only a SUCCESS is
     *  cached — a transient failure (extension still loading, a one-off hiccup)
     *  is retried on the next access rather than stuck as a permanent null
     *  ("no catalog"/"no playable source" until a force-stop). */
    private val provider: HikariProvider?
        get() {
            if (providerResolved) return loadedProvider
            synchronized(this) {
                if (providerResolved) return loadedProvider
                val p = HikariRuntime.providerFor(config)
                if (p != null) {
                    loadedProvider = p
                    providerResolved = true
                }
                return p
            }
        }

    /**
     * The `.hiki` bundle's own answer to "is this an 18+ extension", read
     * straight out of the archive.
     *
     * Unlike [provider] above, this loads no plugin class: a `.hiki` is a zip
     * (the runtime loads its `classes.dex` from it), so its `manifest.json` is
     * one entry read. Null when the bundle says nothing about adult content —
     * an older bundle written before those fields existed — which leaves the
     * answer to the repo that published it (see
     * [com.hikari.app.data.ExtensionNsfw.repoEntryNsfw]).
     */
    override fun adultExtension(): Boolean? = runCatching {
        val file = java.io.File(config.url)
        if (!file.isFile) return null
        java.util.zip.ZipFile(file).use { zip ->
            val entry = zip.getEntry("manifest.json") ?: return null
            val text = zip.getInputStream(entry).use { it.readBytes().decodeToString() }
            val o = org.json.JSONObject(text)
            if (!o.has("tvTypes") && !o.has("nsfw") && !o.has("contentWarning")) return null
            val types = o.optJSONArray("tvTypes")?.let { a ->
                (0 until a.length()).mapNotNull { i -> a.optString(i).ifBlank { null } }
            } ?: emptyList()
            com.hikari.app.data.ExtensionNsfw.repoEntryNsfw(o, types)
        }
    }.getOrNull()

    override suspend fun catalogs(): List<CatalogRef> {
        val p = provider
        if (p == null) {
            catalogErrors[config.id] = loadFailureNote()
            return emptyList()
        }
        return try {
            // `HikariProvider.catalogs()` is blocking by contract (the app's
            // extension ABI declares it non-suspend, and a CloudStream bridge
            // fetches the plugin's home rows inside it), so it is never called
            // on whatever dispatcher the caller happens to be on. Home already
            // runs on Dispatchers.IO; this makes every other call site safe too.
            val raw = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                p.catalogs()
            }
            val list = raw
                .map { CatalogRef(config.id, it.type.toApp(), it.id, it.name, it.rawType) }
            if (list.isEmpty()) {
                catalogErrors[config.id] =
                    "the extension's plugin loaded but returned no home page"
                emptyList()
            } else {
                catalogErrors.remove(config.id)
                list
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            catalogErrors[config.id] = reasonOf(e)
            emptyList()
        }
    }

    override suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem> {
        val p = provider
        if (p == null) {
            catalogErrors[config.id] = loadFailureNote()
            return emptyList()
        }
        return try {
            val items = p.getCatalog(HikariCatalog(ref.id, ref.name, ref.type.toExt(), ref.rawType), page)
                .map { it.toApp(config.id) }
            if (items.isEmpty()) {
                // Keep a reason already recorded by catalogs()/an earlier
                // catalog (a provider with one dead shelf and one live one is
                // not "broken"), but never leave the empty state unexplained.
                catalogErrors.putIfAbsent(
                    config.id,
                    "\"${ref.name}\" came back empty from this extension"
                )
            } else {
                catalogErrors.remove(config.id)
            }
            items
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            catalogErrors[config.id] = reasonOf(e)
            emptyList()
        }
    }

    /** Why the extension itself could not be resolved at all. */
    private fun loadFailureNote(): String {
        val detail = HikariPluginManager.lastError
            ?.lineSequence()
            ?.firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(200)
        return if (detail.isNullOrBlank()) {
            "the extension's provider could not be loaded — reinstall the extension from its repo"
        } else {
            "the extension failed to load — $detail"
        }
    }

    private fun reasonOf(e: Throwable): String {
        val m = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        return m.take(280)
    }

    override suspend fun search(query: String, page: Int): List<MediaItem> {
        val p = provider
        if (p == null) {
            // A .hiki that cannot be loaded (a corrupt/old archive, a bundle
            // that registers no provider, a load that just failed) used to
            // return a bare empty list, so the cross-extension pass reported the
            // repo as "no matching title in this repo" — the one verdict that
            // tells the user nothing, and that hides a broken extension.
            streamErrors[config.id] = "Extension failed to load — " +
                (
                    HikariPluginManager.lastError
                        ?.lineSequence()?.firstOrNull()?.take(140)
                        ?: "open Extensions and reinstall this one."
                    )
            return emptyList()
        }
        return try {
            val found = p.search(query, page)?.map { it.toApp(config.id) } ?: emptyList()
            // The search worked, so any earlier "failed to load"/"search failed"
            // note is stale.
            streamErrors.remove(config.id)
            found
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            streamErrors[config.id] =
                "Search failed: ${e.javaClass.simpleName}: " + (e.message ?: "no message").take(140)
            emptyList()
        }
    }

    override suspend fun getMeta(item: MediaItem): MediaItem {
        val p = provider ?: return item
        return p.getMeta(item.toExt()).toApp(config.id)
    }

    override suspend fun getEpisodes(item: MediaItem): List<Episode>? {
        if (item.type == MediaType.MOVIE) return null
        val p = provider ?: return null
        return p.getEpisodes(item.toExt())?.map { ep ->
            val base = ep.name ?: "Episode ${ep.number}"
            Episode(
                number = ep.number,
                id = ep.id,
                name = if (ep.season > 1) "S${ep.season} E${ep.number} · $base" else base,
                image = ep.image,
                season = ep.season,
            )
        }
    }

    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> {
        val p = provider ?: return emptyList()
        val ep = episode?.let { HikariEpisode(it.number, it.id, it.name, it.image) }
        // One call at a time into this extension: a bridge provider that keeps
        // state (see [ProviderGate]) is corrupted by two concurrent passes.
        return com.hikari.app.providers.ProviderGate.withProvider(
            config.id,
            // A stream lookup is BACKGROUND: it must never queue in front of a
            // page the user is waiting on (see [ProviderGate.Lane]).
            com.hikari.app.providers.ProviderGate.Lane.BACKGROUND,
        ) {
            p.getStreams(item.toExt(), ep).map { it.toApp() }
        }
    }

    // ---- conversions ----

    private fun HikariMediaType.toApp(): MediaType = when (this) {
        HikariMediaType.MOVIE -> MediaType.MOVIE
        HikariMediaType.SERIES -> MediaType.SERIES
        HikariMediaType.UNKNOWN -> MediaType.UNKNOWN
    }

    private fun MediaType.toExt(): HikariMediaType = when (this) {
        MediaType.MOVIE -> HikariMediaType.MOVIE
        MediaType.SERIES -> HikariMediaType.SERIES
        MediaType.UNKNOWN -> HikariMediaType.UNKNOWN
    }

    private fun HikariMedia.toApp(providerId: String): MediaItem = MediaItem(
        providerId = providerId,
        id = id,
        title = title,
        type = type.toApp(),
        posterUrl = posterUrl,
        year = year,
        overview = overview,
        genres = genres,
        backdropUrl = backdropUrl,
        rawType = rawType,
    )

    private fun MediaItem.toExt(): HikariMedia = HikariMedia(
        id = id,
        title = title,
        type = type.toExt(),
        posterUrl = posterUrl,
        year = year,
        overview = overview,
        genres = genres,
        backdropUrl = backdropUrl,
        rawType = rawType,
    )

    private fun HikariStream.toApp(): StreamSource = StreamSource(
        name = name,
        url = url,
        headers = headers,
        subtitles = subtitles.map { SubtitleSource(it.lang, it.url) },
        isTorrent = isTorrent,
        infoHash = infoHash,
        isM3u8 = isM3u8,
        isMpd = isMpd,
        fileIdx = fileIdx,
        trackers = trackers,
        ytId = ytId,
        externalUrl = externalUrl,
    )
}
