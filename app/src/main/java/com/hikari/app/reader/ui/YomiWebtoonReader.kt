package com.hikari.app.reader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hikari.app.reader.ReaderChapter
import com.hikari.app.reader.source.MangaSource
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonConfig
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonItem
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonTrailer
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File

@Composable
fun YomiWebtoonReader(
    source: MangaSource?,
    cacheDir: File,
    streamQueue: List<ReaderChapter>,
    streamSegments: List<List<Any>>,
    segSizes: List<Int>,
    bgColor: Color,
    textColor: Color,
    gaps: Boolean,
    config: WebtoonConfig,
    onHideMenu: () -> Unit,
    colorFilter: android.graphics.ColorFilter?,
    decodeWidth: Int,
    rgb565: Boolean,
    sharpen: Boolean,
    autoScroll: Boolean,
    autoScrollSpeedDp: Float,
    initialPageIndex: Int,
    trailer: WebtoonTrailer,
    viewerRef: MutableState<WebtoonViewer?>,
    onPageChanged: (seg: Int, page: Int, pageTotal: Int) -> Unit,
    onNearEndChanged: (Boolean) -> Unit,
    onNearStartChanged: (Boolean) -> Unit,
    onMenuTap: () -> Unit,
    onUserScroll: () -> Unit,
    onScrollingChanged: (Boolean) -> Unit,
    onTrailerRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current

    val items = remember(streamSegments) {
        buildList {
            streamSegments.forEachIndexed { segIdx, seg ->
                if (segIdx > 0) {
                    val ch = streamQueue.getOrNull(segIdx)
                    if (ch != null) add(WebtoonItem.Divider(ch.id, ch.name))
                }
                seg.forEachIndexed { pi, m ->
                    if (m is MangaSource.PageDescriptor) {
                        add(WebtoonItem.Page(segIdx, pi + 1, m, m.imageUrl))
                    }
                }
            }
        }
    }

    // Before the first chapter's pages are loaded there is nothing to show yet.
    if (items.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = textColor)
        }
        return
    }

    // The item list only changes when a chapter is streamed in; use a cheap signature so the
    // update block doesn't re-run a DiffUtil (and re-scroll) on every recomposition while reading.
    val itemsSignature = remember(streamQueue, streamSegments) {
        streamQueue.joinToString("|") { it.id } + "::" + streamSegments.joinToString("|") { it.size.toString() }
    }
    var lastSignature by remember { mutableStateOf("") }
    var lastIds by remember { mutableStateOf<List<String>>(emptyList()) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val v = WebtoonViewer(ctx)
            viewerRef.value = v
            v.source = source
            v.cacheDir = cacheDir
            v.gaps = gaps
            v.config = config
            v.onHideMenu = onHideMenu
            v.colorFilter = colorFilter
            v.decodeWidth = decodeWidth
            v.decodeRgb565 = rgb565
            v.sharpenPages = sharpen
            v.onPageChanged = { seg, page, total -> onPageChanged(seg, page, total) }
            v.onNearEndChanged = { near -> onNearEndChanged(near) }
            v.onNearStartChanged = { near -> onNearStartChanged(near) }
            v.onMenuTap = { onMenuTap() }
            v.onUserScroll = { onUserScroll() }
            v.onScrollingChanged = { onScrollingChanged(it) }
            v.onTrailerRetry = { onTrailerRetry() }
            v.setTheme(bgColor.toArgb(), textColor.toArgb())
            v.setItems(items, segSizes, trailer, initialPageIndex)
            v.view
        },
        update = { _ ->
            val v = viewerRef.value ?: return@AndroidView
            v.source = source ?: v.source
            v.cacheDir = cacheDir
            v.gaps = gaps
            v.config = config
            v.onHideMenu = onHideMenu
            v.colorFilter = colorFilter
            v.decodeWidth = decodeWidth
            v.decodeRgb565 = rgb565
            v.sharpenPages = sharpen
            v.setTheme(bgColor.toArgb(), textColor.toArgb())
            v.onNearStartChanged = { near -> onNearStartChanged(near) }
            if (lastSignature != itemsSignature) {
                lastSignature = itemsSignature
                val ids = streamQueue.map { it.id }
                val oldIds = lastIds
                val prependedSegments = when {
                    oldIds.isEmpty() -> 0
                    ids.take(oldIds.size) == oldIds -> 0
                    ids.size > oldIds.size && ids.takeLast(oldIds.size) == oldIds ->
                        ids.size - oldIds.size
                    else -> 0
                }
                val prepended = if (prependedSegments > 0) {
                    segSizes.take(prependedSegments).sum() + prependedSegments
                } else {
                    0
                }
                lastIds = ids
                v.setItems(items, segSizes, trailer, initialPageIndex, prepended)
            } else if (v.trailer != trailer) {
                v.setTrailer(trailer)
            }
        },
    )

    // Yomi-style auto-scroll: drives the native recycler directly; any user touch stops it (the
    // viewer reports drags and taps through onUserScroll). With smoothAutoScroll the strip glides
    // via the recycler's smooth-scroll animation, otherwise it scrolls frame-by-frame.
    LaunchedEffect(viewerRef.value, autoScroll, autoScrollSpeedDp, config.smoothAutoScroll) {
        val v = viewerRef.value ?: return@LaunchedEffect
        if (!autoScroll) return@LaunchedEffect
        val pxPerMs = with(density) { autoScrollSpeedDp.dp.toPx() } / 1000f
        while (isActive) {
            if (config.smoothAutoScroll) {
                // One screen-height glide per ~1.1s keeps the pace near the chosen speed.
                val step = (pxPerMs * 1100f).toInt().coerceAtLeast(1)
                v.smoothScrollBy(step, 1100L)
                delay(1100)
            } else {
                v.scrollBy((pxPerMs * 16f).toInt().coerceAtLeast(1))
                delay(16)
            }
        }
    }
}
