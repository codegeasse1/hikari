package com.hikari.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.hikari.app.HikariApp
import com.hikari.app.data.Episode
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.StreamSource
import com.hikari.app.i18n.I18n
import com.hikari.app.i18n.tr
import com.hikari.app.manga.MangaChapter
import com.hikari.app.manga.MangaFit
import com.hikari.app.manga.MangaProvider
import com.hikari.app.manga.MangaProgress
import com.hikari.app.manga.MangaReadMode
import com.hikari.app.manga.MangaStore
import com.hikari.app.tv.TvMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The reader.
 *
 * A chapter arrives from the engine as a list of [StreamSource]s whose `url` is
 * one PAGE IMAGE and whose `headers` are the source's own request headers (many
 * manga CDNs reject a page fetched without the site's Referer). That is the whole
 * reason the manga provider reuses the video "stream" shape: the page list, the
 * per-source headers and the "one stream at a time" plumbing all already exist.
 *
 * Three reading modes (see [MangaReadMode]) and three fits ([MangaFit]) cover
 * what real manga releases are: single pages read left-to-right, the same read
 * right-to-left (Japanese), and one continuous vertical strip (webtoons, and
 * anything drawn with no page gaps). Progress is written into [MangaStore] on
 * every page change — debounced, and again when the screen goes away — so the
 * detail screen, the Manga tab and the My Stuff shelf all agree where the reader
 * stopped.
 *
 * The chrome (bars, slider) is deliberately borrowed from a video player: tap
 * the middle to show or hide it, tap a side to turn the page, and a television's
 * D-pad does the same through [onKeyEvent]. Nothing about the page itself is
 * ever mutated — no filters, no cropping — so what is drawn is what the source
 * published.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MangaReaderScreen(
    nav: NavHostController,
    providerId: String,
    mangaUrl: String,
    chapterUrl: String,
    title: String,
    posterUrl: String,
) {
    val context = LocalContext.current
    val app = context.applicationContext as HikariApp
    val scope = rememberCoroutineScope()
    val key = "$providerId|$mangaUrl"

    // ---- The chapter being read, and the chapter list it sits in ----
    var chapters by remember { mutableStateOf<List<MangaChapter>>(MangaStore.chaptersFor(key).orEmpty()) }
    var chapter by remember { mutableStateOf(chapterUrl) }
    val chapterIndex = chapters.indexOfFirst { it.url == chapter }

    // The whole item, in the shape a provider call needs.
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

    // The chapter list normally arrives cached (the detail screen fetched it);
    // opened straight from a "continue reading" card it may not be there yet.
    LaunchedEffect(key, chapters.isEmpty()) {
        if (chapters.isNotEmpty()) return@LaunchedEffect
        val p = app.providers.byId(providerId) as? MangaProvider ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { p.getEpisodes(item) } }
        chapters = MangaStore.chaptersFor(key).orEmpty()
    }

    // ---- The pages of the current chapter ----
    var pages by remember { mutableStateOf<List<StreamSource>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    // Bumped by the retry button.
    var reload by remember { mutableStateOf(0) }
    // Where the reader is, in pages. Both bodies report it, and it is what gets
    // saved; the pager/list states stay the source of truth for what is drawn.
    // Declared up here because the load below restores it.
    var page by remember { mutableStateOf(0) }

    LaunchedEffect(providerId, chapter, reload) {
        loading = true
        error = null
        pages = emptyList()
        val p = app.providers.byId(providerId) as? MangaProvider
        if (p == null) {
            error = I18n.t("This manga engine is not installed.")
            loading = false
            return@LaunchedEffect
        }
        val ep = Episode(number = chapterIndex + 1, id = chapter, name = null, season = 1)
        val out = withContext(Dispatchers.IO) {
            runCatching { p.getStreams(item, ep) }.getOrNull().orEmpty()
        }
        // A page whose image URL resolved to nothing is dropped rather than
        // drawn as a broken cell — some sources list a placeholder page.
        pages = out.filter { it.url.isNotBlank() }
        // Where to start: the page saved for THIS chapter if the reader has been
        // here before, otherwise the top. Set in the same breath as `pages` so
        // the debounced save below can never observe (and write back) the
        // placeholder 0 before the restored page lands.
        val saved = MangaStore.progressFor(key)
        page = if (saved != null && saved.chapterUrl == chapter) {
            saved.page.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        } else {
            0
        }
        if (pages.isEmpty()) {
            error = MangaProvider.lastOutcome[providerId]
                ?: I18n.t("This chapter returned no pages. Tap to try again.")
        }
        loading = false
    }

    // ---- Reader settings (Settings-shaped, stored in the app's preference store) ----
    val modeFlow = remember { app.store.mangaReadModeFlow() }
    val fitFlow = remember { app.store.mangaFitFlow() }
    val bgFlow = remember { app.store.mangaReaderBgFlow() }
    val awakeFlow = remember { app.store.mangaKeepAwakeFlow() }
    val numberFlow = remember { app.store.mangaShowPageNumberFlow() }
    val mode by modeFlow.collectAsState(initial = MangaReadMode.PAGED_LTR)
    val fit by fitFlow.collectAsState(initial = MangaFit.WIDTH)
    val bgKey by bgFlow.collectAsState(initial = "black")
    val keepAwake by awakeFlow.collectAsState(initial = true)
    val showNumber by numberFlow.collectAsState(initial = false)

    val background = remember(bgKey) {
        when (bgKey) {
            "grey" -> Color(0xFF202124)
            "white" -> Color(0xFFF4F4F4)
            else -> Color.Black
        }
    }
    val onTop = remember(bgKey) { if (bgKey == "white") Color.Black else Color.White }

    // Keep the page lit while it is being read (the default), unless the user
    // turned it off.
    val view = LocalView.current
    DisposableEffect(keepAwake) {
        view.keepScreenOn = keepAwake
        onDispose { view.keepScreenOn = false }
    }

    var chrome by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }

    fun openChapterIndex(i: Int) {
        val next = chapters.getOrNull(i) ?: return
        if (next.url == chapter) return
        chapter = next.url
        page = 0
        chrome = true
    }

    /** Move forward/backward by [delta] pages. Both reading modes share it: in
     *  webtoon the strip is scrolled to that page instead of the pager. */
    fun step(delta: Int) {
        page = (page + delta).coerceIn(0, (pages.size - 1).coerceAtLeast(0))
    }

    // ---- Progress ----
    fun saveProgress(p: Int) {
        if (pages.isEmpty()) return
        val c = chapters.firstOrNull { it.url == chapter }
        MangaStore.setProgress(
            MangaProgress(
                mangaKey = key,
                providerId = providerId,
                providerName = app.providers.byId(providerId)?.config?.name.orEmpty(),
                mangaUrl = mangaUrl,
                title = title,
                posterUrl = posterUrl.takeIf { it.isNotBlank() },
                chapterUrl = chapter,
                chapterName = c?.label.orEmpty(),
                page = p.coerceIn(0, pages.size - 1),
                pages = pages.size,
                at = System.currentTimeMillis(),
            )
        )
    }

    // Debounced: flipping through ten pages writes once, at the tenth.
    LaunchedEffect(page, chapter, pages.size) {
        if (pages.isEmpty()) return@LaunchedEffect
        delay(700)
        saveProgress(page)
    }

    // Leaving the reader records the page immediately (the debounce above would
    // otherwise be cancelled by the disposal).
    DisposableEffect(Unit) {
        onDispose { runCatching { saveProgress(page) } }
    }

    // The hardware/edge back button closes the chrome first, the way every
    // reader does — a bar over the page is a mode, not a destination.
    BackHandler(enabled = chrome || showSettings) {
        if (showSettings) showSettings = false else chrome = false
    }

    // ---- Controls ----
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun pageStep(delta: Int) {
        // In right-to-left mode the RIGHT arrow goes BACK (page 12 → 11) because
        // the book itself runs the other way; this is the single place that
        // decides, so the on-screen zones, the D-pad and the buttons all agree.
        val forwards = mode != MangaReadMode.PAGED_RTL
        step(if (forwards) delta else -delta)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(background)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { pageStep(-1); true }
                    Key.DirectionRight -> { pageStep(1); true }
                    Key.DirectionCenter, Key.Enter -> { chrome = !chrome; true }
                    else -> false
                }
            }
    ) {
        when {
            loading && pages.isEmpty() -> ReaderStatus(
                text = tr("Loading pages…"),
                spinner = true,
                color = onTop,
            )
            pages.isEmpty() -> ReaderStatus(
                text = error ?: tr("Nothing to show."),
                spinner = false,
                color = onTop,
                actionLabel = tr("Try again"),
                onAction = { reload++ },
            )
            mode == MangaReadMode.WEBTOON -> WebtoonBody(
                pages = pages,
                context = context,
                page = page,
                onPage = { page = it },
                onTap = { chrome = !chrome },
            )
            else -> PagedBody(
                pages = pages,
                context = context,
                fit = fit,
                reverse = mode == MangaReadMode.PAGED_RTL,
                page = page,
                onPage = { page = it },
                onTap = { chrome = !chrome },
                onStep = { pageStep(it) },
            )
        }

        // The page number, floating, when the user asked for it permanently.
        if (showNumber && pages.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(50),
                color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                Text(
                    "${page + 1} / ${pages.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }

        if (chrome) {
            ReaderTopBar(
                onBack = { nav.popBackStack() },
                title = chapters.getOrNull(chapterIndex)?.let { "${title} — ${it.label}" }
                    ?: title,
                onSettings = { showSettings = true },
            )
            ReaderBottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                page = page,
                pageCount = pages.size,
                onPage = { page = it },
                chapterLabel = chapters.getOrNull(chapterIndex)?.label.orEmpty(),
                hasPrev = chapterIndex > 0,
                hasNext = chapterIndex in 0..(chapters.size - 2),
                onPrevChapter = { openChapterIndex(chapterIndex - 1) },
                onNextChapter = { openChapterIndex(chapterIndex + 1) },
            )
        }

        // On a television the whole page is one focus target, so a tap-like
        // "select" from the remote must toggle the chrome too — the D-pad's
        // centre press already does (see onKeyEvent above), and the rows below
        // are ordinary clickables the focus ring can walk.
        if (TvMode.current() && chrome) {
            Text(
                tr("Press OK to hide the controls · ◀ ▶ turn the page"),
                style = MaterialTheme.typography.labelSmall,
                color = onTop.copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp),
            )
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            ReaderSettings(
                mode = mode,
                fit = fit,
                bgKey = bgKey,
                keepAwake = keepAwake,
                showNumber = showNumber,
                onMode = { v -> scope.launch { app.store.setMangaReadMode(v) } },
                onFit = { v -> scope.launch { app.store.setMangaFit(v) } },
                onBg = { v -> scope.launch { app.store.setMangaReaderBg(v) } },
                onAwake = { v -> scope.launch { app.store.setMangaKeepAwake(v) } },
                onNumber = { v -> scope.launch { app.store.setMangaShowPageNumber(v) } },
            )
        }
    }
}

