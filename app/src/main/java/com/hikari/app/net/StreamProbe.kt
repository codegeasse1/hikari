package com.hikari.app.net

import androidx.media3.common.MimeTypes
import com.hikari.app.HikariApp
import com.hikari.app.data.StreamSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Resolves container-unknown stream URLs (4KHDHub's HubCloud wrapper pages,
 * JSON APIs, HTML players — anything without a real media extension) into the
 * actual playable URL + container type, ONCE, and remembers the answer.
 *
 * Why this lives here and not in the player: the player used to do the whole
 * probe inline on every play, behind a blocking "Preparing stream…" dialog, so
 * every replay of a 4KHDHub title paid the same network round-trip again. This
 * object keeps a process-wide + on-disk cache (so a replayed server starts
 * instantly), shares ONE connection-pooled OkHttp client across every probe
 * (no per-Activity cold TLS handshake), and lets the source search warm the
 * cache in the background while the user is still picking a server.
 *
 * Deliberately plain OkHttp: no Cloudflare verify-WebView interceptor. A probe
 * that needed a WAF clearance could never be reused by ExoPlayer's own
 * OkHttpDataSource anyway, so waiting on it would only add latency (and the
 * "Preparing stream…" stall on servers like 4KHDHub is not a Cloudflare issue).
 */
object StreamProbe {

    /** The resolved form of a stream URL: what to actually play, and the mime
     *  to force (HLS/DASH) or null to let ExoPlayer sniff a normal container. */
    data class Resolved(val url: String, val mime: String?)

    private const val MAX_DEPTH = 3
    private const val HEAD_BYTES = 131_072
    private const val RESOLVE_TIMEOUT_MS = 12_000L
    private const val SHARED_WAIT_MS = 14_000L
    private const val CACHE_FILE = "stream_probe_cache.json"
    private const val CACHE_MAX = 400

    /** Detached scope for [warmAsync] so a caller never waits on warming. */
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** In-flight resolutions, so a source-search warm and the player's own
     *  probe for the same URL share a single network walk instead of racing. */
    private val inflight = ConcurrentHashMap<String, CompletableDeferred<Resolved?>>()

    /** url -> resolved form. Shared across the whole app (and read first by the
     *  player, so ANY earlier resolution — source search, a previous play, a
     *  previous session — makes this play start instantly). */
    private val cache = ConcurrentHashMap<String, Resolved>()

