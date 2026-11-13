package com.hikari.app.cs3

import com.hikari.app.data.Logs
import com.hikari.app.data.StreamSource
import com.hikari.app.data.SubtitleSource
import com.hikari.app.net.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object YouTubePlayables {

    private const val TAG = "YouTube"

    /** 0 = not tried yet, 1 = NewPipe is usable, 2 = it is not (do not retry). */
    @Volatile
    private var state = 0

    private val initLock = Any()

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
                // DASH carries video+audio adaptations (ExoPlayer merges them), so always
                // offer it when present: 720p+ VP9/AV1 progressive links are video-only
                // (silence) and muxed videoStreams tops out at low res. Added even when
                // muxed rows exist so HD has an audio path.
                if (!info.dashMpdUrl.isNullOrBlank() && out.none { it.url == info.dashMpdUrl }) {
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

object PlayableResolver {

    private const val MAX_ROWS = 6

    private const val PER_ROW_MS = 12_000L

    private const val BUDGET_MS = 20_000L

    /** How many link rows may be resolved at once in the background. An addon
     *  answers with a handful, so this is generous; the cap is only there so a
     *  pathological list cannot fire hundreds of network walks at the phone. */
    private const val BG_PARALLEL = 6

    private val resolvedOk = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val claimed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Application-scoped, so a resolution outlives the screen that started it
     *  (the player is usually already open when it finishes). */
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val bgGate = Semaphore(BG_PARALLEL)

    /** The identity of a link row, for [resolvedOk]/[claimed]. */
    private fun linkKey(s: StreamSource): String =
        s.ytId?.let { "yt:$it" } ?: ("ext:" + s.url.trim().lowercase())

    private fun isLinkRow(s: StreamSource): Boolean =
        s.ytId != null || (s.externalUrl && s.url.startsWith("http"))

    fun warmLinks(sources: List<StreamSource>, onResolved: suspend (List<StreamSource>) -> Unit) {
        val links = sources.filter { isLinkRow(it) }
        if (links.isEmpty()) return
        for (s in links) {
            val key = linkKey(s)
            if (key in resolvedOk || !claimed.add(key)) continue
            bgScope.launch {
                val found = runCatching {
                    bgGate.withPermit { withTimeoutOrNull(PER_ROW_MS) { resolveOne(s) } }
                }.getOrNull().orEmpty()
                if (found.isEmpty()) {
                    claimed.remove(key)
                    return@launch
                }
                resolvedOk.add(key)
                val out = relabel(s, found)
                Logs.log(
                    "Search",
                    "\"${s.name}\" → ${found.size} playable source(s) as it arrived " +
                        "(resolved from a ${if (s.ytId != null) "YouTube" else "external"} link)",
                )
                runCatching { onResolved(out) }
            }
        }
    }

    /** The addon's own identity, kept on the servers a link row was resolved
     *  into, so the chooser still groups and orders them by provider. */
    private fun relabel(s: StreamSource, found: List<StreamSource>): List<StreamSource> =
        found.map { f ->
            f.copy(
                name = if (s.name.isBlank() || f.name.contains(s.name, ignoreCase = true)) f.name
                else "${f.name} · ${s.name}",
                provider = s.provider,
                providerId = s.providerId,
                providerName = s.providerName,
            )
        }

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
            val resolved = withTimeoutOrNull(PER_ROW_MS) { resolveOne(s) }.orEmpty()
            if (resolved.isEmpty()) {
                out += s
                continue
            }
            resolvedOk.add(linkKey(s))
            Logs.log(
                "Search",
                "\"${s.name}\" → ${resolved.size} playable source(s) " +
                    "(resolved from a ${if (s.ytId != null) "YouTube" else "external"} link)",
            )
            out += relabel(s, resolved)
        }
        return out
    }

    private suspend fun resolveOne(s: StreamSource): List<StreamSource> {
        s.ytId?.let { return YouTubePlayables.resolve(it) }
        if (!s.externalUrl || !s.url.startsWith("http")) return emptyList()
        return FallbackResolver.resolveEmbedUrl(s.url, referer = null)
    }
}
