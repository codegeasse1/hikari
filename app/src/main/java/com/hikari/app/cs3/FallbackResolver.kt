package com.hikari.app.cs3

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.hikari.app.data.StreamSource
import com.hikari.app.data.SubtitleSource
import com.hikari.app.net.Http
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min

/**
 * Self-contained fallback extraction engine.
 *
 * CloudStream's own extractors live in cloudstream3.jar and are driven through
 * `loadExtractor()`, but plugins' `loadLinks()` often return "true" while
 * producing zero links (a matching extractor that silently failed), or the
 * plugin's own regex fallbacks can't decode packed player configs. This engine
 * replicates the core of CloudStream's extraction — P.A.C.K.E.R. JS unpacking,
 * JWPlayer `sources:`/m3u8 parsing, the dood `pass_md5` dance — INDEPENDENTLY
 * of the jar, so a broken/mismatched extractor can never leave the user with
 * "no playable sources" while the same video plays in CloudStream.
 *
 * Strategy per embed page (highest coverage first):
 *  1. fetch the embed page with the video page as Referer,
 *  2. unpack any packed JS configs and scan the decoded text for m3u8/mp4,
 *  3. run the dood `pass_md5` dance for dood-style hosts,
 *  4. as a last resort, hand the URL to the jar's full extractor registry
 *     (which now includes HikariExtractorRegistry's aliases).
 */
object FallbackResolver {

    private val PACKED_REGEX = Regex(
        """eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*d\s*\)\s*\{[\s\S]*?return p\}\s*\(\s*'([\s\S]*?)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'([\s\S]*?)'\.split\s*\(\s*'\s*\|\s*'\s*\)\s*\)\s*\)""",
        RegexOption.IGNORE_CASE
    )

