package com.hikari.desktop.engine

import com.hikari.desktop.data.AddonEpisode
import com.hikari.desktop.data.AddonStream
import com.hikari.desktop.data.AddonTitle
import com.hikari.desktop.data.StreamKind

/** Which extension ecosystem an engine provider belongs to. */
enum class EngineKind(val title: String, val prefix: String) {
    STREMIO("Stremio", "stremio"),
    NUVIO("Nuvio", "nuvio"),
    ANYMEX("Anymex", "anymex"),
    SORA("Sora", "sora"),
    SKYSTREAM("SkyStream", "skystream"),
    ZANGETSU("Zangetsu", "zangetsu"),
    VEGA("Vega", "vega"),
    IPTV("IPTV", "iptv"),
}

/** One installed extension, regardless of ecosystem. */
data class EngineProvider(
    val id: String,
    val kind: EngineKind,
    val name: String,
    /** Local file path (.js / .m3u) or remote URL (manifest / portal / repo file). */
    val source: String,
    val enabled: Boolean = true,
    val note: String? = null,
)

enum class EngineMediaKind { MOVIE, SERIES }

data class EngineMedia(
    val providerId: String,
    val id: String,
    val title: String,
    val kind: EngineMediaKind,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val overview: String? = null,
    val year: Int? = null,
    val rating: Double? = null,
    val genres: List<String> = emptyList(),
)

data class EngineEpisode(
    val providerId: String,
    val mediaId: String,
    val id: String,
    val season: Int,
    val number: Int,
    val title: String,
    val overview: String? = null,
)

data class EngineSubtitle(val label: String, val url: String)

data class EngineStream(
    val providerId: String,
    val name: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val quality: String? = null,
    val subtitles: List<EngineSubtitle> = emptyList(),
)

data class EngineCatalog(
    val providerId: String,
    val id: String,
    val title: String,
    val kind: EngineMediaKind,
)

/** Stable prefix so engine rows never collide with Stremio addon URLs. */
fun engineAddonUrl(provider: EngineProvider): String =
    "engine:${provider.kind.prefix}:${provider.id}"

fun parseEngineRef(addonUrl: String): Pair<EngineKind, String>? {
    if (!addonUrl.startsWith("engine:")) return null
    val rest = addonUrl.removePrefix("engine:")
    val cut = rest.indexOf(':')
    if (cut <= 0) return null
    val kind = EngineKind.entries.firstOrNull { it.prefix == rest.substring(0, cut) } ?: return null
    return kind to rest.substring(cut + 1)
}

fun EngineMedia.toAddonTitle(provider: EngineProvider): AddonTitle = AddonTitle(
    addonUrl = engineAddonUrl(provider),
    id = id,
    type = if (kind == EngineMediaKind.MOVIE) "movie" else "series",
    title = title,
    posterUrl = posterUrl,
    backgroundUrl = backdropUrl,
    description = overview,
    releaseInfo = year?.toString(),
    genres = genres,
    rating = rating,
    year = year,
)

fun EngineEpisode.toAddonEpisode(): AddonEpisode = AddonEpisode(
    id = id,
    title = title.ifBlank { "Episode $number" },
    season = season,
    number = number,
)

/** True when the URL is a file the internal player can open directly. */
fun isDirectPlayUrl(raw: String): Boolean {
    val u = raw.trim()
    if (u.isEmpty()) return false
    val low = u.lowercase()
    if (low.startsWith("magnet:") || low.startsWith("acestream://") || low.startsWith("torrent:")) return false
    if (!low.startsWith("http://") && !low.startsWith("https://")) return false
    if (low.contains("youtube.com/") || low.contains("youtu.be")) return false
    val path = low.substringBefore('?').substringBefore('#')
    val directExt = listOf(
        ".mp4", ".m3u8", ".mkv", ".webm", ".mov", ".m4v", ".avi", ".ts", ".m2ts",
        ".mp3", ".m4a", ".aac", ".flac", ".ogg", ".wav",
    )
    if (directExt.any(path::endsWith)) return true
    // HLS/DASH manifests served without an extension still play.
    if (low.contains(".m3u8") || low.contains(".mpd")) return true
    // Plain file hosts: a clean path with no query usually resolves to media.
    if (!low.contains('?') && !low.contains('#') && low.count { it == '/' } >= 3) {
        val last = path.substringAfterLast('/')
        if (last.isNotBlank() && !last.contains('.')) return false
    }
    return false
}

/** True when JavaFX Media can render the URL in-app (progressive + HLS). DASH goes external. */
fun isInternalPlayerUrl(raw: String): Boolean {
    val u = raw.trim()
    if (!isDirectPlayUrl(u)) return false
    val low = u.lowercase()
    if (low.contains(".mpd")) return false
    return true
}

fun EngineStream.toAddonStream(): AddonStream = AddonStream(
    name = listOfNotNull(name.takeIf { it.isNotBlank() }, quality).joinToString(" · "),
    url = url,
    kind = if (isDirectPlayUrl(url)) StreamKind.DIRECT else StreamKind.BROWSER,
)

/** Lenient pickers shared by every JS family's result parsing. */
internal fun pickFirstString(vararg values: Any?): String? =
    values.firstNotNullOfOrNull {
        when (it) {
            is String -> it.takeIf(String::isNotBlank)
            is Number -> it.toString()
            else -> null
        }
    }