    /** Derived from the process-wide playback client ([PlayerHttp]) so probes
     *  and playback SHARE one connection pool: the connection this probe opened
     *  to the CDN can still be warm when ExoPlayer asks for the first segment of
     *  the stream the probe just resolved (no second DNS + TLS handshake), and
     *  the pool/TLS sessions stay warm across sources, activities and calls,
     *  which is what makes the second and later probes noticeably faster than a
     *  per-Activity client could be.
     *
     *  Timeouts are overridden to be much shorter than playback's: a probe is a
     *  quick classification and a dead wrapper page must never hold up a source
     *  search. NO callTimeout: a slow-but-working wrapper hop must be allowed to
     *  finish (the walk self-limits via a clock deadline instead), and a
     *  total-call cap here was cutting off resolvable 4KHDHub/HubCloud chains
     *  mid-walk. */
    private val client: OkHttpClient by lazy {
        PlayerHttp.client.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private val tokenRe = Regex("""https?://[^\s"'<>\\]+""")

    @Volatile
    private var loaded = false

    private fun cacheFile(): File? =
        runCatching { File(HikariApp.instance.cacheDir, CACHE_FILE) }.getOrNull()

    /** Loads the on-disk cache once, lazily, so replaying a server the user
     *  played in a previous session needs no network at all. */
    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            val f = cacheFile() ?: return
            if (!f.exists()) return
            val obj = JSONObject(f.readText())
            obj.keys().forEach { k ->
                val o = obj.optJSONObject(k) ?: return@forEach
                val u = o.optString("u")
                if (u.isNotBlank()) cache[k] = Resolved(u, o.optString("m").ifBlank { null })
            }
        }
    }

    private fun persist() {
        runCatching {
            val f = cacheFile() ?: return
            val obj = JSONObject()
            cache.entries.toList().takeLast(CACHE_MAX).forEach { (k, v) ->
                obj.put(k, JSONObject().put("u", v.url).put("m", v.mime ?: ""))
            }
            f.writeText(obj.toString())
        }
    }

    /** The already-known resolution for [url], if any (never hits the network). */
    fun cached(url: String): Resolved? {
        if (url.isBlank()) return null
        ensureLoaded()
        return cache[url]
    }

    /** Resolves [url] to its playable form, or null when nothing was found (or
     *  the whole walk exceeded [RESOLVE_TIMEOUT_MS]). Successful results are
     *  cached in memory AND on disk, so the same URL never probes twice.
     *  Concurrent callers for the same URL share one network walk. */
    suspend fun resolve(url: String, headers: Map<String, String>): Resolved? =
        withContext(Dispatchers.IO) {
            if (url.isBlank()) return@withContext null
            ensureLoaded()
            cache[url]?.let { return@withContext it }
            val mine = CompletableDeferred<Resolved?>()
            val existing = inflight.putIfAbsent(url, mine)
            if (existing != null) {
                return@withContext withTimeoutOrNull(SHARED_WAIT_MS) { existing.await() }
            }
            try {
                val deadline = System.currentTimeMillis() + RESOLVE_TIMEOUT_MS
                val resolved = withTimeoutOrNull(RESOLVE_TIMEOUT_MS + 8_000L) {
                    follow(url, headers, 0, deadline)
                }
                if (resolved != null) {
                    cache[url] = resolved
                    persist()
                }
                runCatching { mine.complete(resolved) }
                resolved
            } finally {
                inflight.remove(url, mine)
                runCatching { mine.complete(null) }
            }
        }

    /** Fire-and-forget [warm] on a process-wide scope, so callers (the source
     *  search, the detail screen, the player) can start resolving the servers
     *  they just discovered without waiting on the network. */
    fun warmAsync(sources: List<StreamSource>) {
        if (sources.isEmpty()) return
        bgScope.launch { runCatching { warm(sources) } }
    }

    /** True when a source URL needs probing: an http(s) non-torrent that isn't
     *  already flagged HLS/DASH and has no media extension ExoPlayer's own
     *  extractors could sniff. */
    fun needsResolve(
        url: String,
        isTorrent: Boolean = false,
        isM3u8: Boolean = false,
        isMpd: Boolean = false,
    ): Boolean {
        if (isTorrent || isM3u8 || isMpd) return false
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        if (url.contains(".m3u8", true) || url.contains(".mpd", true)) return false
        if (url.contains("master.txt", true)) return false
        return !hasMediaExtension(url)
    }

    private val MEDIA_EXTENSIONS = listOf(
        ".mp4", ".webm", ".mkv", ".flv", ".avi", ".mov", ".m4v", ".m4s",
        ".ts", ".mp3", ".aac", ".ogg", ".ogv", ".m4a", ".wav", ".flac",
        ".3gp", ".mpg", ".mpeg", ".opus", ".wmv",
    )

    private val ARCHIVE_EXTENSIONS = listOf(".zip", ".rar", ".7z", ".tar", ".gz", ".001")

    /** True when a URL points at an ARCHIVE of a video rather than a video:
     *  hubcloud hands out `.mkv.zip` links in quality mode, and no player can
     *  play one directly. Such a source is kept (it is real content, just
     *  wrapped) but must never be the server playback STARTS on — ExoPlayer can
     *  only fail on it, which used to burn a whole prepare+error cycle before
     *  the failover got to a real video. */
    fun isArchive(url: String): Boolean {
        if (url.isBlank()) return false
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return ARCHIVE_EXTENSIONS.any { path.endsWith(it) }
    }

    private fun hasMediaExtension(url: String): Boolean {
        val clean = url.substringBefore('?').substringBefore('#')
        return MEDIA_EXTENSIONS.any { clean.endsWith(it, ignoreCase = true) }
    }

    /** Returns [src] rewritten to its resolved URL/mime — or [src] unchanged
     *  when [resolved] adds nothing. */
    fun apply(src: StreamSource, resolved: Resolved): StreamSource {
        val m3u8 = resolved.mime == MimeTypes.APPLICATION_M3U8
        val mpd = resolved.mime == MimeTypes.APPLICATION_MPD
        if (resolved.url == src.url && src.isM3u8 == m3u8 && src.isMpd == mpd) return src
        return src.copy(url = resolved.url, isM3u8 = m3u8, isMpd = mpd)
    }

    /** Warms the cache for every source that needs resolution, a few at a time,
     *  so a server pick (or an auto-failover) is usually already cached. */
    suspend fun warm(sources: List<StreamSource>) {
        val targets = sources.filter { it.url.isNotBlank() && needsResolve(it.url, it.isTorrent, it.isM3u8, it.isMpd) }
        if (targets.isEmpty()) return
        ensureLoaded()
        val pending = targets.filter { cache[it.url] == null }
        if (pending.isEmpty()) return
        val sem = Semaphore(3)
        coroutineScope {
            pending.map { s ->
                async(Dispatchers.IO) {
                    sem.withPermit { runCatching { resolve(s.url, s.headers) } }
                }
            }.awaitAll()
        }
    }

    // ---- internals -------------------------------------------------------

    /** Fetches [url] and classifies the head of the response; on a wrapper page
     *  digs out the embedded media URLs and follows the most promising one. At
     *  the TOP level the candidates are raced in parallel, so one dead/slow
     *  candidate no longer serializes the walk behind its own timeout; deeper
     *  hops stay sequential so the thread fan-out can't explode. Returns null —
     *  never throws — on any failure. */
    private fun follow(
        url: String,
        headers: Map<String, String>,
        depth: Int,
        deadline: Long,
    ): Resolved? {
        if (depth > MAX_DEPTH) return null
        // Deadline (not a coroutine timeout): the socket reads below are
        // blocking, so the walk self-limits by checking the clock before each
        // hop instead of relying on cancellation.
        if (System.currentTimeMillis() > deadline) return null
        val response = get(url, headers) ?: return null
        response.use { r ->
            if (!r.isSuccessful) return null
            val ct = r.headers["Content-Type"]?.lowercase() ?: ""
            val body = r.body ?: return null
            val head: String = try {
                val buf = ByteArray(HEAD_BYTES)
                val input = body.byteStream()
                var read = 0
                while (read < buf.size) {
                    val n = input.read(buf, read, buf.size - read)
                    if (n < 0) break
                    read += n
                }
                // ISO-8859-1 keeps the raw bytes, so the "#EXTM3U"/"ftyp" sniffs
                // below are never thrown off by charset decoding.
                String(buf, 0, read, Charsets.ISO_8859_1)
            } catch (t: Throwable) {
                return null
            }
            val trimmed = head.trimStart()
            if (trimmed.startsWith("#EXTM3U") || ct.contains("mpegurl") || ct.contains("m3u8")) {
                return Resolved(url, MimeTypes.APPLICATION_M3U8)
            }
            if ((trimmed.startsWith("<?xml") && head.contains("<MPD")) || ct.contains("dash+xml")) {
                return Resolved(url, MimeTypes.APPLICATION_MPD)
            }
            if (ct.startsWith("video/") || ct.startsWith("audio/") ||
                trimmed.startsWith("ftyp") || trimmed.startsWith("\u0000\u0000\u0000\u0018ftyp")
            ) {
                return Resolved(url, null)
            }
            val candidates = candidates(head, url)
            if (candidates.isEmpty()) return null
            if (depth == 0 && candidates.size > 1) {
                val pool = Executors.newFixedThreadPool(minOf(candidates.size, 4)) { runnable ->
                    Thread(runnable, "hikari-probe").apply { isDaemon = true }
                }
                try {
                    val done = ExecutorCompletionService<Resolved?>(pool)
                    candidates.forEach { c ->
                        done.submit(Callable { follow(c, headers, depth + 1, deadline) })
                    }
                    repeat(candidates.size) {
                        val res = runCatching { done.take().get() }.getOrNull()
                        if (res != null) return res
                    }
                } finally {
                    pool.shutdownNow()
                }
                return null
            }
            for (c in candidates) {
                val res = follow(c, headers, depth + 1, deadline)
                if (res != null) return res
            }
            return null
        }
    }

    /** Fetches [url] for probing. Sends a Range header so hosts stream just the
     *  opening bytes instead of holding the connection for the whole file, and
     *  closes the body right after reading the head. Returns null — never
     *  throws — on any failure. */
    private fun get(url: String, headers: Map<String, String>): Response? = try {
        val b = okhttp3.Request.Builder().url(url)
            .header("User-Agent", Http.UA)
            .header("Range", "bytes=0-262143")
        headers.forEach { (k, v) -> if (!k.equals("Range", ignoreCase = true)) b.header(k, v) }
        client.newCall(b.build()).execute()
    } catch (t: Throwable) {
        null
    }

    /** Finds the media-looking URLs inside a wrapper page's head and ranks them
     *  (m3u8 > mpd > direct video > player paths) so the walk follows the most
     *  promising one first. */
    private fun candidates(head: String, pageUrl: String): List<String> {
        val scored = LinkedHashMap<String, Int>()
        for (m in tokenRe.findAll(head)) {
            val u = m.value.trimEnd(')', ']', '}', ',', ';', '.', '"', '\'')
            if (!u.startsWith("http")) continue
            if (u == pageUrl) continue
            val lower = u.lowercase()
            val score = when {
                "m3u8" in lower -> 100
                "mpd" in lower || "manifest" in lower -> 90
                lower.contains(".mp4") || lower.contains(".webm") || lower.contains(".mkv") ||
                    lower.contains(".m4s") -> 80
                "/get" in lower || "/stream" in lower || "/play" in lower || "/hls" in lower ||
                    "master" in lower || "/video" in lower -> 60
                "video" in lower || "media" in lower || "/embed" in lower -> 40
                else -> 10
            }
            if (score >= 40) scored.putIfAbsent(u, score)
        }
        return scored.entries.sortedByDescending { it.value }.take(8).map { it.key }
    }
}
