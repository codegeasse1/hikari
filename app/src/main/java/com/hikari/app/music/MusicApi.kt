package com.hikari.app.music

import android.net.Uri
import com.hikari.app.data.Logs
import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * YouTube Music backend for the "HIKari music" provider.
 */
object MusicApi {
    private const val TAG = "HikariMusic"
    private const val ORIGIN = "https://music.youtube.com"
    private const val BASE = "$ORIGIN/youtubei/v1/"
    private const val KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
    private const val CLIENT_VERSION = "1.20260213.01.00"

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

    suspend fun browseShelves(browseId: String, params: String? = null): Pair<List<MShelf>, String?> {
        val body = JSONObject().put("browseId", browseId)
        if (!params.isNullOrBlank()) body.put("params", params)
        val root = post("browse", body)
        return Pair(parseShelves(root), continuationOf(root))
    }

    suspend fun search(query: String, filter: String?): List<MShelf> =
        if (filter == null) searchAll(query) else searchOne(query, filter)

    private suspend fun searchOne(query: String, filter: String?): List<MShelf> {
        val body = JSONObject().put("query", query)
        if (filter != null) body.put("params", filter)
        return parseShelves(post("search", body))
    }

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
        if (out.isEmpty()) firstError?.let { throw it }
        out
    }

    suspend fun suggestions(query: String): List<String> {
        val root = post("music/get_search_suggestions", JSONObject().put("input", query))
        val found = ArrayList<JSONObject>()
        findAll(root, "searchSuggestionRenderer", found)
        return found.map { it.runsText("suggestion").trim() }.filter { it.isNotEmpty() }.distinct()
    }

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
        fun parseLrclib(text: String?): MLyrics? {
            if (text.isNullOrBlank()) return null
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
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
    private val attempts = ConcurrentHashMap<String, Int>()
    private val badClient = ConcurrentHashMap<String, String>()
    private val clientParam = Regex("[?&]c=([A-Za-z_]+)")

    private fun clientOf(url: String): String? = clientParam.find(url)?.groupValues?.get(1)

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
            if (ok) {
                runCatching {
                    com.hikari.app.music.potoken.HikariPoTokenProvider.attach(
                        com.hikari.app.HikariApp.instance.applicationContext,
                    )
                    org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
                        .setPoTokenProvider(com.hikari.app.music.potoken.HikariPoTokenProvider)
                }
            }
            if (!ok) Logs.log(TAG, "newpipe: the extractor could not be started on this device")
            newPipeState = if (ok) 1 else 2
            ok
        }
    }

    @Volatile
    private var prewarmed = false

    fun prewarmTelevision() {
        if (prewarmed || !com.hikari.app.tv.TvMode.isTv) return
        prewarmed = true
        kotlin.concurrent.thread(start = true, isDaemon = true, name = "music-prewarm") {
            try {
                potPair()
                ensureNewPipe()
            } catch (t: Throwable) {
                Logs.log(TAG, "prewarm failed: " + t.message)
            }
        }
    }

    private val skipPremium = ConcurrentHashMap.newKeySet<String>()

    /** Marks the current cached URL invalid; increments [attempts] only when [countAttempt] is true. */
    fun invalidate(id: String, countAttempt: Boolean = true) {
        streamCache[id]?.url?.let { u -> clientOf(u)?.let { badClient[id] = it } }
        streamCache.remove(id)
        skipPremium.add(id)
        if (countAttempt) {
            attempts[id] = (attempts[id] ?: 0) + 1
        }
    }

    fun markPlayed(id: String) {
        attempts.remove(id)
    }

    fun resetAttempts(id: String) {
        attempts.remove(id)
        badClient.remove(id)
        skipPremium.remove(id)
    }

    private class QuickClient(
        val name: String,
        val numeric: String,
        val version: String,
        val ua: String,
        /** True only for Web-family clients (MWEB / WEB_REMIX) that use Web BotGuard PO tokens. */
        val webPoToken: Boolean = false,
        val extra: JSONObject.() -> Unit,
    )

    /** Separate visitor ID for native clients (ANDROID_VR, IOS, ANDROID_MUSIC) — never mixed with WEB visitorData. */
    @Volatile
    private var nativeVisitorData: String? = null

    private val quickClients: List<QuickClient> by lazy {
        listOf(
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
            QuickClient(
                "ANDROID_MUSIC", "21", "8.12.53",
                "com.google.android.apps.youtube.music/8.12.53 (Linux; U; Android 14; Pixel 8 Build/UQ1A.240205.004) gzip",
            ) {
                put("deviceMake", "Google"); put("deviceModel", "Pixel 8")
                put("androidSdkVersion", 34); put("osName", "Android"); put("osVersion", "14")
            },
            QuickClient(
                "MWEB", "2", "2.20260213.01.00",
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                webPoToken = true,
            ) {
                put("platform", "MOBILE")
            },
        )
    }

    private fun quickAudioUrlWith(
        id: String,
        c: QuickClient,
        visitor: String? = if (c.webPoToken) null else nativeVisitorData,
        retried: Boolean = false,
    ): String? {
        // Only attach Web BotGuard PO token & Web visitorData to Web-family clients (MWEB).
        // Native clients (ANDROID_VR / IOS / ANDROID_MUSIC) must never receive a WEB pot= token.
        val pot = if (c.webPoToken) potPair() else null
        if (c.webPoToken && pot == null) return null
        val vis: String? = if (c.webPoToken) pot?.visitor else visitor

        val client = JSONObject()
            .put("clientName", c.name)
            .put("clientVersion", c.version)
            .put("userAgent", c.ua)
            .put("hl", "en")
            .put("gl", "US")
            .put("timeZone", "UTC")
            .put("utcOffsetMinutes", 0)
        if (!vis.isNullOrBlank()) client.put("visitorData", vis)
        c.extra(client)

        val context = JSONObject().put("client", client)
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
        if (pot != null) {
            body.put(
                "serviceIntegrityDimensions",
                JSONObject().put("poToken", pot.pot),
            )
        }

        val headers = HashMap<String, String>()
        headers["User-Agent"] = c.ua
        headers["X-YouTube-Client-Name"] = c.numeric
        headers["X-YouTube-Client-Version"] = c.version
        headers["Origin"] = if (c.webPoToken) "https://m.youtube.com" else "https://www.youtube.com"
        if (c.webPoToken) headers["Referer"] = "https://m.youtube.com/"
        if (!vis.isNullOrBlank()) headers["X-Goog-Visitor-Id"] = vis

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
        val issued = root.o("responseContext")?.optString("visitorData").orEmpty()
        if (!c.webPoToken && issued.isNotBlank() && nativeVisitorData == null) {
            nativeVisitorData = issued
        }
        val status = root.o("playabilityStatus")?.optString("status").orEmpty()
        if (status != "OK") {
            Logs.log(
                TAG,
                "quick $tag: " + status + " " + root.o("playabilityStatus")?.optString("reason").orEmpty() + " for " + id,
            )
            if (status == "UNPLAYABLE" && !retried && com.hikari.app.tv.TvMode.isTv) {
                if (c.webPoToken) rotateVisitor() else nativeVisitorData = null
                return quickAudioUrlWith(id, c, null, true)
            }
            val known = if (c.webPoToken) null else (issued.ifBlank { nativeVisitorData })
            if (status == "LOGIN_REQUIRED" && !retried && !known.isNullOrBlank() && known != visitor) {
                return quickAudioUrlWith(id, c, known, true)
            }
            return null
        }
        val formats = root.o("streamingData")?.a("adaptiveFormats")
        if (formats == null) {
            Logs.log(TAG, "quick $tag: no adaptive formats for $id")
            return null
        }
        // Prefer AAC (audio/mp4) first, then Opus (audio/webm) which Media3 ExoPlayer decodes natively on all Android devices.
        var bestAac: String? = null
        var bestAacRate = -1
        var bestOpus: String? = null
        var bestOpusRate = -1
        for (f in formats.objs()) {
            val mime = f.optString("mimeType")
            val u = f.optString("url")
            if (u.isBlank()) continue
            val rate = f.optInt("bitrate")
            if (mime.startsWith("audio/mp4") && rate > bestAacRate) {
                bestAacRate = rate
                bestAac = u
            } else if (mime.startsWith("audio/webm") && rate > bestOpusRate) {
                bestOpusRate = rate
                bestOpus = u
            }
        }
        val found = bestAac ?: bestOpus
        if (found == null) {
            Logs.log(TAG, "quick $tag: no direct audio address for $id")
            return null
        }
        return if (pot != null && !found.contains("pot=")) {
            found + "&pot=" + Uri.encode(pot.pot)
        } else {
            found
        }
    }

    private fun potPair(): com.hikari.app.music.potoken.HikariPoTokenProvider.StreamingPot? {
        if (!com.hikari.app.tv.TvMode.isTv) return null
        com.hikari.app.music.potoken.HikariPoTokenProvider.attach(com.hikari.app.HikariApp.instance.applicationContext)
        return com.hikari.app.music.potoken.HikariPoTokenProvider.streamingPot()
    }

    private var lastRotateMs = 0L

    @Synchronized
    private fun rotateVisitor(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastRotateMs < 30_000L) return true
        lastRotateMs = now
        nativeVisitorData = null
        com.hikari.app.music.potoken.HikariPoTokenProvider.rotate()
        return true
    }

    class StreamForm(val post: Boolean, val param: Boolean)

    private val defaultForm = StreamForm(false, true)
    private val formByUrl = ConcurrentHashMap<String, StreamForm>()

    fun formFor(url: String): StreamForm = formByUrl[url] ?: defaultForm

    private val probeForms = listOf(
        StreamForm(false, true),
        StreamForm(false, false),
        StreamForm(true, false),
        StreamForm(true, true),
    )

    private fun formName(f: StreamForm): String =
        (if (f.post) "POST" else "GET") + " with " + (if (f.param) "range parameter" else "Range header")

    private const val POST_BODY_TEXT = "x\u0000"

    private fun probeUrl(url: String, ua: String): Boolean {
        val viaGet = try {
            Http.request(
                "GET", url, null,
                mapOf("User-Agent" to ua, "Range" to "bytes=0-1") + webOnlyHeaders(url),
            ).use { it.code in 200..299 }
        } catch (e: Exception) {
            false
        }
        if (viaGet) return true
        return codeOfRead(url, ua, StreamForm(true, true), 0L) in 200..299
    }

    private val contentLengthInUrl = Regex("[?&]clen=(\\d+)")
    private const val FIRST_MEGABYTE = 1_048_576L

    private fun codeOfRead(url: String, ua: String, form: StreamForm, from: Long): Int = try {
        val headers = HashMap<String, String>()
        headers["User-Agent"] = ua
        headers.putAll(webOnlyHeaders(url))
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

    private val checkClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
            .dns(com.hikari.app.net.DohDns)
            .build()
    }

    private class PieceAnswer(val code: Int, val bytes: Long, val type: String, val declared: Long)

    private fun readPiece(url: String, ua: String, form: StreamForm, from: Long, want: Long): PieceAnswer {
        return try {
            val last = from + want - 1L
            val target = if (form.param) url + "&range=" + from + "-" + last + "&rn=1" else url + "&rn=1"
            val b = okhttp3.Request.Builder().url(target)
            b.header("User-Agent", ua)
            for ((k, v) in webOnlyHeaders(url)) b.header(k, v)
            b.header("Accept-Encoding", "identity")
            if (!form.param) b.header("Range", "bytes=" + from + "-" + last)
            if (form.post) {
                b.post(POST_BODY_TEXT.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            }
            checkClient.newCall(b.build()).execute().use { reply ->
                val type = reply.header("Content-Type").orEmpty().lowercase()
                val declared = reply.header("Content-Length")?.trim()?.toLongOrNull() ?: -1L
                var total = 0L
                val src = reply.body?.byteStream()
                if (src != null && reply.isSuccessful) {
                    val buf = ByteArray(8192)
                    val need = minOf(want, 16_384L)
                    while (total < need) {
                        val n = src.read(buf)
                        if (n < 0) break
                        total += n.toLong()
                    }
                }
                PieceAnswer(reply.code, total, type, declared)
            }
        } catch (e: Exception) {
            PieceAnswer(-1, 0L, "", -1L)
        }
    }

    private fun pieceOk(a: PieceAnswer, want: Long): Boolean {
        if (a.code !in 200..299) return false
        if (a.type.contains("ump") || a.type.startsWith("text/") || a.type.contains("json") || a.type.contains("html")) return false
        if (a.declared > want + 1L) return false
        return a.bytes >= minOf(want, 4096L)
    }

    private val variantForms: List<StreamForm> by lazy { listOf(probeForms[0], probeForms[2]) }

    private fun streamsPastFirstMegabyte(url: String, ua: String, forms: List<StreamForm> = probeForms): Boolean {
        val size = contentLengthInUrl.find(url)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
        val from = FIRST_MEGABYTE
        if (size in 1L..(from + 2L)) return true
        val want = if (size > from) minOf(24_576L, size - from) else 24_576L
        val codes = StringBuilder()
        for (form in forms) {
            val a = readPiece(url, ua, form, from, want)
            codes.append(a.code).append(' ')
            if (pieceOk(a, want)) {
                if (formByUrl.size > 300) formByUrl.clear()
                formByUrl[url] = form
                return true
            }
        }
        Logs.log(TAG, "address refuses everything after the first megabyte (HTTP " + codes.toString().trim() + ")")
        return false
    }

    private fun potVariants(url: String): List<Pair<String, String>> {
        val at = url.indexOf("&pot=")
        if (at < 0) return emptyList()
        val rest = url.substring(at + 5)
        val end = rest.indexOf('&')
        val token = if (end >= 0) rest.substring(0, end) else rest
        val tail = if (end >= 0) rest.substring(end) else ""
        val plain = url.substring(0, at) + tail
        val out = ArrayList<Pair<String, String>>()
        out.add(Pair(plain, "no"))
        val encoded = android.net.Uri.encode(token)
        if (encoded != token) out.add(Pair(plain + "&pot=" + encoded, "an encoded"))
        return out
    }

    private fun webOnlyHeaders(url: String): Map<String, String> {
        if (!com.hikari.app.tv.TvMode.isTv) return emptyMap()
        if (url.contains("c=MWEB")) {
            return mapOf(
                "Origin" to "https://m.youtube.com",
                "Referer" to "https://m.youtube.com/",
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "cross-site",
            )
        }
        return runCatching {
            if (org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isWebStreamingUrl(url)) {
                mapOf(
                    "Origin" to "https://www.youtube.com",
                    "Referer" to "https://www.youtube.com",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "cross-site",
                )
            } else {
                emptyMap()
            }
        }.getOrDefault(emptyMap())
    }

    fun streamHeaders(url: String): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = userAgentFor(url)
        h.putAll(webOnlyHeaders(url))
        return h
    }

    private fun isVisionOsUrl(url: String): Boolean {
        if (!com.hikari.app.tv.TvMode.isTv) return false
        return runCatching {
            org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isVisionOsStreamingUrl(url)
        }.getOrDefault(false)
    }

    fun userAgentFor(url: String): String {
        uaByUrl[url]?.let { return it }
        return when {
            isVisionOsUrl(url) -> runCatching {
                org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getVisionOsUserAgent(null)
            }.getOrDefault(Http.UA)
            url.contains("c=IOS") ->
                "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"
            url.contains("c=ANDROID_VR") ->
                "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
            url.contains("c=ANDROID_MUSIC") ->
                "com.google.android.apps.youtube.music/8.12.53 (Linux; U; Android 14; Pixel 8 Build/UQ1A.240205.004) gzip"
            url.contains("c=ANDROID") -> "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip"
            url.contains("c=MWEB") ->
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
            else -> Http.UA
        }
    }

    @Volatile
    var lastVx: VxLosslessApi.VxStream? = null
    @Volatile
    var lastSource: String = "YouTube"

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

    fun resolveStreamBlocking(id: String): String = resolveStreamBlocking(id, null)

    fun resolveStreamBlocking(id: String, song: MSong?): String {
        val now = System.currentTimeMillis()
        streamCache[id]?.let { if (it.expires > now) return it.url }
        if (com.hikari.app.tv.TvMode.isTv) return resolveTelevision(id, song, now)
        val premium = id !in skipPremium
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
        if (premium && song != null && song.title.isNotBlank() && song.artist.isNotBlank()) {
            jioFor(id, song, now)?.let { return it }
        }
        lastVx = null
        lastSource = "YouTube"
        return resolveLegacyBlocking(id)
    }

    private fun jioFor(id: String, song: MSong, now: Long, slackSec: Int = 6): String? {
        val jio = runCatching {
            VxJioSaavnApi.resolveBlocking(song.title, song.artist, song.durationSec.takeIf { it > 0 }, slackSec)
        }.getOrNull()
        if (jio == null || jio.url.isBlank()) return null
        lastVx = null
        streamCache[id] = Resolved(jio.url, now + 5L * 60 * 60 * 1000)
        lastSource = "JioSaavn 320k"
        Logs.log(TAG, "jio 320k ready for " + id)
        return jio.url
    }

    // ------------------------------------------------------------------ television

    private const val TV_LOOKUP_MS = 22_000L
    private const val NEWPIPE_HEAD_START_MS = 2_000L

    private class Hit(
        val url: String?,
        val ua: String,
        val label: String,
        val client: String = "",
        val whole: Boolean = true,
    )

    private const val LIMITED_GRACE_MS = 4_000L
    private const val SOFT_GRACE_MS = 2_000L

    /**
     * Television lookup: ALWAYS resolves the exact YouTube track first so a JioSaavn
     * search can never hijack a Russian/English/non-Hindi track with a Hindi song.
     */
    private fun resolveTelevision(id: String, song: MSong?, now: Long): String {
        val attempt = attempts[id] ?: 0
        val hit = raceYoutube(id, attempt)
        if (hit != null && hit.url != null && hit.whole) {
            return acceptHit(id, hit, hit.url, now, attempt)
        }
        if (hit != null && hit.url != null) {
            // Even a first-megabyte URL can now play past 1 MB because MusicDataSource streams in 900 KB chunks and refreshes cleanly.
            return acceptHit(id, hit, hit.url, now, attempt)
        }
        Logs.log(TAG, "tv: YouTube gave no stream for " + id + " (try " + (attempt + 1) + ")")
        if (song != null && song.title.isNotBlank() && song.artist.isNotBlank()) {
            jioFor(id, song, now, 6)?.let { return it }
        }
        throw IOException("No audio stream for this song")
    }

    private fun acceptHit(id: String, hit: Hit, url: String, now: Long, attempt: Int): String {
        if (uaByUrl.size > 300) uaByUrl.clear()
        uaByUrl[url] = hit.ua
        lastVx = null
        lastSource = "YouTube"
        val keepMs = if (hit.whole) 5L * 60 * 60 * 1000 else 15_000L
        streamCache[id] = Resolved(url, now + keepMs)
        Logs.log(
            TAG,
            "tv stream ready for " + id + " from " + hit.label + " in " + (System.currentTimeMillis() - now) + "ms (try " + (attempt + 1) + ")" +
                (if (hit.whole) "" else ", chunked mode"),
        )
        return url
    }

    private fun raceYoutube(id: String, attempt: Int): Hit? {
        val results = java.util.concurrent.LinkedBlockingQueue<Hit>()
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val quickDone = java.util.concurrent.atomic.AtomicInteger(0)
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
                                var chosen: String = u
                                var whole = streamsPastFirstMegabyte(u, c.ua)
                                var how = if (u.contains("pot=")) "with" else "without"
                                if (!whole && !finished.get()) {
                                    for (v in potVariants(u)) {
                                        if (finished.get()) break
                                        if (streamsPastFirstMegabyte(v.first, c.ua, variantForms)) {
                                            chosen = v.first
                                            whole = true
                                            how = v.second
                                            break
                                        }
                                    }
                                }
                                Logs.log(TAG, "quick stream from $label: " + how + " PO token, " + (if (whole) "plays the whole song" else "chunked") + " ($id)")
                                hit = Hit(chosen, c.ua, label, c.name, whole)
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
                if (newPipeState != 2) {
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
                }
            } catch (e: Throwable) {
                if (e is NoSuchMethodError || e is NoClassDefFoundError) newPipeState = 2
                Logs.log(TAG, "newpipe failed: " + e.message)
            }
            results.add(hit)
        }
        val total = clients.size + 1
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
                if (current == null || (r.whole && !current.whole) || (!sameAsBad && current.client.equals(bad, ignoreCase = true))) {
                    best = r
                }
                val grace = if (r.whole) SOFT_GRACE_MS else LIMITED_GRACE_MS
                waitUntil = minOf(waitUntil, System.currentTimeMillis() + grace)
            }
        } catch (e: InterruptedException) {
            finished.set(true)
            Thread.currentThread().interrupt()
            throw java.io.InterruptedIOException("stream lookup cancelled")
        }
        finished.set(true)
        return best
    }

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
        streamCache[id] = Resolved(url, now + 5L * 60 * 60 * 1000)
        Logs.log(TAG, "stream ready for $id (${bestRate}kbps)")
        return url
    }
}
