package com.hikari.app.manga

import android.content.Context
import android.graphics.BitmapFactory
import com.hikari.app.HikariApp
import com.hikari.app.data.StreamSource
import com.hikari.app.net.DohDns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Where one page of a chapter stands.
 *
 * [Ready] carries the page's real pixel size, which is the whole reason the size
 * is read from the FILE rather than from the widget: a webtoon strip lays itself
 * out at the page's true aspect ratio, and a page whose ratio is unknown is a
 * gap that jumps under the reader's thumb the moment the image lands.
 */
sealed class MangaPageState {
    /** Nothing has asked for this page yet. */
    object Idle : MangaPageState()

    /** Being fetched; [attempt] is the 1-based try in progress. */
    data class Loading(val attempt: Int) : MangaPageState()

    /** On disk, validated, ready to decode. */
    data class Ready(val file: File, val width: Int, val height: Int) : MangaPageState() {
        /** width / height — 0 when the platform could not read the header. */
        val ratio: Float get() = if (width > 0 && height > 0) width.toFloat() / height else 0f
    }

    /** Every attempt failed; the reader offers [attempts] and a retry button. */
    data class Failed(val attempts: Int, val reason: String) : MangaPageState()
}

/**
 * Fetches, validates, retries and caches the page images of a manga chapter.
 *
 * **Why Hikari fetches the bytes itself instead of letting Coil do it.** A page
 * used to be an ordinary `AsyncImage`: Coil made the request, and whatever came
 * back was handed to a decoder. Two things were wrong with that, and both were
 * visible to the user as "some pages are broken":
 *
 *  1. A truncated response was indistinguishable from a good one. Aggregator
 *     CDNs answer a mid-transfer disconnect with a short body (and some answer
 *     a hotlink refusal with a small HTML page), and a decoder handed a
 *     half-image does not fail — Skia fills the missing macroblocks with black
 *     slabs and decodes the rest, so the reader showed a page cut into
 *     rectangular pieces with black gaps through it. Nothing retried, because
 *     nothing had failed. With `beyondViewportPageCount` on the pager the page
 *     also stayed broken until it was composed again from scratch.
 *  2. The app's shared Coil loader overrides the User-Agent and invents a
 *     `Referer` for every image (poster CDNs need that). A manga page is a very
 *     different client: it must be requested exactly the way the extension would
 *     request it, with the extension's OWN headers, or a CDN that binds the
 *     page to the site's session answers with a refusal.
 *
 * So a page is fetched here with the source's own headers, its bytes are
 * checked to be a COMPLETE image of a known format, and only then is it written
 * to the app's cache as a file. A failed fetch is retried up to [MAX_ATTEMPTS]
 * times (a surprising number of these are one-off CDN timeouts), and a page that
 * runs out of attempts reports [MangaPageState.Failed] so the reader can draw a
 * retry button on that page alone.
 *
 * A page is fetched at most ONCE per URL: [load] is single-flight, so the
 * preloader, the pager and a retry cannot race each other into three downloads.
 *
 * **Why the decode is NOT here.** A page that is on disk still has to become
 * pixels, and that step used to belong to this class: it decoded the file,
 * downsampled it to the reader's width, and kept the bitmap in a byte-budgeted
 * LRU. That is the step that produced "the image in the reader is breaking", and
 * no amount of care inside it could have avoided it — a decoded page is a bitmap,
 * a bitmap handed to the compositor is a GPU texture, and a page taller than the
 * device's `GL_MAX_TEXTURE_SIZE` is then drawn by Skia's tile fallback with its
 * tiles' source rectangles misplaced (see [com.hikari.app.manga.SubsamplingPageView],
 * which has the whole story). Memory was the smaller half of the problem: a
 * webtoon page is up to a 20MB allocation, and the reader wanted several of them
 * alive at once.
 *
 * So this loader stops at the FILE — fetched, validated, retried, on disk — and
 * the reader draws that file with a view that region-decodes only the part on
 * screen ([SubsamplingPageView]). Nothing is ever materialised whole, nothing is
 * decoded twice (the view keeps what it decoded, and a half-scrolled-away page
 * keeps its tiles while it is composed), and the memory a page costs is its
 * compressed bytes rather than its pixels.
 *
 * [plan] still PRELOADS the pages around the reader, so they are on disk before
 * the thumb arrives — the part that makes the next page instant — and a page
 * that cannot be fetched at all still ends up as [MangaPageState.Failed] with a
 * retry row of its own.
 */
