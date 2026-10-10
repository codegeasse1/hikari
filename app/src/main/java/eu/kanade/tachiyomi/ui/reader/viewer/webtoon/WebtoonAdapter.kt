package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.hikari.app.reader.source.MangaSource
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView

/**
 * A single page (or chapter divider) of the webtoon stream. `segIndex` is the index into the
 * streamed-chapters list, `number` is the 1-based page number within that chapter, `key` is the
 * page's stable identity (its image URL) used for diffing.
 */
sealed class WebtoonItem {
    data class Page(
        val segIndex: Int,
        val number: Int,
        val desc: MangaSource.PageDescriptor,
        val key: String,
    ) : WebtoonItem()

    data class Divider(
        val chapterId: String,
        val chapterName: String,
    ) : WebtoonItem()
}

/**
 * Trailing item shown after the last streamed chapter's pages: a loading spinner while the next
 * chapter is being fetched, a retry button on failure, a small spacer while there is still a next
 * chapter to auto-load, or the "end of manga" message when the reader has caught up.
 */
sealed class WebtoonTrailer {
    object None : WebtoonTrailer()
    object Loading : WebtoonTrailer()
    object Idle : WebtoonTrailer()
    data class Error(val message: String) : WebtoonTrailer()
    data class End(val chapterName: String) : WebtoonTrailer()
}

/** RecyclerView adapter used by the yomi webtoon viewer (ported from yomi's WebtoonAdapter). */
class WebtoonAdapter(val viewer: WebtoonViewer) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** List of currently set page/divider items. */
    var items: List<WebtoonItem> = emptyList()
        private set

    /** Current trailing item kind. */
    var trailer: WebtoonTrailer = WebtoonTrailer.None
        private set

    /** Size (page count) of each streamed chapter, in the same order as the items. */
    var segSizes: List<Int> = emptyList()

    private var textColor = AndroidColor.WHITE

    fun setTheme(textColor: Int) {
        this.textColor = textColor
    }

    /** Updates this adapter with the given [newItems] and [newTrailer], dispatching delta updates. */
    fun submit(newItems: List<WebtoonItem>, newTrailer: WebtoonTrailer) {
        val t0 = SystemClock.elapsedRealtime()
        // Fast path: a streaming reader only ever APPENDS to the item list (the next chapter's
        // divider + pages are added at the end), so when the old list is a prefix of the new one we
        // dispatch a single range-insert instead of running DiffUtil over every page. The dx13 log
        // showed the DiffUtil pass costing ~500ms at each chapter boundary (`recompose ReaderScreen
        // 506ms` / `ui stall 595ms`) — the last remaining scroll stall.
        val oldSize = items.size
        if (trailer != WebtoonTrailer.None && newTrailer != WebtoonTrailer.None &&
            newItems.size >= oldSize && isPrefixOf(items, newItems)
        ) {
            val added = newItems.size - oldSize
            val trailerChanged = trailer != newTrailer
            items = newItems
            trailer = newTrailer
            if (added > 0) notifyItemRangeInserted(oldSize, added)
            if (trailerChanged) notifyItemChanged(newItems.size)
            val dt = SystemClock.elapsedRealtime() - t0
            if (dt >= 40) {
                eu.kanade.tachiyomi.ui.reader.viewer.ReaderDiagnostics.log(
                    "adapter append ${dt}ms (+$added pages)"
                )
            }
            return
        }
        val result = DiffUtil.calculateDiff(Callback(items, trailer, newItems, newTrailer))
        items = newItems
        trailer = newTrailer
        result.dispatchUpdatesTo(this)
        val dt = SystemClock.elapsedRealtime() - t0
        if (dt >= 40) {
            eu.kanade.tachiyomi.ui.reader.viewer.ReaderDiagnostics.log(
                "adapter diff ${dt}ms (n=${newItems.size})"
            )
        }
    }

    /** True when [old] is a prefix of [new], comparing stable identity only (cheap string compare). */
    private fun isPrefixOf(old: List<WebtoonItem>, new: List<WebtoonItem>): Boolean {
        for (i in old.indices) {
            val same = when {
                old[i] is WebtoonItem.Page && new[i] is WebtoonItem.Page ->
                    (old[i] as WebtoonItem.Page).key == (new[i] as WebtoonItem.Page).key
                old[i] is WebtoonItem.Divider && new[i] is WebtoonItem.Divider ->
                    (old[i] as WebtoonItem.Divider).chapterId == (new[i] as WebtoonItem.Divider).chapterId
                else -> false
            }
            if (!same) return false
        }
        return true
    }

    override fun getItemCount(): Int = items.size + if (trailer != WebtoonTrailer.None) 1 else 0

    override fun getItemViewType(position: Int): Int = when {
        position >= items.size -> TRAILER_VIEW
        items[position] is WebtoonItem.Page -> PAGE_VIEW
        else -> DIVIDER_VIEW
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
        PAGE_VIEW -> WebtoonPageHolder(ReaderPageImageView(parent.context, isWebtoon = true), viewer)
        DIVIDER_VIEW -> WebtoonDividerHolder(LinearLayout(parent.context), viewer)
        TRAILER_VIEW -> WebtoonTrailerHolder(LinearLayout(parent.context), viewer)
        else -> error("Unknown view type: $viewType")
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val t0 = SystemClock.elapsedRealtime()
        when (holder) {
            is WebtoonPageHolder -> holder.bind(items[position] as WebtoonItem.Page)
            is WebtoonDividerHolder -> holder.bind(items[position] as WebtoonItem.Divider)
            is WebtoonTrailerHolder -> holder.bind(trailer)
        }
        val dt = SystemClock.elapsedRealtime() - t0
        if (dt >= 20) eu.kanade.tachiyomi.ui.reader.viewer.ReaderDiagnostics.log("onBindViewHolder ${dt}ms")
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is WebtoonPageHolder) holder.recycle()
    }

    /** Diff util callback used to dispatch delta updates instead of full dataset changes. */
    private class Callback(
        private val oldItems: List<WebtoonItem>,
        private val oldTrailer: WebtoonTrailer,
        private val newItems: List<WebtoonItem>,
        private val newTrailer: WebtoonTrailer,
    ) : DiffUtil.Callback() {

        private fun oldAt(position: Int): Any? =
            if (position < oldItems.size) oldItems[position] else if (oldTrailer != WebtoonTrailer.None) oldTrailer else null

        private fun newAt(position: Int): Any? =
            if (position < newItems.size) newItems[position] else if (newTrailer != WebtoonTrailer.None) newTrailer else null

        override fun getOldListSize(): Int = oldItems.size + if (oldTrailer != WebtoonTrailer.None) 1 else 0

        override fun getNewListSize(): Int = newItems.size + if (newTrailer != WebtoonTrailer.None) 1 else 0

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val o = oldAt(oldItemPosition)
            val n = newAt(newItemPosition)
            if (o == null || n == null) return o == n
            return when {
                o is WebtoonItem.Page && n is WebtoonItem.Page -> o.key == n.key
                o is WebtoonItem.Divider && n is WebtoonItem.Divider -> o.chapterId == n.chapterId
                o is WebtoonTrailer && n is WebtoonTrailer -> true
                else -> false
            }
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            oldAt(oldItemPosition) == newAt(newItemPosition)
    }
}

