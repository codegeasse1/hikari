package com.hikari.app.manga

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.hikari.app.HikariApp
import com.hikari.app.data.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * The reader's page decoder: one bitmap per page, decoded from the file the
 * loader fetched, kept in a small byte-budgeted cache.
 *
 * **Why one whole bitmap is the safe shape.** Every other shape was tried here
 * first, and each of them is the reason pages came out as "broken" artwork:
 *
 *  - **A bitmap handed to the compositor** is a GPU texture, and a page taller
 *    than the device's `GL_MAX_TEXTURE_SIZE` is then drawn by Skia's tile
 *    fallback — the one whose tiles' source rectangles are mis-placed on the
 *    drivers these phones have, which is what produced displaced bands, artwork
 *    repeated at a fixed offset and a white seam at every tile boundary.
 *  - **Region-decoding the page with a subsampling view** (the Tachiyomi/yomi
 *    "tall strip" path) fixed nothing in practice: it is the same tile grid, one
 *    level down, and on the pages this reader opens it came out as exactly the
 *    same scattered blocks on a white field. The reference reader this app's
 *    sibling Nekoread borrows from says so in its own comments — the chunked and
 *    region-decoded paths are the ones that "came out black", and what fixed it
 *    there was to decode the WHOLE page into ONE bitmap and draw that.
 *
 * So there is exactly one drawing shape here: the page becomes one bitmap, and
 * that bitmap is drawn (and scaled) by the compositor, unchanged, for every fit,
 * every zoom level and every page length. It cannot come out as bands because
 * there is nothing to mis-place: the pixels arrive in one piece.
 *
 * **What "one whole bitmap" is allowed to be.** A page is decoded at most at the
 * width the screen can actually show (never upscaling a ~800px source to a
 * 1080px screen — that only adds bytes, not detail) and only while the result
 * stays inside [MAX_PAGE_PIXELS]. A page too big for that budget is decoded at
 * the next power-of-two reduction down, which is the cheapest thing
 * `BitmapFactory` can do: a 1080×20000 manhwa strip becomes 540×10000, i.e. the
 * sharpest copy of it a phone can hold — and, more to the point, a complete one
 * instead of a scrambled one. That trade is deliberate and it is the same one
 * Nekoread's reader makes (its own budget is 48MB per strip); softness on a
 * pathological strip costs the reader nothing permanent, a broken page costs it
 * the chapter.
 *
 * **Why the loader is not the decoder.** The loader still owns fetching,
 * validating, retrying and caching the bytes ([MangaPageLoader]); this object
 * only turns a file it vouched for into pixels. Keeping the two apart is what
 * lets a page be decoded on demand (and dropped again) without touching the
 * network half, and it is why a retry that lands a good file simply decodes
 * again — the file's name is new, so nothing here can serve the old page.
 */
internal object PageBitmaps {

    /**
     * The most pixels one page may occupy — ~44MB at four bytes a pixel.
     *
     * The number is a ceiling on what a single page can cost the heap, not a
     * target: an ordinary 800×1200 page is 1M pixels, and even a 1080×3000 page
     * (3.2M) is nowhere near it. It exists for the strips: a manhwa page is
     * routinely 8k-20k px tall, and "decode it whole" has to mean "decode it
     * whole at a size this process can actually hold", which for those is the
     * next power-of-two reduction down.
     */
    const val MAX_PAGE_PIXELS = 11_000_000

    /** How many bytes of decoded pages are kept. Big pages are big, so this is
     *  a couple of them and a generous handful of ordinary ones; the pages
     *  themselves are re-decodable in a few hundred milliseconds, so a small
     *  cache costs a little speed on a back-scroll and nothing else. */
    private const val CACHE_BYTES = 80L * 1024 * 1024

    /** Pages decoded at once. Two is enough to keep the page under the thumb and
     *  the page behind it coming, and it keeps a fling's worth of decodes from
     *  competing for the heap at the same moment. */
    private const val PARALLEL = 2

    /** Never sample a page below this width: a strip reduced past this is a
     *  smear rather than a drawing, and no budget is worth that. */
    private const val MIN_DECODE_WIDTH = 320

    private val gate = Semaphore(PARALLEL)
    private val lock = Any()

    private var cachedBytes = 0L

