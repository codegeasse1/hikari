package com.hikari.app.ui.screens

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.NavHostController
import coil.compose.LocalImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Dimension
import coil.size.Size
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.hikari.app.HikariApp
import com.hikari.app.data.Episode
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.StreamSource
import com.hikari.app.i18n.I18n
import com.hikari.app.i18n.tr
import com.hikari.app.manga.MangaChapter
import com.hikari.app.manga.MangaFit
import com.hikari.app.manga.MangaMark
import com.hikari.app.manga.MangaProgress
import com.hikari.app.manga.MangaProvider
import com.hikari.app.manga.MangaReadMode
import com.hikari.app.manga.MangaStore
import com.hikari.app.reader.ColorFilterMode
import com.hikari.app.reader.ReaderBg
import com.hikari.app.reader.ReaderChapter
import com.hikari.app.reader.ReaderFit
import com.hikari.app.reader.ReaderMode
import com.hikari.app.reader.ReaderOrientation
import com.hikari.app.reader.ReaderSettings
import com.hikari.app.reader.cache.WebtoonPageCache
import com.hikari.app.reader.coil.cropBorders
import com.hikari.app.reader.isWebtoon
import com.hikari.app.reader.source.HikariPageSource
import com.hikari.app.reader.source.MangaSource
import com.hikari.app.reader.ui.ChimahonPagerReader
import com.hikari.app.reader.ui.YomiReaderChrome
import com.hikari.app.reader.ui.YomiWebtoonReader
import com.hikari.app.tv.TvMode
import com.hikari.app.ui.components.VerificationNudge
import com.hikari.app.web.WebViewActivity
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.WEBTOON_MAX_DECODE_PIXELS
import eu.kanade.tachiyomi.ui.reader.viewer.budgetedShortWidth
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerConfig
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.readerPageDecodeDispatcher
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonConfig
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonTrailer
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The reader.
 *
 * A chapter arrives from the engine as a list of [StreamSource]s whose `url` is
 * one PAGE IMAGE and whose `headers` are the source's own request headers (many
 * manga CDNs reject a page fetched without the site's Referer). That is the whole
 * reason the manga provider reuses the video "stream" shape: the page list, the
 * per-source headers and the "one stream at a time" plumbing all already exist.
 *
 * The screen itself is the shell; the reading is done by Nekoread's reader,
 * ported whole:
 *
 *  * [YomiWebtoonReader] — the continuous-strip reader (a RecyclerView of
 *    subsampling page frames, chapter dividers between streamed chapters and a
 *    trailing item that reports the next chapter's loading/error/end state).
 *  * [ChimahonPagerReader] — the paged reader (a DirectionalViewPager of the
 *    same page frames) for left-to-right, right-to-left and vertical reading.
 *  * [WebtoonPageCache] — the on-device page cache both of them render from:
 *    every page's bytes are downloaded ONCE, through the source's own client
 *    (see [HikariPageSource]) which is what carries the headers a hotlink
 *    protected CDN insists on.
 *  * [YomiReaderChrome] — every reader option: the reading-mode / fit /
 *    orientation pickers, crop borders (per mode), tap zones and their
 *    inversion, side padding, page scale, zoom, page transitions, auto-scroll,
 *    the hide threshold, brightness, colour filter, grayscale/invert/enhance,
 *    quality, the background, the chapter list and the per-series override.
 *
 * Why the pages used to come out broken, and why they do not any more: the old
 * reader drew one ordinary `Image` per page from a bitmap it decoded itself, so
 * a page that failed to decode (or a URL that 403'd because the request carried
 * the app's headers and not the source's) left a torn or blank cell in the
 * strip — and its "fit" only ever changed how that one bitmap was scaled, which
 * is why the fit buttons looked like they did nothing. Every page here is a
 * file in [WebtoonPageCache], fetched by the source, region-decoded by
 * SubsamplingScaleImageView, and re-fetched/retried by the page holder itself —
 * so a page either has its own Retry row or it is drawn whole.
 *
 * Progress is written into [MangaStore] on every page change — debounced — and
 * again when the screen goes away, so the detail screen, the Manga tab and the
 * My Stuff shelf all agree where the reader stopped.
 */
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
    val activity = context.findActivity()
    val imageLoader = LocalImageLoader.current

    // ---- The chapter being read, and the chapter list it sits in ----
    var chapters by remember { mutableStateOf<List<MangaChapter>>(MangaStore.chaptersFor(key).orEmpty()) }
    var chapter by remember { mutableStateOf(chapterUrl) }

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
    val providerName = remember(providerId) { app.providers.byId(providerId)?.config?.name.orEmpty() }

    // The chapter list normally arrives cached (the detail screen fetched it);
    // opened straight from a "continue reading" card it may not be there yet.
    LaunchedEffect(key, chapters.isEmpty()) {
        if (chapters.isNotEmpty()) return@LaunchedEffect
        val p = app.providers.byId(providerId) as? MangaProvider ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { p.getEpisodes(item) } }
        chapters = MangaStore.chaptersFor(key).orEmpty()
    }

    // ---- The chapters the ◀ ▶ buttons walk ----
    //
    // One entry per chapter NUMBER, not one per release. An aggregator lists the
    // same chapter once per scanlation group — the chapter list in the user's
    // screenshot has "Chapter 1" five times in a row — so stepping to the next
    // LIST entry stepped to the same chapter from the next group ("it again
    // opens chapter 1 instead of loading chapter 2"). The buttons walk this list
    // instead; the reader's chapter sheet still shows every release.
    val currentScanlator = chapters.firstOrNull { it.url == chapter }?.scanlator
    val navChapters = remember(chapters, currentScanlator) {
        dedupeChapters(chapters, currentScanlator)
    }

    // ---- The reader's settings ----
    //
    // One JSON blob holds every option (see [ReaderSettings]); the older per-key
    // preferences are read once, the first time the reader is opened after the
    // port, so an existing install keeps the mode/backdrop/fit it had picked.
    var settings by remember { mutableStateOf(ReaderSettings.DEFAULTS) }
    // Per-series reading modes (yomi's "for this series" scope), as
    // `mangaKey -> ReaderMode name`. Presence of the key IS the override being
    // on, so one map carries both "enabled" and "which mode".
    var seriesModes by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(key) {
        val raw = app.store.mangaReaderSettingsJsonFlow().first()
        settings = if (raw.isNullOrBlank()) {
            ReaderSettings.fromLegacy(
                mode = legacyReaderMode(app.store.mangaReadModeFlow().first()),
                fit = legacyReaderFit(app.store.mangaFitFlow().first()),
                bg = legacyReaderBg(app.store.mangaReaderBgFlow().first()),
                keepAwake = app.store.mangaKeepAwakeFlow().first(),
                showPageNumber = app.store.mangaShowPageNumberFlow().first(),
                enhance = app.store.mangaEnhanceFlow().first(),
            )
        } else {
            ReaderSettings.fromJson(raw)
        }
        seriesModes = parseSeriesModes(app.store.mangaSeriesModesFlow().first())
    }

    /** Writes [next] through to the store (the reader changes settings from its
     *  own sheets, and they must survive the chapter/screen). */
    fun save(next: ReaderSettings) {
        settings = next
        scope.launch { app.store.setMangaReaderSettingsJson(next.toJson().toString()) }
    }

    /** Whether this manga is on the library shelf — the chrome's bookmark button,
     *  mirrored in state because [MangaStore] is not a flow this screen observes. */
    var followed by remember(key) { mutableStateOf(MangaStore.isFollowed(key)) }

    fun saveSeriesModes(next: Map<String, String>) {
        seriesModes = next
        val o = JSONObject()
        for ((k, v) in next) o.put(k, v)
        scope.launch { app.store.setMangaSeriesModesJson(o.toString()) }
    }

    // The mode in force: the per-series override when this manga has one,
    // otherwise the global one.
    val overrideMode = seriesModes[key]?.let { name ->
        ReaderMode.entries.firstOrNull { it.name == name }
    }
    val seriesOverrideEnabled = seriesModes.containsKey(key)
    val readerMode: ReaderMode = overrideMode ?: settings.mode
    val isWebtoon = readerMode.isWebtoon

    // ---- The pages of the chapter being read ----
    var pages by remember { mutableStateOf<List<StreamSource>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    // Bumped by the retry button.
    var reload by remember { mutableStateOf(0) }
    // The page the chapter opens on, decided when its pages arrive. Reset by the
    // chapter key, so a chapter picked from the list starts at its top.
    var openPage by remember(chapter) { mutableIntStateOf(0) }
    // The chapter whose previous neighbour should be prepended to the strip,
    // set when the reader was moved to a chapter from inside the reader (so
    // scrolling up returns to the chapter it came from) — null when the chapter
    // is the one the screen was opened on.
    var jumpSeedChapter by remember { mutableStateOf<String?>(null) }

    // The reader's own [MangaSource]: one page cache and one client for the whole
    // session, with the headers of every page the reader has seen.
    val pageSource = remember(providerId) { HikariPageSource(providerId, providerName) }
    val knownHeaders = remember { java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>() }

    /** The pages of [chapterUrl] as they are known right now. */
    fun labelOf(url: String): String =
        chapters.firstOrNull { it.url == url }?.label.orEmpty()

    suspend fun fetchPages(url: String): List<StreamSource> = withContext(Dispatchers.IO) {
        val p = app.providers.byId(providerId) as? MangaProvider ?: return@withContext emptyList()
        val idx = chapters.indexOfFirst { it.url == url }
        val ep = Episode(number = idx + 1, id = url, name = null, season = 1)
        val out = runCatching { p.getStreams(item, ep) }.getOrNull().orEmpty()
            .filter { it.url.isNotBlank() }
        // Every page is downloaded through the extension's OWN source (see
        // [HikariPageSource]): `getImage(page)` is what carries the extension's
        // imageRequest headers and its own interceptors, which is the whole
        // difference between a page that loads and one that comes back refused or
        // scrambled. Set here, before the viewers ask the cache for anything.
        pageSource.httpSource = p.httpSource()
        // Every page's own headers go into the one map the page cache reads, so a
        // page fetched by a viewer bind (not by this screen) still carries them —
        // the fallback path, for a source with no HttpSource behind it.
        for (s in out) knownHeaders[s.url] = s.headers
        pageSource.setHeaders(HashMap(knownHeaders))
        out
    }

    LaunchedEffect(providerId, chapter, reload) {
        loading = true
        error = null
        pages = emptyList()
        if (app.providers.byId(providerId) !is MangaProvider) {
            error = I18n.t("This manga engine is not installed.")
            loading = false
            return@LaunchedEffect
        }
        val out = fetchPages(chapter)
        // Where to start: the page saved for THIS chapter if the reader has been
        // here before, otherwise the top.
        val saved = MangaStore.progressFor(key)
        // Where to start: the page saved for THIS chapter if the reader has been
        // here before, otherwise the top. A chapter the reader was moved to from
        // inside the reader (`jumpSeedChapter`) always opens at its TOP — the
        // saved page belongs to the last time that chapter was read, and opening a
        // chapter the user just asked for at its first page is the whole point of
        // going there.
        val fromReaderJump = jumpSeedChapter == chapter
        val restored = if (!fromReaderJump && saved != null && saved.chapterUrl == chapter) {
            saved.page.coerceIn(0, (out.size - 1).coerceAtLeast(0))
        } else {
            0
        }
        // Set together, so the page surface is never composed with one of them
        // stale (the viewer positions itself at creation).
        pages = out
        openPage = restored
        if (out.isEmpty()) {
            error = MangaProvider.lastOutcome[providerId]
                ?: I18n.t("This chapter returned no pages. Tap to try again.")
        }
        loading = false
    }

    // ---- The continuous strip's stream ----
    //
    // In webtoon mode the pages on screen are a RUN of chapters, not one. A
    // webtoon's artwork has no chapter-sized break in it, so the strip must not
    // stop at one: reaching the end of a chapter fetches the next and appends
    // it, and a jump from inside the reader prepends the chapter it came from so
    // scrolling up returns to it. A title card is drawn where each chapter
    // begins. Paged modes keep a single chapter, so the run stays empty.
    //
    // The queue holds the chapters (id + label, what the chrome's chapter list
    // needs) and the segments hold their [StreamSource]s — from which the
    // descriptor lists the viewers take are derived, so the source headers stay
    // available in one place.
    var streamQueue by remember(chapter) { mutableStateOf<List<ReaderChapter>>(emptyList()) }
    var streamSegments by remember(chapter) { mutableStateOf<List<List<StreamSource>>>(emptyList()) }
    var loadingNext by remember(chapter) { mutableStateOf(false) }
    var nextError by remember(chapter) { mutableStateOf<String?>(null) }

    val pageDescriptors = remember(pages) {
        pages.map { MangaSource.PageDescriptor(pageUrl = it.pageUrl, imageUrl = it.url) }
    }
    val segmentDescriptors = remember(streamSegments) {
        streamSegments.map { seg -> seg.map { MangaSource.PageDescriptor(pageUrl = it.pageUrl, imageUrl = it.url) } }
    }

    // ---- Where the reader is ----
    //
    // The viewers report their own position; these are the readouts the chrome,
    // the scrubber and the saved progress are built from. They are
    // [derivedStateOf] so the values are read where they are USED (inside the
    // chrome's own scope) rather than at this call site, which would recompose
    // the whole reader once per page turn.
    var viewerPos by remember(chapter) { mutableStateOf(Triple(0, 1, 1)) }
    var pagerPos by remember(chapter) { mutableIntStateOf(1) }
    var nearEnd by remember(chapter) { mutableStateOf(false) }
    var nearStart by remember(chapter) { mutableStateOf(false) }
    // Previous chapters already asked for, so a source that refuses one cannot be
    // asked for it again and again while the user sits at the top.
    var prependTried by remember(chapter) { mutableStateOf<Set<String>>(emptySet()) }
    var userScrolling by remember { mutableStateOf(false) }
    val viewerRef = remember(chapter) { mutableStateOf<WebtoonViewer?>(null) }
    val pagerRef = remember(chapter) { mutableStateOf<PagerViewer?>(null) }

    val prevForStream = remember(navChapters, chapter) {
        neighbourOf(chapter, -1, navChapters, chapters)
    }

    LaunchedEffect(pages, chapter, isWebtoon) {
        if (!isWebtoon) return@LaunchedEffect
        if (pages.isEmpty()) return@LaunchedEffect
        val prev = if (jumpSeedChapter == chapter) prevForStream else null
        if (prev != null) {
            val prevPages = fetchPages(prev)
            if (prevPages.isNotEmpty()) {
                streamQueue = listOf(
                    ReaderChapter(prev, labelOf(prev)),
                    ReaderChapter(chapter, labelOf(chapter)),
                )
                streamSegments = listOf(prevPages, pages)
                // The reader is on the SECOND segment (the prepended chapter is
                // above it) and on the page it was told to open at. Seeding the
                // reported position here is what makes the chapter title, the page
                // counter and the ◀ ▶ neighbours right from the first frame
                // instead of for a moment reporting the chapter above.
                viewerPos = Triple(1, openPage + 1, pages.size)
                return@LaunchedEffect
            }
        }
        streamQueue = listOf(ReaderChapter(chapter, labelOf(chapter)))
        streamSegments = listOf(pages)
        viewerPos = Triple(0, openPage + 1, pages.size)
    }

    /** Appends the next chapter to the strip (auto-continue, or the trailer's
     *  Retry after a failed one). */
    fun loadNextIntoStream(nextUrl: String) {
        scope.launch {
            loadingNext = true
            nextError = null
            val list = fetchPages(nextUrl)
            if (list.isEmpty()) {
                nextError = MangaProvider.lastOutcome[providerId] ?: I18n.t("Couldn't load the next chapter.")
            } else if (streamQueue.none { it.id == nextUrl }) {
                streamQueue = streamQueue + ReaderChapter(nextUrl, labelOf(nextUrl))
                streamSegments = streamSegments + listOf(list)
            }
            loadingNext = false
        }
    }

    /** Streams the PREVIOUS chapter in above the strip — the mirror of
     *  [loadNextIntoStream], and what makes the strip readable upwards as well as
     *  down: with it the reader can scroll from the chapter it opened on all the
     *  way back to chapter 1, one chapter at a time, exactly as it already walks
     *  forward to the last one. The viewer holds the reader's place while the list
     *  grows at the head (see WebtoonViewer.setItems). */
    fun prependIntoStream(prevUrl: String) {
        scope.launch {
            val list = fetchPages(prevUrl)
            if (list.isNotEmpty() && streamQueue.none { it.id == prevUrl }) {
                streamQueue = listOf(ReaderChapter(prevUrl, labelOf(prevUrl))) + streamQueue
                streamSegments = listOf(list) + streamSegments
            }
        }
    }

    val streamPosition by remember(isWebtoon, chapter) {
        derivedStateOf {
            if (isWebtoon) viewerPos else Triple(0, pagerPos, pages.size)
        }
    }
    val activeChapterUrl by remember(isWebtoon, chapter) {
        derivedStateOf {
            if (isWebtoon) streamQueue.getOrNull(streamPosition.first)?.id ?: chapter else chapter
        }
    }
    val currentPage by remember(isWebtoon, chapter) {
        derivedStateOf {
            if (isWebtoon) streamPosition.second else pagerPos.coerceIn(1, pages.size.coerceAtLeast(1))
        }
    }
    val pageTotal by remember(isWebtoon, chapter) {
        derivedStateOf { if (isWebtoon) streamPosition.third else pages.size }
    }

    // The global adapter position the webtoon viewer opens at. The current
    // chapter sits after the prepended one's divider and pages, so its first
    // page is one divider plus the previous segment's page count in.
    val webtoonInitialPos = remember(streamQueue, streamSegments, openPage, chapter) {
        val segIdx = streamQueue.indexOfFirst { it.id == chapter }.coerceAtLeast(0)
        streamSegments.take(segIdx).sumOf { it.size } + segIdx + openPage
    }

    // ---- Progress ----
    //
    // Saved from a `snapshotFlow` rather than a LaunchedEffect keyed on the
    // page: a key is evaluated in the composition's own scope, so keying on the
    // page number made every page turn invalidate this whole screen.
    // `collectLatest` + the delay debounces it, so flipping through ten pages
    // writes once, at the tenth.
    fun saveProgress(p: Int, url: String) {
        val list = if (url == chapter) {
            pages
        } else {
            streamSegments.getOrNull(streamQueue.indexOfFirst { it.id == url }).orEmpty()
        }
        if (list.isEmpty()) return
        MangaStore.setProgress(
            MangaProgress(
                mangaKey = key,
                providerId = providerId,
                providerName = providerName,
                mangaUrl = mangaUrl,
                title = title,
                posterUrl = posterUrl.takeIf { it.isNotBlank() },
                chapterUrl = url,
                chapterName = labelOf(url),
                page = p.coerceIn(0, list.size - 1),
                pages = list.size,
                at = System.currentTimeMillis(),
            )
        )
    }

    LaunchedEffect(activeChapterUrl, pages) {
        snapshotFlow { currentPage }.collectLatest { page ->
            if (page <= 0) return@collectLatest
            delay(700)
            runCatching { saveProgress(page - 1, activeChapterUrl) }
        }
    }
    // Leaving the reader records the page immediately (the debounce above would
    // otherwise be cancelled by the disposal). Everything the write needs is
    // re-captured on every recomposition ([rememberUpdatedState]), because the
    // position states are `remember`ed per chapter — a closure captured once at
    // the first composition would still be holding the chapter the reader was
    // opened on.
    val disposeSave = rememberUpdatedState<() -> Unit> {
        val url = if (isWebtoon) activeChapterUrl else chapter
        saveProgress(currentPage - 1, url)
    }
    DisposableEffect(Unit) {
        onDispose { runCatching { disposeSave.value() } }
    }

    // ---- Reading statistics (the Stats page) ------------------------------
    //
    // The reader feeds the same store the video player does: one "chapter
    // consumed" the first time a chapter is opened in this session, and the
    // wall-clock time the reader was actually on screen. Counting a chapter
    // once per session rather than per position change matters in webtoon
    // mode, where scrolling through the strip walks the reader across several
    // chapters and back again.
    val countedChapters = remember { mutableSetOf<String>() }
    LaunchedEffect(activeChapterUrl) {
        val url = activeChapterUrl
        if (url.isBlank() || !countedChapters.add(url)) return@LaunchedEffect
        runCatching {
            app.store.recordChapterRead(
                "manga:$key",
                title.ifBlank { mangaUrl },
                posterUrl.takeIf { it.isNotBlank() },
            )
        }
    }
    // The clock only counts while the activity is RESUMED: a reader left open
    // in the background is not being read, and counting it would turn "time
    // spent" into "time the app was open".
    LaunchedEffect(activeChapterUrl) {
        var last = System.currentTimeMillis()
        while (true) {
            delay(20_000L)
            val now = System.currentTimeMillis()
            val seconds = (now - last) / 1000L
            last = now
            if (seconds <= 0L) continue
            // The activity is an Activity (not a LifecycleOwner by type), so the
            // state is asked for through the interface it also implements.
            val owner = activity as? androidx.lifecycle.LifecycleOwner
            val resumed = owner == null || owner.lifecycle.currentState
                .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
            if (!resumed) continue
            runCatching {
                app.store.recordWatchSeconds(
                    seconds,
                    "manga:$key",
                    title.ifBlank { mangaUrl },
                    posterUrl.takeIf { it.isNotBlank() },
                    com.hikari.app.data.WatchStats.KIND_MANGA,
                )
            }
        }
    }

    // ---- Navigation ---- (chapter jumps and page seeks)
    fun openChapter(url: String) {
        // Compared against the chapter the VIEWER is in, not the one the screen was
        // seeded with: after a jump into a chapter the strip already held, those two
        // differ, and guarding on the seed made the button a no-op exactly when the
        // user was trying to step back over a chapter they had just passed.
        if (url == activeChapterUrl) return
        // A chapter the strip already holds (a neighbour it pulled in) is jumped
        // to rather than rebuilt — its pages are right there, and its first page is
        // where a chapter change should land.
        if (streamQueue.any { it.id == url }) {
            val seg = streamQueue.indexOfFirst { it.id == url }
            if (seg >= 0) {
                val start = streamSegments.take(seg).sumOf { it.size } + seg
                nextError = null
                viewerRef.value?.moveToPage(start)
                return
            }
        }
        jumpSeedChapter = url
        chapter = url
    }

    val prevChapter = neighbourOf(activeChapterUrl, -1, navChapters, chapters)
    val nextChapter = neighbourOf(activeChapterUrl, +1, navChapters, chapters)
    val streamNextChapter = neighbourOf(streamQueue.lastOrNull()?.id ?: chapter, +1, navChapters, chapters)
    val streamPrevChapter = neighbourOf(streamQueue.firstOrNull()?.id ?: chapter, -1, navChapters, chapters)

    // When the reader nears the bottom of the strip, fetch and append the next
    // chapter. The viewer reports this: true while the last few pages of the
    // last streamed chapter are on screen.
    LaunchedEffect(nearEnd, streamQueue.size, loadingNext, nextError, isWebtoon) {
        if (!isWebtoon) return@LaunchedEffect
        if (!nearEnd) return@LaunchedEffect
        if (loadingNext || nextError != null) return@LaunchedEffect
        val next = streamNextChapter ?: return@LaunchedEffect
        if (streamQueue.none { it.id == next }) loadNextIntoStream(next)
    }

    // …and the same at the TOP of the strip: reaching the first pages of the first
    // streamed chapter streams the previous one in above the reader, so the strip
    // is continuous in BOTH directions and the reader can walk back to chapter 1
    // without ever leaving it. The flag is consumed here rather than relied on
    // again: the prepend shifts the reader into the second segment, so the viewer
    // reports "not at the start" as soon as the list has grown and the next
    // previous chapter is only asked for once the user really scrolls up again.
    LaunchedEffect(nearStart, streamQueue.size, prependTried, isWebtoon) {
        if (!isWebtoon) return@LaunchedEffect
        if (!nearStart) return@LaunchedEffect
        val prev = streamPrevChapter ?: return@LaunchedEffect
        if (streamQueue.any { it.id == prev }) return@LaunchedEffect
        if (prev in prependTried) return@LaunchedEffect
        nearStart = false
        prependTried = prependTried + prev
        prependIntoStream(prev)
    }

    // ---- The reader's own way past a Cloudflare check ----------------------
    //
    // The Manga tab has a globe per engine and every catalog header has one, but
    // a chapter can be behind its own verification page. The reader carries the
    // same escape hatch: the engine's own site is opened in [WebViewActivity],
    // the user passes whatever check the site wants, and the chapter is
    // re-fetched on the way back — between opening the view and closing it, the
    // answer the site gives an extension's request can go from a challenge to the
    // real page list.
    var showHud by remember { mutableStateOf(true) }
    val verifyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
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
                Toast.makeText(context, I18n.t("Couldn't determine this extension's site"), Toast.LENGTH_SHORT).show()
            } else {
                val host = runCatching { java.net.URI(site).host?.lowercase() }.getOrNull()
                verifyLauncher.launch(
                    Intent(context, WebViewActivity::class.java).apply {
                        putExtra("url", site)
                        putExtra("title", "Verify: " + (host ?: title))
                        putExtra("providerId", providerId)
                        putExtra("autoCloseWhenCloudflarePassed", true)
                        if (host != null) putExtra("verifyHost", host)
                    }
                )
            }
        }
    }

    // ---- Colours, decode sizes and the page cache ----
    val bgColor = Color(settings.bgArgb)
    val textColor = if (settings.lightBackdrop) Color.Black else Color.White

    // Grayscale / inverted / enhance combined into one colour matrix, applied by
    // the viewers at draw time — no re-decode, no lag.
    val pageMatrix = remember(settings.grayscale, settings.invertedColors, settings.imageEnhance) {
        if (!settings.grayscale && !settings.invertedColors && !settings.imageEnhance) {
            null
        } else {
            val m = android.graphics.ColorMatrix()
            if (settings.grayscale) m.setSaturation(0f)
            if (settings.invertedColors) {
                m.postConcat(
                    android.graphics.ColorMatrix(
                        floatArrayOf(
                            -1f, 0f, 0f, 0f, 255f,
                            0f, -1f, 0f, 0f, 255f,
                            0f, 0f, -1f, 0f, 255f,
                            0f, 0f, 0f, 1f, 0f,
                        ),
                    ),
                )
            }
            if (settings.imageEnhance) {
                val sat = android.graphics.ColorMatrix()
                sat.setSaturation(1.15f)
                m.postConcat(sat)
                val c = 1.15f
                val t = 0.5f * (1f - c) * 255f
                m.postConcat(
                    android.graphics.ColorMatrix(
                        floatArrayOf(
                            c, 0f, 0f, 0f, t,
                            0f, c, 0f, 0f, t,
                            0f, 0f, c, 0f, t,
                            0f, 0f, 0f, 1f, 0f,
                        ),
                    ),
                )
            }
            m
        }
    }
    val pageColorFilter = remember(pageMatrix) {
        pageMatrix?.let { android.graphics.ColorMatrixColorFilter(it) }
    }

    val screenWidthPx = context.resources.displayMetrics.widthPixels
    // Strips are always drawn at fill-width, so a strip decoded below the screen
    // width costs a little sharpness and saves a lot of memory. The reader's
    // "Image quality" setting picks the width (50/75/100%); the Low tier also
    // decodes as RGB_565 for heavy chapters.
    val webtoonDecodeWidth = (screenWidthPx * settings.readerQuality / 100f).roundToInt().coerceAtLeast(360)
    val useRgb565 = isWebtoon && settings.readerQuality == 50
    val displayDecodeWidth = if (isWebtoon) webtoonDecodeWidth else screenWidthPx
    val cacheDir = remember { File(context.cacheDir, "webtoon_pages") }

    // ---- Keep the screen awake, orientation lock, brightness ----
    val chromeBrightness = settings.customBrightness
    DisposableEffect(settings.keepScreenOn, activity) {
        val window = activity?.window
        if (settings.keepScreenOn && window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    DisposableEffect(chromeBrightness, settings.customBrightnessValue, activity) {
        val window = activity?.window
        if (chromeBrightness && settings.customBrightnessValue > 0 && window != null) {
            val lp = window.attributes
            lp.screenBrightness = (settings.customBrightnessValue / 100f).coerceIn(0f, 1f)
            window.attributes = lp
        }
        onDispose {
            if (window != null) {
                val lp = window.attributes
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = lp
            }
        }
    }
    DisposableEffect(settings.orientation, activity) {
        val requested = when (settings.orientation) {
            ReaderOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            ReaderOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            ReaderOrientation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        activity?.requestedOrientation = requested
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    // The system bars get out of the way while reading and come back with the
    // chrome, so a page is never drawn under a status bar.
    DisposableEffect(showHud, activity) {
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (controller != null) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (showHud) {
                controller.show(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // The hardware/edge back closes the chrome first, the way every reader does —
    // a bar over the page is a mode, not a destination.
    BackHandler(enabled = showHud) { showHud = false }

    // ---- Prewarm ----
    //
    // ONE persistent loop, keyed on the stream and the decode settings and NOT
    // on the current page (an effect keyed on the page restarted — and cancelled
    // its own in-flight downloads — on every scroll step, which is what made slow
    // sources stutter). It walks outward from the page under the reader, nearest
    // first, downloading every in-window page ONCE into [WebtoonPageCache]
    // through the source's own client, so both viewers always find a page's bytes
    // already on disk. Short pages additionally get their display-size decode
    // kept warm in Coil's memory cache by a rolling refresh bounded to one warm
    // in flight and only while the reader is at rest, so a page scrolling in is
    // an instant cache hit; TALL strips (height > 3x width, yomi/mihon's
    // isTallImage rule) are left to the subsampling view's region-decode from the
    // file — never a giant full-height bitmap.
    LaunchedEffect(
        segmentDescriptors,
        imageLoader,
        displayDecodeWidth,
        useRgb565,
        pageSource,
        cacheDir,
        isWebtoon,
        settings.cropBorders,
    ) {
        if (!isWebtoon) return@LaunchedEffect
        if (imageLoader == null) return@LaunchedEffect
        var warmInFlight = 0
        // Pages whose bytes are already on disk, pages with a download in the air
        // (so the loop never launches the same page twice), imageUrl -> time of
        // last failed download (so a transient failure cannot hot-spin the loop),
        // and imageUrl -> the position it was last memory-warmed for.
        val downloaded = HashMap<String, Boolean>()
        val inFlight = HashMap<String, Boolean>()
        val downloadFailed = HashMap<String, Long>()
        val warmPos = HashMap<String, Int>()
        while (isActive) {
            val now = System.currentTimeMillis()
            val segs = segmentDescriptors
            if (segs.isEmpty() || segs.any { it.isEmpty() }) {
                delay(120)
                continue
            }
            val starts = IntArray(segs.size)
            var total = 0
            for (i in segs.indices) {
                starts[i] = total
                total += segs[i].size
            }
            if (total == 0) {
                delay(120)
                continue
            }
            val segIdx = streamPosition.first.coerceIn(0, segs.lastIndex)
            val segSize = segs[segIdx].size
            val globCur = (starts[segIdx] + (currentPage - 1).coerceIn(0, segSize - 1)).coerceIn(0, total - 1)
            // 12 pages behind and 60 ahead: the strip leans AHEAD because that is
            // the direction the reader scrolls, and the lead time is what lets a
            // slow source finish a page before it enters the viewport.
            val warmFrom = (globCur - 12).coerceAtLeast(0)
            val warmTo = (globCur + 60).coerceAtMost(total - 1)
            val maxDist = maxOf(globCur - warmFrom, warmTo - globCur)
            val collected = mutableListOf<MangaSource.PageDescriptor>()
            walk@ for (d in 0..maxDist) {
                val gs = if (d == 0) intArrayOf(globCur) else intArrayOf(globCur + d, globCur - d)
                for (g in gs) {
                    if (collected.size >= WEBTOON_BATCH) break@walk
                    if (g < warmFrom || g > warmTo) continue
                    var seg = 0
                    while (seg < segs.size && g >= starts[seg] + segs[seg].size) seg++
                    if (seg >= segs.size) continue
                    val m = segs[seg][g - starts[seg]]
                    if (downloadFailed.containsKey(m.imageUrl) && now - downloadFailed[m.imageUrl]!! < 8000) continue
                    // The viewer's page holder downloads a page the moment it is
                    // shown; this loop just fills the window AHEAD so the pages
                    // are already on disk when the holder asks (single-flighted
                    // via WebtoonPageCache).
                    if (!downloaded.containsKey(m.imageUrl) && !inFlight.containsKey(m.imageUrl)) {
                        collected.add(m)
                    }
                }
            }
            // A small CONCURRENT batch, re-scanned every tick: one slow page can
            // never stall the whole window the way an awaitAll would.
            val room = (WEBTOON_BATCH - inFlight.size).coerceAtLeast(0)
            for (m in collected.take(room)) {
                val key = m.imageUrl
                if (inFlight.containsKey(key) || downloaded.containsKey(key)) continue
                inFlight[key] = true
                launch {
                    try {
                        WebtoonPageCache.fileFor(m, pageSource, cacheDir)
                        // Warm the in-memory metadata now (dims + animated flag)
                        // so a later bind can size the frame synchronously.
                        WebtoonPageCache.prime(m, cacheDir)
                        downloaded[key] = true
                    } catch (e: Throwable) {
                        // An effect restart cancels in-flight work — a
                        // cancellation is not a page failure.
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        downloadFailed[key] = System.currentTimeMillis()
                    } finally {
                        inFlight.remove(key)
                    }
                }
            }

            // Rolling memory refresh for the pages just ahead (short pages only):
            // at most one warm in flight, one new page per tick, and a page
            // re-warmed only after the position has moved on a few pages.
            val cacheBytes = (imageLoader.memoryCache?.maxSize ?: 0).toLong()
            val estPageBytes = WEBTOON_MAX_DECODE_PIXELS * 4L
            val aheadPages = if (cacheBytes > 0L) {
                (cacheBytes / (estPageBytes * 2L)).toInt()
                    .coerceIn(WEBTOON_MEM_HORIZON_AHEAD_MIN, WEBTOON_MEM_HORIZON_AHEAD_MAX)
            } else {
                WEBTOON_MEM_HORIZON_AHEAD_MIN
            }
            val refreshOrder = buildList {
                for (d in aheadPages downTo 1) add(globCur + d)
                for (d in 1..WEBTOON_MEM_HORIZON_BEHIND) add(globCur - d)
            }
            val slots = if (userScrolling) 0 else (WEBTOON_MEM_WARM_CONCURRENCY - warmInFlight).coerceAtLeast(0)
            var claimed = 0
            for (g in refreshOrder) {
                if (claimed >= slots) break
                if (g < 0 || g >= total) continue
                var seg = 0
                while (seg < segs.size && g >= starts[seg] + segs[seg].size) seg++
                if (seg >= segs.size) continue
                val m = segs[seg][g - starts[seg]]
                val lastPos = warmPos[m.imageUrl] ?: -1
                if (lastPos >= globCur - WEBTOON_MEM_WARM_STALE) continue
                if (downloadFailed.containsKey(m.imageUrl) && now - downloadFailed[m.imageUrl]!! < 8000) continue
                val f = WebtoonPageCache.targetFile(WebtoonPageCache.keyFor(m.imageUrl), cacheDir)
                if (!f.exists() || f.length() == 0L) continue
                val mm = WebtoonPageCache.meta(m, cacheDir) ?: continue
                if (mm.isTall || mm.isAnimated) continue
                warmPos[m.imageUrl] = globCur
                warmInFlight++
                claimed++
                launch {
                    try {
                        val warmW = budgetedShortWidth(mm.width, mm.height, displayDecodeWidth)
                        runCatching {
                            imageLoader.execute(
                                ImageRequest.Builder(context)
                                    .data(f)
                                    .size(Size(warmW, Dimension.Undefined))
                                    .memoryCachePolicy(CachePolicy.ENABLED)
                                    .diskCachePolicy(CachePolicy.DISABLED)
                                    .allowHardware(!settings.cropBorders)
                                    .decoderDispatcher(readerPageDecodeDispatcher)
                                    .apply { if (settings.cropBorders) cropBorders(true) }
                                    .build(),
                            )
                        }
                    } finally {
                        warmInFlight--
                    }
                }
            }
            delay(if (userScrolling) 90 else 50)
        }
    }

    val webtoonConfig = remember(
        settings.cropBorders,
        settings.cropBordersContinuous,
        settings.webtoonSidePadding,
        settings.webtoonNavigationMode,
        settings.webtoonNavInverted,
        settings.webtoonSmallerTapZone,
        settings.webtoonScaleType,
        settings.longStripGapSmartScale,
        settings.webtoonDisableZoomOut,
        settings.webtoonPageTransitions,
        settings.readerHideThreshold,
        settings.doubleTapAnimDuration,
        settings.alwaysDecodeLongStripWithSSIV,
        settings.continuousVerticalTappingByPage,
        settings.webtoonSmoothAutoScroll,
        settings.doubleTapZoom,
        settings.pinchToZoom,
        settings.webtoonFade,
    ) {
        WebtoonConfig().apply {
            cropBordersWebtoon = settings.cropBorders
            continuousCropBorders = settings.cropBordersContinuous
            webtoonSidePadding = settings.webtoonSidePadding
            navigationMode = settings.webtoonNavigationMode
            tappingInverted = settings.webtoonNavInverted
            smallerTapZone = settings.webtoonSmallerTapZone
            webtoonScaleType = settings.webtoonScaleType
            longStripGapSmartScale = settings.longStripGapSmartScale
            webtoonDisableZoomOut = settings.webtoonDisableZoomOut
            usePageTransitions = settings.webtoonPageTransitions
            readerHideThreshold = settings.readerHideThreshold
            doubleTapAnimDuration = settings.doubleTapAnimDuration
            alwaysDecodeLongStripWithSSIV = settings.alwaysDecodeLongStripWithSSIV
            continuousVerticalTappingByPage = settings.continuousVerticalTappingByPage
            smoothAutoScroll = settings.webtoonSmoothAutoScroll
            doubleTapZoom = settings.doubleTapZoom
            pinchToZoom = settings.pinchToZoom
            fadeIn = settings.webtoonFade
        }
    }

    val pagerConfig = remember(
        settings.fit,
        readerMode,
        settings.cropBordersPaged,
        settings.doubleTapZoom,
        settings.pinchToZoom,
        settings.webtoonNavigationMode,
        settings.webtoonNavInverted,
        settings.webtoonSmallerTapZone,
        settings.doubleTapAnimDuration,
        settings.webtoonPageTransitions,
        settings.tapToChangePages,
        settings.bgArgb,
    ) {
        PagerConfig().apply {
            // The fit setting is what a PAGED page is scaled by — which is why
            // picking one here changes the page immediately. A long strip is
            // always fit-width by design; its own scaling is the Webtoon
            // section's scale-type controls.
            imageScaleType = when (settings.fit) {
                ReaderFit.FIT, ReaderFit.SMART_FIT -> SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
                ReaderFit.STRETCH, ReaderFit.FIT_HEIGHT -> SubsamplingScaleImageView.SCALE_TYPE_CENTER_CROP
                ReaderFit.FIT_WIDTH -> SubsamplingScaleImageView.SCALE_TYPE_FIT_WIDTH
                ReaderFit.ORIGINAL_SIZE -> SubsamplingScaleImageView.SCALE_TYPE_CUSTOM
            }
            imageZoomType = when (readerMode) {
                ReaderMode.RIGHT_TO_LEFT -> ReaderPageImageView.Config.ZoomStartPosition.RIGHT
                ReaderMode.VERTICAL -> ReaderPageImageView.Config.ZoomStartPosition.CENTER
                else -> ReaderPageImageView.Config.ZoomStartPosition.LEFT
            }
            imageCropBorders = settings.cropBordersPaged
            doubleTapZoom = settings.doubleTapZoom
            enablePinchToZoom = settings.pinchToZoom
            usePageTransitions = settings.webtoonPageTransitions
            doubleTapAnimDuration = settings.doubleTapAnimDuration
            navigationMode = when {
                !settings.tapToChangePages -> 5
                settings.webtoonNavigationMode != 5 -> settings.webtoonNavigationMode
                else -> 4
            }
            tappingInverted = settings.webtoonNavInverted
            smallerTapZone = settings.webtoonSmallerTapZone
            isVertical = readerMode == ReaderMode.VERTICAL
            pageCanvasColor = settings.bgArgb.toInt()
        }
    }

    val chromeChapters = remember(navChapters, chapters, activeChapterUrl) {
        val at = navIndexOf(navChapters, activeChapterUrl, chapters)
        navChapters.mapIndexed { i, c ->
            ReaderChapter(id = c.url, name = c.label, read = at >= 0 && i < at)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> {
                        if (isWebtoon) viewerRef.value?.scrollBy(-320) else pagerRef.value?.moveToPage(pagerPos - 1)
                        true
                    }
                    Key.DirectionRight -> {
                        if (isWebtoon) viewerRef.value?.scrollBy(320) else pagerRef.value?.moveToPage(pagerPos + 1)
                        true
                    }
                    Key.DirectionCenter, Key.Enter -> {
                        showHud = !showHud
                        true
                    }
                    else -> false
                }
            }
    ) {
        when {
            error != null && pages.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = tr("Couldn't load this chapter"),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = textColor,
                        ),
                    )
                    Text(
                        text = error ?: "",
                        style = MaterialTheme.typography.bodySmall.copy(color = textColor.copy(alpha = 0.7f)),
                        maxLines = 3,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { reload++ }) { Text(tr("Retry")) }
                        OutlinedButton(
                            onClick = openVerify,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = textColor),
                        ) { Text(tr("Verify in WebView")) }
                    }
                }
            }

            loading && pages.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = textColor)
            }

            isWebtoon -> YomiWebtoonReader(
                source = pageSource,
                cacheDir = cacheDir,
                streamQueue = streamQueue,
                streamSegments = segmentDescriptors,
                segSizes = segmentDescriptors.map { it.size },
                bgColor = bgColor,
                textColor = textColor,
                gaps = readerMode == ReaderMode.WEBTOON_GAPS,
                config = webtoonConfig,
                onHideMenu = { showHud = false },
                colorFilter = pageColorFilter,
                decodeWidth = displayDecodeWidth,
                rgb565 = useRgb565,
                autoScroll = settings.autoScroll,
                autoScrollSpeedDp = settings.autoScrollSpeedDp,
                initialPageIndex = webtoonInitialPos,
                trailer = when {
                    loadingNext -> WebtoonTrailer.Loading
                    nextError != null && streamNextChapter != null -> WebtoonTrailer.Error(nextError ?: "")
                    streamNextChapter != null -> WebtoonTrailer.Idle
                    else -> WebtoonTrailer.End(labelOf(activeChapterUrl))
                },
                viewerRef = viewerRef,
                onPageChanged = { seg, page, total -> viewerPos = Triple(seg, page, total) },
                onNearEndChanged = { near -> nearEnd = near },
                onNearStartChanged = { near -> nearStart = near },
                onMenuTap = { showHud = !showHud },
                onUserScroll = { if (settings.autoScroll) save(settings.copy(autoScroll = false)) },
                onScrollingChanged = { userScrolling = it },
                onTrailerRetry = {
                    nextError = null
                    streamNextChapter?.let { loadNextIntoStream(it) }
                },
                modifier = Modifier.fillMaxSize(),
            )

            else -> ChimahonPagerReader(
                source = pageSource,
                cacheDir = cacheDir,
                pages = pageDescriptors,
                bgColor = bgColor,
                textColor = textColor,
                vertical = readerMode == ReaderMode.VERTICAL,
                reversed = readerMode == ReaderMode.RIGHT_TO_LEFT,
                config = pagerConfig,
                initialPageIndex = openPage,
                viewerRef = pagerRef,
                onPageChanged = { page, _ -> pagerPos = page },
                onMenuTap = { showHud = !showHud },
                onZoom = { showHud = false },
                colorFilter = pageColorFilter,
                decodeWidth = displayDecodeWidth,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // A chapter whose PAGE LIST never arrives is usually a verification wall
        // (the site gates chapter requests, not just its catalog), so the reader
        // says so once, ten seconds in — and the nudge itself is the way to the
        // WebView.
        if (pages.isEmpty()) {
            VerificationNudge(
                waiting = loading,
                onOpenWebView = openVerify,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 60.dp),
            )
        }

        // Colour/brightness overlays, above the page but below the chrome so the
        // menu stays legible.
        val blend = ColorFilterMode.entries.getOrElse(settings.colorFilterMode) { ColorFilterMode.DEFAULT }.blendMode
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (settings.customBrightness && settings.customBrightnessValue < 0) {
                        Modifier.background(
                            Color.Black.copy(alpha = (-settings.customBrightnessValue / 100f).coerceIn(0f, 1f)),
                        )
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (settings.colorFilter) {
                        Modifier.drawBehind {
                            drawRect(color = Color(settings.colorFilterValue), blendMode = blend)
                        }
                    } else {
                        Modifier
                    },
                ),
        )

        if (TvMode.current() && showHud) {
            Text(
                tr("Press OK to hide the controls · ◀ ▶ turn the page"),
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp),
            )
        }

        YomiReaderChrome(
            visible = showHud,
            mangaTitle = title,
            chapterTitle = labelOf(activeChapterUrl),
            bookmarked = followed,
            onToggleBookmarked = {
                MangaStore.toggleFollow(MangaMark.record(item, providerName))
                followed = MangaStore.isFollowed(key)
            },
            onOpenInWebView = openVerify,
            onReloadChapter = {
                nextError = null
                reload++
            },
            onBack = { nav.popBackStack() },
            isWebtoon = isWebtoon,
            autoScroll = settings.autoScroll,
            autoScrollSpeedDp = settings.autoScrollSpeedDp,
            onToggleAutoScroll = { save(settings.copy(autoScroll = !settings.autoScroll)) },
            onAutoScrollSpeedChange = { save(settings.copy(autoScrollSpeedDp = it)) },
            prevEnabled = prevChapter != null,
            onPrevChapter = { prevChapter?.let { openChapter(it) } },
            nextEnabled = nextChapter != null,
            onNextChapter = { nextChapter?.let { openChapter(it) } },
            currentPage = { currentPage },
            totalPages = { pageTotal },
            onSeekPage = { target ->
                if (isWebtoon) {
                    val start = streamSegments.take(streamPosition.first).sumOf { it.size } + streamPosition.first
                    viewerRef.value?.moveToPage(start + target)
                } else {
                    pagerRef.value?.moveToPage(target + 1)
                }
            },
            readerMode = readerMode,
            onSelectReaderMode = { mode ->
                if (seriesOverrideEnabled) saveSeriesModes(seriesModes + (key to mode.name))
                else save(settings.copy(mode = mode))
            },
            readerFit = settings.fit,
            onSelectReaderFit = { save(settings.copy(fit = it)) },
            readerOrientation = settings.orientation,
            onSelectReaderOrientation = { save(settings.copy(orientation = it)) },
            cropBorders = settings.cropBorders,
            onToggleCropBorders = { save(settings.copy(cropBorders = !settings.cropBorders)) },
            cropBordersPaged = settings.cropBordersPaged,
            onToggleCropBordersPaged = { save(settings.copy(cropBordersPaged = !settings.cropBordersPaged)) },
            cropBordersContinuous = settings.cropBordersContinuous,
            onToggleCropBordersContinuous = { save(settings.copy(cropBordersContinuous = !settings.cropBordersContinuous)) },
            doubleTapZoom = settings.doubleTapZoom,
            onToggleDoubleTapZoom = { save(settings.copy(doubleTapZoom = !settings.doubleTapZoom)) },
            pinchToZoom = settings.pinchToZoom,
            onTogglePinchToZoom = { save(settings.copy(pinchToZoom = !settings.pinchToZoom)) },
            tapToChangePages = settings.tapToChangePages,
            onToggleTapToChangePages = { save(settings.copy(tapToChangePages = !settings.tapToChangePages)) },
            webtoonSidePadding = settings.webtoonSidePadding,
            onWebtoonSidePaddingChange = { save(settings.copy(webtoonSidePadding = it)) },
            webtoonNavigationMode = settings.webtoonNavigationMode,
            onWebtoonNavigationModeChange = { save(settings.copy(webtoonNavigationMode = it)) },
            webtoonNavInverted = settings.webtoonNavInverted,
            onWebtoonNavInvertedChange = { save(settings.copy(webtoonNavInverted = it)) },
            webtoonSmallerTapZone = settings.webtoonSmallerTapZone,
            onToggleWebtoonSmallerTapZone = { save(settings.copy(webtoonSmallerTapZone = !settings.webtoonSmallerTapZone)) },
            webtoonScaleType = settings.webtoonScaleType,
            onWebtoonScaleTypeChange = { save(settings.copy(webtoonScaleType = it)) },
            longStripGapSmartScale = settings.longStripGapSmartScale,
            onToggleLongStripGapSmartScale = { save(settings.copy(longStripGapSmartScale = !settings.longStripGapSmartScale)) },
            webtoonDisableZoomOut = settings.webtoonDisableZoomOut,
            onToggleWebtoonDisableZoomOut = { save(settings.copy(webtoonDisableZoomOut = !settings.webtoonDisableZoomOut)) },
            webtoonPageTransitions = settings.webtoonPageTransitions,
            onToggleWebtoonPageTransitions = { save(settings.copy(webtoonPageTransitions = !settings.webtoonPageTransitions)) },
            webtoonSmoothAutoScroll = settings.webtoonSmoothAutoScroll,
            onToggleWebtoonSmoothAutoScroll = { save(settings.copy(webtoonSmoothAutoScroll = !settings.webtoonSmoothAutoScroll)) },
            alwaysDecodeLongStripWithSSIV = settings.alwaysDecodeLongStripWithSSIV,
            onToggleAlwaysDecodeLongStripWithSSIV = { save(settings.copy(alwaysDecodeLongStripWithSSIV = !settings.alwaysDecodeLongStripWithSSIV)) },
            continuousVerticalTappingByPage = settings.continuousVerticalTappingByPage,
            onToggleContinuousVerticalTappingByPage = { save(settings.copy(continuousVerticalTappingByPage = !settings.continuousVerticalTappingByPage)) },
            readerHideThreshold = settings.readerHideThreshold,
            onReaderHideThresholdChange = { save(settings.copy(readerHideThreshold = it)) },
            doubleTapAnimDuration = settings.doubleTapAnimDuration,
            onDoubleTapAnimDurationChange = { save(settings.copy(doubleTapAnimDuration = it)) },
            showReadingMode = settings.showReadingMode,
            onToggleShowReadingMode = { save(settings.copy(showReadingMode = !settings.showReadingMode)) },
            customBrightness = settings.customBrightness,
            onToggleCustomBrightness = { save(settings.copy(customBrightness = !settings.customBrightness)) },
            customBrightnessValue = settings.customBrightnessValue,
            onCustomBrightnessValueChange = { save(settings.copy(customBrightnessValue = it)) },
            colorFilter = settings.colorFilter,
            onToggleColorFilter = { save(settings.copy(colorFilter = !settings.colorFilter)) },
            colorFilterValue = settings.colorFilterValue,
            onColorFilterValueChange = { save(settings.copy(colorFilterValue = it)) },
            colorFilterMode = settings.colorFilterMode,
            onColorFilterModeChange = { save(settings.copy(colorFilterMode = it)) },
            grayscale = settings.grayscale,
            onToggleGrayscale = { save(settings.copy(grayscale = !settings.grayscale)) },
            invertedColors = settings.invertedColors,
            onToggleInvertedColors = { save(settings.copy(invertedColors = !settings.invertedColors)) },
            imageEnhance = settings.imageEnhance,
            onToggleImageEnhance = { save(settings.copy(imageEnhance = !settings.imageEnhance)) },
            readerBg = settings.bg,
            onSelectReaderBg = { save(settings.copy(bg = it)) },
            showPageNumber = settings.showPageNumber,
            onToggleShowPageNumber = { save(settings.copy(showPageNumber = !settings.showPageNumber)) },
            keepScreenOn = settings.keepScreenOn,
            onToggleKeepScreenOn = { save(settings.copy(keepScreenOn = !settings.keepScreenOn)) },
            webtoonFade = settings.webtoonFade,
            onToggleWebtoonFade = { save(settings.copy(webtoonFade = !settings.webtoonFade)) },
            readerQuality = settings.readerQuality,
            onSelectReaderQuality = { save(settings.copy(readerQuality = it)) },
            onResetSettings = { save(ReaderSettings.DEFAULTS) },
            seriesOverrideEnabled = seriesOverrideEnabled,
            onToggleSeriesOverride = {
                if (seriesOverrideEnabled) saveSeriesModes(seriesModes - key)
                else saveSeriesModes(seriesModes + (key to readerMode.name))
            },
            chapters = chromeChapters,
            activeChapterId = activeChapterUrl,
            onSelectChapter = { openChapter(it) },
            chapterCoverModel = posterUrl.takeIf { it.isNotBlank() },
        )
    }
}

