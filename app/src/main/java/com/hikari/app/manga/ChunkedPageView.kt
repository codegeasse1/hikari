package com.hikari.app.manga

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
import com.hikari.app.data.Logs
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Semaphore
import kotlin.math.abs
import kotlin.math.min
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
 * One manga page, drawn as a stack of BOUNDED chunk bitmaps decoded out of the
 * page's own file — the renderer the reader was supposed to have from the start.
 *
 * **Where this file comes from.** It is `WebtoonChunkedImageView` from the
 * reader the user asked for (Nekoread / Tachiyomi's webtoon viewer,
 * `eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonChunkedImageView.kt`),
 * ported with its own rules intact: chunks of at most [chunkHeight] decoded
 * px, decoded nearest-to-the-eye first, decoded ONCE and kept for the page's
 * lifetime, memory bounded by recycling only the chunks farthest from the
 * viewport, drawing only the chunk range that overlaps the viewport (plus one
 * chunk of margin), and a global cap on how many region decodes may run at once
 * across all pages. Those rules are the whole reason a 20 000 px strip scrolls
 * smoothly there and shattered into displaced blocks here.
 *
 * **What was wrong with what this replaces.** The reader used to hand a page to
 * the compositor as ONE bitmap — up to 11 000 000 px and 3200 px tall by its own
 * budget ([PageBitmaps]). Everything a page becomes on screen is a GPU texture
 * in the end, and a bitmap that is one page tall is not one texture on any phone:
 * the platform has to split the draw into a grid of tiles, and on the phones that
 * report the "the pages are still breaking" screenshots the tiles come out
 * displaced and blank — the artwork sliced into a rigid lattice of cells that
 * show the wrong part of the page, or nothing at all. The fix is not a smaller
 * budget for one bitmap; it is to make sure a page is never ONE bitmap. Every
 * image this view ever hands the compositor is a chunk: at most [chunkHeight]
 * pixels tall and at most the page's display width wide, with both dimensions
 * inside the smallest texture limit any phone reports. The old code also drew a
 * page-sized bitmap for pages that mere ratio rules called "short", which is why
 * 0.10.12's subsampling view fixed nothing the user could see: their pages were
 * never routed to it (see [PageBitmaps.isTallPage]).
 *
 * **Render-path rules (kept from the ported source, and each one is a bug it
 * prevents):**
 *
 *  - `onDraw` ONLY draws — it reads two fields, draws the visible chunk range,
 *    and posts at most one window update. It never scans, recycles or launches
 *    work, so a fling can never pay for decode bookkeeping per frame.
 *  - Decode/window management runs off the draw path ([updateVisible]), coalesced
 *    so a scroll burst produces one pass, and is also driven by layout changes.
 *  - Chunks are drawn at the PAGE's own scale: chunk i covers source rows
 *    `[srcTop(i), srcBottom(i))` and is drawn exactly there. The decoded rows are
 *    almost never an even multiple of [chunkHeight], so the alternative (splitting
 *    the view height into equal slots) would rescale every full chunk and inflate
 *    the short last one. Contiguous source rows map to contiguous display rows, so
 *    chunks tile with no gaps, and a not-yet-decoded chunk simply skips its own
 *    range while its neighbours stay aligned.
 *  - Chunks decode as plain software ARGB_8888 (or RGB_565 when asked):
 *    [BitmapRegionDecoder] cannot produce hardware bitmaps — Android rejects that
 *    config for a region decode — so asking for one only adds a silent fallback.
 *  - A fresh [BitmapRegionDecoder] per chunk, never shared between coroutines, so
 *    two workers can never contend on one decoder's seek state.
 *
 * **What Hikari changed from the ported file, and why.** Three things, all
 * mechanical:
 *
 *  1. The viewport comes from the view's own [getLocalVisibleRect], not from a
 *     RecyclerView holder: Hikari's reader is a Compose `LazyColumn`, and the
 *     fraction of this page that is on screen IS the visible rect. The ported
 *     code's arithmetic is unchanged (`viewportTop()` is page row 0's offset).
 *  2. [fitInside] — Hikari's paged modes draw the page scaled to FIT the viewport
 *     rather than filling its width, so the drawn size is
 *     `min(w/srcW, h/srcH)` and the decode width follows the DRAWN width. The
 *     strip mode (the default, and the shape every webtoon is read in) is exactly
 *     the ported behaviour.
 *  3. Diagnostics go to Hikari's log ([Logs]) instead of the ported app's
 *     overlay, one line per page: the path, the page's real size, the decode
 *     width, the chunk count — so a broken page can be pinned to a file and a
 *     shape instead of a guess.
 *
 * Touch is ignored: the reader's own list owns every gesture, exactly as the
 * ported viewer does.
 */
