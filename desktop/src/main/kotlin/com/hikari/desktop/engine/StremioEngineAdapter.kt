package com.hikari.desktop.engine

import com.hikari.desktop.data.AddonCatalog
import com.hikari.desktop.data.AddonTitle
import com.hikari.desktop.net.StremioClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stremio adapter: the existing StremioClient behind the uniform Engine
 * contract, so add-ons flow through the same Home/Search/Detail paths.
 */
class StremioEngineAdapter(
    private val client: StremioClient = StremioClient(),
) : Engine {
    override val kind: EngineKind = EngineKind.STREMIO

    private fun titleOf(provider: EngineProvider, media: EngineMedia): AddonTitle =
        AddonTitle(
            addonUrl = provider.source,
            id = media.id,
            type = if (media.kind == EngineMediaKind.MOVIE) "movie" else "series",
            title = media.title,
            posterUrl = media.posterUrl,
            backgroundUrl = media.backdropUrl,
            description = media.overview,
            releaseInfo = media.year?.toString(),
            genres = media.genres,
            rating = media.rating,
        )

    override suspend fun catalogs(provider: EngineProvider): List<EngineCatalog> =
        withContext(Dispatchers.IO) {
            runCatching { client.loadManifest(provider.source) }.getOrNull()
                ?.catalogs
                ?.map { EngineCatalog(provider.id, it.id, it.name, EngineMediaKind.SERIES) }
                .orEmpty()
        }

    override suspend fun catalog(
        provider: EngineProvider,
        catalog: EngineCatalog,
        page: Int,
    ): List<EngineMedia> = withContext(Dispatchers.IO) {
        val manifest = runCatching { client.loadManifest(provider.source) }.getOrNull()
            ?: return@withContext emptyList()
        val addonCatalog = manifest.catalogs.firstOrNull { it.id == catalog.id }
            ?: AddonCatalog(type = "*", id = catalog.id, name = catalog.title)
        runCatching { client.catalog(manifest.addonUrl, addonCatalog, page) }
            .getOrDefault(emptyList())
            .map { t ->
                EngineMedia(
                    providerId = provider.id,
                    id = t.id,
                    title = t.title,
                    kind = if (t.type.equals("movie", true)) EngineMediaKind.MOVIE else EngineMediaKind.SERIES,
                    posterUrl = t.posterUrl,
                    backdropUrl = t.backgroundUrl,
                    overview = t.description,
                    year = t.year,
                    rating = t.rating,
                    genres = t.genres,
                )
            }
    }

    override suspend fun search(provider: EngineProvider, query: String, page: Int): List<EngineMedia> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val manifest = runCatching { client.loadManifest(provider.source) }.getOrNull()
                ?: return@withContext emptyList()
            runCatching { client.search(manifest.addonUrl, query, page) }
                .getOrDefault(emptyList())
                .map { t ->
                    EngineMedia(
                        providerId = provider.id,
                        id = t.id,
                        title = t.title,
                        kind = if (t.type.equals("movie", true)) EngineMediaKind.MOVIE else EngineMediaKind.SERIES,
                        posterUrl = t.posterUrl,
                        backdropUrl = t.backgroundUrl,
                        overview = t.description,
                        year = t.year,
                        rating = t.rating,
                        genres = t.genres,
                    )
                }
        }

    override suspend fun meta(provider: EngineProvider, media: EngineMedia): EngineMedia =
        withContext(Dispatchers.IO) {
            val detail = runCatching { client.detail(titleOf(provider, media)) }.getOrNull()
                ?: return@withContext media
            val t = detail.title
            media.copy(
                title = t.title,
                overview = t.description ?: media.overview,
                posterUrl = t.posterUrl ?: media.posterUrl,
                backdropUrl = t.backgroundUrl ?: media.backdropUrl,
                year = t.year ?: media.year,
                rating = t.rating ?: media.rating,
                genres = t.genres.ifEmpty { media.genres },
            )
        }

    override suspend fun episodes(provider: EngineProvider, media: EngineMedia): List<EngineEpisode> =
        withContext(Dispatchers.IO) {
            val detail = runCatching { client.detail(titleOf(provider, media)) }.getOrNull()
                ?: return@withContext emptyList()
            detail.episodes.map { e ->
                EngineEpisode(provider.id, media.id, e.id, e.season, e.number, e.title)
            }
        }

    override suspend fun streams(
        provider: EngineProvider,
        media: EngineMedia,
        episode: EngineEpisode?,
    ): List<EngineStream> = withContext(Dispatchers.IO) {
        val type = if (media.kind == EngineMediaKind.MOVIE) "movie" else "series"
        val videoId = episode?.id ?: media.id
        if (videoId.isBlank()) return@withContext emptyList()
        runCatching { client.streams(provider.source, type, videoId) }
            .getOrDefault(emptyList())
            .map { s -> EngineStream(provider.id, s.name, s.url) }
    }

    override suspend fun validate(provider: EngineProvider): String? = withContext(Dispatchers.IO) {
        val manifest = runCatching { client.loadManifest(provider.source) }.getOrNull()
            ?: return@withContext "not a Stremio add-on (manifest did not load)"
        if (manifest.catalogs.isEmpty()) {
            // Stream-only add-ons (Torrentio, Comet…) are valid with no catalogs.
            return@withContext null
        }
        null
    }
}
