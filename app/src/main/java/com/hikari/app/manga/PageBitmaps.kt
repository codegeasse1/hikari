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
 * The reader's SHORT-page decoder: one bitmap per page, decoded from the file the
 * loader fetched, kept in a small byte-budgeted cache.
 *
 * **Which page comes here, and which does not.** This object draws the pages that
 * are *not* strips — `h ≤ 3w`, see [TALL_RATIO] — and it draws them as ONE
 * bitmap, because for that shape one bitmap can be made provably small: at most
 * [MAX_PAGE_PIXELS] pixels, at most [MAX_DECODE_HEIGHT] on any axis, and at most
 * the screen's own width. A page taller than that is a webtoon strip, and a strip
 * is NOT decoded here at all: it goes to [ChunkedPageView], the ported chunked
 * renderer, which never builds a page-sized bitmap (and never needs to — a strip
 * is bounded chunks of 2048 px, however tall it is).
 *
 * **Why the budgets are this small — and this is the actual bug this file used to
 * ship.** Everything drawn here ends up as a GPU texture, and the plate's own
 * texture limit is the boundary between "a page" and "a lattice of displaced
 * cells": a bitmap the platform cannot hold as one texture has to be split into a
 * grid of tiles, and on the phones that reported the broken pages those tiles came
 * out showing the wrong rows or nothing at all — the artwork sliced into a rigid
 * lattice, which is exactly what the reader's screenshots showed (seams at exactly
 * 20/40/60/80% of the width, cells unrelated to their neighbours, some of them
 * blank). The old budget here was 11 000 000 px and 3200 px tall — 2.75x the
 * pixels of a 1080-wide comic page, and taller than the most conservative texture
 * limit a phone reports — so an ordinary page was handed to the compositor at a
 * size this platform cannot draw as one image. The budgets above are the ones the
 * reader this app was asked to match uses for exactly this reason (Nekoread's
 * `WEBTOON_MAX_DECODE_PIXELS = 1_000_000`, and its per-chunk 2048 px cap).
 *
 * A page decoded to ~1 000 000 px and drawn at the screen's width is
 * indistinguishable from one decoded at full size on a phone screen — it is the
 * same picture at 1080 px wide, just not carrying the pixels no screen can show —
 * and it is ~4MB instead of ~10MB, which is what keeps the reader's prefetch
 * window (the pages ahead, held warm) affordable.
 *
 * **Why the loader is not the decoder.** The loader still owns fetching,
 * validating, retrying and caching the bytes ([MangaPageLoader]); this object only
 * turns a file it vouched for into pixels. Keeping the two apart is what lets a
 * page be decoded on demand (and dropped again) without touching the network half,
 * and it is why a retry that lands a good file simply decodes again — the file's
 * name is new, so nothing here can serve the old page.
 */
internal object PageBitmaps {

    /**
     * A page taller than this many times its own width is a STRIP — a webtoon page
     * — and is not decoded here at all: it is drawn by [ChunkedPageView], which
     * decodes bounded chunks and never builds a bitmap the height of the page.
     *
     * The number and the rule are the ported reader's, verbatim (Nekoread's
     * `WebtoonPageCache`'s `TALL_RATIO`, which is in turn yomi/mihon's
     * `ImageUtil.isTallImage` rule). What the rule buys is arithmetic, not taste: a
     * page this shape is at most 3x its width, so a page drawn at the screen's own
     * width is at most 3 x 1080 px tall — a bitmap this file can hold inside its
     * budget. Anything taller has no size at which one bitmap is safe, which is why
     * it does not come here at all.
     */
    const val TALL_RATIO = 3f

    /** True for a strip: `h > 3w`, i.e. drawn by [ChunkedPageView], never decoded
     *  as one bitmap (see [TALL_RATIO]). An unknown size counts as tall — the safe
     *  answer, since the chunked view can draw anything. */
    fun isTallPage(width: Int, height: Int): Boolean =
        if (width <= 0 || height <= 0) true else height > width * TALL_RATIO

    /**
     * The most pixels one page decoded HERE may occupy: ~4MB at four bytes a
     * pixel, i.e. roughly a screenful of artwork at the screen's own width.
     *
     * This is the budget the ported reader uses for the same job
     * (`WEBTOON_MAX_DECODE_PIXELS`), and it is a ceiling on what a single page may
     * cost the heap AND on what a single page may cost the compositor — the two
     * reasons it is this small. A 1080-wide page lands at ~1080x926, which is the
     * whole page at the width the screen shows it at; the detail beyond that is
     * pixels no phone screen can display.
     */
    const val MAX_PAGE_PIXELS = 1_000_000L

