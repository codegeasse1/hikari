package com.hikari.app.cs3

import com.hikari.app.data.Logs
import com.hikari.app.data.StreamSource
import com.hikari.app.data.SubtitleSource
import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * YouTube playback, for the addons that offer a video AS a YouTube video.
 *
 * Stremio's stream protocol has three ways to name a playable thing: a direct
 * URL, a torrent (`infoHash`), and `ytId` — a YouTube video id. Plenty of addons
 * use the third for real content (a music video, an old film that lives on
 * YouTube, a channel's own uploads) and for the trailer rows that cover almost
 * every catalogue entry.
 *
 * The CloudStream jar has shipped a YouTube extractor all along
 * (`com.lagradost.cloudstream3.extractors.YoutubeExtractor`), and it is written
 * against NewPipeExtractor — which is also on the classpath (Hikari pins
 * `com.github.teamnewpipe:NewPipeExtractor`). But NewPipeExtractor refuses to do
 * anything at all before `NewPipe.init(Downloader)` has been handed an HTTP
 * implementation, Hikari never called it, and nothing else in the jar does
 * either. So the YouTube path could not have produced a single link here, which
 * is exactly the shape of the report: "in Stremio it plays, in Hikari the same
 * addon says no playable source found".
 *
 * WHY THIS DOES NOT USE THE JAR'S EXTRACTOR, even though it exists: that
 * extractor answers with NewPipe's VIDEO-ONLY streams and attaches the separate
 * audio renditions as `audioTracks`, because CloudStream's player can mux them.
 * Hikari's player has no equivalent (`StreamSource` has no audio-track field),
 * so those links would play as silent video — a worse answer than the honest
 * "nothing found". This resolves the same StreamInfo itself and offers the
 * MUXED formats (progressive HTTP first, then HLS for a live stream, then the
 * full DASH manifest, which carries its own audio representations and is
 * playable by media3). A YouTube source either plays with sound or is not
 * offered.
 */
object YouTubePlayables {

    private const val TAG = "YouTube"

    /** 0 = not tried yet, 1 = NewPipe is usable, 2 = it is not (do not retry). */
    @Volatile
    private var state = 0

    private val initLock = Any()

    /**
     * NewPipe's one-time setup, done lazily so an app that never meets a
     * YouTube row never pays for it — and done under a lock because the
     * resolution pass can run on several providers' coroutines at once, and
     * calling [org.schabi.newpipe.extractor.NewPipe.init] twice throws.
     */
    private fun ensureReady(): Boolean {
        if (state != 0) return state == 1
        return synchronized(initLock) {
            if (state != 0) return@synchronized state == 1
            val ok = runCatching { org.schabi.newpipe.extractor.NewPipe.init(NewPipeHttp) }.isSuccess
            state = if (ok) 1 else 2
            if (!ok) {
                Logs.log(TAG, "✗ NewPipe could not be initialised — YouTube sources will be skipped")
            }
            ok
        }
    }

    /**
     * The HTTP half NewPipeExtractor cannot work without.
     *
     * Deliberately built on Hikari's OWN [Http] stack rather than a fresh
     * OkHttpClient: the app's client already carries the DNS-over-HTTPS
     * fallback, the Cloudflare handling and the connection pool every other
     * request uses — a second client would mean a second DNS answer, a second
     * TLS stack and, on the networks this app is used on, a second set of
     * failures. Responses come back already decompressed (OkHttp asks for gzip
     * and unwraps it), which is what NewPipe's own Android downloaders rely on
     * too.
     */
    private object NewPipeHttp : org.schabi.newpipe.extractor.downloader.Downloader() {

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
            // NewPipe's POSTs are text (YouTube's player request is JSON, its
            // feedback calls are form-encoded), and Http.request takes a String.
            val body = request.dataToSend()?.let { String(it, Charsets.UTF_8) }
            Http.request(request.httpMethod(), url, body, headers, contentType).use { reply ->
                val text = runCatching { reply.body?.string() ?: "" }.getOrDefault("")
                // NewPipe reports a captcha wall as its own exception type, and
                // the caller (and the log) can then say so instead of "empty".
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

    /**
     * The playable links for one YouTube video id, best-format-first. Empty when
     * YouTube has nothing this player can play (see the class note) — an empty
     * answer keeps the original row, so the behaviour is never worse than it was
     * before this existed.
     */
    suspend fun resolve(ytId: String): List<StreamSource> {
        if (ytId.isBlank()) return emptyList()
        return try {
            withContext(Dispatchers.IO) {
                if (!ensureReady()) return@withContext emptyList()
                val info = org.schabi.newpipe.extractor.stream.StreamInfo.getInfo(
                    "https://www.youtube.com/watch?v=" + ytId
                )
                val subs = info.subtitles.orEmpty().mapNotNull { s ->
                    val u = s.url
                    if (u.isNullOrBlank()) null
                    else SubtitleSource(
                        lang = s.displayLanguageName ?: s.languageTag ?: "Unknown",
                        url = u,
                    )
                }.distinctBy { it.url }

                val headers = mapOf("User-Agent" to Http.UA)
                val out = mutableListOf<StreamSource>()
                // `videoStreams` is the MUXED list: each entry already carries
                // its audio, which is the only kind this player can play.
                for (v in info.videoStreams.orEmpty()) {
                    val delivery = v.deliveryMethod
                    val u = when (delivery) {
                        org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP ->
                            v.url ?: v.content
                        org.schabi.newpipe.extractor.stream.DeliveryMethod.HLS ->
                            v.url ?: v.manifestUrl
                        else -> null
                    }
                    if (u.isNullOrBlank()) continue
                    val res = v.resolution.orEmpty()
                        .ifBlank { if (v.height > 0) "${v.height}p" else "" }
                    val resLabel = if (res.isBlank()) "" else " $res"
                    out += StreamSource(
                        name = "YouTube$resLabel",
                        url = u,
                        headers = headers,
                        subtitles = subs,
                        isM3u8 = delivery == org.schabi.newpipe.extractor.stream.DeliveryMethod.HLS ||
                            u.contains(".m3u8", true),
                    )
                }
                // A live stream has no progressive rendition at all — its one
                // address is the HLS manifest.
                if (out.isEmpty() && !info.hlsUrl.isNullOrBlank()) {
                    out += StreamSource(
                        name = "YouTube Live",
                        url = info.hlsUrl!!,
                        headers = headers,
                        subtitles = subs,
                        isM3u8 = true,
                    )
                }
                // Last resort, and only when nothing muxed exists: YouTube's own
                // DASH manifest lists its audio as a separate adaptation set
                // INSIDE the one document, so media3 plays it in sync — unlike a
                // bare video-only URL, which would be silent.
                if (out.isEmpty() && !info.dashMpdUrl.isNullOrBlank()) {
                    out += StreamSource(
                        name = "YouTube DASH",
                        url = info.dashMpdUrl!!,
                        headers = headers,
                        subtitles = subs,
                        isMpd = true,
                    )
                }
                if (out.isEmpty()) {
                    Logs.log(
                        TAG,
                        "no muxed format for $ytId (only video-only renditions, which need a " +
                            "separate audio track this player cannot attach)",
                    )
                } else {
                    Logs.log(TAG, "$ytId → ${out.size} playable format(s): " + out.joinToString(", ") { it.name })
                }
                out
            }
        } catch (t: Throwable) {
            Logs.log(TAG, "✗ $ytId: ${t.javaClass.simpleName}: ${t.message}")
            emptyList()
        }
    }
}

/**
 * The sources that reach the server list as a LINK to a video rather than a
 * video — and used to be dropped one screen later.
 *
 * Two whole classes of Stremio source never played: `ytId` (a YouTube video, see
 * [YouTubePlayables]) and `externalUrl` (the addon's "watch it here" page). Both
 * are perfectly good answers to "give me the video", and
 * `DetailScreen.playableEvery` filters both out before the player sees them, so
 * an addon whose streams are all of those kinds reported servers and then played
 * nothing at all. This pass resolves them, once, at the end of a lookup, and
 * hands the real links on — keeping the original row only when the resolution
 * genuinely found nothing, so the visible result can only ever grow.
 *
 * Bounded on purpose: at most [MAX_ROWS] rows, a per-row ceiling and a whole-pass
 * budget. Resolution does network work (an addon can return eight external
 * links), and the user is waiting on the first server, not on the last one.
 */
object PlayableResolver {

    private const val MAX_ROWS = 6

    private const val PER_ROW_MS = 12_000L

    private const val BUDGET_MS = 20_000L

    /**
     * Returns [sources] with every resolvable link row replaced by the servers
     * it points at. The SAME list is returned (identical instance) when there is
     * nothing to resolve, so the caller can skip re-publishing it.
     */
    suspend fun resolve(sources: List<StreamSource>): List<StreamSource> {
        if (sources.none { it.ytId != null || it.externalUrl }) return sources
        val deadline = System.currentTimeMillis() + BUDGET_MS
        val out = ArrayList<StreamSource>(sources.size)
        var tried = 0
        for (s in sources) {
            if (s.ytId == null && !s.externalUrl) {
                out += s
                continue
            }
            if (tried >= MAX_ROWS || System.currentTimeMillis() >= deadline) {
                out += s
                continue
            }
            tried++
            val found = runCatching {
                kotlinx.coroutines.withTimeoutOrNull(PER_ROW_MS) { resolveOne(s) }
            }.getOrNull().orEmpty()
            if (found.isEmpty()) {
                out += s
                continue
            }
            Logs.log(
                "Search",
                "\"${s.name}\" → ${found.size} playable source(s) " +
                    "(resolved from a ${if (s.ytId != null) "YouTube" else "external"} link)",
            )
            // The resolved links keep the addon's identity (the chooser groups
            // and orders by provider), and the addon's own label is kept in the
            // name when it adds something the format name does not say.
            for (f in found) {
                out += f.copy(
                    name = if (s.name.isBlank() || f.name.contains(s.name, ignoreCase = true)) f.name
                    else "${f.name} · ${s.name}",
                    provider = s.provider,
                    providerId = s.providerId,
                    providerName = s.providerName,
                )
            }
        }
        return out
    }

    private suspend fun resolveOne(s: StreamSource): List<StreamSource> {
        s.ytId?.let { return YouTubePlayables.resolve(it) }
        if (!s.externalUrl || !s.url.startsWith("http")) return emptyList()
        // The same engine every other embed in the app goes through: fetch the
        // page, unpack its player config, scan it for HLS/MP4, run the dood and
        // rumble dances, then hand it to the jar's full extractor registry.
        return FallbackResolver.resolveEmbedUrl(s.url, referer = null)
    }
}
