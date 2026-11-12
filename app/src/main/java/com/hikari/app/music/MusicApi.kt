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
        var videoId = r.o("playlistItemData")?.optString("videoId").orEmpty()
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
        val url = "https://lrclib.net/api/get?track_name=" + URLEncoder.encode(song.title, "UTF-8") +
            "&artist_name=" + URLEncoder.encode(song.artist, "UTF-8") +
            (if (song.durationSec > 0) "&duration=" + song.durationSec else "")
        val text = Http.getString(url) ?: return@withContext null
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return@withContext null
        val plain = json.optString("plainLyrics")
        val synced = ArrayList<MLine>()
        val lrc = json.optString("syncedLyrics")
        val re = Regex("^\\[(\\d+):(\\d+(?:\\.\\d+)?)](.*)$")
        for (line in lrc.lines()) {
            val m = re.find(line.trim()) ?: continue
            val min = m.groupValues[1].toLongOrNull() ?: continue
            val sec = m.groupValues[2].toDoubleOrNull() ?: continue
            synced.add(MLine(min * 60_000L + (sec * 1000).toLong(), m.groupValues[3].trim()))
        }
        if (synced.isEmpty() && plain.isBlank()) null else MLyrics(synced, plain)
    }

    // ------------------------------------------------------------------ streams (NewPipeExtractor)

    private class Resolved(val url: String, val expires: Long)

    private val streamCache = ConcurrentHashMap<String, Resolved>()

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

    fun invalidate(id: String) {
        streamCache.remove(id)
    }

    /** The User-Agent a stream address expects (it depends on which YouTube client made it). */
    fun userAgentFor(url: String): String = when {
        url.contains("c=IOS") ->
            "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"
        url.contains("c=ANDROID_VR") ->
            "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1; Cronet/132.0.6808.3)"
        url.contains("c=ANDROID") -> "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip"
        else -> Http.UA
    }

    /**
     * BLOCKING: returns the audio address for [id]. Called by the player's data source on its
     * own loading thread, never on the UI thread.
     */
    fun resolveStreamBlocking(id: String): String {
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
