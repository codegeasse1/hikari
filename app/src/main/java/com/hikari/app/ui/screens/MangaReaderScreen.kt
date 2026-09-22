package com.hikari.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.hikari.app.HikariApp
import com.hikari.app.data.Episode
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.StreamSource
import com.hikari.app.i18n.I18n
import com.hikari.app.i18n.tr
import com.hikari.app.ui.components.GlassSearchField
import com.hikari.app.ui.components.VerificationNudge
import com.hikari.app.web.WebViewActivity
import com.hikari.app.manga.MangaChapter
import com.hikari.app.manga.MangaFit
import com.hikari.app.manga.mangaEnhanceMatrix
import com.hikari.app.manga.NekoPageView
import com.hikari.app.manga.PageBitmaps
import com.hikari.app.manga.MangaPageLoader
import com.hikari.app.manga.MangaPageState
import com.hikari.app.manga.MangaProvider
import com.hikari.app.manga.MangaProgress
import com.hikari.app.manga.MangaReadMode
import com.hikari.app.manga.MangaStore
import com.hikari.app.tv.TvMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
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

    // ---- Reader settings (Settings-shaped, stored in the app's preference store) ----
    //
    // Declared up here because the reading surface itself depends on them: which
    // mode is in force decides whether a chapter is one page at a time or a
    // continuous strip (and therefore whether the reader keeps a RUN of
    // chapters), so they are read before any of that is built.
    val modeFlow = remember { app.store.mangaReadModeFlow() }
    val fitFlow = remember { app.store.mangaFitFlow() }
    val bgFlow = remember { app.store.mangaReaderBgFlow() }
    val awakeFlow = remember { app.store.mangaKeepAwakeFlow() }
    val numberFlow = remember { app.store.mangaShowPageNumberFlow() }
    val enhanceFlow = remember { app.store.mangaEnhanceFlow() }
    // The initial value is only what is drawn in the frame before the stored
    // preference lands; it matches [MangaReadMode.normalize]'s default (webtoon)
    // so a chapter never opens as a paged one and then re-lays itself out.
    val mode by modeFlow.collectAsState(initial = MangaReadMode.WEBTOON)
    val fit by fitFlow.collectAsState(initial = MangaFit.WIDTH)
    val bgKey by bgFlow.collectAsState(initial = "black")
    val keepAwake by awakeFlow.collectAsState(initial = true)
    val showNumber by numberFlow.collectAsState(initial = false)
    val enhance by enhanceFlow.collectAsState(initial = false)

    // ---- The chapters the ◀ ▶ buttons walk ----
    //
    // One entry per chapter NUMBER, not one per release. An aggregator lists the
    // same chapter once per scanlation group — the chapter list in the user's
    // screenshot has "Chapter 1" five times in a row — so stepping to the next
    // LIST entry stepped to the same chapter from the next group ("it again
    // opens chapter 1 instead of loading chapter 2"). The buttons walk this list
    // instead; the chapter sheet still shows every release, because wanting a
    // different group's scan of the chapter you are ON is a real thing to want.
    val currentScanlator = chapters.firstOrNull { it.url == chapter }?.scanlator
    val navChapters = remember(chapters, currentScanlator) {
        dedupeChapters(chapters, currentScanlator)
    }
    // Where the chapter on screen sits in that order (matched by NUMBER, so a
    // chapter opened from a different group than the list's own release is still
    // found).
    val navIndex = remember(navChapters, chapter) { navIndexOf(navChapters, chapter, chapters) }

    // ---- The pages of the chapter being read ----
    var pages by remember { mutableStateOf<List<StreamSource>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    // Bumped by the retry button.
    var reload by remember { mutableStateOf(0) }

    // ---- The reader's own way past a Cloudflare check ----------------------
    //
    // The Manga tab has a globe per engine and every catalog header has one, but
    // a chapter can be behind its own verification page ("some manga site also
    // put verification on chapter loading page, so add there a webview"). So the
    // reader carries the same escape hatch: the engine's own site is opened in
    // [WebViewActivity], the user passes whatever check the site wants, and the
    // chapter is re-fetched on the way back — because between opening the view
    // and closing it, the answer the site gives an extension's request can go
    // from a challenge to the real page list.
    val verifyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // The clearance is in the cookie jar now (the view flushes it before it
        // closes — see CloudflareVerifier.onVerifyViewClosed); ask the chapter
        // again, and drop the pages that came back before the verification.
        error = null
        reload++
    }
    val openVerify: () -> Unit = {
        scope.launch {
            val site = withContext(Dispatchers.IO) {
                runCatching {
                    app.providers.byId(providerId)?.config?.let {
                        com.hikari.app.manga.MangaExtensionManager.siteUrlOf(it)
                    }
                }.getOrNull()
            }
            if (site.isNullOrBlank()) {
                Toast.makeText(
                    context,
                    I18n.t("Couldn't determine this extension's site"),
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                val host = runCatching { java.net.URI(site).host?.lowercase() }.getOrNull()
                verifyLauncher.launch(
                    Intent(context, WebViewActivity::class.java).apply {
                        putExtra("url", site)
                        putExtra("title", "Verify: " + (host ?: title))
                        putExtra("providerId", providerId)
                        // Closes itself the moment the clearance is in the
                        // cookie jar, so the user never has to guess when they
                        // are "done" — which is also what makes the retry above
                        // land on a page that now answers.
                        putExtra("autoCloseWhenCloudflarePassed", true)
                        if (host != null) putExtra("verifyHost", host)
                    }
                )
            }
        }
    }
    // Where the reader is: the page of the chapter ON SCREEN, and which chapter
    // that is. Both are REPORTED by the body that draws the pages — in webtoon
    // mode the continuous strip can be showing a different chapter than the one
    // the reader was opened on — and both are what the readout, the scrubber and
    // the saved progress mean. Nothing MOVES because they changed: see the
    // request lane below, which is the single thing that may move the pages.
    var page by remember { mutableStateOf(0) }
    var visibleChapter by remember { mutableStateOf(chapterUrl) }

    // ---- The webtoon run ----
    //
    // In webtoon mode the pages on screen are a RUN of chapters, not one. A
    // webtoon's artwork has no chapter-sized break in it, so the strip must not
    // stop at one: reaching the end of a chapter fetches the next and appends
    // it, and scrolling above the top fetches the previous and prepends it (with
    // the page under the eye held still). That is what the user asked for —
    // "the reading must be continuation, no stopping" — and a title card is
    // drawn where each chapter begins so "chapter 2 started" is visible.
    //
    // Paged modes keep a single chapter, so the run is empty for them.
    var run by remember { mutableStateOf<List<RunBlock>>(emptyList()) }
    val runItems = remember(run) { flattenRun(run) }
    val inRun = remember(run) { run.map { it.chapterUrl }.toSet() }
    // The neighbours being fetched right now (both watchers can fire for the
    // same chapter in one pass), the neighbours that turned out to have no pages
    // (never asked twice — see the continuation watcher), and the two
    // single-flight flags that keep one append and one prepend in the air at a
    // time.
    val fetching = remember { mutableStateOf(emptySet<String>()) }
    val tried = remember(chapter) { mutableSetOf<String>() }
    val appending = remember { mutableStateOf(false) }
    val prepending = remember { mutableStateOf(false) }

    // ---- One lane for every request to move the reading surface ------------
    //
    // The bug this exists to kill (reported twice — once for tapping the page
    // bar, once for dragging it): the surface used to be moved by an effect
    // keyed on the very page the surface reports, so the effect restarted — and
    // cancelled its own in-flight scroll — the moment the move it had just been
    // told to make came back as a report. On screen that is "I tap page 50, it
    // flashes there, and I am back on page 12"; a drag was worse, because every
    // page the finger crossed started its own scroll and the scrolls cancelled
    // each other, which is the bar "going back to where I started dragging".
    //
    // So the reader now SPEAKS in requests. The scrubber, the arrow buttons, a
    // D-pad press, the restored position and a chapter jump all publish a
    // [ScrollRequest]; the body that draws the pages carries it out exactly once
    // through its own [ReaderMover], and the body's reports may only update the
    // readout — never move anything. A loop is impossible by construction.
    var request by remember { mutableStateOf<ScrollRequest?>(null) }
    var requestSeq by remember { mutableIntStateOf(0) }

    /** The pages of [chapterUrl] as they are known right now (the run's copy for
     *  a neighbour, the loaded copy for the chapter being read). */
    fun pagesOf(chapterUrl: String): List<StreamSource> =
        run.firstOrNull { it.chapterUrl == chapterUrl }?.pages
            ?: if (chapterUrl == chapter) pages else emptyList()

    fun labelOf(chapterUrl: String): String =
        chapters.firstOrNull { it.url == chapterUrl }?.label.orEmpty()

    /** Ask the surface to show page [target] of [chapterUrl]. Optimistic: the
     *  readout moves in the same frame, and the body confirms it. */
    fun ask(target: Int, chapterUrl: String = visibleChapter) {
        val size = pagesOf(chapterUrl).size
        if (size == 0) return
        val t = target.coerceIn(0, size - 1)
        visibleChapter = chapterUrl
        page = t
        requestSeq += 1
        request = ScrollRequest(requestSeq, chapterUrl, t)
    }

    /** Where a request lands in the webtoon strip: a chapter's pages are one
     *  title card plus one item per page ([RunItem]). */
    fun itemIndexOf(r: ScrollRequest): Int {
        var idx = 0
        for (b in run) {
            if (b.chapterUrl == r.chapterUrl) {
                if (b.pages.isEmpty()) return idx
                return idx + 1 + r.page.coerceIn(0, b.pages.size - 1)
            }
            idx += 1 + b.pages.size
        }
        return -1
    }

    /** What the drawn surface reports: which chapter, and which page inside it.
     *  This is also where the preloading window is set, so the pages ahead of
     *  the reader are already coming down. */
    fun report(chapterUrl: String, p: Int) {
        visibleChapter = chapterUrl
        page = p
        val list = pagesOf(chapterUrl)
        if (list.isEmpty()) return
        MangaPageLoader.plan(list, p)
        // …and the page the thumb is heading for is not only FETCHED ahead, it is
        // DECODED ahead: the loader's window puts the bytes on disk, and this
        // puts the next page's pixels in the heap, so the frame the reader
        // scrolls onto is drawn instead of showing a spinner for the few hundred
        // milliseconds a decode costs. Exactly one page, and never at the expense
        // of the page on screen — see [PageBitmaps.prefetch], which gives up
        // rather than queue in front of a decode that is already running.
        list.getOrNull(p + 1)?.let { next ->
            scope.launch {
                val nextState = MangaPageLoader.state(next.url).value
                if (nextState is MangaPageState.Ready) {
                    PageBitmaps.prefetch(nextState.file, nextState.width, nextState.height)
                }
            }
        }
    }

    /** The pages of a chapter that is NOT the loaded one (the webtoon run's
     *  neighbours), fetched exactly the way the reader's own load does. */
    suspend fun pagesOfChapter(chapterUrl: String): List<StreamSource> =
        withContext(Dispatchers.IO) {
            val p = app.providers.byId(providerId) as? MangaProvider
                ?: return@withContext emptyList()
            val idx = chapters.indexOfFirst { it.url == chapterUrl }
            val ep = Episode(number = idx + 1, id = chapterUrl, name = null, season = 1)
            runCatching { p.getStreams(item, ep) }.getOrNull().orEmpty()
                .filter { it.url.isNotBlank() }
        }

    // ---- The strips' own scroll positions ----
    //
    // Hoisted out of the webtoon body because the reader itself has to watch the
    // continuous strip's position (that is what tells it the next chapter's edge
    // has come into view — see the continuation watcher below) and has to be
    // able to anchor the viewport when a chapter is prepended above it.
    val listState = rememberLazyListState()

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
        val restored = if (saved != null && saved.chapterUrl == chapter) {
            saved.page.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        } else {
            0
        }
        visibleChapter = chapter
        page = restored
        if (pages.isNotEmpty()) {
            // Pull the chapter in around the reader before anything is drawn:
            // the ten pages ahead are what the first swipe needs.
            MangaPageLoader.plan(pages, restored)
            requestSeq += 1
            request = ScrollRequest(requestSeq, chapter, restored)
        }
        if (pages.isEmpty()) {
            error = MangaProvider.lastOutcome[providerId]
                ?: I18n.t("This chapter returned no pages. Tap to try again.")
        }
        loading = false
    }

    // The run is rebuilt whenever the chapters on screen or their pages change
    // (a new chapter, a retry, or the reader switching between paged and webtoon
    // while a chapter is open), and the surface is put back on the page the
    // reader was on — the strip and the pager both start at their top, so this
    // request is what puts them where they belong.
    LaunchedEffect(mode, chapter, pages) {
        run = if (mode == MangaReadMode.WEBTOON && pages.isNotEmpty()) {
            listOf(RunBlock(chapter, labelOf(chapter), pages))
        } else {
            emptyList()
        }
        if (pages.isEmpty()) return@LaunchedEffect
        requestSeq += 1
        request = ScrollRequest(requestSeq, chapter, page)
    }

    // ---- The strip's continuation ----
    //
    // Watching the strip's own position is the only way to know the reader has
    // reached the edge of what is loaded: the last few items coming into view
    // fetches and appends the NEXT chapter, and reaching the first items fetches
    // and prepends the PREVIOUS one. Nothing is fetched twice (see inRun/tried),
    // and only ONE append and one prepend are ever in flight: two prepends at
    // once would both try to correct the scroll position they had just changed,
    // and the view would jump by the first chapter's length. An empty fetch
    // simply leaves the run as it is — a chapter with no pages must not break the
    // strip, and [tried] keeps it from being asked again on every scroll step.
    LaunchedEffect(run, navChapters, mode) {
        if (mode != MangaReadMode.WEBTOON || run.isEmpty()) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            Triple(
                listState.firstVisibleItemIndex,
                info.visibleItemsInfo.lastOrNull()?.index ?: -1,
                info.totalItemsCount,
            )
        }.collect { (first, last, total) ->
            if (total <= 0) return@collect
            if (last >= total - 3 && !appending.value) {
                val nextUrl = neighbourOf(run.last().chapterUrl, +1, navChapters, chapters)
                if (nextUrl != null && nextUrl !in inRun && nextUrl !in fetching.value &&
                    nextUrl !in tried
                ) {
                    appending.value = true
                    fetching.value = fetching.value + nextUrl
                    scope.launch {
                        val p = pagesOfChapter(nextUrl)
                        fetching.value = fetching.value - nextUrl
                        if (p.isEmpty()) tried += nextUrl
                        else if (run.none { it.chapterUrl == nextUrl }) {
                            run = run + RunBlock(nextUrl, labelOf(nextUrl), p)
                            MangaPageLoader.plan(p, 0)
                        }
                        appending.value = false
                    }
                }
            }
            if (first <= 2 && !prepending.value) {
                val prevUrl = neighbourOf(run.first().chapterUrl, -1, navChapters, chapters)
                if (prevUrl != null && prevUrl !in inRun && prevUrl !in fetching.value &&
                    prevUrl !in tried
                ) {
                    prepending.value = true
                    fetching.value = fetching.value + prevUrl
                    scope.launch {
                        val p = pagesOfChapter(prevUrl)
                        fetching.value = fetching.value - prevUrl
                        if (p.isEmpty()) {
                            tried += prevUrl
                            prepending.value = false
                            return@launch
                        }
                        if (run.none { it.chapterUrl == prevUrl }) {
                            // The anchor is read as LATE as possible — after the
                            // fetch, right before the strip grows — so it is the
                            // position the reader is really at when the new items
                            // land above the viewport.
                            val anchor = listState.firstVisibleItemIndex to
                                listState.firstVisibleItemScrollOffset
                            val added = 1 + p.size
                            run = listOf(RunBlock(prevUrl, labelOf(prevUrl), p)) + run
                            MangaPageLoader.plan(p, p.size - 1)
                            // Hold the page under the eye exactly where it was:
                            // the strip just grew by [added] items ABOVE the
                            // viewport, so the anchor moves down by that many. The
                            // reader only ever prepends with the anchor at the very
                            // top (first <= 2), so the target index cannot be past
                            // the end of the list it is measured against — which is
                            // what makes this safe without waiting for the new
                            // items to be laid out.
                            listState.scrollToItem(anchor.first + added, anchor.second)
                        }
                        prepending.value = false
                    }
                }
            }
        }
    }

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
    // The chapter picker: a reader without one has to go back to the title page
    // to move on, which is also the only way to jump around inside a long series
    // (a 300-chapter manhwa is not navigated by pressing "next").
    var showChapters by remember { mutableStateOf(false) }
    // The filter inside the chapter picker — its own state, so opening the sheet
    // twice does not show the previous search still applied.
    var chapterQuery by remember { mutableStateOf("") }

    /** Jump to the chapter at position [i] of [chapters] (the chapter sheet's
     *  rows report their position in the READING order, which is this array). */
    fun openChapterIndex(i: Int) {
        val next = chapters.getOrNull(i) ?: return
        // A chapter the strip already holds (a neighbour it pulled in) is jumped
        // to rather than fetched again — its pages are right there.
        if (run.any { it.chapterUrl == next.url }) {
            chrome = true
            ask(0, next.url)
            return
        }
        if (next.url == chapter) return
        chapter = next.url
        visibleChapter = next.url
        page = 0
        chrome = true
    }

    /** Step to the next/previous chapter NUMBER ([delta] = ±1 in [navChapters]). */
    fun stepChapter(delta: Int) {
        val i = if (navIndex >= 0) navIndex + delta else -1
        val target = navChapters.getOrNull(i) ?: return
        chapter = target.url
        visibleChapter = target.url
        page = 0
        chrome = true
    }

    /** Move forward/backward by [delta] pages. Both reading modes share it: the
     *  request lane carries it to whichever body is drawing the pages. */
    fun step(delta: Int) {
        ask(page + delta)
    }

    // ---- Progress ----
    fun saveProgress(p: Int) {
        val list = pagesOf(visibleChapter)
        if (list.isEmpty()) return
        val c = chapters.firstOrNull { it.url == visibleChapter }
        MangaStore.setProgress(
            MangaProgress(
                mangaKey = key,
                providerId = providerId,
                providerName = app.providers.byId(providerId)?.config?.name.orEmpty(),
                mangaUrl = mangaUrl,
                title = title,
                posterUrl = posterUrl.takeIf { it.isNotBlank() },
                chapterUrl = visibleChapter,
                chapterName = c?.label.orEmpty(),
                page = p.coerceIn(0, list.size - 1),
                pages = list.size,
                at = System.currentTimeMillis(),
            )
        )
    }

    // Debounced: flipping through ten pages writes once, at the tenth.
    LaunchedEffect(page, visibleChapter, pages.size) {
        if (pagesOf(visibleChapter).isEmpty()) return@LaunchedEffect
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
    BackHandler(enabled = chrome || showSettings || showChapters) {
        when {
            showSettings -> showSettings = false
            // The sheet is the topmost surface, so back closes IT first and the
            // chrome stays exactly as the user left it.
            showChapters -> showChapters = false
            else -> chrome = false
        }
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
            mode == MangaReadMode.WEBTOON -> WebtoonRunBody(
                entries = runItems,
                listState = listState,
                request = request,
                itemIndexOf = { itemIndexOf(it) },
                onReport = { c, p -> report(c, p) },
                onTap = { chrome = !chrome },
                enhance = enhance,
            )
            else -> PagedBody(
                pages = pages,
                chapterUrl = chapter,
                fit = fit,
                reverse = mode == MangaReadMode.PAGED_RTL,
                request = request,
                onReport = { c, p -> report(c, p) },
                onTap = { chrome = !chrome },
                onStep = { pageStep(it) },
                enhance = enhance,
            )
        }

        // A chapter whose PAGE LIST never arrives is usually a verification wall
        // (the site gates chapter requests, not just its catalog), so the reader
        // says so once, ten seconds in — and the nudge itself is the way to the
        // WebView, because telling the user to go and press another button is
        // one step worse than the button (see [VerificationNudge]).
        if (pages.isEmpty()) {
            VerificationNudge(
                waiting = loading,
                onOpenWebView = openVerify,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 60.dp),
            )
        }

        // The page number, floating, when the user asked for it permanently.
        if (showNumber && pagesOf(visibleChapter).isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(50),
                color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                Text(
                    "${page + 1} / ${pagesOf(visibleChapter).size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }

        if (chrome) {
            ReaderTopBar(
                onBack = { nav.popBackStack() },
                title = chapters.firstOrNull { it.url == visibleChapter }
                    ?.let { "${title} — ${it.label}" }
                    ?: title,
                onSettings = { showSettings = true },
                onChapters = if (chapters.isEmpty()) null else {
                    {
                        chapterQuery = ""
                        showChapters = true
                    }
                },
                chapterCount = chapters.size,
                onVerify = openVerify,
            )
            ReaderBottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                page = page,
                pageCount = pagesOf(visibleChapter).size,
                onPage = { ask(it) },
                chapterLabel = chapters.firstOrNull { it.url == visibleChapter }?.label.orEmpty(),
                hasPrev = navIndex > 0,
                hasNext = navIndex >= 0 && navIndex < navChapters.size - 1,
                onPrevChapter = { stepChapter(-1) },
                onNextChapter = { stepChapter(+1) },
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
                enhance = enhance,
                onMode = { v -> scope.launch { app.store.setMangaReadMode(v) } },
                onFit = { v -> scope.launch { app.store.setMangaFit(v) } },
                onBg = { v -> scope.launch { app.store.setMangaReaderBg(v) } },
                onAwake = { v -> scope.launch { app.store.setMangaKeepAwake(v) } },
                onNumber = { v -> scope.launch { app.store.setMangaShowPageNumber(v) } },
                onEnhance = { v -> scope.launch { app.store.setMangaEnhance(v) } },
            )
        }
    }

    if (showChapters && chapters.isNotEmpty()) {
        ChapterSheet(
            chapters = chapters,
            currentUrl = visibleChapter,
            query = chapterQuery,
            onQuery = { chapterQuery = it },
            onPick = { i ->
                showChapters = false
                openChapterIndex(i)
            },
            onDismiss = { showChapters = false },
        )
    }
}

