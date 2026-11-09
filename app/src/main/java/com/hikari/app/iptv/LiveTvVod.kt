package com.hikari.app.iptv

import com.hikari.app.data.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * On Demand: each Xtream login's movies and series, imported into a small
 * file cache (categories + items, details on open). Plays through the normal
 * provider path — see [vodStreams].
 */
class LiveTvVod {

    /** Source id -> its categories (movies + series). */
    @Volatile private var categories: Map<String, Pair<List<VodCategory>, List<VodCategory>>> = emptyMap()

    /** Source id -> "m"|"s" -> category id -> items. */
    @Volatile private var items: Map<String, List<VodItem>> = emptyMap()

    data class VodCategory(val id: String, val name: String)

    private fun cacheFile(src: LiveTvSource, what: String): File =
        File(LiveTvEngine.store.vodRoot, src.id + "_" + what + ".json").apply { parentFile?.mkdirs() }

    /** Imports one source's On Demand catalogue (categories + every title). */
    suspend fun refreshSource(src: LiveTvSource) {
        if (!src.isXtream || !src.importVod) return
        val eng = LiveTvEngine.xtream
        val movieCats = withContext(Dispatchers.IO) { eng.vodCategories(src, false) }
        val seriesCats = withContext(Dispatchers.IO) { eng.vodCategories(src, true) }
        categories = categories + (src.id to (movieCats.map { VodCategory(it.first, it.second) } to seriesCats.map { VodCategory(it.first, it.second) }))
        val all = ArrayList<VodItem>()
        val hidden = LiveTvEngine.store.userState().vodHiddenCategories
        movieCats.forEach { (cid, cname) ->
            if ("movie:${src.id}:$cid" in hidden) return@forEach
            withContext(Dispatchers.IO) { runCatching { eng.vodItems(src, false, cid) }.getOrDefault(emptyList()) }
                .forEach { e ->
                    all += VodItem(
                        uid = "${src.id}|movie|${e.id}", sourceId = src.id, sourceName = src.name,
                        isSeries = false, id = e.id, name = e.name, icon = e.icon,
                        categoryId = cid, categoryName = cname,
                        containerExtension = e.containerExtension, tmdbId = e.tmdbId,
                        year = e.year, rating = e.rating, addedSec = e.addedSec, genre = e.genre,
                    )
                }
        }
        seriesCats.forEach { (cid, cname) ->
            if ("series:${src.id}:$cid" in hidden) return@forEach
            withContext(Dispatchers.IO) { runCatching { eng.vodItems(src, true, cid) }.getOrDefault(emptyList()) }
                .forEach { e ->
                    all += VodItem(
                        uid = "${src.id}|series|${e.id}", sourceId = src.id, sourceName = src.name,
                        isSeries = true, id = e.id, name = e.name, icon = e.icon,
                        categoryId = cid, categoryName = cname,
                        containerExtension = e.containerExtension, tmdbId = e.tmdbId,
                        year = e.year, rating = e.rating, addedSec = e.addedSec, genre = e.genre,
                    )
                }
        }
        val merged = if (LiveTvEngine.store.settings().vodMergeDuplicates) mergeDuplicates(all) else all
        items = items + (src.id to merged)
        // Persist a light index (uid → names) so the tab paints before refresh.
        runCatching {
            val a = JSONArray()
            merged.take(20000).forEach { v ->
                a.put(JSONObject().put("uid", v.uid).put("name", v.name).put("icon", v.icon.orEmpty())
                    .put("cat", v.categoryName).put("series", v.isSeries).put("year", v.year.orEmpty()))
            }
            cacheFile(src, "index").writeText(a.toString())
        }
        Logs.log("LiveTv", "${src.name}: on-demand ${merged.size} title(s)")
    }

    fun titles(sourceId: String? = null): List<VodItem> {
        val all = if (sourceId == null) items.values.flatten() else items[sourceId].orEmpty()
        return sortTitles(all, LiveTvEngine.store.settings().vodDefaultSort)
    }

    fun categoriesOf(sourceId: String, series: Boolean): List<VodCategory> {
        val (m, s) = categories[sourceId] ?: return emptyList()
        return if (series) s else m
    }

    fun search(query: String): List<VodItem> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        return items.values.flatten().filter { it.name.contains(q, ignoreCase = true) }.take(300)
    }

    suspend fun episodesOf(item: VodItem): List<VodEpisode> {
        val src = LiveTvEngine.sourceOf(item.sourceId) ?: return emptyList()
        if (!item.isSeries) return emptyList()
        val info = withContext(Dispatchers.IO) {
            runCatching { LiveTvEngine.xtream.vodInfo(src, true, item.id) }.getOrNull()
        } ?: return emptyList()
        val out = ArrayList<VodEpisode>()
        val episodes = info.optJSONObject("episodes")
        if (episodes != null) {
            episodes.keys().forEach { seasonKey ->
                val season = seasonKey.toIntOrNull() ?: return@forEach
                val arr = episodes.optJSONArray(seasonKey) ?: return@forEach
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    out += VodEpisode(
                        season = season,
                        episode = e.optString("episode_num").toIntOrNull() ?: (i + 1),
                        title = e.optString("title").ifBlank { "Episode ${i + 1}" },
                        streamId = e.optString("id"),
                        containerExtension = e.optString("container_extension").ifBlank { item.containerExtension },
                        info = e.optString("info").ifBlank { null },
                    )
                }
            }
        }
        return out.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    suspend fun infoOf(item: VodItem): JSONObject? {
        val src = LiveTvEngine.sourceOf(item.sourceId) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { LiveTvEngine.xtream.vodInfo(src, item.isSeries, item.id) }.getOrNull()
        }
    }

    /** Direct stream URL(s) for a movie or one series episode. */
    fun streamUrls(item: VodItem, episode: VodEpisode? = null): List<Pair<String, String>> {
        val src = LiveTvEngine.sourceOf(item.sourceId) ?: return emptyList()
        return if (item.isSeries) {
            val ep = episode ?: return emptyList()
            if (ep.streamId.isBlank()) return emptyList()
            listOf(
                "Episode ${ep.episode}" to LiveTvEngine.xtream.episodeUrl(src, ep.streamId, ep.containerExtension ?: item.containerExtension)
            )
        } else {
            listOf(item.name to LiveTvEngine.xtream.movieUrl(src, item.id, item.containerExtension))
        }
    }

    /** One title per provider-name (the provider lists copies). */
    fun mergeDuplicates(all: List<VodItem>): List<VodItem> {
        val seen = HashSet<String>()
        val out = ArrayList<VodItem>()
        all.forEach { v ->
            val key = v.sourceId + "|" + v.isSeries + "|" + LiveTvEngine.normName(v.name)
            if (seen.add(key)) out += v
        }
        return out
    }

    fun sortTitles(all: List<VodItem>, sort: String): List<VodItem> = when (sort.uppercase()) {
        "NAME" -> all.sortedBy { it.name.lowercase() }
        "NEWEST" -> all.sortedByDescending { it.addedSec }
        "RATING" -> all.sortedByDescending { it.rating?.toDoubleOrNull() ?: -1.0 }
        else -> all
    }

    fun defaultSorts(): List<String> = listOf("DEFAULT", "NAME", "NEWEST", "RATING")
}
