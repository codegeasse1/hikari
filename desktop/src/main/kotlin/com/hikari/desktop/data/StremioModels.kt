package com.hikari.desktop.data

data class AddonCatalog(
    val type: String,
    val id: String,
    val name: String,
    val extraNames: Set<String> = emptySet(),
)

data class AddonResource(
    val name: String,
    val types: Set<String> = emptySet(),
    val idPrefixes: List<String>? = null,
) {
    fun supports(type: String, id: String): Boolean =
        (types.isEmpty() || types.any { it.equals(type, ignoreCase = true) }) &&
            (idPrefixes.isNullOrEmpty() || idPrefixes.any(id::startsWith))
}

data class AddonManifest(
    val addonUrl: String,
    val name: String,
    val description: String?,
    val catalogs: List<AddonCatalog>,
    val resources: List<AddonResource> = emptyList(),
)

data class AddonTitle(
    val addonUrl: String,
    val id: String,
    val type: String,
    val title: String,
    val posterUrl: String? = null,
    val backgroundUrl: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val genres: List<String> = emptyList(),
    val year: Int? = null,
    val rating: Double? = null,
)

data class AddonEpisode(
    val id: String,
    val title: String,
    val season: Int,
    val number: Int,
)

data class AddonDetail(
    val title: AddonTitle,
    val episodes: List<AddonEpisode>,
)

enum class StreamKind {
    DIRECT,
    BROWSER,
}

data class AddonStream(
    val name: String,
    val url: String,
    val kind: StreamKind,
)
