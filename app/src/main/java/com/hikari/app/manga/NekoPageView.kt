package com.hikari.app.manga

import android.content.Context
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.hikari.app.data.Logs
import java.io.File
import java.io.FileInputStream

/**
 * A TALL page (see [PageBitmaps.isTallPage]) drawn by the subsampling view —
 * the port of the reader that works, Nekoread's.
 *
 * **Where this comes from.** This is `WebtoonSubsamplingItem` from Nekoread's
 * source (`com/example/ui/screens/WebtoonSubsamplingItem.kt`) — the same app
 * whose reader loads these pages correctly on the very phones that break here,
 * and the reader the user asked to have ported. Every setting below is that
 * file's, unchanged, because the settings are the part that works: the view
 * region-decodes the page FILE (a downsampled base layer plus the tiles the
 * viewport actually needs, at [SubsamplingScaleImageView.setMinimumTileDpi]),
 * so no bitmap the size of the page is ever allocated and no page-sized texture
 * is ever handed to the compositor. That is what makes a 20 000px webtoon strip
 * a strip instead of a scrambled grid.
 *
 * **Why the whole page is not a bitmap here.** Hikari's own reader drew a page
 * by decoding it whole and letting Compose scale it. For anything taller than
 * the device's maximum texture size the compositor cannot draw that bitmap
 * directly, and the fallback it uses instead is what has been scattering the
 * artwork into displaced blocks since 0.10.9. There is no size at which
 * "decode it whole and hope" is safe for a webtoon page, which is exactly why
 * Nekoread never does it — and why this file exists.
 *
 * **What Hikari adds to the port.** Two things, and only two:
 *
 *  * the page's BYTES are already on disk (Hikari's [MangaPageLoader] fetched
 *    and validated them, and a file per fetch means a retry yields a new path),
 *    so there is no download here at all;
 *  * the view reports a page it cannot decode back to that loader
 *    ([MangaPageLoader.markUndecodable]), so a damaged file becomes the
 *    reader's Failed row with a retry button instead of an empty pane.
 *
 * The view ignores touch ([NekoPageImageView]); the reader's own list owns every
 * gesture, exactly as Nekoread's does.
 */
@Composable
internal fun NekoPageView(
    file: File,
    sourceUrl: String,
    modifier: Modifier = Modifier,
) {
    // Read at CALL time, not at factory time: the AndroidView factory runs once,
    // and the page it is reporting on changes under it as the reader scrolls
    // (each page is its own item, so a recycled view can be asked about the
    // previous page's url).
    val url = rememberUpdatedState(sourceUrl)
    val current = rememberUpdatedState(file)
    AndroidView(
        factory = { ctx ->
            NekoPageImageView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_FIT_WIDTH)
                setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_INSIDE)
                setMinimumTileDpi(180)
                // Never downsample the initial load: even a short strip is shown
                // at full sharpness (yomi's reader does the same).
                setMinimumDpi(1)
                setDoubleTapZoomStyle(SubsamplingScaleImageView.ZOOM_FOCUS_CENTER)
                setZoomEnabled(false)
                // Defer high-res tile decoding until the scroll settles —
                // decoding tiles mid-fling is the single biggest dropped-frame
                // source on a long strip.
                setEagerLoadingEnabled(false)
                setOnImageEventListener(
                    object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                        override fun onImageLoadError(e: Exception) {
                            Logs.log(
                                "Manga",
                                "tall page undecodable (${current.value.name}): ${e.message}",
                            )
                            MangaPageLoader.markUndecodable(url.value)
                        }
                    }
                )
            }
        },
        modifier = modifier,
        update = { view ->
            // Keyed on the FILE, not the page: a retry writes a new file, so this
            // is what makes the view pick the retried bytes up.
            val path = file.absolutePath
            if (view.tag as? String != path) {
                view.tag = path
                view.recycle()
                // A fresh stream per call is what the view asks for; it reads it
                // once and region-decodes from its own copy (Nekoread's own note
                // — this fork's ImageSource has no file factory).
                view.setImage(ImageSource.provider { FileInputStream(file) })
            }
        },
        onRelease = { it.recycle() },
    )
}

/** The subsampling view that ignores touch — the reader's list handles every
 *  gesture (ported verbatim from Nekoread's `NekoWebtoonImageView`). */
private class NekoPageImageView(context: Context) : SubsamplingScaleImageView(context) {
    override fun onTouchEvent(event: MotionEvent): Boolean = false
}
