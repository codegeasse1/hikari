package com.hikari.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reader for JSON channel playlists — the links users paste that are NOT
 * M3U and NOT Stremio manifests:
 *
 *   https://raw.githubusercontent.com/…/Livetv.json      (encrypted channels)
 *   https://raw.githubusercontent.com/…/leaked18+.json  (encrypted share list)
 *   https://…/play.json                                 (playlist config)
 *   any plain JSON array of `{name, url/stream_url, logo, group}` objects
 *
 * Two shapes exist in the wild and both are accepted, encrypted or plain:
 *
 *  1. A JSON ARRAY of channel objects:
 *     `[{"name","title","logo","image","url","stream_url","group",
 *       "working","stream_type","playback_type","key_id","key","cookie"}]`
 *     `stream_url` (else `url`) is what plays; `group` buckets the catalogue;
 *     `key_id`+`key` are hex ClearKey DRM (converted to the base64url the
 *     player feeds media3); `cookie` rides as a Cookie header.
 *  2. A JSON ARRAY of bare strings — share links (Terabox family) or direct
 *     stream URLs, one channel per entry.
 *  3. A JSON OBJECT with `tmdb_api_key` and no channels (e.g. a "TMDB Auto
 *     Scraping Playlist" config): not a playlist — [Parsed.tmdbKey] carries
 *     the key so the caller can save it instead of listing nothing.
 *
 * Some authors publish these XOR-encrypted with the ASCII text lines prefixed
 * `TERAPLAY_ENC:` (base64 of the plaintext XOR a 22-byte repeating key).
 * [maybeDecrypt] reverses that per element; plain JSON passes through, so
 * both publish styles of the same list work from the same URL.
 */
object JsonPlaylist {

    const val PREFIX = "TERAPLAY_ENC:"

    /** The repeating XOR key those playlists are encrypted with. Obfuscation,
     *  not a secret (it ships inside every reader, including this one). */
    private const val XOR_KEY = "PlyonSecretKey2026!@#$"

    /** Terabox-family share hosts — resolved at play time, never handed to
     *  the player raw (see [com.hikari.app.data.NetworkStream]). */
    private val BOX_HOSTS = listOf(
        "terabox", "1024tera", "4funbox", "momerybox", "tibibox",
        "nepnepbox", "telebox", "mirrobox", "shibabox", "teraboxapp",
        "freeterabox", "terashare", "tbx",
    )

    data class Parsed(
        val channels: List<IptvChannel>,
        /** Present when the file is a playlist CONFIG, not channels. */
        val tmdbKey: String? = null,
        val label: String? = null,
    )

    /** True when [text] is a JSON playlist (or its encrypted form). */
    fun isJsonPlaylist(text: String): Boolean {
        val t = maybeDecrypt(text).trim()
        return t.startsWith("[") || t.startsWith("{")
    }

    /** True when the FILE behind this text is a Stremio addon, not a
     *  playlist — the caller must route it to Extensions → Add Stremio. */
    fun looksLikeStremioManifest(text: String): Boolean {
        val o = runCatching { JSONObject(maybeDecrypt(text)) }.getOrNull() ?: return false
        if (!o.has("streams") && !o.has("catalogs") && !o.has("resources")) return false
        return o.has("id") || o.has("version") || o.has("catalogs")
    }

    /** Decrypts one `TERAPLAY_ENC:` element, else returns it unchanged. */
    fun decryptElement(element: String): String {
        val t = element.trim()
        if (!t.startsWith(PREFIX)) return t
        val b64 = t.removePrefix(PREFIX).trim().replace("\\s".toRegex(), "")
        val bytes = runCatching {
            android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        }.getOrNull() ?: return t
        val key = XOR_KEY.toByteArray(Charsets.UTF_8)
        val plain = ByteArray(bytes.size) { i -> (bytes[i].toInt() xor key[i % key.size].toInt()).toByte() }
        return runCatching { String(plain, Charsets.UTF_8) }.getOrDefault(t)
    }

    /** Whole-file decrypt: a bare encrypted blob, or a JSON array whose
     *  STRING elements are each encrypted (objects pass through). Anything
     *  else is returned as-is. */
    fun maybeDecrypt(text: String): String {
        val t = text.trim()
        if (t.startsWith(PREFIX)) return decryptElement(t)
        if (!t.startsWith("[")) return t
        val arr = runCatching { JSONArray(t) }.getOrNull() ?: return t
        var changed = false
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "\u0000")
            if (s != "\u0000" && s.trim().startsWith(PREFIX)) {
                out.put(decryptElement(s)); changed = true
            } else {
                out.put(arr.opt(i))
            }
        }
        return if (changed) out.toString() else t
    }

    /** Parses decrypted-or-plain JSON text into channels. Null when it is not
     *  a channel list at all (the caller falls back to M3U). */
    fun parse(text: String): Parsed? {
        val t = maybeDecrypt(text).trim()
        if (t.startsWith("[")) return parseArray(runCatching { JSONArray(t) }.getOrNull() ?: return null)
        if (t.startsWith("{")) return parseObject(runCatching { JSONObject(t) }.getOrNull() ?: return null)
        return null
    }

    private fun parseArray(arr: JSONArray): Parsed? {
        if (arr.length() == 0) return null
        // All-strings → share/stream link list.
        var allStrings = true
        for (i in 0 until arr.length()) {
            if (arr.opt(i) !is String) { allStrings = false; break }
        }
        if (allStrings) {
            val out = ArrayList<IptvChannel>()
            for (i in 0 until arr.length()) {
                val u = arr.optString(i).trim()
                if (u.isBlank() || (!u.startsWith("http://") && !u.startsWith("https://"))) continue
                out += linkChannel(u, i + 1)
            }
            return if (out.isEmpty()) null else Parsed(out)
        }
        val out = ArrayList<IptvChannel>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            channelOf(o)?.let { out += it }
        }
        return if (out.isEmpty()) null else Parsed(out)
    }

    private fun parseObject(o: JSONObject): Parsed? {
        channelOf(o)?.let { return Parsed(listOf(it)) }
        val key = o.optString("tmdb_api_key").trim()
            .ifBlank { o.optString("tmdbApiKey").trim() }
        if (key.isNotBlank()) {
            return Parsed(emptyList(), tmdbKey = key, label = o.optString("name").trim().ifBlank { null })
        }
        return null
    }

    private fun linkChannel(url: String, n: Int): IptvChannel {
        val host = hostOf(url)
        val box = BOX_HOSTS.any { host.contains(it) }
        val name = url.substringBefore('?').trimEnd('/').substringAfterLast('/')
            .substringBeforeLast('.').ifBlank { "Video $n" }
        return IptvChannel(
            name = name,
            url = url,
            group = if (box) "Shares" else "Links",
            kind = if (box) IptvChannel.KIND_TERABOX else IptvChannel.KIND_DIRECT,
        )
    }

    /** One channel object → [IptvChannel]. Null when it names nothing to
     *  play (a header/separator row some panels emit). Entries explicitly
     *  marked `"working": false` are skipped — the list flags its own dead. */
    fun channelOf(o: JSONObject): IptvChannel? {
        if (o.optBoolean("working", true) == false && o.has("working")) return null
        val name = o.optString("name").trim()
            .ifBlank { o.optString("title").trim() }
            .ifBlank { o.optString("channel").trim() }
        val stream = o.optString("stream_url").trim()
            .ifBlank { o.optString("streamUrl").trim() }
        var page = o.optString("url").trim()
            .ifBlank { o.optString("link").trim() }
            .ifBlank { o.optString("file").trim() }
        val logo = o.optString("logo").trim()
            .ifBlank { o.optString("image").trim() }
            .ifBlank { o.optString("tvg-logo").trim() }
            .ifBlank { o.optString("icon").trim() }
            .ifBlank { null }
        val group = o.optString("group").trim()
            .ifBlank { o.optString("group-title").trim() }
            .ifBlank { o.optString("category").trim() }
        val playback = o.optString("playback_type").trim().lowercase()
            .ifBlank { o.optString("playbackType").trim().lowercase() }
        val stype = o.optString("stream_type").trim().lowercase()
            .ifBlank { o.optString("streamType").trim().lowercase() }
        var url = stream.ifBlank { page }
        if (url.isBlank() || (!url.startsWith("http://") && !url.startsWith("https://"))) return null
        if (name.isBlank() && !url.startsWith("http")) return null
        val host = hostOf(url)
        val box = BOX_HOSTS.any { host.contains(it) }
        val embed = !box && (playback == "embed" || stype == "embed" ||
            host.contains("aioplayer") || url.contains("embed", ignoreCase = true))
        // An embed entry's playable address is its PAGE (the `url` field);
        // the `stream_url` of one is usually the same page or empty.
        if (embed && page.isNotBlank() && (page.startsWith("http://") || page.startsWith("https://"))) {
            url = page
        }
        val cookie = o.optString("cookie").trim().ifBlank { null }
        val (kid, key) = clearKey(o.optString("key_id"), o.optString("key"))
        return IptvChannel(
            name = name.ifBlank {
                url.substringBefore('?').trimEnd('/').substringAfterLast('/').ifBlank { "Channel" }
            },
            url = url,
            logo = logo,
            group = group,
            headers = emptyMap(),
            drmKid = kid,
            drmKey = key,
            cookie = cookie,
            kind = when {
                box -> IptvChannel.KIND_TERABOX
                embed -> IptvChannel.KIND_EMBED
                else -> IptvChannel.KIND_DIRECT
            },
        )
    }

    /** Hex ClearKey (`key_id` 32 hex, `key` 64 hex) → the base64url the
     *  player feeds media3. Already-encoded values pass through untouched. */
    private fun clearKey(kidRaw: String, keyRaw: String): Pair<String?, String?> {
        val kid = kidRaw.trim().ifBlank { return null to null }
        val key = keyRaw.trim().ifBlank { return null to null }
        return hexToB64Url(kid) to hexToB64Url(key)
    }

    private fun hexToB64Url(v: String): String {
        val t = v.trim().replace("\\s".toRegex(), "")
        val isHex = t.length % 2 == 0 && t.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        if (!isHex) return t
        return try {
            val bytes = ByteArray(t.length / 2) { i ->
                t.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
        } catch (e: Throwable) {
            t
        }
    }

    private fun hostOf(url: String): String {
        val t = url.trim().lowercase()
        val i = t.indexOf("://")
        if (i < 0) return ""
        return t.substring(i + 3).substringBefore('/').substringBefore('?').substringBefore('#')
    }
}