    private val M3U8_RE = Regex("""https?://[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
    private val TXT_RE = Regex("""https?://[^\s"'<>\\]+?/master\.txt[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
    private val MP4_RE = Regex("""https?://[^\s"'<>\\]+?\.mp4[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
    private val QUOTED_RE = Regex(
        """(?:file|src|url|source|video_url|playlist_url|hls\d?)\s*[:=]\s*["']([^"'\s<>]+\.(?:m3u8|mp4|txt)[^"'\s<>]*)["']""",
        RegexOption.IGNORE_CASE
    )
    private val ALL_URL_RE = Regex("""https?://[^\s"'<>{}\\]{6,}""", RegexOption.IGNORE_CASE)

    private val ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    private val JUNK = listOf(
        ".js", ".css", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp", ".ico",
        "ads", "advert", "banner", "vast", "tracker", "pixel", "analytics",
        "javascript:", "blob:", "data:image"
    )

    private data class RawStream(
        val url: String,
        val referer: String,
        val name: String,
        val quality: Int,
        val isM3u8: Boolean,
    )

    /** Resolve every embed on a plugin video page into playable StreamSources.
     *  Runs BOTH our own scraping AND the jar's full extractor registry — the
     *  same `loadExtractor` every CloudStream plugin delegates to — on every
     *  embed it finds, so the resulting server list matches CloudStream's
     *  regardless of which plugin produced the page or whether that plugin's
     *  own resolver for a host (Rumble, ok.ru, dailymotion…) is broken. */
    suspend fun resolve(pageUrl: String): List<StreamSource> {
        val html = runCatching {
            app.get(pageUrl, referer = pageUrl).text
        }.getOrNull() ?: return emptyList()

        val raws = LinkedHashMap<String, RawStream>()
        val subs = mutableListOf<SubtitleSource>()

        for (embed in collectEmbeds(html, pageUrl).take(12)) {
            runCatching {
                withTimeoutOrNull(14_000) { resolveEmbed(embed, pageUrl, raws, subs) }
            }
            if (raws.size >= 12) break
        }

        // Some pages embed the stream directly (no iframes) — scan the video
        // page itself too, and let the jar registry have a crack at it.
        if (raws.isEmpty()) {
            val text = getAndUnpack(html)
            scanForUrls(text, pageUrl, raws)
            if (raws.isNotEmpty()) {
                runCatching { subs += scanSubtitles(text) }
            } else {
                runCatching {
                    withTimeoutOrNull(10_000) {
                        loadExtractor(pageUrl, pageUrl, { }, { addRaw(it, pageUrl, raws) })
                    }
                }
            }
        }

        return raws.values.map { r ->
            StreamSource(
                name = r.name,
                url = r.url,
                headers = mapOf("Referer" to r.referer, "User-Agent" to Http.UA),
                subtitles = subs.distinctBy { it.url },
                isM3u8 = r.isM3u8 || r.url.contains(".m3u8", true) || r.url.contains("master.txt", true),
            )
        }
    }

    private suspend fun resolveEmbed(
        embedUrl: String,
        pageUrl: String,
        raws: MutableMap<String, RawStream>,
        subs: MutableList<SubtitleSource>,
    ) {
        val html = runCatching { app.get(embedUrl, referer = pageUrl).text }.getOrNull()
        if (html.isNullOrBlank()) return

        val text = getAndUnpack(html)
        scanForUrls(text, embedUrl, raws)
        runCatching { subs += scanSubtitles(text) }

        if (isDoodHost(embedUrl)) {
            runCatching { doodExtract(embedUrl, raws) }
        }
        if (isRumbleUrl(embedUrl)) {
            runCatching { rumbleExtract(embedUrl, raws) }
        }

        // The full jar registry (incl. aliases) — CloudStream plugins get their
        // OkRuSSL/Dailymotion/… servers through this exact call, so we always
        // run it instead of only as a last resort. A broken plugin-side
        // resolver for a host can then never lose a server the jar knows.
        runCatching {
            withTimeoutOrNull(12_000) {
                loadExtractor(embedUrl, pageUrl, { }, { addRaw(it, pageUrl, raws) })
            }
        }
    }

    private fun getAndUnpack(html: String): String {
        val unpacked = unpackPacked(html)
        if (!unpacked.isNullOrEmpty()) return unpacked
        return html.replace("\\/", "/").replace("\\u002F", "/")
    }

    private fun scanForUrls(text: String, referer: String, raws: MutableMap<String, RawStream>) {
        for (m in M3U8_RE.findAll(text)) addRaw(m.value, referer, streamNameFor(m.value), raws)
        for (m in TXT_RE.findAll(text)) addRaw(m.value, referer, streamNameFor(m.value), raws)
        for (m in MP4_RE.findAll(text)) addRaw(m.value, referer, streamNameFor(m.value), raws)
        for (m in QUOTED_RE.findAll(text)) addRaw(m.groupValues[1], referer, streamNameFor(m.groupValues[1]), raws)
        if (raws.size < 10) probeCandidates(text, referer, raws)
    }

    /**
     * Many anime/NSFW CDNs serve extensionless HLS (the playlist URL has no
     * `.m3u8` suffix — the CDN sniffs the request). URL patterns can't catch
     * those, so probe the most promising http(s) URLs in the page text and
     * keep the ones that actually answer with `#EXTM3U` / `#EXT-X` (or an
     * MP4 box header).
     */
    private fun probeCandidates(text: String, referer: String, raws: MutableMap<String, RawStream>) {
        val candidates = ALL_URL_RE.findAll(text)
            .map { it.value.replace("\\/", "/").trim().trimEnd('"', '\'', ',', ';', ')', '}') }
            .filter { it.startsWith("http") && !JUNK.any { j -> it.contains(j, ignoreCase = true) } }
            .filter { u ->
                // Skip URLs we already found via patterns, and known non-video
                // file types.
                !raws.containsKey(u) &&
                    !Regex("\\.(js|css|png|jpg|jpeg|gif|svg|webp|ico|json|xml|html?)(\\?.*)?$", RegexOption.IGNORE_CASE)
                        .containsMatchIn(u)
            }
            .distinct()
            .take(8)
        for (u in candidates) {
            val bytes = runCatching {
                Http.getBytes(u, mapOf("Range" to "bytes=0-511", "Referer" to referer, "User-Agent" to Http.UA))
            }.getOrNull() ?: continue
            if (bytes.isEmpty()) continue
            val head = String(bytes, 0, min(bytes.size, 512), Charsets.ISO_8859_1)
            val isStream = head.startsWith("#EXTM3U") || head.contains("#EXT-X") ||
                head.contains("<mpd", ignoreCase = true) || looksLikeMp4(bytes)
            if (isStream) addRaw(u, referer, streamNameFor(u), raws)
        }
    }

    private fun looksLikeMp4(b: ByteArray): Boolean {
        if (b.size < 12) return false
        val head = String(b, 4, min(8, b.size - 4), Charsets.ISO_8859_1)
        return head.startsWith("ftyp") || head.startsWith("moov") || head.startsWith("mdat") ||
            head.startsWith("styp")
    }

    private fun addRaw(
        raw: String,
        referer: String,
        name: String,
        raws: MutableMap<String, RawStream>,
    ) {
        val u = cleanUrl(raw) ?: return
        raws.putIfAbsent(
            u,
            RawStream(u, referer, name, Qualities.Unknown.value, u.contains(".m3u8", true))
        )
    }

    private fun addRaw(
        l: com.lagradost.cloudstream3.utils.ExtractorLink,
        pageUrl: String,
        raws: MutableMap<String, RawStream>,
    ) {
        val u = cleanUrl(l.url) ?: return
        val q = Qualities.getStringByInt(l.quality)
        val base = l.name.ifBlank { streamNameFor(u) }
        val name = if (q.isNotBlank() && !base.contains(q, ignoreCase = true)) "$base $q" else base
        raws.putIfAbsent(
            u,
            RawStream(u, l.referer?.takeIf { it.isNotBlank() } ?: pageUrl, name, l.quality, l.isM3u8)
        )
    }

    /** Human-readable server name for a bare stream URL (the jar's extractors
     *  name their own links; scanned URLs get a host-based name that matches
     *  what CloudStream shows in its source picker). */
    private fun streamNameFor(url: String): String {
        val h = url.lowercase()
        return when {
            h.contains("rumble") || h.contains("rmbl.ws") || h.contains("rumble.cloud") -> "Rumble"
            h.contains("ok.ru") || h.contains("odnoklassniki") || h.contains("okcdn") -> "OkRuSSL"
            h.contains("dailymotion") || h.contains("dai.ly") -> "Dailymotion"
            h.contains("dood") || h.contains("playmogo") || h.contains("ds2play") || h.contains("doood") ||
                h.contains("d000") || h.contains("doods") || h.contains("myvidplay") || h.contains("vide0") ||
                h.contains("dsvplay") -> "Dood"
            else -> "Hikari Auto"
        }
    }

    private fun isRumbleUrl(url: String): Boolean =
        url.lowercase().contains("rumble")

    private fun scanSubtitles(text: String): List<SubtitleSource> {
        val out = mutableListOf<SubtitleSource>()
        val re = Regex("""(?:file|src|url)\s*[:=]\s*["']([^"'\s<>]+\.(?:vtt|srt|ass|ssa)[^"'\s<>]*)["']""", RegexOption.IGNORE_CASE)
        val seen = HashSet<String>()
        for (m in re.findAll(text)) {
            val u = cleanUrl(m.groupValues[1]) ?: continue
            if (seen.add(u)) out.add(SubtitleSource("Sub", u))
        }
        return out
    }

    private fun cleanUrl(raw: String): String? {
        val u = raw.replace("\\/", "/").replace("\\u002F", "/")
            .trim().trimEnd('"', '\'', ',', ';', ')', '}')
        if (!u.startsWith("http://") && !u.startsWith("https://")) return null
        if (JUNK.any { u.contains(it, ignoreCase = true) }) return null
        return u
    }

    // ------------------------------------------------------------------
    //  P.A.C.K.E.R. unpacker (classic jsunpack algorithm, reimplemented)
    // ------------------------------------------------------------------
    private fun unpackPacked(input: String): String? {
        val m = PACKED_REGEX.find(input) ?: return null
        val p = m.groupValues[1]
        val a = m.groupValues[2].toIntOrNull() ?: 36
        val k = m.groupValues[4].split("|")
        if (k.isEmpty()) return null

        fun b36(c: Int): String {
            val div = c / a
            val rem = c % a
            val r = if (rem > 35) ((rem + 29).toChar()).toString() else rem.toString(36)
            return (if (div == 0) "" else b36(div)) + r
        }

        var out = p
        for (i in k.indices.reversed()) {
            val word = k[i]
            if (word.isEmpty()) continue
            out = out.replace(Regex("\\b" + Regex.escape(b36(i)) + "\\b"), word)
        }
        return out
    }

    // ------------------------------------------------------------------
    //  dood `pass_md5` dance (same as CloudStream's DoodLaExtractor)
    // ------------------------------------------------------------------
    private fun isDoodHost(url: String): Boolean {
        val h = url.lowercase()
        return h.contains("dood") || h.contains("playmogo") || h.contains("ds2play") ||
            h.contains("doood") || h.contains("d000") || h.contains("doods") ||
            h.contains("myvidplay") || h.contains("vide0") || h.contains("dsvplay")
    }

    private suspend fun doodExtract(embedUrl: String, raws: MutableMap<String, RawStream>) {
        val embed = embedUrl.replace("/d/", "/e/")
        val req = runCatching { app.get(embed) }.getOrNull() ?: return
        val host = baseOf(req.url)
        val path = Regex("""/pass_md5/[^']*""").find(req.text)?.value ?: return
        val md5 = runCatching { app.get(host + path, referer = req.url).text.trim() }.getOrNull()
        if (md5.isNullOrEmpty()) return
        val token = path.substringAfterLast("/")
        val finalUrl = if (md5.startsWith("http")) {
            md5 + "?token=" + token
        } else {
            host + "/" + md5 + random10() + "?token=" + token
        }
        // Confirm it really serves HLS before emitting (dood can answer with
        // junk/redirect pages when the CDN is unhappy).
        val bytes = runCatching {
            Http.getBytes(finalUrl, mapOf("Referer" to "$host/", "User-Agent" to Http.UA))
        }.getOrNull()
        if (bytes != null && bytes.isNotEmpty()) {
            val head = String(bytes, 0, min(bytes.size, 64), Charsets.ISO_8859_1)
            if (head.startsWith("#EXTM3U")) {
                addRaw(finalUrl, "$host/", "Dood", raws)
            }
        }
    }

    /**
     * Rumble embeds (rumble.com/embed/… and rumble.com/v/…). The jar has no
     * Rumble extractor, so plugins either scrape the embed page themselves
     * (and break when their regex misses) or can't offer Rumble at all — which
     * is why Rumble servers show up in CloudStream but not in Hikari. This is
     * core-level: fetch with the rumble referer and find the HLS/mp4 URLs in
     * the player config, exactly like CloudStream's rumble sources do.
     */
    private suspend fun rumbleExtract(embedUrl: String, raws: MutableMap<String, RawStream>) {
        val html = runCatching {
            app.get(embedUrl, referer = "https://rumble.com/").text
        }.getOrNull() ?: return
        val text = getAndUnpack(html)
        val m3u8 = Regex("""\"hls\"\s*:\s*\{[^}]*\"url\"\s*:\s*\"([^\"]+\.m3u8[^\"]*)\"""").find(text)?.groupValues?.get(1)
            ?: Regex("""https?://rumble\.com/hls-vod/[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(text)?.value
            ?: Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(text)?.value
        if (m3u8 != null && m3u8.startsWith("http")) {
            addRaw(m3u8, "https://rumble.com/", "Rumble", raws)
        }
        if (raws.isEmpty()) {
            // mp4 fallback: the config has {"mp4":{"1080":["https://…mp4"],…}}
            for (m in Regex("""\"mp4\"\s*:\s*\{\s*\"\d+\"\s*:\s*\[\s*\"([^\"]+\.mp4[^\"]*)\"""").findAll(text)) {
                addRaw(m.groupValues[1], "https://rumble.com/", "Rumble", raws)
            }
            if (raws.isEmpty()) scanForUrls(text, "https://rumble.com/", raws)
        }
    }

    private fun baseOf(url: String): String {
        val u = url.substringBefore("?")
        val m = Regex("""(https?://[^/]+)""").find(u) ?: return url
        return m.groupValues[1]
    }

    private fun random10(): String = buildString {
        repeat(10) { append(ALNUM.random()) }
    }

    // ------------------------------------------------------------------
    //  embed collection (mirrors LeakPornerProvider.loadLinks scraping)
    // ------------------------------------------------------------------
    private fun collectEmbeds(html: String, pageUrl: String): List<String> {
        val raw = LinkedHashSet<String>()
        for (m in Regex("""data-embed=["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(html)) {
            raw.add(m.groupValues[1])
        }
        for (m in Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(html)) {
            raw.add(m.groupValues[1])
        }
        for (m in Regex("""<iframe[^>]+data-src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(html)) {
            raw.add(m.groupValues[1])
        }
        // base64-encoded iframe srcs
        for (m in Regex("""(?:data-embed|value)=["']([A-Za-z0-9+/=]{40,})["']""").findAll(html)) {
            runCatching {
                val decoded = String(android.util.Base64.decode(m.groupValues[1], android.util.Base64.DEFAULT))
                Regex("""src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(decoded)?.groupValues?.get(1)?.let { raw.add(it) }
            }
        }
        return raw
            .map { fixUrl(it, pageUrl) }
            .filter { it.startsWith("http") && !it.contains("blob:", ignoreCase = true) }
    }

    private fun fixUrl(url: String, pageUrl: String): String {
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> baseOf(pageUrl) + url
            else -> url
        }
    }
}
