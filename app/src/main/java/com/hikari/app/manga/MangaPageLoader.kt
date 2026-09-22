package com.hikari.app.manga

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
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
 * One page's PIXELS, ready to be drawn: a list of horizontal slices, tallest
 * first-to-last, plus the page's own decoded size.
 *
 * Slicing is not a memory trick — it is the difference between a page that draws
 * and a page that draws as a wreck. A page is handed to the GPU as a texture, and
 * a texture cannot be bigger than the device's `GL_MAX_TEXTURE_SIZE`; when the
 * image is bigger than that, Skia falls back to drawing it as a GRID OF TILES,
 * and on the drivers this app meets that fallback mis-places the tiles' source
 * rectangles. What the reader shows is then the reported "the image is breaking":
 * bands of the page displaced sideways, the same artwork repeated at an offset, a
 * white seam where a tile boundary is, and the whole thing enlarged past both
 * screen edges. Every one of those is a symptom of the tiled draw, not of the
 * bytes on disk — the file is fine, and re-fetching it (which the loader does
 * try) cannot change anything.
 *
 * So a page is cut into slices no taller than [MAX_DRAW_H], each of which is a
 * legal texture on every device that is allowed to report a GLES2 context, and
 * the reader stacks them. The page's own slices add up to exactly the page, so a
 * slice stack is drawn in a box of the page's aspect ratio and reads as one
 * image.
 */
