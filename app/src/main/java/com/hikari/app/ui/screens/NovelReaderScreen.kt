package com.hikari.app.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.hikari.app.HikariApp
import com.hikari.app.data.Episode
import com.hikari.app.data.Logs
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.manga.MangaChapter
import com.hikari.app.manga.MangaProgress
import com.hikari.app.manga.MangaStore
import com.hikari.app.manga.NovelHighlight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The novel reader: text chapters from plain novel sources (see
 * [com.hikari.app.manga.MangaExtensionManager.isNovelId]).
 *
 * Separate from the image reader on purpose: text needs flowing paragraphs,
 * font sizing and light/sepia/dark themes — none of which belongs in the
 * image pipeline. Chapters, the chapter list and progress ride the same
 * [MangaStore] the image reader uses (same key shape), so follows, history
 * and "where was I" work across both.
 *
 * Chapter text arrives as [com.hikari.app.data.StreamSource.text] parts via
 * the provider's novel `getStreams` branch (blank urls, so the image reader
 * can never pick them up); they are joined here in order.
 *
 * The Mark button highlights words, lines or whole paragraphs in 12 colors —
 * saved per novel+chapter and restored on reopen. A long-press on any word
 * (~0.5s) starts a copy-like selection anywhere: drag to the wanted word or
 * line and release to commit it.
 */
