package com.hikari.app.music

import com.hikari.app.data.Logs
import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * YouTube Music backend for the "HIKari music" provider.
 *
 * - Browsing (home / search / albums / artists / playlists / radio) talks to the same public
 *   "InnerTube" web endpoints Echo Music uses, through Hikari's own OkHttp client ([Http]).
 * - Stream addresses come from NewPipeExtractor, which Hikari already ships (so this adds
 *   no new library and almost no APK size).
 * - Every JSON read is defensive: a field that is missing just drops that one item.
 */
object MusicApi {
    private const val TAG = "HikariMusic"
    private const val ORIGIN = "https://music.youtube.com"
    private const val BASE = "$ORIGIN/youtubei/v1/"
    private const val KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
    private const val CLIENT_VERSION = "1.20260213.01.00"

    // The same filter tokens Echo Music uses for YouTube Music search.
    const val FILTER_SONG = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
    const val FILTER_ALBUM = "EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D"
    const val FILTER_ARTIST = "EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D"
    const val FILTER_PLAYLIST = "EgeKAQQoADgBagwQDhAKEAMQBRAJEAQ%3D"
    const val FILTER_VIDEO = "EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D"

    private val TYPE_WORDS = setOf(
        "Song", "Video", "Album", "Single", "EP", "Playlist", "Artist", "Podcast", "Episode", "Profile",
    )
    private val DURATION = Regex("^\\d{1,2}(:\\d{2}){1,2}$")

    // ------------------------------------------------------------------ http

    private fun context(): JSONObject = JSONObject().put(
        "client",
        JSONObject()
            .put("clientName", "WEB_REMIX")
            .put("clientVersion", CLIENT_VERSION)
            .put("hl", "en")
            .put("gl", "US"),
    )

    private suspend fun post(endpoint: String, body: JSONObject, extraQuery: String = ""): JSONObject =
        withContext(Dispatchers.IO) {
            body.put("context", context())
            val url = BASE + endpoint + "?prettyPrint=false&key=" + KEY + extraQuery
            val headers = mapOf(
                "Origin" to ORIGIN,
                "Referer" to "$ORIGIN/",
                "X-Origin" to ORIGIN,
                "X-Goog-Api-Format-Version" to "1",
            )
            Http.request("POST", url, body.toString(), headers).use { reply ->
                val text = reply.body?.string().orEmpty()
                if (!reply.isSuccessful) throw IOException("YouTube Music error " + reply.code)
                JSONObject(text)
            }
        }

    // ------------------------------------------------------------------ json helpers

    private fun JSONObject.o(key: String): JSONObject? = optJSONObject(key)
    private fun JSONObject.a(key: String): JSONArray? = optJSONArray(key)

    private fun JSONArray.objs(): List<JSONObject> {
        val out = ArrayList<JSONObject>(length())
        for (i in 0 until length()) optJSONObject(i)?.let { out.add(it) }
        return out
    }

