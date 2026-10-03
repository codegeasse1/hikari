package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.view.View
import android.view.ViewGroup
import androidx.viewpager.widget.PagerAdapter

/**
 * Pager adapter for the chimahon pager viewer: one [PagerPage] per position, no chapter
 * transitions or inserted pages. For R2L reading the page list is displayed reversed (position 0
 * is the last page), so the natural reading direction always moves toward higher positions.
 */
class PagerAdapter(
    private val viewer: PagerViewer,
    private val reversed: Boolean = false,
) : PagerAdapter() {

    /** Pages in display order (reversed for R2L). */
    private var displayPages: List<PagerPage> = emptyList()

    /** Updates the displayed pages, forcing a full rebind (new chapter = new page objects). */
    fun setPages(pages: List<PagerPage>) {
        displayPages = if (reversed) pages.reversed() else pages
        notifyDataSetChanged()
    }

    override fun getCount(): Int = displayPages.size

    override fun isViewFromObject(view: View, obj: Any): Boolean = view === obj

    override fun instantiateItem(container: ViewGroup, position: Int): Any {
        val holder = PagerPageHolder(container.context, viewer, displayPages[position])
        container.addView(holder, 0)
        return holder
    }

    override fun destroyItem(container: ViewGroup, position: Int, obj: Any) {
        (obj as? PagerPageHolder)?.onRecycled()
        container.removeView(obj as View)
    }

    override fun getItemPosition(obj: Any): Int {
        val holder = obj as? PagerPageHolder ?: return POSITION_NONE
        val index = displayPages.indexOf(holder.page)
        return if (index != -1) index else POSITION_NONE
    }

    /** The page displayed at [position], or null if out of range. */
    fun pageAt(position: Int): PagerPage? = displayPages.getOrNull(position)

    /** Display position of the page with the given 1-based [pageNumber], or -1. */
    fun positionOf(pageNumber: Int): Int = displayPages.indexOfFirst { it.number == pageNumber }
}
