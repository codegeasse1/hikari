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

/**
 * The reader's page decoder: ONE software bitmap per page, decoded from the file
 * the loader fetched, kept in a byte-budgeted cache.
 *
 * **How a page is drawn, and where that rule comes from.** Every page — the
 * ordinary one and the webtoon strip — is decoded ONCE, whole, and drawn as one
 * bitmap. That is not a simplification, it is the only shape that has ever worked
 * on the phones this app ships to, and it is what the reader this app's manga
 * sibling uses (`ReaderPageImageView`, ported from yomi/Mihon) does: a tall strip
 * is decoded to a single SOFTWARE bitmap (never a hardware one — Coil's hardware
 * decode fails outright for a strip taller than the GPU texture limit) and drawn
 * by an ordinary `ImageView`; the platform's render thread splits such a bitmap
 * into however many tiles it needs, silently and correctly, and because the bitmap
 * is stable for the page's whole lifetime there is nothing to re-decode while
 * scrolling.
 *
 * **The four things that were tried instead, and why each failed.** (0.10.9)
 * cutting a decoded page into slices and stacking them; (0.10.10) handing the file
 * to `SubsamplingScaleImageView`; (0.10.12) the same view behind a shape rule;
 * (0.10.13) region-decoding the file into 2048px chunks and drawing those — the
 * ported chunked renderer. Every one of them drew the page in PIECES, and every
 * one came out on the user's phone as artwork scattered in displaced blocks or as a
 * black field. The reference reader's own log is explicit about the last shape: the
 * chunked renderer "came out black" for long strips, which is why it is used there
 * only as a last resort for a mega-strip that no single decode can hold, and never
 * for a normal webtoon page. This file therefore never cuts a page up: a page is
 * one bitmap, or it does not render.
 *
 * **What a bitmap may cost.** Two ceilings, and they exist for different reasons:
 *
 *  * **Width** — never wider than the screen (the compositor scales the page to
 *    the screen anyway) and never wider than the source (a 720px page is not
 *    upscaled; that would be memory for pixels that do not exist).
 *  * **Bytes** — a SHORT page is capped at [MAX_PAGE_PIXELS] (~4MB), because short
 *    pages are what the reader keeps warm several at a time and a 1080-wide
 *    7.9MB page is what used to thrash that window; a TALL strip is capped at
 *    [TALL_PAGE_BYTES] (48MB) instead, because a strip has to be held as one
 *    bitmap to be drawn at all, and 48MB is the reference reader's own budget for
 *    exactly that job (it covers the whole realistic range of webtoon sources at
 *    their own 720-1280px width). Nothing else about the page's shape matters:
 *    there is no height ceiling any more, because a height ceiling is what forced
 *    the page into pieces.
 *
 * Sizing is done with `inSampleSize` only — the platform applies it while reading
 * the file, so the full-size pixels are never allocated even for a moment.
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
     * A page taller than this many times its own width is a STRIP — a webtoon page.
     *
     * The number and the rule are the reference reader's ([MangaPageCache]'s
     * `TALL_RATIO`, in turn yomi's `ImageUtil.isTallImage`). Its only job here is
     * to pick which byte budget a page gets ([TALL_PAGE_BYTES] versus
     * [MAX_PAGE_PIXELS]) and to say so in the log — never to route the page to a
     * different renderer, because there is only one renderer.
     */
    const val TALL_RATIO = 3f

    /** True for a strip. An unknown size counts as tall (the safer budget). */
    fun isTallPage(width: Int, height: Int): Boolean =
        if (width <= 0 || height <= 0) true else height > width * TALL_RATIO

    /**
     * The most pixels a SHORT page may occupy: ~4MB at four bytes a pixel.
     *
     * This is the reference reader's budget for the same job
     * (`WEBTOON_MAX_DECODE_PIXELS`), and it is about the CACHE rather than one
     * page: short pages are the ones the reader keeps several of (the one on
     * screen, the preload window), and a 1080-wide page is ~2M px (~7.9MB), which
     * is what used to thrash that window on a low-end device. Capped, a page is
     * ~4MB and the warm window holds what it is sized for; the page still fills
     * the screen, just sampled slightly.
     */
    const val MAX_PAGE_PIXELS = 1_000_000L

    /**
     * The most bytes a STRIP's single bitmap may occupy — the reference reader's
     * own per-bitmap cap (`TALL_SINGLE_DECODE_BYTES`), raised there from 40MB to
     * 48MB after a source's 13.7k-17k-px strips kept falling foul of it.
     *
     * A strip cannot be split (see the class doc), so this budget is not about
     * speed: it is the line between "the strip is held as one bitmap" and "this
     * page cannot be shown at this size". A 1280x5000 page is 25MB and fits; a
     * 20 000px monster is reduced by powers of two until it does.
     */
    private const val TALL_PAGE_BYTES = 48L * 1024 * 1024

    /** Width a strip is never reduced below, however tall it is. */
    private const val MIN_DECODE_WIDTH = 256

    /**
     * How many bytes of decoded pages are kept. Derived from the device's own heap
     * rather than fixed, because the pages on a webtoon source are now up to
     * [TALL_PAGE_BYTES] each: an eighth of the heap, floored at 48MB (a couple of
     * strips) and capped at 160MB (past that, the heap is better spent on
     * something else).
     *
     * The pixels are re-decodable in a few hundred milliseconds, so a small cache
     * costs a little speed on a back-scroll and nothing else.
     */
    private val CACHE_BYTES: Long by lazy {
        val heap = runCatching { Runtime.getRuntime().maxMemory() }.getOrDefault(256L * 1024 * 1024)
        (heap / 8).coerceIn(48L * 1024 * 1024, 160L * 1024 * 1024)
    }

    /** Pages decoded at once. Two keeps the page under the thumb and the one behind
     *  it coming without having several multi-megabyte decodes compete for the heap
     *  at the same moment — the reference reader bounds its own page decodes to two
     *  for the same reason. */
    private const val PARALLEL = 2

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
     * (the platform updates the app resources on every configuration change).
     */
    private fun targetWidthPx(): Int {
        val w = runCatching { HikariApp.instance.resources.displayMetrics.widthPixels }
            .getOrDefault(0)
        return if (w > 0) w else 1080
    }

    /**
     * One page as a bitmap: the whole page in ONE image, never wider than the
     * screen can show and never over the budget its shape allows (see the class
     * doc). Two rules, in this order:
     *
     *  1. **At or under the screen's own width, and never past the source.** A page
     *     NARROWER than the screen is left alone (a 720px source is not upscaled —
     *     that buys bytes and no detail).
     *  2. **Inside the budget** ([MAX_PAGE_PIXELS] for a short page,
     *     [TALL_PAGE_BYTES] for a strip). This is the rule that keeps the page
     *     affordable, and it is the only thing that ever reduces a page further.
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
        val tall = isTallPage(width, height)
        val bpp = 4L
        var sample = 1
        // (1) The screen's own width, or the source's, whichever is smaller.
        val capWidth = if (width < targetWidth) width else targetWidth
        while (sample < 64 && width / (sample * 2) >= capWidth) sample *= 2
        // (2) The budget the page's shape allows. Halving the decoded size is the
        // only lever there is (inSampleSize is a power of two by definition), so a
        // page that is a little over comes down to a little under.
        while (sample < 64 &&
            overBudget(width, height, sample, tall, bpp) &&
            width / (sample * 2) >= MIN_DECODE_WIDTH
        ) {
            sample *= 2
        }
        val outW = width / sample
        val outH = height / sample
        Logs.log(
            "Manga",
            "page ${width}x$height -> bitmap ${outW}x$outH (sample $sample, " +
                "${outW.toLong() * outH * bpp / (1024 * 1024)}MB, ${if (tall) "strip" else "page"})",
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

    /** True when the bitmap [sample] would produce is over the budget its shape allows. */
    private fun overBudget(srcW: Int, srcH: Int, sample: Int, tall: Boolean, bpp: Long): Boolean {
        val w = (srcW / sample).toLong()
        val h = (srcH / sample).toLong()
        return if (tall) w * h * bpp > TALL_PAGE_BYTES else w * h > MAX_PAGE_PIXELS
    }

    private fun decodeWith(file: File, sample: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            // ARGB_8888, and SOFTWARE: this bitmap is drawn by the compositor, which
            // is exactly what must be able to hold it — and the render thread splits
            // a software bitmap that is larger than one texture into as many tiles as
            // it needs (see the class doc). Asking for RGB_565 would halve the bytes
            // and lose the alpha channel some sources use for page transparency;
            // asking for a HARDWARE bitmap is what fails outright once the bitmap is
            // taller than the texture limit, which a strip usually is.
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

    private fun bytesOf(bitmap: Bitmap): Long =
        runCatching { bitmap.allocationByteCount.toLong() }
            .getOrElse { (bitmap.width.toLong() * bitmap.height.toLong() * 4L) }
}
