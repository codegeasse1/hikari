package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.content.Context

/**
 * Implementation of a left to right PagerViewer.
 */
class L2RPagerViewer(context: Context) : PagerViewer(context) {
    override fun createPager(): Pager = Pager(context)
}

/**
 * Implementation of a right to left PagerViewer.
 */
class R2LPagerViewer(context: Context) : PagerViewer(context) {
    override fun createPager(): Pager = Pager(context)

    /** On a R2L pager the next page is the one at the left. */
    override fun moveToNext() = moveLeft()

    /** On a R2L pager the previous page is the one at the right. */
    override fun moveToPrevious() = moveRight()
}

/**
 * Implementation of a vertical (top to bottom) PagerViewer.
 */
class VerticalPagerViewer(context: Context) : PagerViewer(context) {
    override fun createPager(): Pager = Pager(context, isHorizontal = false)
}
