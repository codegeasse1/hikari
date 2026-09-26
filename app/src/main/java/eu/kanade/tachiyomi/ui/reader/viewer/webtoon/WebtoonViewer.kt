package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.PointF
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.animation.LinearInterpolator
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.WebtoonLayoutManager
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.hikari.app.reader.cache.WebtoonPageCache
import com.hikari.app.reader.source.MangaSource
import com.hikari.app.reader.WebtoonScaleType
import eu.kanade.tachiyomi.ui.reader.viewer.NavigationRegion
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation
import java.io.File
import java.io.IOException
import kotlin.math.abs

/**
 * How close to the top of the first streamed chapter counts as "at the start" —
 * the shell streams the previous chapter in when the reader is within this many
 * pages of it, the mirror of the last-few-pages rule at the end.
 */
private const val NEAR_START_PAGES = 2

/**
 * The yomi/chimahon webtoon reader, ported into Nekoread. A [RecyclerView] (with the
 * extra-layout-space [WebtoonLayoutManager]) displays every page through a [ReaderPageImageView]
 * that region-decodes each strip straight from its on-device cache file. Tap zones (configurable
 * via [WebtoonConfig]'s navigation scheme), pinch / double-tap whole-strip zoom, side padding,
 * border cropping and near-end reporting all mirror yomi's behavior.
 */
class WebtoonViewer(context: Context) {

    /** Recycler view used by this viewer. */
    val recycler = WebtoonRecyclerView(context)

    /** Frame containing the recycler view (handles scaled-touch translation for whole-strip zoom). */
    private val frame = WebtoonFrame(context)

    /** Distance to scroll when the user taps on one side of the recycler view. */
    private val scrollDistance = context.resources.displayMetrics.heightPixels * 3 / 4

    /** How far beyond the viewport the layout manager creates/binds pages. */
    private val extraLayoutSpace = context.resources.displayMetrics.heightPixels

    /** Layout manager of the recycler view. */
    private val layoutManager = WebtoonLayoutManager(context, extraLayoutSpace)

    /** Adapter of the recycler view. */
    private val adapter = WebtoonAdapter(this)

    /** The source used to download page bytes (through its own client) into [cacheDir]. */
    var source: MangaSource? = null

    /** On-device cache dir holding the downloaded page image files. */
    var cacheDir: File = File(context.cacheDir, "webtoon_pages")

