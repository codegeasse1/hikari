package com.hikari.app.data

import com.hikari.app.ui.Artwork
import com.hikari.app.nuvio.TmdbResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

object TmdbScreenshots {

    private const val IMG_BACKDROP = "https://image.tmdb.org/t/p/w1280"
    /** Portrait art for the vertical server page — one step down from the
     *  backdrop size, enough for a 560dp column. */
    private const val IMG_POSTER = "https://image.tmdb.org/t/p/w500"

    private val mem = ConcurrentHashMap<String, List<String>>()

    /** Portrait-art twin of [mem]: one `/images` call carries both lists. */
    private val posterMem = ConcurrentHashMap<String, List<String>>()

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
            if (item.type != MediaType.SERIES && item.type != MediaType.MOVIE &&
                item.type != MediaType.UNKNOWN
            ) return emptyList()
            // User order: TMDB first, anime banner second. The anime pick used
            // to win outright here; now it only fills the gaps TMDB leaves —
            // computed once and consulted when TMDB resolves to nothing.
            // Absolute URLs — never TMDB's mistranslated backdrops. A bare pick
            // (no art, or cover-only with no overview/score) still falls
            // through instead of going bare.
            val pickFallback: List<String>? = when (val pick = DonghuaDetails.pick(item)) {
            is DonghuaDetails.Pick.Anime -> {
                    val list = listOfNotNull(pick.info.banner, pick.info.cover).distinct()
                    if (list.isNotEmpty()) {
                        firstByKey[item.uniqueId] = list.first()
                        listByKey[item.uniqueId] = list
                        Artwork.noteHeaderArt(item, pick.info.cover, list.first())
                        listOfNotNull(pick.info.cover).takeIf { it.isNotEmpty() }?.let {
                            firstPosterByKey[item.uniqueId] = it.first()
                            listPosterByKey[item.uniqueId] = it
                        }
                        list
                    } else null
                }
                else -> null
            }
            val resolved = TmdbResolver.resolve(item)
                ?: runCatching { TvdbArt.tmdbBridge(item) }.getOrNull()
            val seg = if (resolved?.mediaType == "movie") "movie" else "tv"
            resolved?.let { r ->
                mem[seg + "/" + r.tmdbId]?.let {
                    if (it.isNotEmpty()) {
                        firstByKey[item.uniqueId] = it.first()
                        listByKey[item.uniqueId] = it
                        Artwork.noteHeaderArt(
                            item,
                            posterMem[seg + "/" + r.tmdbId]?.firstOrNull(),
                            it.first(),
                        )
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
                val obj = TmdbResolver.apiGet(
                    "/$seg/${resolved.tmdbId}/images",
                    emptyMap(),
                ) ?: return@withContext emptyList()
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
            // TMDB's answer wins (user order); the anime pick fills only the
            // gaps. The key writes for the pick already happened above, so a
            // TMDB miss must not wipe them here.
            val combined = if (out.isNotEmpty()) out else (pickFallback ?: emptyList())
            if (out.isNotEmpty() && resolved != null) {
                mem[seg + "/" + resolved.tmdbId] = out
                firstByKey[item.uniqueId] = out.first()
                listByKey[item.uniqueId] = out
                Artwork.noteHeaderArt(item, firstPosterByKey[item.uniqueId], out.first())
            } else if (pickFallback == null) {
                firstByKey.remove(item.uniqueId)
                listByKey.remove(item.uniqueId)
            }
            if (combined.isEmpty()) {
                runCatching { TvdbArt.lookup(item) }.getOrNull()?.let { tv ->
                    val fb = listOfNotNull(tv.banner, tv.poster).distinct()
                    if (fb.isNotEmpty()) {
                        firstByKey[item.uniqueId] = fb.first()
                        listByKey[item.uniqueId] = fb
                        Artwork.noteHeaderArt(item, tv.poster, fb.first())
                        listOfNotNull(tv.poster).takeIf { it.isNotEmpty() }?.let {
                            firstPosterByKey[item.uniqueId] = it.first()
                            listPosterByKey[item.uniqueId] = it
                        }
                        return fb
                    }
                }
                runCatching { DonghuaDetails.load(item) }.getOrNull()?.let { a ->
                    val fb = listOfNotNull(a.banner, a.cover).distinct()
                    if (fb.isNotEmpty()) {
                        firstByKey[item.uniqueId] = fb.first()
                        listByKey[item.uniqueId] = fb
                        Artwork.noteHeaderArt(item, a.cover, fb.first())
                        listOfNotNull(a.cover).takeIf { it.isNotEmpty() }?.let {
                            firstPosterByKey[item.uniqueId] = it.first()
                            listPosterByKey[item.uniqueId] = it
                        }
                        return fb
                    }
                }
            }
            combined
        }.getOrDefault(emptyList())
}