// How many in-window webtoon pages the prewarm fetches at once. A small
// concurrent batch keeps the window filled ahead of the scroll even when every
// page costs a full download — a sequential one-at-a-time loop cannot keep up on
// slow sources.
private const val WEBTOON_BATCH = 8

// Rolling memory-refresh horizon: up to this many pages just ahead (and a couple
// behind) are kept warm in Coil's memory cache, re-warmed as the reader
// advances. The depth is derived from the memory cache's capacity and clamped,
// so a big-heap device gets a long runway for flings while a small-heap one
// cannot overflow the cache and thrash.
private const val WEBTOON_MEM_HORIZON_AHEAD_MIN = 6
private const val WEBTOON_MEM_HORIZON_AHEAD_MAX = 12
private const val WEBTOON_MEM_HORIZON_BEHIND = 3

// The rolling memory refresh never floods the decoder: at most this many warm
// executes are in flight at once (one), which also guarantees a visible-page
// bind always has a free slot in the shared decode dispatcher.
private const val WEBTOON_MEM_WARM_CONCURRENCY = 1

// A page is re-warmed only after the position has advanced this many pages, so
// near pages get re-touched enough to survive LRU eviction without re-decoding
// on every tick.
private const val WEBTOON_MEM_WARM_STALE = 2

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
 * One entry per chapter NUMBER, in reading order.
 *
 * An aggregator lists the same chapter once per scanlation group, so walking the
 * raw list stepped to the same chapter from the next group. For each number the
 * release by [preferScanlator] wins when there is one, so stepping forward keeps
 * the translation the reader is already reading; otherwise the first release
 * wins. Chapters whose number cannot be read at all are kept as their own
 * entries, so nothing is ever merged by accident.
 */
