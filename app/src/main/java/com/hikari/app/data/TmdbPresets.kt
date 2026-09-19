package com.hikari.app.data

import com.hikari.app.nuvio.TmdbResolver
import org.json.JSONObject

/**
 * A ready-made TMDB catalog: one production company's films, or one network's
 * series, sorted by popularity.
 *
 * These are the "Pick a ready-made source" presets in the reference client's
 * TMDB Sources screen, and they're why a collection works with NO extensions
 * installed: TMDB's discovery endpoint can answer "popular Marvel Studios
 * films" or "popular HBO series" directly. Every id below was verified against
 * the live API (a company id and a network id are different namespaces —
 * company 174 is Warner Bros. Pictures, network 174 is AMC — which is exactly
 * why [companyId] and [networkId] are separate fields instead of one).
 */
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

/**
 * TMDB's preset catalogs — the source kind a collection folder can hold without
 * needing any extension.
 *
 * [page] is the only thing that talks to the network. Items are built exactly
 * like [TmdbMeta]'s shelves (`providerId = "tmdb"`, `rawType = "tmdb"`, a
 * numeric TMDB id) so the detail screen resolves them — and remaps them onto an
 * installed extension by title — through the same path a TMDB "Similar" row
 * already uses, which is what makes a preset item playable like any other.
 */
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

    /**
     * One page (TMDB pages are 20 items) of a preset catalog. Empty list on any
     * failure — a preset that can't load must simply leave its row/geout out,
     * never block the folder it sits in.
     */
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
            // The original (untranslated) name, so a preset row renamed by the
            // app's TMDB language is still searched for in the extensions under
            // the name their sites use (see MediaItem.originalTitle).
            originalTitle = originalOf(o),
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