    /**
     * The tallest a decoded page may be, on either axis.
     *
     * Kept at 2048 — the same bound the ported reader's chunk renderer keeps its
     * chunks inside, and the smallest maximum-texture size a phone is likely to
     * report — so a page decoded here is never handed to the compositor at a size
     * that forces it to split the draw into tiles. That split is the failure this
     * whole file is written around (see the class doc), and this is the second of
     * the two rules that make sure a bitmap from here never triggers it.
     */
    private const val MAX_DECODE_HEIGHT = 2048

    /** How many bytes of decoded pages are kept. Pages are ~4MB here, so this is a
     *  couple of screens' worth plus the prefetch window; the pixels are
     *  re-decodable in a few hundred milliseconds, so a small cache costs a little
     *  speed on a back-scroll and nothing else. */
    private const val CACHE_BYTES = 48L * 1024 * 1024

    /** Pages decoded at once. Two is enough to keep the page under the thumb and
     *  the page behind it coming, and it keeps a fling's worth of decodes from
     *  competing for the heap at the same moment — the ported reader bounds its own
     *  page decodes to two for the same reason (their log showed three concurrent
     *  multi-megabyte decodes costing 5-10x each). */
    private const val PARALLEL = 2

    /** Never sample a page below this width: a page reduced past this is a smear
     *  rather than a drawing, and no budget is worth that. */
    private const val MIN_DECODE_WIDTH = 320

    private val gate = Semaphore(PARALLEL)
    private val lock = Any()

    private var cachedBytes = 0L