    /** Whether a small gap is added between pages (the "vertical with gaps" mode). */
    var gaps: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            isContinuous = !value
        }

    /** Whether the pages form one continuous strip (no gaps). */
    var isContinuous: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            applyConfig()
        }

    /** Webtoon-mode settings (tap zones, crop, side padding, zoom...). */
    var config: WebtoonConfig = WebtoonConfig()
        set(value) {
            if (field === value) return
            field = value
            applyConfig()
        }

    /** Target decode width (px) for short webtoon pages that render via Coil (yomi's fast path). */
    var decodeWidth: Int = 0

    /** Tall strips (the SSIV path) decode as RGB_565 at Low image quality. */
    var decodeRgb565: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            reloadPages()
        }

    /** Rendering config for each page (fit-width, pinch-to-zoom, ...). */
    var pageConfig: ReaderPageImageView.Config = ReaderPageImageView.Config(
        zoomDuration = 200,
        minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_FIT_WIDTH,
        cropBorders = false,
        webtoonSmartFit = false,
        enablePinchToZoom = true,
    )

    /** Color filter (grayscale / inverted colors) applied to every page's image view. */
    var colorFilter: android.graphics.ColorFilter? = null
        set(value) {
            if (field == value) return
            field = value
            for (i in 0 until recycler.childCount) {
                val holder = recycler.getChildViewHolder(recycler.getChildAt(i)) as? WebtoonPageHolder
                    ?: continue
                holder.frame.colorFilter = value
            }
        }

    /** Called when the current page changes (segment index, 1-based page, page total). */
    var onPageChanged: ((seg: Int, page: Int, pageTotal: Int) -> Unit)? = null

    /** Called when the user enters/leaves the last few pages of the last streamed chapter. */
    var onNearEndChanged: ((Boolean) -> Unit)? = null

    /** Called when the user enters/leaves the first few pages of the FIRST streamed
     *  chapter. The mirror of [onNearEndChanged], and the whole reason the strip can
     *  be read UPWARDS as well as down: at the top of the stream the shell streams
     *  the previous chapter in above the reader (see [setItems]'s `prependedItems`). */
    var onNearStartChanged: ((Boolean) -> Unit)? = null

    /** Called when the user taps a menu region (toggle the reader menu). */
    var onMenuTap: (() -> Unit)? = null

    /** Called when the user presses "Retry" in the failed-next-chapter trailer. */
    var onTrailerRetry: (() -> Unit)? = null

    /** Called on any user touch (drag or tap) — used to stop auto-scroll. */
    var onUserScroll: (() -> Unit)? = null

    /** Called when the recycler enters/leaves a scrolling state (drag, fling or settling). */
    var onScrollingChanged: ((Boolean) -> Unit)? = null

    /** Called when a fast scroll should hide the reader menu (threshold from config). */
    var onHideMenu: (() -> Unit)? = null

    /** Text color for the reader chrome (dividers, errors, trailer text). */
    var textColor: Int = AndroidColor.WHITE
        private set

    /** Current trailing item kind. */
    var trailer: WebtoonTrailer = WebtoonTrailer.None
        private set

    private var positioned = false
    private var reportedPage: Triple<Int, Int, Int>? = null
    private var lastNearEnd: Boolean? = null
    private var lastNearStart: Boolean? = null
    private var scrolling = false
    private var navigator: ViewerNavigation = config.buildNavigator()

    /** Hash of the last applied render config; toggles that don't re-render pages skip re-binding. */
    private var lastRenderKey: Int = -1

    /** The view this viewer owns (the zoom frame containing the recycler). */
    val view: View get() = frame

    init {
        recycler.setItemViewCacheSize(4)
        recycler.isVisible = false // Don't let the recycler layout until items are set
        recycler.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        recycler.isFocusable = false
        recycler.itemAnimator = null
        recycler.layoutManager = layoutManager
        recycler.adapter = adapter
        recycler.useConfirmedSingleTap = isContinuous
        recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    updateCurrentPage()
                    updateNearEnd()
                    updateNearStart()
                    // Hide the reader menu once the user scrolls fast enough (yomi's hide threshold).
                    if (scrolling && abs(dy) > config.readerHideThreshold.threshold) {
                        onHideMenu?.invoke()
                    }
                }

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    val next = newState != RecyclerView.SCROLL_STATE_IDLE
                    if (next != scrolling) onScrollingChanged?.invoke(next)
                    scrolling = next
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        onUserScroll?.invoke()
                    }
                }
            },
        )
        recycler.tapListener = { event -> handleTap(event) }
        frame.doubleTapZoom = config.doubleTapZoom
        frame.pinchToZoom = config.pinchToZoom
        frame.zoomOutDisabled = config.webtoonDisableZoomOut
        frame.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        frame.addView(recycler)
    }

    /** Applies the current [config]/[isContinuous] to the frame, page config and navigator. */
    private fun applyConfig() {
        val crop = if (isContinuous) config.cropBordersWebtoon else config.continuousCropBorders
        pageConfig = pageConfig.copy(
            cropBorders = crop,
            zoomDuration = config.doubleTapAnimDuration,
            alwaysDecodeLongStripWithSSIV = config.alwaysDecodeLongStripWithSSIV,
            enablePinchToZoom = config.pinchToZoom,
            doubleTapZoom = config.doubleTapZoom,
            fadeIn = config.fadeIn,
        )
        frame.doubleTapZoom = config.doubleTapZoom
        frame.pinchToZoom = config.pinchToZoom
        frame.zoomOutDisabled = config.webtoonDisableZoomOut
        navigator = config.buildNavigator()
        recycler.useConfirmedSingleTap = isContinuous
        // Only re-bind/decode pages when a setting that actually changes how pages render changed.
        // Navigation-only toggles (tap zones, transitions, hide threshold, pinch/double-tap zoom,
        // gap size...) used to trigger a full reloadPages() on every tap of the settings sheet —
        // re-decoding every visible strip for a non-render toggle caused memory spikes (OOM on
        // low-RAM devices) and visible jank.
        val key = renderKey()
        if (key != lastRenderKey) {
            lastRenderKey = key
            reloadPages()
        }
        applyWebtoonScaleType()
    }

    /** Render-affecting settings only — combined into a hash for change detection. */
    private fun renderKey(): Int {
        var h = if (isContinuous) config.cropBordersWebtoon.hashCode() else config.continuousCropBorders.hashCode()
        h = h * 31 + config.webtoonSidePadding.hashCode()
        h = h * 31 + config.alwaysDecodeLongStripWithSSIV.hashCode()
        h = h * 31 + config.fadeIn.hashCode()
        h = h * 31 + gaps.hashCode()
        return h
    }

    /**
     * Sets the reader's items, sizes and trailer, scrolling to [initialPage] on first layout.
     *
     * [prependedItems] is the number of items that were added to the HEAD of the
     * list by this call (an earlier chapter streamed in above the reader). The
     * adapter positions of everything the user can see therefore shift by that
     * much, so the page under the reader is remembered first and the scroll is put
     * back on it afterwards — otherwise growing the strip upwards would yank the
     * reader into the chapter that was just added, which is the exact opposite of
     * what "keep scrolling up" means.
     */
    fun setItems(
        items: List<WebtoonItem>,
        segSizes: List<Int>,
        trailer: WebtoonTrailer,
        initialPage: Int,
        prependedItems: Int = 0,
    ) {
        val anchorPos = if (prependedItems > 0) layoutManager.findFirstVisibleItemPosition()
        else RecyclerView.NO_POSITION
        val anchorOffset = if (anchorPos != RecyclerView.NO_POSITION) {
            (layoutManager.findViewByPosition(anchorPos)?.top ?: 0) - recycler.paddingTop
        } else 0
        adapter.segSizes = segSizes
        adapter.submit(items, trailer)
        this.trailer = trailer
        if (!positioned) {
            positioned = true
            recycler.isVisible = true
            moveToPage(initialPage)
        } else if (anchorPos != RecyclerView.NO_POSITION) {
            layoutManager.scrollToPositionWithOffset(anchorPos + prependedItems, anchorOffset)
            recycler.post { reportPosition() }
        } else {
            recycler.post { reportPosition() }
        }
        recycler.useConfirmedSingleTap = isContinuous
        recycler.post { applyWebtoonScaleType() }
    }

    /** Re-reports the position and both near-the-end flags after a list change. */
    private fun reportPosition() {
        updateCurrentPage()
        updateNearEnd()
        updateNearStart()
    }

    /** Updates only the trailing item (spinner / error / end) without touching the pages. */
    fun setTrailer(trailer: WebtoonTrailer) {
        if (this.trailer == trailer) return
        adapter.submit(adapter.items, trailer)
        this.trailer = trailer
    }

    /** Re-binds every currently bound page holder so a render-config change takes effect now. */
    fun reloadPages() {
        if (recycler.isComputingLayout) {
            recycler.post { reloadPages() }
            return
        }
        for (i in 0 until recycler.childCount) {
            val holder = recycler.getChildViewHolder(recycler.getChildAt(i)) as? WebtoonPageHolder
                ?: continue
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) continue
            val item = adapter.items.getOrNull(pos) as? WebtoonItem.Page ?: continue
            holder.bind(item)
        }
    }

    /** Applies the reader background and chrome text color. */
    fun setTheme(bgColor: Int, textColor: Int) {
        recycler.setBackgroundColor(bgColor)
        this.textColor = textColor
        adapter.setTheme(textColor)
    }

    /** Scrolls so the given adapter position is at the top of the viewport. */
    fun moveToPage(adapterPosition: Int) {
        val pos = adapterPosition.coerceIn(0, (adapter.itemCount - 1).coerceAtLeast(0))
        layoutManager.scrollToPositionWithOffset(pos, 0)
        recycler.post {
            reportedPage = null
            reportPosition()
        }
    }

    /** Scrolls the strip by [dy] pixels (used by auto-scroll and tap zones). */
    fun scrollBy(dy: Int) {
        recycler.scrollBy(0, dy)
    }

    /** Smooth-scrolls the strip by [dy] pixels over [durationMs] (used by smooth auto-scroll). */
    fun smoothScrollBy(dy: Int, durationMs: Long) {
        recycler.smoothScrollBy(0, dy, LinearInterpolator(), durationMs.toInt())
    }

    /** Smooth-scrolls one screen height over [durationMs] (yomi's linear auto-scroll step). */
    fun linearScroll(durationMs: Long) {
        recycler.smoothScrollBy(0, recycler.height, LinearInterpolator(), durationMs.toInt())
    }

    /** Downloads the page's bytes once to the cache file and returns it. */
    suspend fun loadPage(item: WebtoonItem.Page): File {
        val src = source ?: throw IOException("No source available for this manga")
        return WebtoonPageCache.fileFor(item.desc, src, cacheDir)
    }

    /** Tap zones via the config's navigation scheme (port of chimahon's tap listener). */
    private fun handleTap(event: MotionEvent) {
        onUserScroll?.invoke()
        val width = recycler.width.coerceAtLeast(1)
        val height = recycler.originalHeight.coerceAtLeast(1)
        val pos = PointF(event.x / width, event.y / height)
        when (navigator.getAction(pos)) {
            NavigationRegion.MENU -> onMenuTap?.invoke()
            NavigationRegion.NEXT, NavigationRegion.RIGHT -> scrollDown()
            NavigationRegion.PREV, NavigationRegion.LEFT -> scrollUp()
        }
    }

    private fun scrollUp() {
        if (config.usePageTransitions) {
            recycler.smoothScrollBy(0, -scrollDistance)
        } else {
            recycler.scrollBy(0, -scrollDistance)
        }
    }

    private fun scrollDown() {
        if (!isContinuous && config.continuousVerticalTappingByPage) {
            moveToNextPage()
            return
        }
        if (config.usePageTransitions) {
            recycler.smoothScrollBy(0, scrollDistance)
        } else {
            recycler.scrollBy(0, scrollDistance)
        }
    }

    /** In gaps mode with tap-by-page enabled: jump to the page after the one at the bottom. */
    private fun moveToNextPage() {
        val page = currentLastPage() ?: return
        val pos = adapter.items.indexOfFirst { it === page }
        if (pos < 0 || pos + 1 >= adapter.itemCount) return
        if (config.usePageTransitions) {
            layoutManager.smoothScrollToPosition(recycler, RecyclerView.State(), pos + 1)
        } else {
            layoutManager.scrollToPositionWithOffset(pos + 1, 0)
        }
    }

    /** True if [file] is a tall webtoon strip (height > 3x width — yomi/mihon's rule). */
    fun isTallPage(file: File): Boolean {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            WebtoonPageCache.isTallPage(opts.outWidth, opts.outHeight)
        } catch (e: Throwable) {
            true
        }
    }

    /**
     * Gaps-mode smart scaling (port of chimahon's webtoonScaleType listener): in gaps mode with
     * smart scale enabled, pages are zoomed so each displays at the chosen aspect ratio — on tall
     * portrait screens this is a no-op; on short/landscape screens it zooms pages to fill the
     * screen height.
     */
    private fun applyWebtoonScaleType() {
        if (isContinuous || !config.longStripGapSmartScale) {
            recycler.scaleTo(1f)
            return
        }
        recycler.post {
            val currentWidth = recycler.width
            val currentHeight = recycler.originalHeight
            if (currentWidth <= 0 || currentHeight <= 0) return@post
            val scaleType = config.webtoonScaleType
            if (scaleType == WebtoonScaleType.FIT) {
                recycler.scaleTo(1f)
                return@post
            }
            val desiredRatio = scaleType.ratio
            val screenRatio = currentWidth.toFloat() / currentHeight
            val desiredWidth = currentHeight * desiredRatio
            val desiredScale = desiredWidth / currentWidth
            if (screenRatio > desiredRatio) {
                recycler.scaleTo(desiredScale)
            } else {
                recycler.scaleTo(1f)
            }
        }
    }

    /** The last Page item whose position is at or above the viewport's bottom edge. */
    private fun currentLastPage(): WebtoonItem.Page? {
        val lastEnd = layoutManager.findLastEndVisibleItemPosition()
        if (lastEnd == RecyclerView.NO_POSITION) return null
        for (i in lastEnd downTo 0) {
            (adapter.items.getOrNull(i) as? WebtoonItem.Page)?.let { return it }
        }
        return null
    }

    private fun updateCurrentPage() {
        val page = currentLastPage() ?: return
        val pageTotal = adapter.segSizes.getOrElse(page.segIndex) { page.number }
        val key = Triple(page.segIndex, page.number, pageTotal)
        if (reportedPage != key) {
            reportedPage = key
            onPageChanged?.invoke(page.segIndex, page.number, pageTotal)
        }
    }

    private fun updateNearEnd() {
        val page = currentLastPage()
        val near = if (page == null) {
            false
        } else {
            val lastSeg = adapter.segSizes.lastIndex
            val lastSize = adapter.segSizes.getOrElse(lastSeg) { 0 }
            page.segIndex == lastSeg && lastSize > 0 && page.number >= (lastSize - 3).coerceAtLeast(1)
        }
        if (lastNearEnd != near) {
            lastNearEnd = near
            onNearEndChanged?.invoke(near)
        }
    }

    /** The first Page item at or below the top of the viewport. */
    private fun currentFirstPage(): WebtoonItem.Page? {
        if (adapter.items.isEmpty()) return null
        val first = layoutManager.findFirstVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return null
        val last = layoutManager.findLastVisibleItemPosition()
        val end = (if (last == RecyclerView.NO_POSITION) adapter.items.size - 1 else last)
            .coerceAtMost(adapter.items.size - 1)
        for (i in first.coerceAtLeast(0)..end) {
            (adapter.items.getOrNull(i) as? WebtoonItem.Page)?.let { return it }
        }
        return null
    }

    /**
     * Reports whether the reader sits at the very top of the FIRST streamed chapter,
     * which is the shell's cue to stream the previous chapter in above it.
     *
     * The window is a few pages rather than one, because the previous chapter has to
     * be on its way before the user's finger reaches the top — the same lead time the
     * end-of-chapter append gets.
     */
    private fun updateNearStart() {
        val page = currentFirstPage()
        val near = page != null && page.segIndex == 0 && page.number <= NEAR_START_PAGES
        if (lastNearStart != near) {
            lastNearStart = near
            onNearStartChanged?.invoke(near)
        }
    }
}