object MangaPageLoader {

    /**
     * How many times one page is fetched before the reader gives up and offers
     * the manual retry. Ten is the number the user asked for, and it is a sane
     * ceiling: with the backoff below it is ~20s of trying before a page admits
     * defeat, and anything still failing by then is a wall, not a hiccup.
     */
    const val MAX_ATTEMPTS = 10

    /** Pages fetched at once. Higher just queues on the CDN's own throttling and
     *  starves the page the reader is actually looking at. */
    private const val PARALLEL = 4

    /** The cache's ceiling. Pages are big; a few hundred MB is plenty to keep a
     *  chapter you are reading (and the one you just read) instant. */
    private const val CACHE_CAP_BYTES = 400L * 1024 * 1024

    /** How often the cache is measured. Every chapter open is enough. */
    private const val PRUNE_EVERY_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(PARALLEL)
    private val states = ConcurrentHashMap<String, MutableStateFlow<MangaPageState>>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val files = ConcurrentHashMap<String, File>()

    @Volatile
    private var prunedAt = 0L

    /** The last [plan] call, so a scroll step that moves the reader by a page or
     *  two does not re-walk (and re-ask for) the whole window below. */
    @Volatile
    private var plannedKey: String = ""
    @Volatile
    private var plannedAt: Int = -1

