package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import com.hikari.app.reader.cache.WebtoonPageCache
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
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

    /** Job for loading the page. */
    private var loadJob: Job? = null

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
        removeErrorLayout()
        super.recycle()
        progressContainer.isVisible = true
    }

    /** Called when the image is decoded and going to be displayed. */
    private fun onImageDecoded() {
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
                    setOnClickListener { load() }
                }
                addView(retry, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(12) })
            }
            addView(errorLayout)
        }
    }

    /** Removes the error layout from the holder, if found. */
    private fun removeErrorLayout() {
        errorLayout?.let {
            removeView(it)
            errorLayout = null
        }
    }

    private fun dp(value: Int): Int =
        (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
}
