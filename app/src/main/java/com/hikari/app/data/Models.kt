package com.hikari.app.data

enum class ProviderType { STREMIO, UNIVERSAL, CS3 }

data class ProviderConfig(
    val id: String,
    val name: String,
    val type: ProviderType,
    val url: String = "",
    val iconUrl: String? = null,
    val enabled: Boolean = true,
    val extra: String? = null,
)

/** A CloudStream-style plugin repository (repo.json → pluginLists → plugin list). */
data class Cs3Repo(
    val url: String,
    val name: String,
    val description: String = "",
)

/** A single installable plugin entry from a CloudStream repository. */
data class Cs3RepoPlugin(
    val name: String,
    val description: String = "",
    val url: String,
    val iconUrl: String? = null,
    val authors: List<String> = emptyList(),
    val version: Int = 1,
    val tvTypes: List<String> = emptyList(),
    val fileHash: String? = null,
)

/** Per-repo plugin-list loading state shown in the Extensions screen. */
data class RepoLoadState(
    val loading: Boolean = false,
    val error: String? = null,
)

enum class MediaType { MOVIE, SERIES, UNKNOWN }

data class MediaItem(
    val providerId: String,
    val id: String,
    val title: String,
    val type: MediaType,
    val posterUrl: String? = null,
    val year: Int? = null,
    val overview: String? = null,
    val genres: List<String> = emptyList(),
    val backdropUrl: String? = null,
) {
    val uniqueId: String get() = "$providerId|$type|$id"
}

data class Episode(
    val number: Int,
    val id: String,
    val name: String? = null,
    val image: String? = null,
)

data class SubtitleSource(val lang: String, val url: String)

data class StreamSource(
    val name: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<SubtitleSource> = emptyList(),
    val isTorrent: Boolean = false,
    val infoHash: String? = null,
)

data class CatalogRef(
    val providerId: String,
    val type: MediaType,
    val id: String,
    val name: String,
)

data class CatalogRow(
    val providerName: String,
    val title: String,
    val items: List<MediaItem>,
)