/** A centred status line (loading, empty, or a retryable failure). */
@Composable
private fun ReaderStatus(
    text: String,
    spinner: Boolean,
    color: Color,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (spinner) {
            CircularProgressIndicator(Modifier.size(30.dp), color = color)
            Spacer(Modifier.height(12.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/**
 * Paged reading: one page per screen, swiped horizontally. The tap zones are the
 * familiar reader layout — a side for the previous/next page and the middle for
 * the controls — and they only ever receive TAPS, so the pager's own horizontal
 * drag keeps working underneath.
 */
@Composable
private fun PagedBody(
    pages: List<StreamSource>,
    context: android.content.Context,
    fit: String,
    reverse: Boolean,
    page: Int,
    onPage: (Int) -> Unit,
    onTap: () -> Unit,
    onStep: (Int) -> Unit,
) {
    val pager = rememberPagerState(pageCount = { pages.size })

    // The pager drives the reported page (single source of truth for the slider
    // and for what gets saved).
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { onPage(it) }
    }
    // ...and follows it when something else moved it (the slider, the buttons, a
    // D-pad press, a restored position).
    LaunchedEffect(page, pager.currentPage) {
        if (pager.currentPage != page && page in pages.indices) pager.scrollToPage(page)
    }

    Box(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            reverseLayout = reverse,
            beyondViewportPageCount = 1,
        ) { i ->
            PageImage(
                context = context,
                source = pages[i],
                fit = fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Tap zones. `indication = null` — an invisible region must not flash a
        // ripple over the artwork.
        Row(Modifier.fillMaxSize()) {
            TapZone(Modifier.weight(1f)) { onStep(-1) }
            TapZone(Modifier.weight(1.2f)) { onTap() }
            TapZone(Modifier.weight(1f)) { onStep(1) }
        }
    }
}

@Composable
private fun TapZone(modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        )
    )
}

/**
 * Continuous vertical reading. Each page is drawn at the full width and its real
 * aspect ratio, filled in as the image reports its size — without that a strip
 * of unknown-height boxes jumps under the reader's thumb as every page lands.
 */
@Composable
private fun WebtoonBody(
    pages: List<StreamSource>,
    context: android.content.Context,
    page: Int,
    onPage: (Int) -> Unit,
    onTap: () -> Unit,
) {
    val listState = rememberLazyListState()
    val ratios = remember { mutableStateMapOf<String, Float>() }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { onPage(it) }
    }
    LaunchedEffect(page, listState.firstVisibleItemIndex, pages.size) {
        if (page in pages.indices && page != listState.firstVisibleItemIndex) {
            listState.animateScrollToItem(page)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { onTap() } }
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(pages.size, key = { pages[it].url }) { i ->
                val src = pages[i]
                val ratio = ratios[src.url]
                Box(Modifier.fillMaxWidth()) {
                    if (ratio == null || ratio <= 0f) {
                        // A plausible page height behind the image, so the strip
                        // neither jumps as pages land nor looks broken while one
                        // is coming. The image itself is given a real height in
                        // the same case (below) — a zero-sized target would never
                        // be fetched, so the ratio would never arrive.
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(420.dp)
                                .background(Color(0xFF101010)),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(Modifier.size(22.dp), color = Color.White)
                        }
                    }
                    AsyncImage(
                        model = readerRequest(context, src),
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        onSuccess = { state ->
                            val size = state.painter.intrinsicSize
                            if (size.width > 0f && size.height > 0f) {
                                ratios[src.url] = size.height / size.width
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (ratio != null && ratio > 0f) Modifier.aspectRatio(1f / ratio)
                                else Modifier.height(420.dp)
                            ),
                    )
                }
            }
        }
    }
}

