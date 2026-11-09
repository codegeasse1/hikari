package com.hikari.app.iptv

import com.hikari.app.data.Logs
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** Xtream Codes account details (player_api.php user_info + server_info). */
data class XtreamAccountInfo(
    val expiresAtMs: Long = 0L,
    val status: String = "",
    val maxConnections: Int = 0,
    val activeConnections: Int = 0,
    val authFailed: Boolean = false,
    val allowedFormats: Set<String> = emptySet(),
    val serverTimezone: String? = null,
) {
    fun loginError(nowMs: Long = System.currentTimeMillis()): String? {
        if (authFailed) return "Login failed: check the username and password"
        if (status.isNotBlank() && !status.equals("Active", ignoreCase = true)) return "Account $status"
        if (expiresAtMs > 0 && expiresAtMs < nowMs) return "Account expired"
        return null
    }
}

/** One live channel from get_live_streams. */
data class XtreamLiveStream(
    val streamId: String,
    val name: String,
    val epgChannelId: String?,
    val icon: String?,
    val categoryId: String?,
    val num: String?,
    val tvArchive: Boolean,
    val tvArchiveDays: String?,
)

/** One movie (get_vod_streams) or series (get_series). */
data class XtreamVodEntry(
    val isSeries: Boolean,
    val id: String,
    val name: String,
    val icon: String?,
    val categoryId: String,
    val containerExtension: String?,
    val tmdbId: String?,
    val year: String?,
    val rating: String?,
    val addedSec: Long,
    val genre: String?,
)

class XtreamLoginError(message: String) : Exception(message)

/**
 * The Xtream Codes API (player_api.php), OkHttp edition: account check, live
 * categories/streams (written out as M3U so the rest of Live TV treats a login
 * like any playlist), movie/series lists for On Demand, and the provider guide.
 */
class XtreamApi {
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    // ---------- account ----------

    fun accountInfo(src: LiveTvSource): XtreamAccountInfo {
        require(src.xtreamServer.isNotBlank()) { "No server set" }
        require(src.xtreamUsername.isNotBlank() && src.xtreamPassword.isNotBlank()) { "Username and password are required" }
        return accountInfo(apiUrl(src), src.userAgent)
    }

    fun accountInfo(apiUrl: String, userAgent: String): XtreamAccountInfo {
        val info = parseObjectOrNull(getText(apiUrl, userAgent)) ?: throw XtreamLoginError("Not an Xtream API response")
        val ui = info.optJSONObject("user_info")
        if (ui == null && !info.has("user_info")) throw XtreamLoginError("Not an Xtream API response")
        val allowed = ui?.optJSONArray("allowed_output_formats")?.let { a ->
            List(a.length()) { i -> a.optString(i).lowercase() }.filter { it.isNotBlank() }.toSet()
        }.orEmpty()
        val authFailed = ui?.optString("auth") == "0"
        val exp = ui?.optString("exp_date")?.toLongOrNull()?.takeIf { it > 0 }?.times(1000) ?: 0L
        return XtreamAccountInfo(
            expiresAtMs = exp,
            status = ui?.optString("status").orEmpty(),
            maxConnections = ui?.optString("max_connections")?.toIntOrNull() ?: 0,
            activeConnections = ui?.optString("active_cons")?.toIntOrNull() ?: 0,
            authFailed = authFailed,
            allowedFormats = allowed,
            serverTimezone = info.optJSONObject("server_info")?.optString("timezone")?.takeIf { it.isNotBlank() },
        )
    }

    /** An M3U link from an Xtream provider: the same server's player_api.php account URL. */
    fun m3uAccountUrl(link: String): String? = runCatching {
        val q = link.substringAfter('?', "").ifBlank { return null }
        val params = q.split('&').associate {
            it.substringBefore('=').lowercase() to it.substringAfter('=', "")
        }
        val user = params["username"]?.takeIf { it.isNotBlank() } ?: return null
        val pass = params["password"] ?: return null
        val base = cleanServer(link.substringBefore("/get.php"))
        "$base/player_api.php?username=$user&password=$pass"
    }.getOrNull()

    // ---------- live ----------

    fun liveCategories(src: LiveTvSource): Map<String, String> = runCatching {
        val out = HashMap<String, String>()
        forEachObject("${apiUrl(src)}&action=get_live_categories", src.userAgent) { f ->
            val id = f["category_id"] ?: return@forEachObject
            out[id] = f["category_name"] ?: ""
        }
        out
    }.getOrDefault(emptyMap())