    /**
     * The decoded pages, keyed by the page FILE's path — which is unique per fetch
     * (see [MangaPageLoader]), so a retry can never hit the page it replaced.
     *
     * Access-ordered, and the eldest entry is dropped the moment the budget is
     * exceeded. Nothing here ever calls [Bitmap.recycle]: a bitmap that is on
     * screen is still referenced by the composable that asked for it, and recycling
     * an evicted entry could pull the pixels out from under a frame that is already
     * being drawn. Dropping the reference is enough — the heap frees the pixels by
     * itself, without a window in which the reader could draw a dead bitmap.
     */
    private val cache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            if (cachedBytes <= CACHE_BYTES) return false
            eldest?.value?.let { cachedBytes -= bytesOf(it) }
            return true
        }
    }

    /** A page already decoded, if it is still cached. Synchronous on purpose: this
     *  is what the drawing composable reads as its first value, so a page that is
     *  already decoded is drawn on the first frame instead of after a round trip to
     *  the dispatcher. */
    fun cached(file: File): Bitmap? = synchronized(lock) { cache[file.absolutePath] }

    /**
     * The page as one bitmap, decoded if it is not cached yet. Returns null when the
     * file cannot be decoded at all — the reader reports that to the loader (see
     * [MangaPageLoader.markUndecodable]), which drops the file and turns the page
     * into one the retry button can fetch again.
     */
    suspend fun page(file: File, hintWidth: Int = 0, hintHeight: Int = 0): Bitmap? {
        cached(file)?.let { return it }
        // A strip is never decoded here (see [TALL_RATIO]); the reader routes it to
        // [ChunkedPageView] instead. This guard is what keeps a caller that got that
        // wrong from re-introducing the page-sized bitmap.
        if (isTallPage(hintWidth, hintHeight)) {
            Logs.log("Manga", "strip asked for a bitmap (${hintWidth}x${hintHeight}) — refused")
            return null
        }
        val target = targetWidthPx()
        val decoded = gate.withPermit {
            withContext(Dispatchers.Default) { decode(file, hintWidth, hintHeight, target) }
        } ?: return null
        // Another decode of the same page may have landed while this one was running
        // (the page's slot and the preloader both ask). The one that is already in
        // the cache is as good as this one, and it is the one the reader may already
        // be drawing, so this one is thrown away.
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
     * Decodes a page the reader is *about* to show, without ever delaying the page
     * it is showing.
     *
     * The gate is taken with `tryAcquire`: if the two decode slots are busy, this
     * page is simply not pre-warmed — the reader will decode it when it arrives —
     * and, crucially, a preload can never queue in front of the page under the
     * reader's thumb. That is the whole reason this is not just `page()` launched in
     * the background.
     */
    suspend fun prefetch(file: File, hintWidth: Int = 0, hintHeight: Int = 0) {
        if (cached(file) != null) return
        // A strip has no bitmap to warm: its pages are region-decoded from the file
        // by [ChunkedPageView] when they come on screen, and the file being on disk
        // (which IS the warm-up) is all it needs. Decoding one here would be exactly
        // the page-sized bitmap the strip path exists to avoid.
        if (isTallPage(hintWidth, hintHeight)) return
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
     * composable, because it is the same number everywhere and the one place that
     * must not have to know about it is the caller. It follows the current rotation
     * (the platform updates the app resources on every configuration change), which
     * is what makes a page decoded in the strip and the same page decoded in the
     * pager agree.
     */
    private fun targetWidthPx(): Int {
        val w = runCatching { HikariApp.instance.resources.displayMetrics.widthPixels }
            .getOrDefault(0)
        return if (w > 0) w else 1080
    }

    /**
     * One page as a bitmap: never wider than the screen can show, never above
     * [MAX_PAGE_PIXELS] and never taller than [MAX_DECODE_HEIGHT] on either axis.
     *
     * Three rules, in this order, and each one is a different failure:
     *
     *  1. **At or under the screen's own width.** The compositor scales the page to
     *     the screen regardless, so source pixels past that are heap, not detail —
     *     and they are what would push the page past the sizes the platform has to
     *     split into tiles. A page NARROWER than the screen is left alone: an
     *     ~800 px manhwa source is not upscaled, because that buys bytes and no
     *     detail.
     *  2. **Inside the budgets** ([MAX_PAGE_PIXELS], [MAX_DECODE_HEIGHT]). This is
     *     the rule that keeps the page drawable at all (see the class doc).
     *  3. **As fine as those two allow.** Rule 1 drops a 3000 px scan to 750 px,
     *     which is softer than it needs to be; this step walks the reduction back
     *     up while every ceiling still holds, landing it at 1500 px.
     *
     * `inSampleSize` is the only reduction used, deliberately: the platform applies
     * it while it reads the file, so the full-size pixels are never allocated even
     * for a moment. An exact rescale would materialise the bigger bitmap first and
     * then copy it — a page-sized spike of heap on the device least able to afford
     * one.
     */
    private fun decode(file: File, hintWidth: Int, hintHeight: Int, targetWidth: Int): Bitmap? {
        var width = hintWidth
        var height = hintHeight
        if (width <= 0 || height <= 0) {
            // No size from the loader (a page it did not read the header of): ask for
            // the header only — cheap, and it allocates no pixels.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching { BitmapFactory.decodeFile(file.absolutePath, bounds) }
            width = bounds.outWidth
            height = bounds.outHeight
        }
        if (width <= 0 || height <= 0) {
            Logs.log("Manga", "page header unreadable — ${file.name}")
            return null
        }
        var sample = 1
        // (1) The screen's own width, or narrower.
        while (sample < 64 && width / sample > max(targetWidth, MIN_DECODE_WIDTH)) {
            sample *= 2
        }
        // (2) Inside the pixel and axis budgets, whichever way the page is shaped.
        while (sample < 64 &&
            (
                height / sample > MAX_DECODE_HEIGHT ||
                    width / sample > MAX_DECODE_HEIGHT ||
                    pixels(width / sample, height / sample) > MAX_PAGE_PIXELS
                )
        ) {
            sample *= 2
        }
        // (3) …and then back up towards the detail the budgets allow.
        while (sample > 1 &&
            height / (sample / 2) <= MAX_DECODE_HEIGHT &&
            width / (sample / 2) <= MAX_DECODE_HEIGHT &&
            pixels(width / (sample / 2), height / (sample / 2)) <= MAX_PAGE_PIXELS &&
            width / (sample / 2) >= MIN_DECODE_WIDTH
        ) {
            sample /= 2
        }
        Logs.log(
            "Manga",
            "page ${width}x$height -> bitmap ${width / sample}x${height / sample} (sample $sample)",
        )
        val whole = decodeWith(file, sample)
        if (whole != null) return whole
        // A decode that ran out of heap despite the budgets (they are about THIS
        // page, and other pages are alive beside it). One more halving is all that is
        // offered: a file the platform cannot decode even downsampled is a file that
        // is damaged, and the loader's retry is the right answer for that, not an
        // ever-smaller bitmap.
        return decodeWith(file, (sample * 2).coerceAtMost(64))
    }

    private fun decodeWith(file: File, sample: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            // The page is drawn at the layout's size, not at its own; letting the
            // decoder apply a density scale as well would only blur it.
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
