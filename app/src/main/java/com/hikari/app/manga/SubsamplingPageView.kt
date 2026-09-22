package com.hikari.app.manga

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.view.MotionEvent
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import java.io.File
import java.io.FileInputStream

/**
 * One manga page, drawn by REGION-DECODING straight from the page's file.
 *
 * This is the reader's whole drawing path, and it replaced a Compose `Image` fed
 * a decoded bitmap — which is the change that ends "the image in the reader is
 * breaking" for good.
 *
 * **Why a decoded bitmap could never be safe.** Anything handed to the
 * compositor becomes a GPU texture, and a texture cannot be larger than the
 * device's `GL_MAX_TEXTURE_SIZE`. An image bigger than that is not drawn by
 * Skia — it is drawn as a GRID OF TILES, and that fallback mis-places the tiles'
 * source rectangles on the drivers these phones have, so the page comes out as
 * displaced bands, the same artwork repeated at a fixed offset, a white seam at
 * every tile boundary and the whole thing running off both screen edges. Splitting
 * the page into smaller bitmaps before drawing fixes nothing in general: a
 * "smaller" webtoon page is still 1600×8000, and the phones that report a 2048
 * limit are exactly the ones the slices have to be cut for. The only way out is
 * to not hand the GPU the page at all.
 *
 * **What this view does instead.** It is [SubsamplingScaleImageView] — the
 * Tachiyomi/yomi reader's own image view, and the view Nekoread (this app's manga
 * sibling) draws its pages with. It opens the page's file through
 * [ImageSource.provider], reads it ONCE into a native byte buffer, and from there
 * decodes TILES: a downsampled base layer at about the resolution the screen can
 * show, plus the higher-resolution tiles the viewport actually needs. A
 * 1080×20000 manhwa strip is never decoded at its own size — what the process
 * holds for a page is a handful of viewports of pixels, whatever the page's
 * height, and the taller the page the coarser its base layer. Tall pages, wide
 * pages, EXIF-rotated pages and pages whose full height no phone could allocate
 * all draw the same way: edge to edge, uninterrupted, as tiles that are each a
 * legal texture.
 *
 * **Why the reader keeps its own loader.** The page still has to be FETCHED,
 * validated and retried (see [MangaPageLoader]) — the extension's own headers, a
 * complete image of a known format, up to ten attempts, a file per fetch so
 * nothing can serve a stale copy. That part is unchanged and is what makes a
 * broken CDN response a retry instead of a broken page. This view is the half
 * that draws.
 *
 * **Gestures.** Every touch is refused (see [onTouchEvent]): the reader's own
 * container owns them (a tap toggles the chrome, a drag scrolls the strip or
 * swipes the pager), and a page that swallowed them — as this view normally does
 * once zoom is enabled — would break all of it. Zoom is therefore off; if the
 * reader ever grows a zoom gesture, enable it here and let the container
 * disambiguate, not by taking touches away from it silently.
 */
internal class SubsamplingPageView(context: Context) : SubsamplingScaleImageView(context) {

    /** Called once the page's first layer is up, so the reader can take its
     *  spinner down (before that the view has nothing to draw and would be a
     *  black rectangle under a spinner). */
    var onPageReady: (() -> Unit)? = null

    /** Called when the file cannot be decoded at all — the fetch that
     *  "succeeded" evidently did not (see [MangaPageLoader.markUndecodable]). */
    var onPageFailed: (() -> Unit)? = null

    /**
     * The reader's "Enhance" look, applied to whatever this view draws.
     *
     * A [SubsamplingScaleImageView] paints its tiles itself and cannot hold a
     * color filter, so the filter is applied by drawing the page into a saved
     * layer that carries it ([filterPaint]). That is one extra layer only while
     * the filter is ON: with Enhance off — the default — the page is drawn
     * straight through, so the feature costs nothing until it is asked for.
     */
    var pageFilter: ColorFilter? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val filterPaint = Paint()

    /** The file this view has open, so a recomposition that lands on the same
     *  page does not throw the decoded page away and start again. */
    private var shownPath: String? = null

    init {
        // The container's gestures are the truth: no zoom, no pan, no quick
        // scale, no touch at all.
        setZoomEnabled(false)
        setQuickScaleEnabled(false)
        setPanEnabled(false)
        setDoubleTapZoomStyle(SubsamplingScaleImageView.ZOOM_FOCUS_CENTER)
        setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_INSIDE)
        // Never sample the page down for the initial load: the decoder is asked
        // for the region at the scale the screen needs, and `minimumDpi = 1`
        // is what stops the library from deciding a page is "already sharp
        // enough" and handing over a smaller one.
        setMinimumDpi(1)
        setMinimumTileDpi(180)
        // Decode tiles once a fling settles rather than mid-scroll, which is what
        // keeps a fast webtoon scroll at the display's refresh rate.
        setEagerLoadingEnabled(false)
        // ...and never let one tile be bigger than a texture. By default the view
        // picks a tile size from its own dimensions, which is fine on a phone and
        // is exactly how the old drawing path went wrong on a page: a bitmap
        // bigger than the device's GL_MAX_TEXTURE_SIZE is drawn as a displaced
        // grid of tiles by Skia. Pinning the tile ceiling at 2048 — the smallest
        // maximum texture size any GLES2 device is allowed to report — makes a
        // tile a legal texture everywhere, including a tablet or a desktop window
        // whose viewport is wider than any phone's. The cost is a tile or two
        // more per screen, and it is paid in a place that cannot produce a broken
        // page.
        setMaxTileSize(2048)
        setOnImageEventListener(object : SubsamplingScaleImageView.OnImageEventListener {
            override fun onReady() = onPageReady?.invoke() ?: Unit
            override fun onImageLoaded() = Unit
            override fun onImageLoadError(e: Exception) = onPageFailed?.invoke() ?: Unit
            override fun onTileLoadError(e: Exception) = Unit
        })
    }

    /**
     * Points the view at [file], drawing it at the full width of the view
     * ([fitWidth], the webtoon strip and `MangaFit.WIDTH`) or scaled to fit
     * inside it (the modes that show the whole page at once).
     *
     * A no-op for the file already open: the reader recomposes on every scroll
     * frame and a page must not be re-opened — and re-decoded — because of it.
     */
    fun showPage(file: File, fitWidth: Boolean) {
        val path = file.absolutePath
        if (shownPath == path) return
        shownPath = path
        // Freed BEFORE the new page is opened: the old page's byte buffer and
        // tiles are exactly as big as the new one's, and holding both at once is
        // how a two-pages-of-margin reader doubles its peak memory for nothing.
        recycle()
        setMinimumScaleType(
            if (fitWidth) SubsamplingScaleImageView.SCALE_TYPE_FIT_WIDTH
            else SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE,
        )
        // A provider that opens a fresh stream per call, rather than a file path:
        // the decoder reads the stream once and keeps the bytes natively, and it
        // means this view never depends on content-resolver file URIs.
        setImage(ImageSource.provider { FileInputStream(file) })
    }

    override fun onDraw(canvas: Canvas) {
        val filter = pageFilter
        if (filter == null) {
            super.onDraw(canvas)
            return
        }
        filterPaint.colorFilter = filter
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), filterPaint)
        super.onDraw(canvas)
        canvas.restoreToCount(layer)
    }

    /** Every gesture belongs to the reader's container (see the class note). */
    override fun onTouchEvent(event: MotionEvent): Boolean = false
}