/** Holder for the "— Chapter Name —" divider between streamed chapters. */
class WebtoonDividerHolder(
    private val container: LinearLayout,
    private val viewer: WebtoonViewer,
) : RecyclerView.ViewHolder(container) {

    private val title = TextView(container.context).apply {
        textSize = 14f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
    }

    init {
        container.orientation = LinearLayout.VERTICAL
        container.gravity = Gravity.CENTER
        container.setPadding(0, dp(20), 0, dp(20))
        container.addView(title)
    }

    fun bind(item: WebtoonItem.Divider) {
        title.text = "— ${item.chapterName} —"
        title.setTextColor(viewer.textColor.withAlpha(0.7f))
    }
}

/** Holder for the trailing item (spinner / retry / end-of-manga). */
class WebtoonTrailerHolder(
    private val container: LinearLayout,
    private val viewer: WebtoonViewer,
) : RecyclerView.ViewHolder(container) {

    fun bind(trailer: WebtoonTrailer) {
        container.removeAllViews()
        container.orientation = LinearLayout.VERTICAL
        container.gravity = Gravity.CENTER
        val desiredHeight = if (trailer is WebtoonTrailer.Idle) dp(48) else WRAP_CONTENT
        // The container is a direct child of the RecyclerView, so it MUST use RecyclerView.LayoutParams
        // (a MarginLayoutParams) — a plain ViewGroup.LayoutParams crashes getChildViewHolderInt. And it
        // must never have its LayoutParams OBJECT replaced once attached (RecyclerView stamps the
        // holder ref into it; replacing it nulls that ref and the next layout pass NPEs). Mutate in
        // place; only a fresh, never-attached view may take new params.
        val curLp = container.layoutParams
        if (curLp == null) {
            container.layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, desiredHeight)
        } else {
            val mlp = curLp as? ViewGroup.MarginLayoutParams ?: return
            if (mlp.height == desiredHeight) return
            mlp.height = desiredHeight
            if (viewer.recycler.isComputingLayout) {
                viewer.recycler.post { container.requestLayout() }
            } else {
                container.requestLayout()
            }
        }
        when (trailer) {
            WebtoonTrailer.None -> Unit
            WebtoonTrailer.Loading -> {
                container.setPadding(0, dp(32), 0, dp(32))
                container.addView(ProgressBar(container.context))
                val t = TextView(container.context).apply {
                    text = "Loading next chapter..."
                    textSize = 12f
                }
                container.addView(t, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(12) })
                t.setTextColor(viewer.textColor.withAlpha(0.7f))
            }
            WebtoonTrailer.Idle -> Unit
            is WebtoonTrailer.Error -> {
                container.setPadding(dp(32), dp(32), dp(32), dp(32))
                val t = TextView(container.context).apply { text = "Couldn't load the next chapter" }
                container.addView(t)
                val retry = Button(container.context).apply {
                    text = "Retry"
                    setOnClickListener { viewer.onTrailerRetry?.invoke() }
                }
                container.addView(retry, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(12) })
                t.setTextColor(viewer.textColor)
            }
            is WebtoonTrailer.End -> {
                container.setPadding(dp(32), dp(32), dp(32), dp(32))
                val t1 = TextView(container.context).apply {
                    text = "End of ${trailer.chapterName}"
                    textSize = 16f
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    gravity = Gravity.CENTER
                }
                container.addView(t1)
                val t2 = TextView(container.context).apply {
                    text = "You have caught up with the latest released chapter!"
                    textSize = 12f
                    gravity = Gravity.CENTER
                }
                container.addView(t2, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) })
                t1.setTextColor(viewer.textColor)
                t2.setTextColor(viewer.textColor.withAlpha(0.7f))
            }
        }
    }
}

private const val PAGE_VIEW = 0
private const val DIVIDER_VIEW = 1
private const val TRAILER_VIEW = 2

private fun Int.withAlpha(alpha: Float): Int = AndroidColor.argb(
    (AndroidColor.alpha(this) * alpha).toInt().coerceIn(0, 255),
    AndroidColor.red(this),
    AndroidColor.green(this),
    AndroidColor.blue(this),
)

private fun dp(value: Int): Int =
    (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