@Composable
fun NovelReaderScreen(
    nav: NavHostController,
    providerId: String,
    mangaUrl: String,
    chapterUrl: String,
    title: String,
    posterUrl: String,
) {
    val context = LocalContext.current
    val app = context.applicationContext as HikariApp
    val key = "$providerId|$mangaUrl"

    var chapters by remember { mutableStateOf<List<MangaChapter>>(MangaStore.chaptersFor(key).orEmpty()) }
    // Resume the saved chapter when opened without one (continue-reading cards).
    var chapter by remember {
        mutableStateOf(
            chapterUrl.ifBlank { MangaStore.progressFor(key)?.chapterUrl.orEmpty() }
                .ifBlank { chapters.firstOrNull()?.url.orEmpty() }
        )
    }
    var paragraphs by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var fontSize by remember { mutableFloatStateOf(18f) }
    // 0 = dark, 1 = sepia, 2 = light.
    var theme by remember { mutableIntStateOf(0) }
    var showChapters by remember { mutableStateOf(false) }
    // Highlighter: mark mode + word/line/paragraph unit + the active color.
    // Highlights persist per novel+chapter (see MangaStore) and are restored
    // whenever the chapter loads.
    var highlightMode by remember { mutableStateOf(false) }
    var hlUnit by remember { mutableIntStateOf(0) }
    var hlColor by remember { mutableIntStateOf(NovelHighlight.COLORS.first()) }
    // Erase mode: tap removes the highlight under the finger, a long-press
    // drag removes every highlight the drag touches (the only way to undo a
    // free-dragged range, which can never be re-tapped exactly).
    var hlErase by remember { mutableStateOf(false) }
    var highlights by remember { mutableStateOf<List<NovelHighlight>>(emptyList()) }

    val item = remember(providerId, mangaUrl, title, posterUrl) {
        MediaItem(
            providerId = providerId,
            id = mangaUrl,
            title = title.ifBlank { mangaUrl },
            type = MediaType.SERIES,
            posterUrl = posterUrl.takeIf { it.isNotBlank() },
            rawType = "manga",
        )
    }
    val providerName = remember(providerId) { app.providers.byId(providerId)?.config?.name.orEmpty() }
    val chapterIndex = chapters.indexOfFirst { it.url == chapter }
    val chapterLabel = chapters.getOrNull(chapterIndex)?.label ?: title.ifBlank { mangaUrl }

    // The chapter list normally arrives cached (the detail screen fetched it).
    LaunchedEffect(key) {
        if (chapters.isNotEmpty()) return@LaunchedEffect
        Logs.log("Novel", "chapters [$providerId] ${mangaUrl.take(80)} start (cache empty)")
        val ct0 = SystemClock.elapsedRealtime()
        val p = app.providers.byId(providerId) ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { p.getEpisodes(item) } }
        chapters = MangaStore.chaptersFor(key).orEmpty()
        Logs.log("Novel", "chapters [$providerId] ${mangaUrl.take(80)} end in ${SystemClock.elapsedRealtime() - ct0}ms count=${chapters.size}")
        if (chapter.isBlank()) chapter = chapters.firstOrNull()?.url.orEmpty()
    }

    // Load the chapter text, restore the saved position, then follow it.
    val listState = rememberLazyListState()
    LaunchedEffect(key, chapter, reload) {
        paragraphs = emptyList()
        error = null
        loading = true
        val idx0 = chapters.indexOfFirst { it.url == chapter }
        Logs.log("Novel", "chapter [$providerId] ${mangaUrl.take(60)} tap idx=$idx0/${chapters.size} id=${chapter.take(80)} attempt=$reload")
        val xt0 = SystemClock.elapsedRealtime()
        val p = app.providers.byId(providerId)
        if (p == null) {
            Logs.log("Novel", "chapter [$providerId] ${mangaUrl.take(60)} FAIL engine not installed")
            error = "This novel engine is not installed"
            loading = false
            return@LaunchedEffect
        }
        val idx = chapters.indexOfFirst { it.url == chapter }
        val ch = chapters.getOrNull(idx)
        val ep = Episode(
            number = if (idx >= 0) idx + 1 else 1,
            id = chapter,
            name = ch?.name,
            image = null,
            season = 1,
        )
        val streams = withContext(Dispatchers.IO) {
            runCatching { p.getStreams(item, ep) }.getOrNull().orEmpty()
        }
        val parts = streams.mapNotNull { it.text.takeIf { t -> t.isNotBlank() } }
        if (parts.isEmpty()) {
            Logs.log("Novel", "chapter [$providerId] ${mangaUrl.take(60)} id=${chapter.take(60)} FAIL no text in ${SystemClock.elapsedRealtime() - xt0}ms")
            error = "Chapter text unavailable"
            loading = false
            return@LaunchedEffect
        }
        val paras = parts.flatMap { it.split("\n\n") }
            .map { it.trim() }
            .filter { it.isNotBlank() }
        Logs.log("Novel", "chapter [$providerId] ${mangaUrl.take(60)} id=${chapter.take(60)} OK parts=${parts.size} paras=${paras.size} chars=${paras.sumOf { it.length }} in ${SystemClock.elapsedRealtime() - xt0}ms")
        paragraphs = paras
        // Restore this chapter's highlights (persisted per novel+chapter).
        highlights = MangaStore.highlightsFor(key).filter { it.chapterUrl == chapter }
        loading = false
        // Mark the chapter open (progress), then restore + follow the position.
        val chName = ch?.label ?: chapter
        MangaStore.setProgress(
            MangaProgress(
                mangaKey = key,
                providerId = providerId,
                providerName = providerName,
                mangaUrl = mangaUrl,
                title = title.ifBlank { mangaUrl },
                posterUrl = posterUrl.takeIf { it.isNotBlank() },
                chapterUrl = chapter,
                chapterName = chName,
                page = 0,
                pages = paras.size,
                at = System.currentTimeMillis(),
            )
        )
        val saved = MangaStore.progressFor(key)?.takeIf { it.chapterUrl == chapter }
        if (saved != null && saved.page in paras.indices && saved.page > 0) {
            runCatching { listState.scrollToItem(saved.page) }
        }
        var last = -1
        while (true) {
            delay(1500)
            val at = listState.firstVisibleItemIndex
            if (at != last && at in paras.indices) {
                last = at
                MangaStore.setProgress(
                    MangaProgress(
                        mangaKey = key,
                        providerId = providerId,
                        providerName = providerName,
                        mangaUrl = mangaUrl,
                        title = title.ifBlank { mangaUrl },
                        posterUrl = posterUrl.takeIf { it.isNotBlank() },
                        chapterUrl = chapter,
                        chapterName = chName,
                        page = at,
                        pages = paras.size,
                        at = System.currentTimeMillis(),
                    )
                )
            }
        }
    }

    fun stepChapter(next: Boolean) {
        if (chapters.isEmpty() || chapterIndex < 0) return
        val to = if (next) chapterIndex + 1 else chapterIndex - 1
        chapters.getOrNull(to)?.let {
            Logs.log("Novel", "chapter [$providerId] ${mangaUrl.take(60)} step ${if (next) "next" else "prev"} $chapterIndex->$to id=${it.url.take(60)}")
            chapter = it.url
        }
    }

    /** The word holding [offset] in [text] (start to exclusive end), or null
     *  when the tap landed on whitespace/punctuation (nothing to mark). */
    fun wordRange(text: String, offset: Int): Pair<Int, Int>? {
        if (offset !in text.indices) return null
        var s = offset
        while (s > 0 && (text[s - 1].isLetterOrDigit() || text[s - 1] == '\'')) s--
        var e = offset
        while (e < text.length && (text[e].isLetterOrDigit() || text[e] == '\'')) e++
        return if (e > s) s to e else null
    }

    /** Toggles the highlight for one range: tapping the same range in the
     *  same color removes it, tapping it in another color recolors it. */
    fun toggleHighlight(paraIndex: Int, start: Int, end: Int) {
        val tag = "chapter [$providerId] ${mangaUrl.take(60)} id=${chapter.take(60)}"
        val existing = highlights.firstOrNull {
            it.paraIndex == paraIndex && it.start == start && it.end == end
        }
        if (existing != null && existing.color == hlColor) {
            MangaStore.clearHighlightExact(key, chapter, paraIndex, start, end)
            Logs.log("Novel", "$tag highlight clear para=$paraIndex start=$start end=$end")
        } else {
            MangaStore.setHighlight(
                key,
                NovelHighlight(key, chapter, paraIndex, hlColor, start, end),
            )
            Logs.log("Novel", "$tag highlight add para=$paraIndex start=$start end=$end color=$hlColor")
        }
        highlights = MangaStore.highlightsFor(key).filter { it.chapterUrl == chapter }
    }

    /** End offset of [h] inside a paragraph of [paraLen] (whole-paragraph
     *  marks store -1). */
    fun hlEnd(h: NovelHighlight, paraLen: Int): Int =
        if (h.end < 0) paraLen else h.end.coerceAtMost(paraLen)

    /** Removes every highlight in [paraIndex] holding [offset] (tap-erase:
     *  any overlapping range counts — no exact re-tap needed). */
    fun eraseAt(paraIndex: Int, offset: Int, paraLen: Int) {
        val tag = "chapter [$providerId] ${mangaUrl.take(60)} id=${chapter.take(60)}"
        val hits = highlights.filter {
            it.paraIndex == paraIndex &&
                offset >= it.start.coerceIn(0, paraLen) && offset < hlEnd(it, paraLen)
        }
        if (hits.isEmpty()) return
        hits.forEach { MangaStore.clearHighlightExact(key, chapter, it.paraIndex, it.start, it.end) }
        Logs.log("Novel", "$tag highlight erase-at para=$paraIndex offset=$offset count=${hits.size}")
        highlights = MangaStore.highlightsFor(key).filter { it.chapterUrl == chapter }
    }

    /** Removes every highlight in [paraIndex] touching [start, end)
     *  (drag-erase: undoes free-dragged ranges). */
    fun eraseRange(paraIndex: Int, start: Int, end: Int, paraLen: Int) {
        val tag = "chapter [$providerId] ${mangaUrl.take(60)} id=${chapter.take(60)}"
        val s = start.coerceIn(0, paraLen)
        val e = end.coerceIn(0, paraLen)
        if (e <= s) return
        val hits = highlights.filter {
            it.paraIndex == paraIndex &&
                s < hlEnd(it, paraLen) && e > it.start.coerceIn(0, paraLen)
        }
        if (hits.isEmpty()) return
        hits.forEach { MangaStore.clearHighlightExact(key, chapter, it.paraIndex, it.start, it.end) }
        Logs.log("Novel", "$tag highlight erase-range para=$paraIndex start=$s end=$e count=${hits.size}")
        highlights = MangaStore.highlightsFor(key).filter { it.chapterUrl == chapter }
    }

    val bg = when (theme) {
        1 -> Color(0xFFF4ECD8)
        2 -> Color(0xFFFFFFFF)
        else -> Color(0xFF121212)
    }
    val fg = when (theme) {
        1 -> Color(0xFF3E3526)
        2 -> Color(0xFF1A1A1A)
        else -> Color(0xFFE8E8E8)
    }
    val dim = when (theme) {
        1 -> Color(0xFF8A7D63)
        2 -> Color(0xFF757575)
        else -> Color(0xFF9E9E9E)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
    ) {
        // Top bar: back, title, theme, font size.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = fg)
            }
            Text(
                text = chapterLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = fg,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { theme = (theme + 1) % 3 }) {
                Text(
                    text = when (theme) {
                        1 -> "Sepia"
                        2 -> "Light"
                        else -> "Dark"
                    },
                    color = fg,
                )
            }
            TextButton(
                onClick = { fontSize = (fontSize - 2f).coerceAtLeast(12f) },
                enabled = fontSize > 12f,
            ) { Text("A−", color = fg) }
            TextButton(
                onClick = { fontSize = (fontSize + 2f).coerceAtMost(28f) },
                enabled = fontSize < 28f,
            ) { Text("A+", color = fg) }
            TextButton(onClick = {
                highlightMode = !highlightMode
                if (!highlightMode) hlErase = false
            }) {
                Text(
                    text = "Mark",
                    color = if (highlightMode) MaterialTheme.colorScheme.primary else fg,
                    fontWeight = if (highlightMode) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
        // Chapter navigation.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(
                onClick = { stepChapter(false) },
                enabled = chapterIndex > 0,
            ) { Text("‹ Prev", color = if (chapterIndex > 0) fg else dim) }
            Text(
                text = if (chapters.isEmpty()) "" else "${(chapterIndex + 1).coerceAtLeast(1)} / ${chapters.size}",
                style = MaterialTheme.typography.labelMedium,
                color = dim,
                modifier = Modifier.clickable { showChapters = true },
            )
            TextButton(
                onClick = { stepChapter(true) },
                enabled = chapterIndex >= 0 && chapterIndex < chapters.size - 1,
            ) {
                Text(
                    "Next ›",
                    color = if (chapterIndex >= 0 && chapterIndex < chapters.size - 1) fg else dim,
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        // Highlighter controls: word/line/paragraph unit + the color palette.
        // Visible only in mark mode; the text itself becomes tappable below.
        if (highlightMode && !loading && error == null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf("Word", "Line", "Para").forEachIndexed { i, label ->
                    TextButton(onClick = { hlUnit = i }) {
                        Text(
                            text = label,
                            color = if (hlUnit == i) MaterialTheme.colorScheme.primary else fg,
                            fontWeight = if (hlUnit == i) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
                TextButton(onClick = { hlErase = !hlErase }) {
                    Text(
                        text = "Erase",
                        color = if (hlErase) MaterialTheme.colorScheme.primary else fg,
                        fontWeight = if (hlErase) FontWeight.Bold else FontWeight.Normal,
                    )
                }
                Text(
                    text = if (hlErase) "tap/drag to unmark" else "tap text to mark",
                    style = MaterialTheme.typography.labelSmall,
                    color = dim,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                NovelHighlight.COLORS.chunked(6).forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        row.forEach { c ->
                            Box(
                                modifier = Modifier
                                    .padding(4.dp)
                                    .size(30.dp)
                                    .background(Color(c), CircleShape)
                                    .border(
                                        width = if (c == hlColor) 3.dp else 1.dp,
                                        color = if (c == hlColor) MaterialTheme.colorScheme.primary else dim,
                                        shape = CircleShape,
                                    )
                                    .clickable { hlColor = c },
                            )
                        }
                    }
                }
            }
        }
        when {
            loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }
            error != null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error.orEmpty(), color = fg)
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = {
                            Logs.log("Novel", "chapter [$providerId] ${mangaUrl.take(60)} id=${chapter.take(60)} retry tap (attempt=${reload + 1})")
                            reload++
                        }) { Text("Try again") }
                    }
                }
            }
            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp),
                ) {
                    item {
                        Text(
                            text = chapterLabel,
                            fontSize = (fontSize + 4f).sp,
                            fontWeight = FontWeight.Bold,
                            color = fg,
                            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                        )
                    }
                    items(paragraphs.size) { idx ->
                        val para = paragraphs[idx]
                        // Saved ranges render as background spans. Three ways to
                        // mark: in Mark mode a tap marks the word / line /
                        // paragraph under the finger (tap again in the same
                        // color to remove it, or use Erase); with Erase on, a
                        // tap removes the highlight under the finger and a
                        // long-press drag removes every highlight it touches.
                        // In ANY mode a long-press (~0.5s) on a word starts a
                        // copy-like selection — drag to the wanted word or
                        // line and release to commit it in the current color.
                        val paraHl = highlights.filter { it.paraIndex == idx }
                        var layout by remember(para) { mutableStateOf<TextLayoutResult?>(null) }
                        var liveSel by remember(para) { mutableStateOf<Pair<Int, Int>?>(null) }
                        val annotated = remember(para, paraHl, liveSel, hlColor) {
                            buildAnnotatedString {
                                append(para)
                                paraHl.forEach { h ->
                                    val e = if (h.end < 0) para.length
                                    else h.end.coerceAtMost(para.length)
                                    val s = h.start.coerceIn(0, e)
                                    if (e > s) {
                                        addStyle(SpanStyle(background = Color(h.color)), s, e)
                                    }
                                }
                                liveSel?.let { (a, b) ->
                                    val s = minOf(a, b).coerceIn(0, para.length)
                                    val e = maxOf(a, b).coerceIn(0, para.length)
                                    if (e > s) {
                                        addStyle(SpanStyle(background = Color(hlColor)), s, e)
                                    }
                                }
                            }
                        }
                        Text(
                            text = annotated,
                            fontSize = fontSize.sp,
                            lineHeight = (fontSize + 8f).sp,
                            color = fg,
                            onTextLayout = { layout = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 14.dp)
                                .pointerInput(highlightMode, hlUnit, hlErase, para) {
                                    detectTapGestures(
                                        onTap = { pos ->
                                            if (!highlightMode) return@detectTapGestures
                                            val l = layout ?: return@detectTapGestures
                                            val offset = l.getOffsetForPosition(pos)
                                            if (hlErase) {
                                                eraseAt(idx, offset, para.length)
                                                return@detectTapGestures
                                            }
                                            val range = when (hlUnit) {
                                                1 -> {
                                                    val line = l.getLineForOffset(offset)
                                                    l.getLineStart(line) to l.getLineEnd(line)
                                                }
                                                2 -> 0 to -1
                                                else -> wordRange(para, offset)
                                                    ?: return@detectTapGestures
                                            }
                                            toggleHighlight(idx, range.first, range.second)
                                        },
                                    )
                                }
                                .pointerInput(para) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { pos ->
                                            val l = layout
                                                ?: return@detectDragGesturesAfterLongPress
                                            val o = l.getOffsetForPosition(pos)
                                            liveSel = o to o
                                        },
                                        onDrag = { change, _ ->
                                            change.consume()
                                            val l = layout
                                                ?: return@detectDragGesturesAfterLongPress
                                            val o = l.getOffsetForPosition(change.position)
                                            liveSel = liveSel?.let { it.first to o } ?: (o to o)
                                        },
                                        onDragEnd = {
                                            val sel = liveSel
                                            liveSel = null
                                            if (sel != null) {
                                                var s = minOf(sel.first, sel.second)
                                                    .coerceIn(0, para.length)
                                                var e = maxOf(sel.first, sel.second)
                                                    .coerceIn(0, para.length)
                                                if (hlErase) {
                                                    eraseRange(idx, s, e, para.length)
                                                    return@detectDragGesturesAfterLongPress
                                                }
                                                // Snap to word edges, like a
                                                // copy selection.
                                                while (s > 0 && (para[s - 1].isLetterOrDigit() || para[s - 1] == '\'')) s--
                                                while (e < para.length && (para[e].isLetterOrDigit() || para[e] == '\'')) e++
                                                if (e > s) toggleHighlight(idx, s, e)
                                            }
                                        },
                                        onDragCancel = { liveSel = null },
                                    )
                                },
                        )
                    }
                    item { Spacer(Modifier.height(48.dp)) }
                }
            }
        }
    }

    if (showChapters) {
        AlertDialog(
            onDismissRequest = { showChapters = false },
            confirmButton = {},
            text = {
                LazyColumn {
                    items(chapters) { ch ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    Logs.log("Novel", "chapter [$providerId] ${mangaUrl.take(60)} picker id=${ch.url.take(60)}")
                                    chapter = ch.url
                                    showChapters = false
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = ch.label,
                                color = if (ch.url == chapter) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (ch.url == chapter) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            },
        )
    }
}