    fun liveStreams(src: LiveTvSource, onProgress: ((Int) -> Unit)? = null): List<XtreamLiveStream> {
        val out = ArrayList<XtreamLiveStream>()
        forEachObject("${apiUrl(src)}&action=get_live_streams", src.userAgent) { f ->
            val streamId = f["stream_id"] ?: return@forEachObject
            out += XtreamLiveStream(
                streamId = streamId,
                name = (f["name"] ?: "Channel $streamId").replace("\n", " ").trim(),
                epgChannelId = f["epg_channel_id"],
                icon = f["stream_icon"],
                categoryId = f["category_id"],
                num = f["num"],
                tvArchive = f["tv_archive"] == "1",
                tvArchiveDays = f["tv_archive_duration"]?.takeIf { it != "0" },
            )
            if (out.size % 500 == 0) onProgress?.invoke(out.size)
        }
        return out
    }

    data class LivePlaylist(val m3u: String, val channelCount: Int, val account: XtreamAccountInfo)

    /** Loads a login (account → categories → streams) and returns channels as an M3U. */
    fun livePlaylistM3u(src: LiveTvSource, onProgress: ((Int) -> Unit)? = null): LivePlaylist {
        val info = accountInfo(src)
        info.loginError()?.let { throw XtreamLoginError(it) }
        val categories = runCatching { liveCategories(src) }.getOrDefault(emptyMap())
        val streams = liveStreams(src, onProgress)
        val ext = streamExtension(src, info.allowedFormats)
        val base = cleanServer(src.xtreamServer)
        val user = enc(src.xtreamUsername.trim())
        val pass = enc(src.xtreamPassword.trim())
        val out = StringBuilder(streams.size * 160 + 16)
        out.append("#EXTM3U\n")
        var count = 0
        for (s in streams) {
            out.append("#EXTINF:-1")
            fun attr(k: String, v: String?) {
                if (!v.isNullOrBlank()) out.append(" $k=\"${v.replace("\"", "'")}\"")
            }
            attr("tvg-id", s.epgChannelId)
            attr("tvg-name", s.name)
            attr("tvg-logo", s.icon)
            attr("group-title", categories[s.categoryId] ?: "Uncategorized")
            attr("tvg-chno", s.num)
            if (s.tvArchive) {
                attr("catchup", "xc")
                attr("catchup-days", s.tvArchiveDays ?: "1")
            }
            out.append(',').append(s.name).append('\n')
            out.append(base).append("/live/").append(user).append('/').append(pass)
                .append('/').append(s.streamId).append('.').append(ext).append('\n')
            count++
        }
        if (count == 0) throw XtreamLoginError("The provider returned no live channels")
        return LivePlaylist(out.toString(), count, info)
    }

    /** The stream file extension for this login: its own choice, or what the account allows. */
    fun streamExtension(src: LiveTvSource, allowedFormats: Set<String>): String = when (src.streamFormat) {
        "ts" -> "ts"
        "m3u8" -> "m3u8"
        else -> if (allowedFormats.isNotEmpty() && "ts" !in allowedFormats && "m3u8" in allowedFormats) "m3u8" else "ts"
    }

    fun liveUrl(src: LiveTvSource, streamId: String, ext: String? = null): String {
        val e = ext ?: streamExtension(src, emptySet())
        return "${cleanServer(src.xtreamServer)}/live/${enc(src.xtreamUsername.trim())}/${enc(src.xtreamPassword.trim())}/$streamId.$e"
    }

    // ---------- On Demand ----------

    fun vodCategories(src: LiveTvSource, series: Boolean): List<Pair<String, String>> {
        val action = if (series) "get_series_categories" else "get_vod_categories"
        return runCatching {
            val out = ArrayList<Pair<String, String>>()
            forEachObject("${apiUrl(src)}&action=$action", src.userAgent) { f ->
                val id = f["category_id"] ?: return@forEachObject
                out += id to (f["category_name"]?.ifBlank { "Other" } ?: "Other")
            }
            out
        }.getOrDefault(emptyList())
    }

    fun vodItems(src: LiveTvSource, series: Boolean, categoryId: String? = null): List<XtreamVodEntry> {
        val action = if (series) "get_series" else "get_vod_streams"
        var url = "${apiUrl(src)}&action=$action"
        if (categoryId != null) url += "&category_id=" + enc(categoryId)
        val out = ArrayList<XtreamVodEntry>()
        forEachObject(url, src.userAgent) { f ->
            val id = (if (series) f["series_id"] else f["stream_id"]) ?: return@forEachObject
            val name = f["name"]?.trim().orEmpty().ifBlank { f["title"].orEmpty() }
            if (name.isBlank()) return@forEachObject
            out += XtreamVodEntry(
                isSeries = series,
                id = id,
                name = name,
                icon = (if (series) f["cover"] else f["stream_icon"])?.takeIf { it.startsWith("http") },
                categoryId = f["category_id"].orEmpty(),
                containerExtension = f["container_extension"]?.takeIf { it.isNotBlank() },
                tmdbId = (f["tmdb"] ?: f["tmdb_id"])?.takeIf { it.isNotBlank() && it != "0" },
                year = listOf(f["year"], f["releaseDate"], f["release_date"]).firstNotNullOfOrNull { yearOf(it) }?.toString(),
                rating = f["rating"]?.takeIf { it.isNotBlank() && it != "0" },
                addedSec = (f["added"] ?: f["last_modified"])?.toLongOrNull() ?: 0L,
                genre = f["genre"]?.trim()?.takeIf { it.isNotBlank() },
            )
        }
        return out
    }

    fun vodInfo(src: LiveTvSource, series: Boolean, id: String): JSONObject? {
        val action = if (series) "get_series_info&series_id=$id" else "get_vod_info&vod_id=$id"
        return parseObjectOrNull(runCatching { getText("${apiUrl(src)}&action=$action", src.userAgent) }.getOrNull() ?: return null)
    }

    fun movieUrl(src: LiveTvSource, streamId: String, containerExtension: String?): String {
        val ext = containerExtension?.trim()?.trimStart('.')?.takeIf { it.isNotBlank() } ?: "mp4"
        return "${cleanServer(src.xtreamServer)}/movie/${enc(src.xtreamUsername.trim())}/${enc(src.xtreamPassword.trim())}/$streamId.$ext"
    }

    fun episodeUrl(src: LiveTvSource, streamId: String, containerExtension: String?): String {
        val ext = containerExtension?.trim()?.trimStart('.')?.takeIf { it.isNotBlank() } ?: "mp4"
        return "${cleanServer(src.xtreamServer)}/series/${enc(src.xtreamUsername.trim())}/${enc(src.xtreamPassword.trim())}/$streamId.$ext"
    }

    // ---------- guide ----------

    fun epgXml(src: LiveTvSource): String? {
        val url = "${cleanServer(src.xtreamServer)}/xmltv.php?username=${enc(src.xtreamUsername.trim())}&password=${enc(src.xtreamPassword.trim())}"
        return runCatching { getText(url, src.userAgent, GUIDE_MAX_BYTES) }.getOrNull()
    }

    // ---------- plumbing ----------

    private fun apiUrl(src: LiveTvSource): String =
        "${cleanServer(src.xtreamServer)}/player_api.php?username=${enc(src.xtreamUsername.trim())}&password=${enc(src.xtreamPassword.trim())}"

    private fun getText(url: String, userAgent: String, maxBytes: Int = API_MAX_BYTES): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", userAgent.ifBlank { DEFAULT_UA })
            .header("Accept", "*/*")
            .build()
        client.newCall(req).execute().use { resp ->
            val code = resp.code
            if (code !in 200..299) throw XtreamLoginError(httpError(code))
            val body = resp.body ?: throw XtreamLoginError("Empty response")
            val stream = body.byteStream()
            val encoding = resp.header("Content-Encoding").orEmpty().lowercase()
            val input = if ("gzip" in encoding) GZIPInputStream(stream) else stream
            val buf = ByteArray(64 * 1024)
            val out = StringBuilder()
            var total = 0
            input.use { ins ->
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > maxBytes) throw XtreamLoginError("Response exceeds size limit")
                    // Cap text held at once: guides are parsed in pieces by the caller.
                    if (out.length < maxBytes) out.append(String(buf, 0, n, Charsets.UTF_8))
                }
            }
            if (total == 0) throw XtreamLoginError("Empty response")
            return out.toString()
        }
    }

    /** Reads the guide body in pieces (it can be hundreds of MB) for the windowed parser. */
    fun epgChunks(url: String, userAgent: String): Sequence<String> = sequence {
        val req = Request.Builder().url(url)
            .header("User-Agent", userAgent.ifBlank { DEFAULT_UA })
            .header("Accept", "*/*")
            .header("Accept-Encoding", "gzip")
            .build()
        val resp = client.newCall(req).execute()
        val code = resp.code
        if (code !in 200..299) {
            resp.close()
            throw XtreamLoginError(httpError(code))
        }
        val body = resp.body ?: run { resp.close(); throw XtreamLoginError("Empty response") }
        val stream = body.byteStream()
        val encoding = resp.header("Content-Encoding").orEmpty().lowercase()
        val input = if ("gzip" in encoding) GZIPInputStream(stream) else stream
        val buf = ByteArray(256 * 1024)
        var total = 0L
        try {
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                total += n
                if (total > GUIDE_MAX_BYTES) break
                yield(String(buf, 0, n, Charsets.UTF_8))
            }
        } finally {
            runCatching { input.close() }
            runCatching { resp.close() }
        }
    }

    /** A panel list, read a piece at a time; [onObject] gets each item's simple fields. */
    private fun forEachObject(url: String, userAgent: String, onObject: (Map<String, String>) -> Unit) {
        val req = Request.Builder().url(url)
            .header("User-Agent", userAgent.ifBlank { DEFAULT_UA })
            .header("Accept", "*/*")
            .build()
        client.newCall(req).execute().use { resp ->
            val code = resp.code
            if (code !in 200..299) throw XtreamLoginError(httpError(code))
            val scanner = FlatJsonScanner(onObject)
            val buf = ByteArray(64 * 1024)
            var total = 0L
            var any = false
            (resp.body ?: throw XtreamLoginError("Empty response")).byteStream().use { ins ->
                while (true) {
                    val n = ins.read(buf)
                    if (n == -1) break
                    if (n == 0) continue
                    any = true
                    total += n
                    if (total > API_MAX_BYTES) throw XtreamLoginError("Response exceeds size limit")
                    scanner.feed(buf, n)
                }
            }
            if (!any) throw XtreamLoginError("Empty response")
            scanner.finish()
        }
    }

    private fun parseObjectOrNull(text: String): JSONObject? =
        runCatching { JSONObject(text) }.getOrNull()?.takeIf { looksLikeXtream(it) }

    private fun looksLikeXtream(o: JSONObject): Boolean =
        o.has("user_info") || o.has("server_info")

    private fun yearOf(raw: String?): Int? {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return null
        return Regex("""(19|20)\d{2}""").find(t)?.value?.toIntOrNull()
    }

    companion object {
        const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
        const val API_MAX_BYTES = 256 * 1024 * 1024
        const val GUIDE_MAX_BYTES = 512 * 1024 * 1024

        /** http://host:port/(live/)?user/pass/id(.ext) */
        val xtreamLink = Regex("""^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\d+)(\.[a-z0-9]+)?(\?.*)?$""", RegexOption.IGNORE_CASE)

        fun enc(s: String): String = runCatching { URLEncoder.encode(s, "UTF-8") }.getOrDefault(s)

        /** Just scheme://host:port, even if a full link was pasted. */
        fun cleanServer(raw: String): String {
            var s = tidyLink(raw.trim())
            if (s.isEmpty()) return s
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
            return runCatching {
                val u = java.net.URI(s)
                val host = u.host?.takeIf { it.isNotBlank() } ?: return@runCatching s.trimEnd('/')
                val port = if (u.port > 0) ":${u.port}" else ""
                "${u.scheme.lowercase()}://$host$port"
            }.getOrDefault(s.trimEnd('/'))
        }

        /** Dashes/whitespace a keyboard or chat app smuggled into a pasted link, removed. */
        fun tidyLink(raw: String): String = raw
            .replace(Regex("[\\u2010-\\u2015\\u2212\\uFE58\\uFE63\\uFF0D]"), "-")
            .replace(Regex("[\\u200B-\\u200D\\u2060\\uFEFF\\u00A0\\s]"), "")

        /** Username / password from a pasted get.php link. */
        fun credentialsFromLink(raw: String): Pair<String, String>? {
            val query = raw.substringAfter('?', "")
            if (query.isEmpty()) return null
            val params = query.split('&').associate {
                it.substringBefore('=').lowercase() to runCatching {
                    java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
                }.getOrDefault(it.substringAfter('=', ""))
            }
            val user = params["username"].orEmpty()
            val pass = params["password"].orEmpty()
            return if (user.isNotBlank() && pass.isNotBlank()) user to pass else null
        }

        fun httpError(code: Int): String = when (code) {
            401, 403 -> "HTTP $code: the provider refused the request (wrong login, or it blocks this app)"
            404 -> "HTTP 404: nothing at that address (check the server, port and link)"
            429 -> "HTTP 429: too many requests, try again in a few minutes"
            456, 458, 461 -> "HTTP $code: the provider refused this login for now (too many connections, expired, or blocked)"
            502, 503, 504, 520, 521, 522, 523, 524 -> "HTTP $code: the provider's server didn't respond, try again later"
            else -> "HTTP $code"
        }
    }
}
