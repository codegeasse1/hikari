package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.ColorFilter
import android.graphics.PointF
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.core.view.isVisible
import androidx.viewpager.widget.ViewPager
import com.hikari.app.reader.cache.WebtoonPageCache
import com.hikari.app.reader.source.MangaSource
import eu.kanade.tachiyomi.ui.reader.viewer.NavigationRegion
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation
import java.io.File
import java.io.IOException

/**
 * The chimahon paged reader, ported into Nekoread. A [androidx.viewpager.widget.ViewPager]
 * (DirectionalViewPager, so it can page vertically too) shows one page per screen through a
 * [PagerPageHolder], which region-decodes each page straight from its on-device cache file
 * (WebtoonPageCache) with a [com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView] â the
 * same smooth, memory-bounded rendering the webtoon reader uses. Tap zones, page transitions,
 * double-tap/pinch zoom, crop borders and zoom start position all mirror chimahon's pager behavior.
 */
abstract class PagerViewer(val context: Context) {

    /** View pager used by this viewer. Abstract to implement L2R, R2L and vertical pagers. */
    val pager: Pager = createPager()

    /** Configuration used by the pager (tap zones, scale mode, transitions...). */
    var config: PagerConfig = PagerConfig()
        set(value) {
            if (field === value) return
            field = value
            applyConfig()
        }

    /** The source used to download page bytes (through its own client) into [cacheDir]. */
    var source: MangaSource? = null

    /** On-device cache dir holding the downloaded page image files. */
    var cacheDir: File = File(context.cacheDir, "webtoon_pages")

    /** Target decode width (px) hint passed to the page image view. */
    var decodeWidth: Int = 0

    /** Color filter (grayscale / inverted colors / enhance) applied to every page's image view. */
    var colorFilter: ColorFilter? = null
        set(value) {
            if (field === value) return
            field = value
            // Re-apply to the currently-bound page holders so toggling the filter (e.g. the image
            // enhancer) redraws the visible pages immediately instead of waiting for a page turn.
            for (i in 0 until pager.childCount) {
                (pager.getChildAt(i) as? PagerPageHolder)?.colorFilter = value
            }
        }

    /** Text color for the reader chrome (errors). */
    var textColor: Int = AndroidColor.WHITE
        private set

    /** Called when the current page changes (1-based page number, page total). */
    var onPageChanged: ((page: Int, pageTotal: Int) -> Unit)? = null

    /** Called when the user taps a menu region (toggle the reader menu). */
    var onMenuTap: (() -> Unit)? = null

    /** Called when a page is zoomed (hides the reader menu, like chimahon). */
    var onZoom: (() -> Unit)? = null

    /** Adapter of the pager. */
    private val adapter = PagerAdapter(this, this is R2LPagerViewer)

    /** Currently active page. */
    private var currentPage: PagerPage? = null

    private var positioned = false
    private var reportedPage: Pair<Int, Int>? = null
    private var navigator: ViewerNavigation = config.buildNavigator()
    private var lastRenderKey: Int = -1

    private val pagerListener = object : ViewPager.SimpleOnPageChangeListener() {
        override fun onPageSelected(position: Int) {
            if (pager.isRestoring) return
            onPageChange(position)
        }
    }

    init {
        pager.isVisible = false // Don't layout the pager yet
        pager.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        pager.isFocusable = false
        pager.offscreenPageLimit = 1
        pager.adapter = adapter
        pager.addOnPageChangeListener(pagerListener)
        pager.tapListener = f@{ event ->
            val pos = PointF(event.x / pager.width.coerceAtLeast(1), event.y / pager.height.coerceAtLeast(1))
            when (navigator.getAction(pos)) {
                NavigationRegion.MENU -> onMenuTap?.invoke()
                NavigationRegion.NEXT -> moveToNext()
                NavigationRegion.PREV -> moveToPrevious()
                NavigationRegion.RIGHT -> moveRight()
                NavigationRegion.LEFT -> moveLeft()
            }
        }
    }

    /** Applies the current [config] to the navigator and rebinds pages if rendering changed. */
    private fun applyConfig() {
        navigator = config.buildNavigator()
        val key = config.renderKey()
        if (key != lastRenderKey) {
            lastRenderKey = key
            reloadPages()
        }
    }

    /** Re-binds every currently displayed page holder so a render-config change takes effect now. */
    private fun reloadPages() {
        for (i in 0 until pager.childCount) {
            val child = pager.getChildAt(i) as? PagerPageHolder ?: continue
            child.reload()
        }
    }

    /** Sets the reader background color. */
    fun setTheme(bgColor: Int, textColor: Int) {
        pager.setBackgroundColor(bgColor)
        this.textColor = textColor
    }

    /** Sets the pages of the current chapter and moves to [initialPageNumber] (1-based). */
    fun setPages(pages: List<PagerPage>, initialPageNumber: Int) {
        adapter.setPages(pages)
        if (!positioned) {
            positioned = true
            pager.isVisible = true
        }
        moveToPage(initialPageNumber)
    }

    /** Scrolls so the page with the given 1-based [pageNumber] is shown. */
    fun moveToPage(pageNumber: Int) {
        val position = adapter.positionOf(pageNumber)
        if (position == -1) return
        pager.setCurrentItem(position, config.usePageTransitions)
        pager.post {
            reportedPage = null
            onPageChange(pager.currentItem)
        }
    }

    /** Moves to the next page (in reading order). */
    open fun moveToNext() = moveRight()

    /** Moves to the previous page (in reading order). */
    open fun moveToPrevious() = moveLeft()

    /** Moves one step toward the end of the chapter in display order. */
    protected open fun moveRight() {
        if (pager.currentItem < adapter.count - 1) {
            pager.setCurrentItem(pager.currentItem + 1, config.usePageTransitions)
        }
    }

    /** Moves one step toward the start of the chapter in display order. */
    protected open fun moveLeft() {
        if (pager.currentItem > 0) {
            pager.setCurrentItem(pager.currentItem - 1, config.usePageTransitions)
        }
    }

    /** Downloads the page's bytes once to the cache file and returns it. */
    suspend fun loadPage(page: PagerPage): File {
        val src = source ?: throw IOException("No source available for this manga")
        return WebtoonPageCache.fileFor(page.desc, src, cacheDir)
    }

    /** Called when a new page is marked as active. */
    private fun onPageChange(position: Int) {
        val page = adapter.pageAt(position) ?: return
        if (currentPage != page) {
            currentPage = page
        }
        val total = adapter.count
        val key = page.number to total
        if (reportedPage != key) {
            reportedPage = key
            onPageChanged?.invoke(page.number, total)
        }
    }

    /** Creates a new ViewPager. */
    abstract fun createPager(): Pager

    /** The view this viewer owns. */
    val view: View get() = pager

    /** Called when leaving the reader. */
    fun destroy() {
        pager.removeOnPageChangeListener(pagerListener)
    }
}
