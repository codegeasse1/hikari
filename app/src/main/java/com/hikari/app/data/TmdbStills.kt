package com.hikari.app.data

import com.hikari.app.nuvio.TmdbResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

object TmdbStills {

    private const val IMG_STILL = "https://image.tmdb.org/t/p/w780"

    /** One TMDB episode: its still plus the title/overview the detail page
     *  falls back to when the provider shipped none (see TvCs3EpisodeCard).
     *  [still] is null where TMDB has no still — the position fallback fills
     *  those from the episode's POSITION, like the desktop client. */
    data class EpisodeStill(
        val still: String? = null,
        val name: String? = null,
        val overview: String? = null,
    )

    /** One season's stills: [byNumber] for direct hits, [ordered] (TMDB order,
     *  null where an episode is missing entirely) for the position fallback. */
    data class SeasonStills(
        val byNumber: Map<Int, EpisodeStill> = emptyMap(),
        val ordered: List<EpisodeStill?> = emptyList(),
    )

    private val mem = ConcurrentHashMap<String, Map<Int, SeasonStills>>()

    /**
     * Still data for [seasons] of [item], keyed by season.
     * Series only; anything else (and anything unresolvable) is empty.
     */
    suspend fun stillsDataFor(item: MediaItem, seasons: Set<Int>): Map<Int, SeasonStills> =
        runCatching {
            if (item.type != MediaType.SERIES || seasons.isEmpty()) return emptyMap()
            if (DonghuaDetails.pick(item) is DonghuaDetails.Pick.Anime) return emptyMap()
            val resolved = TmdbResolver.resolve(item) ?: return emptyMap()
            if (resolved.mediaType == "movie") return emptyMap()
            if (resolved.tmdbId.toLongOrNull() == null) return emptyMap()
            mem[resolved.tmdbId]?.let { return it }
            val out = withContext(Dispatchers.IO) {
                coroutineScope {
                    seasons
                        .filter { it in 0..60 }
                        .take(40)
                        .map { sn ->
                            async {
                                sn to seasonStills(resolved.tmdbId, sn)
                            }
                        }
                        .mapNotNull { runCatching { it.await() }.getOrNull() }
                        .toMap()
                }
            }
            if (mem.size >= 200) mem.clear()
            if (out.isNotEmpty()) mem[resolved.tmdbId] = out
            out
        }.getOrDefault(emptyMap())

    private suspend fun seasonStills(tmdbId: String, season: Int): SeasonStills =
        runCatching {
            val sd = TmdbResolver.apiGet("/tv/$tmdbId/season/$season", emptyMap())
                ?: return SeasonStills()
            val eps = sd.optJSONArray("episodes") ?: return SeasonStills()
            val by = LinkedHashMap<Int, EpisodeStill>()
            val ordered = ArrayList<EpisodeStill?>()
            for (i in 0 until eps.length()) {
                val e = eps.optJSONObject(i) ?: continue
                val en = e.optInt("episode_number", i + 1)
                val rec = EpisodeStill(
                    still = e.optString("still_path").trim()
                        .takeIf { it.isNotBlank() && it != "null" }
                        ?.let { IMG_STILL + it },
                    name = e.optString("name").trim()
                        .takeIf { it.isNotBlank() && it != "null" },
                    overview = e.optString("overview").trim()
                        .takeIf { it.isNotBlank() && it != "null" },
                )
                ordered.add(rec)
                if (en > 0 && (rec.still != null || rec.name != null || rec.overview != null)) {
                    by.putIfAbsent(en, rec)
                }
            }
            SeasonStills(by, ordered)
        }.getOrDefault(SeasonStills())
}
