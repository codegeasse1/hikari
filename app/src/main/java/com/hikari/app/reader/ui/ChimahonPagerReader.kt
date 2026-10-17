package com.hikari.app.reader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.hikari.app.reader.source.MangaSource
import eu.kanade.tachiyomi.ui.reader.viewer.pager.L2RPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerConfig
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerPage
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.R2LPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.VerticalPagerViewer
import java.io.File

/**
 * Hosts the chimahon pager viewer (a DirectionalViewPager-based reader) inside Compose for the
 * paged reading modes (left-to-right, right-to-left, vertical). Pages are downloaded once to the
 * on-device cache and region-decoded from disk by the page holder, exactly like the webtoon
 * reader. All paging/zoom/tap behavior is handled by the native viewer. The settings ([config])
 * are a snapshot rebuilt by the caller whenever any of the underlying preferences change;
 * assigning it to the viewer re-applies tap zones, zoom and scale behavior live.
 */
@Composable
fun ChimahonPagerReader(
    source: MangaSource?,
    cacheDir: File,
    pages: List<MangaSource.PageDescriptor>,
    bgColor: Color,
    textColor: Color,
    vertical: Boolean,
    reversed: Boolean,
    config: PagerConfig,
    initialPageIndex: Int,
    viewerRef: MutableState<PagerViewer?>,
    onPageChanged: (page: Int, pageTotal: Int) -> Unit,
    onMenuTap: () -> Unit,
    onZoom: () -> Unit,
    colorFilter: android.graphics.ColorFilter?,
    decodeWidth: Int,
    modifier: Modifier = Modifier,
) {
    val items = remember(pages) {
        pages.mapIndexed { i, d -> PagerPage(i + 1, d) }
    }

    if (items.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = textColor)
        }
        return
    }

    var lastItems by remember { mutableStateOf<List<PagerPage>?>(null) }
    var lastInitial by remember { mutableStateOf(-1) }

    // Keying on the direction forces a fresh viewer when the user switches between left-to-right,
    // right-to-left and vertical inside a chapter (the AndroidView factory only runs once per
    // composition otherwise, so the pager direction is fixed at creation).
    key(vertical, reversed) {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                val v: PagerViewer = when {
                    vertical -> VerticalPagerViewer(ctx)
                    reversed -> R2LPagerViewer(ctx)
                    else -> L2RPagerViewer(ctx)
                }
                viewerRef.value = v
                v.source = source
                v.cacheDir = cacheDir
                v.decodeWidth = decodeWidth
                v.colorFilter = colorFilter
                v.onPageChanged = { page, total -> onPageChanged(page, total) }
                v.onMenuTap = { onMenuTap() }
                v.onZoom = { onZoom() }
                v.setTheme(bgColor.toArgb(), textColor.toArgb())
                v.config = config
                lastItems = items
                lastInitial = initialPageIndex
                v.setPages(items, initialPageIndex + 1)
                v.view
            },
            update = { _ ->
                val v = viewerRef.value ?: return@AndroidView
                v.source = source ?: v.source
                v.cacheDir = cacheDir
                v.decodeWidth = decodeWidth
                v.colorFilter = colorFilter
                v.setTheme(bgColor.toArgb(), textColor.toArgb())
                v.config = config
                if (lastItems !== items || lastInitial != initialPageIndex) {
                    lastItems = items
                    lastInitial = initialPageIndex
                    v.setPages(items, initialPageIndex + 1)
                }
            },
        )
    }
}
