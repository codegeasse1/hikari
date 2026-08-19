package com.hikari.app.providers

import com.hikari.app.data.CatalogRef
import com.hikari.app.data.Episode
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.StreamSource
import com.hikari.app.data.SubtitleSource
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

    override suspend fun catalogs(): List<CatalogRef> =
        provider?.catalogs()?.map { CatalogRef(config.id, it.type.toApp(), it.id, it.name, it.rawType) }
            ?: emptyList()

    override suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem> =
        provider?.getCatalog(HikariCatalog(ref.id, ref.name, ref.type.toExt(), ref.rawType), page)
            ?.map { it.toApp(config.id) }
            ?: emptyList()

    override suspend fun search(query: String, page: Int): List<MediaItem> =
        provider?.search(query, page)?.map { it.toApp(config.id) }
            ?: emptyList()

    override suspend fun getMeta(item: MediaItem): MediaItem {
        val p = provider ?: return item
        return p.getMeta(item.toExt()).toApp(config.id)
    }

    override suspend fun getEpisodes(item: MediaItem): List<Episode>? {
        val p = provider ?: return null
        if (item.type == MediaType.MOVIE) return null
        return p.getEpisodes(item.toExt())?.map { ep ->
            val base = ep.name ?: "Episode ${ep.number}"
            Episode(
                number = ep.number,
                id = ep.id,
                name = if (ep.season > 1) "S${ep.season} E${ep.number} · $base" else base,
                image = ep.image,
            )
        }
    }

    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> {
        val p = provider ?: return emptyList()
        val ep = episode?.let { HikariEpisode(it.number, it.id, it.name, it.image) }
        return p.getStreams(item.toExt(), ep).map { it.toApp() }
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