    /**
     * A client of its own, deliberately: no ad-blocker, no Cloudflare marking
     * (an image that answered a challenge must not be able to raise the app's
     * "verification needed" banner — see [com.hikari.app.net.CloudflareVerifier]),
     * no shared cookie jar rewriting, and its own read timeout. Cookies for the
     * image's host are attached per request from the WebView jar, because that
     * is where the clearance the user earned lives.
     */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .dns(DohDns)
            .build()
    }

    /** The observable state of [url] (created on first sight, never removed
     *  while [load] is the only thing touching a page). */
    fun state(url: String): StateFlow<MangaPageState> =
        states.getOrPut(url) { MutableStateFlow(MangaPageState.Idle) }.asStateFlow()

    /**
     * Makes sure [url] is being fetched. Idempotent: a page already loading (or
     * already on disk) is left alone unless [force], which is what the reader's
     * retry button and the loader's own retry loop use.
     */
    fun load(url: String, headers: Map<String, String>, force: Boolean = false) {
        if (url.isBlank()) return
        val current = states.getOrPut(url) { MutableStateFlow(MangaPageState.Idle) }
        val running = jobs[url]?.isActive == true
        when (val s = current.value) {
            is MangaPageState.Ready -> {
                // A page that is on disk needs nothing... unless the system
                // cleared the cache directory under us, which it is free to do
                // at any time. The state is only as good as the file.
                if (s.file.exists() && !force) return
            }
            is MangaPageState.Loading -> if (running && !force) return
            // A page that has spent its ten attempts stays failed until the
            // reader asks again — otherwise every page turn would restart the
            // whole ladder on a page that is simply not there (a 404).
            is MangaPageState.Failed -> if (!force) return
            MangaPageState.Idle -> Unit
        }
        if (force) jobs.remove(url)?.cancel()
        jobs[url] = scope.launch {
            gate.withPermit { fetch(url, headers, current) }
            jobs.remove(url)
        }
    }

    /**
     * Reports a page whose file cannot be decoded after all.
     *
     * The fetch validates the bytes structurally (a complete image of a known
     * format) and reads the header for the page's size, but a JPEG can pass every
     * one of those checks with damaged scan data — and the file that fails to
     * DECODE is a file that would otherwise sit in the reader as a page that
     * never appears. The drawing view reports that here (see
     * [SubsamplingPageView.onPageFailed]), so the page ends up on the same
     * [MangaPageState.Failed] row as a page that never arrived, with the same
     * retry button, and the file is dropped so the retry really re-fetches.
     */
    fun markUndecodable(url: String) {
        if (url.isBlank()) return
        jobs.remove(url)?.cancel()
        val ready = states[url]?.value as? MangaPageState.Ready
        ready?.file?.let { f ->
            runCatching { f.delete() }
            files.remove(url, f)
        }
        states[url]?.value = MangaPageState.Failed(MAX_ATTEMPTS, "undecodable image")
    }

    /** The reader's retry button: forget the failure and try the full ladder
     *  again from the first attempt. */
    fun retry(url: String, headers: Map<String, String>) {
        load(url, headers, force = true)
    }

    /**
     * Preloads the pages around [current].
     *
     * The order is the whole point: the ten pages AHEAD first (that is where the
     * reader's thumb is going), then the eight BEHIND (they are what a back-swipe
     * lands on), then the rest of the chapter forward — so a chapter is pulled in
     * completely while it is being read instead of one page at a time, which is
     * what makes the next page appear instantly instead of after a spinner.
     * Within a chapter that is already running, the URLs already in flight or
     * on disk are skipped, so this can be called on every page turn for free.
     */
    fun plan(pages: List<StreamSource>, current: Int) {
        if (pages.isEmpty()) return
        pruneCache()
        val at = current.coerceIn(0, pages.lastIndex)
        // Called on EVERY scroll step by the surface that draws the pages, and
        // the window it computes only changes meaningfully every couple of
        // pages — so a step that lands within one page of the last plan is
        // skipped. The loads themselves are idempotent, but walking 200 page
        // entries on every frame of a fling is not free, and a fling is exactly
        // when the reader is watching for dropped frames.
        val key = pages.first().url
        if (key == plannedKey && kotlin.math.abs(at - plannedAt) < 2) return
        plannedKey = key
        plannedAt = at
        val ahead = (at + 1..at + 10).filter { it in pages.indices }
        val behind = (at - 1 downTo at - 8).filter { it in pages.indices }
        val forward = (ahead.lastOrNull()?.plus(1) ?: at + 1)..pages.lastIndex
        val restBehind = (behind.lastOrNull()?.minus(1) ?: at - 1) downTo 0
        val order = ArrayList<Int>(pages.size)
        order += ahead
        order += behind
        order += forward
        order += restBehind
        // Every page is pulled onto the DISK, in that order. There is nothing
        // else to warm: the reader draws a page by region-decoding its file (see
        // [SubsamplingPageView]), so a page whose bytes are here is a page that
        // appears the moment the thumb reaches it — there is no second, decoded
        // form to prepare in advance, and nothing to evict when the chapter is
        // long.
        for (i in order) load(pages[i].url, pages[i].headers)
    }

    // ---- Fetching -----------------------------------------------------------

    private suspend fun fetch(
        url: String,
        headers: Map<String, String>,
        state: MutableStateFlow<MangaPageState>,
    ) {
        var lastReason = "unknown"
        for (attempt in 1..MAX_ATTEMPTS) {
            state.value = MangaPageState.Loading(attempt)
            val result = runCatching { get(url, headers, attempt) }
            val outcome = result.getOrNull()
            if (result.isFailure) {
                lastReason = result.exceptionOrNull()?.javaClass?.simpleName ?: "error"
            } else if (outcome == null) {
                lastReason = "empty body"
            } else {
                state.value = outcome
                return
            }
            if (attempt < MAX_ATTEMPTS) {
                // Linear-ish backoff, capped: a CDN that just refused a
                // connection is usually happy a second later, and the tenth
                // attempt should not be 20 seconds after the ninth.
                delay((250L * attempt).coerceAtMost(2_000L))
            }
        }
        state.value = MangaPageState.Failed(MAX_ATTEMPTS, lastReason)
        // One line per page that gives up, with the reason: it is what separates
        // "the server has no page at that URL" from "the bytes came back damaged"
        // in a report about a broken page, and the reader's own row cannot say
        // which it was.
        com.hikari.app.data.Logs.log(
            "Manga",
            "page gave up after $MAX_ATTEMPTS tries ($lastReason) — ${url.take(140)}",
        )
    }

    /** One attempt. Returns the [MangaPageState.Ready] state on success, null on
     *  a soft failure (retry), and throws only for programming errors. */
    private fun get(url: String, headers: Map<String, String>, attempt: Int): MangaPageState? {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) ->
            // A header value OkHttp refuses (a stray newline in an extension's
            // own header map) must not take the whole page down.
            runCatching { builder.header(k, v) }
        }
        // The extension's own headers are replayed verbatim — that is what the
        // CDN expects. A cookie is only added when the extension set none, and
        // it comes from the WebView jar (the clearance the user's verification
        // earned, and whatever session the site handed the WebView).
        if (headers.keys.none { it.equals("Cookie", ignoreCase = true) }) {
            runCatching {
                android.webkit.CookieManager.getInstance().getCookie(url)
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
        }
        if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            builder.header("User-Agent", HikariApp.instance.effectiveWebViewUa())
        }
        // A RETRY is a statement that what came back last time was unusable —
        // short, not an image at all, or bytes the decoder refused — and the
        // likeliest reason a later attempt gets the SAME bad body is that a CDN
        // edge has it cached. Asking for a revalidation is the polite form of
        // "give me a different copy": it changes no URL, so a signed link stays
        // valid on the CDNs that mint one, and it costs nothing when the edge's
        // copy is good.
        if (attempt > 1) builder.header("Cache-Control", "no-cache")
        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 404 || response.code == 410) return null
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            val declared = body.contentLength()
            val bytes = body.bytes()
            if (bytes.isEmpty()) return null
            // A body that arrives short is the "broken page" itself, not a
            // hiccup to be decoded: `contentLength` is what the CDN promised,
            // and `bytes()` is what actually came through.
            if (declared > 0 && bytes.size.toLong() != declared) return null
            val kind = imageKind(bytes) ?: return null
            if (!looksComplete(bytes, kind)) return null
            val file = write(url, bytes, kind) ?: return null
            val bounds = bounds(file)
            // The size the reader lays the page out at comes out of the file's
            // HEADER (`inJustDecodeBounds` — cheap, no pixels), and a header the
            // platform cannot read is a file that will not decode either: it goes
            // now and this attempt counts as a failure, which is retried like any
            // other. The full decode that used to run here is deliberately gone —
            // it existed only to feed the bitmap cache (and, on the side, to
            // catch damaged scan data). The drawing view region-decodes the file
            // and reports a file it cannot open through [markUndecodable], so
            // that case still ends on a retry row instead of a page that never
            // appears.
            if (bounds.first <= 0 || bounds.second <= 0) {
                file.delete()
                files.remove(url, file)
                return null
            }
            return MangaPageState.Ready(file, bounds.first, bounds.second)
        }
    }

    // ---- Bytes → file ------------------------------------------------------

    private fun cacheDir(context: Context): File =
        File(context.cacheDir, "manga-pages").apply { mkdirs() }

    private fun nameOf(url: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Writes the bytes under a FRESH name every time (a decoder keeps what it
     *  read keyed by the file, so the same path with new bytes would keep serving
     *  the old page), and drops the previous file for this url — a retry that
     *  lands a good page must never leave the old garbage on screen. */
    private fun write(url: String, bytes: ByteArray, kind: String): File? = runCatching {
        val dir = cacheDir(HikariApp.instance)
        val file = File(dir, nameOf(url) + "-" + System.nanoTime() + "." + kind)
        val tmp = File(dir, file.name + ".part")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            return@runCatching null
        }
        files.put(url, file)?.let { old -> if (old.absolutePath != file.absolutePath) old.delete() }
        file
    }.getOrNull()

    /** The decoded size, read from the file's header only — no full decode, so
     *  the reader can lay a page out at its real shape before it is drawn. */
    private fun bounds(file: File): Pair<Int, Int> = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        opts.outWidth to opts.outHeight
    }.getOrDefault(0 to 0)

    /**
     * Which format a page file is, from its own magic bytes.
     *
     * This is also the first gate against a CDN answering an image request with
     * something that is not an image at all (a hotlink refusal, a bot wall, an
     * APK download page) — decoding those was the other half of "some pages are
     * broken": a decoder handed HTML draws nothing at all, forever.
     */
    private fun imageKind(b: ByteArray): String? {
        if (b.size < 16) return null
        fun at(i: Int) = b[i].toInt() and 0xFF
        return when {
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "jpg"
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "png"
            at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x38 -> "gif"
            at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
                at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50 -> "webp"
            at(0) == 0x42 && at(1) == 0x4D -> "bmp"
            at(4) == 0x66 && at(5) == 0x74 && at(6) == 0x79 && at(7) == 0x70 -> "avif"
            else -> null
        }
    }

    /**
     * True when the file carries its own end-of-image marker.
     *
     * Every format says how it ends, and the markers are exact, so a truncated
     * body is detectable without decoding anything: a JPEG ends `FF D9`, a PNG
     * with the `IEND` chunk, a GIF with `0x3B`, and a RIFF/WebP file declares
     * its total length in its own header. A page that fails this test is
     * re-fetched rather than decoded into black slabs.
     */
    private fun looksComplete(b: ByteArray, kind: String): Boolean {
        fun at(i: Int) = b[i].toInt() and 0xFF
        val n = b.size
        return when (kind) {
            "jpg" -> {
                // Trailing padding bytes are legal after EOI, so look back a
                // little from the end for the marker.
                (n - 2 downTo (n - 32).coerceAtLeast(0)).any { at(it) == 0xFF && at(it + 1) == 0xD9 }
            }
            "png" -> n >= 8 && at(n - 8) == 0x49 && at(n - 7) == 0x45 && at(n - 6) == 0x4E &&
                at(n - 5) == 0x44 && at(n - 4) == 0xAE && at(n - 3) == 0x42 &&
                at(n - 2) == 0x60 && at(n - 1) == 0x82
            "gif" -> at(n - 1) == 0x3B
            "webp" -> {
                val declared = (at(4) or (at(5) shl 8) or (at(6) shl 16) or (at(7) shl 24)) + 8
                n >= declared
            }
            // BMP declares its size too; AVIF/HEIC are box-structured with no
            // cheap end marker, so a declared-length check is the best a header
            // read can do (the decoder itself is the final judge).
            "bmp" -> {
                val declared = at(2) or (at(3) shl 8) or (at(4) shl 16) or (at(5) shl 24)
                declared <= n || declared == 0
            }
            else -> n > 1024
        }
    }

    /** Keeps the page cache under [CACHE_CAP_BYTES] by deleting the least
     *  recently used files. Runs at most once a minute (see [plan]). */
    private fun pruneCache() {
        val now = System.currentTimeMillis()
        if (now - prunedAt < PRUNE_EVERY_MS) return
        prunedAt = now
        scope.launch {
            runCatching {
                val dir = cacheDir(HikariApp.instance)
                val all = dir.listFiles()?.filter { it.isFile } ?: return@runCatching
                var total = all.sumOf { it.length() }
                if (total <= CACHE_CAP_BYTES) return@runCatching
                // Oldest first, and never the file the reader is looking at
                // right now (its state was touched most recently, so a
                // least-recently-used order naturally spares it).
                for (f in all.sortedBy { it.lastModified() }) {
                    if (total <= CACHE_CAP_BYTES) break
                    val len = f.length()
                    // The url → file entries pointing at this file go with it,
                    // and so do their Ready states: a state that keeps claiming
                    // Ready for a file that is gone is exactly how a page ends
                    // up drawn as a blank cell with no retry offered.
                    val gone = files.entries.filter { it.value.absolutePath == f.absolutePath }
                        .map { it.key }
                    if (f.delete()) {
                        total -= len
                        for (url in gone) {
                            files.remove(url)
                            states[url]?.value = MangaPageState.Idle
                        }
                    }
                }
            }
        }
    }
}