    private fun JSONObject.runs(key: String): List<String> {
        val arr = o(key)?.a("runs") ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) out.add(arr.optJSONObject(i)?.optString("text").orEmpty())
        return out
    }

    private fun JSONObject.runsText(key: String): String = runs(key).joinToString("")

    /** Depth-first: the first value stored under [key] anywhere below [node]. */
    private fun findValue(node: Any?, key: String): Any? {
        when (node) {
            is JSONObject -> {
                if (node.has(key)) return node.opt(key)
                val iter = node.keys()
                while (iter.hasNext()) {
                    val found = findValue(node.opt(iter.next()), key)
                    if (found != null) return found
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val found = findValue(node.opt(i), key)
                    if (found != null) return found
                }
            }
        }
        return null
    }

    /** Every object stored under [key] anywhere below [node] (does not descend into a match). */
    private fun findAll(node: Any?, key: String, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                val iter = node.keys()
                while (iter.hasNext()) {
                    val k = iter.next()
                    val v = node.opt(k)
                    if (k == key && v is JSONObject) out.add(v) else findAll(v, key, out)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) findAll(node.opt(i), key, out)
        }
    }

    private fun thumbOf(node: Any?): String {
        val arr = findValue(node, "thumbnails") as? JSONArray ?: return ""
        if (arr.length() == 0) return ""
        return arr.optJSONObject(arr.length() - 1)?.optString("url").orEmpty()
    }

    /** Asks the image server for a bigger / smaller square version of a thumbnail. */
    fun hq(url: String, size: Int = 544): String {
        if (url.isBlank()) return url
        return if (url.contains("googleusercontent.com") || url.contains("ggpht.com")) {
            url.replace(Regex("=w\\d+-h\\d+[^&?]*"), "=w$size-h$size-l90-rj")
        } else url
    }

    private fun parseDuration(text: String): Int {
        if (!DURATION.matches(text)) return 0
        var total = 0
        for (p in text.split(":")) total = total * 60 + (p.toIntOrNull() ?: 0)
        return total
    }

    private fun cleanParts(parts: List<String>): List<String> =
        parts.map { it.trim() }.filter { it.isNotEmpty() && it != "•" && it != "·" }

    // ------------------------------------------------------------------ item parsing

    private fun kindOf(pageType: String): MKind? = when {
        pageType.contains("ALBUM") -> MKind.ALBUM
        pageType.contains("ARTIST") || pageType.contains("USER_CHANNEL") -> MKind.ARTIST
        pageType.contains("PLAYLIST") -> MKind.PLAYLIST
        else -> null
    }

    private fun pageTypeOf(browse: JSONObject?): String =
        browse?.o("browseEndpointContextSupportedConfigs")
            ?.o("browseEndpointContextMusicConfig")
            ?.optString("pageType").orEmpty()

    private fun songFrom(id: String, title: String, parts: List<String>, thumb: String, durationHint: Int): MSong {
        var p = parts
        if (p.isNotEmpty() && p[0] in TYPE_WORDS) p = p.drop(1)
        var duration = durationHint
        val rest = ArrayList<String>()
        for (x in p) {
            if (DURATION.matches(x)) duration = parseDuration(x) else rest.add(x)
        }
        val artist = rest.getOrNull(0).orEmpty()
        val album = rest.getOrNull(1).orEmpty().let { if (it.endsWith("plays") || it.endsWith("views")) "" else it }
        return MSong(id, title, artist, album, duration, thumb)
    }

    private fun parseListItem(r: JSONObject): MItem? {
        val cols = r.a("flexColumns")?.objs().orEmpty()
        fun colRuns(i: Int): List<String> {
            val arr = cols.getOrNull(i)?.o("musicResponsiveListItemFlexColumnRenderer")
                ?.o("text")?.a("runs") ?: return emptyList()
            val out = ArrayList<String>()
            for (k in 0 until arr.length()) out.add(arr.optJSONObject(k)?.optString("text").orEmpty())
            return out
        }
        val title = colRuns(0).joinToString("").trim()
        if (title.isEmpty()) return null
        val parts = cleanParts(colRuns(1) + colRuns(2))
        val thumb = thumbOf(r.o("thumbnail"))
        val fixed = r.a("fixedColumns")?.objs()?.firstOrNull()
            ?.o("musicResponsiveListItemFixedColumnRenderer")?.runsText("text").orEmpty().trim()
        val ownBrowse = r.o("navigationEndpoint")?.o("browseEndpoint")
        val watchId = r.o("navigationEndpoint")?.o("watchEndpoint")?.optString("videoId").orEmpty()
        var videoId = r.o("playlistItemData")?.optString("videoId").orEmpty().ifBlank { watchId }
            ?: ""
        // Only fall back to a deep search when this row is not an album / artist / playlist link,
        // otherwise a "play" button inside it would turn an album into a fake song.
        if (videoId.isBlank() && ownBrowse == null) videoId = (findValue(r, "videoId") as? String).orEmpty()
        if (videoId.isNotBlank()) {
            val song = songFrom(videoId, title, parts, thumb, parseDuration(fixed))
            val sub = cleanParts(listOf(song.artist, song.album)).joinToString(" • ")
            return MItem(MKind.SONG, videoId, title, sub, thumb, song)
        }
        val browse = ownBrowse ?: return null
        val id = browse.optString("browseId")
        val kind = kindOf(pageTypeOf(browse)) ?: return null
        if (id.isBlank()) return null
        val p = if (parts.isNotEmpty() && parts[0] in TYPE_WORDS && parts.size > 1) parts.drop(1) else parts
        return MItem(kind, id, title, p.joinToString(" • "), thumb)
    }

    private fun parseTwoRow(r: JSONObject): MItem? {
        val title = r.runsText("title").trim()
        if (title.isEmpty()) return null
        val parts = cleanParts(r.runs("subtitle"))
        val thumb = thumbOf(r.o("thumbnailRenderer"))
        val nav = r.o("navigationEndpoint")
        val videoId = nav?.o("watchEndpoint")?.optString("videoId").orEmpty()
        if (videoId.isNotBlank()) {
            val song = songFrom(videoId, title, parts, thumb, 0)
            val sub = cleanParts(listOf(song.artist, song.album)).joinToString(" • ")
            return MItem(MKind.SONG, videoId, title, sub, thumb, song)
        }
        val browse = nav?.o("browseEndpoint")
        if (browse != null) {
            val id = browse.optString("browseId")
            val kind = kindOf(pageTypeOf(browse)) ?: return null
            if (id.isBlank()) return null
            val p = if (parts.isNotEmpty() && parts[0] in TYPE_WORDS && parts.size > 1) parts.drop(1) else parts
            return MItem(kind, id, title, p.joinToString(" • "), thumb)
        }
        val playlistId = nav?.o("watchPlaylistEndpoint")?.optString("playlistId").orEmpty()
        if (playlistId.isNotBlank()) {
            return MItem(MKind.PLAYLIST, "VL$playlistId", title, parts.joinToString(" • "), thumb)
        }
        return null
    }

    private fun parseShelf(kind: String, v: JSONObject): MShelf? {
        val title = when (kind) {
            "musicCarouselShelfRenderer" ->
                v.o("header")?.o("musicCarouselShelfBasicHeaderRenderer")?.runsText("title").orEmpty()
            "musicShelfRenderer" -> v.runsText("title")
            "gridRenderer" -> v.o("header")?.o("gridHeaderRenderer")?.runsText("title").orEmpty()
            else -> ""
        }.trim()
        val items = ArrayList<MItem>()
        var asList = false
        // A grid (New releases, "see all" pages) keeps its cards under "items", the others under "contents".
        val entries = (if (kind == "gridRenderer") v.a("items") else v.a("contents"))?.objs().orEmpty()
        for (c in entries) {
            val list = c.o("musicResponsiveListItemRenderer")?.let { parseListItem(it) }
            if (list != null) {
                items.add(list)
                asList = true
            }
            val card = c.o("musicTwoRowItemRenderer")?.let { parseTwoRow(it) }
            if (card != null) items.add(card)
        }
        if (items.isEmpty()) return null
        return MShelf(title, items, asList)
    }

    private fun parseShelves(root: JSONObject): List<MShelf> {
        val out = ArrayList<MShelf>()
        fun walk(n: Any?) {
            when (n) {
                is JSONObject -> {
                    val iter = n.keys()
                    while (iter.hasNext()) {
                        val k = iter.next()
                        val v = n.opt(k)
                        if (v is JSONObject &&
                            (k == "musicShelfRenderer" || k == "musicPlaylistShelfRenderer" ||
                                k == "musicCarouselShelfRenderer" || k == "gridRenderer")
                        ) {
                            val shelf = parseShelf(k, v)
                            if (shelf != null) out.add(shelf)
                        } else walk(v)
                    }
                }
                is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
            }
        }
        walk(root)
        return out
    }

    private fun continuationOf(root: JSONObject): String? {
        // Classic responses: nextContinuationData. Newer ones: a continuationItemRenderer / continuationCommand.
        val next = findValue(root, "nextContinuationData") as? JSONObject
        next?.optString("continuation")?.ifBlank { null }?.let { return it }
        val cmd = findValue(root, "continuationCommand") as? JSONObject
        return cmd?.optString("token")?.ifBlank { null }
    }

    // ------------------------------------------------------------------ public api

    suspend fun home(params: String? = null): MHome {
        val body = JSONObject().put("browseId", "FEmusic_home")
        if (!params.isNullOrBlank()) body.put("params", params)
        val root = post("browse", body)
        val chips = ArrayList<MChip>()
        val found = ArrayList<JSONObject>()
        findAll(root, "chipCloudChipRenderer", found)
        for (c in found) {
            val title = c.runsText("text").ifBlank { c.o("text")?.optString("simpleText").orEmpty() }.trim()
            val p = (findValue(c.o("navigationEndpoint"), "params") as? String).orEmpty()
            if (title.isNotEmpty() && p.isNotEmpty()) chips.add(MChip(title, p))
        }
        return MHome(chips, parseShelves(root), continuationOf(root))
    }

    suspend fun homeMore(token: String): Pair<List<MShelf>, String?> {
        val enc = URLEncoder.encode(token, "UTF-8")
        val first = runCatching { post("browse", JSONObject(), "&ctoken=$enc&continuation=$enc&type=next") }
        val firstRoot = first.getOrNull()
        var shelves = firstRoot?.let { parseShelves(it) }.orEmpty()
        var next = firstRoot?.let { continuationOf(it) }
        if (shelves.isEmpty()) {
            // Some responses want the token in the request body instead of the address.
            val alt = runCatching { post("browse", JSONObject().put("continuation", token)) }
            val altRoot = alt.getOrNull()
            if (altRoot != null) {
                shelves = parseShelves(altRoot)
                next = continuationOf(altRoot) ?: next
            } else if (first.isFailure) {
                throw first.exceptionOrNull() ?: IOException("YouTube Music error")
            }
        }
        return Pair(shelves, next)
    }

    /**
     * Any YouTube Music browse page (charts, explore, new releases...) read as shelves.
     * Used to keep the Home feed filling up while the person scrolls.
     */
    suspend fun browseShelves(browseId: String, params: String? = null): Pair<List<MShelf>, String?> {
        val body = JSONObject().put("browseId", browseId)
        if (!params.isNullOrBlank()) body.put("params", params)
        val root = post("browse", body)
        return Pair(parseShelves(root), continuationOf(root))
    }

    /**
     * [filter] = one of the FILTER_* tokens, or null for the "All" tab.
     * The "All" tab shows everything (songs, albums, artists, playlists) in one scrolling page.
     */
    suspend fun search(query: String, filter: String?): List<MShelf> =
        if (filter == null) searchAll(query) else searchOne(query, filter)

    private suspend fun searchOne(query: String, filter: String?): List<MShelf> {
        val body = JSONObject().put("query", query)
        if (filter != null) body.put("params", filter)
        return parseShelves(post("search", body))
    }

    /**
     * "All": the plain (unfiltered) search is often empty / partial, so the four typed searches
     * run in parallel and are merged into Songs, Albums, Artists and Playlists sections.
     * Whatever extra the plain search found (top result, videos...) is added after them.
     */
    private suspend fun searchAll(query: String): List<MShelf> = coroutineScope {
        val groups = listOf(
            "Songs" to FILTER_SONG,
            "Albums" to FILTER_ALBUM,
            "Artists" to FILTER_ARTIST,
            "Playlists" to FILTER_PLAYLIST,
            "Videos" to FILTER_VIDEO,
        )
        val mixed = async { runCatching { searchOne(query, null) } }
        val typed = groups.map { (title, filter) ->
            title to async { runCatching { searchOne(query, filter) } }
        }
        val out = ArrayList<MShelf>()
        val seen = HashSet<String>()
        var firstError: Throwable? = null
        for ((title, job) in typed) {
            val r = job.await()
            if (r.isFailure && firstError == null) firstError = r.exceptionOrNull()
            val items = r.getOrNull().orEmpty()
                .flatMap { it.items }
                .filter { seen.add(it.kind.name + ":" + it.id) }
            if (items.isNotEmpty()) out.add(MShelf(title, items, items.all { it.kind == MKind.SONG }))
        }
        val m = mixed.await()
        if (m.isFailure && firstError == null) firstError = m.exceptionOrNull()
        for (shelf in m.getOrNull().orEmpty()) {
            val items = shelf.items.filter { seen.add(it.kind.name + ":" + it.id) }
            if (items.isNotEmpty()) out.add(MShelf(shelf.title, items, shelf.asList))
        }
        // Only report an error when nothing at all came back.
        if (out.isEmpty()) firstError?.let { throw it }
        out
    }

    suspend fun suggestions(query: String): List<String> {
        val root = post("music/get_search_suggestions", JSONObject().put("input", query))
        val found = ArrayList<JSONObject>()
        findAll(root, "searchSuggestionRenderer", found)
        return found.map { it.runsText("suggestion").trim() }.filter { it.isNotEmpty() }.distinct()
    }

    /** An album, playlist or artist page. */
    suspend fun detail(browseId: String, fallbackTitle: String, fallbackThumb: String): MDetail {
        val root = post("browse", JSONObject().put("browseId", browseId))
        var header: JSONObject? = null
        for (key in listOf(
            "musicResponsiveHeaderRenderer", "musicImmersiveHeaderRenderer",
            "musicVisualHeaderRenderer", "musicDetailHeaderRenderer",
        )) {
            val list = ArrayList<JSONObject>()
            findAll(root, key, list)
            if (list.isNotEmpty()) {
                header = list[0]
                break
            }
        }
        val title = header?.runsText("title")?.trim().orEmpty().ifBlank { fallbackTitle }
        val headerParts = cleanParts(
            (header?.runs("straplineTextOne") ?: emptyList()) + (header?.runs("subtitle") ?: emptyList()),
        )
        val subtitle = headerParts.joinToString(" • ")
        val thumb = (if (header != null) thumbOf(header) else "").ifBlank { fallbackThumb }
        val artistGuess = headerParts.firstOrNull { it !in TYPE_WORDS }.orEmpty()

        val shelves = parseShelves(root)
        val songs = ArrayList<MSong>()
        val rest = ArrayList<MShelf>()
        var songsTaken = false
        for (s in shelves) {
            val allSongs = s.items.isNotEmpty() && s.items.all { it.kind == MKind.SONG }
            if (!songsTaken && s.asList && allSongs) {
                songsTaken = true
                for (entry in s.items) {
                    val raw = entry.song ?: continue
                    val artist = if (raw.artist.isBlank() || raw.artist.endsWith("plays") || raw.artist.endsWith("views")) {
                        artistGuess
                    } else raw.artist
                    songs.add(
                        raw.copy(
                            artist = artist,
                            thumb = raw.thumb.ifBlank { thumb },
                            album = if (browseId.startsWith("MPRE") && raw.album.isBlank()) title else raw.album,
                        ),
                    )
                }
            } else rest.add(s)
        }
        return MDetail(title, subtitle, thumb, songs, rest)
    }

    /** "Up next" songs for a seed track (YouTube Music radio). The seed itself is not included. */
    suspend fun radio(videoId: String): List<MSong> {
        val body = JSONObject()
            .put("videoId", videoId)
            .put("playlistId", "RDAMVM$videoId")
            .put("params", "wAEB")
            .put("isAudioOnly", true)
        val root = post("next", body)
        val found = ArrayList<JSONObject>()
        findAll(root, "playlistPanelVideoRenderer", found)
        val out = ArrayList<MSong>()
        for (r in found) {
            val id = r.optString("videoId")
            if (id.isBlank() || id == videoId) continue
            val title = r.runsText("title").trim()
            if (title.isEmpty()) continue
            val parts = cleanParts(r.runs("longBylineText"))
            val duration = parseDuration(r.runs("lengthText").joinToString("").trim())
            out.add(songFrom(id, title, parts, thumbOf(r.o("thumbnail")), duration))
        }
        return out.distinctBy { it.id }
    }

    // ------------------------------------------------------------------ lyrics (LRCLIB)

    suspend fun lyrics(song: MSong): MLyrics? = withContext(Dispatchers.IO) {
        // VxMusic-style: exact /api/get first (with then without duration), then /api/search fallback.
        fun parseLrclib(text: String?): MLyrics? {
            if (text.isNullOrBlank()) return null
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
            // /api/search returns an array; take first usable record.
            val obj = if (json.has("plainLyrics") || json.has("syncedLyrics")) json else {
                val arr = json.optJSONArray("result") ?: json.optJSONArray("data") ?: return null
                if (arr.length() == 0) return null
                arr.optJSONObject(0) ?: return null
            }
            val plain = obj.optString("plainLyrics")
            val synced = ArrayList<MLine>()
            val lrc = obj.optString("syncedLyrics")
            val re = Regex("^\\[(\\d+):(\\d+(?:\\.\\d+)?)](.*)$")
            for (line in lrc.lines()) {
                val m = re.find(line.trim()) ?: continue
                val min = m.groupValues[1].toLongOrNull() ?: continue
                val sec = m.groupValues[2].toDoubleOrNull() ?: continue
                synced.add(MLine(min * 60_000L + (sec * 1000).toLong(), m.groupValues[3].trim()))
            }
            if (synced.isEmpty() && plain.isBlank()) return null
            return MLyrics(synced, plain)
        }
        val base = "https://lrclib.net/api/get?track_name=" + URLEncoder.encode(song.title, "UTF-8") +
            "&artist_name=" + URLEncoder.encode(song.artist, "UTF-8")
        if (song.durationSec > 0) {
            parseLrclib(Http.getString(base + "&duration=" + song.durationSec))?.let { return@withContext it }
        }
        parseLrclib(Http.getString(base))?.let { return@withContext it }
        val searchUrl = "https://lrclib.net/api/search?q=" + URLEncoder.encode((song.artist + " " + song.title).trim(), "UTF-8")
        parseLrclib(Http.getString(searchUrl))
    }

    // ------------------------------------------------------------------ streams (YouTube / NewPipeExtractor)

    private class Resolved(val url: String, val expires: Long)

    private val streamCache = ConcurrentHashMap<String, Resolved>()

    /**
     * How many times a song's address has failed to play since it last played (0 = first try).
     * Every retry of the same song goes one step further down the chain (see [resolveTelevision]),
     * instead of being handed the very same address again.
     */
    private val attempts = ConcurrentHashMap<String, Int>()

    /**
     * The YouTube client (the `c=` of the address: ANDROID_VR, IOS ...) whose address for a song just
     * failed to play. The next lookup of that song skips that client's quick request, so a retry gets
     * an address of a DIFFERENT kind instead of the same kind of address that just failed.
     */
    private val badClient = ConcurrentHashMap<String, String>()

    private val clientParam = Regex("[?&]c=([A-Za-z_]+)")

    private fun clientOf(url: String): String? = clientParam.find(url)?.groupValues?.get(1)

    /** The User-Agent each address was made for (a stream address belongs to the client that asked for it). */
    private val uaByUrl = ConcurrentHashMap<String, String>()

    @Volatile
    private var newPipeState = 0
    private val initLock = Any()

    private object NpDownloader : org.schabi.newpipe.extractor.downloader.Downloader() {
        override fun execute(
            request: org.schabi.newpipe.extractor.downloader.Request,
        ): org.schabi.newpipe.extractor.downloader.Response {
            val url = request.url()
            val headers = LinkedHashMap<String, String>()
            var contentType = "application/json"
            for ((name, values) in request.headers()) {
                val value = values.lastOrNull() ?: continue
                if (name.equals("Content-Type", ignoreCase = true)) contentType = value
                headers[name] = value
            }
            val body = request.dataToSend()?.let { String(it, Charsets.UTF_8) }
            Http.request(request.httpMethod(), url, body, headers, contentType).use { reply ->
                val text = runCatching { reply.body?.string() ?: "" }.getOrDefault("")
                if (reply.code == 429) {
                    throw org.schabi.newpipe.extractor.exceptions.ReCaptchaException(
                        "reCaptcha Challenge requested",
                        url,
                    )
                }
                return org.schabi.newpipe.extractor.downloader.Response(
                    reply.code,
                    reply.message,
                    reply.headers.toMultimap(),
                    text,
                    reply.request.url.toString(),
                )
            }
        }
    }

    private fun ensureNewPipe(): Boolean {
        if (newPipeState != 0) return newPipeState == 1
        return synchronized(initLock) {
            if (newPipeState != 0) return@synchronized newPipeState == 1
            val ok = runCatching { org.schabi.newpipe.extractor.NewPipe.init(NpDownloader) }.isSuccess
            newPipeState = if (ok) 1 else 2
            ok
        }
    }

    /**
     * Songs whose Vx lossless / JioSaavn address already failed to play (PHONE). The retry skips
     * those two and goes straight to YouTube, otherwise it would be handed the very same address
     * again and the song would sit on "Loading" forever.
     */
    private val skipPremium = ConcurrentHashMap.newKeySet<String>()

    /** The player gave up on this address (error or stall): the next ask for the song starts one step further on. */
    fun invalidate(id: String) {
        streamCache[id]?.url?.let { u -> clientOf(u)?.let { badClient[id] = it } }
        streamCache.remove(id)
        skipPremium.add(id)
        attempts[id] = (attempts[id] ?: 0) + 1
    }

    /** The song reached "playing": its failure count starts over. */
    fun markPlayed(id: String) {
        attempts.remove(id)
    }

    /** The person picked this song (or pressed Play on it after a failure): start from the first step again. */
    fun resetAttempts(id: String) {
        attempts.remove(id)
        badClient.remove(id)
    }

    private class QuickClient(
        val name: String,
        val numeric: String,
        val version: String,
        val ua: String,
        /** True: the request says it comes from a page that embeds the video (needs `thirdParty`). */
        val embed: Boolean = false,
        val extra: JSONObject.() -> Unit,
    )

    /**
     * YouTube's "visitor" id. Every answer carries one, even a "Sign in to confirm you're not a bot"
     * answer. Asking again with it is what makes YouTube treat the second request as coming from the
     * same ordinary visitor instead of a nameless one, which is what clears that bot check on many
     * networks. Shared by every quick request.
     */
    @Volatile
    private var visitorData: String? = null

    private val quickClients: List<QuickClient> by lazy {
        listOf(
            QuickClient(
                "ANDROID_VR", "28", "1.61.48",
                "com.google.android.apps.youtube.vr.oculus/1.61.48 " +
                    "(Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            ) {
                put("deviceMake", "Oculus"); put("deviceModel", "Quest 3")
                put("androidSdkVersion", 32); put("osName", "Android"); put("osVersion", "12L")
            },
            QuickClient(
                "ANDROID_VR", "28", "1.65.10",
                "com.google.android.apps.youtube.vr.oculus/1.65.10 " +
                    "(Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            ) {
                put("deviceMake", "Oculus"); put("deviceModel", "Quest 3")
                put("androidSdkVersion", 32); put("osName", "Android"); put("osVersion", "12L")
            },
            QuickClient(
                "ANDROID_VR", "28", "1.71.26",
                "com.google.android.apps.youtube.vr.oculus/1.71.26 " +
                    "(Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            ) {
                put("deviceMake", "Oculus"); put("deviceModel", "Quest 3")
                put("androidSdkVersion", 32); put("osName", "Android"); put("osVersion", "12L")
            },
            QuickClient(
                "IOS", "5", "20.10.4",
                "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            ) {
                put("deviceMake", "Apple"); put("deviceModel", "iPhone16,2")
                put("osName", "iPhone"); put("osVersion", "18.3.2.22D82")
            },
            QuickClient(
                "IOS", "5", "21.02.3",
                "com.google.ios.youtube/21.02.3 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            ) {
                put("deviceMake", "Apple"); put("deviceModel", "iPhone16,2")
                put("osName", "iPhone"); put("osVersion", "18.3.2.22D82")
            },
            // One more client that does not need a proof-of-origin token. When it gives no plain
            // address the log says so and nothing else changes.
            QuickClient(
                "TVHTML5_SIMPLY_EMBEDDED_PLAYER", "85", "2.0",
                "Mozilla/5.0 (PlayStation; PlayStation 4/12.00) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.4 Safari/605.1.15",
                true,
            ) {
                put("clientScreen", "EMBED")
            },
        )
    }

    /**
     * One plain request to YouTube's player endpoint for ONE client, which hands back direct audio
     * addresses with nothing to decode (NewPipe has to download and run YouTube's player script for
     * the same job, and a television's slow processor can spend minutes on that).
     * Returns null whenever anything is off; the reason is written to the log.
     */
    private fun quickAudioUrlWith(
        id: String,
        c: QuickClient,
        visitor: String? = visitorData,
        retried: Boolean = false,
    ): String? {
        val client = JSONObject()
            .put("clientName", c.name)
            .put("clientVersion", c.version)
            .put("userAgent", c.ua)
            .put("hl", "en")
            .put("gl", "US")
            .put("timeZone", "UTC")
            .put("utcOffsetMinutes", 0)
        if (!visitor.isNullOrBlank()) client.put("visitorData", visitor)
        c.extra(client)
        val context = JSONObject().put("client", client)
        if (c.embed) {
            context.put("thirdParty", JSONObject().put("embedUrl", "https://www.youtube.com/watch?v=" + id))
        }
        val body = JSONObject()
            .put("context", context)
            .put("videoId", id)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
            .put(
                "playbackContext",
                JSONObject().put(
                    "contentPlaybackContext",
                    JSONObject().put("html5Preference", "HTML5_PREF_WANTS"),
                ),
            )
        val headers = HashMap<String, String>()
        headers["User-Agent"] = c.ua
        headers["X-YouTube-Client-Name"] = c.numeric
        headers["X-YouTube-Client-Version"] = c.version
        headers["Origin"] = "https://www.youtube.com"
        if (!visitor.isNullOrBlank()) headers["X-Goog-Visitor-Id"] = visitor
        val tag = c.name + " " + c.version
        val text = Http.request(
            "POST",
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            body.toString(),
            headers,
        ).use { reply ->
            if (!reply.isSuccessful) {
                Logs.log(TAG, "quick $tag: HTTP " + reply.code + " for " + id)
                return null
            }
            reply.body?.string().orEmpty()
        }
        val root = JSONObject(text)
        // Remember the visitor id YouTube hands out (the first one is kept).
        val issued = root.o("responseContext")?.optString("visitorData").orEmpty()
        if (issued.isNotBlank() && visitorData == null) visitorData = issued
        val status = root.o("playabilityStatus")?.optString("status").orEmpty()
        if (status != "OK") {
            Logs.log(
                TAG,
                "quick $tag: " + status + " " + root.o("playabilityStatus")?.optString("reason").orEmpty() + " for " + id,
            )
            // "Sign in to confirm you're not a bot" often goes away when the same request is sent
            // again with the visitor id YouTube just gave out.
            val known = visitorData
            if (status == "LOGIN_REQUIRED" && !retried && !known.isNullOrBlank() && known != visitor) {
                Logs.log(TAG, "quick $tag: asking again with the visitor id for $id")
                return quickAudioUrlWith(id, c, known, true)
            }
            return null
        }
        val formats = root.o("streamingData")?.a("adaptiveFormats")
        if (formats == null) {
            Logs.log(TAG, "quick $tag: no adaptive formats for $id")
            return null
        }
        // AAC (audio/mp4) only: every television can decode it, which is not true of Opus.
        var best: String? = null
        var bestRate = -1
        for (f in formats.objs()) {
            if (!f.optString("mimeType").startsWith("audio/mp4")) continue
            val u = f.optString("url")
            if (u.isBlank()) continue
            val rate = f.optInt("bitrate")
            if (rate > bestRate) {
                bestRate = rate
                best = u
            }
        }
        if (best == null) Logs.log(TAG, "quick $tag: no plain AAC address for $id")
        return best
    }

    /**
     * How one ranged request for a YouTube audio address is sent.
     * [post]: the way the YouTube apps and NewPipe send it (POST with a two-byte body: the letter x and a zero byte);
     * otherwise a plain GET. [param]: the byte range goes into the address (`range=a-b`); otherwise it
     * goes into the `Range` header.
     */
    class StreamForm(val post: Boolean, val param: Boolean)

    private val defaultForm = StreamForm(false, true)

    /** The request form that was seen to work PAST the first megabyte for an address (see [streamsPastFirstMegabyte]). */
    private val formByUrl = ConcurrentHashMap<String, StreamForm>()

    /** The request form to start with for [url]: the one that was proven to work, else a plain GET with `range=`. */
    fun formFor(url: String): StreamForm = formByUrl[url] ?: defaultForm

    /** Every way of asking for a range, in the order they are tried. */
    private val probeForms = listOf(
        StreamForm(false, true),
        StreamForm(false, false),
        StreamForm(true, false),
        StreamForm(true, true),
    )

    private fun formName(f: StreamForm): String =
        (if (f.post) "POST" else "GET") + " with " + (if (f.param) "range parameter" else "Range header")

    /** The two bytes the YouTube apps (and NewPipe) send as the body of every stream request. */
    private const val POST_BODY_TEXT = "x\u0000"

    /** Reads the first two bytes of [url]: true only when the server really serves it (GET first, then POST). */
    private fun probeUrl(url: String, ua: String): Boolean {
        val viaGet = try {
            Http.request(
                "GET", url, null,
                mapOf("User-Agent" to ua, "Range" to "bytes=0-1"),
            ).use { it.code in 200..299 }
        } catch (e: Exception) {
            false
        }
        if (viaGet) return true
        return codeOfRead(url, ua, StreamForm(true, true), 0L) in 200..299
    }

    private val contentLengthInUrl = Regex("[?&]clen=(\\d+)")

    /** YouTube answers the first megabyte of an address that needs a proof-of-origin token and refuses the rest. */
    private const val FIRST_MEGABYTE = 1_048_576L

    /** The answer code of one 2-byte read at [from], asked the way [form] says; -1 when the request itself failed (no network, timeout). */
    private fun codeOfRead(url: String, ua: String, form: StreamForm, from: Long): Int = try {
        val headers = HashMap<String, String>()
        headers["User-Agent"] = ua
        val last = from + 1L
        val target = if (form.param) {
            url + "&range=" + from + "-" + last + "&rn=1"
        } else {
            headers["Range"] = "bytes=" + from + "-" + last
            url + "&rn=1"
        }
        if (form.post) {
            Http.request("POST", target, POST_BODY_TEXT, headers, "application/x-www-form-urlencoded").use { it.code }
        } else {
            Http.request("GET", target, null, headers).use { it.code }
        }
    } catch (e: Exception) {
        -1
    }

    /**
     * False when [url] serves the first megabyte but REFUSES everything after it. That is the "song
     * stops at 1:04" address: 1 MB of 128 kbit/s AAC is 1:04. A probe of the first two bytes cannot
     * tell, so this reads two bytes just after the first megabyte, in every way the player can ask
     * for a range (GET and POST, `range=` parameter and `Range` header). The way that worked is
     * remembered for this address ([formFor]) so the player asks the same way. Every YouTube client,
     * ANDROID_VR included, has to pass this test: none of them is trusted blindly any more.
     * A network error says nothing about the address, so it counts as "fine".
     */
    private fun streamsPastFirstMegabyte(url: String, ua: String): Boolean {
        val size = contentLengthInUrl.find(url)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
        val from = FIRST_MEGABYTE
        // The whole file is inside the first megabyte (a short track): nothing to check.
        if (size in 1L..(from + 2L)) return true
        var networkError = false
        val codes = StringBuilder()
        for (form in probeForms) {
            val code = codeOfRead(url, ua, form, from)
            codes.append(code).append(' ')
            if (code in 200..299 || code == 416) {
                if (formByUrl.size > 300) formByUrl.clear()
                formByUrl[url] = form
                if (form.post || !form.param) {
                    Logs.log(TAG, "address serves past the first megabyte only as " + formName(form))
                }
                return true
            }
            if (code < 0) networkError = true
        }
        Logs.log(TAG, "address refuses everything after the first megabyte (HTTP " + codes.toString().trim() + ")")
        return networkError
    }

    /** The User-Agent a stream address expects (it depends on which YouTube client made it). */
    fun userAgentFor(url: String): String {
        uaByUrl[url]?.let { return it }
        return when {
            url.contains("c=IOS") ->
                "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"
            url.contains("c=ANDROID_VR") ->
                "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1; Cronet/132.0.6808.3)"
            url.contains("c=ANDROID") -> "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip"
            else -> Http.UA
        }
    }

    /** Last Vx lossless stream used (for the quality badge). Null = YouTube/Jio fallback. */
    @Volatile
    var lastVx: VxLosslessApi.VxStream? = null
    @Volatile
    var lastSource: String = "YouTube"

    /** Changing quality must re-resolve; cached YouTube URLs would otherwise stick. */
    fun clearStreamCache() {
        streamCache.clear()
        formByUrl.clear()
        VxLosslessApi.resetProbe()
        skipPremium.clear()
        attempts.clear()
        badClient.clear()
        uaByUrl.clear()
        lastVx = null
        lastSource = "YouTube"
    }

    /**
     * BLOCKING: returns the audio address for [id]. Called by the player's data source on its
     * own loading thread, never on the UI thread.
     */
    fun resolveStreamBlocking(id: String): String = resolveStreamBlocking(id, null)

    /**
     * Television: [resolveTelevision] (YouTube only, every way at once, with an exact-match
     * JioSaavn address as the very last resort).
     * Phone: unchanged. VxMusic port, tries lossless FLAC first when the user enabled it and we know
     * title/artist (strict match rejects remix/slowed variants -> YouTube fallback keeps them playable),
     * then JioSaavn 320k, then YouTube through NewPipe.
     */
    fun resolveStreamBlocking(id: String, song: MSong?): String {
        val now = System.currentTimeMillis()
        streamCache[id]?.let { if (it.expires > now) return it.url }
        if (com.hikari.app.tv.TvMode.isTv) return resolveTelevision(id, song, now)
        // After a failed play the retry skips Vx lossless / JioSaavn (it would get the same address back).
        val premium = id !in skipPremium
        // Lossless-first (needs metadata; without it we cannot safely match Qobuz).
        if (premium && song != null && song.title.isNotBlank() && song.artist.isNotBlank()) {
            val app = com.hikari.app.HikariApp.instance.applicationContext
            if (VxQuality.preferLossless(app)) {
                val q = VxQuality.streamQuality(app)
                if (q != VxQuality.YOUTUBE) {
                    val vx = runCatching {
                        VxLosslessApi.resolveStreamBlocking(
                            song.title, song.artist,
                            song.durationSec.takeIf { it > 0 }, song.album.takeIf { it.isNotBlank() }, q,
                        )
                    }.getOrNull()
                    if (vx != null) {
                        val flac = vx.mimeType.contains("flac", ignoreCase = true)
                        lastVx = if (flac) vx else null
                        streamCache[id] = Resolved(vx.url, now + 5L * 60 * 60 * 1000)
                        lastSource = if (flac) "FLAC " + vx.bitDepth + "-bit/" + vx.samplingRate + "kHz"
                            else "Qobuz MP3 320k"
                        Logs.log(TAG, "vx lossless ready for " + id + " (" + vx.bitDepth + "/" + vx.samplingRate + " FLAC q=" + vx.formatId + ")")
                        return vx.url
                    }
                }
            }
        }
        // JioSaavn 320k (VxMusic-style): only for an exact title / artist / length match (see VxJioSaavnApi).
        if (premium && song != null && song.title.isNotBlank()) {
            jioFor(id, song, now)?.let { return it }
        }
        lastVx = null
        lastSource = "YouTube"
        return resolveLegacyBlocking(id)
    }

    private fun jioFor(id: String, song: MSong, now: Long): String? {
        val jio = runCatching {
            VxJioSaavnApi.resolveBlocking(song.title, song.artist, song.durationSec.takeIf { it > 0 })
        }.getOrNull()
        if (jio == null || jio.url.isBlank()) return null
        lastVx = null
        streamCache[id] = Resolved(jio.url, now + 5L * 60 * 60 * 1000)
        lastSource = "JioSaavn 320k"
        Logs.log(TAG, "jio 320k ready for " + id)
        return jio.url
    }

    // ------------------------------------------------------------------ television

    /** Hard limit for the whole television lookup, so a bad network fails fast instead of hanging on "Loading". */
    private const val TV_LOOKUP_MS = 30_000L

    /** How long the quick requests get to themselves before the slow NewPipe run starts alongside them. */
    private const val NEWPIPE_HEAD_START_MS = 2_500L

    private class Hit(
        val url: String?,
        val ua: String,
        val label: String,
        /** The YouTube client that made the address (the `name` of its [QuickClient]). */
        val client: String = "",
        /** False: the address only serves the first megabyte (about 1:04 of the song). */
        val whole: Boolean = true,
    )

    /** A limited address is only used when nothing else exists: how long to wait for a better one. */
    private const val LIMITED_GRACE_MS = 18_000L

    /** An address from the client that failed last time: how long to wait for a different client's address. */
    private const val SOFT_GRACE_MS = 3_000L

    /**
     * Television lookup. Every YouTube client asks at the SAME time (one failing or slow client no
     * longer delays the others) and NewPipe starts alongside them after a short head start, so the
     * first address that really works wins. A retry of the same song starts NewPipe at once.
     * The old code switched the quick requests off for good after the first failed play, which left
     * only the (very slow on a television) NewPipe run for every retry.
     * Only when YouTube gives nothing at all is an exact title / artist / length JioSaavn address
     * tried; it can never be a different song, a non-match just returns nothing.
     */
    private fun resolveTelevision(id: String, song: MSong?, now: Long): String {
        val attempt = attempts[id] ?: 0
        val hit = raceYoutube(id, attempt)
        if (hit != null && hit.url != null && hit.whole) return acceptHit(id, hit, hit.url, now, attempt)
        if (hit != null && hit.url != null) {
            Logs.log(TAG, "tv: YouTube only gave an address that stops after the first minute for " + id + " (try " + (attempt + 1) + ")")
        } else {
            Logs.log(TAG, "tv: YouTube gave no stream for " + id + " (try " + (attempt + 1) + ")")
        }
        if (song != null && song.title.isNotBlank()) {
            jioFor(id, song, now)?.let { return it }
        }
        // Last resort: an address that plays the first minute only. Better than nothing at all.
        if (hit != null && hit.url != null) return acceptHit(id, hit, hit.url, now, attempt)
        throw IOException("No audio stream for this song")
    }

    private fun acceptHit(id: String, hit: Hit, url: String, now: Long, attempt: Int): String {
        if (uaByUrl.size > 300) uaByUrl.clear()
        uaByUrl[url] = hit.ua
        lastVx = null
        lastSource = "YouTube"
        // An address that stops after the first megabyte is not kept for long, so the next lookup
        // of the song goes looking for a better one again.
        val keepMs = if (hit.whole) 5L * 60 * 60 * 1000 else 20_000L
        streamCache[id] = Resolved(url, now + keepMs)
        Logs.log(
            TAG,
            "tv stream ready for " + id + " from " + hit.label + " in " + (System.currentTimeMillis() - now) + "ms (try " + (attempt + 1) + ")" +
                (if (hit.whole) "" else ", first minute only"),
        )
        return url
    }

    private fun raceYoutube(id: String, attempt: Int): Hit? {
        val results = java.util.concurrent.LinkedBlockingQueue<Hit>()
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val quickDone = java.util.concurrent.atomic.AtomicInteger(0)
        // The client whose address failed last time. It still asks (a new address from it is better
        // than no address at all), but an address from another client is preferred over it.
        val bad = badClient[id]
        val clients = quickClients
        for (c in clients) {
            kotlin.concurrent.thread(start = true, isDaemon = true, name = "music-quick") {
                val label = c.name + " " + c.version
                var hit = Hit(null, c.ua, label, c.name)
                try {
                    if (!finished.get()) {
                        val u = quickAudioUrlWith(id, c)
                        if (u != null && !finished.get()) {
                            if (probeUrl(u, c.ua)) {
                                // EVERY client's address (ANDROID_VR too) must prove it serves more
                                // than the first megabyte. ANDROID_VR used to be taken on trust, and an
                                // address that stops at 1:04 was played as if it were fine.
                                val whole = streamsPastFirstMegabyte(u, c.ua)
                                if (!whole) Logs.log(TAG, "quick stream from $label stops after the first megabyte for $id")
                                hit = Hit(u, c.ua, label, c.name, whole)
                            } else {
                                Logs.log(TAG, "quick stream from $label was refused for $id")
                            }
                        }
                    }
                } catch (e: Throwable) {
                    Logs.log(TAG, "quick $label failed: " + e.message)
                }
                quickDone.incrementAndGet()
                results.add(hit)
            }
        }
        kotlin.concurrent.thread(start = true, isDaemon = true, name = "music-newpipe") {
            var hit = Hit(null, "", "NewPipe", "NewPipe")
            try {
                val headStart = if (attempt == 0) NEWPIPE_HEAD_START_MS else 0L
                val begun = System.currentTimeMillis()
                while (!finished.get() && quickDone.get() < clients.size &&
                    System.currentTimeMillis() - begun < headStart
                ) {
                    Thread.sleep(80)
                }
                if (!finished.get()) {
                    val u = newPipeUrl(id)
                    if (u != null) {
                        val ua = userAgentFor(u)
                        hit = Hit(u, ua, "NewPipe", "NewPipe", streamsPastFirstMegabyte(u, ua))
                    }
                }
            } catch (e: Throwable) {
                // This Android version lacks a method the extractor needs (for example
                // URLDecoder.decode with a Charset before Android 13): it fails the same way every
                // time, so it is switched off instead of being asked again.
                if (e is NoSuchMethodError || e is NoClassDefFoundError) newPipeState = 2
                Logs.log(TAG, "newpipe failed: " + e.message)
            }
            results.add(hit)
        }
        val total = clients.size + 1
        // The best address so far: one that plays the whole song beats one that stops after a minute.
        var best: Hit? = null
        val deadline = System.currentTimeMillis() + TV_LOOKUP_MS
        var waitUntil = deadline
        try {
            var seen = 0
            while (seen < total) {
                val left = waitUntil - System.currentTimeMillis()
                if (left <= 0) break
                val r = results.poll(left, java.util.concurrent.TimeUnit.MILLISECONDS) ?: break
                seen++
                if (r.url == null) continue
                val sameAsBad = bad != null && r.client.equals(bad, ignoreCase = true)
                if (r.whole && !sameAsBad) {
                    best = r
                    break
                }
                val current = best
                if (current == null || (r.whole && !current.whole)) best = r
                // Give the others a moment, then take what there is.
                val grace = if (r.whole) SOFT_GRACE_MS else LIMITED_GRACE_MS
                waitUntil = minOf(waitUntil, System.currentTimeMillis() + grace)
            }
        } catch (e: InterruptedException) {
            // The player threw this song away (the person picked another one): stop at once.
            finished.set(true)
            Thread.currentThread().interrupt()
            throw java.io.InterruptedIOException("stream lookup cancelled")
        }
        finished.set(true)
        return best
    }

    /** NewPipe's own lookup. Prefers AAC (audio/mp4), which every television decodes. */
    private fun newPipeUrl(id: String): String? {
        if (!ensureNewPipe()) return null
        val info = org.schabi.newpipe.extractor.stream.StreamInfo.getInfo("https://www.youtube.com/watch?v=$id")
        var best: String? = null
        var bestRate = -1
        var m4a: String? = null
        var m4aRate = -1
        var any: String? = null
        for (a in info.audioStreams.orEmpty()) {
            val u = a.url ?: a.content
            if (u.isNullOrBlank()) continue
            if (any == null) any = u
            if (a.deliveryMethod != org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP) continue
            val rate = a.averageBitrate
            if (rate > bestRate) {
                bestRate = rate
                best = u
            }
            if (u.contains("mime=audio%2Fmp4") && rate > m4aRate) {
                m4aRate = rate
                m4a = u
            }
        }
        return m4a ?: best ?: any
    }

    // ------------------------------------------------------------------ phone (NewPipe)

    private fun resolveLegacyBlocking(id: String): String {
        val now = System.currentTimeMillis()
        streamCache[id]?.let { if (it.expires > now) return it.url }
        if (!ensureNewPipe()) throw IOException("YouTube extractor is not available")
        val info = org.schabi.newpipe.extractor.stream.StreamInfo.getInfo("https://www.youtube.com/watch?v=$id")
        var best: String? = null
        var bestRate = -1
        var any: String? = null
        for (a in info.audioStreams.orEmpty()) {
            val u = a.url ?: a.content
            if (u.isNullOrBlank()) continue
            if (any == null) any = u
            if (a.deliveryMethod != org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP) continue
            val rate = a.averageBitrate
            if (rate > bestRate) {
                bestRate = rate
                best = u
            }
        }
        val url = best ?: any ?: throw IOException("No audio stream for this song")
        // Stream addresses stop working after ~6 hours; keep them for 5.
        streamCache[id] = Resolved(url, now + 5L * 60 * 60 * 1000)
        Logs.log(TAG, "stream ready for $id (${bestRate}kbps)")
        return url
    }
}
