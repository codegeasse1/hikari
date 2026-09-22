package com.hikari.app.manga

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

/**
 * One TALL page (see [PageBitmaps.isTallPage]) drawn by [ChunkedPageView], the
 * ported chunked renderer.
 *
 * **Why a tall page is not a bitmap here.** A page taller than three times its
 * own width is a webtoon strip, and a strip drawn as ONE bitmap is a bitmap one
 * page tall: no phone can hold that as a texture, so the platform splits the draw
 * into a grid of tiles — and on the devices that reported the broken pages the
 * tiles come out displaced and blank (the artwork sliced into a lattice of cells
 * showing the wrong rows, or nothing). [ChunkedPageView] is the fix and it is the
 * ported reader's own renderer: the page is decoded into bounded chunks and drawn
 * chunk by chunk, so nothing the compositor ever sees is bigger than one chunk.
 * [PageBitmaps] carries the full story of the two shapes and their budgets.
 *
 * **Touch.** The view ignores touch (`ChunkedPageView` never consumes a gesture),
 * so the reader's list keeps every scroll, fling and tap exactly as it had them.
 *
 * [fitInside] is a property of the reader MODE, not of the page: the webtoon strip
 * and `MangaFit.WIDTH` draw the page at the view's full width, while the paged
 * fit modes scale the whole page into the viewport (see `ChunkedPageView.drawScale`).
 * It is set from the caller's own fit, so the two shapes cannot disagree.
 */
@Composable
internal fun NekoPageView(
    file: File,
    sourceUrl: String,
    fitInside: Boolean,
    label: String,
    onReady: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Read at CALL time, not at factory time: the AndroidView factory runs once,
    // and the page it is reporting on changes under it as the reader scrolls (each
    // page is its own item, so a recycled view can be asked about the previous
    // page's url).
    val url = rememberUpdatedState(sourceUrl)
    val ready = rememberUpdatedState(onReady)
    // Read BEFORE the factory: the factory lambda has no view of the composable's
    // parameters once it is running, and capturing the value (rather than reading
    // [fitInside] inside `apply`, where the name means the view's own property)
    // keeps the initial fit from being a self-assignment.
    val initialFit = fitInside
    AndroidView(
        factory = { ctx ->
            ChunkedPageView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                fitInside = initialFit
            }
        },
        modifier = modifier,
        update = { view ->
            view.debugLabel = label
            view.fitInside = fitInside
            view.onReady = { ready.value() }
            // A page the platform cannot decode at all is reported back to the
            // loader, so a damaged file becomes the reader's Failed row with a
            // retry button instead of a page that stays blank forever (see
            // [MangaPageLoader.markUndecodable]).
            view.onError = { MangaPageLoader.markUndecodable(url.value) }
            // Idempotent for the page it already holds (a re-composition, a
            // settings change, a scroll out and back), so this costs nothing on
            // the frames where nothing changed — and a page whose FILE changed is
            // picked up here, because a retry writes a new file (see
            // MangaPageLoader.write).
            view.setPage(file)
        },
        onRelease = { it.clear() },
    )
}
