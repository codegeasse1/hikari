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
    /** Portrait art for the vertical server page — one step down from the
     *  backdrop size, enough for a 560dp column. */
    private const val IMG_POSTER = "https://image.tmdb.org/t/p/w500"

    private val mem = ConcurrentHashMap<String, List<String>>()

    /** Portrait-art twin of [mem]: one `/images` call carries both lists. */
    private val posterMem = ConcurrentHashMap<String, List<String>>()

    /** First header-sized art per title id, filled whenever [backdropsFor]
     *  succeeds — the Play intent reads this synchronously (it cannot suspend)
     *  so the player's cover and server screen paint the same TMDB image the
     *  detail header settled on, instead of the single backdrop_path that is
     *  often null while /images has art. */
    private val firstByKey = ConcurrentHashMap<String, String>()

    /** The FULL plural list behind [firstCached], per title id — the Play
     *  intent carries it so the server screen rotates through the same art
     *  the detail header rotates through, every 2s. Empty until loaded. */
    private val listByKey = ConcurrentHashMap<String, List<String>>()

    /** Synchronous read of the header art [backdropsFor] resolved for this
     *  title id (null until it has loaded once). Never throws. */
    fun firstCached(uniqueId: String): String? = firstByKey[uniqueId]

    /** Synchronous read of the full header-art list (empty until loaded).
     *  Never throws. */
    fun allCached(uniqueId: String): List<String> = listByKey[uniqueId] ?: emptyList()

    /** First portrait art per title id — the vertical server page's opening
     *  frame. Synchronous, like [firstCached]. Never throws. */
    private val firstPosterByKey = ConcurrentHashMap<String, String>()

    /** The FULL plural portrait list behind [firstPosterCached], per title
     *  id — the Play intent carries it so the vertical server page rotates
     *  through portraits the way the horizontal one rotates backdrops.
     *  Empty until loaded. Never throws. */
    private val listPosterByKey = ConcurrentHashMap<String, List<String>>()

    /** Synchronous read of the portrait art (null until loaded). */
    fun firstPosterCached(uniqueId: String): String? = firstPosterByKey[uniqueId]

    /** Synchronous read of the full portrait-art list (empty until loaded). */
    fun allPostersCached(uniqueId: String): List<String> = listPosterByKey[uniqueId] ?: emptyList()

    suspend fun backdropsFor(item: MediaItem): List<String> =
        runCatching {
            if (item.type != MediaType.SERIES && item.type != MediaType.MOVIE) return emptyList()
            // Anime pick with real art: the anime's own banner/cover, which are absolute
            // URLs — never TMDB's mistranslated backdrops. Unprefixed, like
            // every non-TMDB art source. A bare pick (no art, or cover-only with
            // no overview/score) falls through to TMDB below instead of going bare.
            when (val pick = DonghuaDetails.pick(item)) {
                is DonghuaDetails.Pick.Anime -> {
                    val list = listOfNotNull(pick.info.banner, pick.info.cover).distinct()
                    if (list.isNotEmpty()) {
                        firstByKey[item.uniqueId] = list.first()
                        listByKey[item.uniqueId] = list
                        // The anime's own cover is its portrait art — the
                        // vertical server page rotates through it the same way
                        // the horizontal one rotates backdrops.
                        listOfNotNull(pick.info.cover).takeIf { it.isNotEmpty() }?.let {
                            firstPosterByKey[item.uniqueId] = it.first()
                            listPosterByKey[item.uniqueId] = it
                        }
                        return list
                    }
                    // else fall through to TMDB — no anime art to show.
                }
                else -> {}
            }
            // Null when TMDB cannot resolve the title at all (mistranslated
            // donghua, provider-only rows): the anime fallback below still
            // gets its chance instead of bailing out here.
            val resolved = TmdbResolver.resolve(item)
            // "anime" is TMDB's tv namespace (see TmdbResolver): the images
            // endpoint is the same one. Resolving any other way 404s every
            // anime/donghua title.
            val seg = if (resolved?.mediaType == "movie") "movie" else "tv"
            resolved?.let { r ->
                mem[seg + "/" + r.tmdbId]?.let {
                    if (it.isNotEmpty()) {
                        firstByKey[item.uniqueId] = it.first()
                        listByKey[item.uniqueId] = it
                    } else {
                        firstByKey.remove(item.uniqueId)
                        listByKey.remove(item.uniqueId)
                    }
                    // The portraits fetched alongside live in the twin cache.
                    posterMem[seg + "/" + r.tmdbId]?.let { posters ->
                        if (posters.isNotEmpty()) {
                            firstPosterByKey[item.uniqueId] = posters.first()
                            listPosterByKey[item.uniqueId] = posters
                        } else {
                            firstPosterByKey.remove(item.uniqueId)
                            listPosterByKey.remove(item.uniqueId)
                        }
                    }
                    return it
                }
            }
            val out = if (resolved == null) {
                emptyList()
            } else withContext(Dispatchers.IO) {
                // No language filter (desktop parity): a zh-tagged donghua
                // backdrop beats a provider thumbnail stretched over the
                // hero. English/unflagged art first, then art in any
                // language, best-voted first inside each group.
                val obj = TmdbResolver.apiGet(
                    "/$seg/${resolved.tmdbId}/images",
                    emptyMap(),
                ) ?: return@withContext emptyList()
                // Portraits ride the same response (`posters`) under the same
                // rule — the vertical page rotates these, capped like the
                // backdrops so the intent stays small. Parsed (and banked)
                // first, so a response without backdrops still leaves them.
                runCatching {
                    val parr = obj.optJSONArray("posters") ?: return@runCatching
                    val posterPref = ArrayList<Pair<Double, String>>()
                    val posterRest = ArrayList<Pair<Double, String>>()
                    for (i in 0 until parr.length()) {
                        val o = parr.optJSONObject(i) ?: continue
                        val path = o.optString("file_path").trim()
                            .takeIf { it.isNotBlank() && it != "null" } ?: continue
                        val lang = o.optString("iso_639_1")
                        (if (lang == "en" || lang.isBlank() || lang == "null") posterPref else posterRest)
                            .add(o.optDouble("vote_average", 0.0) to path)
                    }
                    val posters = (posterPref.sortedByDescending { it.first } + posterRest.sortedByDescending { it.first })
                        .take(8).map { IMG_POSTER + it.second }
                    if (resolved != null) {
                        if (posterMem.size >= 200) posterMem.clear()
                        if (posters.isNotEmpty()) {
                            posterMem[seg + "/" + resolved.tmdbId] = posters
                            firstPosterByKey[item.uniqueId] = posters.first()
                            listPosterByKey[item.uniqueId] = posters
                        } else {
                            firstPosterByKey.remove(item.uniqueId)
                            listPosterByKey.remove(item.uniqueId)
                        }
                    }
                }
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
            if (out.isNotEmpty() && resolved != null) {
                mem[seg + "/" + resolved.tmdbId] = out
                firstByKey[item.uniqueId] = out.first()
                listByKey[item.uniqueId] = out
            } else {
                firstByKey.remove(item.uniqueId)
                listByKey.remove(item.uniqueId)
            }
            if (out.isEmpty()) {
                // Universal fallback (parity with TmdbMeta.artwork): a title
                // TMDB missed still gets the anime sources' own banner/cover
                // sharp instead of a blurred provider poster. This is the
                // cover-only/scoreless case the usable-pick gate sends here.
                runCatching { DonghuaDetails.load(item) }.getOrNull()?.let { a ->
                    val fb = listOfNotNull(a.banner, a.cover).distinct()
                    if (fb.isNotEmpty()) {
                        firstByKey[item.uniqueId] = fb.first()
                        listByKey[item.uniqueId] = fb
                        listOfNotNull(a.cover).takeIf { it.isNotEmpty() }?.let {
                            firstPosterByKey[item.uniqueId] = it.first()
                            listPosterByKey[item.uniqueId] = it
                        }
                        return fb
                    }
                }
            }
            out
        }.getOrDefault(emptyList())
}
