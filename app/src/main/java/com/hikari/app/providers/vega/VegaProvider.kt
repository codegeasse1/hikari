package com.hikari.app.providers.vega

import com.hikari.app.HikariApp
import com.hikari.app.data.CatalogRef
import com.hikari.app.data.Episode
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.StreamSource
import com.hikari.app.data.SubtitleSource
import com.hikari.app.net.Http
import com.hikari.app.providers.ContentProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Adapts a Vega provider (a folder of CommonJS modules under
 * `filesDir/vega/providers/<value>`) to Hikari's ContentProvider contract.
 *
 * Unlike a nuvio provider — which only resolves streams — a Vega provider is a
 * whole site and maps onto every part of the contract:
 *
 *   catalogs()      <- catalog.js's `catalog` + `genres` arrays
 *   getCatalog()    <- posts.js  getPosts({filter, page})
 *   search()        <- posts.js  getSearchPosts({searchQuery, page})
 *   getMeta()       <- meta.js   getMeta({link})           -> Info
 *   getEpisodes()   <- meta.js Info.linkList, then episodes.js getEpisodes({url})
 *   getStreams()    <- stream.js getStream({link, type, isDownload})
 *
 * The item's `id` is the provider's own opaque link (a page URL, or a JSON blob
 * the provider's own meta produced), carried through Hikari verbatim — never
 * parsed, never re-encoded.
 */
class VegaProvider(override val config: ProviderConfig) : ContentProvider {

    companion object {
        /** Per-provider last stream failure (shown on the Detail screen). */
        val streamErrors = ConcurrentHashMap<String, String>()

        /** Per-provider last lookup outcome — one short line per provider,
         *  shown in the sources sheet. */
        val lastOutcome = ConcurrentHashMap<String, String>()

        /** Per-provider home failure (shown on the Home empty state). */
        val catalogErrors = ConcurrentHashMap<String, String>()

        private const val MAX_ITEMS = 120
        private const val MAX_EPISODES = 2000
        private const val MAX_STREAMS = 60
        private const val MAX_SEASONS = 24
        private const val CATALOG_TTL_MS = 30 * 60 * 1000L
        /** Detail jobs kept per provider — an LRU, because a session opens
         *  hundreds of titles and each job holds one season list's raw JSON. */
        private const val DETAIL_JOBS_MAX = 8
    }

    /** `vega|<value>` → the provider's manifest value (its dist folder name). */
    private val value: String get() = config.id.removePrefix("vega|")

    private val dir: File get() = VegaPluginManager.dirOf(config)

    /** catalog.js's parsed `{catalog, genres}`, read once per provider. */
    @Volatile
    private var catalogCache: JSONObject? = null

    @Volatile
    private var catalogReadAt = 0L

    /** `Info` per item link — getEpisodes and getStreams both need it, and both
     *  are called for the same title. */
    private val infoCache = ConcurrentHashMap<String, JSONObject>()

    /** The episodes of an item, and the episode links they resolve to. */
    private val episodeCache = ConcurrentHashMap<String, List<Episode>>()

    /** The stream link a movie's own meta resolved to. */
    private val movieLinkCache = ConcurrentHashMap<String, String>()

    /**
     * The UI-picked pack per title (series audio/season pack, see [VegaPack]).
     * The detail page's dropdown writes it and the episode lookup reads it, so
     * the list on screen is always the pack the viewer picked — one pack at a
     * time, exactly the way the real Vega app works (its SeasonList keeps one
     * `activeSeason` and only ever asks that entry's `episodesLink`). Absent =
     * pack 0, which is also the Vega app's default (`LinkList[0]`).
     */
    private val selectedPack = ConcurrentHashMap<String, Int>()

    /**
     * The UI-picked direct link per movie title (quality-pack choice). Read by
     * [movieLink] before anything else, so Play resolves the quality row the
     * viewer picked instead of always the first row of the first pack.
     */
    private val selectedLink = ConcurrentHashMap<String, String>()

    /**
     * One detail job per item link — the work [getMeta] and [getEpisodes] share.
     *
     * Both halves of it live in the same engine (see [VegaRuntime.detail]) and
     * the second cannot even be asked for without the first, so they are one
     * unit of work with two delivery points: the meta document as soon as it
     * answers, the seasons when they are done. Finished jobs stay in the map
     * (bounded, least-recently-used first) so a caller that arrives after the
     * engine has already closed still finds the answer here instead of booting
     * a third engine for it.
     */
    private class DetailJob {
        val info = CompletableDeferred<JSONObject?>()
        val episodes = CompletableDeferred<Map<Int, JSONArray>>()
    }

    private val detailJobs = object : LinkedHashMap<String, DetailJob>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DetailJob>?) =
            size > DETAIL_JOBS_MAX
    }

    /** Runs detail jobs in their own coroutines: their two callers come and go
     *  (a page's meta fetch and its episode fetch are separate calls, and either
     *  can be cancelled by its own timeout), while the engine one of them
     *  started keeps going and its answer lands in the caches either way. */
    private val detailScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---- catalogue ----

    override suspend fun catalogs(): List<CatalogRef> = withContext(Dispatchers.IO) {
        val lists = catalogLists() ?: return@withContext emptyList()
        val out = ArrayList<CatalogRef>()
        readList(lists.optJSONArray("catalog")).forEach { (title, filter) ->
            out += CatalogRef(config.id, MediaType.UNKNOWN, "cat:$filter", title, "vega")
        }
        readList(lists.optJSONArray("genres")).forEach { (title, filter) ->
            out += CatalogRef(config.id, MediaType.UNKNOWN, "genre:$filter", title, "vega")
        }
        out.distinctBy { it.id }
    }

    /** Home rows are the provider's OWN catalog entries — its genres stay out of
     *  the feed and are reachable through the catalogue picker, so an AniKoto
     *  home page is not twenty genre rows. */
    override suspend fun homeCatalogs(): List<CatalogRef> = withContext(Dispatchers.IO) {
        val lists = catalogLists() ?: return@withContext emptyList()
        readList(lists.optJSONArray("catalog")).map { (title, filter) ->
            CatalogRef(config.id, MediaType.UNKNOWN, "cat:$filter", title, "vega")
        }.distinctBy { it.id }
    }

    private suspend fun catalogLists(): JSONObject? {
        val cached = catalogCache
        if (cached != null && System.currentTimeMillis() - catalogReadAt < CATALOG_TTL_MS) return cached
        if (!File(dir, "catalog.js").exists()) {
            catalogErrors[config.id] = "This provider has no catalogue (it is search-only)."
            return null
        }
        val json = VegaRuntime.catalogJson(dir, value, value)
        val parsed = json?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (parsed == null) {
            catalogErrors[config.id] = "This provider's catalogue would not load — tap Retry."
            return null
        }
        catalogErrors.remove(config.id)
        catalogCache = parsed
        catalogReadAt = System.currentTimeMillis()
        return parsed
    }

    private fun readList(arr: JSONArray?): List<Pair<String, String>> {
        if (arr == null) return emptyList()
        val out = ArrayList<Pair<String, String>>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title").trim().ifBlank { "Browse" }
            val filter = o.optString("filter")
            out += title to filter
        }
        return out
    }

    override suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val filter = ref.id.substringAfter(':', "")
            val args = JSONObject()
                .put("filter", filter)
                .put("page", page.coerceAtLeast(1))
            val payload = VegaRuntime.call(
                dir, value, value, "posts", "getPosts", args.toString(), catalogBudget = true,
            )
            val data = dataOf(payload) ?: run {
                noteCatalogFailure(payload, "Posts")
                return@withContext emptyList()
            }
            lastOutcome.remove(config.id)
            toItems(data)
        }

    override suspend fun search(query: String, page: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            if (!File(dir, "posts.js").exists()) return@withContext emptyList()
            val args = JSONObject()
                .put("searchQuery", query)
                .put("page", page.coerceAtLeast(1))
            val payload = VegaRuntime.call(
                dir, value, value, "posts", "getSearchPosts", args.toString(), catalogBudget = true,
            )
            val data = dataOf(payload) ?: run {
                noteCatalogFailure(payload, "Search")
                return@withContext emptyList()
            }
            lastOutcome.remove(config.id)
            toItems(data)
        }

    private fun noteCatalogFailure(payload: String, what: String) {
        val err = runCatching { JSONObject(payload).optString("error") }.getOrNull()
        val msg = if (err.isNullOrBlank()) "$what returned nothing" else err
        catalogErrors[config.id] = "$what failed — $msg".take(200)
        lastOutcome[config.id] = "✗ ${msg.take(72)}"
    }

    private fun toItems(data: Any?): List<MediaItem> {
        val arr = data as? JSONArray ?: return emptyList()
        val out = ArrayList<MediaItem>(minOf(arr.length(), MAX_ITEMS))
        for (i in 0 until minOf(arr.length(), MAX_ITEMS)) {
            val o = arr.optJSONObject(i) ?: continue
            val link = o.optString("link").trim()
            val title = o.optString("title").trim()
                .ifBlank { o.optString("name").trim() }
            if (link.isBlank() || title.isBlank()) continue
            out += MediaItem(
                providerId = config.id,
                id = link,
                title = title,
                type = MediaType.UNKNOWN,
                posterUrl = o.optString("image").ifBlank { o.optString("poster") }
                    .ifBlank { o.optString("posterUrl") }.ifBlank { null },
                rawType = o.optString("type").ifBlank { "movie" },
            )
        }
        return out
    }

    // ---- details ----

    /** The provider's `Info` for an item, cached — both [getEpisodes] and
     *  [getStreams] ask for it. */
    private suspend fun infoFor(item: MediaItem): JSONObject? {
        if (item.id.isBlank()) return null
        infoCache[item.id]?.let { return it }
        // A detail job already has the document in hand (or is fetching it right
        // now): await THAT rather than booting a second engine for a page the
        // user is already looking at.
        synchronized(detailJobs) { detailJobs[item.id] }?.let { job ->
            val info = job.info.await()
            if (info != null) infoCache[item.id] = info
            return info
        }
        if (!File(dir, "meta.js").exists()) return null
        val args = JSONObject().put("link", item.id)
        val payload = VegaRuntime.call(dir, value, value, "meta", "getMeta", args.toString())
        val info = dataObject(payload) ?: return null
        infoCache[item.id] = info
        return info
    }

    override suspend fun getMeta(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        // The detail job, not [infoFor]: this is the call the detail page makes,
        // and the job is what puts the meta document AND the season list in one
        // engine instead of two (see [VegaRuntime.detail]). The header still
        // paints the moment the meta half answers — the job completes `info`
        // then, while its episodes are still being fetched.
        val info = detailJob(item).info.await() ?: infoFor(item) ?: return@withContext item
        val type = info.optString("type").trim().lowercase()
        MediaItem(
            providerId = item.providerId,
            id = item.id,
            title = info.optString("title").trim().ifBlank { item.title },
            type = mediaTypeOf(type),
            posterUrl = info.optString("image").ifBlank { item.posterUrl.orEmpty() }.ifBlank { null },
            overview = info.optString("synopsis").ifBlank { info.optString("overview") }
                .ifBlank { item.overview.orEmpty() }.ifBlank { null },
            genres = stringList(info.optJSONArray("tags")),
            rawType = type.ifBlank { item.rawType },
            originalTitle = item.originalTitle,
        )
    }

    /**
     * Starts (or finds) the one job behind a title's detail view — meta document
     * and seasons in a single engine.
     *
     * Nothing here waits: the caller awaits whichever half it needs. A job whose
     * engine dies completes both halves (null / empty) rather than leaving a
     * caller hanging, and the job stays in [detailJobs] afterwards so the other
     * caller — which is a separate call from the same screen — gets the same
     * answer instead of starting a fresh engine.
     */
    private fun detailJob(item: MediaItem): DetailJob {
        synchronized(detailJobs) { detailJobs[item.id] }?.let { return it }
        val job = DetailJob()
        val previous = synchronized(detailJobs) { detailJobs.put(item.id, job) }
        if (previous != null) return previous
        detailScope.launch {
            try {
                val args = JSONObject().put("link", item.id).toString()
                val payload = VegaRuntime.detail(dir, value, value, args) { infoJson ->
                    val parsed = runCatching { JSONObject(infoJson) }.getOrNull()
                    if (parsed != null) infoCache[item.id] = parsed
                    job.info.complete(parsed)
                }
                val data = dataObject(payload)
                // Whichever half has the document: the progress callback normally
                // delivered it while the seasons were still being fetched, and the
                // final payload carries it again. Read through the deferred rather
                // than through a captured variable — the callback runs on the
                // engine's own thread.
                var info: JSONObject? = runCatching { job.info.getCompleted() }.getOrNull()
                val fromPayload = data?.optJSONObject("info")
                if (fromPayload != null) info = fromPayload
                info?.let { infoCache[item.id] = it }
                if (info == null && File(dir, "meta.js").exists()) {
                    // The combined run produced no document at all (a module that
                    // would not compile, an engine that stopped answering): ask
                    // for the meta on its own, the way this provider always did,
                    // so a page never loses its header to the detail call's own
                    // failure.
                    val args = JSONObject().put("link", item.id).toString()
                    info = dataObject(VegaRuntime.call(dir, value, value, "meta", "getMeta", args.toString()))
                        ?.also { infoCache[item.id] = it }
                }
                if (!job.info.isCompleted) job.info.complete(info)
                job.episodes.complete(packEpisodesFrom(data, info))
            } catch (e: Throwable) {
                if (!job.info.isCompleted) job.info.complete(null)
                if (!job.episodes.isCompleted) job.episodes.complete(emptyMap())
            }
        }
        return job
    }

    /** One `linkList` entry of a Vega `Info`, verbatim and in list order.
     *
     *  A pack is whatever one row of the provider's answer is: an audio
     *  variant ("Original Audio", "Hindi dub"), a season ("Season 1"), a
     *  season+quality pair ("Season 1 1080p") or a movie quality row. It either
     *  carries the `episodesLink` a separate episodes.js request resolves, or
     *  its own `directLinks`, or a single plain `link`. The detail page shows
     *  these titles EXACTLY as the provider wrote them (the real Vega app's
     *  SeasonList dropdown does the same) and only ever opens the picked one —
     *  merging every pack's episodes into one list with made-up season numbers
     *  is what made "Season 10" show season 1's episodes.
     */
    fun packsOf(info: JSONObject): List<VegaPack> {
        val list = info.optJSONArray("linkList") ?: return emptyList()
        if (list.length() == 0) return emptyList()
        val packs = ArrayList<VegaPack>(list.length())
        for (i in 0 until list.length()) {
            val e = list.optJSONObject(i) ?: continue
            val direct = e.optJSONArray("directLinks")
            val epLink = e.optString("episodesLink").trim()
            val single = e.optString("link").trim()
            if (direct != null && direct.length() > 0) {
                packs += VegaPack(i, e.optString("title").trim().ifBlank { "Option ${i + 1}" }, direct, epLink, "")
            } else if (epLink.isNotBlank()) {
                packs += VegaPack(i, e.optString("title").trim().ifBlank { "Option ${i + 1}" }, null, epLink, "")
            } else if (single.isNotBlank()) {
                packs += VegaPack(
                    i,
                    e.optString("title").trim().ifBlank { "Option ${i + 1}" },
                    JSONArray().put(JSONObject().put("link", single).put("title", e.optString("title"))),
                    "",
                    single,
                )
            }
        }
        return packs
    }

    /** The packs of a title for the detail page's picker, in provider order. */
    suspend fun packsFor(item: MediaItem): List<VegaPack> = withContext(Dispatchers.IO) {
        val info = infoFor(item) ?: return@withContext emptyList()
        packsOf(info)
    }

    /** The UI-picked series pack for a title (see [selectedPack]). */
    fun setSelectedPack(itemId: String, index: Int) {
        if (index <= 0) selectedPack.remove(itemId)
        else selectedPack[itemId] = index
    }

    /** The UI-picked movie link for a title (see [selectedLink]). */
    fun setSelectedLink(itemId: String, link: String?) {
        if (link.isNullOrBlank()) selectedLink.remove(itemId)
        else selectedLink[itemId] = link
    }

    /**
     * The UI selection folded into a cache-key suffix, so a pack or quality
     * switch is a different lookup — never the previous selection's answers.
     * "" when nothing is picked (pack 0, first row), which keeps every default
     * key exactly what it has always been.
     */
    fun selectionKey(itemId: String): String {
        val pack = selectedPack[itemId] ?: 0
        val link = selectedLink[itemId]
        if (pack == 0 && link.isNullOrBlank()) return ""
        return "|vsel:$pack:${link?.hashCode() ?: 0}"
    }

    /**
     * Every playable movie option of a title, in provider order: each pack's
     * `directLinks` (a quality row each), else the pack's own plain link. The
     * first option is what [movieLink] has always resolved, so the default —
     * nothing picked — plays exactly what it used to.
     */
    suspend fun movieOptions(item: MediaItem): List<VegaMovieOption> = withContext(Dispatchers.IO) {
        val info = infoFor(item) ?: return@withContext emptyList()
        val out = ArrayList<VegaMovieOption>()
        for (pack in packsOf(info)) {
            val direct = pack.direct
            if (direct != null && direct.length() > 0) {
                for (i in 0 until direct.length()) {
                    val o = direct.optJSONObject(i) ?: continue
                    val l = o.optString("link").trim()
                    if (l.isBlank()) continue
                    val t = o.optString("title").trim().ifBlank { pack.title }
                    out += VegaMovieOption(t, l, pack.title)
                }
            } else if (pack.link.isNotBlank()) {
                out += VegaMovieOption(pack.title, pack.link, pack.title)
            }
        }
        out
    }

    /** The detail payload's episode arrays, keyed by PACK index — the position
     *  of the entry in the full `linkList`, not a parsed season number. The
     *  runtime collects one answer per entry carrying an `episodesLink`, in
     *  list order ([VegaRuntime.detail]), so the walk below replays that same
     *  order and files each answer under its own pack. */
    private fun packEpisodesFrom(data: Any?, info: JSONObject?): Map<Int, JSONArray> {
        val obj = data as? JSONObject ?: return emptyMap()
        val arr = obj.optJSONArray("episodes") ?: return emptyMap()
        val source = info ?: obj.optJSONObject("info") ?: return emptyMap()
        val packs = packsOf(source)
        val map = HashMap<Int, JSONArray>()
        var reqIdx = 0
        packs.take(MAX_SEASONS).forEach { pack ->
            if (pack.episodesLink.isBlank()) return@forEach
            if (reqIdx < arr.length()) {
                (arr.opt(reqIdx) as? JSONArray)?.let { a -> map[pack.index] = a }
            }
            reqIdx++
        }
        return map
    }

    /**
     * One pack's episode array: the detail job's answer when there is one,
     * else an engine of its own for just that pack's `episodesLink`.
     *
     * The job is the fast path and the one the detail page takes. The fallback
     * is for a caller that asks a title for its episodes WITHOUT ever asking
     * for its meta (a background re-ask, a "next episode" prefetch): it pays
     * for its own engine, exactly as it always did — but only for the one pack
     * being shown, never for the whole list at once.
     */
    private suspend fun fetchedPackEpisodes(
        item: MediaItem,
        pack: VegaPack,
    ): JSONArray? {
        if (pack.episodesLink.isBlank() || !File(dir, "episodes.js").exists()) return null
        synchronized(detailJobs) { detailJobs[item.id] }?.let { job -> return job.episodes.await()[pack.index] }
        val args = JSONArray().put(JSONObject().put("url", pack.episodesLink))
        val payload = VegaRuntime.callMany(dir, value, value, "episodes", "getEpisodes", args.toString())
        val data = dataOf(payload)
        return if (data is JSONArray && data.length() > 0) data.opt(0) as? JSONArray else null
    }

    /**
     * The episodes of ONE pack — the picked entry's `getEpisodes` answer plus,
     * appended after it, the entry's own `directLinks`, the same two sections
     * the real Vega app renders for one picked entry.
     *
     * Season numbers come from the episode titles themselves ("S01 E01",
     * "Season 2", "1x03") first — those are the provider's own season markers
     * — then from the pack's title ("Season 2 480p"), then 1. Parsing the
     * titles is what makes the season picker filter REAL seasons: numbering
     * packs 1..N and calling those "seasons" is what showed pack 10's list
     * under the "Season 10" pill while every row still read S01.
     */
    suspend fun getPackEpisodes(item: MediaItem, packIndex: Int): List<Episode>? =
        withContext(Dispatchers.IO) {
            val info = infoFor(item) ?: return@withContext null
            val packs = packsOf(info)
            if (packs.isEmpty()) return@withContext null
            val pack = packs.firstOrNull { it.index == packIndex } ?: packs.first()
            val key = "${item.id}|pack:${pack.index}"
            episodeCache[key]?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
            val fetched = fetchedPackEpisodes(item, pack)
            val packSeason = seasonNumberOf(pack.title, pack.index + 1)
            val out = ArrayList<Episode>()
            var number = 0
            fetched?.let { arr ->
                for (i in 0 until arr.length()) {
                    if (out.size >= MAX_EPISODES) break
                    val o = arr.optJSONObject(i) ?: continue
                    val link = o.optString("link").trim()
                    if (link.isBlank()) continue
                    number++
                    val name = o.optString("title").trim().ifBlank { null }
                    out += Episode(
                        number = number,
                        id = link,
                        name = name,
                        season = seasonFromEpisodeTitle(name) ?: packSeason,
                    )
                }
            }
            pack.direct?.let { arr ->
                for (i in 0 until arr.length()) {
                    if (out.size >= MAX_EPISODES) break
                    val o = arr.optJSONObject(i) ?: continue
                    val link = o.optString("link").trim()
                    if (link.isBlank()) continue
                    number++
                    val name = o.optString("title").trim().ifBlank { null }
                    out += Episode(
                        number = number,
                        id = link,
                        name = name,
                        season = seasonFromEpisodeTitle(name) ?: packSeason,
                    )
                }
            }
            if (out.isEmpty()) return@withContext null
            episodeCache[key] = out
            out
        }

    override suspend fun getEpisodes(item: MediaItem): List<Episode>? = withContext(Dispatchers.IO) {
        if (item.type == MediaType.MOVIE) return@withContext null
        val job = detailJob(item)
        val info = job.info.await() ?: infoCache[item.id] ?: return@withContext null
        val type = info.optString("type").trim().lowercase()
        val packs = packsOf(info)
        if (packs.isEmpty()) return@withContext null
        val isSeries = type == "series" || type == "tv" ||
            (type.isBlank() && (packs.size > 1 || packs.any { it.episodesLink.isNotBlank() }))
        if (!isSeries) return@withContext null
        // One pack at a time — the picked one, pack 0 by default — the way the
        // real Vega app only ever opens its `activeSeason` entry. Merging every
        // pack's answer into one list is what broke season switching.
        val picked = selectedPack[item.id]?.let { idx -> packs.firstOrNull { it.index == idx } }
            ?: packs.first()
        getPackEpisodes(item, picked.index)
    }

    // ---- streams ----

    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            if (!File(dir, "stream.js").exists()) {
                return@withContext fail("✗ This provider has no stream module — reinstall it.")
            }
            val type = runCatching { infoFor(item)?.optString("type") }.getOrNull()
                ?.trim()?.lowercase()?.ifBlank { null }
                ?: item.rawType.ifBlank { "movie" }
            val link = episode?.id?.takeIf { it.isNotBlank() }
                ?: movieLink(item)
                ?: return@withContext fail("✗ No playable link for this title.")
            val args = JSONObject()
                .put("link", link)
                .put("type", type)
                .put("isDownload", false)
            val payload = VegaRuntime.call(dir, value, value, "stream", "getStream", args.toString())
            val data = dataOf(payload) ?: run {
                val err = runCatching { JSONObject(payload).optString("error") }.getOrNull()
                return@withContext fail("✗ " + (err?.takeIf { it.isNotBlank() } ?: "no sources found"))
            }
            val out = mapStreams(data)
            if (out.isEmpty()) return@withContext fail("✗ No playable sources for this title.")
            streamErrors.remove(config.id)
            lastOutcome[config.id] = "✓ ${out.size} source${if (out.size == 1) "" else "s"} in " +
                "${(System.currentTimeMillis() - started) / 1000}s"
            val distinct = out.distinctBy { it.url }
            com.hikari.app.net.StreamProbe.warmAsync(distinct)
            distinct
        }

    /** A movie has no episode, so its playable link comes from its own meta's
     *  `linkList` — the UI-picked quality row first ([selectedLink]), else a
     *  `directLinks[0]`, a pack's own `link`, or the page URL. */
    private suspend fun movieLink(item: MediaItem): String? {
        selectedLink[item.id]?.takeIf { it.isNotBlank() }?.let { return it }
        movieLinkCache[item.id]?.let { return it }
        val info = infoFor(item) ?: return item.id.takeIf { it.isNotBlank() }
        val list = info.optJSONArray("linkList")
        if (list != null) {
            for (i in 0 until list.length()) {
                val e = list.optJSONObject(i) ?: continue
                val direct = e.optJSONArray("directLinks")
                if (direct != null && direct.length() > 0) {
                    val l = direct.optJSONObject(0)?.optString("link").orEmpty().trim()
                    if (l.isNotBlank()) return l.also { movieLinkCache[item.id] = it }
                }
                val l = e.optString("link").trim()
                if (l.isNotBlank()) return l.also { movieLinkCache[item.id] = it }
            }
        }
        val web = info.optString("webUrl").trim()
        if (web.isNotBlank()) return web.also { movieLinkCache[item.id] = it }
        return item.id.takeIf { it.isNotBlank() }
    }

    private fun mapStreams(data: Any?): List<StreamSource> {
        val arr = data as? JSONArray ?: return emptyList()
        val out = ArrayList<StreamSource>()
        for (i in 0 until minOf(arr.length(), MAX_STREAMS)) {
            val o = arr.optJSONObject(i) ?: continue
            val raw = o.optString("link").trim()
            if (raw.isBlank()) continue
            val kind = o.optString("type").trim().lowercase()
            val quality = o.optString("quality").trim()
            val server = o.optString("server").trim().ifBlank { o.optString("name").trim() }
            val base = server.ifBlank { config.name }
            val name = if (quality.isNotBlank() && !base.contains(quality, true)) "$base $quality" else base
            val headers = LinkedHashMap<String, String>()
            o.optJSONObject("headers")?.keys()?.forEach { k ->
                val v = o.optJSONObject("headers")?.optString(k) ?: return@forEach
                val clean = v.filter { it.code in 32..126 }
                if (clean.isNotBlank()) headers.putIfAbsent(k, clean)
            }
            headers.putIfAbsent("User-Agent", Http.UA)
            val subs = ArrayList<SubtitleSource>()
            o.optJSONArray("subtitles")?.let { subsArr ->
                for (j in 0 until subsArr.length()) {
                    val st = subsArr.optJSONObject(j) ?: continue
                    val su = st.optString("uri").ifBlank { st.optString("file") }
                        .ifBlank { st.optString("url") }.trim()
                    if (su.isBlank()) continue
                    subs += SubtitleSource(
                        lang = st.optString("language").ifBlank { st.optString("label") }
                            .ifBlank { st.optString("title") }.ifBlank { "Sub" },
                        url = su,
                    )
                }
            }
            val isTorrent = raw.startsWith("magnet:", true) || raw.startsWith("torrent:", true)
            val isM3u8 = kind == "m3u8" || raw.contains(".m3u8", true)
            val isMpd = kind == "dash" || raw.contains(".mpd", true)
            val isExternal = kind == "external" || kind == "externalurl"
            out += StreamSource(
                name = name,
                url = if (isTorrent || isExternal) raw else Http.normalizeDriveUrl(raw),
                headers = headers,
                subtitles = subs,
                isTorrent = isTorrent,
                isM3u8 = isM3u8,
                isMpd = isMpd,
                externalUrl = isExternal,
                provider = "Vega",
                providerId = config.id,
                providerName = config.name,
            )
        }
        return out
    }

    private fun fail(msg: String): List<StreamSource> {
        streamErrors[config.id] = msg
        lastOutcome[config.id] = msg.take(80)
        return emptyList()
    }

    // ---- helpers ----

    private fun dataObject(payload: String): JSONObject? {
        val o = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        if (!o.optBoolean("ok", false)) return null
        return o.opt("data") as? JSONObject
    }

    private fun dataOf(payload: String): Any? {
        val o = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        if (!o.optBoolean("ok", false)) return null
        return o.opt("data")
    }

    private fun stringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optString(i).takeIf { it.isNotBlank() }?.let { out += it }
        }
        return out
    }

    private fun mediaTypeOf(raw: String): MediaType = when (raw.lowercase()) {
        "series", "tv", "tvseries", "tvshow", "anime" -> MediaType.SERIES
        "movie", "film" -> MediaType.MOVIE
        else -> MediaType.UNKNOWN
    }

    private fun seasonNumberOf(title: String, fallback: Int): Int {
        val m = Regex("(\\d+)").find(title)
        return m?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..200 } ?: fallback
    }

    /**
     * The season an episode title carries itself ("S01 E01", "S01E01",
     * "Season 2", "2x03") — null when the title names no season. Providers
     * that serve a whole show per audio pack title every row this way, so
     * reading it is what groups those rows into real seasons.
     */
    private fun seasonFromEpisodeTitle(title: String?): Int? {
        if (title.isNullOrBlank()) return null
        val t = title.trim()
        Regex("(?i)\\bS(\\d{1,3})\\s?E\\d").find(t)?.groupValues?.getOrNull(1)
            ?.toIntOrNull()?.takeIf { it in 1..200 }?.let { return it }
        Regex("(?i)\\bseason\\s?(\\d{1,3})\\b").find(t)?.groupValues?.getOrNull(1)
            ?.toIntOrNull()?.takeIf { it in 1..200 }?.let { return it }
        Regex("^\\s*(\\d{1,3})\\s?[x×]\\s?\\d+").find(t)?.groupValues?.getOrNull(1)
            ?.toIntOrNull()?.takeIf { it in 1..200 }?.let { return it }
        return null
    }
}

/** One `linkList` entry of a Vega `Info`: either a list of direct links or the
 *  `episodesLink` a separate episodes.js request resolves. `index` is the
 *  entry's position in the provider's list — the key episode answers are filed
 *  under. */
class VegaPack(
    val index: Int,
    val title: String,
    val direct: JSONArray?,
    val episodesLink: String,
    val link: String = "",
)

/** One playable movie option: a quality row inside a pack (`label`) and the
 *  link it resolves (`link`), with the pack's own title for grouping. */
data class VegaMovieOption(
    val label: String,
    val link: String,
    val packTitle: String,
)