class MangaPage(
    /** Top to bottom. Never empty. Each one is at most [MAX_DRAW_H] tall. */
    val slices: List<Bitmap>,
    /** The decoded page's width in px — every slice shares it. */
    val width: Int,
    /** The decoded page's height in px — the sum of the slices' heights. */
    val height: Int,
) {
    /** width / height — 0 when the platform could not read the header. */
    val ratio: Float get() = if (width > 0 && height > 0) width.toFloat() / height else 0f

    val byteCount: Int get() = slices.sumOf { it.byteCount }
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
 * to the app's cache as a file the loader decodes itself. A failed fetch is
 * retried up to [MAX_ATTEMPTS] times (a surprising number of these are one-off
 * CDN timeouts), and a page that runs out of attempts reports [MangaPageState.Failed]
 * so the reader can draw a retry button on that page alone.
 *
 * A page is fetched at most ONCE per URL: [load] is single-flight, so the
 * preloader, the pager and a retry cannot race each other into three downloads.
 *
 * **Why the decode lives here too.** A page that is on disk still has to become
 * pixels, and that step was Coil's — which meant a fresh request, a fresh
 * decode and a fresh upload to the GPU every time a page scrolled back into
 * view, at whatever size the composable happened to ask for. A webtoon strip is
 * a dozen 1080×8000 pages; decoding each of them at full size, over and over,
 * on a list that scrolls with the thumb is exactly what "not buttery smooth"
 * is. So the loader decodes a page itself — deliberately DOWNSAMPLED to the
 * reader's own width (see [decodeFor]) and to [MAX_DECODE_H] — cuts the result
 * into GPU-legal slices ([MangaPage]), and keeps those in a byte-budgeted LRU
 * ([pages]). [plan] then PRELOADS the pages around the reader, so by the time a
 * page appears its slices are either in that cache (a blit) or one decode away.
 * Nothing is ever fetched twice and nothing is decoded twice.
 *
 * The decode is also the last word on whether a page is GOOD: a file that
 * cannot be decoded is treated as a failed fetch and retried like any other —
 * because a page that cannot become pixels is a page that shows a retry row
 * instead of a spinner that never ends.
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

    /** The widest a page is ever decoded. A phone is 1080–1440px wide and a
     *  webtoon page is 800–1600, so this is the reader's own resolution on
     *  almost every page — anything wider is an upscale nobody can see, paid for
     *  in memory and in the upload to the GPU on every frame it is on screen. */
    private const val MAX_DECODE_W = 1600

    /** The tallest a page is ever decoded. A long strip is 8000–20000px tall
     *  and decoding one of those in one piece is a 20–60MB allocation per page,
     *  so it is sampled down (a power-of-two sample, so the decoder does the
     *  work on the fly) to at most this. It is a MEMORY ceiling, not a drawing
     *  one: the drawn page is cut into [MAX_DRAW_H] slices whatever this says. */
    private const val MAX_DECODE_H = 8192

    /**
     * The tallest bitmap this app will ever hand to the GPU — half the smallest
     * `GL_MAX_TEXTURE_SIZE` any GLES2 device is allowed to report (2048), and
     * therefore small enough to be a legal texture everywhere.
     *
     * This is the number that fixes "the image in the reader is still breaking".
     * A drawn image taller than the device's maximum texture size is not drawn —
     * it is drawn as a grid of tiles, and Skia's tile fallback puts the wrong
     * source rectangles on those tiles on the drivers these phones have: the page
     * comes out as displaced bands, with artwork repeated at a fixed offset, a
     * white seam at every tile boundary and the whole thing running off both
     * screen edges. Nothing about the bytes on disk is wrong and no amount of
     * re-fetching helps, which is exactly why it looked unfixable from the
     * fetch side. Handing the GPU only legally-sized pieces IS the fix, and it
     * costs nothing visible: the slices are drawn edge to edge in a stack, which
     * is the whole page. */
    const val MAX_DRAW_H = 2048

    /** How many pixels of decoded page the bitmap cache may hold, derived from
     *  the heap the process was actually given rather than from a number picked
     *  on a developer's phone: a sixth of it, never below 48MB (a couple of
     *  webtoon pages, which is what "back one page" needs) and never above
     *  320MB (a big heap should still leave room for the list, the player and
     *  the rest of the app). Pages are RGB_565 (see [decodeFor]), so ~2 bytes a
     *  pixel: 96MB is around a dozen 1080×3600 pages. */
    private val bitmapBudget: Int by lazy {
        val max = Runtime.getRuntime().maxMemory()
        ((max / 6).coerceAtMost(320L * 1024 * 1024).coerceAtLeast(48L * 1024 * 1024))
            .toInt()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(PARALLEL)
    private val states = ConcurrentHashMap<String, MutableStateFlow<MangaPageState>>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val files = ConcurrentHashMap<String, File>()

    /**
     * Decoded pages, keyed by the url AND by the size they were decoded for (see
     * [drawKey]) and budgeted by their own byte count.
     *
     * The entries are deliberately NEVER recycled on eviction: the reader draws
     * them straight from this map, and a bitmap the cache has just evicted can
     * still be the one a frame is drawing — recycling it there is a hard crash
     * ("Canvas: trying to use a recycled bitmap"), while simply dropping the
     * reference lets the GC take it back a moment later. The budget is what
     * keeps the pressure off; the missing `recycle()` is what keeps the reader
     * up.
     */
    private val pages: LruCache<String, MangaPage> by lazy {
        object : LruCache<String, MangaPage>(bitmapBudget) {
            override fun sizeOf(key: String, value: MangaPage): Int = value.byteCount
        }
    }

    /**
     * The cache key of a page AS DRAWN: its url plus the decode target it was
     * asked for.
     *
     * A page can legitimately be wanted at two sizes at once — the webtoon strip
     * decodes at the reader's width with no height ceiling, while the paged
     * "fit the whole page" modes ask for a decode that already fits the viewport
     * — and the two must not evict each other's answer or hand it back at the
     * wrong size. The target is part of the identity, so they simply coexist.
     */
    private fun drawKey(url: String, targetW: Int, targetH: Int): String =
        url + "\u0001" + targetW + "\u0001" + targetH

    /** Drops every DRAWN copy of [url], whatever size it was decoded for. */
    private fun forgetDrawn(url: String) {
        runCatching {
            val prefix = url + "\u0001"
            for (key in pages.snapshot().keys) if (key.startsWith(prefix)) pages.remove(key)
        }
    }

    /** One lock per URL, so two composables asking for the same page at once
     *  share a decode instead of racing into two. */
    private val decodes = ConcurrentHashMap<String, Any>()

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
     *
     * [eager] asks for the page to be decoded into the bitmap cache as soon as
     * its bytes land — the reader passes it for the pages immediately around the
     * thumb ([plan]'s ahead/behind window) and leaves it off for the long tail
     * of the chapter, whose bytes are wanted on disk but whose pixels are not
     * needed for another minute: decoding all two hundred pages of a chapter up
     * front would evict the page under the eye from the budget.
     */
    fun load(url: String, headers: Map<String, String>, force: Boolean = false, eager: Boolean = false) {
        if (url.isBlank()) return
        val current = states.getOrPut(url) { MutableStateFlow(MangaPageState.Idle) }
        val running = jobs[url]?.isActive == true
        when (val s = current.value) {
            is MangaPageState.Ready -> {
                // A page that is on disk needs nothing... unless the system
                // cleared the cache directory under us, which it is free to do
                // at any time. The state is only as good as the file.
                if (s.file.exists() && !force) {
                    if (eager) ensureBitmap(url)
                    return
                }
            }
            is MangaPageState.Loading -> if (running && !force) return
            // A page that has spent its ten attempts stays failed until the
            // reader asks again — otherwise every page turn would restart the
            // whole ladder on a page that is simply not there (a 404).
            is MangaPageState.Failed -> if (!force) return
            MangaPageState.Idle -> Unit
        }
        if (force) {
            jobs.remove(url)?.cancel()
            // A retry is a statement that what is on screen is wrong, so the
            // decoded page goes now — not when (and if) the new bytes land.
            forgetDrawn(url)
        }
        jobs[url] = scope.launch {
            gate.withPermit { fetch(url, headers, current, eager) }
            jobs.remove(url)
        }
    }

    /** The width the reader draws a page at, in pixels. The reader knows it (it
     *  is the window's own width); the preloader needs it to decode a page at
     *  the size it will actually be shown. Set from the reader's composition. */
    @Volatile
    var decodeWidth: Int = 1080

    /** Decodes a page that is already on disk but not yet in memory, off the
     *  main thread — the "it is in the near window now" path (see [plan]). */
    private fun ensureBitmap(url: String) {
        if (pages.get(drawKey(url, decodeWidth, 0)) != null) return
        val ready = states[url]?.value as? MangaPageState.Ready ?: return
        if (!ready.file.exists()) return
        scope.launch { page(url, decodeWidth, 0) }
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
        // The near window is fetched EAGERLY (bytes AND pixels): those are the
        // pages the thumb is about to land on, and having their decoded bitmaps
        // already in memory is what makes the next swipe a blit instead of a
        // decode. The rest of the chapter is mapped onto the disk only.
        val near = (ahead + behind).toHashSet()
        for (i in order) load(pages[i].url, pages[i].headers, eager = i in near)
    }

    /**
     * The page's pixels, ready to draw — its GPU-legal slices — or null while it
     * is not (yet) in memory.
     *
     * Called from the reader's composition, off the main thread. [targetW] is the
     * width the reader will DRAW at, so a page wider than the phone is
     * downsampled by the decoder itself instead of being decoded at full size
     * and scaled on the GPU every frame. [targetH] is a height the DECODE should
     * fit into, for the modes that draw the whole page scaled into the viewport
     * (0 = no ceiling: the page is decoded at the reader's width and cut into
     * slices, which is what the webtoon strip wants). A page that is on disk but
     * not in the cache is decoded here and remembered; the caller re-asks when it
     * wants it again, and the answer is then a map lookup.
     *
     * Returns null for a page that has no file (never fetched, or the system
     * cleared the cache directory) and for one that cannot be decoded — the
     * latter is a page the reader shows as its retry row, because the fetch that
     * "succeeded" evidently did not.
     */
    fun page(url: String, targetW: Int, targetH: Int): MangaPage? {
        val key = drawKey(url, targetW, targetH)
        pages.get(key)?.let { return it }
        val file = files[url] ?: (states[url]?.value as? MangaPageState.Ready)?.file ?: return null
        if (!file.exists() || file.length() <= 0L) return null
        val lock = decodes.getOrPut(key) { Any() }
        synchronized(lock) {
            // Another composable may have decoded it while this one waited.
            pages.get(key)?.let { return it }
            val decoded = decodeFor(file, targetW, targetH) ?: return null
            val page = slice(decoded) ?: return null
            pages.put(key, page)
            return page
        }
    }

    // ---- Fetching -----------------------------------------------------------

    private suspend fun fetch(
        url: String,
        headers: Map<String, String>,
        state: MutableStateFlow<MangaPageState>,
        eager: Boolean,
    ) {
        var lastReason = "unknown"
        for (attempt in 1..MAX_ATTEMPTS) {
            state.value = MangaPageState.Loading(attempt)
            val result = runCatching { get(url, headers, eager, attempt) }
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
    private fun get(url: String, headers: Map<String, String>, eager: Boolean, attempt: Int): MangaPageState? {
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
            // The last gate, and the only one no header can stand in for: the
            // bytes must actually DECODE, at the size the reader will draw them.
            // A file can be a complete, correctly-terminated JPEG whose scan data
            // is damaged — every structural check above passes, the decoder
            // returns a bitmap full of displaced blocks, and the reader shows a
            // page "cut into rectangles" forever with nothing to retry. Decoding
            // here turns that into a failed attempt, which is retried like any
            // other. It is also the decode the reader would have done anyway, so
            // when [eager] is set the result is KEPT (see [pages]) and the page
            // is one blit away when the thumb reaches it.
            val key = drawKey(url, if (eager) decodeWidth else 0, 0)
            val decoded = synchronized(decodes.getOrPut(key) { Any() }) {
                decodeFor(file, if (eager) decodeWidth else 0, 0)
            }
            if (decoded == null) {
                file.delete()
                files.remove(url, file)
                return null
            }
            // A page that is taller than one drawn slice is logged once, because
            // that number is the difference between a page that draws and one
            // that the GPU draws as a tiled wreck (see [MAX_DRAW_H]) — and it is
            // the first thing a report about a broken page needs to say.
            if (decoded.height > MAX_DRAW_H) {
                com.hikari.app.data.Logs.log(
                    "Manga",
                    "page ${decoded.width}x${decoded.height} decoded — cut into " +
                        "${(decoded.height + MAX_DRAW_H - 1) / MAX_DRAW_H} slices of at most " +
                        "${MAX_DRAW_H}px",
                )
            }
            // Only the reader's own copy is kept; a gate-only decode has no owner
            // and giving its memory back immediately is the difference between
            // fetching a chapter and thrashing the heap for one. When the page IS
            // wanted eagerly, [slice] takes ownership of the bitmap it was given
            // (it either keeps it whole or cuts it up and recycles the original),
            // so this must not recycle a second time.
            if (eager) {
                slice(decoded)?.let { pages.put(key, it) }
            } else {
                decoded.recycle()
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

    /** Writes the bytes under a FRESH name every time (see [load]'s note about
     *  Coil's cache key: the same path with new bytes would keep serving the old
     *  decode), and drops the previous file for this url. The url's DECODED page
     *  goes with it: a retry that lands a good page must never leave the old
     *  garbage on screen. */
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
        forgetDrawn(url)
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
     * Decodes a page at a size the reader can actually use: [targetW] is the
     * width it will be drawn at (the reader's own width) and [targetH] a height
     * the whole page should fit into — 0 for "no ceiling", which is what the
     * full-width webtoon strip asks for, because a strip is meant to be tall.
     *
     * The sampling is a power of two because that is what the decoder can do
     * while it decodes: `inSampleSize = 2` skips every other block as it reads
     * the file, so a 1080×12000 strip is never materialised full size. Three caps
     * apply at once — the reader's width, [MAX_DECODE_H] (memory: a strip taller
     * than that would be a 20MB+ single allocation per page) and the caller's
     * own height target — and the tightest of them wins.
     *
     * What comes back is the WHOLE page (one bitmap, possibly very tall): the
     * caller cuts it into GPU-legal slices (see [slice]). It is deliberately not
     * sampled to [MAX_DRAW_H] — that would make a long strip unreadably soft;
     * the height ceiling that matters for drawing is applied by cutting the page
     * up, not by shrinking it.
     */
    private fun decodeFor(file: File, targetW: Int, targetH: Int): Bitmap? {
        val (w, h) = bounds(file)
        if (w <= 0 || h <= 0) return null
        val tw = if (targetW <= 0) 1080 else targetW.coerceIn(600, MAX_DECODE_W)
        val th = if (targetH <= 0) MAX_DECODE_H else targetH.coerceIn(1, MAX_DECODE_H)
        var sample = 1
        while (w / sample > tw || h / sample > th) sample *= 2
        return decode(file, sample)
    }

    /**
     * Cuts a decoded page into slices no taller than [MAX_DRAW_H] (see there for
     * why a drawn page may never be taller than that), and hands back the page
     * that owns them.
     *
     * The whole-page bitmap this is given is decoded only to be cut up: nothing
     * outside this call ever sees it, so it is RECYCLED here — which is safe
     * precisely because it was never handed to a composable (the cache's own
     * entries are never recycled, see [pages]). That matters: decoding a 1080×8000
     * strip and then slicing it means both the strip and its slices are alive at
     * once, and giving the strip's 17MB back immediately is what keeps the peak
     * at one page rather than two.
     *
     * Null means the platform refused to allocate the slices; the caller treats
     * that like any other undecodable page.
     */
    private fun slice(full: Bitmap): MangaPage? {
        val w = full.width
        val h = full.height
        if (w <= 0 || h <= 0) {
            full.recycle()
            return null
        }
        if (h <= MAX_DRAW_H) return MangaPage(listOf(full), w, h)
        val slices = ArrayList<Bitmap>((h + MAX_DRAW_H - 1) / MAX_DRAW_H)
        return try {
            var y = 0
            while (y < h) {
                val sliceH = minOf(MAX_DRAW_H, h - y)
                slices.add(Bitmap.createBitmap(full, 0, y, w, sliceH))
                y += sliceH
            }
            MangaPage(slices, w, h)
        } catch (t: Throwable) {
            slices.forEach { runCatching { it.recycle() } }
            null
        } finally {
            full.recycle()
        }
    }

    /** One BitmapFactory pass over [file], sampled by [sample] (see
     *  [decodeFor]). Null for anything the platform refuses to decode — which is
     *  precisely the answer [get] needs. */
    private fun decode(file: File, sample: Int): Bitmap? = runCatching {
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            // A page is opaque artwork: an alpha channel would be 255
            // everywhere, at a quarter of every page's memory and every frame's
            // upload. RGB_565 halves both, which is what keeps a window of
            // webtoon pages in memory and the strip's scrolling smooth.
            inPreferredConfig = Bitmap.Config.RGB_565
            inScaled = false
        })
    }.getOrNull()

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
                    // and so do their decoded pages and their Ready states: a
                    // bitmap whose file was just evicted can never be re-read,
                    // and a state that keeps claiming Ready for a file that is
                    // gone is exactly how a page ends up drawn as a blank cell
                    // with no retry offered.
                    val gone = files.entries.filter { it.value.absolutePath == f.absolutePath }
                        .map { it.key }
                    if (f.delete()) {
                        total -= len
                        for (url in gone) {
                            files.remove(url)
                            forgetDrawn(url)
                            states[url]?.value = MangaPageState.Idle
                        }
                    }
                }
            }
        }
    }
}
