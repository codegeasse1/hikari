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
 * Backoff between translation retries on the open page (5s, 15s, 30s, 60s,
 * 120s — about 4 minutes of cover). A first-use model download or a
 * rate-limited engine fails fast for a while, then succeeds; the retries
 * let the page fill in by itself instead of needing a scroll or reopen.
 */
private val TL_RETRY_MS = longArrayOf(5_000L, 15_000L, 30_000L, 60_000L, 120_000L)

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
    private var tlFile: java.io.File? = null
    private var tlBadge: TextView? = null

    /** Re-runs the overlay when the translate switch flips (no scroll needed). */
    private val tlRefreshListener: () -> Unit = { post { refreshTranslation(); postDelayed({ if (tlOverlay == null && MangaTranslateRuntime.settings.enabled) refreshTranslation() }, 600) } }

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

        MangaTranslateRuntime.addListener(tlRefreshListener)

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
                var iw = meta?.width ?: 0
                var ih = meta?.height ?: 0
                if ((iw <= 0 || ih <= 0) && file.exists() && file.length() > 0) {
                    // Metadata missed (evicted sidecar, exotic file): read
                    // the size straight off the file instead of skipping
                    // translation silently — a bounds decode costs nothing
                    // next to the download that just happened.
                    val d = WebtoonPageCache.dimensions(page.desc, viewer.cacheDir)
                    if (d != null) {
                        iw = d.first
                        ih = d.second
                    }
                }
                decodeWidthPx = viewer.decodeWidth
                setImage(
                    file,
                    meta?.isAnimated == true,
                    viewer.config.readerPageConfig().copy(isTallImage = meta?.isTall),
                )
                colorFilter = viewer.colorFilter
                bindTranslation(page.desc.imageUrl, file, iw, ih)
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
        MangaTranslateRuntime.removeListener(tlRefreshListener)
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
    private fun bindTranslation(pageKey: String, file: java.io.File, imgW: Int, imgH: Int, retry: Int = 0) {
        tlJob?.cancel()
        tlJob = null
        tlOverlay?.let { removeView(it) }
        tlOverlay = null
        tlBadge?.let { removeView(it) }
        tlBadge = null
        tlPageKey = pageKey
        tlFile = file
        tlImgW = imgW
        tlImgH = imgH
        if (!MangaTranslateRuntime.settings.enabled || imgW <= 0 || imgH <= 0) return
        // Instant feedback: the page says it is working right away, so a slow
        // first run (model download + tiled OCR + network) no longer reads as
        // "not translating" and no restart is needed to see it finish.
        val badge = TextView(context).apply {
            text = "Translating..."
            gravity = Gravity.CENTER
            setPadding(24, 12, 24, 12)
        }
        badge.layoutParams = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER)
        addView(badge)
        tlBadge = badge
        // A stuck badge is worse than none: it always comes down — but only
        // once the work is truly done. Across retries the page keeps saying
        // it is working, so a slow first run reads as working, not broken.
        postDelayed({ if (tlBadge === badge) { removeView(badge); if (tlBadge === badge) tlBadge = null } }, 300000)
        tlJob = scope.launch {
            val res = MangaTranslateRuntime.translatePage(pageKey, file, imgW, imgH, onStatus = { s ->
                if (tlPageKey == pageKey && tlBadge === badge) badge.post {
                    if (tlPageKey == pageKey && tlBadge === badge) badge.text = s
                }
            })
            if (tlPageKey != pageKey) return@launch
            val list = res.boxes.filter { it.trans.isNotBlank() }
            if (list.isEmpty()) {
                if (res.status == MangaTranslateRuntime.TLStatus.ALREADY_TARGET || res.status == MangaTranslateRuntime.TLStatus.BLANK_PAGE) {
                    // Nothing a retry could fix: the page is already in the
                    // target language, or it holds no readable text. Say so
                    // briefly instead of spinning "Translating..." through
                    // the whole retry budget.
                    if (tlBadge === badge) {
                        badge.post { if (tlBadge === badge && tlPageKey == pageKey) badge.text = res.note.ifBlank { "Nothing to translate" } }
                        delay(2500)
                        if (tlBadge === badge && tlPageKey == pageKey) { removeView(badge); tlBadge = null }
                    }
                    return@launch
                }
                // Longer backoff retries (see TL_RETRY_MS): a first-use model
                // download or a rate-limited engine needs minutes, not
                // seconds. The open page fills in by itself — the badge stays
                // up until it does (or the budget runs out), so nothing needs
                // a scroll or a reopen to appear.
                if (retry < TL_RETRY_MS.size && MangaTranslateRuntime.settings.enabled) {
                    delay(TL_RETRY_MS[retry])
                    if (tlPageKey == pageKey && MangaTranslateRuntime.settings.enabled) {
                        bindTranslation(pageKey, file, imgW, imgH, retry + 1)
                    } else if (tlBadge === badge) { removeView(badge); tlBadge = null }
                } else if (tlPageKey == pageKey && MangaTranslateRuntime.settings.enabled && tlBadge === badge) {
                    // Spent the retry budget with nothing to show (offline
                    // engine, rate limit): say so, and let a tap retry at
                    // once — instead of a blank page that only a scroll
                    // would ever fix.
                    badge.text = "Translation failed — tap to retry"
                    badge.isClickable = true
                    badge.setOnClickListener { bindTranslation(pageKey, file, imgW, imgH, 0) }
                } else if (tlBadge === badge) { removeView(badge); tlBadge = null }
                return@launch
            }
            if (tlBadge === badge) { removeView(badge); tlBadge = null }
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
        tlFile = null
        tlOverlay?.let { removeView(it) }
        tlOverlay = null
        tlBadge?.let { removeView(it) }
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

    /** Full-image pixel -> this view pixel, for the translation overlay. */
    private fun locateTranslationPoint(px: Float, py: Float): PointF? {
        val iv = getImageView() ?: return null
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

    private fun dp(value: Int): Int =
        (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()

    companion object {
        /** How many times a failed page retries itself before leaving the Retry button to the user. */
        const val AUTO_RETRY_MAX = 10
    }
}
