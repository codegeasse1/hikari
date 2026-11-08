package com.hikari.app.data

import com.hikari.app.nuvio.TmdbResolver
import org.json.JSONObject

data class TmdbPreset(
    val key: String,
    val name: String,
    val kind: MediaType,
    val companyId: Int = 0,
    val networkId: Int = 0,
    val sort: String = "popularity.desc",
) {
    /** The one-line "Production • Movies • Popular" caption from the picker. */
    val detail: String
        get() = if (kind == MediaType.MOVIE) "Production · Movies · Popular"
        else "Network · Series · Popular"

    val isMovie: Boolean get() = kind == MediaType.MOVIE
}

object TmdbPresets {

    private const val IMG = "https://image.tmdb.org/t/p/w500"
    private const val IMG_WIDE = "https://image.tmdb.org/t/p/w780"

    val MOVIES: List<TmdbPreset> = listOf(
        TmdbPreset("marvel_studios", "Marvel Studios", MediaType.MOVIE, companyId = 420),
        TmdbPreset("disney_pictures", "Walt Disney Pictures", MediaType.MOVIE, companyId = 2),
        TmdbPreset("pixar", "Pixar", MediaType.MOVIE, companyId = 3),
        TmdbPreset("lucasfilm", "Lucasfilm", MediaType.MOVIE, companyId = 1),
        TmdbPreset("warner_bros", "Warner Bros.", MediaType.MOVIE, companyId = 174),
        TmdbPreset("a24", "A24", MediaType.MOVIE, companyId = 41077),
        TmdbPreset("dc", "DC", MediaType.MOVIE, companyId = 9993),
    )

    val SERIES: List<TmdbPreset> = listOf(
        TmdbPreset("netflix", "Netflix", MediaType.SERIES, networkId = 213),
        TmdbPreset("hbo", "HBO", MediaType.SERIES, networkId = 49),
        TmdbPreset("disney_plus", "Disney+", MediaType.SERIES, networkId = 2739),
        TmdbPreset("prime_video", "Prime Video", MediaType.SERIES, networkId = 1024),
        TmdbPreset("hulu", "Hulu", MediaType.SERIES, networkId = 453),
        TmdbPreset("apple_tv", "Apple TV+", MediaType.SERIES, networkId = 2552),
        TmdbPreset("amc", "AMC", MediaType.SERIES, networkId = 174),
        TmdbPreset("bbc_one", "BBC One", MediaType.SERIES, networkId = 4),
        TmdbPreset("cbs", "CBS", MediaType.SERIES, networkId = 16),
    )

    val ALL: List<TmdbPreset> = MOVIES + SERIES

    fun byKey(key: String): TmdbPreset? = ALL.firstOrNull { it.key == key }

    /** The preset's display name, or the key echoed back when it's unknown
     *  (a collection saved by a newer build, or an edited data file). */
    fun nameOf(key: String): String = byKey(key)?.name ?: key

    suspend fun page(preset: TmdbPreset, page: Int = 1): List<MediaItem> {
        val seg = if (preset.isMovie) "movie" else "tv"
        val query = LinkedHashMap<String, String>()
        if (preset.isMovie && preset.companyId > 0) {
            query["with_companies"] = preset.companyId.toString()
        } else if (!preset.isMovie && preset.networkId > 0) {
            query["with_networks"] = preset.networkId.toString()
        } else {
            return emptyList()
        }
        query["sort_by"] = preset.sort
        query["page"] = page.coerceAtLeast(1).toString()
        val data = TmdbResolver.apiGet("/discover/$seg", query) ?: return emptyList()
        val arr = data.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<MediaItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            item(preset, o)?.let { out.add(it) }
        }
        return out
    }

    private fun item(preset: TmdbPreset, o: JSONObject): MediaItem? {
        val id = o.optString("id")
        if (id.isBlank() || id == "null") return null
        val title = o.optString("title").ifBlank { o.optString("name") }.trim()
        if (title.isBlank()) return null
        val poster = path(o, "poster_path")?.let { IMG + it }
        val backdrop = path(o, "backdrop_path")?.let { IMG_WIDE + it }
        // A cell with no art reads as a hole: leave artless entries out, exactly
        // like the detail page's shelves do.
        if (poster == null && backdrop == null) return null
        return MediaItem(
            providerId = "tmdb",
            id = id,
            title = title,
            type = preset.kind,
            posterUrl = poster,
            year = year(o),
            overview = o.optString("overview").takeIf { it.isNotBlank() },
            backdropUrl = backdrop,
            rawType = "tmdb",
            rating = o.optDouble("vote_average", 0.0).takeIf { it > 0.0 },
            originalTitle = originalOf(o),
            // TMDB's own adult marker (see MediaItem.nsfw).
            nsfw = o.optBoolean("adult", false),
        )
    }

    private fun originalOf(o: JSONObject): String {
        val t = o.optString("original_title").ifBlank { o.optString("original_name") }.trim()
        return if (t.isBlank() || t == "null") "" else t
    }

    private fun path(o: JSONObject, key: String): String? =
        o.optString(key).takeIf { it.isNotBlank() && it != "null" }

    private fun year(o: JSONObject): Int? {
        val raw = o.optString("release_date").ifBlank { o.optString("first_air_date") }
        return raw.take(4).toIntOrNull()?.takeIf { it > 1800 }
    }
}
