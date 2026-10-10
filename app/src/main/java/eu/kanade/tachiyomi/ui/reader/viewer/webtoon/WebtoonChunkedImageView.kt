package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderDiagnostics
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Semaphore
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Renders a pathologically tall webtoon strip (one whose whole-strip decode would exceed the
 * reader's single-decode memory budget) as a stack of display-width chunk bitmaps decoded from the
 * page's cache file. Chunks are decoded lazily nearest-to-the-eye first, and — critically for
 * scroll smoothness — ONCE: a decoded chunk stays cached for the page's lifetime (so scrolling and
 * re-scrolling is a set of stable bitmaps the render thread has already texture-cached, with no
 * per-frame decode/upload churn). Memory is bounded by recycling only the chunks farthest from the
 * viewport once the retained total exceeds a byte budget.
 *
 * Render-path rules:
 *  - onDraw ONLY draws the chunk range overlapping the viewport (+1 margin) — never scans,
 *    recycles or launches work.
 *  - Decode/window management runs OFF the draw path, driven by the recycler's scroll listener,
 *    the view's layout pass, and the per-draw "viewport moved?" check — each coalesced into at
 *    most one posted pass.
 *  - Chunks are drawn at the PAGE's own aspect ratio: the display scale is viewWidth /
 *    sourceWidth, so chunk i is drawn at source rows [srcTop(i), srcBottom(i)) mapped to
 *    [srcTop(i)*sx, srcBottom(i)*sx) — its true row count at the true scale. Nothing is stretched:
 *    the strip's decoded row count is not an even multiple of [chunkHeight] (2048 almost never
 *    divides it), so the old equal-slot mapping (`slotH = viewHeight / partCount`) rescaled every
 *    chunk to the same height and inflated the short last chunk to a full slot. Contiguous source
 *    rows map to contiguous display rows, so the chunks still tile with no gaps. Missing
 *    (not-yet-decoded) chunks just skip their range; neighbours stay aligned.
 *  - Chunks decode as plain software ARGB_8888/RGB_565. BitmapRegionDecoder CANNOT produce
 *    hardware bitmaps (Android rejects HARDWARE config for region decode — the old code attempted
 *    it and silently fell back to software on every chunk), and that silent fallback plus the
 *    per-scroll recycling was the remaining comix jank. Software chunks are fine here because they
 *    are stable for the page's lifetime.
 *  - A single global semaphore caps how many region decodes run at once across ALL live pages.
 *
 * The view is scroll-aware: each pass reads its position in the recycler (via the holder's `top` —
 * the view itself fills the holder, so its own `top` is always 0) and decodes/recycles chunks
 * around the visible window. Touches are ignored — the reader's scroll container owns all
 * gestures. Each chunk is capped at [chunkHeight] display pixels so no single bitmap approaches
 * the 4096 GPU texture limit, and a fresh [BitmapRegionDecoder] is opened per chunk (cheap; no
 * decoder state is shared across coroutines).
 */
class WebtoonChunkedImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Target height (px) of each chunk in decode space. Keeps every bitmap comfortably under the
     *  GPU's 4096 texture limit and keeps per-chunk decode latency a few frames at most. */
    private val chunkHeight: Int = 2048

    /** Decode this many chunk-heights past the viewport edge (ahead = scroll direction). */
    private val decodeBehindChunks: Int = 1
    private val decodeAheadChunks: Int = 2

    /** Hard cap on retained decoded chunk bytes per page; beyond it the chunks farthest from the
     *  viewport are recycled (they re-decode on demand if the user scrolls back). */
    private val maxRetainedBytes: Int = 64 * 1024 * 1024

    private var scope: CoroutineScope? = null

    /** Builds the chunk layout for the current page (IO); the decode loop is a separate job so a
     *  finished/in-flight decode loop can be re-kicked independently of the layout build. */
    private var infoJob: Job? = null
    private var decodeJob: Job? = null

    /** Chunk indices currently being decoded by a worker (so two workers never decode the same
     *  chunk). Bookkeeping happens only on the main thread (workers decode on IO). */
    private val inFlight = mutableSetOf<Int>()

    /** Chunk indices whose decode already failed for this page. They are skipped so a transient
     *  failure isn't retried in a hot loop; only a failure on a chunk overlapping the actual
     *  viewport (or the failure of every chunk) fails the page. */
    private val failed = mutableSetOf<Int>()
    private var errorFired = false

    /** Chunk indices that already got their one retry after a failure (see [updateVisible]). */
    private val retried = mutableSetOf<Int>()

    /** How many chunk-decode workers run in parallel per page. The global [decodeSemaphore] still
     *  bounds the total across all live pages, so several pages can't multiply this. */
    private val decodeWorkers = 2

    /** Bumped on every setChunkedImage/recycle/detach so stale in-flight work recognizes itself. */
    private var generation = 0L

    private class ChunkInfo(
        val file: File,
        val srcWidth: Int,
        val srcHeight: Int,
        val sample: Int,
        val partCount: Int,
        val chunkHeight: Int,
        /** Approx bytes of one full chunk bitmap (decode width x chunk height x bpp). */
        val chunkBytes: Int,
    ) {
        fun srcTop(i: Int): Int = i * chunkHeight * sample
        fun srcBottom(i: Int): Int = minOf(srcHeight, (i + 1) * chunkHeight * sample)
    }

    private var info: ChunkInfo? = null

    /** Decoded chunk bitmaps, one entry per source chunk: index i holds source rows
     *  [srcTop(i), srcBottom(i)) and therefore occupies display rows starting at `i * step` (step =
     *  one full chunk's display height at the view width). Null entries are not decoded (yet). */
    private val bitmaps = ArrayList<Bitmap?>(0)

    private var decodeWidth: Int = 0
    private var rgb565: Boolean = false
    private var readyFired = false

    private var decodeWindow: IntRange? = null

    /** The most recent [setChunkedImage] request, remembered in case it arrives before the view is
     *  attached to a window (a fresh bind): the per-view coroutine scope only exists while attached,
     *  so the load is started from [onAttachedToWindow] instead of being dropped. */
    private var pendingFile: File? = null

    /** Page label for the diagnostics lines this view emits, set by [ReaderPageImageView] at bind
     *  time so a `chunked …` line is attributed to the page it actually belongs to (the shared
     *  [ReaderDiagnostics.currentLabel] is whatever page bound most recently, which made the earlier
     *  logs look like page 6's chunk build never ran when it was simply relabelled). */
    var debugLabel: String = "-"

    var onReady: (() -> Unit)? = null
    var onError: (() -> Unit)? = null

    /** The recycler this page lives in (cached at attach) — drives viewport-height reads and the
     *  scroll listener that re-targets decoding as the user scrolls. */
    private var recyclerView: RecyclerView? = null
    private var scrollListener: RecyclerView.OnScrollListener? = null

    /** Viewport rows (display px) last applied by [updateVisible]. onDraw draws only the chunk
     *  range overlapping them, and any pass that sees the viewport move re-targets decoding. */
    private var lastViewportTop = 0
    private var lastViewportBottom = 0

    /** Rate-limits the "visible chunk missing" diagnostic emitted from the draw path. */
    private var lastGapKey = ""
    private var lastGapLoggedAt = 0L

    /** Coalesced re-target: at most one updateVisible is queued at a time, so a scroll burst (or
     *  the per-draw viewport check) never floods the main thread with redundant window passes. */
    private var visibleUpdatePosted = false
    private val visibleUpdateRunnable = Runnable {
        visibleUpdatePosted = false
        updateVisible()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        var p = parent
        while (p != null) {
            if (p is RecyclerView) {
                recyclerView = p
                break
            }
            p = p.parent
        }
        scrollListener = object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                scheduleUpdateVisible()
            }
        }
        recyclerView?.addOnScrollListener(scrollListener!!)
        // A setChunkedImage that landed before we were attached starts here (the scope it needed
        // didn't exist yet, and without this the first-bound page would stay blank forever). If the
        // layout was already built before a detach/re-attach cycle, just resume decoding into it —
        // rebuilding from scratch on every detach is what left long strips permanently black.
        if (info == null) {
            pendingFile?.let { startLoad(generation, it) }
        } else {
            scheduleUpdateVisible()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scrollListener?.let { recyclerView?.removeOnScrollListener(it) }
        scrollListener = null
        recyclerView = null
        removeCallbacks(visibleUpdateRunnable)
        visibleUpdatePosted = false
        // A RecyclerView detaches a child every time it scrolls out of view — even briefly, into its
        // view cache — and re-attaches it on the way back. Tearing the whole load down here (the old
        // behaviour: cancel + info = null + releaseChunks) meant a 15k-px strip restarted from a
        // bounds-decode and a fresh chunk layout every time it left the screen, so during normal
        // scrolling it never accumulated more than a chunk or two and read as black. Stop the jobs
        // (the scope is per-attach) but KEEP the built layout: re-attaching resumes decoding into it.
        // The decoded chunk bitmaps themselves are still released here so cached/off-screen holders
        // can't pin up to [maxRetainedBytes] each; they re-decode quickly from the known layout.
        infoJob?.cancel()
        infoJob = null
        decodeJob?.cancel()
        decodeJob = null
        inFlight.clear()
        releaseChunks()
        scope?.cancel()
        scope = null
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // A page appearing at its slot (or a relayout without a scroll event) still needs its
        // viewport window targeted before the first draw.
        if (info != null) scheduleUpdateVisible()
    }

    /** Starts (re)loading [file] as display-width chunks.
     *
     *  Idempotent for the same page: a RecyclerView re-binds and re-attaches holders constantly
     *  (the dx21 log shows page 1's chunk layout built five times in a few seconds), and the reader
     *  re-binds every visible holder whenever a render setting changes. The old code tore the load
     *  down on every one of those calls (`cancelAll()` + `info = null`), which discarded every
     *  decoded chunk — and because a 15k-px strip's chunks take longer to decode than the gap
     *  between re-binds, the page never got past a chunk or two and stayed black. When the same file
     *  with the same decode parameters is already built or being built, keep the work and just
     *  re-target the window. Any previous load of a DIFFERENT page is still cancelled and released.
     */
    fun setChunkedImage(file: File, decodeWidthPx: Int, decodeRgb565: Boolean) {
        val width = decodeWidthPx.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val sameRequest = pendingFile == file && decodeWidth == width && rgb565 == decodeRgb565
        if (sameRequest && (info != null || infoJob?.isActive == true)) {
            pendingFile = file
            scheduleUpdateVisible()
            invalidate()
            return
        }
        cancelAll()
        generation++
        val gen = generation
        this.decodeWidth = width
        this.rgb565 = decodeRgb565
        readyFired = false
        invalidate()
        pendingFile = file
        startLoad(gen, file)
    }

    private fun startLoad(gen: Long, file: File) {
        val sc = scope ?: return
        infoJob = sc.launch {
            val built = try {
                withContext(Dispatchers.IO) { buildChunkInfo(file, decodeWidth) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (gen == generation) onError?.invoke()
                return@launch
            }
            if (gen != generation || !isActive) return@launch
            info = built
            bitmaps.clear()
            repeat(built.partCount) { bitmaps.add(null) }
            decodeWindow = null
            ReaderDiagnostics.logFor(
                debugLabel,
                "chunked info: decodeW=${built.srcWidth / built.sample} " +
                    "sample=${built.sample} partCount=${built.partCount} " +
                    "chunkBytes=${built.chunkBytes / 1024}KB",
            )
            invalidate()
            updateVisible()
        }
    }

    /** Cancels any in-flight decode and frees the decoded chunks (holder recycled / page changed). */
    fun recycle() {
        cancelAll()
        generation++
        pendingFile = null
        releaseChunks()
        invalidate()
    }

    private fun cancelAll() {
        infoJob?.cancel()
        infoJob = null
        decodeJob?.cancel()
        decodeJob = null
        inFlight.clear()
        failed.clear()
        retried.clear()
        errorFired = false
        info = null
        decodeWindow = null
    }

    /** Recycles every decoded chunk. The slot list keeps its length so a view whose layout survived a
     *  detach/re-attach cycle can resume decoding into the same positions (see
     *  [onDetachedFromWindow]) — clearing the list would make [updateVisible] bail out and the page
     *  never fill in. */
    private fun releaseChunks() {
        for (i in bitmaps.indices) {
            bitmaps[i]?.let { recycleChunk(it) }
            bitmaps[i] = null
        }
    }

    private fun recycleChunk(b: Bitmap) {
        if (!b.isRecycled) runCatching { b.recycle() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cur = info ?: return
        if (bitmaps.isEmpty()) return
        // Cheap field reads only: if the viewport moved since the last pass, queue a posted
        // re-target (the actual window scan/recycle/decode kick never runs inside draw).
        val vTop = viewportTop()
        val vBottom = vTop + viewportHeight()
        if (vTop != lastViewportTop || vBottom != lastViewportBottom) {
            lastViewportTop = vTop
            lastViewportBottom = vBottom
            scheduleUpdateVisible()
        }
        // Draw ONLY the chunk range overlapping the viewport (+1 margin). A long page can hold many
        // decoded chunks but only 2-4 are ever on screen — walking/drawing all of them per frame is
        // wasted work.
        val step = chunkStep()
        val first = ((lastViewportTop / step) - 1).coerceIn(0, bitmaps.lastIndex)
        val last = ((lastViewportBottom / step) + 1).coerceIn(0, bitmaps.lastIndex)
        val dw = width.coerceAtLeast(1).toFloat()
        // Display pixels per source pixel: the page fills the view's WIDTH, and each chunk keeps
        // the height of its own source rows at that width. Drawing by source geometry (instead of
        // stretching every chunk into an equal `height / partCount` slot) means the strip is never
        // rescaled vertically — the decoded rows are almost never an even multiple of chunkHeight,
        // so equal slots used to compress every full chunk and inflate the short last one to a full
        // slot. Chunk i's display top is exactly i*step, so the chunks still tile without gaps.
        val sx = dw / cur.srcWidth
        val dst = RectF()
        var missing = 0
        for (i in first..last) {
            val b = bitmaps[i]
            if (b == null) {
                missing++
                continue
            }
            dst.set(0f, cur.srcTop(i) * sx, dw, cur.srcBottom(i) * sx)
            canvas.drawBitmap(b, null, dst, null)
        }
        // A not-yet-decoded (or failed) chunk is invisible in the log — the page just shows a black
        // field — so record it (rate-limited) to pin any remaining black band to exact chunk indices.
        if (missing > 0) logMissingChunks(first, last, missing)
    }

    /** Rate-limited diagnostic for a black region: the visible chunk range has undecoded chunks. */
    private fun logMissingChunks(first: Int, last: Int, missing: Int) {
        val now = android.os.SystemClock.elapsedRealtime()
        val key = "$first-$last-$missing"
        if (key == lastGapKey && now - lastGapLoggedAt < 2000) return
        lastGapKey = key
        lastGapLoggedAt = now
        ReaderDiagnostics.logFor(
            debugLabel,
            "chunked GAP ${missing}/${last - first + 1} drawn chunks missing " +
                "(range $first..$last) decoded=${bitmaps.count { it != null }}/${bitmaps.size} " +
                "failed=${failed.size}",
        )
    }

    /** Computes the decode-space row layout for [file]: power-of-two sample so the decoded width
     *  is at least [decodeWidthPx] (BitmapRegionDecoder only supports power-of-two sampling) and
     *  no dimension exceeds the GPU texture limit. Bounds-only decode; throws on unreadable files. */
    private fun buildChunkInfo(file: File, decodeWidthPx: Int): ChunkInfo {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        val srcW = opts.outWidth
        val srcH = opts.outHeight
        if (srcW <= 0 || srcH <= 0) throw java.io.IOException("Couldn't read image dimensions")
        var sample = 1
        while (srcW / (sample * 2) >= decodeWidthPx || srcW / sample > 4096) sample *= 2
        val partCount = (srcH / sample + chunkHeight - 1) / chunkHeight
        val bpp = if (rgb565) 2 else 4
        val chunkBytes = (srcW / sample) * chunkHeight * bpp
        return ChunkInfo(file, srcW, srcH, sample, partCount, chunkHeight, chunkBytes)
    }

    /** The display-space height (px) of one full chunk at the current view width: the chunk's real
     *  source rows ([chunkHeight] * [ChunkInfo.sample]) scaled by the page's x scale. Chunk i's
     *  display top is exactly `i * this` (see [onDraw]), so the decode window and the
     *  nearest-chunk targeting are exact rather than a `viewHeight / partCount` approximation.
     *  Falls back to [chunkHeight] before the view has a measured width. */
    private fun chunkStep(): Int {
        val cur = info ?: return chunkHeight
        val w = width
        if (w <= 0 || cur.srcWidth <= 0) return chunkHeight
        return (w.toFloat() * cur.chunkHeight * cur.sample / cur.srcWidth).toInt().coerceAtLeast(1)
    }

    /** Re-targets the decode window to the current viewport, trims decoded chunks to the memory
     *  budget, and (re)starts the decode loop if it isn't running. Runs off the draw path, at most
     *  once per scroll burst (see [scheduleUpdateVisible]). */
    private fun updateVisible() {
        val cur = info ?: return
        if (bitmaps.isEmpty()) return
        val vTop = viewportTop()
        val vBottom = vTop + viewportHeight()
        lastViewportTop = vTop
        lastViewportBottom = vBottom
        val partCount = cur.partCount
        val step = chunkStep()
        val first = ((vTop / step) - decodeBehindChunks).coerceIn(0, partCount - 1)
        val last = ((vBottom / step) + decodeAheadChunks).coerceIn(0, partCount - 1)
        decodeWindow = first..last
        // Give a chunk that failed while it was outside the window one more chance now that it is
        // entering it: a transient failure (a memory spike, a decode that lost its permit when a
        // re-bind cancelled the page) must not leave a permanent black hole in the strip. Each chunk
        // is retried at most once, so a genuinely undecodable region can't spin.
        if (failed.isNotEmpty()) {
            for (i in first..last) {
                if (i in failed && retried.add(i)) {
                    failed.remove(i)
                    errorFired = false
                }
            }
        }
        trimToBudget(vTop, vBottom, cur)
        kickDecodeLoop()
    }

    /** Decoded chunks stay cached for the page's lifetime (so scrolling and re-scrolling is a set
     *  of stable bitmaps — never re-decode/re-upload churn). Only when the retained total exceeds
     *  [maxRetainedBytes] are the chunks farthest from the viewport recycled, to bound memory on
     *  pathological mega-strips. */
    private fun trimToBudget(vTop: Int, vBottom: Int, cur: ChunkInfo) {
        val decoded = bitmaps.indices.filter { bitmaps[it] != null }
        if (decoded.size * cur.chunkBytes <= maxRetainedBytes) return
        val center = vTop + (vBottom - vTop) / 2
        val step = chunkStep()
        // Farthest from the viewport's centre first, so the visible/upcoming chunks are kept.
        val order = decoded.sortedBy { -abs(it * step + step / 2 - center) }
        var total = decoded.size * cur.chunkBytes
        for (i in order) {
            if (total <= maxRetainedBytes) break
            recycleChunk(bitmaps[i]!!)
            bitmaps[i] = null
            total -= cur.chunkBytes
        }
    }

    /** The scroll offset of this page's top edge within the recycler viewport, in display px.
     *  The chunked view fills its holder, so the holder's `top` (relative to the recycler content)
     *  is the page's position: when scrolled down by S, holder.top = itemTop - S, so the viewport
     *  covers page rows [-holder.top, -holder.top + viewportHeight). */
    private fun viewportTop(): Int {
        val holder = parent as? View ?: return 0
        return -holder.top
    }

    private fun viewportHeight(): Int {
        val rv = recyclerView
        if (rv != null) return rv.height
        var p = parent
        while (p != null) {
            if (p is RecyclerView) return p.height
            p = p.parent
        }
        return height
    }

    /** True if chunk [idx]'s display slot overlaps the viewport rows last seen by the draw pass. */
    private fun isChunkVisible(idx: Int): Boolean {
        val step = chunkStep()
        val first = lastViewportTop / step
        val last = lastViewportBottom / step
        return idx in first..last
    }

    private fun scheduleUpdateVisible() {
        if (visibleUpdatePosted) return
        visibleUpdatePosted = true
        post(visibleUpdateRunnable)
    }

    private fun kickDecodeLoop() {
        if (decodeJob?.isActive == true) return
        val gen = generation
        decodeJob = scope?.launch {
            val workers = List(decodeWorkers) { launch { decodeWorker(gen) } }
            workers.forEach { it.join() }
        }
    }

    /** One decode worker: pulls the missing chunk nearest the viewport's centre and decodes it,
     *  repeating until the window is fully decoded (or the page generation changed / cancelled).
     *  Several workers run concurrently so a fast fling fills blank regions quickly, but the global
     *  [decodeSemaphore] still bounds total concurrent decodes across all live pages. */
    private suspend fun CoroutineScope.decodeWorker(gen: Long) {
        while (isActive && gen == generation) {
            val idx = nextChunkToDecode() ?: return
            inFlight.add(idx)
            try {
                val bmp = decodeChunk(idx)
                if (bmp == null) {
                    // A failed chunk is skipped, not retried in a loop. Only fail the page when a
                    // chunk the user is actually looking at failed (fail fast — don't leave a hole
                    // at their viewport with a spinner), or when every chunk failed.
                    failed.add(idx)
                    if (!errorFired && (isChunkVisible(idx) || failed.size >= bitmaps.size)) {
                        errorFired = true
                        onError?.invoke()
                    }
                    continue
                }
                if (gen != generation) {
                    recycleChunk(bmp)
                    return
                }
                bitmaps[idx] = bmp
                invalidate()
                if (!readyFired) {
                    readyFired = true
                    ReaderDiagnostics.logFor(debugLabel, "chunked ready (first chunk $idx of ${bitmaps.size})")
                    onReady?.invoke()
                }
            } finally {
                inFlight.remove(idx)
            }
        }
    }

    /** The missing chunk in the decode window nearest the viewport's vertical centre (what the
     *  user is looking at decodes first). Null when the window is fully decoded. */
    private fun nextChunkToDecode(): Int? {
        val win = decodeWindow ?: return null
        var best: Int? = null
        var bestDist = Int.MAX_VALUE
        val step = chunkStep()
        val center = viewportTop() + viewportHeight() / 2
        for (i in win) {
            if (i < 0 || i >= bitmaps.size || bitmaps[i] != null) continue
            if (i in inFlight || i in failed) continue
            val dist = abs(i * step + step / 2 - center)
            if (dist < bestDist) {
                bestDist = dist
                best = i
            }
        }
        return best
    }

    /** Decodes one chunk on the IO dispatcher with a fresh decoder (never shared, so no concurrent
     *  decodeRegion hazard). Software ARGB_8888/RGB_565 only — BitmapRegionDecoder cannot produce
     *  hardware bitmaps (Android rejects HARDWARE for region decode; the old code attempted it and
     *  silently fell back to software on every chunk). Returns null on any decode failure. */
    private suspend fun decodeChunk(idx: Int): Bitmap? {
        val cur = info ?: return null
        return withContext(Dispatchers.IO) {
            withDecodePermit {
                try {
                    val decoder = newRegionDecoder(cur.file)
                    try {
                        val rect = Rect(0, cur.srcTop(idx), cur.srcWidth, cur.srcBottom(idx))
                        val opts = BitmapFactory.Options().apply {
                            inSampleSize = cur.sample
                            inPreferredConfig = if (rgb565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
                        }
                        decoder.decodeRegion(rect, opts)
                    } finally {
                        decoder.recycle()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Never fail silently: a null chunk is a black hole in the strip, and the old
                    // code discarded the reason. The label comes from the bind, so this names the page.
                    ReaderDiagnostics.logFor(
                        debugLabel,
                        "chunked decode FAILED idx=$idx rows=${cur.srcTop(idx)}..${cur.srcBottom(idx)}: " +
                            "${e.javaClass.simpleName}: ${e.message}",
                    )
                    null
                }
            }
        }
    }

    private fun newRegionDecoder(file: File): BitmapRegionDecoder {
        val decoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(FileInputStream(file))
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(FileInputStream(file), false)
        }
        return decoder ?: throw java.io.IOException("Couldn't create image region decoder")
    }

    private companion object {
        /** Global cap on concurrent region decodes across ALL chunked pages. Live page holders
         *  (visible + prefetched) would otherwise multiply decodeWorkers (2 x N) and saturate the
         *  device's IO/CPU — and churn heap — during a fling. Small enough to stay out of the way,
         *  large enough that the visible page's workers (2) are never starved. */
        private val decodeSemaphore = Semaphore(3)

        private fun <T> withDecodePermit(block: () -> T): T {
            decodeSemaphore.acquire()
            try {
                return block()
            } finally {
                decodeSemaphore.release()
            }
        }
    }
}