/**
 * The chapter picker.
 *
 * In READING order (oldest first, the order the engine returned) rather than the
 * newest-first order the title page defaults to, and opened scrolled to the
 * chapter being read: the reader's own ◀ ▶ buttons walk this array, so the row
 * under the current one has to be the NEXT chapter — a list that disagreed with
 * the buttons would be a trap. The search box is what makes a 300-chapter series
 * navigable at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterSheet(
    chapters: List<MangaChapter>,
    currentUrl: String,
    query: String,
    onQuery: (String) -> Unit,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentIndex = chapters.indexOfFirst { it.url == currentUrl }
    // Indices into [chapters] rather than a copied list: a tapped row has to
    // report the chapter's position in the READING order (the array the ◀ ▶
    // buttons walk), and a filtered copy loses that. Built once per query rather
    // than once per row — the row-level version of this is a scan of the whole
    // list for every row on screen.
    val shownIndices = remember(chapters, query) {
        chapters.indices.filter { i ->
            val c = chapters[i]
            query.isBlank() ||
                c.label.contains(query, ignoreCase = true) ||
                (c.scanlator?.contains(query, ignoreCase = true) == true)
        }
    }
    // Opens on the chapter being read (a long list would otherwise always start
    // at chapter 1), which is also where the user's eye lands first.
    val state = rememberLazyListState(
        initialFirstVisibleItemIndex = if (query.isBlank() && currentIndex > 0) currentIndex else 0,
    )
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tr("Chapters"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (currentIndex >= 0) "${currentIndex + 1} / ${chapters.size}"
                    else chapters.size.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            GlassSearchField(
                value = query,
                onValueChange = onQuery,
                placeholder = I18n.t("Search %s chapters…").replace("%s", chapters.size.toString()),
                height = 46.dp,
            )
            Spacer(Modifier.height(6.dp))
            if (shownIndices.isEmpty()) {
                Text(
                    tr("No chapter matches that."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
            LazyColumn(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    // Tall enough for a real list, short enough that the sheet
                    // never becomes the whole screen (the page behind it is the
                    // thing being navigated).
                    .heightIn(max = 440.dp),
            ) {
                // Keyed by position, not by url: a source can list the same
                // chapter twice (a site mid-rebuild publishes the same link under
                // two names), and a repeated Lazy key is a hard crash in Compose,
                // not a warning. The detail screen's own list keys on the url —
                // this one is the defensive half.
                items(shownIndices, key = { it }) { i ->
                    val c = chapters[i]
                    val isCurrent = c.url == currentUrl
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(i) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (isCurrent) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                                )
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                c.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val sub = listOfNotNull(
                                c.scanlator?.takeIf { it.isNotBlank() },
                                if (c.dateUpload > 0L) {
                                    java.text.SimpleDateFormat(
                                        "d MMM yyyy", java.util.Locale.getDefault()
                                    ).format(java.util.Date(c.dateUpload))
                                } else null,
                            ).joinToString("  ·  ")
                            if (sub.isNotBlank()) {
                                Text(
                                    sub,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (isCurrent) {
                            Text(
                                tr("Reading"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
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
 *
 * The pager is MOVED only by [request], and its own position is only ever
 * REPORTED back. The effect that used to make the pager follow the reported page
 * is what broke the page bar: as the pager reported the very move it had been
 * asked to make, that effect restarted, cancelled the scroll it was carrying out
 * and the pager snapped back to the page the reader had started on.
 */