/**
 * One page image in a paged mode.
 *
 * [MangaFit.HEIGHT] (and [MangaFit.WHOLE], which differs only on a screen wider
 * than the page) draw the whole page inside the viewport. [MangaFit.WIDTH] is the
 * reading position for a tall page on a phone: full width, scrolled vertically —
 * which is why the image is given its true aspect ratio first (a page drawn at
 * the wrong height is either squashed or cropped, and neither is acceptable for
 * artwork).
 */
@Composable
private fun PageImage(
    context: android.content.Context,
    source: StreamSource,
    fit: String,
    modifier: Modifier = Modifier,
) {
    var ratio by remember(source.url) { mutableStateOf(0f) }
    val scroll = rememberScrollState()
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        if (fit == MangaFit.WIDTH) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
            ) {
                AsyncImage(
                    model = readerRequest(context, source),
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    onSuccess = { state ->
                        val size = state.painter.intrinsicSize
                        if (size.width > 0f && size.height > 0f) {
                            ratio = size.height / size.width
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (ratio > 0f) Modifier.aspectRatio(1f / ratio)
                            else Modifier.height(520.dp)
                        ),
                )
            }
        } else {
            AsyncImage(
                model = readerRequest(context, source),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * The request that actually fetches a page.
 *
 * The source's own headers (its User-Agent, its Referer, any cookie it set) are
 * replayed verbatim, because a manga CDN routinely answers a hotlink with a 403
 * — the extension's own request carried them, and so must this one. They come
 * from the provider (see MangaProvider.sourceHeaders).
 */
private fun readerRequest(
    context: android.content.Context,
    source: StreamSource,
): ImageRequest {
    val builder = ImageRequest.Builder(context).data(source.url)
    source.headers.forEach { (k, v) -> builder.addHeader(k, v) }
    return builder.build()
}

@Composable
private fun ReaderTopBar(
    onBack: () -> Unit,
    title: String,
    onSettings: () -> Unit,
) {
    Surface(
        color = Color.Black.copy(alpha = 0.78f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = tr("Back"),
                    tint = Color.White,
                )
            }
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSettings) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = tr("Reader settings"),
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun ReaderBottomBar(
    modifier: Modifier,
    page: Int,
    pageCount: Int,
    onPage: (Int) -> Unit,
    chapterLabel: String,
    hasPrev: Boolean,
    hasNext: Boolean,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
) {
    Surface(
        color = Color.Black.copy(alpha = 0.78f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            if (pageCount > 1) {
                Slider(
                    value = page.toFloat(),
                    onValueChange = { onPage(it.roundToInt()) },
                    valueRange = 0f..(pageCount - 1).toFloat(),
                    // No steps: the readout under the bar is the truth, and a
                    // stepped slider on a 900-page chapter is unusable.
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevChapter, enabled = hasPrev) {
                    Icon(
                        Icons.Filled.ChevronLeft,
                        contentDescription = tr("Previous chapter"),
                        tint = if (hasPrev) Color.White else Color.White.copy(alpha = 0.35f),
                    )
                }
                Text(
                    chapterLabel.ifBlank { tr("Chapter") },
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (pageCount > 0) "${page + 1} / $pageCount" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = onNextChapter, enabled = hasNext) {
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = tr("Next chapter"),
                        tint = if (hasNext) Color.White else Color.White.copy(alpha = 0.35f),
                    )
                }
            }
        }
    }
}

/** The reader's settings sheet: the two shapes of a chapter, and the page
 *  furniture around it. */
@Composable
private fun ReaderSettings(
    mode: String,
    fit: String,
    bgKey: String,
    keepAwake: Boolean,
    showNumber: Boolean,
    onMode: (String) -> Unit,
    onFit: (String) -> Unit,
    onBg: (String) -> Unit,
    onAwake: (Boolean) -> Unit,
    onNumber: (Boolean) -> Unit,
) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
        Text(
            tr("Reader"),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        SettingGroup(tr("Reading direction"))
        SettingRadio(tr("Left to right (comics, manhwa)"), mode == MangaReadMode.PAGED_LTR) { onMode(MangaReadMode.PAGED_LTR) }
        SettingRadio(tr("Right to left (manga)"), mode == MangaReadMode.PAGED_RTL) { onMode(MangaReadMode.PAGED_RTL) }
        SettingRadio(tr("Webtoon (vertical strip)"), mode == MangaReadMode.WEBTOON) { onMode(MangaReadMode.WEBTOON) }

        SettingGroup(tr("Page fit"))
        SettingRadio(tr("Fit width (scroll tall pages)"), fit == MangaFit.WIDTH) { onFit(MangaFit.WIDTH) }
        SettingRadio(tr("Fit height (whole page, spread)"), fit == MangaFit.HEIGHT) { onFit(MangaFit.HEIGHT) }
        SettingRadio(tr("Fit the screen"), fit == MangaFit.WHOLE) { onFit(MangaFit.WHOLE) }

        SettingGroup(tr("Background"))
        SettingRadio(tr("Black"), bgKey == "black") { onBg("black") }
        SettingRadio(tr("Dark grey"), bgKey == "grey") { onBg("grey") }
        SettingRadio(tr("White"), bgKey == "white") { onBg("white") }

        SettingGroup(tr("While reading"))
        SettingSwitch(tr("Keep the screen awake"), keepAwake, onAwake)
        SettingSwitch(tr("Show page number over the page"), showNumber, onNumber)
    }
}

@Composable
private fun SettingGroup(label: String) {
    Text(
        label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun SettingRadio(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
