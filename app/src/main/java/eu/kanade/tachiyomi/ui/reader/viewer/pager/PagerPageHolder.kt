package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.PointF
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
import com.hikari.app.reader.cache.WebtoonPageCache
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
 * View of the ViewPager that contains one page of a chapter (ported from chimahon's
 * PagerPageHolder, minus OCR / panel navigation / dual-page merging). Downloads the page's bytes
 * once to the on-device cache file (single-flighted with the reader's preload loop via
 * [WebtoonPageCache]), then hands the file to the [ReaderPageImageView] which region-decodes only
 * the visible slice from disk — never a giant full-resolution bitmap in memory. While
 * downloading/decoding a progress container keeps the view centered; failures show a retry button.
 */
class PagerPageHolder(
    context: android.content.Context,
    private val viewer: PagerViewer,
    val page: PagerPage,
) : ReaderPageImageView(context) {

    /** Loading progress bar (indeterminate). */
    private val progressBar: ProgressBar

    /** Progress container, centered over the page. */
    private val progressContainer: FrameLayout

    /** Error layout to show when the image fails to load. */
    private var errorLayout: LinearLayout? = null

    private val scope = MainScope()

    /** Page-translation overlay state (see [bindTranslation]). */
    private var tlJob: Job? = null
    private var tlPageKey: String? = null
    private var tlImgW: Int = 0
    private var tlImgH: Int = 0
    private var tlOverlay: TranslationOverlayView? = null

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

    init {
        progressContainer = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }
        addView(progressContainer)
        progressBar = ProgressBar(context)
        progressContainer.addView(
            progressBar,
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER),
        )

        onImageLoaded = { onImageDecoded() }
        onImageLoadError = { setError() }
        onScaleChanged = { viewer.onZoom?.invoke() }

        load()
    }

    /** (Re)loads the page — used on creation and for the retry button. */
    fun load() {
        loadJob?.cancel()
        autoJob?.cancel()
        removeErrorLayout()
        progressContainer.isVisible = true
        loadJob = scope.launch {
            try {
                val file = viewer.loadPage(page)
                // Everything the render needs (dims, animated, tall) comes from cached metadata —
                // no bounds decode, no header read on the main thread.
                val meta = WebtoonPageCache.meta(page.desc, viewer.cacheDir)
                decodeWidthPx = viewer.decodeWidth
                setImage(
                    file,
                    meta?.isAnimated == true,
                    viewer.config.readerPageConfig().copy(isTallImage = meta?.isTall),
                )
                colorFilter = viewer.colorFilter
                bindTranslation(page.desc.imageUrl, file, meta?.width ?: 0, meta?.height ?: 0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                setError()
            }
        }
    }

    /** Re-runs the bind with the current viewer config (a render setting changed). */
    fun reload() {
        load()
    }

    /** Called when the view is recycled by the pager. */
    fun onRecycled() {
        loadJob?.cancel()
        loadJob = null
        autoJob?.cancel()
        autoJob = null
        autoTriesLeft = AUTO_RETRY_MAX
        removeErrorLayout()
        cancelTranslation()
        super.recycle()
        progressContainer.isVisible = true
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
            errorLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                val msg = TextView(context).apply {
                    text = "Couldn't load page"
                    textSize = 14f
                    setTextColor(viewer.textColor)
                }
                addView(msg)
                val retry = Button(context).apply {
                    text = "Retry"
                    setOnClickListener { manualRetry() }
                }
                addView(retry, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(12) })
            }
            addView(errorLayout)
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
                load()
            }
        }
    }

    /** The Retry button: a manual tap always works and refills the automatic budget. */
    private fun manualRetry() {
        autoTriesLeft = AUTO_RETRY_MAX
        load()
    }

    /** Removes the error layout from the holder, if found. */
    private fun removeErrorLayout() {
        errorLayout?.let {
            removeView(it)
            errorLayout = null
        }
    }

    /** Starts (or clears) the translation overlay for the just-loaded page. */
    private fun bindTranslation(pageKey: String, file: java.io.File, imgW: Int, imgH: Int) {
        tlJob?.cancel()
        tlJob = null
        tlOverlay?.let { removeView(it) }
        tlOverlay = null
        tlPageKey = pageKey
        tlImgW = imgW
        tlImgH = imgH
        if (!MangaTranslateRuntime.settings.enabled || imgW <= 0 || imgH <= 0) return
        tlJob = scope.launch {
            val boxes = MangaTranslateRuntime.translatePage(pageKey, file, imgW, imgH)
            if (tlPageKey != pageKey) return@launch
            val list = boxes.filter { it.trans.isNotBlank() }
            if (list.isEmpty()) return@launch
            val ov = TranslationOverlayView(context)
            ov.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            ov.setContent(imgW.toFloat(), imgH.toFloat(), list, ::locateTranslationPoint)
            addView(ov)
            tlOverlay = ov
        }
    }

    private fun cancelTranslation() {
        tlJob?.cancel()
        tlJob = null
        tlPageKey = null
        tlOverlay?.let { removeView(it) }
        tlOverlay = null
    }

    /** Full-image pixel -> this view pixel, for the translation overlay. */
    private fun locateTranslationPoint(px: Float, py: Float): PointF? {
        val iv = getImageView() ?: return null
        return try {
            when {
                iv is SubsamplingScaleImageView && iv.sWidth > 0 -> {
                    val p = iv.sourceToViewCoord(px, py)
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

    private fun dp(value: Int): Int =
        (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()

    companion object {
        /** How many times a failed page retries itself before leaving the Retry button to the user. */
        const val AUTO_RETRY_MAX = 10
    }
}