@Composable
private fun PagedBody(
    pages: List<StreamSource>,
    chapterUrl: String,
    fit: String,
    reverse: Boolean,
    request: ScrollRequest?,
    onReport: (String, Int) -> Unit,
    onTap: () -> Unit,
    onStep: (Int) -> Unit,
    enhance: Boolean = false,
) {
    val pager = rememberPagerState(pageCount = { pages.size })
    val mover = remember { ReaderMover() }

    // ONE collector carries out every move for as long as this body lives, so a
    // new request can never cancel the scroll the previous one started. The
    // bounds come from the pager itself, not from a captured list: a chapter
    // change replaces the list under this effect's feet.
    LaunchedEffect(Unit) {
        mover.run { target -> if (target in 0 until pager.pageCount) pager.scrollToPage(target) }
    }
    LaunchedEffect(request?.seq) {
        request?.let { mover.request(it.page) }
    }
    // The pager's own position is the report (a finger swipe, or a move we just
    // made — either way it is where the reader is).
    LaunchedEffect(pager, chapterUrl) {
        snapshotFlow { pager.currentPage }.collect { onReport(chapterUrl, it) }
    }

    Box(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            reverseLayout = reverse,
            beyondViewportPageCount = 1,
        ) { i ->
            PageImage(
                source = pages[i],
                fit = fit,
                enhance = enhance,
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
 * Continuous vertical reading, ACROSS chapters.
 *
 * [entries] is the run: a chapter's title card followed by its pages, for as many
 * chapters as the reader has walked into (see the run in [MangaReaderScreen]).
 * Each page is drawn at the full width and its real aspect ratio — known BEFORE
 * it is drawn, because the loader reads the page's size out of the file header —
 * so the strip is the right height from the first frame and never shifts under
 * the reader's thumb as pages land.
 *
 * The strip is scrolled only by [request], and the item under the viewport is
 * reported back: that report is what decides which chapter and page the reader is
 * on, what gets saved as progress, and where the preloading window sits.
 */
@Composable
private fun WebtoonRunBody(
    entries: List<RunItem>,
    listState: LazyListState,
    request: ScrollRequest?,
    itemIndexOf: (ScrollRequest) -> Int,
    onReport: (String, Int) -> Unit,
    onTap: () -> Unit,
    enhance: Boolean = false,
) {
    val mover = remember { ReaderMover() }
    LaunchedEffect(Unit) {
        mover.run { target -> if (target >= 0) listState.scrollToItem(target) }
    }
    LaunchedEffect(request?.seq) {
        request?.let { mover.request(itemIndexOf(it)) }
    }
    // One report per item the viewport passes, which is also when the preload
    // window is recomputed (see the reader's report()).
    LaunchedEffect(listState, entries.size) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { i -> entries.getOrNull(i)?.let { onReport(it.chapterUrl, it.page) } }
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
            // Keyed by the item's own identity (chapter + page), never by its
            // position: the run is appended to and prepended to while the reader
            // scrolls, and the keys are what keep the page under the eye exactly
            // where it is when the strip grows at either end.
            items(entries, key = { it.key }) { item ->
                when (item) {
                    is RunItem.Head -> ChapterCard(item.label)
                    is RunItem.Page -> {
                        val state = rememberPageState(item.source)
                        val ratio = (state as? MangaPageState.Ready)?.ratio ?: 0f
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .then(
                                    if (ratio > 0f) Modifier.aspectRatio(ratio)
                                    else Modifier.height(420.dp)
                                )
                        ) {
                            PageContent(
                                state = state,
                                source = item.source,
                                // The strip is the full-width shape: the page
                                // is drawn at its real height inside a box of
                                // its own aspect ratio (known from the file
                                // header before it is drawn), and the strip
                                // scrolls as one column.
                                fitWidth = true,
                                enhance = enhance,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The card drawn where a chapter begins in the continuous strip.
 *
 * This is the user's own request: "when chapter 2 starting it shows chapter 2 so
 * the user knows chapter 2 started". In a strip with no page breaks a chapter
 * boundary is otherwise invisible — the pages simply flow on, and there is no way
 * to tell that what you are reading belongs to the next chapter.
 */
@Composable
private fun ChapterCard(label: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 26.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label.ifBlank { tr("Next chapter") },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.88f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(
            color = Color.White.copy(alpha = 0.18f),
            modifier = Modifier.padding(horizontal = 40.dp),
        )
    }
}

/**
 * One page image in a paged mode.
 *
 * [MangaFit.HEIGHT] (and [MangaFit.WHOLE], which differs only on a screen wider
 * than the page) draw the whole page inside the viewport. [MangaFit.WIDTH] is the
 * reading position for a tall page on a phone: full width, scrolled vertically —
 * which is why the page is laid out at its true aspect ratio, read from the file
 * the loader already fetched.
 *
 * The page itself is drawn by [PageContent] — one bitmap, scaled by the
 * platform — so the two shapes only differ in the BOX they give it and in how
 * that bitmap is scaled into it: `MangaFit.WIDTH` lays the page out at its real
 * height inside a vertical scroll, and the fit modes give it the viewport.
 *
 * A page TALLER than 3× its width is the exception, and it is a deliberate one:
 * it is not a bitmap at all but a strip, drawn region-by-region by
 * [NekoPageView] (see [PageBitmaps.TALL_RATIO]). The box is the same either way
 * — the page's own aspect ratio — which is what keeps "the strip scrolls at the
 * right speed" true for both shapes.
 */
@Composable
private fun PageImage(
    source: StreamSource,
    fit: String,
    enhance: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val state = rememberPageState(source)
    val ratio = (state as? MangaPageState.Ready)?.ratio ?: 0f
    val scroll = rememberScrollState()
    val fullWidth = fit == MangaFit.WIDTH
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        if (fullWidth) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .then(
                            if (ratio > 0f) Modifier.aspectRatio(ratio)
                            else Modifier.height(520.dp)
                        )
                ) {
                    PageContent(
                        state = state,
                        source = source,
                        fitWidth = true,
                        enhance = enhance,
                    )
                }
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                PageContent(
                    state = state,
                    source = source,
                    fitWidth = false,
                    enhance = enhance,
                )
            }
        }
    }
}

/**
 * The state of one page, wired to [MangaPageLoader].
 *
 * The preloader fetches the pages around the reader, but a page can also be
 * reached without being planned (the first frame of a chapter, a chapter whose
 * neighbours have no pages) — so the composable asks for its own page too. The
 * call is idempotent: a page already on disk or in flight is left alone.
 */
@Composable
private fun rememberPageState(source: StreamSource): MangaPageState {
    val flow = remember(source.url) { MangaPageLoader.state(source.url) }
    val state by flow.collectAsState()
    LaunchedEffect(source.url) { MangaPageLoader.load(source.url, source.headers) }
    return state
}

/**
 * The reader's "Enhance" look as a Compose colour filter — [mangaEnhanceMatrix]
 * in the manga package is where the numbers live, and this is the one place they
 * are turned into a filter.
 *
 * It is applied to the PAGE (see [PageContent]) and to whatever chrome images
 * are drawn over it; one filter, one look, so a page does not change colour when
 * the chrome is drawn on top of it.
 */
internal val mangaEnhanceFilter: ColorFilter by lazy {
    ColorFilter.colorMatrix(ColorMatrix(mangaEnhanceMatrix()))
}

/**
 * The page itself, drawn with the fit the MODE asked for — a spinner while it is
 * coming, or, once the loader has spent all ten attempts, a row that says so and
 * offers one more try on THAT page alone, so a single bad page never costs the
 * reader the whole chapter.
 *
 * There are exactly TWO drawing shapes, and which one a page gets is decided by
 * its own proportions (see [PageBitmaps.TALL_RATIO]):
 *
 *  * **h ≤ 3w** — the ordinary page and the ordinary manhwa page. One bitmap
 *    from [PageBitmaps], drawn by an ordinary Compose `Image`, the same thing
 *    every poster and cover in this app is drawn with. One bitmap is provably
 *    safe at this shape (it cannot be taller than 3 × the screen's width), and
 *    [PageBitmaps] says why that bound is the whole argument.
 *  * **h > 3w** — a webtoon strip. Drawn by [NekoPageView], the ported
 *    subsampling reader, which region-decodes the file and never builds a
 *    bitmap the height of the page. This is the shape that used to come out as
 *    displaced blocks, because a page that tall cannot be one texture.
 *
 * [fitWidth] is a property of the MODE, not of the page: the webtoon strip and
 * `MangaFit.WIDTH` draw the page at its full width (at its real height, inside a
 * vertical scroll), while the modes that scale the whole page into the viewport
 * draw it fitted. The container decides that, because the container is what knows
 * how much room the page was given — and the page's own size is known before it is
 * drawn (the loader reads it from the file header), which is what keeps a strip
 * from shifting under the reader's thumb as pages land.
 */
@Composable
private fun PageContent(
    state: MangaPageState,
    source: StreamSource,
    fitWidth: Boolean,
    enhance: Boolean = false,
) {
    when (state) {
        is MangaPageState.Ready -> {
            if (PageBitmaps.isTallPage(state.width, state.height)) {
                // A STRIP (h > 3w): drawn by the ported subsampling reader, not
                // by a bitmap. Nothing here decodes the page at all — the view
                // region-decodes the file it is given, which is the only shape
                // that survives a webtoon's height (see [NekoPageView], and
                // [PageBitmaps.TALL_RATIO] for why the rule is 3×).
                //
                // The box around this is already the page's own aspect ratio, so
                // the strip occupies exactly the space it should and the list
                // scrolls through it; the view ignores touch, so the list keeps
                // every gesture.
                NekoPageView(
                    file = state.file,
                    sourceUrl = source.url,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
            // Keyed on the file's own (unique-per-fetch) path, so a retry that
            // lands a good page decodes THAT file and nothing else changes. The
            // first value is the cache peek: a page that is already decoded (it
            // is one the preloader warmed, or one the reader has come back to) is
            // drawn on the frame it is composed on, with no spinner at all.
            val cached = remember(state.file.absolutePath) { PageBitmaps.cached(state.file) }
            val bitmap by produceState<Bitmap?>(initialValue = cached, state.file.absolutePath) {
                val decoded = PageBitmaps.page(state.file, state.width, state.height)
                if (decoded == null) {
                    // A file the platform cannot decode is a file the fetch's
                    // own checks could not catch (a JPEG can pass every one of
                    // them with damaged scan data). Reported back to the loader,
                    // which drops it and turns this page into a Failed row with
                    // a retry button — rather than a spinner that never ends.
                    MangaPageLoader.markUndecodable(source.url)
                }
                value = decoded
            }
            val page = bitmap
            Box(Modifier.fillMaxSize()) {
                if (page != null) {
                    Image(
                        bitmap = page.asImageBitmap(),
                        // Decorative: the page is the content, and a screen
                        // reader announcing "image" over artwork is noise. The
                        // reader's own bars carry the page position.
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        // The strip and `MangaFit.WIDTH` fill the box's width and
                        // take the height their own aspect ratio gives them (the
                        // container has already sized the box to it); the other
                        // fits show the whole page inside the viewport.
                        contentScale = if (fitWidth) ContentScale.FillWidth else ContentScale.Fit,
                        alignment = if (fitWidth) Alignment.TopCenter else Alignment.Center,
                        colorFilter = if (enhance) mangaEnhanceFilter else null,
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = Color.White)
                    }
                }
            }
            }
        }
        is MangaPageState.Failed -> Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF141414)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(30.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                tr("This page did not load"),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                I18n.t("%s tries did not get it").replace("%s", state.attempts.toString()),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { MangaPageLoader.retry(source.url, source.headers) }) {
                Text(tr("Retry"))
            }
        }
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp), color = Color.White)
        }
    }
}