private fun dedupeChapters(list: List<MangaChapter>, preferScanlator: String?): List<MangaChapter> {
    val group = preferScanlator?.takeIf { it.isNotBlank() }
    val slots = ArrayList<Pair<Float, MangaChapter>>(list.size)
    list.forEachIndexed { i, c ->
        val no = chapterNo(c)
        // A chapter with no readable number gets a key of its own — a distinct
        // negative — so it can never be merged into another one.
        val slotKey = if (no >= 0f) no else -1f - i
        val at = slots.indexOfFirst { it.first == slotKey }
        if (at < 0) {
            slots += slotKey to c
            return@forEachIndexed
        }
        if (group != null) {
            val held = slots[at].second
            if (held.scanlator != group && c.scanlator == group) slots[at] = slotKey to c
        }
    }
    return slots.map { it.second }
}

/** The legacy per-key reading mode ([MangaReadMode]) as the reader's own mode. */
private fun legacyReaderMode(raw: String): ReaderMode = when (raw) {
    MangaReadMode.PAGED_LTR -> ReaderMode.LEFT_TO_RIGHT
    MangaReadMode.PAGED_RTL -> ReaderMode.RIGHT_TO_LEFT
    else -> ReaderMode.WEBTOON
}

/** The legacy fit ([MangaFit]) as the reader's own fit. */
private fun legacyReaderFit(raw: String): ReaderFit = when (raw) {
    MangaFit.HEIGHT -> ReaderFit.FIT_HEIGHT
    MangaFit.WHOLE -> ReaderFit.FIT
    else -> ReaderFit.FIT_WIDTH
}

/** The legacy backdrop key (AppStore.normalizeReaderBg) as the reader's own. */
private fun legacyReaderBg(raw: String): ReaderBg = when (raw.lowercase()) {
    "grey", "gray" -> ReaderBg.DARK_GRAY
    "white" -> ReaderBg.WHITE
    else -> ReaderBg.PURE_BLACK
}

/** The per-series override map, from its `mangaKey -> ReaderMode name` JSON. */
private fun parseSeriesModes(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    val o = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
    val out = LinkedHashMap<String, String>(o.length())
    for (k in o.keys()) {
        val v = o.optString(k)
        if (ReaderMode.entries.any { it.name == v }) out[k] = v
    }
    return out
}

/** Unwraps the ContextWrapper chain to the hosting Activity — the Compose
 *  context is a wrapper, and the window flags/orientation live on the Activity. */
private fun android.content.Context.findActivity(): Activity? {
    var ctx: android.content.Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
