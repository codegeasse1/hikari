package com.hikari.app.cs3

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.hikari.app.data.StreamSource
import com.hikari.app.data.SubtitleSource
import com.hikari.app.net.Http
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.Jsoup
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
        // The synchronized views keep the parallel embed resolves safe to
        // update from multiple coroutines at once.
        val rawsSync = java.util.Collections.synchronizedMap(raws)
        val subsSync = java.util.Collections.synchronizedList(subs)

        // Embeds resolve IN PARALLEL (CloudStream fires all its extractors at
        // once too) — the old sequential loop could take 12 × 14s = 3 minutes
        // of nothing before the player opened. Now the slowest single embed
        // bounds the whole pass.
        val embeds = collectEmbeds(html, pageUrl).take(12)
        val needsMegaPlay = isMegaPlayPage(pageUrl)
        if (embeds.isNotEmpty() || needsMegaPlay) {
            runCatching {
                withTimeoutOrNull(18_000) {
                    coroutineScope {
                        val jobs = embeds.map { embed ->
                            async {
                                runCatching {
                                    withTimeoutOrNull(12_000) {
                                        resolveEmbed(embed, pageUrl, rawsSync, subsSync)
                                    }
                                }
                            }
                        }
                        // Anikoto & friends resolve their servers via AJAX (not
                        // iframes) — replicate the getSources dance in parallel
                        // so a stalled plugin can never leave the user empty.
                        val megaJobs = if (needsMegaPlay) {
                            listOf(async {
                                runCatching {
                                    withTimeoutOrNull(10_000) {
                                        megaPlayFromPage(pageUrl, rawsSync, subsSync)
                                    }
                                }
                            })
                        } else emptyList()
                        (jobs + megaJobs).forEach { it.await() }
                    }
                }
            }
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
                    withTimeoutOrNull(7_000) {
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
        // LuluStream (luluvdo.com & friends) expects its CDN requests to carry
        // the site ROOT as Referer (CloudStream's LuluStream uses mainUrl) — a
        // full embed URL can make the CDN's hotlink check answer 403. All other
        // hosts keep the embed URL as referer, like before.
        val scanReferer = if (isLuluHost(embedUrl)) baseOf(embedUrl) + "/" else embedUrl
        scanForUrls(text, scanReferer, raws)
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
            withTimeoutOrNull(10_000) {
                loadExtractor(embedUrl, pageUrl, { }, { addRaw(it, pageUrl, raws) })
            }
        }
    }

    private fun getAndUnpack(html: String): String {
        val unpacked = unpackPacked(html)
        if (!unpacked.isNullOrEmpty()) return unpacked
        return html.replace("\\/", "/").replace("\\u002F", "/")
    }

    private suspend fun scanForUrls(text: String, referer: String, raws: MutableMap<String, RawStream>) {
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
    private suspend fun probeCandidates(text: String, referer: String, raws: MutableMap<String, RawStream>) {
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
            // One slow host must not stall the whole embed resolve — each probe
            // gets a short budget (3s) and the rest just gets skipped.
            val bytes = runCatching {
                kotlinx.coroutines.withTimeoutOrNull(3_000) {
                    Http.getBytes(u, mapOf("Range" to "bytes=0-511", "Referer" to referer, "User-Agent" to Http.UA))
                }
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
            // The StreamHG/StreamGG CDN network (hgcloud.to, vidhidepro.com,
            // cavanhabg.com, tryzendm.com, hqq/playhq players, …). TamilBlasters
            // & friends sign their playlists through these; naming them properly
            // keeps fallback rows readable instead of a generic "Hikari Auto".
            h.contains("streamhg") || h.contains("streamgg") || h.contains("hgcloud") ||
                h.contains("vidhidepro") || h.contains("cavanhabg") || h.contains("tryzendm") ||
                h.contains("hqq") || h.contains("playhq") || h.contains("playx") -> "StreamHG"
            h.contains("luluvdo") || h.contains("lulustream") || h.contains("lulucdn") ||
                h.contains("tnmr.org") || h.contains("kinoger.pw") -> "LuluStream"
            else -> "Hikari Auto"
        }
    }

    private fun isLuluHost(url: String): Boolean {
        val h = url.lowercase()
        return h.contains("luluvdo") || h.contains("lulustream") ||
            h.contains("lulucdn") || h.contains("kinoger.pw")
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
    //  megaplay family (megaplay.buzz / megacloud / rapid-cloud / vidplay…)
    //  — the `getSources` AJAX dance anime plugins use. The jar ships NO
    //  megaplay extractor and plugins' own resolver can stall or 403 in-app,
    //  so this core fallback hits the same endpoints with the same XHR
    //  headers — "no source available" in Hikari while CloudStream plays.
    // ------------------------------------------------------------------
    private fun isMegaPlayPage(pageUrl: String): Boolean {
        val lower = pageUrl.lowercase()
        if (lower.contains("anikoto")) return true
        return Regex("""[?&]embed=([^&]+)""").containsMatchIn(pageUrl) &&
            Regex("""[?&]ep=(\d+)""").containsMatchIn(pageUrl)
    }

    private fun isMegaPlayHost(url: String): Boolean {
        val h = url.lowercase()
        return h.contains("megaplay") || h.contains("megacloud") ||
            h.contains("rapid-cloud") || h.contains("vidplay") ||
            h.contains("vidtube") || h.contains("vidwish") ||
            h.contains("mikora") || h.contains("watching.onl") ||
            h.contains("shiora")
    }

    /**
     * Anikoto-style episode pages: the megaplay fast path straight from the
     * api's `embed` id, plus the site's own ajax server list (`ids` param →
     * `/ajax/server/list` → `/ajax/server?get=` → megaplay embeds). Mirrors
     * AnikotoProvider.loadLinks so the fallback engine yields the SAME servers
     * as CloudStream even when the plugin's own resolver comes up empty.
     */
    private suspend fun megaPlayFromPage(
        pageUrl: String,
        raws: MutableMap<String, RawStream>,
        subs: MutableList<SubtitleSource>,
    ) {
        val embedId = Regex("""[?&]embed=([^&]+)""").find(pageUrl)?.groupValues?.get(1)?.trim().orEmpty()
        val lang = Regex("""[?&]lang=(sub|dub)""").find(pageUrl)?.groupValues?.get(1) ?: "sub"
        if (embedId.isNotBlank()) {
            megaPlayEmbedExtract("https://megaplay.buzz/stream/s-2/$embedId/$lang", raws, subs)
        }

        val siteIds = Regex("""[?&]ids=([^&]+)""").find(pageUrl)?.groupValues?.get(1)?.trim().orEmpty()
        val watchUrl = pageUrl.substringBefore("?")
        if (siteIds.isBlank()) return
        val base = baseOf(watchUrl)
        val ajax = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "Referer" to watchUrl,
        )
        val serverList = runCatching {
            app.get("$base/ajax/server/list?servers=$siteIds", headers = ajax).text
        }.getOrNull() ?: return
        val doc = runCatching { Jsoup.parse(parseAjaxResult(serverList)) }.getOrNull() ?: return
        val linkIds = doc.select("li[data-link-id], li[data-id]").mapNotNull { li ->
            li.attr("data-link-id").ifBlank { li.attr("data-id") }.takeIf { it.isNotBlank() }
        }
        if (linkIds.isEmpty()) return
        // Resolve every server's embed IN PARALLEL (the old sequential loop
        // added ~1s per server — a 4-server page could stall the player for
        // 10s while sources sat one request away).
        runCatching {
            withTimeoutOrNull(9_000) {
                coroutineScope {
                    linkIds.map { linkId ->
                        async {
                            runCatching {
                                withTimeoutOrNull(7_000) {
                                    val serverHtml = app.get("$base/ajax/server?get=$linkId", headers = ajax).text
                                    val text = parseAjaxResult(serverHtml)
                                    val embedUrl = Regex("""https?://[^\s"'<>\\]{8,}""").findAll(text)
                                        .map { it.value.replace("\\/", "/") }
                                        .firstOrNull { isMegaPlayHost(it) }
                                    if (embedUrl != null) {
                                        megaPlayEmbedExtract(embedUrl, raws, subs)
                                    }
                                }
                            }
                        }
                    }.forEach { it.await() }
                }
            }
        }
    }

    /**
     * The getSources dance: GET {host}/stream/getSources?id={epId} with the
     * XHR header megaplay requires (a plain fetch answers 403 "AJAX requests
     * only"). Emits the JSON `sources` as MegaPlay servers + `tracks` subs.
     */
    private suspend fun megaPlayEmbedExtract(
        embedUrl: String,
        raws: MutableMap<String, RawStream>,
        subs: MutableList<SubtitleSource>,
    ) {
        val clean = embedUrl.replace("\\/", "/")
        val host = runCatching { java.net.URI(clean).host }.getOrNull() ?: return
        val epId = Regex("""/stream/s-\d+/(\d+)""").find(clean)?.groupValues?.get(1)
            ?: Regex("""[?&]id=(\d+)""").find(clean)?.groupValues?.get(1)
            ?: return
        val headers = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to "https://$host/",
            "Origin" to "https://$host",
            "Accept" to "application/json, text/javascript, */*; q=0.01",
        )
        val text = runCatching {
            app.get("https://$host/stream/getSources?id=$epId", headers = headers).text
        }.getOrNull() ?: return
        if (text.isBlank() || text.contains("Forbidden")) return
        val ref = "https://$host/"

        runCatching {
            val j = JSONObject(text)
            val tracks = j.optJSONArray("tracks")
            if (tracks != null) {
                for (i in 0 until tracks.length()) {
                    val t = tracks.optJSONObject(i) ?: continue
                    val subUrl = t.optString("file")
                    if (subUrl.isBlank() || !subUrl.startsWith("http")) continue
                    subs.add(
                        SubtitleSource(
                            t.optString("label", "English"),
                            subUrl.replace("\\/", "/")
                        )
                    )
                }
            }
            val sources = j.optJSONArray("sources")
            if (sources != null) {
                for (i in 0 until sources.length()) {
                    val s = sources.optJSONObject(i) ?: continue
                    val file = s.optString("file")
                    if (file.isBlank() || !file.startsWith("http")) continue
                    val label = s.optString("label").ifBlank { s.optString("quality") }.ifBlank { "HD" }
                    addRaw(file.replace("\\/", "/"), ref, "MegaPlay $label", raws)
                }
            }
        }

        // Not JSON / empty arrays — scan the raw response instead.
        if (raws.isEmpty()) {
            for (m in Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|mp4)(?:[^\s"'<>\\]*)""").findAll(text)) {
                addRaw(m.value.replace("\\/", "/"), ref, "MegaPlay", raws)
            }
        }
    }

    /** Unwraps the {status, result} ajax wrapper the anime sites use. */
    private fun parseAjaxResult(body: String): String {
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) {
            return try {
                val j = JSONObject(trimmed)
                if (j.optInt("status", 500) == 200) j.optString("result", trimmed) else ""
            } catch (e: Exception) {
                trimmed
            }
        }
        return trimmed
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
        // Many movie sites LINK to their player instead of iframing it —
        // <a href="https://host/file/abc">Watch Now</a>. Follow anchors that
        // look like a player/file URL so a link-only page (movies4u, …) never
        // looks link-free to the fallback engine.
        for (m in Regex("""<a[^>]+href=["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(html)) {
            if (FILE_HOST_LINK_RE.containsMatchIn(m.groupValues[1])) {
                raw.add(m.groupValues[1])
            }
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

    /** Paths that mark an anchor as a player/stream page rather than an
     *  ordinary link (comments, share, tags, …). */
    private val FILE_HOST_LINK_RE = Regex(
        "(/(?:file|embed|e|v|d|stream|play|watch|player)/|play\\.php|player\\.php|watch\\?|\\?v=)",
        RegexOption.IGNORE_CASE
    )

    private fun fixUrl(url: String, pageUrl: String): String {
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> baseOf(pageUrl) + url
            else -> url
        }
    }
}
