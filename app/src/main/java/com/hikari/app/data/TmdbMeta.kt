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
    /** Cast headshots: TMDB's small profile size renders well in a circle. */
    private const val IMG_PROFILE = "https://image.tmdb.org/t/p/w185"

    /** TMDB endpoint segment ("movie" | "tv") for a resolved media type. */
    private fun segment(mediaType: String): String =
        if (mediaType.equals("movie", true)) "movie" else "tv"

    /** Reads a TMDB image path, treating JSON null / "" / "null" as absent.
     *  org.json's `optString` returns the literal string "null" for a JSON
     *  null, which used to be accepted as a path and produced URLs like
     *  "…/w500null" (HTTP 404 → blank cell) while also short-circuiting the
     *  IMDb fallback below, since the "path" looked present. */
    private fun JSONObject.tmdbPath(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).trim().takeIf { it.isNotBlank() && it != "null" }
    }

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
                val p = d.tmdbPath("poster_path")?.let { IMG + it }
                val b = d.tmdbPath("backdrop_path")?.let { IMG_WIDE + it }
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
            val poster = o.tmdbPath("poster_path")?.let { IMG + it }
            val backdrop = o.tmdbPath("backdrop_path")?.let { IMG_WIDE + it }
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

    /** A trailer with its sort rank, so the ranking logic stays readable. */
    private data class ScoredTrailer(val score: Int, val trailer: Trailer)

    /**
     * The detail page's extra sections — the "Show Details" metadata block, the
     * Cast row and the Trailers row — in ONE TMDB call. `append_to_response`
     * bundles `credits`, `videos` and the per-region certification list
     * (`release_dates` for movies, `content_ratings` for series) into the
     * details response, so opening a page costs one extra request, not three.
     *
     * Like [artwork] and [shelf] this is a bonus that runs in the background:
     * a slow or failed lookup simply leaves the sections out, never blocking
     * the page or playback. Null when the title can't be resolved to a TMDB id.
     */
    suspend fun extras(item: MediaItem): TitleExtras? {
        val resolved = runCatching { TmdbResolver.resolve(item) }.getOrNull() ?: return null
        val seg = segment(resolved.mediaType)
        val certKey = if (seg == "movie") "release_dates" else "content_ratings"
        val d = TmdbResolver.apiGet(
            "/$seg/${resolved.tmdbId}",
            mapOf("append_to_response" to "credits,videos,$certKey"),
        ) ?: return null

        val isMovie = seg == "movie"
        // Movie runtime is one number; a series carries a per-episode list and,
        // as a fallback, the runtime of its most recent episode.
        val runtime = if (isMovie) {
            d.optInt("runtime").takeIf { it > 0 }
        } else {
            d.optJSONArray("episode_run_time")
                ?.let { arr -> (0 until arr.length()).map { arr.optInt(it) }.firstOrNull { it > 0 } }
                ?: d.optJSONObject("last_episode_to_air")?.optInt("runtime")?.takeIf { it > 0 }
        }

        val credits = d.optJSONObject("credits")
        val crew = credits?.optJSONArray("crew")
        val directors = crewNames(crew, setOf("Director"), limit = 2).ifEmpty {
            // Series list their creators separately instead of as crew.
            val cb = d.optJSONArray("created_by")
            (0 until (cb?.length() ?: 0)).mapNotNull { i ->
                cb?.optJSONObject(i)?.optString("name")?.trim()?.takeIf { it.isNotBlank() }
            }
        }
        val writers = crewNames(crew, setOf("Writer", "Screenplay", "Story"), limit = 3)

        val details = TitleDetails(
            status = d.optString("status").trim().takeIf { it.isNotBlank() },
            runtimeMinutes = runtime,
            year = yearOf(d),
            rating = d.optDouble("vote_average").takeIf { it > 0.0 },
            voteCount = d.optInt("vote_count").takeIf { it > 0 },
            certification = certificationOf(d, seg),
            country = originCountryOf(d),
            language = d.optString("original_language").trim()
                .takeIf { it.isNotBlank() }?.uppercase(),
            director = directors.takeIf { it.isNotEmpty() }?.joinToString(", "),
            writers = writers,
        )

        val cast = ArrayList<CastMember>(20)
        val castArr = credits?.optJSONArray("cast")
        for (i in 0 until (castArr?.length() ?: 0)) {
            if (cast.size >= 20) break
            val o = castArr?.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            if (name.isBlank()) continue
            cast.add(
                CastMember(
                    name = name,
                    character = o.optString("character").trim().takeIf { it.isNotBlank() },
                    profileUrl = o.tmdbPath("profile_path")?.let { IMG_PROFILE + it },
                )
            )
        }

        // Trailers before teasers, official before unofficial — the order the
        // reference clients show them in. `videos` mixes everything together.
        val ranked = ArrayList<ScoredTrailer>(12)
        val vids = d.optJSONObject("videos")?.optJSONArray("results")
        for (i in 0 until (vids?.length() ?: 0)) {
            val o = vids?.optJSONObject(i) ?: continue
            if (!o.optString("site").equals("YouTube", true)) continue
            val key = o.optString("key").trim()
            if (key.isBlank()) continue
            val type = o.optString("type").trim().ifBlank { "Video" }
            val name = o.optString("name").trim().ifBlank { type }
            var score = when {
                type.equals("Trailer", true) -> 30
                type.equals("Teaser", true) -> 20
                else -> 10
            }
            if (o.optBoolean("official")) score += 5
            ranked.add(
                ScoredTrailer(
                    score,
                    Trailer(
                        youtubeKey = key,
                        name = name,
                        type = type,
                        thumbnailUrl = "https://img.youtube.com/vi/$key/hqdefault.jpg",
                    )
                )
            )
        }
        ranked.sortByDescending { it.score }
        val trailers = ranked.take(12).map { it.trailer }

        return TitleExtras(details = details, cast = cast, trailers = trailers)
    }

    /** Names of the crew members whose `job` is in [jobs], in listing order. */
    private fun crewNames(crew: org.json.JSONArray?, jobs: Set<String>, limit: Int): List<String> {
        if (crew == null) return emptyList()
        val out = ArrayList<String>(limit)
        for (i in 0 until crew.length()) {
            if (out.size >= limit) break
            val o = crew.optJSONObject(i) ?: continue
            if (o.optString("job").trim() !in jobs) continue
            val n = o.optString("name").trim()
            if (n.isNotBlank() && n !in out) out.add(n)
        }
        return out
    }

    /** US age rating when TMDB has one, else the first non-blank region's. */
    private fun certificationOf(d: JSONObject, seg: String): String? {
        val arr = d.optJSONObject(if (seg == "movie") "release_dates" else "content_ratings")
            ?.optJSONArray("results") ?: return null
        var fallback: String? = null
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val iso = r.optString("iso_3166_1")
            if (seg == "movie") {
                val dates = r.optJSONArray("release_dates") ?: continue
                for (j in 0 until dates.length()) {
                    val c = dates.optJSONObject(j)?.optString("certification")?.trim().orEmpty()
                    if (c.isBlank()) continue
                    if (iso == "US") return c
                    if (fallback == null) fallback = c
                }
            } else {
                val c = r.optString("rating").trim()
                if (c.isBlank()) continue
                if (iso == "US") return c
                if (fallback == null) fallback = c
            }
        }
        return fallback
    }

    private fun originCountryOf(d: JSONObject): String? {
        d.optJSONArray("origin_country")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.optString(i).trim()
                if (c.isNotBlank()) return c
            }
        }
        val pc = d.optJSONArray("production_countries")
        for (i in 0 until (pc?.length() ?: 0)) {
            val c = pc?.optJSONObject(i)?.optString("iso_3166_1")?.trim()
            if (!c.isNullOrBlank()) return c
        }
        return null
    }
}
