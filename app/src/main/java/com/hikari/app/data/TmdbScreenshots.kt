package com.hikari.app.data

import com.hikari.app.nuvio.TmdbResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * TMDB artwork backdrops ("screenshots") for the television detail page's
 * banner and Screenshots section, like the desktop client's own artwork.
 *
 * One `/movie/{id}/images` (or `/tv/...`) call per title, backdrops only,
 * capped at 8. English/unflagged art leads, then art in any language: a
 * zh-tagged donghua backdrop still beats a provider thumbnail stretched over
 * the hero. Cached process-wide, fills blanks and never throws: an
 * unresolvable title or a dead network is an empty list, and the page falls
 * back to provider art.
 */
object TmdbScreenshots {

    private const val IMG_BACKDROP = "https://image.tmdb.org/t/p/w1280"

    private val mem = ConcurrentHashMap<String, List<String>>()

    /** First header-sized art per title id, filled whenever [backdropsFor]
     *  succeeds — the Play intent reads this synchronously (it cannot suspend)
     *  so the player's cover and server screen paint the same TMDB image the
     *  detail header settled on, instead of the single backdrop_path that is
     *  often null while /images has art. */
    private val firstByKey = ConcurrentHashMap<String, String>()

    /** Synchronous read of the header art [backdropsFor] resolved for this
     *  title id (null until it has loaded once). Never throws. */
    fun firstCached(uniqueId: String): String? = firstByKey[uniqueId]

    suspend fun backdropsFor(item: MediaItem): List<String> =
        runCatching {
            if (item.type != MediaType.SERIES && item.type != MediaType.MOVIE) return emptyList()
            // Anime pick: the anime's own banner/cover, which are absolute
            // URLs — never TMDB's mistranslated backdrops. Unprefixed, like
            // every non-TMDB art source.
            when (val pick = DonghuaDetails.pick(item)) {
                is DonghuaDetails.Pick.Anime -> {
                    val list = listOfNotNull(pick.info.banner, pick.info.cover).distinct()
                    if (list.isNotEmpty()) firstByKey[item.uniqueId] = list.first()
                    else firstByKey.remove(item.uniqueId)
                    return list
                }
                else -> {}
            }
            val resolved = TmdbResolver.resolve(item) ?: return emptyList()
            // "anime" is TMDB's tv namespace (see TmdbResolver): the images
            // endpoint is the same one. Resolving any other way 404s every
            // anime/donghua title.
            val seg = if (resolved.mediaType == "movie") "movie" else "tv"
            mem[seg + "/" + resolved.tmdbId]?.let {
                if (it.isNotEmpty()) firstByKey[item.uniqueId] = it.first()
                return it
            }
            val out = withContext(Dispatchers.IO) {
                // No language filter (desktop parity): a zh-tagged donghua
                // backdrop beats a provider thumbnail stretched over the
                // hero. English/unflagged art first, then art in any
                // language, best-voted first inside each group.
                val obj = TmdbResolver.apiGet(
                    "/$seg/${resolved.tmdbId}/images",
                    emptyMap(),
                ) ?: return@withContext emptyList()
                val arr = obj.optJSONArray("backdrops") ?: return@withContext emptyList()
                val pref = ArrayList<Pair<Double, String>>()
                val rest = ArrayList<Pair<Double, String>>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val path = o.optString("file_path").trim()
                        .takeIf { it.isNotBlank() && it != "null" } ?: continue
                    val lang = o.optString("iso_639_1")
                    (if (lang == "en" || lang.isBlank() || lang == "null") pref else rest)
                        .add(o.optDouble("vote_average", 0.0) to path)
                }
                (pref.sortedByDescending { it.first } + rest.sortedByDescending { it.first })
                    .take(8).map { IMG_BACKDROP + it.second }
            }
            if (mem.size >= 200) mem.clear()
            if (out.isNotEmpty()) {
                mem[seg + "/" + resolved.tmdbId] = out
                firstByKey[item.uniqueId] = out.first()
            } else {
                firstByKey.remove(item.uniqueId)
            }
            out
        }.getOrDefault(emptyList())
}
