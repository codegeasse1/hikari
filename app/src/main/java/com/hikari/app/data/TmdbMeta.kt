package com.hikari.app.data

import com.hikari.app.net.Http
import com.hikari.app.nuvio.TmdbResolver
import org.json.JSONObject

/**
 * The parts of TMDB the app needs besides stream resolution: alternative
 * artwork for titles an extension left blank, and the "Similar"/"Related"
 * shelves on a detail page.
 *
 * Artwork lookup is deliberately two-stage, because neither source covers
 * everything:
 *
 *  1. TMDB (the app's existing resolver turns a title/year, a numeric id or an
 *     IMDb `tt` id into a tmdbId, and `/movie/{id}` / `/tv/{id}` carries the
 *     poster and backdrop paths). This is the good art when it exists.
 *  2. IMDb's public suggestion endpoint — no key, keyed by title — which
 *     returns both the `tt` id and a poster image URL. This is what fills in
 *     the titles TMDB has no image for (regional/Indian catalogs especially).
 *
 * A title that neither source has art for is a genuine miss and is cached as
 * such, so a blank cell costs one lookup, not one per recomposition.
 */
object TmdbMeta {

    private const val IMG = "https://image.tmdb.org/t/p/w500"
    private const val IMG_WIDE = "https://image.tmdb.org/t/p/w780"

    /** TMDB endpoint segment ("movie" | "tv") for a resolved media type. */
    private fun segment(mediaType: String): String =
        if (mediaType.equals("movie", true)) "movie" else "tv"

    private fun yearOf(o: JSONObject): Int? {
        val raw = o.optString("release_date").ifBlank { o.optString("first_air_date") }
        return raw.take(4).takeIf { it.length == 4 }?.toIntOrNull()
    }

    /**
     * (poster, backdrop) for [item] from TMDB, falling back to IMDb when TMDB
     * has no usable image. Either element of the pair may be null; null as a
     * whole means neither source knew this title.
     */
    suspend fun artwork(item: MediaItem): Pair<String?, String?>? {
        val resolved = runCatching { TmdbResolver.resolve(item) }.getOrNull()
        if (resolved != null) {
            val seg = segment(resolved.mediaType)
            val d = TmdbResolver.apiGet("/$seg/${resolved.tmdbId}", emptyMap())
            if (d != null) {
                val p = d.optString("poster_path").takeIf { it.isNotBlank() }?.let { IMG + it }
                val b = d.optString("backdrop_path").takeIf { it.isNotBlank() }?.let { IMG_WIDE + it }
                if (p != null || b != null) return p to b
            }
        }
        return imdbArtwork(item)
    }

    /**
     * IMDb's suggestion endpoint (`/suggestion/h/<query>.json`) needs no API
     * key and answers with `d: [{ l: title, y: year, i: { imageUrl } }]`. The
     * image URLs are on m.media-amazon.com and load like any other poster.
     */
    private suspend fun imdbArtwork(item: MediaItem): Pair<String?, String?>? {
        val title = item.title.trim()
        if (title.isBlank()) return null
        val q = runCatching {
            java.net.URLEncoder.encode(title.lowercase(), "UTF-8")
        }.getOrNull() ?: return null
        val text = Http.getString(
            "https://v3.sg.media-imdb.com/suggestion/h/$q.json",
            mapOf("Accept" to "application/json")
        ) ?: return null
        val arr = runCatching {
            JSONObject(text).optJSONArray("d")
        }.getOrNull() ?: return null
        val wanted = title.lowercase()
        var best: String? = null
        var bestScore = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val label = o.optString("l").lowercase()
            if (label.isBlank()) continue
            var score = 0
            if (label == wanted) score += 50
            else if (label.startsWith(wanted) || wanted.startsWith(label)) score += 20
            else continue
            if (item.year != null && o.optString("y") == item.year.toString()) score += 30
            val img = o.optJSONObject("i")?.optString("imageUrl").orEmpty()
            if (img.isBlank()) continue
            if (score > bestScore) {
                bestScore = score
                best = img
            }
        }
        return best?.let { it to null }
    }

    /** TMDB's "similar" titles for [item] (same genre/vibe). */
    suspend fun similar(item: MediaItem, limit: Int = 18): List<MediaItem> =
        shelf(item, "similar", limit)

    /** TMDB's "recommendations" for [item] (what people watched next). */
    suspend fun related(item: MediaItem, limit: Int = 18): List<MediaItem> =
        shelf(item, "recommendations", limit)

    private suspend fun shelf(item: MediaItem, kind: String, limit: Int): List<MediaItem> {
        val resolved = runCatching { TmdbResolver.resolve(item) }.getOrNull() ?: return emptyList()
        val seg = segment(resolved.mediaType)
        val data = TmdbResolver.apiGet("/$seg/${resolved.tmdbId}/$kind", emptyMap())
            ?: return emptyList()
        val arr = data.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<MediaItem>(limit)
        for (i in 0 until arr.length()) {
            if (out.size >= limit) break
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank() || id == resolved.tmdbId) continue
            val title = o.optString("title").ifBlank { o.optString("name") }.trim()
            if (title.isBlank()) continue
            val type = if (seg == "movie") MediaType.MOVIE else MediaType.SERIES
            val poster = o.optString("poster_path").takeIf { it.isNotBlank() }?.let { IMG + it }
            val backdrop = o.optString("backdrop_path").takeIf { it.isNotBlank() }?.let { IMG_WIDE + it }
            // A shelf cell with no art at all reads as a hole in the row, so
            // leave those out rather than padding the shelf with blanks.
            if (poster == null && backdrop == null) continue
            out.add(
                MediaItem(
                    providerId = "tmdb",
                    id = id,
                    title = title,
                    type = type,
                    posterUrl = poster,
                    year = yearOf(o),
                    overview = o.optString("overview").takeIf { it.isNotBlank() },
                    backdropUrl = backdrop,
                    rawType = "tmdb",
                )
            )
        }
        return out
    }
}