@Composable
private fun ReaderTopBar(
    onBack: () -> Unit,
    title: String,
    onSettings: () -> Unit,
    /** Opens the chapter picker. Null while the chapter list is unknown or
     *  holds a single chapter — a button that can only show one row is noise. */
    onChapters: (() -> Unit)? = null,
    chapterCount: Int = 0,
    /** Opens the engine's own site in the verification WebView — the reader's
     *  own way past a Cloudflare check that sits in front of a CHAPTER rather
     *  than the catalog (the user's report: "some manga site also put
     *  verification on chapter loading page, so add there a webview"). */
    onVerify: (() -> Unit)? = null,
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
            if (onChapters != null) {
                IconButton(onClick = onChapters) {
                    Icon(
                        Icons.Filled.List,
                        // The count travels in the description, so a screen
                        // reader says "Chapters, 184" rather than "List".
                        contentDescription = if (chapterCount > 0)
                            I18n.t("Chapters (%s)").replace("%s", chapterCount.toString())
                        else tr("Chapters"),
                        tint = Color.White,
                    )
                }
            }
            if (onVerify != null) {
                IconButton(onClick = onVerify) {
                    Icon(
                        Icons.Filled.Public,
                        contentDescription = tr("Open the site to pass its Cloudflare check"),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
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
                // A point per page, not a slider: with the dots visible the bar
                // says WHERE in the chapter a page is, and a tap or a drag lands
                // on the exact page rather than on an interpolation of it.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "1",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                    ReaderScrubber(
                        page = page,
                        pageCount = pageCount,
                        onPage = onPage,
                        onColor = Color.White,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    )
                    Text(
                        pageCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }
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

/**
 * The page bar: one dot per page of the chapter, the current one highlighted,
 * with a drag anywhere on it jumping to the page under the finger.
 *
 * Why not a [Slider]: a slider's thumb has no relationship to a PAGE — on a
 * 184-page chapter it is a 5-pixel step, so the readout is the only thing that
 * says where you are, and letting go lands on whatever page the fraction
 * happened to round to. A dot per page makes the chapter's own structure
 * visible (short chapters read as a sparse row, long ones as a dense one), and
 * both a tap and a drag resolve to the nearest page deterministically.
 *
 * The dots are drawn rather than laid out, because a 900-page chapter would be
 * 900 composables otherwise. Their radius shrinks as the count grows so they
 * stay separate on a phone's width, and stops at a hairline — past that point
 * the strip reads as a dotted track, which is still exactly what it is.
 */
@Composable
private fun ReaderScrubber(
    page: Int,
    pageCount: Int,
    onPage: (Int) -> Unit,
    onColor: Color,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val dotMin = with(density) { 1.1.dp.toPx() }
    val dotMax = with(density) { 3.4.dp.toPx() }
    val activeRadius = with(density) { 5.6.dp.toPx() }
    Box(
        modifier
            .fillMaxWidth()
            // A finger's worth of height: the dots are 7px, but the touch target
            // has to be something a person can hit without looking.
            .height(30.dp)
            .pointerInput(pageCount) {
                detectTapGestures { offset ->
                    onPage(pageAt(offset.x, size.width, pageCount))
                }
            }
            .pointerInput(pageCount) {
                // A drag scrubs. Separate from the tap detector above because a
                // pointer-input block runs one suspend loop each; this is the
                // standard pairing and the drag wins as soon as the finger moves.
                detectHorizontalDragGestures(
                    onDragStart = { offset -> onPage(pageAt(offset.x, size.width, pageCount)) },
                ) { change, _ ->
                    onPage(pageAt(change.position.x, size.width, pageCount))
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val cy = size.height / 2f
            if (w <= 0f) return@Canvas
            if (pageCount <= 1) return@Canvas
            val step = w / (pageCount - 1).toFloat()
            val radius = (step / 2.6f).coerceIn(dotMin, dotMax)
            // The track behind the dots: what makes a dense strip read as a bar
            // with points on it rather than as noise.
            drawLine(
                color = onColor.copy(alpha = 0.22f),
                start = androidx.compose.ui.geometry.Offset(0f, cy),
                end = androidx.compose.ui.geometry.Offset(w, cy),
                strokeWidth = 1.5.dp.toPx(),
            )
            for (i in 0 until pageCount) {
                val isCurrent = i == page
                drawCircle(
                    color = if (isCurrent) accent else onColor.copy(alpha = 0.45f),
                    radius = if (isCurrent) activeRadius else radius,
                    center = androidx.compose.ui.geometry.Offset(i * step, cy),
                )
            }
        }
    }
}

/** The page a touch at [x] means: the nearest of the [count] evenly spaced
 *  points, clamped. Shared by the tap and the drag so both can never disagree. */
private fun pageAt(x: Float, width: Int, count: Int): Int {
    if (count <= 1 || width <= 0) return 0
    val fraction = (x / width).coerceIn(0f, 1f)
    return (fraction * (count - 1)).roundToInt().coerceIn(0, count - 1)
}

/** The reader's settings sheet: the shapes of a chapter, the page furniture
 *  around it, and the two switches that change how the artwork is DRAWN.
 *
 *  The sheet's content SCROLLS, and that is not a nicety: the settings are
 *  grouped (direction, fit, background, while-reading) and on a short screen
 *  the last group did not fit. A `Column` inside a `ModalBottomSheet` that
 *  overflows is CLIPPED — it does not scroll by itself — so the last row was
 *  cut in half at the sheet's own edge, and a half-drawn `Switch` sitting under
 *  another row's switch is exactly what the user reported as "an extra toggle
 *  near Keep screen on". Nothing was ever duplicated; the row below it was
 *  sliced by the sheet's bottom edge. Scrolling the content is the fix, and it
 *  keeps every option (including any added later) reachable on any screen. */
@Composable
private fun ReaderSettings(
    mode: String,
    fit: String,
    bgKey: String,
    keepAwake: Boolean,
    showNumber: Boolean,
    enhance: Boolean,
    onMode: (String) -> Unit,
    onFit: (String) -> Unit,
    onBg: (String) -> Unit,
    onAwake: (Boolean) -> Unit,
    onNumber: (Boolean) -> Unit,
    onEnhance: (Boolean) -> Unit,
) {
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
    ) {
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
        SettingSwitch(tr("Enhance images"), enhance, onEnhance)
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

// ---------------------------------------------------------------------------
// The reader's own plumbing: how a move is asked for, and how a webtoon's
// chapters are strung together.
// ---------------------------------------------------------------------------

/**
 * A request to move the reading surface to page [page] of [chapterUrl].
 *
 * [seq] is what makes two requests to the same page distinguishable: the body
 * carries out a request once per [seq], so re-publishing the same viewport (a
 * recomposition, the chapter being reloaded) can never be mistaken for a new
 * instruction — and, in the other direction, asking twice for the page you are
 * already on still works (which a value comparison could never express).
 */
private data class ScrollRequest(val seq: Int, val chapterUrl: String, val page: Int)

/**
 * The single lane every request to move the reading surface travels down.
 *
 * The channel is CONFLATED — while one move is being carried out, later requests
 * replace each other, so dragging along the page bar crosses forty pages and ends
 * on the one the finger stopped at instead of replaying all forty. Moves are
 * carried out one at a time by ONE collector, started with `LaunchedEffect(Unit)`
 * in the body that owns the scroll, which is what makes a move impossible to
 * cancel from outside: the old reader moved the surface from an effect keyed on
 * the state the move itself reported, so it cancelled its own scroll mid-flight
 * and the reader snapped back to where they started.
 */
private class ReaderMover {
    private val moves = Channel<Int>(Channel.CONFLATED)

    fun request(target: Int) {
        moves.trySend(target)
    }

    /** Runs for the lifetime of the body. [move] is the body's own way of
     *  moving — `pager.scrollToPage` or `listState.scrollToItem` — and is called
     *  on the main thread, which is where it must be called from. */
    suspend fun run(move: suspend (Int) -> Unit) {
        for (target in moves) {
            runCatching { move(target) }
        }
    }
}

/** One chapter inside the continuous webtoon strip. */
private data class RunBlock(
    val chapterUrl: String,
    val label: String,
    val pages: List<StreamSource>,
)

/**
 * One drawn thing in that strip: a chapter's title card, or one of its pages.
 *
 * [key] is the item's own identity and is what the LazyColumn positions itself
 * by, so appending the next chapter (below the viewport) or prepending the
 * previous one (above it) leaves the page under the reader's eye exactly where it
 * was.
 */
private sealed class RunItem(open val chapterUrl: String, open val page: Int) {
    class Head(override val chapterUrl: String, val label: String) : RunItem(chapterUrl, 0)
    class Page(
        override val chapterUrl: String,
        override val page: Int,
        val source: StreamSource,
    ) : RunItem(chapterUrl, page)

    val key: String
        get() = when (this) {
            is Head -> "$chapterUrl#head"
            is Page -> "$chapterUrl#$page"
        }
}

private fun flattenRun(run: List<RunBlock>): List<RunItem> = buildList {
    for (block in run) {
        add(RunItem.Head(block.chapterUrl, block.label))
        block.pages.forEachIndexed { i, source -> add(RunItem.Page(block.chapterUrl, i, source)) }
    }
}

/** The chapter [delta] away from [url] in the de-duplicated reading order. */
private fun neighbourOf(
    url: String,
    delta: Int,
    nav: List<MangaChapter>,
    all: List<MangaChapter>,
): String? {
    val at = navIndexOf(nav, url, all)
    if (at < 0) return null
    return nav.getOrNull(at + delta)?.url
}

/** Where [url] sits in [nav] — by url, or by the chapter NUMBER when the url
 *  belongs to a different release of the same chapter. */
private fun navIndexOf(nav: List<MangaChapter>, url: String, all: List<MangaChapter>): Int {
    nav.indexOfFirst { it.url == url }.let { if (it >= 0) return it }
    val no = all.firstOrNull { it.url == url }?.let { chapterNo(it) } ?: return -1
    if (no < 0f) return -1
    return nav.indexOfFirst { chapterNo(it) == no }
}

/**
 * The number a chapter is known by: the source's own `chapter_number` when it has
 * one, otherwise read out of its name — plenty of sources leave the number unset
 * and put everything in the title ("Chapter 12.5", "Ch. 12", "Vol. 3 Ch. 12").
 * A name with a volume number in it is why "ch" is looked for FIRST: "Vol. 3 Ch.
 * 12" must be chapter 12, not chapter 3, or two different chapters would be
 * treated as the same one. -1 when no number can be found at all.
 */
private fun chapterNo(c: MangaChapter): Float {
    if (c.number > 0f) return c.number
    val name = c.name
    if (name.isBlank()) return -1f
    val match = Regex("""(?i)\bch(?:apter|ap)?\.?\s*(\d+(?:\.\d+)?)""").find(name)
        ?: Regex("""(\d+(?:\.\d+)?)""").find(name)
        ?: return -1f
    return match.groupValues.getOrNull(1)?.toFloatOrNull() ?: -1f
}

/**
 * One entry per chapter NUMBER, in reading order — what the ◀ ▶ buttons walk.
 *
 * An aggregator lists the same chapter once per scanlation group (the chapter
 * sheet in the user's screenshot has "Chapter 1" five times in a row), so walking
 * the raw list stepped to the same chapter from the next group — "it again opens
 * chapter 1 instead of loading chapter 2". For each number the release by
 * [preferScanlator] wins when there is one, so stepping forward keeps the
 * translation the reader is already reading; otherwise the first release wins.
 * Chapters whose number cannot be read at all are kept as their own entries, so
 * nothing is ever merged by accident.
 */
private fun dedupeChapters(list: List<MangaChapter>, preferScanlator: String?): List<MangaChapter> {
    val group = preferScanlator?.takeIf { it.isNotBlank() }
    val slots = ArrayList<Pair<Float, MangaChapter>>(list.size)
    list.forEachIndexed { i, c ->
        val no = chapterNo(c)
        // A chapter with no readable number gets a key of its own — a distinct
        // negative — so it can never be merged into another one.
        val key = if (no >= 0f) no else -1f - i
        val at = slots.indexOfFirst { it.first == key }
        if (at < 0) {
            slots += key to c
            return@forEachIndexed
        }
        // A later release of the same number takes the slot only when it is by
        // the group the reader is reading; the entry is swapped IN PLACE, so the
        // list's order keeps matching the chapters' numbers.
        if (group != null) {
            val held = slots[at].second
            if (held.scanlator != group && c.scanlator == group) slots[at] = key to c
        }
    }
    return slots.map { it.second }
}