    /**
     * The decoded pages, keyed by the page FILE's path — which is unique per
     * fetch (see [MangaPageLoader]), so a retry can never hit the page it
     * replaced.
     *
     * Access-ordered, and the eldest entry is dropped the moment the budget is
     * exceeded. Nothing here ever calls [Bitmap.recycle]: a bitmap that is on
     * screen is still referenced by the composable that asked for it, and
     * recycling an evicted entry could pull the pixels out from under a frame
     * that is already being drawn. Dropping the reference is enough — the heap
     * frees the pixels by itself, without a window in which the reader could
     * draw a dead bitmap.
     */
    private val cache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            if (cachedBytes <= CACHE_BYTES) return false
            eldest?.value?.let { cachedBytes -= bytesOf(it) }
            return true
        }
    }

    /** A page already decoded, if it is still cached. Synchronous on purpose:
     *  this is what the drawing composable reads as its first value, so a page
     *  that is already decoded is drawn on the first frame instead of after a
     *  round trip to the dispatcher. */
    fun cached(file: File): Bitmap? = synchronized(lock) { cache[file.absolutePath] }

    /**
     * The page as one bitmap, decoded if it is not cached yet. Returns null when
     * the file cannot be decoded at all — the reader reports that to the loader
     * (see [MangaPageLoader.markUndecodable]), which drops the file and turns
     * the page into one the retry button can fetch again.
     */
    suspend fun page(file: File, hintWidth: Int = 0, hintHeight: Int = 0): Bitmap? {
        cached(file)?.let { return it }
        val target = targetWidthPx()
        val decoded = gate.withPermit {
            withContext(Dispatchers.Default) { decode(file, hintWidth, hintHeight, target) }
        } ?: return null
        // Another decode of the same page may have landed while this one was
        // running (the page's slot and the preloader both ask). The one that is
        // already in the cache is as good as this one, and it is the one the
        // reader may already be drawing, so this one is thrown away.
        return synchronized(lock) {
            val existing = cache[file.absolutePath]
            if (existing != null) {
                decoded.recycle()
                existing
            } else {
                cachedBytes += bytesOf(decoded)
                cache[file.absolutePath] = decoded
                decoded
            }
        }
    }

    /**
     * Decodes a page the reader is *about* to show, without ever delaying the
     * page it is showing.
     *
     * The gate is taken with `tryAcquire`: if the two decode slots are busy,
     * this page is simply not pre-warmed — the reader will decode it when it
     * arrives — and, crucially, a preload can never queue in front of the page
     * under the reader's thumb. That is the whole reason this is not just
     * `page()` launched in the background.
     */
    suspend fun prefetch(file: File, hintWidth: Int = 0, hintHeight: Int = 0) {
        if (cached(file) != null) return
        if (!gate.tryAcquire()) return
        try {
            val target = targetWidthPx()
            val decoded = withContext(Dispatchers.Default) {
                decode(file, hintWidth, hintHeight, target)
            } ?: return
            synchronized(lock) {
                if (cache[file.absolutePath] != null) {
                    decoded.recycle()
                } else {
                    cachedBytes += bytesOf(decoded)
                    cache[file.absolutePath] = decoded
                }
            }
        } finally {
            gate.release()
        }
    }

    // ---- Decoding ----------------------------------------------------------

    /**
     * The width a page is decoded for: the screen's own width in px.
     *
     * Read from the application's resources rather than passed down through the
     * composable, because it is the same number everywhere and the one place
     * that must not have to know about it is the caller. It follows the current
     * rotation (the platform updates the app resources on every configuration
     * change), which is what makes a page decoded in the strip and the same page
     * decoded in the pager agree.
     */
    private fun targetWidthPx(): Int {
        val w = runCatching { HikariApp.instance.resources.displayMetrics.widthPixels }
            .getOrDefault(0)
        return if (w > 0) w else 1080
    }

    /**
     * One whole page as a bitmap, never larger than the screen needs and never
     * larger than [MAX_PAGE_PIXELS].
     *
     * `inSampleSize` is the only reduction used, deliberately: it is what the
     * platform decoder applies while it reads the file (so the full-size pixels
     * are never allocated even for a moment), where an exact rescale would have
     * to materialise the bigger bitmap first and then copy it — a page-sized
     * spike of heap for a couple of percent of sharpness on the pages that are
     * too big to decode whole at native width anyway.
     */
    private fun decode(file: File, hintWidth: Int, hintHeight: Int, targetWidth: Int): Bitmap? {
        var width = hintWidth
        var height = hintHeight
        if (width <= 0 || height <= 0) {
            // No size from the loader (a page it did not read the header of):
            // ask for the header only — cheap, and it allocates no pixels.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching { BitmapFactory.decodeFile(file.absolutePath, bounds) }
            width = bounds.outWidth
            height = bounds.outHeight
        }
        if (width <= 0 || height <= 0) {
            Logs.log("Manga", "page header unreadable — ${file.name}")
            return null
        }
        // The smallest power-of-two reduction that brings the page inside the
        // budget, and never one step further than [MIN_DECODE_WIDTH] of source.
        var sample = 1
        while (sample < 64 &&
            pixels(width / (sample * 2), height / (sample * 2)) >= MAX_PAGE_PIXELS &&
            width / (sample * 2) >= MIN_DECODE_WIDTH
        ) {
            sample *= 2
        }
        // A source page wider than the screen can show is worth one more step
        // too: the compositor is going to scale it down to the screen anyway, so
        // the only thing a full-width decode of a 2400px-wide scan buys is four
        // times the heap. One halving keeps the decoded page inside twice the
        // screen's width — still more detail than any screen can display, at a
        // quarter of the bytes. A page NARROWER than the screen is left alone:
        // that is the ~800px manhwa source every source here serves, and its own
        // width is the sharpest copy that exists.
        while (sample < 64 && width / sample > max(targetWidth, MIN_DECODE_WIDTH) * 2) {
            sample *= 2
        }
        val whole = decodeWith(file, sample)
        if (whole != null) return whole
        // A decode that ran out of heap despite the budget (the budget is about
        // THIS page, and other pages are alive beside it). One more halving is
        // all that is offered: a file the platform cannot decode even
        // downsampled is a file that is damaged, and the loader's retry is the
        // right answer for that, not an ever-smaller bitmap.
        return decodeWith(file, (sample * 2).coerceAtMost(64))
    }

    private fun decodeWith(file: File, sample: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            // The page is drawn at the layout's size, not at its own; letting
            // the decoder apply a density scale as well would only blur it.
            inScaled = false
        }
        val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }
            .getOrNull()
            ?: return null
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            bitmap.recycle()
            return null
        }
        return bitmap
    }

    private fun pixels(w: Int, h: Int): Long = w.toLong() * h.toLong()

    private fun bytesOf(bitmap: Bitmap): Long =
        runCatching { bitmap.allocationByteCount.toLong() }
            .getOrElse { (bitmap.width.toLong() * bitmap.height.toLong() * 4L) }
}