internal class ChunkedPageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /**
     * Target height (px) of each chunk in DECODE space — the ported value.
     *
     * It is the whole point of the class: every bitmap this view draws is at most
     * this tall, which keeps it comfortably under the smallest texture limit a
     * phone reports, and keeps one chunk's decode latency to a few frames. It is
     * also why a webtoon's height stops mattering — a 20 000 px strip is simply
     * more chunks, not a bigger bitmap.
     */
    private val chunkHeight: Int = 2048

    /** Chunk-heights decoded past each viewport edge (ahead = the scroll direction). */
    private val decodeBehindChunks: Int = 1
    private val decodeAheadChunks: Int = 2

    /** Hard cap on retained decoded chunk bytes per page; past it the chunks
     *  farthest from the viewport are recycled and re-decode if the user scrolls
     *  back. The ported value: enough for a screenful of chunks either way. */
    private val maxRetainedBytes: Int = 64 * 1024 * 1024

    /**
     * Draw the page SCALED TO FIT the view (Hikari's paged fit modes) instead of
     * filling its width (the webtoon strip, and every page in `MangaFit.WIDTH`).
     *
     * Set before [setPage]; a change rebuilds everything, because the drawn
     * width is what decides the decode width.
     */
    var fitInside: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            rebuildForSize()
        }

    /** Page label for the log lines this view emits ("Chapter 12 · page 7"). */
    var debugLabel: String = "-"

    /** First chunk of this page is on screen — the reader's spinner can go. */
    var onReady: (() -> Unit)? = null

    /** The page cannot be decoded at all (see [MangaPageLoader.markUndecodable]). */
    var onError: (() -> Unit)? = null

    private var scope: CoroutineScope? = null

    /** Builds the chunk layout for the current page (IO); decoding is a separate
     *  job so a finished/in-flight decode loop can be re-kicked on its own. */
    private var infoJob: Job? = null
    private var decodeJob: Job? = null

    /** Chunk indices being decoded right now (so two workers never take the same
     *  one). Only ever touched on the main thread. */
    private val inFlight = mutableSetOf<Int>()

    /** Chunk indices whose decode already failed for this page — skipped so a
     *  transient failure is not retried in a hot loop. Each one gets exactly one
     *  more chance if the reader scrolls onto it (see [updateVisible]). */
    private val failed = mutableSetOf<Int>()
    private var errorFired = false
    private var retried = mutableSetOf<Int>()

    /** Decode workers per page. The global [decodeSemaphore] still bounds the
     *  total across all live pages, so several pages cannot multiply this. */
    private val decodeWorkers = 2

    /** Bumped on every load/recycle/detach so stale in-flight work recognises
     *  itself and drops its result instead of drawing the previous page's rows. */
    private var generation = 0L

    /**
     * The layout of one page's chunks, in decode space.
     *
     * [sample] is a power of two because that is all [BitmapRegionDecoder] can do,
     * chosen so the decoded width is at least the width the page is drawn at (no
     * upscaling — chunks are never soft) and no single image dimension exceeds
     * [MAX_DECODE_DIM]. [partCount] is how many [chunkHeight]-tall chunks that
     * comes to; chunk `i` holds source rows `[srcTop(i), srcBottom(i))`.
     */
    private class ChunkInfo(
        val file: File,
        val srcWidth: Int,
        val srcHeight: Int,
        val sample: Int,
        val partCount: Int,
        val chunkHeight: Int,
        /** Approx bytes of one full chunk bitmap (decode width x chunk height x bpp). */
        val chunkBytes: Int,
        /** View size the layout was built for — a resize rebuilds it. */
        val viewWidth: Int,
        val viewHeight: Int,
    ) {
        fun srcTop(i: Int): Int = i * chunkHeight * sample
        fun srcBottom(i: Int): Int = minOf(srcHeight, (i + 1) * chunkHeight * sample)
    }

    private var info: ChunkInfo? = null

    /** Decoded chunk bitmaps, one slot per source chunk; `null` = not decoded yet. */
    private val bitmaps = ArrayList<Bitmap?>(0)

    private var decodeWidth: Int = 0
    private var rgb565: Boolean = false
    private var readyFired = false

    /** Chunk indices the decode loop is currently targeting. */
    private var decodeWindow: IntRange? = null

    /** Remembered in case the load arrives before the view is attached: the scope
     *  it needs only exists while attached, so the load starts from
     *  [onAttachedToWindow] rather than being dropped. */
    private var pendingFile: File? = null

    /** Viewport rows (display px) last applied by [updateVisible]. `onDraw` draws
     *  only the chunk range overlapping them; any pass that sees the viewport move
     *  re-targets decoding. */
    private var lastViewportTop = 0
    private var lastViewportBottom = 0

    private var visibleUpdatePosted = false
    private val visibleUpdateRunnable = Runnable {
        visibleUpdatePosted = false
        updateVisible()
    }

    private val visibleRect = Rect()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        // A page bound before the view was attached starts here (the scope it
        // needed did not exist yet, and without this the first-bound page would
        // stay blank forever). If a layout was already built, just resume
        // decoding into it — rebuilding on every re-attach is what left long
        // strips permanently empty in the ported app.
        if (info == null) pendingFile?.let { startLoad(generation, it) } else scheduleUpdateVisible()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(visibleUpdateRunnable)
        visibleUpdatePosted = false
        // Stop the jobs (the scope is per-attach) but KEEP the built layout: a
        // Compose list detaches and re-attaches a page's view constantly while
        // scrolling, and throwing the layout away each time meant a long strip
        // restarted from a bounds decode again and again. The decoded bitmaps
        // themselves go — a detached page must not pin its byte budget.
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
        if (info == null) return
        // A resize changes the drawn width, and therefore the decode width the
        // chunks were built for (see [decodeWidthFor]) — rebuild rather than draw
        // chunks that are too coarse or too fine for the new size.
        val cur = info ?: return
        if (cur.viewWidth != width || cur.viewHeight != height) {
            rebuildForSize()
            return
        }
        scheduleUpdateVisible()
    }

    /**
     * Loads [file] as display-width chunks. Idempotent for the same page: a list
     * re-binds (a re-composition, a settings change, a scroll in and out) far more
     * often than a page changes, and tearing the load down each time would discard
     * every decoded chunk — which is exactly how a tall page ends up with a blank
     * field and no error. Any load of a DIFFERENT page is still cancelled and
     * released, so a recycled view can never mix two pages' chunks.
     */
    fun setPage(file: File, decodeRgb565: Boolean = false) {
        val sameRequest = pendingFile == file && rgb565 == decodeRgb565
        if (sameRequest && (info != null || infoJob?.isActive == true)) {
            scheduleUpdateVisible()
            invalidate()
            return
        }
        cancelAll()
        generation++
        val gen = generation
        rgb565 = decodeRgb565
        readyFired = false
        invalidate()
        pendingFile = file
        startLoad(gen, file)
    }

    /** There is no page to draw (the reader's Failed row, a recycled slot). */
    fun clear() {
        cancelAll()
        generation++
        pendingFile = null
        releaseChunks()
        invalidate()
    }

    private fun rebuildForSize() {
        val file = pendingFile ?: return
        cancelAll()
        generation++
        readyFired = false
        decodeJob = null
        startLoad(generation, file)
    }

    private fun startLoad(gen: Long, file: File) {
        val sc = scope ?: return
        infoJob = sc.launch {
            val built = try {
                withContext(Dispatchers.IO) { buildChunkInfo(file) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logs.log("Manga", "page $debugLabel: chunk layout failed (${e.message})")
                if (gen == generation) onError?.invoke()
                return@launch
            }
            if (gen != generation || !isActive) return@launch
            info = built
            decodeWidth = built.srcWidth / built.sample
            bitmaps.clear()
            repeat(built.partCount) { bitmaps.add(null) }
            decodeWindow = null
            // One line per page, and the numbers are the whole diagnosis: a page
            // that comes out wrong is a page whose decode width/chunk count can be
            // compared against the file's own size.
            Logs.log(
                "Manga",
                "page $debugLabel ${built.srcWidth}x${built.srcHeight} -> " +
                    "${built.srcWidth / built.sample}px, ${built.partCount} chunks of " +
                    "${built.chunkHeight} (sample ${built.sample}, " +
                    "${built.chunkBytes / 1024}KB each, view ${built.viewWidth}x${built.viewHeight})",
            )
            invalidate()
            updateVisible()
        }
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
        decodeWidth = 0
        decodeWindow = null
    }

    /** Recycles every decoded chunk but keeps the slot list's length, so a layout
     *  that survived a detach/re-attach can resume decoding into the same
     *  positions — clearing the list would make [updateVisible] bail out and the
     *  page would never fill in. */
    private fun releaseChunks() {
        for (i in bitmaps.indices) {
            bitmaps[i]?.let { recycleChunk(it) }
            bitmaps[i] = null
        }
    }

    private fun recycleChunk(b: Bitmap) {
        if (!b.isRecycled) runCatching { b.recycle() }
    }

    // ---- Drawing ------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cur = info ?: return
        if (bitmaps.isEmpty()) return
        // Cheap field reads only. A viewport that moved queues ONE posted
        // re-target; the actual scan/recycle/kick never runs inside a draw pass,
        // so a fling never pays for bookkeeping on the frame path.
        val vTop = viewportTop()
        val vBottom = vTop + viewportHeight()
        if (vTop != lastViewportTop || vBottom != lastViewportBottom) {
            lastViewportTop = vTop
            lastViewportBottom = vBottom
            scheduleUpdateVisible()
        }
        // Draw ONLY the chunk range overlapping the viewport (+1 margin). A long
        // page holds many decoded chunks and at most a few are ever on screen.
        val step = chunkStep()
        val first = ((lastViewportTop / step) - 1).coerceIn(0, bitmaps.lastIndex)
        val last = ((lastViewportBottom / step) + 1).coerceIn(0, bitmaps.lastIndex)
        val scale = drawScale(cur)
        val xOff = (width - cur.srcWidth * scale) / 2f
        val yOff = (height - cur.srcHeight * scale) / 2f
        val dst = RectF()
        for (i in first..last) {
            val b = bitmaps[i] ?: continue
            dst.set(
                xOff,
                yOff + cur.srcTop(i) * scale,
                xOff + cur.srcWidth * scale,
                yOff + cur.srcBottom(i) * scale,
            )
            canvas.drawBitmap(b, null, dst, null)
        }
    }

    /**
     * The display-space height (px) of one full chunk at the current size: the
     * chunk's real source rows ([chunkHeight] * [ChunkInfo.sample]) at the page's
     * own scale. Chunk i's display top is exactly `i * this`, so the decode window
     * and the nearest-chunk targeting are exact rather than an approximation of
     * `viewHeight / partCount`.
     */
    private fun chunkStep(): Int {
        val cur = info ?: return chunkHeight
        if (cur.srcWidth <= 0) return chunkHeight
        val step = (cur.chunkHeight * cur.sample * drawScale(cur)).toInt()
        return step.coerceAtLeast(1)
    }

    /** Display pixels per source pixel: the page fills the view's width in the
     *  strip mode, and is scaled to fit the view in the paged fit modes. */
    private fun drawScale(cur: ChunkInfo): Float {
        if (cur.srcWidth <= 0) return 1f
        val w = width.toFloat()
        if (!fitInside) return w / cur.srcWidth
        val h = height.toFloat()
        if (cur.srcHeight <= 0) return w / cur.srcWidth
        return min(w / cur.srcWidth, h / cur.srcHeight)
    }

    /**
     * The width a page's chunks must be decoded to: the width it is DRAWN at, so a
     * chunk is never upscaled (soft) and never wastes pixels the screen cannot
     * show. Read from the view's own size once it has one, and from the display
     * before that — a page bound before its first layout still gets a sane plan,
     * and [onLayout] rebuilds it if the real size disagrees.
     */
    private fun decodeWidthFor(srcWidth: Int, srcHeight: Int): Int {
        val w = if (width > 0) width else resources.displayMetrics.widthPixels
        if (!fitInside || srcWidth <= 0 || srcHeight <= 0) return w.coerceAtLeast(1)
        val h = if (height > 0) height else resources.displayMetrics.heightPixels
        val scale = min(w.toDouble() / srcWidth, h.toDouble() / srcHeight)
        return (srcWidth * scale).toInt().coerceAtLeast(1)
    }

    /** The screen-space rows of this page that are actually on screen. The reader
     *  is a list, so a page's own visible rect IS the viewport — the ported file
     *  read the same number from its RecyclerView holder's top. */
    private fun viewportTop(): Int = if (getLocalVisibleRect(visibleRect)) visibleRect.top else 0

    private fun viewportHeight(): Int =
        if (getLocalVisibleRect(visibleRect)) visibleRect.height() else height

    /** True if chunk [idx]'s display slot overlaps the viewport the draw pass last
     *  saw. */
    private fun isChunkVisible(idx: Int): Boolean {
        val step = chunkStep()
        return idx in (lastViewportTop / step)..(lastViewportBottom / step)
    }

    private fun scheduleUpdateVisible() {
        if (visibleUpdatePosted) return
        visibleUpdatePosted = true
        post(visibleUpdateRunnable)
    }

    /**
     * Builds the chunk layout for [file]: a power-of-two [ChunkInfo.sample] so the
     * decoded width is at least the drawn width (chunks are never upscaled) and no
     * decoded dimension exceeds [MAX_DECODE_DIM]. Bounds-only decode — no pixels
     * are allocated — and it throws on a file the platform cannot read at all.
     */
    private fun buildChunkInfo(file: File): ChunkInfo {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        val srcW = opts.outWidth
        val srcH = opts.outHeight
        if (srcW <= 0 || srcH <= 0) throw java.io.IOException("unreadable page header")
        val wanted = decodeWidthFor(srcW, srcH)
        var sample = 1
        while (srcW / (sample * 2) >= wanted || srcW / sample > MAX_DECODE_DIM) sample *= 2
        val partCount = (srcH / sample + chunkHeight - 1) / chunkHeight
        val bpp = if (rgb565) 2 else 4
        val chunkBytes = (srcW / sample) * chunkHeight * bpp
        return ChunkInfo(
            file, srcW, srcH, sample, partCount.coerceAtLeast(1), chunkHeight, chunkBytes,
            width, height,
        )
    }

    /**
     * Re-targets the decode window to the current viewport, trims decoded chunks to
     * the memory budget, and (re)starts the decode loop if it is not running. Runs
     * off the draw path, at most once per scroll burst ([scheduleUpdateVisible]).
     */
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
        // A chunk that failed while it was off screen gets one more chance now that
        // it is entering the window: a transient failure (a memory spike, a decode
        // that lost its permit when the page was re-bound) must not leave a
        // permanent hole in the strip. One retry each, so a genuinely undecodable
        // region cannot spin.
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

    /** Decoded chunks stay cached for the page's lifetime, so scrolling back over a
     *  page is a set of stable bitmaps rather than a re-decode. Only past
     *  [maxRetainedBytes] are the chunks farthest from the viewport dropped, which
     *  bounds a pathological mega-strip without ever touching what is on screen. */
    private fun trimToBudget(vTop: Int, vBottom: Int, cur: ChunkInfo) {
        val decoded = bitmaps.indices.filter { bitmaps[it] != null }
        if (decoded.size.toLong() * cur.chunkBytes <= maxRetainedBytes) return
        val center = vTop + (vBottom - vTop) / 2
        val step = chunkStep()
        val order = decoded.sortedBy { -abs(it * step + step / 2 - center) }
        var total = decoded.size.toLong() * cur.chunkBytes
        for (i in order) {
            if (total <= maxRetainedBytes) break
            bitmaps[i]?.let { recycleChunk(it) }
            bitmaps[i] = null
            total -= cur.chunkBytes
        }
    }

    private fun kickDecodeLoop() {
        if (decodeJob?.isActive == true) return
        val gen = generation
        decodeJob = scope?.launch {
            val workers = List(decodeWorkers) { launch { decodeWorker(gen) } }
            workers.forEach { it.join() }
        }
    }

    /** One decode worker: pulls the missing chunk nearest the viewport's centre and
     *  decodes it, until the window is full (or the generation changed). Several
     *  workers fill a fast fling's blank regions quickly; the global
     *  [decodeSemaphore] still bounds total concurrent decodes across all pages. */
    private suspend fun CoroutineScope.decodeWorker(gen: Long) {
        while (isActive && gen == generation) {
            val idx = nextChunkToDecode() ?: return
            inFlight.add(idx)
            try {
                val bmp = decodeChunk(idx)
                if (bmp == null) {
                    // A failed chunk is skipped, not retried in a loop — but a chunk
                    // the user is LOOKING at is a page that cannot be read, so it
                    // reports the failure and the reader shows its retry row instead
                    // of a silent black band.
                    failed.add(idx)
                    if (!errorFired && (isChunkVisible(idx) || failed.size >= bitmaps.size)) {
                        errorFired = true
                        Logs.log("Manga", "page $debugLabel: chunk $idx failed — page reported broken")
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
                    onReady?.invoke()
                }
            } finally {
                inFlight.remove(idx)
            }
        }
    }

    /** The missing chunk in the decode window nearest the viewport's centre — what
     *  the user is looking at decodes first. Null when the window is full. */
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

    /** Decodes one chunk on IO with a fresh decoder, so no two coroutines can
     *  contend on one region decoder's stream state. Null on any failure. */
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
                            inPreferredConfig =
                                if (rgb565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
                        }
                        decoder.decodeRegion(rect, opts)
                    } finally {
                        decoder.recycle()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Never fail silently: a null chunk is a black band in the
                    // strip, so the reason and the rows are logged with the page.
                    Logs.log(
                        "Manga",
                        "page $debugLabel: chunk $idx (rows ${cur.srcTop(idx)}.." +
                            "${cur.srcBottom(idx)}) failed — " +
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
        return decoder ?: throw java.io.IOException("could not open page region decoder")
    }

    private companion object {
        /**
         * No single decoded image may exceed this on either axis. It is the
         * ported reader's own guard against the platform's texture limit, with
         * room under the 4096 that phones report for the compositor's own scaling
         * and rounding.
         */
        const val MAX_DECODE_DIM = 4096

        /**
         * Global cap on concurrent region decodes across ALL chunked pages. Live
         * pages (the one on screen, the ones the reader keeps alive) would
         * otherwise multiply the per-page workers and saturate IO/CPU — and churn
         * the heap — during a fling. Small enough to stay out of the way, large
         * enough that the visible page's two workers are never starved.
         */
        val decodeSemaphore = Semaphore(3)

        fun <T> withDecodePermit(block: () -> T): T {
            decodeSemaphore.acquire()
            try {
                return block()
            } finally {
                decodeSemaphore.release()
            }
        }
    }
}
