package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.graphics.PointF
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.hikari.app.reader.cache.WebtoonPageCache
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderDiagnostics
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.hikari.app.reader.MangaTranslateRuntime
import com.hikari.app.reader.TranslationOverlayView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Holder of the yomi webtoon reader for a single page of a chapter (ported from yomi's
 * WebtoonPageHolder): downloads the page's bytes ONCE to the on-device cache file (single-flighted
 * with the preload loop via [WebtoonPageCache]), then hands the file to the [ReaderPageImageView]
 * which region-decodes only the visible slice from disk — never a giant full-height bitmap. While
 * downloading/decoding a progress container keeps the holder at viewport height; failures show a
 * retry button.
 */
class WebtoonPageHolder(
    val frame: ReaderPageImageView,
    private val viewer: WebtoonViewer,
) : androidx.recyclerview.widget.RecyclerView.ViewHolder(frame) {

    /** Loading progress bar to indicate the current progress. */
    private val progressBar: ProgressBar

    /** Progress container. Kept at a minimum height (the viewport) so the adapter doesn't create
     *  more views to fill the screen while a page loads. */
    private lateinit var progressContainer: FrameLayout

    /** Error layout to show when the image fails to load. */
    private var errorLayout: LinearLayout? = null

    /** Current page bound to this holder (for the retry button). */
    private var item: WebtoonItem.Page? = null

    private val scope = MainScope()

    /** Page-translation overlay state (see [bindTranslation]). */
    private var tlJob: Job? = null
    private var tlPageKey: String? = null
    private var tlImgW: Int = 0
    private var tlImgH: Int = 0
    private var tlOverlay: TranslationOverlayView? = null
    private var tlFile: java.io.File? = null
    private var tlBadge: TextView? = null

    /** Re-runs the overlay when the translate switch flips (no scroll needed). */
    private val tlRefreshListener: () -> Unit = { frame.post { refreshTranslation(); frame.postDelayed({ if (tlOverlay == null && MangaTranslateRuntime.settings.enabled) refreshTranslation() }, 600) } }

    init {
        MangaTranslateRuntime.addListener(tlRefreshListener)
    }

    /** Job for loading the page. */
    private var loadJob: Job? = null

    /**
     * Automatic retries left for the current failure run (see [setError]).
     * A failure schedules its own retry instead of waiting for the user; a
     * success refills the budget, and so does a manual Retry tap — after the
     * budget is spent the button simply stays put for the user.
     */
    private var autoTriesLeft = AUTO_RETRY_MAX

    /** The currently scheduled automatic retry, if any. */
    private var autoJob: Job? = null

    /**
     * The frame's placeholder height while a page loads: the recycler's viewport height. The
     * recycler is not necessarily measured yet when the FIRST holders are created (the adapter is
     * built during the initial layout), so fall back to the display height rather than 0 — a 0
     * placeholder is coerced to 1px, which collapses every not-yet-loaded page to a sliver and
     * makes the whole chapter fit the layout window at once (the dx15 log binds all 18 pages of the
     * chapter in one burst on open).
     */
    private val parentHeight
        get() = viewer.recycler.height.takeIf { it > 0 }
            ?: frame.context.resources.displayMetrics.heightPixels

    init {
        refreshLayoutParams()

        frame.onImageLoaded = { onImageDecoded() }
        frame.onImageLoadError = { setError() }

        progressBar = createProgressIndicator()
    }

    /** Binds the given [page] to this holder and starts loading its cache file. */
    fun bind(item: WebtoonItem.Page) {
        val syncT0 = SystemClock.elapsedRealtime()
        this.item = item
        val label = item.desc.imageUrl
        ReaderDiagnostics.init(frame.context)
        ReaderDiagnostics.currentLabel = label
        frame.debugLabel = label
        ReaderDiagnostics.logFor(label, "bind seg=${item.segIndex} page=${item.number}")
        loadJob?.cancel()
        autoJob?.cancel()
        // A new page earns a fresh automatic budget; re-binding the SAME page
        // (the automatic retry below) keeps spending the current one, or the
        // budget would refill on every try and never run out. Compared by URL
        // because `item` is already assigned above.
        if (this.item?.desc?.imageUrl != item.desc.imageUrl) autoTriesLeft = AUTO_RETRY_MAX
        removeErrorLayout()
        progressContainer.isVisible = true
        refreshLayoutParams()
        refreshPlaceholderHeight()
        // If the prewarm already knows this page's size (in memory), size the frame NOW — this bind
        // runs during RecyclerView layout, so the first layout pass reserves the page's true height
        // and the list does not relayout a frame later (the viewport-placeholder snap that reads as
        // scroll jitter). Pages whose size is not yet known keep the placeholder and settle in the
        // coroutine below.
        WebtoonPageCache.cachedSize(label)?.let { (w, h) ->
            if (w > 0 && h > 0) {
                setFrameHeightNow((pageWidthPx().toFloat() * h / w).toInt().coerceAtLeast(1))
            }
        }
        loadJob = scope.launch {
            val bindT0 = SystemClock.elapsedRealtime()
            try {
                // Pre-size the holder to the page's REAL height from cached metadata (the prewarm
                // downloads pages well ahead, and meta() is cached after the first read, so nearly
                // every page is known) — loading the image then never relayouts the list. That
                // per-image relayout (viewport -> image height) is a major scroll-stutter source.
                // Unknown pages keep the viewport-height placeholder and settle once the image
                // lands.
                var meta = WebtoonPageCache.meta(item.desc, viewer.cacheDir)
                val rw = pageWidthPx()
                val targetH = if (meta != null) {
                    (rw.toFloat() * meta.height / meta.width).toInt().coerceAtLeast(1)
                } else {
                    WRAP_CONTENT
                }
                setFrameHeight(targetH)

                val wasCached = WebtoonPageCache.targetFile(
                    WebtoonPageCache.keyFor(item.desc.imageUrl),
                    viewer.cacheDir,
                ).exists()
                val file = viewer.loadPage(item)
                ReaderDiagnostics.logFor(label, "file ready ${file.length() / 1024}KB cachedBeforeBind=$wasCached")
                // Everything the render needs (dims, animated, tall) comes from cached metadata —
                // no bounds decode, no header read, no isTallPage sniff during the bind.
                if (meta == null) meta = WebtoonPageCache.meta(item.desc, viewer.cacheDir)
                // The page's dimensions were unknown at bind time (first-ever page still
                // downloading), so the frame is still the viewport-height placeholder. Now that
                // the file exists the real size is known — fix the frame to the true strip height
                // so the page isn't left cropped to the viewport.
                if (meta != null) {
                    val rw2 = pageWidthPx()
                    setFrameHeight((rw2.toFloat() * meta.height / meta.width).toInt().coerceAtLeast(1))
                }
                frame.decodeWidthPx = viewer.decodeWidth
                frame.setImage(
                    file,
                    meta?.isAnimated == true,
                    viewer.pageConfig.copy(
                        isTallImage = meta?.isTall,
                        decodeRgb565 = viewer.decodeRgb565,
                        sharpen = viewer.sharpenPages,
                        nativeWidth = meta?.width,
                        nativeHeight = meta?.height,
                    ),
                )
                frame.colorFilter = viewer.colorFilter
                bindTranslation(item.desc.imageUrl, file, meta?.width ?: 0, meta?.height ?: 0)
                val bindDt = SystemClock.elapsedRealtime() - bindT0
                if (bindDt >= 40) ReaderDiagnostics.logFor(label, "bind work ${bindDt}ms")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                setError()
            }
        }
        val syncDt = SystemClock.elapsedRealtime() - syncT0
        if (syncDt >= 20) ReaderDiagnostics.logFor(label, "bind sync ${syncDt}ms")
    }

    /** The width (px) the page image is actually laid out at: the recycler's width less the webtoon
     *  side padding the frame carries on both sides. The frame height is derived from this — using
     *  the full recycler width made every page's slot taller than the page's own aspect whenever
     *  side padding was set, which the chunked renderer then filled by stretching vertically (and
     *  which left a gap under every short page). */
    private fun pageWidthPx(): Int {
        val rw = viewer.recycler.width.takeIf { it > 0 }
            ?: frame.context.resources.displayMetrics.widthPixels
        val sidePadding = (viewer.config.webtoonSidePadding.coerceIn(0, 25) / 100f) *
            frame.context.resources.displayMetrics.widthPixels
        return (rw - 2 * sidePadding.toInt()).coerceAtLeast(1)
    }

    private fun refreshLayoutParams() {
        val bottomMargin = if (viewer.gaps) dp(15) else 0
        val sidePadding = (viewer.config.webtoonSidePadding.coerceIn(0, 25) / 100f) * frame.context.resources.displayMetrics.widthPixels

        val lp = frame.layoutParams
        if (lp == null) {
            // Fresh view that has never been added to the RecyclerView: setting new params is safe
            // (no ViewHolder stamped in yet). RecyclerView.LayoutParams is required — the frame is
            // a direct child of the RecyclerView, and getChildViewHolderInt casts every direct
            // child's params to RecyclerView.LayoutParams during layout.
            frame.layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                this.bottomMargin = bottomMargin
                this.leftMargin = sidePadding.toInt()
                this.rightMargin = sidePadding.toInt()
            }
            return
        }
        // The frame is (or was) a direct child of the RecyclerView: NEVER replace its LayoutParams
        // object. RecyclerView stamps a private reference to the child's ViewHolder into the params
        // at attach time (getChildViewHolderInt), so assigning a fresh object nulls that reference
        // and the next layout pass crashes (findMinMaxChildLayoutPositions / updateLayoutState).
        // Mutate the existing params in place instead.
        val mlp = lp as? ViewGroup.MarginLayoutParams ?: return
        if (mlp.bottomMargin == bottomMargin && mlp.leftMargin.toFloat() == sidePadding) {
            return
        }
        mlp.bottomMargin = bottomMargin
        mlp.leftMargin = sidePadding.toInt()
        mlp.rightMargin = sidePadding.toInt()
        if (viewer.recycler.isComputingLayout) {
            viewer.recycler.post { frame.requestLayout() }
        } else {
            frame.requestLayout()
        }
    }

    /**
     * Sizes the frame to [targetH] immediately, safe to call from inside a bind (RecyclerView is
     * mid-layout there). Mutating the existing params in place and letting the in-progress layout
     * measure the child is correct and costs no extra pass; a layout request is only issued when the
     * recycler is NOT currently laying out (otherwise the request would schedule a redundant pass).
     */
    private fun setFrameHeightNow(targetH: Int) {
        val lp = frame.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (lp.height == targetH) return
        lp.height = targetH
        if (!viewer.recycler.isComputingLayout) frame.requestLayout()
    }

    /** Pre-sizes the frame to [targetH] without layout thrash (WRAP_CONTENT = unknown page). */
    private fun setFrameHeight(targetH: Int) {
        val lp = frame.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (lp.height == targetH) return
        if (viewer.recycler.isComputingLayout) {
            viewer.recycler.post { setFrameHeight(targetH) }
        } else {
            lp.height = targetH
            frame.requestLayout()
        }
    }

    /** Keeps the loading placeholder matched to the current viewport height (rotation etc). */
    private fun refreshPlaceholderHeight() {
        val height = parentHeight
        if (height <= 0) return
        val params = progressContainer.layoutParams
        if (params != null && params.height != height) {
            progressContainer.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, height)
        }
    }

    /** Called when the view is recycled and added to the view pool. */
    fun recycle() {
        MangaTranslateRuntime.removeListener(tlRefreshListener)
        loadJob?.cancel()
        loadJob = null
        autoJob?.cancel()
        autoJob = null
        autoTriesLeft = AUTO_RETRY_MAX
        removeErrorLayout()
        cancelTranslation()
        frame.recycle()
        progressContainer.isVisible = true
    }

    /** Starts (or clears) the translation overlay for the just-bound page. */
    private fun bindTranslation(pageKey: String, file: java.io.File, imgW: Int, imgH: Int, retry: Int = 0) {
        tlJob?.cancel()
        tlJob = null
        tlOverlay?.let { frame.removeView(it) }
        tlOverlay = null
        tlBadge?.let { frame.removeView(it) }
        tlBadge = null
        tlPageKey = pageKey
        tlFile = file
        tlImgW = imgW
        tlImgH = imgH
        if (!MangaTranslateRuntime.settings.enabled || imgW <= 0 || imgH <= 0) return
        // Instant feedback: the page says it is working right away, so a slow
        // first run (model download + tiled OCR + network) no longer reads as
        // "not translating" and no restart is needed to see it finish.
        val badge = TextView(frame.context).apply {
            text = "Translating..."
            gravity = Gravity.CENTER
            setPadding(24, 12, 24, 12)
        }
        badge.layoutParams = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER)
        frame.addView(badge)
        tlBadge = badge
        // A stuck badge is worse than none: it always comes down.
        frame.postDelayed({ if (tlBadge === badge) { frame.removeView(badge); if (tlBadge === badge) tlBadge = null } }, 45000)
        tlJob = scope.launch {
            val boxes = MangaTranslateRuntime.translatePage(pageKey, file, imgW, imgH)
            if (tlPageKey != pageKey) return@launch
            if (tlBadge === badge) { frame.removeView(badge); tlBadge = null }
            val list = boxes.filter { it.trans.isNotBlank() }
            if (list.isEmpty()) {
                // One delayed retry: transient engine/rate-limit failures then
                // resolve on the open page, without any restart.
                if (retry < 1 && MangaTranslateRuntime.settings.enabled) {
                    delay(6000)
                    if (tlPageKey == pageKey && MangaTranslateRuntime.settings.enabled) {
                        bindTranslation(pageKey, file, imgW, imgH, retry + 1)
                    }
                }
                return@launch
            }
            val ov = TranslationOverlayView(frame.context)
            ov.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            ov.setContent(imgW.toFloat(), imgH.toFloat(), list, ::locateTranslationPoint)
            frame.addView(ov)
            tlOverlay = ov
        }
    }

    private fun cancelTranslation() {
        tlJob?.cancel()
        tlJob = null
        tlPageKey = null
        tlFile = null
        tlOverlay?.let { frame.removeView(it) }
        tlOverlay = null
        tlBadge?.let { frame.removeView(it) }
        tlBadge = null
    }

    /** Settings changed (toggle on/off): redo the open page in place. */
    fun refreshTranslation() {
        val key = tlPageKey
        val f = tlFile
        if (key == null || f == null || !f.exists() || tlImgW <= 0 || tlImgH <= 0) {
            if (!MangaTranslateRuntime.settings.enabled) cancelTranslation()
            return
        }
        bindTranslation(key, f, tlImgW, tlImgH)
    }

    /** Full-image pixel -> frame pixel, for the translation overlay. */
    private fun locateTranslationPoint(px: Float, py: Float): PointF? {
        val iv = frame.getImageView() ?: return null
        return try {
            when {
                iv is SubsamplingScaleImageView && iv.sWidth > 0 -> {
                    val p = PointF()
                    iv.sourceToViewCoord(px, py, p)
                    PointF(p.x + iv.left, p.y + iv.top)
                }
                iv is ImageView -> {
                    val d = iv.drawable ?: return null
                    val dw = d.intrinsicWidth
                    if (dw <= 0 || tlImgW <= 0) return null
                    val sc = dw.toFloat() / tlImgW
                    val pts = floatArrayOf(px * sc, py * sc)
                    iv.imageMatrix.mapPoints(pts)
                    PointF(pts[0] + iv.left, pts[1] + iv.top)
                }
                else -> {
                    if (tlImgW <= 0 || tlImgH <= 0) return null
                    PointF(iv.left + px / tlImgW * iv.width, iv.top + py / tlImgH * iv.height)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Called when the image is decoded and going to be displayed. */
    private fun onImageDecoded() {
        autoJob?.cancel()
        autoJob = null
        autoTriesLeft = AUTO_RETRY_MAX
        progressContainer.isVisible = false
        removeErrorLayout()
    }

    /** Called when the page has an error. */
    private fun setError() {
        progressContainer.isVisible = false
        if (errorLayout == null) {
            errorLayout = LinearLayout(frame.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    MATCH_PARENT,
                    (viewer.recycler.height * 0.8).toInt().coerceAtLeast(400),
                )
                val msg = TextView(frame.context).apply {
                    text = "Couldn't load page"
                    textSize = 14f
                    setTextColor(viewer.textColor)
                }
                addView(msg)
                val retry = Button(frame.context).apply {
                    text = "Retry"
                    setOnClickListener { manualRetry() }
                }
                addView(retry, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(12) })
            }
            frame.addView(errorLayout)
        }
        // The page retries itself: a transient 403/5xx is the common case, and
        // tapping Retry by hand until it loads is what this replaces. Up to
        // [AUTO_RETRY_MAX] tries with a growing pause between them; the first
        // one that decodes refills the budget (see [onImageDecoded]), and when
        // the budget is spent the button simply stays for a manual tap.
        if (autoTriesLeft > 0 && (autoJob?.isActive != true)) {
            autoTriesLeft--
            val attempt = AUTO_RETRY_MAX - autoTriesLeft
            autoJob = scope.launch {
                try {
                    delay(800L + attempt * 400L)
                } catch (e: CancellationException) {
                    throw e
                }
                item?.let { bind(it) }
            }
        }
    }

    /** The Retry button: a manual tap always works and refills the automatic budget. */
    private fun manualRetry() {
        autoTriesLeft = AUTO_RETRY_MAX
        item?.let { bind(it) }
    }

    /** Removes the error layout from the holder, if found. */
    private fun removeErrorLayout() {
        errorLayout?.let {
            frame.removeView(it)
            errorLayout = null
        }
    }

    /** Creates a new progress bar centered in a viewport-height container. */
    private fun createProgressIndicator(): ProgressBar {
        progressContainer = FrameLayout(frame.context)
        frame.addView(progressContainer, MATCH_PARENT, parentHeight.coerceAtLeast(1))
        return ProgressBar(frame.context).also {
            val lp = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER)
            progressContainer.addView(it, lp)
        }
    }

    companion object {
        /** How many times a failed page retries itself before leaving the Retry button to the user. */
        const val AUTO_RETRY_MAX = 10
    }
}

private fun dp(value: Int): Int =
    (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
