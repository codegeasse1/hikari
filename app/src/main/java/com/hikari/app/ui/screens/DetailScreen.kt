package com.hikari.app.ui.screens
import androidx.compose.ui.focus.focusRequester
import com.hikari.app.i18n.I18n
import com.hikari.app.i18n.tr
import com.hikari.app.i18n.trTag

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.hikari.app.HikariApp
import com.hikari.app.R
import com.hikari.app.data.ContentRepository
import com.hikari.app.data.ContentRepository.StreamLookup
import com.hikari.app.data.AppStore
import com.hikari.app.data.CatalogRef
import com.hikari.app.data.CastMember
import com.hikari.app.data.CompanyRef
import com.hikari.app.data.Episode
import com.hikari.app.data.HistoryEntry
import com.hikari.app.data.LibraryCategory
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.screenTypeFor
import com.hikari.app.data.isSiteScraperProviderId
import com.hikari.app.data.ProviderType
import com.hikari.app.data.RatingSource
import com.hikari.app.data.RatingVerdict
import com.hikari.app.data.Ratings
import com.hikari.app.data.StreamCache
import com.hikari.app.data.StreamSource
import com.hikari.app.data.TitleDetails
import com.hikari.app.data.TitleExtras
import com.hikari.app.data.TitleRating
import com.hikari.app.data.TmdbMeta
import com.hikari.app.data.TmdbSourceType
import com.hikari.app.data.TmdbSpec
import com.hikari.app.data.TmdbStills
import com.hikari.app.data.TmdbScreenshots
import com.hikari.app.data.Translator
import com.hikari.app.data.Trailer
import com.hikari.app.data.WatchProgress
import com.hikari.app.tracker.TrackerSync
import com.hikari.app.net.StreamProbe
import com.hikari.app.player.PlayerActivity
import com.hikari.app.player.StreamsLive
import com.hikari.app.providers.ContentProvider
import com.hikari.app.providers.IptvProvider
import com.hikari.app.ui.Artwork
import com.hikari.app.ui.LoadingStyles
import com.hikari.app.ui.PosterArt
import com.hikari.app.ui.PosterLoader
import com.hikari.app.ui.openUrl
import com.hikari.app.ui.openYouTubeVideo
import com.hikari.app.ui.rememberPosterScore
import com.hikari.app.ui.rememberPosterStyle
import com.hikari.app.ui.components.ChoiceDialog
import com.hikari.app.ui.components.ChoiceItem
import com.hikari.app.ui.components.EmptyState
import com.hikari.app.ui.components.CategoryPickerSheet
import com.hikari.app.ui.components.GlassShape
import com.hikari.app.ui.theme.rememberGlassTokens
import com.hikari.app.ui.components.HeroArtwork
import com.hikari.app.ui.navigation.Routes
import com.hikari.app.tv.tvPress
import com.hikari.app.tv.Cs3
import com.hikari.app.tv.Cs3ActorCard
import com.hikari.app.tv.Cs3AntiSpoilerPill
import com.hikari.app.tv.Cs3CastHeader
import com.hikari.app.tv.Cs3CountHeader
import com.hikari.app.tv.Cs3EpisodeCard
import com.hikari.app.tv.Cs3HeaderLink
import com.hikari.app.tv.Cs3RatingBadges
import com.hikari.app.tv.Cs3ScreenshotsMosaic
import com.hikari.app.tv.Cs3SectionHeader
import com.hikari.app.tv.Cs3ShelfHeader
import com.hikari.app.tv.Cs3ShowDetails
import com.hikari.app.tv.Cs3SortChip
import com.hikari.app.tv.Cs3TrailerCard
import com.hikari.app.tv.Cs3ViewAllCastCard
import com.hikari.app.web.WebViewActivity
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class DetailViewModel(app: Application) : AndroidViewModel(app) {
    private val manager = (app as HikariApp).providers
    private val repo = ContentRepository(manager)
    /** The title's own provider asked alone on a short budget — the fast
     *  first half of a re-extract (see the refresh collector in the play
     *  flow below): the dead links almost always came from it. */
    suspend fun originStreamsFast(item: MediaItem, episode: Episode?): List<StreamSource> =
        runCatching { repo.originStreams(item, episode) }.getOrDefault(emptyList())
    suspend fun serversForBatch(
        item: MediaItem,
        episode: Episode,
        originOnly: Boolean = false,
        /** Live downloadable servers for the batch picker: invoked whenever the running lookup grows, so the UI can show servers instantly instead of waiting for the whole union. Null keeps the old blocking shape. */
        onServers: (suspend (List<StreamSource>) -> Unit)? = null,
    ): List<StreamSource> {
        fun dlOk(s: StreamSource) =
            !s.isTorrent && !s.externalUrl && s.ytId.isNullOrBlank() && s.url.startsWith("http", true)
        fun epTag() = "S" + episode.season + "E" + episode.number
        fun log(msg: String) = runCatching {
            com.hikari.app.data.Logs.log("Download", "batch \"${item.title.take(50)}\" ${epTag()} $msg")
        }
        suspend fun publishLive(servers: List<StreamSource>) {
            val sink = onServers ?: return
            if (servers.isEmpty()) return
            try {
                sink(servers)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
        log("lookup start scope=" + if (originOnly) "origin-only" else "all-extensions")
        if (originOnly) {
            // "This extension": the title's own provider alone, no cross search.
            val origin = runCatching { repo.originStreams(item, episode, 15000L) }.getOrDefault(emptyList())
            val playable = origin.filter { dlOk(it) }
            log("origin-only: ${playable.size} downloadable of ${origin.size} found")
            publishLive(playable)
            return playable
        }
        val origin = runCatching { repo.originStreams(item, episode, 15000L) }.getOrDefault(emptyList())
        val playable = origin.filter { dlOk(it) }
        if (playable.isNotEmpty()) {
            log("origin hit: ${playable.size} downloadable — no sweep needed")
            publishLive(playable)
            return playable
        }
        // "All extensions": the full cross-extension sweep — but one capped
        // pass is not an answer. The play flow retries an unfinished lookup
        // instead of reporting none (see StreamLookup.complete); the batch
        // does the same, bounded by a TOTAL 100s budget across up to three
        // passes, keeping the best partial. A pass cut off by its timeout
        // used to return NOTHING (the lookup never came back, so there was no
        // list to filter) even while the log showed servers landing — the
        // "31 servers found, download-all says none" report. Now every pass
        // harvests its own progressive feed, so a slow pass still queues what
        // it found instead of nothing.
        var best: List<StreamSource> = emptyList()
        var liveSent = 0
        val deadline = android.os.SystemClock.uptimeMillis() + 100_000L
        repeat(3) { pass ->
            val left = deadline - android.os.SystemClock.uptimeMillis()
            if (left <= 0L) {
                log("pass ${pass + 1} skipped: out of budget (best=${best.size})")
                return best
            }
            val partial = java.util.Collections.synchronizedList(ArrayList<StreamSource>())
            val lookup = withTimeoutOrNull(left.coerceAtMost(60_000L)) {
                runCatching {
                    repo.streamsForOutcome(item, episode, onProgress = { got ->
                        val downloadable = got.filter { dlOk(it) }
                        partial.clear()
                        partial.addAll(downloadable)
                        if (downloadable.size > liveSent) {
                            liveSent = downloadable.size
                            publishLive(downloadable)
                        }
                    })
                }.getOrNull()
            }
            val got = (lookup?.servers ?: partial.toList()).filter { dlOk(it) }
            if (got.size > best.size) {
                best = got
                if (got.size > liveSent) {
                    liveSent = got.size
                    publishLive(got)
                }
            }
            log(
                "pass ${pass + 1}: got=${got.size} best=${best.size} " +
                    "complete=${lookup?.complete} timedOut=${lookup == null}",
            )
            if (got.isNotEmpty() && lookup?.complete == true) return got
            if (lookup?.complete == true) return best
        }
        log("lookup done: ${best.size} server(s)")
        return best
    }
    suspend fun enqueueBatch(ctx: android.content.Context, item: MediaItem, episodes: List<Episode>, chosen: StreamSource): Int {
        var done = 0
        for (ep in episodes) {
            val servers = runCatching { serversForBatch(item, ep) }.getOrDefault(emptyList())
            if (servers.isEmpty()) continue
            val src = servers.firstOrNull { it.name == chosen.name && it.providerId == chosen.providerId }
                ?: servers.firstOrNull { it.providerId == chosen.providerId }
                ?: servers.firstOrNull { it.name == chosen.name }
                ?: servers[0]
            if (src.url.isBlank()) continue
            val label = if (ep.season > 1) "S" + ep.season + " E" + ep.number else "E" + ep.number
            // The task id is per episode: a blank provider episode id used to
            // collapse the whole batch onto ONE id, so every enqueue replaced
            // the last and only a single download survived.
            val epKey = ep.id.ifBlank { "s" + ep.season + "e" + ep.number }
            val task = com.hikari.app.download.DownloadTask(
                id = com.hikari.app.download.DownloadTask.idFor(item.providerId.ifBlank { "detail" }, item.id, epKey, com.hikari.app.download.DownloadKind.OFFLINE),
                title = item.title.ifBlank { "Video" },
                episodeLabel = label,
                poster = item.posterUrl,
                providerId = item.providerId,
                mediaId = item.id,
                episodeId = ep.id,
                sourceName = src.name,
                url = src.url,
                headers = src.headers,
                isM3u8 = src.isM3u8 || src.url.substringBefore("?").lowercase().contains(".m3u8"),
                subtitles = src.subtitles,
                kind = com.hikari.app.download.DownloadKind.OFFLINE,
                status = com.hikari.app.download.DownloadStatus.QUEUED,
                createdAt = System.currentTimeMillis()
            )
            runCatching { com.hikari.app.download.DownloadsRepository.enqueue(ctx, task) }
            done++
        }
        return done
    }

    /** Installed extensions (names for the per-provider diagnostics shown in
     *  the sources sheet's empty state). */
    val providers: StateFlow<List<ContentProvider>> = manager.providers

    private val _meta = MutableStateFlow<MediaItem?>(null)
    val meta: StateFlow<MediaItem?> = _meta.asStateFlow()

    private val _episodes = MutableStateFlow<List<Episode>?>(null)
    val episodes: StateFlow<List<Episode>?> = _episodes.asStateFlow()

    /** True while the origin addon is still listing episodes (so the UI shows
     *  a spinner instead of a misleading "no episodes" for the first seconds). */
    private val _episodesLoading = MutableStateFlow(false)
    val episodesLoading: StateFlow<Boolean> = _episodesLoading.asStateFlow()

    /** True once the origin addon has FINISHED listing episodes (success or
     *  failure). Lets a Play tap tell "episodes still loading" apart from "this
     *  item genuinely has none", so a series is never searched with no episode. */
    private val _episodesLoaded = MutableStateFlow(false)
    val episodesLoaded: StateFlow<Boolean> = _episodesLoaded.asStateFlow()

    /** True when the episode lookup came back empty even after every retry —
     *  i.e. the extension could not answer, rather than "this show has no
     *  episodes". The page shows a real, tappable "couldn't load" line for that
     *  instead of the flat "No episode list available.", which is what made a
     *  cold plugin look like an empty series (see [loadEpisodesFor]). */
    private val _episodesFailed = MutableStateFlow(false)
    val episodesFailed: StateFlow<Boolean> = _episodesFailed.asStateFlow()

    /**
     * A Vega title's packs (audio variants, seasons, quality rows) in provider
     * order, for the picker's dropdown. The real Vega app shows exactly this
     * list and only ever opens the picked entry (`activeSeason`); the episodes
     * below are that entry's answer, so switching packs actually switches the
     * list instead of re-filtering one merged blob.
     */
    private val _vegaPacks =
        MutableStateFlow<List<com.hikari.app.providers.vega.VegaPack>>(emptyList())
    val vegaPacks: StateFlow<List<com.hikari.app.providers.vega.VegaPack>> = _vegaPacks.asStateFlow()
    private val _vegaPackIndex = MutableStateFlow(0)
    val vegaPackIndex: StateFlow<Int> = _vegaPackIndex.asStateFlow()

    /**
     * A Vega movie's playable quality rows in provider order, for the quality
     * picker under the Play row. Picking one resolves Play through THAT row's
     * link (see `selectedLink` in the provider) instead of always the first
     * row — which is why a title whose first row is dead played nowhere while
     * the Vega app played its other rows fine.
     */
    private val _vegaMovieOptions =
        MutableStateFlow<List<com.hikari.app.providers.vega.VegaMovieOption>>(emptyList())
    val vegaMovieOptions: StateFlow<List<com.hikari.app.providers.vega.VegaMovieOption>> =
        _vegaMovieOptions.asStateFlow()
    private val _vegaMovieIndex = MutableStateFlow(0)
    val vegaMovieIndex: StateFlow<Int> = _vegaMovieIndex.asStateFlow()

    /** The page's own "retry the episode list" tap (see [retryEpisodes]). */
    private var episodeRetryJob: Job? = null

    /**
     * The page's own stream prefetch (see [prefetchFirstStreams]).
     *
     * Held so that a REAL Play/episode tap can cancel it. The prefetch is a
     * full stream pass — inside one Aniyomi extension that is a walk over up to
     * eight hosters, up to half a minute — and it runs against the SAME
     * extension the tap needs. Letting it run put the tap's own lookup behind
     * work the user had just superseded: the reported "it takes almost 15
     * seconds" on Aniyomi/SkyStream, where the tap's search waited for the
     * prefetch's search to finish first. Cancelling costs nothing — the
     * servers the prefetch had already found are on the live feed, which
     * [openStreams] resets anyway — and the tap's own lookup then runs
     * immediately.
     */
    private var prefetchJob: Job? = null

    /** Starts the page's stream prefetch, superseding any earlier one. Held
     *  off while the episode list a series' prefetch would be for is still
     *  coming: the prefetch's stream walk holds the same extension's gate, so
     *  starting it mid-load is itself what made the episode list wait. */
    private fun startPrefetch(base: MediaItem) {
        if (base.type == MediaType.SERIES && _episodesLoading.value) return
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch { prefetchFirstStreams(base) }
    }

    /** A real lookup is starting: the prefetch is superseded (see
     *  [prefetchJob]). Returns immediately; the cancelled job's own provider
     *  call is released by cancellation. */
    fun cancelPrefetch() {
        prefetchJob?.cancel()
        prefetchJob = null
    }

    /**
     * Which episode load is the live one.
     *
     * Every load that can touch the episode state takes a ticket: `load()` for
     * its initial pass, `retryEpisodes()` for the page's own retry, and the
     * partial updates a streaming lookup publishes while it runs. Writes are
     * only applied when the ticket is still current. Without this, a
     * superseded pass could land its empty result on a NEWER pass's page — the
     * "Episodes (0) — No episode list available." that sat over a series whose
     * episodes were being fetched right then, and then filled in seconds later.
     */
    @Volatile
    private var episodeGeneration = 0

    /** Takes the next ticket for an episode load. */
    private fun newEpisodeGeneration(): Int = ++episodeGeneration

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** The provider id this page ended up using. Normally the one it was opened
     *  with; when that provider no longer exists, the one [remapMissingProvider]
     *  found for the same title — so Play/History carry a LIVE id. */
    private val _activeProviderId = MutableStateFlow("")
    val activeProviderId: StateFlow<String> = _activeProviderId.asStateFlow()

    /**
     * Finds an installed provider that carries one of [titles], for a page whose
     * origin provider id no longer exists. Local History is checked first
     * (instant, no network), then the installed providers of the same engine (a
     * renamed plugin usually re-registers the same sources), then the rest —
     * bounded to a handful of searches so this can never become a long stall.
     * Returns null when nothing matches, which keeps the old "Provider not
     * found" state.
     *
     * A LIST of names, because a TMDB row is displayed under a name its
     * extensions do not know (the app's TMDB language renamed it): every name
     * the item is known by is tried, and any of them counts as a match. Trying
     * only the display name is exactly what dead-ended a Spanish-language
     * install on a title every other language finds.
     */
    private suspend fun remapMissingProvider(missingId: String, titles: List<String>): String? {
        val names = titles.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (names.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            // 1) Watch history / library: the same title may still be recorded
            //    against a provider that exists (the user opened it there once).
            runCatching {
                val wanted = names.map { it.lowercase() }.toSet()
                HikariApp.instance.store.historyFlow().first()
                    .firstOrNull {
                        it.title.lowercase().trim() in wanted &&
                            manager.byId(it.providerId) != null
                    }?.providerId
            }.getOrNull()?.let { return@withContext it }

            // 2) The installed providers, same engine first.
            val engine = missingId.substringBefore('|')
            val candidates = manager.providers.value
                .filter { it.config.enabled }
                .sortedBy { if (it.config.id.substringBefore('|') == engine) 0 else 1 }
                .take(6)
            var best: Pair<String, Int>? = null
            for (p in candidates) {
                for (name in names) {
                    val hits = runCatching {
                        withTimeoutOrNull(5_000) { p.search(name, 1) }.orEmpty()
                    }.getOrDefault(emptyList())
                    for (hit in hits) {
                        val score = titleScoreFor(name, hit.title)
                        if (score >= 55 && (best == null || score > best!!.second)) {
                            best = p.config.id to score
                        }
                    }
                    if (best?.first == p.config.id) break
                }
                if ((best?.second ?: 0) >= 100) break
            }
            val found = best?.first
            if (found != null) {
                com.hikari.app.data.Logs.log(
                    "Detail",
                    "provider $missingId no longer exists — remapped " +
                        "\"${names.first()}\" to $found",
                )
            } else {
                com.hikari.app.data.Logs.log(
                    "Detail",
                    "provider $missingId no longer exists and " +
                        "\"${names.first()}\" was not found elsewhere" +
                        (if (names.size > 1) " (tried ${names.size} names)" else ""),
                )
            }
            found
        }
    }

    /** Loose title comparison for [remapMissingProvider] — keeps letters of any
     *  script (a CJK title must not normalise to nothing). */
    private fun titleScoreFor(wanted: String, candidate: String): Int {
        fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        val a = norm(wanted)
        val b = norm(candidate)
        if (a.isEmpty() || b.isEmpty()) return 0
        return when {
            a == b -> 100
            b.startsWith(a) || a.startsWith(b) -> 70
            b.contains(a) || a.contains(b) -> 55
            else -> {
                val ta = a.split(' ').filter { it.length > 2 }.toSet()
                val tb = b.split(' ').filter { it.length > 2 }.toSet()
                if (ta.isEmpty() || tb.isEmpty()) 0
                else (ta.intersect(tb).size * 100) / maxOf(ta.size, tb.size)
            }
        }
    }

    /** TMDB \"Recommendations\" shelf for the current title — what people
     *  watched next. Empty until the (background) lookup lands. */
    private val _related = MutableStateFlow<List<MediaItem>>(emptyList())
    val related: StateFlow<List<MediaItem>> = _related.asStateFlow()

    /** TMDB \"Similar\" shelf for the current title. */
    private val _similar = MutableStateFlow<List<MediaItem>>(emptyList())
    val similar: StateFlow<List<MediaItem>> = _similar.asStateFlow()

    /** Detail-page extras (metadata block, Cast, Trailers) for the current
     *  title, from a single background TMDB lookup. Null until it lands, and
     *  stays null when the title has no TMDB match — the sections then simply
     *  don't render. */
    private val _extras = MutableStateFlow<TitleExtras?>(null)
    val extras: StateFlow<TitleExtras?> = _extras.asStateFlow()

    /** The coloured rating badges (IMDb / Rotten Tomatoes / Metacritic /
     *  Letterboxd / TMDB) for the current title. Filled in two steps: the TMDB
     *  badge the details lookup already produced first, then whatever the
     *  review-site lookup found (see [Ratings]). Empty until then — the row
     *  simply isn't there. */
    private val _ratings = MutableStateFlow<List<TitleRating>>(emptyList())
    val ratings: StateFlow<List<TitleRating>> = _ratings.asStateFlow()

    /** Streams resolved ahead of time (first episode / movie) so tapping Play
     *  or the first episode starts instantly instead of waiting 20-30s for
     *  extraction. Keyed by the target id.
     *
     *  The storage is [StreamCache] — a PROCESS-WIDE object, not a field here.
     *  This screen is thrown away and recreated every time the user leaves it
     *  (back out of the player, reopen the same title), and a cache that died
     *  with it meant every return re-ran the whole extraction: the "tap Play,
     *  watch Finding the best server… for a minute" report. The list is
     *  TIMESTAMPED because the links providers hand out expire —
     *  4KHDHub/hubcloud's direct links are signed workers.dev URLs whose
     *  `<token>::<sig>` part rotates per mirror. Reusing a list extracted
     *  minutes ago (or during a previous play) handed the player dead links, so
     *  every server 403'd and the app reported "Playback failed" / "No playable
     *  sources found" for a title that plays fine — the classic "it worked the
     *  first time, now it errors" report. */

    private val _streamsReady = MutableStateFlow(false)
    val streamsReady: StateFlow<Boolean> = _streamsReady.asStateFlow()

    /** Growing list of sources found SO FAR for the current lookup, re-emitted
     *  after every provider answers — lets the UI start playback the instant
     *  the first server appears instead of waiting for all providers. */
    private val _liveStreams = MutableStateFlow<List<StreamSource>>(emptyList())
    val liveStreams: StateFlow<List<StreamSource>> = _liveStreams.asStateFlow()

    /**
     * Straight-to-the-player hand-off for servers that arrive LATE, set by the
     * play flow for the session it is playing.
     *
     * The screen's own live collector is cancelled the moment its pass ends, so
     * it can never forward a server found afterwards — and servers ARE still
     * being found afterwards: a pass that ran out of budget hands the
     * extensions it never reached to a background sweep that keeps searching
     * while the video plays (see
     * [com.hikari.app.data.ContentRepository.startSweepIfNeeded]). The feed
     * every provider response flows through calls this sink, so a late find
     * reaches the player's "Select server" list exactly like one that arrived
     * in time.
     */
    @Volatile
    var liveSink: (suspend (List<StreamSource>) -> Unit)? = null

    /** How many addons were asked for sources on the last lookup. */
    private val _searchedProviders = MutableStateFlow(0)
    val searchedProviders: StateFlow<Int> = _searchedProviders.asStateFlow()

    /** Reason the last lookup came up empty (origin addon's message). */
    private val _streamError = MutableStateFlow<String?>(null)
    val streamError: StateFlow<String?> = _streamError.asStateFlow()

    /** The set of addons asked for sources, Stremio-style: every installed
     *  Stremio addon plus the origin provider itself (so CS3 plugins and
     *  universal scrapers keep their own pipeline), plus any Nuvio provider
     *  that could resolve the item to a TMDB id. */
    private fun streamTargets(item: MediaItem): List<ContentProvider> =
        manager.providers.value.filter {
            it.config.enabled &&
                (it.config.type == ProviderType.STREMIO ||
                    it.config.id == item.providerId ||
                    // SkyStream plugins carry their own search, so every
                    // installed one is asked by title through the cross pass
                    // (see ContentRepository.crossExtensionTargets).
                    // SkyStream plugins and Aniyomi extensions carry their own
                    // search, so every one installed is asked by title through
                    // the cross pass (see ContentRepository.crossExtensionTargets).
                    it.config.type == ProviderType.SKYSTREAM ||
                    it.config.type == ProviderType.ANIYOMI ||
                    (it.config.type == ProviderType.NUVIO &&
                        com.hikari.app.nuvio.TmdbResolver.isLikelyResolvable(item)))
        }

    private fun recordOutcome(result: List<StreamSource>, item: MediaItem) {
        _searchedProviders.value = streamTargets(item).size
        // The servers' own names are the only real quality data this app ever
        // sees ("HdHub 4K", "NetMirror 720p"), so the completed search files the
        // best of them for this title — the poster's optional quality badge
        // reads it back (see [com.hikari.app.data.TitleQuality]).
        if (result.isNotEmpty()) {
            runCatching { com.hikari.app.data.TitleQuality.remember(item, result) }
        }
        if (result.isEmpty()) {
            // Attribute the failure to the ORIGIN provider only — a global
            // "last error" from a different video (e.g. iStreamFlare on the
            // hstream title) used to leak into every other extension's "no
            // sources" message and made the whole app look broken.
            val origin = manager.byId(item.providerId)
            val originMessage = when (origin?.config?.type) {
                ProviderType.STREMIO ->
                    com.hikari.app.providers.StremioAddon.streamErrors[item.providerId]
                ProviderType.CS3 ->
                    com.hikari.app.cs3.Cs3MainApiProvider.streamErrors[item.providerId]
                ProviderType.HIKARI ->
                    com.hikari.app.providers.HikariProviderAdapter.streamErrors[item.providerId]
                ProviderType.UNIVERSAL ->
                    com.hikari.app.providers.UniversalScraper.streamErrors[item.providerId]
                ProviderType.NUVIO ->
                    com.hikari.app.nuvio.NuvioScraper.streamErrors[item.providerId]
                ProviderType.SKYSTREAM ->
                    com.hikari.app.skystream.SkyStreamProvider.lastOutcome[item.providerId]
                        ?: com.hikari.app.skystream.SkyStreamProvider.streamErrors[item.providerId]
                ProviderType.ANIYOMI ->
                    com.hikari.app.aniyomi.AniyomiProvider.lastOutcome[item.providerId]
                        ?: com.hikari.app.aniyomi.AniyomiProvider.streamErrors[item.providerId]
                ProviderType.VEGA ->
                    com.hikari.app.providers.vega.VegaProvider.streamErrors[item.providerId]
                ProviderType.ZANGETSU ->
                    com.hikari.app.zangetsu.ZangetsuProvider.streamErrors[item.providerId]
                ProviderType.SORA ->
                    com.hikari.app.sora.SoraProvider.streamErrors[item.providerId]
                ProviderType.ANYMEX ->
                    com.hikari.app.anymex.AnymexProvider.streamErrors[item.providerId]
                ProviderType.ANYMEX_MANGA ->
                    com.hikari.app.anymex.AnymexMangaProvider.lastOutcome[item.providerId]
                ProviderType.IPTV ->
                    com.hikari.app.providers.IptvProvider.iptvErrors[item.providerId]
                else -> null
            }
            // A Cloudflare wall is never surfaced here: the Home screen reports
            // it in Hikari's own words (see CloudflareVerifier) and the raw
            // extension wording ("Cloudflare blocked. Go to Settings…") must
            // not leak into the server list or the player's diagnostics.
            _streamError.value = originMessage?.takeIf {
                it.isNotBlank() && !com.hikari.app.net.CloudflareVerifier.isVerificationMessage(it)
            }
        } else {
            _streamError.value = null
        }
    }

    fun load(providerId: String, type: MediaType, mediaId: String, title: String, posterUrl: String?, rawType: String) {
        // Keeps the page's meta/episode/source work alive if the user leaves the
        // app (see [com.hikari.app.work.BackgroundWork]) — the process would
        // otherwise be frozen mid-fetch. The token also carries the way to STOP
        // this page's work: when the user closes the app every registered task is
        // cancelled directly rather than left running for minutes under a
        // "Hikari keeps running…" notification (see BackgroundWork.cancelAll).
        var loadJob: kotlinx.coroutines.Job? = null
        val work = com.hikari.app.work.BackgroundWork.begin(
            "Opening \"${title.take(60)}\"",
        ) { loadJob?.cancel() }
        loadJob = viewModelScope.launch {
            _loading.value = true
            _error.value = null
            _streamsReady.value = false
            _streamError.value = null
            _episodes.value = null
            _episodesLoaded.value = false
            _related.value = emptyList()
            _similar.value = emptyList()
            _extras.value = null
            // The provider this page was opened from may no longer exist: an
            // extension can be renamed or removed, and a CloudStream plugin
            // re-registering its providers REINDEXES them (its stored ids are
            // `cs3|<file name>|<index>`), which invalidates ids saved in
            // History, Library and share links. Dead-ending the page on
            // "Provider not found" punished the user for that, so find the same
            // title in the installed providers and carry on from there.
            // A TMDB-sourced row arrives carrying only its DISPLAY name, which
            // the app's TMDB language may have localized ("Vengadores:
            // Endgame"). No extension indexes that name — they all index the
            // original one — so both names are resolved from TMDB up front: the
            // page keeps showing the localized one, and every provider lookup
            // (the remap below, and the whole source search the page is about to
            // start) searches the ORIGINAL one. This is the fix for "I switched
            // the TMDB language and now there is no extension for this movie",
            // which was the search asking 250 repos for a name none of them has.
            // ONLY for a genuine TMDB row. A dead extension id is remapped by
            // name alone — probing TMDB with an extension's own numeric id would
            // invent an unrelated "original title" and then search 250 repos for
            // it, which is far worse than the problem being fixed.
            val isTmdbRow = providerId == "tmdb" || rawType.equals("tmdb", ignoreCase = true)
            val isTrackerRow = providerId.lowercase() in setOf(
                "anilist", "simkl", "mal", "kitsu", "shikimori", "trakt"
            )
            // Tracker library rows are virtual: their provider id identifies the
            // tracker account, not an installed playback extension. Keep the
            // detail page alive and let ContentRepository resolve the title
            // through the installed playback providers.
            // A live TV channel's NAME is not a title any database knows: the
            // English-name lookup below searched TMDB with "Sony TV HD" and
            // "Pardesi TV" for nothing. IPTV items keep their own name as the
            // name every provider lookup uses (see IptvMark).
            val isIptv = com.hikari.app.data.IptvMark.isIptvProvider(providerId)
            val tmdbNames = if (isTmdbRow) {
                withTimeoutOrNull(6_000) {
                    runCatching { TmdbMeta.titlesForId(mediaId, type) }.getOrNull()
                }
            } else null
            val originalName = tmdbNames?.second?.takeIf { it.isNotBlank() }
                ?: if (isIptv) null else englishSearchName(title, isTmdbRow)
            val lookupNames = listOfNotNull(title.takeIf { it.isNotBlank() }, originalName)
                .distinctBy { it.lowercase() }
            // Tracker rows are virtual catalogue entries (AniList/Simkl/MAL/…): their
            // id is the TRACKER's numeric id, which no installed extension can
            // interpret. Remapping one onto an extension kept that numeric id and
            // the extension answered with an unrelated title's details/episodes
            // (wrong show, wrong seasons) — so tracker rows always stay virtual
            // and resolve by title through the installed providers instead.
            val activeProvider = if (manager.byId(providerId) != null || isTrackerRow) {
                providerId
            } else {
                remapMissingProvider(providerId, lookupNames) ?: providerId
            }
            _activeProviderId.value = activeProvider
            if (manager.byId(activeProvider) == null && !isTmdbRow && !isTrackerRow) {
                // Only reached when the remap above could not find the title in
                // any installed provider either — i.e. the extension really is
                // gone. Say what to do about it instead of a bare "not found".
                _error.value =
                    "The extension this title came from is no longer installed. " +
                        "Install it again from Sources & Extensions, or open the " +
                        "title from Search."
                _loading.value = false
                _episodesLoaded.value = true
                return@launch
            }
            // A TMDB row with no matching extension keeps its page. It was NEVER
            // from an extension — it is an imported list, a TMDB catalog, a
            // franchise or a Similar cell — so "the extension this title came
            // from is no longer installed" is not just unhelpful, it is untrue,
            // and it dead-ended the page on top of a record the app already has
            // in full: poster, overview, details, ratings, cast, trailers, the
            // franchise and Related/Similar all come from TMDB and none of them
            // needs a provider. The source search below is not affected either:
            // a lookup for an item whose provider is not installed still asks
            // EVERY other installed extension by title (see ContentRepository's
            // cross pass), so a server is still found whenever one of them
            // carries the title — and when none does, the page reports the
            // ordinary "no playable server found" rather than a claim about an
            // extension that never existed.
            // The catalog row already carries the poster — render the page
            // immediately instead of waiting on the origin's /meta (which may
            // be slow or minimal). rawType keeps the addon's own type string
            // for meta/episode/stream URLs.
            val base = MediaItem(
                activeProvider, mediaId, title, type,
                posterUrl = posterUrl,
                rawType = rawType,
                // Carried so the source search asks the extensions for the name
                // THEY know the title by (see the lookupNames block above).
                originalTitle = originalName.orEmpty(),
            )
            _meta.value = base
            _loading.value = false
            // A fresh page starts on Vega pack 0 / quality row 0 — the provider
            // default — and it is set SYNCHRONOUSLY here so the episode lookup
            // starting below can never read a previous visit's pick for the
            // same title.
            _vegaPacks.value = emptyList()
            _vegaMovieOptions.value = emptyList()
            _vegaPackIndex.value = 0
            _vegaMovieIndex.value = 0
            if (activeProvider.startsWith("vega|")) {
                (manager.byId(activeProvider) as? com.hikari.app.providers.vega.VegaProvider)?.let { p ->
                    p.setSelectedPack(mediaId, 0)
                    p.setSelectedLink(mediaId, null)
                }
            }
            // Movies: start the multi-provider source search NOW — before the
            // origin's /meta and episode fetches — so the first server is
            // already resolving while the page renders. Previously the search
            // only began after meta+episodes landed, which is why tapping Play
            // sat on a spinner while the (slow) providers were still warming up.
            if (type != MediaType.SERIES) {
                startPrefetch(base)
            }
            // The origin's own /meta and the shelf lookups (TMDB extras, cast,
            // trailers, ratings, Related/Similar) are INDEPENDENT of the episode
            // list, so all of them start together: a page used to show its score
            // strip and its Related row only after the episodes had finished
            // (the shelves were launched after the episode await), which is the
            // reported "it shows no IMDb/TMDB rating and no similar titles on
            // some titles". Now the page fills in from three directions at once.
            val metaDeferred = async { runCatching { repo.metaFor(base) }.getOrDefault(base) }
            // IPTV: a live channel gets NO TMDB shelves — no ratings strip, no
            // Cast/Trailers, and above all no Related/Similar row, which for a
            // channel name ("Sony TV HD") returned a shelf of unrelated films.
            // See [com.hikari.app.data.IptvMark].
            if (!com.hikari.app.data.IptvMark.of(base)) {
                launch { loadShelves(metaDeferred.await()) }
            }
            // The 3-second rule: the header and the episode list are on screen
            // within 3 seconds — TMDB-enriched when TMDB answered in time, the
            // extension's own data otherwise. The origin's /meta is usually the
            // slow part (a cold runtime, a Stremio race, an anime enrichment),
            // so the page never waits on it: whatever is ready at 3s is what
            // the episodes are asked for, and the full meta repaints the header
            // when it lands. Guarded by the episode generation, so a stale load
            // can never repaint a newer page.
            val gen = newEpisodeGeneration()
            val fastMeta = withTimeoutOrNull(3_000L) { metaDeferred.await() }
            launch {
                val full = runCatching { metaDeferred.await() }.getOrNull() ?: return@launch
                if (gen == episodeGeneration) {
                    val cur = _meta.value
                    _meta.value = full.copy(
                        title = base.title.ifBlank { full.title },
                        originalTitle = base.originalTitle.ifBlank { full.originalTitle },
                        // Two or more episodes already proved this is a series
                        // (see below): a late meta must not uncorrect that.
                        type = if (cur?.type == MediaType.SERIES) MediaType.SERIES else full.type,
                    )
                }
            }
            withContext(Dispatchers.IO) {
                // The origin's own meta corrects the item's TYPE before the
                // episode list is asked for — CS3 plugins can label a series page
                // as a movie on their search results (LeakPorner actors are
                // NSFW→MOVIE), and getMeta corrects it from the LoadResponse, so
                // episodes must be fetched against the CORRECTED item
                // (loadResponse is cached, so this stays a single origin fetch).
                // The fetch itself was started above, together with the shelves;
                // [fastMeta] is whatever it had ready at 3 seconds (or the
                // row's own data when it had nothing yet) — the full answer
                // repaints the header above when it lands.
                val meta = fastMeta ?: base
                // THE PAGE NEVER RENAMES ITSELF.
                //
                // The origin's /meta answers with ITS OWN title — the site's
                // (English) name for a site-scraping extension, and the English
                // one for a TMDB row the app remapped onto an extension. So a
                // page opened in the user's chosen language used to flip back out
                // of it the moment the origin's meta landed, and the player's
                // artwork card followed it. The name the page was OPENED with is
                // the one the user tapped (for a TMDB row it is TMDB's localized
                // name), so that is the name it keeps; the provider's meta only
                // fills what the row did not carry. The ORIGINAL name stays on the
                // item either way, because every provider lookup searches with it
                // (see [MediaItem.searchTitle]).
                _meta.value = meta.copy(
                    title = base.title.ifBlank { meta.title },
                    originalTitle = base.originalTitle.ifBlank { meta.originalTitle },
                )
                if (meta.type == MediaType.MOVIE) {
                    // A movie has no episode endpoint. Never enter the episode
                    // loader for it; otherwise the page flashes "Loading
                    // episodes" and can report Episodes (0) after a harmless
                    // provider miss.
                    _episodes.value = emptyList()
                    _episodesLoading.value = false
                    _episodesLoaded.value = true
                } else {
                    _episodesLoading.value = true
                    try {
                        val list = loadEpisodesFor(meta, gen)
                        if (gen == episodeGeneration) {
                            _episodes.value = list
                            // The catalog row never knew the type (UNKNOWN) and
                            // the origin's meta did not say either — but two or
                            // more episodes ARE a series. Correct the item so
                            // the Play button, the Library filing and History
                            // all treat it as one (see [detailIsSeries], which
                            // already rendered it as a series from the list
                            // alone). A single row proves nothing (films are
                            // listed as one entry by some providers), so those
                            // keep whatever type they arrived with.
                            //
                            // And the reverse (see [screenTypeFor]): a
                            // site-scraper "series" with exactly ONE episode is
                            // a film wearing the wrong tag (Series chip, "Ep 1"
                            // pill, Episodes (1) page with Play E1) — it plays
                            // and files as a movie. Nuvio/Stremio/TMDB rows are
                            // exempt inside the helper: their namespaces type
                            // correctly.
                            val cur = _meta.value
                            if (cur != null) {
                                val fixed = screenTypeFor(cur, list.size)
                                if (fixed != cur.type) _meta.value = cur.copy(type = fixed)
                                else if (list.size >= 2 && cur.type != MediaType.SERIES) {
                                    _meta.value = cur.copy(type = MediaType.SERIES)
                                }
                            }
                        }
                    } finally {
                        if (gen == episodeGeneration) {
                            _episodesLoading.value = false
                            _episodesLoaded.value = true
                        }
                    }
                }
                // The movie probe: a site-scraper title whose episode list
                // came back EMPTY is not necessarily a broken series — a
                // single-video page (a film, a JAV release) wears an episode
                // UI it should never have had, and its list lookup fails
                // instead of answering one row. One bounded movie-style
                // server lookup decides it (cache-shared with the prefetch
                // below, so an answered title costs nothing extra): servers
                // mean "plays as a movie" — the type is corrected, which
                // hides the Episodes (0) error, drops the Series chip and
                // the "Ep 1" pills, and makes Play work on the first tap.
                // A SERIES claim is only overturned by the title's OWN
                // provider (a foreign mirror's embeds must never retype
                // it); UNKNOWN claimed nothing, so any servers settle it.
                // No servers: a genuinely broken series, which keeps the
                // retry UI it already had.
                if (gen == episodeGeneration) {
                    val settled = _meta.value
                    if (settled != null &&
                        settled.type != MediaType.MOVIE &&
                        _episodes.value.isNullOrEmpty() &&
                        _episodesFailed.value &&
                        isSiteScraperProviderId(settled.providerId)
                    ) {
                        val probe = runCatching {
                            withTimeoutOrNull(MOVIE_PROBE_MS) {
                                resolveStreams(settled.copy(type = MediaType.MOVIE), null)
                            }?.servers
                        }.getOrNull().orEmpty()
                        if (probe.isNotEmpty() &&
                            (settled.type == MediaType.UNKNOWN ||
                                probe.any { it.providerId == settled.providerId }) &&
                            gen == episodeGeneration
                        ) {
                            _meta.value = settled.copy(type = MediaType.MOVIE)
                            _episodesFailed.value = false
                            com.hikari.app.data.Logs.log(
                                "Episodes",
                                "\"${settled.title}\" plays movie-style " +
                                    "(${probe.size} server(s)) — corrected to movie",
                            )
                        }
                    }
                }
            }
            // Close the IO scope before continuing with the page-level work.
            // Without this brace all following ViewModel methods became local
            // functions, which cascaded into hundreds of misleading unresolved
            // references and caused the build failures from 889 onward.
            val item = _meta.value ?: base
            // The Vega picker rows (packs / quality rows) arrive on their own —
            // the page never waits for them, and pack 0 is already showing.
            viewModelScope.launch { loadVegaPacks(item) }
            // (The shelves — ratings, cast, trailers, Related/Similar — were
            // started above, in parallel with the episode list: they are a bonus
            // that must never gate the page, but they must also never wait for
            // the episodes to finish before they begin.)
            startPrefetch(item)
        }
        loadJob?.invokeOnCompletion { com.hikari.app.work.BackgroundWork.end(work) }
    }

    /**
     * The episode list for [item], asked again when it comes back empty.
     *
     * An empty answer used to be FINAL for the whole screen: one cold plugin
     * runtime (an Aniyomi extension pays an APK class load before its first
     * answer; a .hiki/.cs3 plugin spins its runtime and its site session up) or
     * one dropped request put "Episodes (0) — No episode list available." on a
     * series that plainly has episodes — and only closing and reopening the app
     * cleared it, which is precisely the reported "sometimes I click a series and
     * it shows no episode, and it is fine after I restart the app": the retry the
     * user performed by hand was really the FIRST attempt against a warm runtime.
     *
     * So the lookup is retried here instead, with a pause long enough for a cold
     * runtime to finish coming up. When even the retries come back empty,
     * [episodesFailed] is set and the page offers a tappable retry rather than a
     * sentence that reads like the show has no episodes at all.
     *
     * Cancellation is NEVER swallowed. `runCatching` around this call used to do
     * exactly that: a screen left mid-load wrote an empty list, i.e. a
     * "no episodes" verdict for a lookup that had simply been cancelled.
     */
    private suspend fun loadEpisodesFor(item: MediaItem, gen: Int): List<Episode> {
        // A movie (or an item whose type is still unknown) has no episode list
        // to retry — one ask, and its answer stands.
        // episodesFor() already performs a bounded parallel fallback. Repeating
        // that whole sweep three times could keep the page in "Loading episodes…"
        // for minutes; the explicit Try again action is the retry path.
        val tries = 1
        var got: List<Episode> = emptyList()
        for (attempt in 1..tries) {
            val at = System.currentTimeMillis()
            got = try {
                repo.episodesFor(item) { partial ->
                    // A list exists NOW (the extension answered, or a borrowed
                    // one landed mid-sweep): paint it immediately and keep the
                    // spinner running for the finishing touches. This is what
                    // makes the episodes appear in about a second instead of
                    // after the whole lookup — including the TMDB name pass —
                    // had finished.
                    if (partial.isNotEmpty() && gen == episodeGeneration) {
                        _episodes.value = partial
                    }
                }.orEmpty()
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                com.hikari.app.data.Logs.log(
                    "Episodes",
                    "\"${item.title}\" episode list threw on attempt $attempt " +
                        "(${t.javaClass.simpleName}: ${t.message})",
                )
                emptyList()
            }
            if (gen != episodeGeneration) return got
            if (got.isNotEmpty()) {
                if (attempt > 1) {
                    com.hikari.app.data.Logs.log(
                        "Episodes",
                        "\"${item.title}\": ${got.size} episode(s) on attempt $attempt — " +
                            "the first ${attempt - 1} attempt(s) came back empty",
                    )
                }
                _episodesFailed.value = false
                return got
            }
            com.hikari.app.data.Logs.log(
                "Episodes",
                "\"${item.title}\": no episode list on attempt $attempt of $tries " +
                    "(${System.currentTimeMillis() - at}ms, type ${item.type})",
            )
            // Nothing on this attempt: the page keeps its spinner (an empty
            // verdict here is what read as "this show has 0 episodes" over a
            // series that was still being resolved).
            if (gen != episodeGeneration) return got
            if (attempt < tries) delay(EPISODE_LOAD_PAUSE_MS * attempt)
        }
        // Empty after every attempt: the extension could not answer. The page
        // says so (and offers a retry) instead of implying the series has none.
        if (gen == episodeGeneration) _episodesFailed.value = true
        return got
    }

    /** Re-runs the episode lookup for the page on screen — the page's own
     *  "couldn't load the episode list" tap. */
    fun retryEpisodes() {
        reloadEpisodes()
    }

    /** The episode lookup, restarted — used by [retryEpisodes] and by a Vega
     *  pack switch (which is a different list, not a filter over the old one). */
    private fun reloadEpisodes() {
            // Movies never load episodes: answer instantly so movies never spin on Loading episodes.
            if (_meta.value?.type == MediaType.MOVIE) { _episodesLoading.value = false; _episodesLoaded.value = true; return }
        val item = _meta.value ?: return
        episodeRetryJob?.cancel()
        episodeRetryJob = viewModelScope.launch {
            _episodesLoading.value = true
            val gen = newEpisodeGeneration()
            try {
                val list = loadEpisodesFor(item, gen)
                if (gen == episodeGeneration) {
                    _episodes.value = list
                    // Same type correction as the initial load above: a retry
                    // (or a Vega pack switch) that lands two or more episodes
                    // on an UNKNOWN-typed item proves it is a series — and a
                    // single-episode site-scraper "series" proves it is a film
                    // (see [screenTypeFor]; Nuvio/Stremio/TMDB exempt).
                    val cur = _meta.value
                    if (cur != null) {
                        val fixed = screenTypeFor(cur, list.size)
                        if (fixed != cur.type) _meta.value = cur.copy(type = fixed)
                        else if (list.size >= 2 && cur.type != MediaType.SERIES) {
                            _meta.value = cur.copy(type = MediaType.SERIES)
                        }
                    }
                }
            } finally {
                if (gen == episodeGeneration) {
                    _episodesLoading.value = false
                    _episodesLoaded.value = true
                }
            }
        }
    }

    /**
     * The Vega packs (and movie quality rows) of the page's title, loaded once
     * its meta has answered. A fresh page starts on pack 0 / row 0 — the Vega
     * app's own default (`LinkList[0]`) — so the default view plays exactly
     * what it always did.
     */
    private suspend fun loadVegaPacks(item: MediaItem) {
        if (!item.providerId.startsWith("vega|")) return
        _vegaPacks.value = runCatching { repo.vegaPacksFor(item) }.getOrDefault(emptyList())
        _vegaMovieOptions.value = runCatching { repo.vegaMovieOptions(item) }.getOrDefault(emptyList())
    }

    /** Picks a Vega series pack and loads ITS episodes (see [reloadEpisodes]). */
    fun setVegaPack(index: Int) {
        val item = _meta.value ?: return
        if (!item.providerId.startsWith("vega|")) return
        if (index == _vegaPackIndex.value) return
        _vegaPackIndex.value = index
        (manager.byId(item.providerId) as? com.hikari.app.providers.vega.VegaProvider)
            ?.setSelectedPack(item.id, index)
        reloadEpisodes()
    }

    /** Picks a Vega movie's quality row — Play resolves through its link. */
    fun setVegaMovie(index: Int) {
        val item = _meta.value ?: return
        if (!item.providerId.startsWith("vega|")) return
        val options = _vegaMovieOptions.value
        if (options.isEmpty()) return
        val at = index.coerceIn(0, options.lastIndex)
        _vegaMovieIndex.value = at
        (manager.byId(item.providerId) as? com.hikari.app.providers.vega.VegaProvider)
            ?.setSelectedLink(item.id, options[at].link)
    }

    private suspend fun loadShelves(item: MediaItem) {
        // Everything here is blocking network + file IO, so it runs off the main
        // thread. Without this the TMDB lookups below were killed by Android's
        // NetworkOnMainThreadException (viewModelScope is the main dispatcher) and
        // silently swallowed by the runCatching calls — which is exactly why the
        // Details block and the Cast/Trailers/Related/Similar rows never showed up.
        withContext(Dispatchers.IO) {
            // Extras first: they are ONE TMDB call (credits+videos+certifications)
            // and carry the details block, so the page fills in fastest this way.
            val ex = runCatching { TmdbMeta.extras(item) }.getOrNull()
            _extras.value = ex
            // Ratings next: a handful of review sites (Wikidata, Rotten Tomatoes,
            // Metacritic, Letterboxd) asked in parallel and each independently
            // optional. TMDB's own score is part of the strip and comes from the
            // call above, so the row has something to show immediately.
            runCatching {
                Ratings.load(
                    item,
                    ex?.details?.imdbId,
                    ex?.details?.rating,
                    ex?.details?.voteCount,
                    // A source that had nothing to say just now is re-asked in
                    // the background; when it finally answers, the strip grows
                    // instead of staying frozen on the first attempt's misses.
                    onUpdate = { _ratings.value = it },
                )
            }.getOrDefault(emptyList()).let { _ratings.value = it }
            _related.value = runCatching { TmdbMeta.related(item) }.getOrDefault(emptyList())
            _similar.value = runCatching { TmdbMeta.similar(item) }.getOrDefault(emptyList())
        }
    }

    /**
     * Extracts streams for one item, sharing the work with every other caller
     * through [StreamCache]: a Play tap made while the page is still prefetching
     * joins the SAME extraction instead of launching a second one — two
     * concurrent loadLinks runs on the same CS3 plugin instance can corrupt its
     * state and make it return "no sources" for a movie that plays fine on its
     * own (and the same applies across a re-created screen).
     */
    private suspend fun resolveStreams(
        item: MediaItem,
        ep: Episode?,
        onProgress: (suspend (List<StreamSource>) -> Unit)? = null,
        /** Ignore the prefetch cache and run the providers again. Set by the
         *  player when every server it was given turned out to be dead. */
        force: Boolean = false,
        /** Fires when the title's OWN provider has answered (see
         *  ContentRepository.streamsForInner). It no longer releases any hold —
         *  playback starts on the first server, whatever the origin is doing —
         *  but the signal is still passed through and logged, so the log says
         *  whether and when the origin answered. */
        onOriginSettled: (() -> Unit)? = null,
    ): StreamLookup {
        val key = cacheKey(item, ep)
        val cached = StreamCache.get(key)
        if (cached != null) {
            // Fresh enough to trust: serve it with no network at all (this is
            // what makes a Play tap instant right after the detail page opened).
            // A fresh list is safe to mirror onto the live feed, because those
            // signed links still work.
            val fresh = System.currentTimeMillis() - cached.at < STREAM_CACHE_TTL_MS
            // Only a FRESH, NON-EMPTY list is trusted without asking anyone. An
            // empty entry can no longer exist (StreamCache.put refuses to store
            // one), but the guard stays explicit: serving emptiness back as a
            // "hit" is exactly the bug that made the next taps of the same title
            // fail instantly instead of searching.
            if (!force && fresh && cached.list.isNotEmpty()) {
                // Scoped to the current Server-search switches: the cache is
                // keyed by title, not by scope, so a wider earlier lookup's
                // sibling-engine servers must not serve a scope that says
                // "only this extension" (see ContentRepository.filterToScope).
                val scoped = repo.filterToScope(item, cached.list)
                if (scoped.isEmpty()) {
                    com.hikari.app.data.Logs.log(
                        "Search",
                        "cache hit \"${item.title}\" (fresh) → ${cached.list.size} servers, " +
                            "none inside the current scope — re-extracting",
                    )
                } else {
                    com.hikari.app.data.Logs.log(
                        "Search",
                        "cache hit \"${item.title}\" (fresh) → ${scoped.size} servers",
                    )
                    _liveStreams.value = scoped
                    return StreamLookup(scoped, complete = true)
                }
            }
            // Stale or forced: the signed links in there are very likely dead.
            // They are deliberately NOT put on the live feed — whatever lands
            // on the feed first is what an instant-play tap starts on, so
            // seeding the feed with expired links is exactly the "server
            // failed, trying next … every server failed, tap Play again and it
            // works" bug. The fresh extraction below streams the new servers to
            // the feed instead, and the player's title card covers the wait.
            // Callers that track their own "ready" state are still told what we
            // are holding, so the Play button never stalls on a stale entry.
            // (The one thing allowed onto the feed before the search starts is
            // the list that really worked here a few minutes ago — see the block
            // below. That one is backed by an actual play, not by a timestamp.)
            com.hikari.app.data.Logs.log(
                "Search",
                "cache " + (if (force) "forced" else if (cached.list.isEmpty()) "empty" else "stale") +
                    " \"${item.title}\" — re-extracting",
            )
            if (cached.list.isNotEmpty()) onProgress?.invoke(cached.list)
        }
        // The server that worked for this exact title+episode minutes ago goes
        // onto the feed BEFORE the search below starts, so a Play tap begins
        // playing instead of waiting out a fresh sweep — the servers the
        // extraction finds replace them the moment it answers. Skipped when the
        // caller FORCED the lookup (the player asking for fresh sources because
        // every server it had is dead): putting those same links back would be a
        // failover loop. See [ContentRepository.recentlyFoundStreams].
        if (!force) {
            val recent = repo.recentlyFoundStreams(item, ep)
            if (recent.isNotEmpty()) {
                com.hikari.app.data.Logs.log(
                    "Search",
                    "recent \"${item.title}\" → ${recent.size} server(s) that worked before, " +
                        "starting on them while the fresh pass runs",
                )
                _liveStreams.value = recent
                onProgress?.invoke(recent)
            }
        }
        // Every provider response is mirrored into the live feed so the UI can
        // start playback with the first server found, regardless of which caller
        // kicked off the search (prefetch or Play tap) — and it is also what a
        // BACKGROUND SWEEP's late finds travel through to a player that is
        // already open, so it is built here, before the two join paths below,
        // rather than inside the extraction.
        val feed: (suspend (List<StreamSource>) -> Unit) = { partial ->
            _liveStreams.value = partial
            onProgress?.invoke(partial)
            // …and straight to the player when the play flow has a session up
            // (see [liveSink]): this is the only route a BACKGROUND SWEEP's late
            // finds have to a player that is already playing, because the
            // screen's own collector is stopped as soon as its pass ends.
            liveSink?.invoke(partial)
            // Turn an addon's LINK rows (a YouTube id, a "watch it here" page)
            // into the servers they point at, as they arrive — see
            // [com.hikari.app.cs3.PlayableResolver.warmLinks]. Without this, a
            // link-only addon showed its rows in the source sheet while
            // `DetailScreen.playableEvery` dropped every one of them, which is
            // exactly "the same addon plays in Stremio and says no playable
            // server here". The resolved servers are pushed back through this
            // same feed, so they join the list and reach an already-open player
            // exactly like a provider's own links.
            com.hikari.app.cs3.PlayableResolver.warmLinks(partial) { resolved ->
                if (resolved.isEmpty()) return@warmLinks
                val have = _liveStreams.value
                val known = have.mapTo(HashSet()) { it.infoHash ?: it.url }
                val add = resolved.filterNot { (it.infoHash ?: it.url) in known }
                if (add.isEmpty()) return@warmLinks
                val grown = have + add
                _liveStreams.value = grown
                onProgress?.invoke(grown)
                liveSink?.invoke(grown)
            }
        }
        // Someone (another instance of this screen for the same title, or a
        // prefetch that is still running) already owns this extraction: join it
        // instead of running the providers a second time. BOUNDED: an owner that
        // was cancelled without ever completing its shared deferred would
        // otherwise park every later lookup of this title on `await()` for good,
        // which is the difference between "one slow search" and "this title
        // never searches again".
        //
        // A BACKGROUND SWEEP for this video counts as an owner too. It is
        // already asking every repo the last pass did not finish, on the
        // application scope, with the plugins warm — and every server it finds
        // is streamed to the player through the live session. Starting a whole
        // new pass on top of it is exactly the reported "it says 5 servers and
        // search finished, then on the second tap it searches everything again
        // and shows all of them": the second tap re-ran 250 extensions from
        // scratch while the first tap's own continuation was still running. So
        // when there is already something to play (the ledger the sweep keeps
        // up to date, or the live feed), the caller gets it marked "not a
        // verdict" and the sweep keeps adding servers to it.
        //
        // Joining also means REGISTERING [feed] as a sink of that sweep — the
        // sweep's other sinks belong to the pass that started it, and that pass's
        // live session is not this caller's. Without this the joined list would
        // be a snapshot that never grows, and a tap made while a sweep was
        // running would look like a search that stopped the moment it returned.
        if (!force) {
            val joined = _liveStreams.value.takeIf { it.isNotEmpty() }
                ?: repo.recentlyFoundStreams(item, ep)
            if (ContentRepository.sweepBusyFor(item, ep)) {
                if (repo.attachToRunningSweep(item, ep, feed)) {
                    com.hikari.app.data.Logs.log(
                        "Search",
                        "join \"${item.title}\" — its background sweep is still working " +
                            "(${joined.size} server(s) so far, more on the way)",
                    )
                    return StreamLookup(joined, complete = false)
                }
            }
        }
        // Another lookup (usually the detail-page prefetch) may already own
        // this extraction. Do not merely wait on its completion: that owner is
        // publishing partial servers through _liveStreams while it works. A Play
        // tap that joined the pending extraction used to sit on the player's
        // "Searching…" card until the entire owner finished, even though a server
        // had already arrived in the shared live feed. Forward that feed to THIS
        // lookup's onProgress callback so the player can start immediately, while
        // still sharing the single extraction and avoiding duplicate provider work.
        suspend fun awaitShared(pending: CompletableDeferred<StreamLookup>): StreamLookup {
            val seed = _liveStreams.value
            if (seed.isNotEmpty()) onProgress?.invoke(seed)
            val result = withTimeoutOrNull(JOIN_WAIT_MS) {
                kotlinx.coroutines.coroutineScope {
                    val forward = if (onProgress != null) {
                        launch {
                            _liveStreams.collect { partial ->
                                if (partial.isNotEmpty()) onProgress(partial)
                            }
                        }
                    } else null
                    try {
                        pending.await()
                    } finally {
                        forward?.cancel()
                    }
                }
            }
            return result
                ?: StreamLookup(
                    _liveStreams.value.takeIf { it.isNotEmpty() } ?: emptyList(),
                    complete = false,
                )
        }

        StreamCache.joined(key)?.let { pending ->
            return awaitShared(pending)
        }
        val deferred = CompletableDeferred<StreamLookup>()
        if (!StreamCache.claim(key, deferred)) {
            val pending = StreamCache.joined(key)
                ?: return StreamLookup(emptyList(), complete = false)
            return awaitShared(pending)
        }
        try {
            val lookup = withContext(Dispatchers.IO) {
                repo.streamsForOutcome(item, ep, feed, onOriginSettled)
            }
            val result = lookup.servers
            if (result.isEmpty() && !lookup.complete) {
                // The pass was CUT SHORT — it never reached an answer, so it must
                // not be handed out (or remembered) as one. Give whoever is
                // waiting whatever the live feed already produced, explicitly
                // marked "not a verdict", so a joiner/retry asks again instead of
                // being told this title has no servers.
                val partial = cached?.list?.takeIf { it.isNotEmpty() }?.let { repo.filterToScope(item, it) }?.takeIf { it.isNotEmpty() }
                    ?: _liveStreams.value.takeIf { it.isNotEmpty() }
                    ?: emptyList()
                val early = StreamLookup(partial, complete = false)
                deferred.complete(early)
                return early
            }
            if (result.isEmpty()) {
                // Never downgrade. A re-extraction can legitimately come back
                // empty — every provider failing or timing out on the retry —
                // even though the progressive feed, which the player is
                // ALREADY being handed servers through, got a full list
                // moments earlier. Handing back (and caching) an empty list
                // here made the player's source sheet look like it had stopped
                // loading halfway, and made the next Play tap report "no
                // servers" for the whole cache window even though the servers
                // were still good. Prefer whatever we already have: the cached
                // list first, else the live feed. The cache is deliberately NOT
                // rewritten, so its old timestamp stands and the next lookup
                // tries the providers again instead of trusting a dead list.
                val fallback = cached?.list?.takeIf { it.isNotEmpty() }?.let { repo.filterToScope(item, it) }?.takeIf { it.isNotEmpty() }
                    ?: _liveStreams.value.takeIf { it.isNotEmpty() }
                if (fallback != null) {
                    // This list IS this title's server list as far as the user
                    // is concerned (it is what the player is playing), so the
                    // quality it carries has to be filed like any other answer:
                    // returning here without recording it is why a title that
                    // was played yesterday showed no quality badge on its poster
                    // even with the badge switched on.
                    recordOutcome(fallback, item)
                    val answered = StreamLookup(fallback, complete = true)
                    deferred.complete(answered)
                    return answered
                }
            }
            StreamCache.put(key, result)
            // An empty result is not put on the live feed: the feed is what the
            // player starts on, and blanking a list it is already playing from
            // is the one thing that can stop playback dead here.
            if (result.isNotEmpty()) _liveStreams.value = result
            recordOutcome(result, item)
            val done = StreamLookup(result, complete = true)
            deferred.complete(done)
            return done
        } catch (e: Throwable) {
            // Same never-downgrade rule for a cancelled or failed extraction:
            // complete the shared deferred with whatever the live feed already
            // found, flagged as NOT a verdict, so a joiner — the player's own
            // follow-up read, or a second Play tap sharing this extraction — is
            // never wiped back to empty and never told the search came up empty.
            val partial = _liveStreams.value
            deferred.complete(StreamLookup(partial, complete = false))
            throw e
        } finally {
            StreamCache.release(key, deferred)
        }
    }

    /** While the user is still reading the detail page, resolve sources for the
     *  movie or the first episode so the player starts immediately on tap. */
    private suspend fun prefetchFirstStreams(base: MediaItem) {
        val target = if (_episodes.value.isNullOrEmpty()) {
            base to null
        } else {
            val first = _episodes.value!!.sortedWith(compareBy({ it.season }, { it.number })).firstOrNull()
            if (first == null) return
            base to first
        }
        val (item, ep) = target
        val key = cacheKey(item, ep)
        val cached = StreamCache.get(key)
        // Reuse a NON-EMPTY, still-fresh cache; anything else (empty result from
        // a minute ago, or a stale list whose signed links have since expired)
        // falls through to a real extraction so the tap that follows has live
        // links ready.
        if (cached != null && cached.list.isNotEmpty() &&
            System.currentTimeMillis() - cached.at < STREAM_CACHE_TTL_MS) {
            // A cached answer is still an answer: file the quality it carries, so
            // opening a title that was searched a minute ago (the common case —
            // every re-entry into a detail page) keeps the poster's badge alive
            // instead of only ever recording the FIRST, uncached search.
            recordOutcome(cached.list, item)
            _streamsReady.value = true
            return
        }
        // Set "ready" as soon as the FIRST source arrives (not only after every
        // provider has been searched), so the Play button lights up early while
        // the slower providers keep adding servers in the background.
        resolveStreams(item, ep, onProgress = { partial ->
            if (partial.isNotEmpty()) _streamsReady.value = true
        })
        _streamsReady.value = true
    }

    /** Warms one NON-first episode's servers (the continue episode — the page
     *  prefetch above only warms the first), so its Resume tap is a cache hit.
     *  Same sharing as every other lookup (see [resolveStreams]). */
    fun prefetchEpisode(base: MediaItem, ep: Episode) {
        viewModelScope.launch {
            val key = cacheKey(base, ep)
            val cached = StreamCache.get(key)
            if (cached != null && cached.list.isNotEmpty() &&
                System.currentTimeMillis() - cached.at < STREAM_CACHE_TTL_MS
            ) {
                return@launch
            }
            resolveStreams(base, ep, onProgress = { partial ->
                if (partial.isNotEmpty()) _streamsReady.value = true
            })
        }
    }

    private fun cacheKey(item: MediaItem, ep: Episode?): String =
        item.providerId + "|" + item.id + "|" + (ep?.id ?: "")

    /** Is a BACKGROUND continuation sweep still asking the repos this video's
     *  last source pass never reached? The pass hands every repo it ran out of
     *  time for to a sweep that keeps searching on the application scope while
     *  the video plays (see ContentRepository.startSweepIfNeeded), so while one
     *  is alive the search is NOT over and the screen must never announce "no
     *  playable server found after searching N extensions" — that verdict is
     *  exactly what made a search that was still running look like it had
     *  stopped at the 5th or 13th extension. */
    fun backgroundSweepBusy(episode: Episode?): Boolean {
        val m = _meta.value ?: return false
        return ContentRepository.sweepBusyFor(m, episode)
    }

    /** [getStreamsLookup]'s servers, for callers that only want the list. */
    suspend fun getStreams(
        episode: Episode?,
        onProgress: (suspend (List<StreamSource>) -> Unit)? = null,
        /** Re-run the providers even if a cached list exists — used when the
         *  player reports that every server it was given is dead. */
        force: Boolean = false,
    ): List<StreamSource> = getStreamsLookup(episode, onProgress, force).servers

    /**
     * [getStreams] plus whether the lookup actually FINISHED — see
     * [StreamLookup]. The play flow uses this to tell "the search ran and found
     * nothing" (a real answer, report it) apart from "the search was cut short"
     * (knows nothing about the title, so retry instead of telling the user there
     * are no servers).
     */
    suspend fun getStreamsLookup(
        episode: Episode?,
        onProgress: (suspend (List<StreamSource>) -> Unit)? = null,
        force: Boolean = false,
        /** Passed through to [resolveStreams]: fires when the title's own
         *  provider has answered, which is what releases the player's hold. */
        onOriginSettled: (() -> Unit)? = null,
    ): StreamLookup {
        // No metadata yet means we could not search at all: not a verdict.
        val m = _meta.value ?: return StreamLookup(emptyList(), complete = false)
        // The provider scan can run for a minute; keep it alive across a
        // background trip (see [com.hikari.app.work.BackgroundWork]).
        val work = com.hikari.app.work.BackgroundWork.begin(
            "Finding servers for \"${m.title.take(60)}\""
        )
        try {
            return resolveStreams(m, episode, onProgress, force, onOriginSettled)
        } finally {
            com.hikari.app.work.BackgroundWork.end(work)
        }
    }

    /** New play session (a fresh tap of Play / a new episode): clear the live
     *  feed so stale servers from a previous lookup never leak into the next. */
    fun resetLiveStreams() {
        _liveStreams.value = emptyList()
    }
}

/** One diagnostic line per extension for the sources sheet's empty state:
 *  what each searched addon actually reported ("✓ 3 sources", "✗ timeout",
 *  "✗ cut off after 110s", …). Null when the addon has no recorded outcome. */
private fun providerOutcomeLine(p: ContentProvider): String? {
    val name = p.config.name
    val msg = when (p.config.type) {
        ProviderType.NUVIO -> com.hikari.app.nuvio.NuvioScraper.lastOutcome[p.config.id]
            ?: com.hikari.app.nuvio.NuvioScraper.streamErrors[p.config.id]
        ProviderType.STREMIO -> com.hikari.app.providers.StremioAddon.streamErrors[p.config.id]
        ProviderType.CS3 -> com.hikari.app.cs3.Cs3MainApiProvider.streamErrors[p.config.id]
        ProviderType.HIKARI -> com.hikari.app.providers.HikariProviderAdapter.streamErrors[p.config.id]
        ProviderType.UNIVERSAL -> com.hikari.app.providers.UniversalScraper.streamErrors[p.config.id]
        ProviderType.SKYSTREAM ->
            com.hikari.app.skystream.SkyStreamProvider.lastOutcome[p.config.id]
                ?: com.hikari.app.skystream.SkyStreamProvider.streamErrors[p.config.id]
        ProviderType.ANIYOMI ->
            com.hikari.app.aniyomi.AniyomiProvider.lastOutcome[p.config.id]
                ?: com.hikari.app.aniyomi.AniyomiProvider.streamErrors[p.config.id]
        ProviderType.VEGA ->
            com.hikari.app.providers.vega.VegaProvider.streamErrors[p.config.id]
        ProviderType.ZANGETSU ->
            com.hikari.app.zangetsu.ZangetsuProvider.streamErrors[p.config.id]
        ProviderType.SORA ->
            com.hikari.app.sora.SoraProvider.streamErrors[p.config.id]
        ProviderType.ANYMEX ->
            com.hikari.app.anymex.AnymexProvider.streamErrors[p.config.id]
        ProviderType.ANYMEX_MANGA ->
            com.hikari.app.anymex.AnymexMangaProvider.lastOutcome[p.config.id]
        else -> null
    }
    return msg?.takeIf { !com.hikari.app.net.CloudflareVerifier.isVerificationMessage(it) }
        ?.let { "$name: $it" }
}

/** Every INSTALLED nuvio engine's verdict for this search, one line each —
 *  including the ones that never got an engine slot, which is the whole point:
 *  "in nuvio every plugin shows servers, in hikari only 2-3" is only
 *  diagnosable if the engines that produced nothing are visible. [NuvioScraper]
 *  records an outcome for every provider it actually ran (sources, no sources,
 *  error), and its maps are cleared at the start of a search, so a provider
 *  with no entry was never asked. */
private fun nuvioReportLines(providers: List<ContentProvider>): List<String> {
    val nuvio = providers.filter { it.config.type == ProviderType.NUVIO && it.config.enabled }
    if (nuvio.isEmpty()) return emptyList()
    val answered = nuvio.count { com.hikari.app.nuvio.NuvioScraper.lastOutcome.containsKey(it.config.id) }
    val withServers = nuvio.count {
        com.hikari.app.nuvio.NuvioScraper.lastOutcome[it.config.id]?.startsWith("✓") == true
    }
    return listOf(
        "Nuvio engines: $answered of ${nuvio.size} answered, $withServers found servers"
    ) + nuvio.map { p ->
        val outcome = com.hikari.app.nuvio.NuvioScraper.lastOutcome[p.config.id]
            ?: com.hikari.app.nuvio.NuvioScraper.streamErrors[p.config.id]
            ?: "never ran — no engine slot before the search ended"
        p.config.name + ": " + outcome
    }
}

/**
 * REMOVED: how long a replay waited for the server it was last played with to
 * appear before falling back to the first server found.
 *
 * The wait is gone (see the note where the source list is ordered): a
 * remembered server is still preferred — it is placed directly
 * after the origin's own rows and the player's own start-index rule picks it —
 * but it can no longer delay playback. "Play as soon as ONE server is found"
 * means exactly that.
 */

/** How long a lookup will join an extraction someone else already owns before
 *  giving up on it (see [resolveStreams]). A shared extraction that was
 *  cancelled without completing its deferred used to hang every later lookup of
 *  the same title here, forever. */
private const val JOIN_WAIT_MS = 90_000L

/** Hard ceiling on the final sources read of a play tap. The cross-extension
 *  pass has its own budget, but this is the outer guarantee: when it expires the
 *  play flow stops asking (the `finally` below reports the outcome — an
 *  unfinished pass is reported as unfinished, never as "no servers found") so the
 *  player's cover does not sit on "still searching" while an unbounded provider
 *  combination works on it. */
private const val STREAMS_FINAL_CAP_MS = 80_000L

/** How many extra times the final sources read may ask again when a pass comes
 *  back without a verdict (see [StreamLookup]). Each retry either JOINS the pass
 *  that is still running for this title via [StreamCache] or, when that pass
 *  really died, starts it once more — so this is a bounded "try again", not a
 *  way to re-run every extension in a loop.
 *
 *  Eight, because a cold origin burns whole passes in milliseconds (see the
 *  warm pause in [ContentRepository.fetchStreams]): three retries were spent
 *  before the runtime had come up, and the 4th–9th manual taps were what found
 *  the servers. The 80s cap above still binds the total. */
private const val STREAMS_FINAL_RETRIES = 8

/** Breather between those retries, so a pass that keeps dying does not spin. */
private const val SEARCH_RETRY_PAUSE_MS = 1_500L

/** How long the play flow keeps a session open waiting for a BACKGROUND SWEEP
 *  to finish before it declares the search over regardless. Mirrors
 *  ContentRepository's own sweep ceiling (two 2-minute rounds) plus a minute of
 *  slack, so the watcher never gives up on a sweep that is still legitimately
 *  working — and, just as important, never keeps "still searching" on screen
 *  after the whole background re-ask has run out of time. The old 11 minutes
 *  meant the Sources panel could honestly read "still searching" for the better
 *  part of an hour while the film played, which reads as a stuck search. */
private const val SWEEP_WATCH_CAP_MS = 5 * 60 * 1000L

/** How long the list of servers has to stay UNCHANGED before a lookup that never
 *  reached a verdict is declared over.
 *
 *  A lookup can come back with servers but no verdict — it was cut short, or it
 *  joined a pass that is still running (the detail screen's own prefetch pass,
 *  another screen's lookup) and gave up waiting for it. That other pass is still
 *  pushing its servers into this very session, which is why the count on the
 *  cover used to say "5 servers — search finished" and then be 43 a minute
 *  later. So instead of announcing a verdict the flow does not have, the cover
 *  keeps saying the search is still going and simply watches the list: n servers
 *  arriving keeps it alive, and this much silence ends it. Long enough that a
 *  slow extension's answer is never mistaken for the end (several minutes of
 *  nothing IS the end, at that point), and short enough that a genuinely dead
 *  tail does not leave the player waiting out its own safety timeout. */
private const val STREAMS_QUIET_MS = 12_000L

/**
 * REMOVED: the "wait for the extension this title was opened from" window.
 *
 * Playback used to hold its first start until the provider the title was opened
 * FROM had answered (`ORIGIN_PLAY_GRACE_MS` = 45s in "wait for more servers
 * first" mode, a 20s backstop and a 3s head start in "play as soon as the first
 * server is found"), on the theory that tapping a movie inside an extension
 * should play THAT extension's link. It bought a marginal ordering preference
 * and cost the one thing the user reported over and over: *"N servers found and
 * it is still not playing"* — most recently with 56 servers on the list and the
 * cover still reading "still searching". The ordering it was protecting is kept
 * WITHOUT the wait: the pass asks the origin first, its servers are placed at
 * the top of the list (see [healthyStartIndex] in the player and the ordering in
 * [launchPlayer]), and the remembered server of a replay is preferred the same
 * way. What is gone is only the waiting.
 *
 * The player is still told 0 for both extras (see [launchPlayer]); a player
 * build that predates this change therefore also sees "no hold" and starts on
 * the first server.
 */

/**
 * How long a prefetched source list may be reused before it must be resolved
 * again. 4KHDHub/hubcloud hand out SIGNED, time-limited workers.dev links, and
 * a detail page left open for a few minutes used to replay those dead links on
 * a Play tap (every server 403s → "No playable sources found"). Five minutes
 * is comfortably under the rotation window while still making an immediate
 * Play tap instant. */
private const val STREAM_CACHE_TTL_MS = 300_000L

/** How long a Play tap made while episodes are still loading waits for the
 *  episode list before falling back to a movie-style search. The player is
 *  already open on its title card for the whole wait, so the tap still feels
 *  instant — this only decides which episode the source search runs for. */
private const val EPISODE_WAIT_MS = 25_000L

/**
 * How many times the episode list is asked for before the page accepts that the
 * extension could not answer (see DetailViewModel.loadEpisodesFor).
 *
 * Three, because the failure this exists for is a COLD runtime: the first ask
 * pays an APK class load or a plugin runtime boot and can come back empty simply
 * because nothing was ready, and the second ask — against what the first one
 * warmed — is the one that works. That is exactly the user's own workaround
 * ("it fixed after I closed the app and opened it again"), done here instead of
 * making them do it.
 */
private const val EPISODE_LOAD_TRIES = 3

/** Pause between those attempts. Long enough for a cold runtime to come up,
 *  short enough that a genuinely empty series does not sit on a spinner. */
private const val EPISODE_LOAD_PAUSE_MS = 1_500L

/**
 * How long the movie probe may run (see DetailViewModel.load): a
 * site-scraper title whose episode list came back empty gets one bounded
 * movie-style server lookup before the page settles on "broken series".
 * Shares its work with the page prefetch through StreamCache, so when the
 * prefetch already answered this is a cache hit, not a second search.
 */
private const val MOVIE_PROBE_MS = 30_000L

/**
 * Titles an automatic resume has ALREADY been started for, and when.
 *
 * The detail page auto-plays when it is opened FROM WATCH HISTORY with a saved
 * position (see the resume effect), which is what makes "Continue watching" feel
 * like it works. The guard that makes it fire once — `resumeHandled` — lives in
 * the composition, so it only ever covered ONE instance of the screen: the page
 * is re-created constantly in practice (back out of the player, rotate, the
 * process being rebuilt, a fresh navigation from the same History row seconds
 * later), and every one of those creations saw `resumeHandled = false` again,
 * with the episode id and position restored from the same navigation arguments.
 * The result was the reported "I press back and it starts loading the screen all
 * over again with the server search": the player was re-opened, from scratch —
 * and on a title where that happened twice, two player instances deep.
 *
 * So the claim is kept out here, process-wide, and is HONOURED for
 * [TTL_MS]: within that window a re-created page shows the title card and its
 * Play/Resume button instead of starting a search by itself — which is what a
 * user who has just backed out of the player wants. A deliberate tap still
 * plays, of course; this only suppresses the AUTOMATIC resume.
 */
private object AutoResumeGuard {
    /** Long enough to cover a burst of re-creations, short enough that coming
     *  back to the title later still resumes it. */
    private const val TTL_MS = 5 * 60 * 1000L

    private val at = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** True the FIRST time this video asks; false while a recent claim stands. */
    fun claim(key: String): Boolean {
        val now = System.currentTimeMillis()
        at.entries.removeAll { now - it.value >= TTL_MS }
        return at.putIfAbsent(key, now) == null
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    nav: NavHostController,
    providerId: String,
    type: MediaType,
    mediaId: String,
    title: String,
    posterUrl: String? = null,
    rawType: String = "",
    /** Set when arriving from watch history: auto-open this episode on load. */
    episodeId: String = "",
    /** Resume position (ms) from history — forwarded to the player. */
    startPositionMs: Long = 0L,
    /** Poster hold-menu play: fire the primary action once it has settled. */
    autoplay: Boolean = false,
) {
    val vm: DetailViewModel = viewModel()
    val meta by vm.meta.collectAsState()
    val episodes by vm.episodes.collectAsState()
    val episodesLoading by vm.episodesLoading.collectAsState()
    // True once the episode lookup has FINISHED (success or failure). The page
    // keeps its spinner until then, so a lookup that is still running can never
    // be painted as "this series has no episodes".
    val episodesLoaded by vm.episodesLoaded.collectAsState()
    // True when the episode lookup failed outright (every retry came back
    // empty) — the page then says "couldn't load" and offers a retry instead of
    // claiming the series has no episodes (see DetailViewModel.loadEpisodesFor).
    val episodesFailed by vm.episodesFailed.collectAsState()
    // A Vega title's packs / movie quality rows for the pickers below (empty
    // for every other engine). The episodes above are already the picked
    // pack's answer — the picker only switches which pack that is.
    val vegaPacks by vm.vegaPacks.collectAsState()
    val vegaPackIndex by vm.vegaPackIndex.collectAsState()
    val vegaMovieOptions by vm.vegaMovieOptions.collectAsState()
    val vegaMovieIndex by vm.vegaMovieIndex.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val searchedProviders by vm.searchedProviders.collectAsState()
    val streamError by vm.streamError.collectAsState()
    val providers by vm.providers.collectAsState()
    val related by vm.related.collectAsState()
    val similar by vm.similar.collectAsState()
    val extras by vm.extras.collectAsState()
    val ratings by vm.ratings.collectAsState()
    val m = meta
    // When the origin provider no longer exists, the ViewModel remaps this page
    // onto a live provider (see remapMissingProvider). Everything that RECORDS
    // or LOOKS UP state by provider must use that live id — the id this page was
    // opened with is precisely the dead one.
    val activeProviderId by vm.activeProviderId.collectAsState()
    val livePid = activeProviderId.ifBlank { providerId }
    val isVegaPage = livePid.startsWith("vega|")
    val isIptvPage = remember(livePid) { com.hikari.app.data.IptvMark.isIptvProvider(livePid) }
    // Siblings from the same playlist (the reference app's channel grid under
    // its player): same group first, the open channel excluded, capped at 24.
    // Loaded once per channel — a playlist parse is cached by the provider.
    var iptvSiblings by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    LaunchedEffect(livePid, mediaId, isIptvPage) {
        iptvSiblings = emptyList()
        if (!isIptvPage) return@LaunchedEffect
        iptvSiblings = withContext(Dispatchers.IO) {
            runCatching {
                val prov = providers.firstOrNull { it.config.id == livePid }
                    as? IptvProvider ?: return@runCatching emptyList()
                val ref = CatalogRef(
                    prov.config.id, MediaType.MOVIE,
                    IptvProvider.CATALOG_ALL, "All channels"
                )
                val page1 = prov.getCatalog(ref, 1)
                val all = if (page1.size >= 240) page1 + prov.getCatalog(ref, 2) else page1
                if (all.isEmpty()) return@runCatching emptyList<MediaItem>()
                val group = all.firstOrNull { it.id == mediaId }?.overview
                all.filter { it.id != mediaId }
                    .sortedWith(compareBy({ it.overview != group }, { it.title.lowercase() }))
                    .take(24)
            }.getOrDefault(emptyList())
        }
    }

    /**
     * The name the PLAYER's artwork card prints (and the detail page's own
     * full-screen cover).
     *
     * It is the item's own title — which, for a title that came from an
     * EXTENSION, is the site's (usually English) name and therefore the one
     * place the app's TMDB language never reached: the page was translated, the
     * loading card was not. TMDB's localized name for the same title is used
     * whenever it differs, so the card follows the chosen language like
     * everything else.
     */
    val artTitle = extras?.localizedTitle
        ?.takeIf { it.isNotBlank() && it != (m?.title ?: title) }
        ?: (m?.title ?: title)

    /**
     * The description the page shows.
     *
     * TMDB's own summary — asked for in the app's chosen TMDB language, in the
     * same response as the localized title — wins whenever it exists; an
     * extension's own blurb only ever fills a blank. Before this, a page whose
     * title was translated kept the provider's English description underneath it
     * (the reported "the description turns English even though the language is
     * French"), because for anything that did not come from TMDB the only
     * description the app had WAS the provider's English one.
     */
    val displayOverview = extras?.overview?.takeIf { it.isNotBlank() } ?: m?.overview

    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    // Television: the remote's focus starts on this page's Play button, so
    // opening a title and pressing Enter plays it — no hunting for the button
    // with the D-pad first (see com.hikari.app.tv.TvMode). The request itself
    // lives ON the Play row (see below): the row is a LazyColumn item, so it can
    // appear long after this page does, and asking for focus before the row
    // exists would do nothing.
    val playFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    // The score strip (IMDb / RT / …) on the details block is drawn unless the
    // user switched it off in Settings → App Layout: it is on by default,
    // because a title's score is part of what the page is for.
    val detailApp = context.applicationContext as HikariApp
    val detailRatingFlow = remember { detailApp.store.showDetailRatingFlow() }
    val showDetailRating by detailRatingFlow.collectAsState(initial = true)
    // Settings → App Layout → Details header: which shape the page's header art
    // takes (wide banner, poster beside the art, tall cinematic art, poster on a
    // blurred backdrop, or no art at all).
    val detailHeroFlow = remember { detailApp.store.detailHeroStyleFlow() }
    val detailHeroStyle by detailHeroFlow.collectAsState(initial = DetailHeroStyles.WIDE)
    val isTvLayout = com.hikari.app.tv.TvMode.current()
    val tvDetailBigFlow = remember { detailApp.store.tvDetailBigFlow() }
    val tvDetailBig by tvDetailBigFlow.collectAsState(initial = isTvLayout)
    // Full-bleed detail art (Settings → TV & Remote, off by default): the
    // art fills the frame edge to edge — portrait, square and wide alike —
    // instead of a fit strip with blurred bars down both sides.
    val tvBlurFlow = remember { detailApp.store.tvBlurDetailFlow() }
    val tvBlurDetail by tvBlurFlow.collectAsState(initial = false)
    val tvEpisodeSizeFlow = remember { detailApp.store.tvEpisodeSizeFlow() }
    val tvEpisodeSize by tvEpisodeSizeFlow.collectAsState(initial = 100)
    // The viewport scale rides along (see TvUi.scale): a 4K panel gets bigger
    // episode cards AND bigger card text (EpisodeBigCard/EpisodePosterCard read
    // this as their sizeScale), a dense stick screen smaller ones. 1.0 on
    // phones (TvUi.scale answers 1 there), so the phone layout never moves.
    val tvEpisodeScale =
        ((tvEpisodeSize / 100f) * com.hikari.app.tv.TvUi.scale()).coerceIn(0.8f, 1.6f)
    // The hero/detail type scale (see TvUi.scale): 1.0 on phones, so every
    // use below is a phone no-op and the phone layout never moves.
    val tvTypeScale = com.hikari.app.tv.TvUi.scale()
    val heroStyle =
        // Big TV detail: SIDE puts poster left + info/episodes right (matches
        // the living-room layout users expect). TALL was full-width art only.
        if (com.hikari.app.tv.TvMode.current() && tvDetailBig) DetailHeroStyles.SIDE
        else detailHeroStyle
    // How big the title LOGO is drawn (Settings → App Layout → Details header →
    // Title logo size), in percent of the size it has always been drawn at.
    val detailLogoFlow = remember { detailApp.store.detailLogoSizeFlow() }
    val detailLogoPercent by detailLogoFlow.collectAsState(initial = 100)
    // The multi-provider source search must OUTLIVE this screen. Playback now
    // opens the player the instant Play is tapped, and on a memory-tight device
    // (the reported Infinix) the activity behind the player can be torn down
    // while the player is in the foreground — which cancelled a
    // composition-scoped search mid-flight and left the player with nothing.
    // That is the "it just keeps saying loading server and then fails / the
    // search stopped early (LeftCompositionCancellationException)" report. The
    // search therefore runs on the APPLICATION scope (HikariApp.appScope, never
    // cancelled) and only touches this screen's state while it is still alive.
    val screenAlive = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    DisposableEffect(Unit) { onDispose { screenAlive.set(false) } }
    val onUi: suspend (() -> Unit) -> Unit = { block ->
        withContext(Dispatchers.Main.immediate) {
            if (screenAlive.get()) runCatching { block() }
        }
    }

    var showSheet by remember { mutableStateOf(false) }
    var nuvioReportOpen by remember { mutableStateOf(false) }
    // The rating the user tapped in the score strip, or null when no
    // explanation dialog is up. Set by DetailsBlock, cleared by the dialog.
    var ratingInfo by remember { mutableStateOf<TitleRating?>(null) }
    // The full-screen title-card cover shown from the moment the user taps Play
    // until the player activity takes over (Nuvio/Stremio style). It is the
    // instant feedback for a tap, replacing the old bare source sheet.
    var showLoadingBanner by remember { mutableStateOf(false) }
    var selectedEp by remember { mutableStateOf<Episode?>(null) }
    // Resume position for the current play session — applied when the user
    // picks a server from the sheet too, not just on the auto-launched one.
    var pendingStartPos by remember { mutableStateOf(0L) }
    // Saved progress (position, duration) for the video being launched, handed
    // to the player so it can show ITS OWN "continue from where you left off?"
    // prompt in-video. [pendingStartPos] stays for explicit, no-ask seeks.
    var resumeHint by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var streams by remember { mutableStateOf<List<StreamSource>>(emptyList()) }
    var loadingStreams by remember { mutableStateOf(false) }
    /** Live-update session handed to the player: while playback runs, the
     *  ongoing multi-provider search keeps appending servers to it. */
    var sessionId by remember { mutableStateOf("") }
    var selectedSeason by rememberSaveable { mutableStateOf<Int?>(null) }
    var seasonExpanded by remember { mutableStateOf(false) }
    var rangeExpanded by remember { mutableStateOf(false) }
    /**
     * Which way round the episode list runs. Off = oldest first (episode 1 at
     * the top), which is how every provider hands the list over and what most
     * shows want; on = newest first, for the long-running shows where the
     * episode you actually came for is the last one. Toggled by the arrow
     * beside the episode count, and remembered across a rotation.
     */
    var epsNewestFirst by rememberSaveable { mutableStateOf(false) }
    /**
     * Anti-spoiler for the episode lists, the reference TV client's toggle:
     * while on, episodes with no finished watch entry draw a blurred thumb
     * and "Hidden to avoid spoilers" instead of their overview. Finished
     * episodes (the Continue Watching shelf's own finished condition) always
     * show — the point is hiding what has not been seen, never what has.
     */
    var antiSpoiler by rememberSaveable { mutableStateOf(false) }
    // Episode presentation can be switched without leaving the detail page.
    // List mode keeps the compact existing rows; poster mode uses the episode
    // artwork like the TV reference layout.
    // 0 = list rows, 1 = poster grid, 2 = big cinema cards. TV starts on the
    // grid, phones on the list; the header button cycles all three in order.
    // The default comes from Settings → App Layout → Episode UI (-1 = Auto:
    // grid on TV, list on phones). The header button cycles the same three
    // and writes back, so the choice sticks across titles.
    val episodeViewPrefFlow = remember { detailApp.store.episodeViewModeFlow() }
    val episodeViewPref by episodeViewPrefFlow.collectAsState(initial = -1)
    // TV keeps its OWN layout (-1 = Auto = cinema grid): a List choice made on
    // the phone (or in Settings) must never drag the television back to rows.
    // The first cycle on TV writes the TV pref, so a later change sticks.
    val tvEpPrefFlow = remember { detailApp.store.tvEpisodeViewModeFlow() }
    val tvEpPref by tvEpPrefFlow.collectAsState(initial = -1)
    var episodeViewMode by rememberSaveable(isTvLayout) { mutableStateOf(if (isTvLayout) 1 else 0) }
    LaunchedEffect(episodeViewPref, tvEpPref, isTvLayout) {
        val mapped = if (isTvLayout) {
            if (tvEpPref >= 0) tvEpPref
            else if (episodeViewPref < 0) 1 else episodeViewPref
        } else {
            if (episodeViewPref < 0) 0 else episodeViewPref
        }
        if (episodeViewMode != mapped) episodeViewMode = mapped
    }
    val episodePosterStyle = episodeViewMode != 0
    val episodeBigStyle = episodeViewMode == 2
    // CloudStream-style frosted-glass episode cards (Settings → App Layout →
    // Episode UI). One switch for all three layouts — read once here and
    // passed down, so the rows do not each open their own store collection.
    val episodeGlassFlow = remember { detailApp.store.episodeGlassFlow() }
    val episodeGlass by episodeGlassFlow.collectAsState(initial = false)
    fun cycleEpisodeView() {
        val next = (episodeViewMode + 1) % 3
        episodeViewMode = next
        scope.launch {
            runCatching {
                if (isTvLayout) detailApp.store.setTvEpisodeViewMode(next)
                else detailApp.store.setEpisodeViewMode(next)
            }
        }
    }

    // Related/Similar cells. Tapping a cell opens the title directly instead of
    // dropping the user on the Search tab with a bare name query (which lists
    // lookalikes from every extension). The cells come from TMDB, so when the
    // open extension is TMDB-backed the numeric TMDB id IS a valid id for it
    // and the page loads straight away; any other extension needs its own id
    // for the title, so the same lookup its search does runs in the background
    // and the match is opened — still without the Search tab.
    var shelfOpening by remember { mutableStateOf<String?>(null) }
    fun openShelfItem(item: MediaItem) {
        val origin = providers.firstOrNull { it.config.id == livePid }
        if (origin == null || origin.config.type == ProviderType.NUVIO) {
            Routes.safeNavigate(
                nav,
                Routes.detail(
                    livePid, item.type, item.id, item.title, item.posterUrl,
                    rawType = item.rawType.ifBlank { "tmdb" },
                ),
            )
            return
        }
        if (shelfOpening != null) return
        shelfOpening = item.title
        scope.launch {
            val hit = withContext(Dispatchers.IO) {
                runCatching {
                    val hits = withTimeoutOrNull(15_000) { origin.search(item.searchTitle, 1) }.orEmpty()
                    hits.firstOrNull { it.type == item.type } ?: hits.firstOrNull()
                }.getOrNull()
            }
            shelfOpening = null
            if (hit != null) {
                Routes.safeNavigate(
                    nav,
                    Routes.detail(hit.providerId, hit.type, hit.id, hit.title, hit.posterUrl, hit.rawType),
                )
            } else {
                Routes.safeNavigate(nav, Routes.searchInProvider(livePid, item.title))
            }
        }
    }

    val sortedEps = remember(episodes) {
        episodes.orEmpty().sortedWith(compareBy({ it.season }, { it.number }))
    }
    val seasons = remember(sortedEps) { sortedEps.map { it.season }.distinct().sorted() }
    // The season the list is currently showing. Defaults to the first season —
    // a multi-season show must never dump every episode of every season into
    // one flat list. When the show has a single season the picker is hidden.
    val activeSeason = selectedSeason?.takeIf { it in seasons } ?: seasons.firstOrNull() ?: 1
    // Only one season → show everything; more than one → show just the picked
    // season, so a 5-season show no longer floods the list with 100+ rows.
    val shownEps = remember(sortedEps, seasons, activeSeason) {
        if (seasons.size <= 1) sortedEps else sortedEps.filter { it.season == activeSeason }
    }
    // The arrow's order, applied to the SEASON's list before it is paged: on a
    // 600-episode donghua "newest first" has to mean the newest 30 make up the
    // FIRST page, not that the reader scrolls to the bottom to find them.
    val orderedEps = remember(shownEps, epsNewestFirst) {
        if (epsNewestFirst) shownEps.asReversed() else shownEps
    }
    // Episode pagination: a long-running donghua can have 600+ episodes in a
    // single season, which used to force one enormous scroll. Split the current
    // season into 30-episode pages and expose a page picker (just like the
    // season picker) right next to the episode count. `remember(activeSeason,
    // epsNewestFirst)` snaps back to page 1 whenever the user switches season or
    // flips the order — page 3 of the oldest-first list is a different set of
    // episodes from page 3 of the newest-first one.
    val epPageSize = 30
    var rangeStart by remember(activeSeason, epsNewestFirst) { mutableStateOf(0) }
    val ranges = remember(orderedEps) {
        if (orderedEps.size <= epPageSize) emptyList()
        else (0 until orderedEps.size step epPageSize).toList()
    }
    val safeStart = if (ranges.isEmpty()) 0 else rangeStart.coerceIn(0, ranges.last())
    val pageEps = remember(orderedEps, safeStart) {
        orderedEps.drop(safeStart).take(epPageSize)
    }

    LaunchedEffect(providerId, mediaId) {
        vm.load(providerId, type, mediaId, title, posterUrl, rawType)
    }

    // Watch-history for this title (every episode), so a tapped video can offer
    // "continue from where you left off?" no matter how the user got here
    // (History tab, Home, Continue Watching, or a catalog).
    val app = context.applicationContext as HikariApp
    // Settings that shape the Play tap: whether playback starts on the first
    // server found or waits for a chosen number of them, and whether the
    // full-screen title card covers the player until video is ready.
    val playWaitFlow = remember { app.store.playWaitServersFlow() }
    val playWaitServers by playWaitFlow.collectAsState(initial = false)
    val playMinFlow = remember { app.store.playMinServersFlow() }
    val playMinServers by playMinFlow.collectAsState(initial = 2)
    val bannerFlow = remember { app.store.showLoadingBannerFlow() }
    val showLoadingCoverSetting by bannerFlow.collectAsState(initial = true)
    // Look of the "finding your server" card — the same choice the player's own
    // cover wears, handed over as an intent extra so the two screens cannot
    // disagree (see [LoadingStyles] and PlayerActivity.showLoadingBanner).
    val loadingStyleFlow = remember { app.store.loadingStyleFlow() }
    val loadingStyleSetting by loadingStyleFlow.collectAsState(initial = LoadingStyles.CINEMATIC)
    // The treatment drawn over that card — handed over the same way, so the
    // detail page's cover and the player's cover stay identical through the
    // hand-off (see [com.hikari.app.ui.LoadingEffects]).
    val loadingEffectsFlow = remember { app.store.loadingEffectsFlow() }
    // Empty until the store answers: the default is NONE (see
    // AppStore.DEFAULT_LOADING_EFFECTS), so no treatment is ever drawn for the
    // frame before the real value arrives.
    val loadingEffectsSetting by loadingEffectsFlow
        .collectAsState(initial = emptySet<String>())
    // The colour the loading cover's aura ring is drawn in, resolved HERE and
    // handed to the player as an ARGB int — the ring is the one part of the
    // cover with its own colour setting, and the two screens showing it
    // (this page and the player) must resolve it to the same pixels or the
    // hand-off from one to the other looks like a second loading screen.
    val loadingAuraColorSetting by remember { app.store.loadingAuraColorFlow() }
        .collectAsState(initial = com.hikari.app.ui.AuraColors.THEME)
    // The ring's colour as the ARGB the player is handed. Resolved HERE, in
    // composition, rather than inside the launch lambda below: reading
    // MaterialTheme is a composable call, and that lambda is not composable.
    val loadingAuraArgb = com.hikari.app.ui.AuraColors
        .color(loadingAuraColorSetting, MaterialTheme.colorScheme.primary)
        .toArgb()
    // The title wordmark on the loading cover (Settings → App Layout → Loading
    // screen → Title logo). The cover draws the title as ART here — the same
    // TMDB wordmark the header above draws — with the plain text title as the
    // fallback for a title that has none (and for a user who turned it off).
    // Both the switch and the size ride to the player as intent extras, so the
    // hand-off into the player never changes the picture.
    val loadingLogoOn by remember { app.store.loadingLogoFlow() }
        .collectAsState(initial = true)
    val loadingLogoPercent by remember { app.store.loadingLogoSizeFlow() }
        .collectAsState(initial = 100)
    // The wordmark itself. Declared HERE rather than beside the header art far
    // below, because the Play intent needs it: it is fetched on a background
    // request per title and read by both the header and the loading cover.
    var heroLogo by remember { mutableStateOf<String?>(null) }
    // "Don't play directly — show all servers to choose": when on, the player
    // opens on its server list (grouped by engine) and never starts a server by
    // itself, so this screen must not hold playback back for a remembered
    // server either — the chooser should come up the moment servers exist.
    val askServerFlow = remember { app.store.askServerOnPlayFlow() }
    val askServerOnPlay by askServerFlow.collectAsState(initial = false)
    val playScopeAskOn by remember { app.store.playScopeAskFlow() }.collectAsState(initial = false)
    var scopeAskShow by remember { mutableStateOf(false) }
    var scopeAskPending by remember { mutableStateOf<Triple<Episode?, Long, Boolean>?>(null) }
    var scopeAskArmed by remember { mutableStateOf(false) }
    // The last finished search ran narrowed ("this extension only"): when it
    // ends with nothing playable, the empty sheet offers widening it instead
    // of leaving that dead end.
    var lastSearchNarrowed by remember { mutableStateOf(false) }
    // The dead-end widen already fired for the current tap chain (see the
    // verdict block in [openStreams]): one automatic widened wave per tap, so
    // a widened pass that also ends empty can never re-trigger itself.
    // Atomic: the verdict runs on the app scope while taps land on the main
    // thread.
    val autoWidenFired = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var pendingNarrowTo by remember { mutableStateOf<String?>(null) }
    // Servers the player must know about before it starts. 1 = "as soon as the
    // first server is found" (the default).
    val startAfterServers = if (playWaitServers) playMinServers else 1
    // Collected reactively (not a one-shot read) so that returning here after a
    // play immediately sees the progress the player just wrote — otherwise the
    // "Continue from where you left off?" prompt never appeared on the second
    // open of a title, because this screen's keys hadn't changed.
    // The Flow is `remember`ed: an inline `app.store.historyFlow()` would be a
    // brand-new Flow on every recomposition, so collectAsState kept re-attaching
    // and resetting to `initial` (empty) — which left the resume hint empty and
    // the in-video "continue?" prompt never fired.
    val historyFlow = remember { app.store.historyFlow() }
    val allHistory by historyFlow.collectAsState(initial = emptyList())
    // Match by provider+id first; if the same title/episode was watched on a
    // DIFFERENT provider (the user's stated pattern — started on one extension,
    // reopened from another), fall back to id (then title) so the saved
    // position is still found and the in-player resume prompt appears.
    val historyForTitle = remember(allHistory, providerId, mediaId, title, type) {
        val sameProvider = allHistory.filter { it.providerId == providerId && it.mediaId == mediaId }
        if (sameProvider.isNotEmpty()) return@remember sameProvider
        val sameId = allHistory.filter { it.mediaId == mediaId }
        if (sameId.isNotEmpty()) return@remember sameId
        allHistory.filter {
            it.mediaId == mediaId ||
                (it.title.equals(title, ignoreCase = true) && it.type == type)
        }
    }

    // Library state for this page's heart button. Collecting the Flow (rather
    // than reading `favorites()` once) means the icon also flips if the same
    // title is (un)saved from the player or another screen while this is open.
    val favoritesFlow = remember { app.store.favoritesFlow() }
    val favorites by favoritesFlow.collectAsState(initial = emptyList())
    // The Library's categories themselves, and which ones this title is filed
    // under. Both are flows, so a category invented from inside the sheet — or
    // a filing changed on the Library screen — is reflected here at once.
    val categoriesFlow = remember { app.store.libraryCategoriesFlow() }
    val categories by categoriesFlow.collectAsState(initial = LibraryCategory.DEFAULTS)
    val filingsFlow = remember { app.store.favoriteCategoriesFlow() }
    val filings by filingsFlow.collectAsState(initial = emptyMap())
    var librarySheet by remember { mutableStateOf(false) }
    // The mark sheet (the check button beside Play) — see the block below the
    // library picker.
    var markSheet by remember { mutableStateOf(false) }
    // The full cast roster dialog on TV (the cast header's "View All"): the
    // reference client's "View All Cast & Crew" opens the whole roster, and
    // Hikari has no person page — a dialog grid over the detail page instead.
    var showFullCast by remember { mutableStateOf(false) }

    var playerLaunched by remember { mutableStateOf(false) }
    // Resets the once-only launch guard the moment the player activity returns
    // to this screen — without this, the FIRST play set the flag and every
    // later tap (episode 2..N, another server) was silently swallowed, so a
    // 10-episode melon list only ever played its first video.
    val playerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // The player session is gone. Stop the live sink from forwarding a late
        // background result into the destroyed session; the next Play tap installs
        // a fresh sink/session. Leaving the old callback installed was especially
        // bad with the shared prefetch: a later provider result could recreate the
        // removed session and make the next play appear to be searching the wrong
        // session.
        vm.liveSink = null
        playerLaunched = false
        showLoadingBanner = false
    }
    // [wantsDownload] rides along as the `openDownload` intent extra: the player
    // then puts its download chooser up as soon as a server is ready instead of
    // just watching. Same intent, same player, same chooser as the in-player
    // Download button — the download buttons outside the player are only a
    // different way IN, never a second download implementation.
    val launchPlayer: (List<StreamSource>, Episode?, String, Long, Boolean) -> Boolean = launchPlayer@{ playable, ep, liveId, startPos, wantsDownload ->
        if (playerLaunched) return@launchPlayer false
        // Build the payload BEFORE flipping the once-only guard. It used to be
        // the other way round: one malformed source list set `playerLaunched`
        // and then bailed out, so the player never opened AND every later tap
        // was swallowed by the guard — the Play button looked completely dead.
        val payload = playerPayload(playable)
        if (payload == null) return@launchPlayer false
        playerLaunched = true
        // Keep the background extension sweep off the loading cover: it is held
        // from here and released the moment the player has a frame on screen
        // (see StreamsLive.releaseSweep). The servers it looks for are only ever
        // ADDED to a list that is already playing, so nothing is lost by
        // waiting, and "searching extensions…" no longer races the video.
        m?.let { StreamsLive.holdSweep(liveId, it, ep) }
        // History context rides along so the player can record resume position
        // and remember which server this video was last played with (so a
        // replay continues on that server and starts instantly).
        val intent = Intent(context, PlayerActivity::class.java).apply {
                // The artwork card prints this, so it follows the TMDB language
                // (see artTitle); History keeps recording the item's own title.
                putExtra("title", artTitle)
                putExtra("sources", payload)
                // Live server feed: playback starts with the first server found
                // while the detail screen keeps searching every installed
                // provider; the player appends them to its "Select server" list.
                putExtra("streamsLiveId", liveId)
                putExtra("histTitle", m?.title ?: title)
                // The provider that the page actually resolved to — NOT the id
                // the page was opened with, which may be a stale one that no
                // longer exists (see DetailViewModel.load's remap). Without
                // this, History would re-record a dead id every play.
                putExtra("histProviderId", m?.providerId ?: providerId)
                putExtra("histMediaId", mediaId)
                putExtra("histType", (m?.type ?: type).name)
                putExtra("histPoster", PosterLoader.tokenize((m?.posterUrl ?: posterUrl).orEmpty()).orEmpty())
                // Backdrop (poster fallback) for the player's own title card —
                // passed as a disk-cache token so the intent never carries a
                // multi-MB base64 string. The plural TMDB (/images) art the
                // header settled on first (via its cache — the intent cannot
                // suspend), then the single backdrop_path, then the provider
                // art, which is frequently a low-res thumb.
                putExtra(
                    "bannerBackdrop",
                    PosterLoader.tokenize(
                        (if (m != null) TmdbScreenshots.firstCached(m.uniqueId) else null)?.takeIf { it.isNotBlank() }
                            ?: (if (m != null) Artwork.backdrop(m) else null)?.takeIf { it.isNotBlank() }
                            ?: m?.backdropUrl?.takeIf { it.isNotBlank() }
                            ?: posterUrl.orEmpty()
                    ).orEmpty()
                )
                // The same art as a plain URL for the player's server screen,
                // which paints it full-bleed behind its glass rows (the
                // desktop client's look). Coil loads it there directly.
                putExtra(
                    "serverArt",
                    (if (m != null) TmdbScreenshots.firstCached(m.uniqueId) else null)?.takeIf { it.isNotBlank() }
                        ?: (if (m != null) Artwork.backdrop(m) else null)?.takeIf { it.isNotBlank() }
                        ?: m?.backdropUrl?.takeIf { it.isNotBlank() }
                        ?: posterUrl.orEmpty()
                )
                putExtra("showLoadingBanner", showLoadingCoverSetting)
                putExtra("loadingStyle", loadingStyleSetting)
                putExtra("loadingEffect", com.hikari.app.ui.LoadingEffects.encode(loadingEffectsSetting))
                // The title's own wordmark, for the cover's title block (see
                // loadingLogoOn above) — tokenized like the artwork, so the
                // intent never carries a URL that has to be re-fetched on the
                // main thread when the player opens.
                putExtra("titleLogo", if (loadingLogoOn) PosterLoader.tokenize(heroLogo.orEmpty()).orEmpty() else "")
                putExtra("titleLogoSize", loadingLogoPercent)
                // The aura ring's colour, resolved to the same ARGB this page
                // draws it in, so the player's cover continues the exact picture
                // the detail page put up (see loadingAuraArgb above).
                putExtra("loadingAuraColor", loadingAuraArgb)
                putExtra("startAfterServers", startAfterServers)
                // Ask before playing: the player shows every server it found,
                // grouped by engine, instead of starting one by itself. The
                // television always asks: on the big screen a tap shows the
                // server list (over the title's art, like the desktop client)
                // instead of playing directly.
                putExtra("askServer", askServerOnPlay || isTvLayout)
                // NO origin hold, in either playback mode. These two extras used
                // to carry the window in which the player waited for the
                // extension the title was opened FROM; the servers that decide
                // WHICH one plays are now decided by the list's order instead
                // (the origin's own servers sort first — see the player's
                // healthyStartIndex), so the wait bought nothing and cost the
                // one thing the user kept reporting: "N servers found and it is
                // still not playing". "Wait for more servers first" is still
                // honoured by [startAfterServers] above — a count, not a
                // provider. Kept as explicit 0s (instead of dropped) so an older
                // player build reading this intent still sees "no hold".
                putExtra("originGraceMs", 0)
                putExtra("originHeadStartMs", 0)
                putExtra("openDownload", wantsDownload)
                putExtra("histEpisodeId", ep?.id.orEmpty())
                putExtra("histEpisodeName", ep?.name.orEmpty())
                putExtra("histEpisodeSeason", ep?.season ?: 0)
                putExtra("histEpisodeNumber", ep?.number ?: 0)
                putExtra("startPosition", startPos.coerceAtLeast(0L))
                // Saved progress offered to the player's own resume prompt. It
                // reads the store itself first; this is the cross-provider
                // fallback (watched on another extension) so the prompt still
                // appears instead of silently starting from 0.
                putExtra("histResumePosition", resumeHint?.first ?: 0L)
                putExtra("histResumeDuration", resumeHint?.second ?: 0L)
                putExtra("histAskResume", true)
        }
        // Never leave the guard stuck ON if the launch itself fails (e.g. the
        // player activity can't be resolved): report failure so the caller can
        // fall back to the source sheet instead of a dead tap.
        return@launchPlayer runCatching { playerLauncher.launch(intent) }
            .fold(onSuccess = { true }, onFailure = { playerLaunched = false; false })
    }

    // The episode a bare Play tap should search for: the tapped episode, or —
    // when the origin addon is still listing episodes — episode 1 as soon as it
    // lands (bounded, so a genuine movie is never held up for long). The player
    // is already open on its title card for the whole wait, so tapping Play
    // always gives immediate feedback and playback starts the instant episode 1
    // resolves, instead of searching for a series with no episode and finding
    // nothing.
    val firstEpisodeOrNull: suspend () -> Episode? = {
        val t = (vm.meta.value ?: m)?.type
        val eps = vm.episodes.value
        // UNKNOWN included: a catalog row that honestly does not know its
        // kind yet (see the site-scraper providers) waits for episode 1
        // exactly like a series — otherwise Play on a genuine series would
        // fire a movie-style search before its list had landed.
        val mightBeSeries = t == MediaType.SERIES || t == MediaType.UNKNOWN ||
            (eps?.isNotEmpty() == true)
        if (mightBeSeries && eps == null && !vm.episodesLoaded.value) {
            withTimeoutOrNull(EPISODE_WAIT_MS) { vm.episodesLoaded.first { it } }
        }
        vm.episodes.value
            ?.sortedWith(compareBy({ it.season }, { it.number }))
            ?.firstOrNull()
    }

   val openStreams: (Episode?, Long, Boolean) -> Unit = openStreamsCall@{ ep, startPos, wantsDownload ->
        if (playScopeAskOn && !scopeAskArmed) {
            pendingNarrowTo = null
            scopeAskPending = Triple(ep, startPos, wantsDownload)
            scopeAskShow = true
            return@openStreamsCall
        }
        scopeAskArmed = false
        val narrowTo = pendingNarrowTo
        pendingNarrowTo = null
        lastSearchNarrowed = (narrowTo != null)
        // A fresh tap earns its own automatic widened wave (see the verdict
        // block below): a widened pass that also ends empty must not widen
        // again, but the NEXT tap starts over.
        autoWidenFired.set(false)
        // Open the PLAYER on the very first frame of the tap (Nuvio/Stremio
        // style). The player has its own title-card screen, so instead of the
        // detail page sitting on a spinner for several seconds while the first
        // server is found, the player comes up instantly and starts playback the
        // moment a server lands on the live session. Source resolution keeps
        // running here in the background.
        // Keep an already-running detail-page prefetch alive. DetailViewModel
        // shares the extraction through StreamCache, so cancelling it here throws
        // away the fastest path to a server and starts the same provider walk again.
        // The player can open immediately and consume the prefetch's first result.
        selectedEp = ep
        pendingStartPos = startPos
        streams = emptyList()
        loadingStreams = true
        // Full-screen title card from the very first frame of the tap (the
        // source sheet only appears if nothing playable can be found at all).
        // Skipped entirely when the user turned the loading banner off.
        showLoadingBanner = showLoadingCoverSetting
        showSheet = false
        // A fresh tap must always be allowed to open the player. If an earlier
        // launch never reported back (activity result lost, process reshuffle),
        // the once-only guard could stay stuck ON and silently swallow every
        // later play — the Play button then looked completely dead.
        playerLaunched = false
        // One live-update session per play tap: the player subscribes to it and
        // keeps receiving servers as slower providers answer, so its "Select
        // server" dialog shows every source from every installed provider.
        // The id is held in a LOCAL and handed to the search coroutine below;
        // the state is only for the screen's other entry points. Reading the
        // state back from inside the coroutine was a real bug: a second Play
        // tap (or an episode tap) reassigns it, and the FIRST tap's search —
        // still running on the app scope — then appended every server it found
        // to the NEW session, while the player it had launched kept listening
        // on the old one. The player therefore showed only the first batch
        // (one engine's servers) and never grew, which reads exactly like "the
        // search stopped in the middle and only one category loaded".
        val sid = UUID.randomUUID().toString()
        sessionId = sid
        vm.resetLiveStreams()
        // Local once-only flag: playback launches exactly ONCE per tap (here —
        // the immediate launch below — or later from the feed as the first
        // servers arrive, or from the final batch); afterwards new servers are
        // appended to the player's live session, never re-launched.
        // Atomic because this search runs on the app scope while the screen's
        // own reads happen on the main thread.
        //
        // CRITICAL: the immediate launch below must ARM this flag. It did not,
        // and that was a real, reported bug: [openStreams] opens the player
        // before any server exists, so `launched` stayed false while the player
        // was already up. The player's launcher callback (see [playerLauncher])
        // clears `playerLaunched` the moment the user backs out of the player —
        // so the FIRST server that arrived after that found BOTH flags false and
        // launched the player again. Backing out therefore re-opened the same
        // "finding your server" card (the detail screen's title card and the
        // player's are deliberately identical, so it reads as the same page
        // loading two or three times), every time, for every title.
        val launched = java.util.concurrent.atomic.AtomicBoolean(false)
        // Launch the player NOW with an empty source list — it shows its own
        // title card and waits for the first servers on [sessionId]. If the
        // launch itself fails (the activity can't be resolved), the coroutine
        // below falls back to the old "resolve here, then open the player" path
        // and the source sheet.
        if (launchPlayer(emptyList<StreamSource>(), ep, sid, startPos, wantsDownload)) {
            launched.set(true)
        }
        // Warm-start from the persisted last successful server. This removes the
        // blank "Searching your extension..." wait on repeat plays while the normal
        // provider search continues in parallel. The player already has failover,
        // so a stale remembered URL is simply skipped when it cannot prepare.
        app.appScope.launch {
            // "This extension only" is a demand for a FRESH search of that
            // extension: warming the new session with the last successful URL
            // (saved by an earlier "My selected servers" play under the same
            // key) made the player start on the old server instead of the
            // extension's own results. Skip the warm start for a narrowed
            // search; the unscoped path below keeps it.
            if (narrowTo != null) return@launch
            runCatching {
                val warmType = (vm.meta.value ?: m)?.type?.name ?: type.name
                val warmKey = "${livePid}|$warmType|${mediaId}|${ep?.id.orEmpty()}"
                val last = app.store.lastSource(warmKey)
                val warmUrl = last?.url.orEmpty()
                if (warmUrl.isNotBlank() && !warmUrl.startsWith("javascript:", true)) {
                    StreamsLive.append(
                        sid,
                        listOf(
                            StreamSource(
                                name = last?.name?.ifBlank { "Last used server" } ?: "Last used server",
                                url = warmUrl,
                                providerId = livePid,
                                providerName = providers.firstOrNull { it.config.id == livePid }?.config?.name.orEmpty(),
                            )
                        )
                    )
                }
            }
        }
        // The coroutine's own copy of everything found so far. Deliberately NOT
        // the Compose state: the search outlives this screen, so it keeps its
        // own list and only mirrors it into [streams] (for the source sheet)
        // while the screen is alive.
        var found: List<StreamSource> = emptyList()
        // Did the final lookup reach a real conclusion? Only then may the user be
        // told the search "found nothing" — a pass that was cut short (cancelled,
        // a provider blow-up, the device starving the threads) knows nothing
        // about this title, and reporting it as "no playable server found after
        // searching 254 extensions" is exactly what ended playback seconds into
        // a search that was still running. See [StreamLookup].
        var lookupComplete = false
        // Set when the search threw: shown instead of a false "found nothing".
        var problemNote: String? = null
        val playableEvery = { list: List<StreamSource> ->
            val basic = list.filter { s ->
                s.ytId == null && !s.externalUrl && (s.url.isNotBlank() || s.isTorrent) &&
                    (narrowTo == null || s.providerId.isBlank() || s.providerId == narrowTo)
            }
                // Every server the providers returned is offered, in full. A
                // "needs a browser check" record is NOT used to hold anything
                // back: a title that played once must not come back with a
                // shorter list (or none) because a host got flagged in the
                // meantime — the user asked for the servers, so the servers are
                // shown and the player can try them.
                //
                // Archive links (.zip/.rar/.7z …) are not videos: providers
                // (4KHDHub's isDirectVideo only checks the hostname, so its
                // ".mkv.zip" hubcloud links leak through) sometimes hand them
                // out, and they cost a full prepare+error cycle before the
                // player falls through. A stable sort keeps arrival order but
                // pushes archives to the back, so they are never server #1.
                basic.sortedBy { if (!it.isTorrent && StreamProbe.isArchive(it.url)) 1 else 0 }
        }
        // Late-server hand-off to the player (see [liveSink]).
        vm.liveSink = { partial ->
            if (launched.get() || playerLaunched) {
                val playable = playableEvery(partial)
                if (playable.isNotEmpty()) StreamsLive.append(sid, playable)
            }
        }
        // NOTE: application scope, NOT the composition's. See [screenAlive].
        // The episode the search actually runs for, hoisted OUT of the `try`
        // below because a local declared inside it is not visible from the
        // `finally` block — and that block is where the end-of-search verdict is
        // written (and where a background sweep has to be recognised).
        var searchedEpisode: Episode? = ep
        app.appScope.launch {
            try {
                // Live progress for the player's loading cover. The player
                // opened the instant Play was tapped, so this line is the
                // only thing on screen saying that anything is happening at
                // all (and, in the log, the only record of it).
                val searchable = providers.count { it.config.enabled }
                // "Search all installed extensions" off (Settings → Playback
                // & Servers → Server search) means exactly ONE extension is
                // being asked, and saying "Searching 257 extensions…" over a
                // single-repo lookup would be a plain lie on the only line
                // the user can see. With exception extensions in force more than
                // one repo IS asked, so the line counts them honestly.
                val exceptionN = if (com.hikari.app.data.SearchScope.allExtensions) 0
                else com.hikari.app.data.SearchScope.exceptions.count { id ->
                    providers.any { it.config.id == id && it.config.enabled }
                }
                StreamsLive.setStatus(
                    sid,
                    when {
                        // An IPTV channel is looked up in its own playlist and
                        // nowhere else (see ContentRepository.streamsForInner),
                        // so the line must not promise a sweep of every
                        // installed extension.
                        com.hikari.app.data.IptvMark.isIptvProvider(
                            activeProviderId.ifBlank { providerId }
                        ) ->
                            "Searching this playlist for servers…"
                        exceptionN > 0 ->
                            "Searching your extension + $exceptionN more…"
                        !com.hikari.app.data.SearchScope.allExtensions -> {
                            // Name the extension being searched the moment the
                            // search starts, so a "this extension only" tap
                            // never sits on a nameless line while its 15
                            // servers resolve one by one.
                            val originName = providers.firstOrNull { it.config.id == (m?.providerId ?: providerId) }
                                ?.config?.name?.ifBlank { null }
                            if (originName != null) "Searching $originName for servers…"
                            else "Searching your extension for servers…"
                        }
                        else ->
                            "Searching $searchable extension" + (if (searchable == 1) "" else "s") + "…"
                    },
                )
                // Which episode the search runs for: the tapped one, or episode 1
                // when the origin addon was still listing episodes. The player is
                // already open on its title card during this wait, so it opens and
                // starts playing the instant episode 1 resolves.
                val epForSearch: Episode? = ep ?: firstEpisodeOrNull()
                searchedEpisode = epForSearch
                if (ep == null && epForSearch != null) {
                    onUi { selectedEp = epForSearch }
                    // Hand the already-open player the episode it ended up on, so
                    // its title card, resume key and watch history are per-episode
                    // rather than the movie-level entry.
                    StreamsLive.setEpisode(sid, epForSearch)
                }
                // The server this video was last played with, remembered by the
                // player under the same key as the watch-history entry. It is
                // PREFERRED, never waited for — see the note below.
                val historyKey = "${livePid}|${(vm.meta.value ?: m)?.type?.name ?: type.name}|$mediaId|${epForSearch?.id.orEmpty()}"
                val last = runCatching { app.store.lastSource(historyKey) }.getOrNull()
                val prefUrl = last?.url.orEmpty()
                val prefName = last?.name.orEmpty()
                // NOT a hold any more. A remembered server used to suppress the
                // first-server start (for up to ten seconds) so a replay
                // continued on the link that worked last time — which is exactly
                // the kind of "there are servers and it is not playing" wait this
                // app has been reported for. The REMEMBERED server keeps its
                // advantage without the wait: it is placed directly after the
                // origin's own rows (see the ordering below), and the player's
                // own [preferredStartIndex] picks it when it is in the list. If
                // it has not arrived yet, the first server that has starts.
                val preferredIndex = { list: List<StreamSource> ->
                    if (prefUrl.isBlank() && prefName.isBlank()) -1
                    else list.indexOfFirst { s ->
                        (prefUrl.isNotBlank() && s.url == prefUrl) ||
                            (prefName.isNotBlank() && s.name.equals(prefName, ignoreCase = true))
                    }
                }
                // The order of the list the player is handed decides what it
                // PLAYS, not just what it shows: the player starts on the row its
                // own start-index rule picks and walks the list top-down when a
                // server dies. Two rules, in this order:
                //
                //  1. the ORIGIN's own servers first — the extension the user
                //     opened this title from is the one whose link they asked
                //     for. This used to be pure arrival order, so whichever
                //     extension answered fastest won the top row: for a title on
                //     a Chinese .hiki repo, an adult tube repo that echoes the
                //     query back into its page titles arrived in a second, sat
                //     above the origin's own link, and got played (the reported
                //     "it selected XFree and played the wrong video instead of
                //     the MRDS server");
                //  2. then the remembered server (a replay continues on the one
                //     that worked — see [preferredIndex]), then everything else
                //     in arrival order.
                val ordered = { list: List<StreamSource> ->
                    val originPid = m?.providerId ?: providerId
                    val pref = preferredIndex(list)
                    val placed = HashSet<Int>()
                    val out = ArrayList<StreamSource>(list.size)
                    list.forEachIndexed { i, s ->
                        if (s.providerId == originPid) {
                            out += s
                            placed += i
                        }
                    }
                    if (pref >= 0 && placed.add(pref)) out += list[pref]
                    list.forEachIndexed { i, s -> if (placed.add(i)) out += s }
                    out
                }
                // Live re-extraction. A play session can have all of its servers
                // die at once: 4KHDHub/hubcloud's signed workers.dev links expire,
                // and the mirror that served them can go away. The player (still
                // attached via [sid]) then requests fresh sources by bumping
                // the session's refresh counter instead of replaying a dead link
                // forever — we re-run the providers ignoring the cache and stream
                // the new servers straight to the player, which retries with them.
                var lastRefresh = StreamsLive.refreshFlow(sid).value
                launch {
                    StreamsLive.refreshFlow(sid).collect { n ->
                        if (n == lastRefresh) return@collect
                        lastRefresh = n
                        StreamsLive.setStatus(sid, "Re-extracting expired links…")
                        // Targeted first: the dead links almost always came
                        // from the title's OWN provider, so re-ask it ALONE on
                        // a short budget and hand the player fresh links in
                        // seconds. The old code ran the whole cross-extension
                        // pass first, which is what kept "Re-extracting
                        // expired links…" on screen for a minute while the one
                        // provider that mattered had answered long ago. The
                        // full pass still runs below for everyone else.
                        val mm = m
                        if (mm != null) {
                            val originFirst = vm.originStreamsFast(mm, epForSearch)
                            if (originFirst.isNotEmpty()) {
                                found = originFirst
                                onUi { streams = originFirst }
                                val originPlayable = playableEvery(originFirst)
                                StreamProbe.warmAsync(originPlayable)
                                StreamsLive.setStatus(
                                    sid,
                                    "Found " + originPlayable.size + " fresh server" +
                                        (if (originPlayable.size == 1) "" else "s") + " — retrying…",
                                )
                                StreamsLive.append(sid, originPlayable)
                            }
                        }
                        val fresh = vm.getStreams(epForSearch, force = true)
                        if (fresh.isNotEmpty()) {
                            found = fresh
                            onUi { streams = fresh }
                            val freshPlayable = playableEvery(fresh)
                            StreamProbe.warmAsync(freshPlayable)
                            StreamsLive.setStatus(
                                sid,
                                "Found " + freshPlayable.size + " fresh server" +
                                    (if (freshPlayable.size == 1) "" else "s") + " — retrying…",
                            )
                            StreamsLive.append(sid, freshPlayable)
                        }
                    }
                }
                // Starts playback on the servers found so far. Runs on the main
                // thread: [launchPlayer] uses this composition's
                // ActivityResultLauncher, so it may only be called while the
                // screen is alive.
                val startNow: suspend () -> Unit = startNow@{
                    if (launched.get() || playerLaunched) return@startNow
                    val playable = playableEvery(found)
                    if (playable.isEmpty()) return@startNow
                    var started = false
                    onUi {
                        if (launched.get()) return@onUi
                        started = launchPlayer(ordered(playable), epForSearch, sid, startPos, wantsDownload)
                    }
                    if (started) {
                        launched.set(true)
                        onUi {
                            showSheet = false
                            loadingStreams = false
                        }
                    } else if (screenAlive.get()) {
                        // Player could not be opened (bad payload / launch
                        // failure) — leave the source sheet up with its
                        // per-extension diagnostics so the user can still pick a
                        // server.
                        onUi {
                            loadingStreams = false
                            showLoadingBanner = false
                            showSheet = true
                        }
                    } else {
                        // The screen is gone and the player never opened: keep the
                        // servers on the live session instead, so a player that
                        // opens later still finds them.
                        StreamsLive.append(sid, playableEvery(found))
                    }
                }
                // Live feed: start the instant a playable server appears — unless a
                // preferred server is remembered, in which case keep waiting for it.
                val feed = launch {
                    vm.liveStreams.collect { current ->
                        val playable = playableEvery(current)
                        if (playable.isEmpty()) return@collect
                        found = current
                        onUi { streams = current }
                        // Resolve wrapper URLs ahead of playback so "Select server"
                        // and any failover are instant.
                        StreamProbe.warmAsync(playable)
                        StreamsLive.setStatus(
                            sid,
                            if (launched.get() || playerLaunched) {
                                "Found " + playable.size + " server" +
                                    (if (playable.size == 1) "" else "s") + " — still searching…"
                            } else {
                                "Found " + playable.size + " server" +
                                    (if (playable.size == 1) "" else "s") +
                                    " — starting playback…"
                            },
                        )
                        if (launched.get() || playerLaunched) {
                            // Player already up — hand it the newly found servers.
                            StreamsLive.append(sid, playable)
                        } else {
                            // No hold to wait out: the first playable server starts
                            // playback, whatever else is still being searched.
                            startNow()
                        }
                    }
                }
                // The final read is the one that decides the outcome, so it is
                // RETRIED while a pass keeps coming back unfinished. This used
                // to be a single read whose timeout/emptiness was treated as an
                // answer: a search that was still running (three Play taps →
                // three back-to-back 254-extension sweeps, nothing reused) got
                // reported as "no playable server found" about nine seconds in
                // and the player quit. Only a lookup that FINISHED — with
                // servers, or genuinely empty — ends the loop; an unfinished one
                // is asked again, which either JOINS the pass still running for
                // this title or (if it really died) starts it once more. Bounded
                // both by [STREAMS_FINAL_RETRIES] and by [STREAMS_FINAL_CAP_MS].
                val finalDeadline = System.currentTimeMillis() + STREAMS_FINAL_CAP_MS
                var final: List<StreamSource> = emptyList()
                var attempts = 0
                while (attempts <= STREAMS_FINAL_RETRIES) {
                    val left = finalDeadline - System.currentTimeMillis()
                    if (left <= 0L) break
                    val lookup = withTimeoutOrNull(left) {
                        vm.getStreamsLookup(
                            episode = epForSearch,
                            onOriginSettled = { StreamsLive.settleOrigin(sid) },
                        )
                    } ?: break
                    attempts++
                    final = lookup.servers
                    lookupComplete = lookup.complete
                    if (lookup.servers.isNotEmpty() || lookup.complete) break
                    // Not an answer: the pass was cut short. Say that plainly and
                    // ask again — never "no servers found".
                    StreamsLive.setStatus(
                        sid,
                        "Still searching — no server found yet (attempt ${attempts + 1})…",
                    )
                    delay(SEARCH_RETRY_PAUSE_MS)
                }
                feed.cancel()
                // Never downgrade. The live feed above may already have handed
                // the player a full list from the first providers that
                // answered, and a late or cached re-read can come back empty
                // (every provider having failed or timed out on the retry).
                // Blindly overwriting [found] with that would blank the
                // servers already in the player's "Select server" list. Only
                // accept the batch when it actually carries servers, or when
                // nothing was found at all — so a genuinely empty result is
                // still reported.
                if (final.isNotEmpty() || found.isEmpty()) found = final
                onUi {
                    loadingStreams = false
                    streams = found
                }
                val playable = playableEvery(found)
                StreamProbe.warmAsync(playable)
                if (launched.get() || playerLaunched) {
                    // Player is up (or already was) — close the sheet and hand it the
                    // complete list.
                    onUi { showSheet = false }
                    StreamsLive.append(sid, playable)
                } else if (playable.isNotEmpty()) {
                    // Cached/instant result arrived before the feed attached.
                    var started = false
                    onUi {
                        if (!launched.get()) {
                            started = launchPlayer(ordered(playable), epForSearch, sid, startPos, wantsDownload)
                        }
                    }
                    if (started) {
                        launched.set(true)
                        onUi { showSheet = false }
                    } else {
                        onUi {
                            showLoadingBanner = false
                            showSheet = true
                        }
                        if (!screenAlive.get()) StreamsLive.append(sid, playable)
                    }
                } else {
                    // Nothing playable anywhere — keep the source sheet up, with the
                    // per-extension diagnostics explaining what failed.
                    onUi {
                        showLoadingBanner = false
                        showSheet = true
                    }
                }
                // The whole source search is over. [StreamsLive.markDone] is sent
                // below ONLY when there is something to play or the pass reached a
                // real verdict — see the note there.
            } catch (t: Throwable) {
                // A throw here (a provider blowing up, a cancelled child
                // collector) used to skip markDone entirely, so the player
                // kept spinning on an empty session until its 90s safety
                // timeout. Say what happened instead — and say it as a
                // PROBLEM, never as "no servers were found", which is what
                // made a search that died mid-flight read like a verdict.
                problemNote = "The search hit a problem (" + t.javaClass.simpleName + ")."
                StreamsLive.setStatus(sid, problemNote!!)
            } finally {
                // ALWAYS declare the search over. The player only leaves its
                // "Finding the best server…" cover when a server arrives or
                // the search is declared finished, so this is what turns an
                // empty result into a clear message within a second instead
                // of a minute and a half of nothing.
                val foundCount0 = playableEvery(found).size
                // Reassigned when the dead-end widen below finds servers, so
                // every status branch under this reads the widened count.
                var foundCount = foundCount0
                // Is a background sweep still asking repos this lookup did not
                // really finish — the ones the pass never reached, or the ones it
                // answered out of the session's "no such title" record after
                // coming back thin (see ContentRepository.CROSS_THIN_RESULT)?
                // Then the search is NOT over, and every server the sweep finds is
                // pushed into the live session the player is already listening on.
                // Announcing a verdict here is exactly what made a search that was
                // still working look like it had stopped at the 5th or 13th
                // extension (and made the player quit early on "no playable
                // sources" while the sweep was still finding them).
                //
                // This is deliberately NOT gated on "found nothing": the report
                // that matters most is the one with servers already on the list —
                // "it says 2 servers and search finished, then on the fourth tap
                // it finds 36". Saying "search finished" while the sweep that
                // exists precisely to find the other 34 is still running is what
                // made the count look like it came out of nowhere.
                val sweepBusy = vm.backgroundSweepBusy(searchedEpisode)
                // Dead-end rescue: a pass that reached a REAL verdict (not cut
                // short), with nothing still running behind it, and zero
                // playable servers — while the scope was narrowed (an explicit
                // "this extension only" tap, or the "only this extension"
                // setting with the origin engine's family switch off) — fires
                // ONE widened wave automatically instead of sitting on the dead
                // end. The wave feeds the same live session the player is
                // already listening on (and the sheet's list), so an open
                // "still searching" cover resolves into servers instead of
                // hanging. Structural narrowness is never lifted: IPTV
                // playlists, exception-repo collapses, tracker and originless
                // lookups keep their scope — only the user's preference gates
                // (all-extensions + the origin engine's family) are widened,
                // for this one pass, then restored.
                var widenedRan = false
                if (foundCount == 0 && lookupComplete && !sweepBusy &&
                    autoWidenFired.compareAndSet(false, true)
                ) {
                    val originId = m?.providerId ?: providerId
                    val origin = providers.firstOrNull { it.config.id == originId }
                    val settingsNarrow = narrowTo == null &&
                        !com.hikari.app.data.SearchScope.allExtensions &&
                        origin != null && origin.config.enabled &&
                        !com.hikari.app.data.IptvMark.isIptvProvider(originId) &&
                        !com.hikari.app.data.SearchScope.isException(originId)
                    if (narrowTo != null || settingsNarrow) {
                        var savedAll = true
                        var savedFamilies: Set<String> = emptySet()
                        if (settingsNarrow) {
                            savedAll = com.hikari.app.data.SearchScope.allExtensions
                            savedFamilies = com.hikari.app.data.SearchScope.engineFamilies
                            com.hikari.app.data.SearchScope.allExtensions = true
                            val engineName = origin?.config?.type?.name
                            if (engineName != null) {
                                com.hikari.app.data.SearchScope.engineFamilies =
                                    savedFamilies + engineName
                            }
                            com.hikari.app.data.Logs.log(
                                "Search",
                                "dead end with narrowed scope — widening to all servers for one pass " +
                                    "\"${m?.title.orEmpty().take(60)}\"",
                            )
                        }
                        try {
                            StreamsLive.setStatus(sid, "Nothing in scope — widening to all servers…")
                            val re = runCatching {
                                vm.getStreamsLookup(episode = searchedEpisode, force = true)
                            }.getOrNull()
                            val playable = if (re == null) emptyList() else playableEvery(re.servers)
                            if (playable.isNotEmpty()) {
                                found = re!!.servers
                                onUi { streams = re.servers }
                                StreamProbe.warmAsync(playable)
                                StreamsLive.append(sid, playable)
                                StreamsLive.setStatus(
                                    sid,
                                    "Widened search found " + playable.size + " server" +
                                        (if (playable.size == 1) "" else "s") + ".",
                                )
                                foundCount = playable.size
                            }
                        } finally {
                            if (settingsNarrow) {
                                com.hikari.app.data.SearchScope.allExtensions = savedAll
                                com.hikari.app.data.SearchScope.engineFamilies = savedFamilies
                            }
                        }
                        widenedRan = true
                    }
                }
                // The "nothing playable, and that is a real answer" note, built as
                // a lambda so the sweep's own watcher below can use the very same
                // wording if the sweep comes back empty a minute later.
                val noResultNote = {
                    // Truly scoped only when "only this extension" is on AND no
                    // exception repos are being searched (see [SearchScope]).
                    val scoped = !com.hikari.app.data.SearchScope.allExtensions &&
                        com.hikari.app.data.SearchScope.exceptions.isEmpty()
                    // …and "only this extension" only MEANS anything when the
                    // title really came from an extension. A title opened from
                    // Home / Search / Collections carries `providerId = "tmdb"`,
                    // which names no installed provider — so there is no "the
                    // extension this title came from" to search or to blame, and
                    // the lookup asks the id-resolving addons instead (see
                    // ContentRepository.streamsForInner).
                    val originId = m?.providerId ?: providerId
                    val originless = providers.none { it.config.id == originId }
                    val enabledN = providers.count { it.config.enabled }
                    val installedN = providers.size
                    val reason = vm.streamError.value?.takeIf { it.isNotBlank() }
                    // Rows WERE found, but not one of them is a video this app can
                    // hand to the player: an addon's LINK rows (a YouTube id, an
                    // external "watch it here" page) whose resolution came back
                    // empty. Reporting that as "no playable server found after
                    // searching N extensions" is false, and it is the report this
                    // note keeps producing — the source sheet is showing the user
                    // those very rows while this text denies they exist.
                    val links = found.filter { it.ytId != null || it.externalUrl }
                    val linksOnly = links.isNotEmpty() && playableEvery(found).isEmpty()
                    val note = buildString {
                        when {
                            linksOnly -> {
                                append("Found ${links.size} link")
                                append(if (links.size == 1) "" else "s")
                                append(" in ")
                                append(if (originless) "your playback addons" else "your extensions")
                                append(
                                    ", but none could be turned into a video — they point at " +
                                        "a page rather than a file (open that page to watch it)."
                                )
                            }
                            // The widened wave above ran and still found
                            // nothing: say that, or the "extension this title
                            // came from" line below would deny a search that
                            // already covered every server.
                            widenedRan && foundCount == 0 -> {
                                append("No playable server found, even after widening to all servers")
                            }
                            originless && enabledN == 0 -> {
                                append("No playback extension is switched on, so there was ")
                                append("nothing to play this title from.")
                            }
                            scoped -> {
                                // "Only this extension" is on, so the lookup's verdict
                                // is about ONE repo — say that, and say why it might
                                // be empty when the user knows other extensions have
                                // the title.
                                append("No playable server found in the extension this title came from")
                            }
                            else -> {
                                append("No playable server found after searching $enabledN ")
                                append(if (enabledN == 1) "extension" else "extensions")
                            }
                        }
                        // Only worth saying when a real number of extensions is
                        // switched off — "only 256 of your 257" is noise, and it
                        // made a normal empty result read like a configuration
                        // problem.
                        if (!scoped && !linksOnly && installedN - enabledN >= 5) {
                            append(" — ${installedN - enabledN} of your installed extensions are turned off")
                        }
                        if (scoped && !originless) {
                            append(
                                "\nOnly the extension this title came from is searched " +
                                    "(Settings → Playback & Servers → Server search)."
                            )
                        }
                        if (originless && !linksOnly) {
                            // Say what WAS asked, because this title has no
                            // extension of its own: the addons that resolve an item
                            // by its id — which is exactly what Stremio asks for a
                            // catalogue id (see ContentRepository.streamsForInner).
                            val addons = providers.count {
                                it.config.enabled && (
                                    it.config.type == ProviderType.STREMIO ||
                                        it.config.type == ProviderType.NUVIO
                                    )
                            }
                            if (addons > 0) {
                                append("\nThis title came from Hikari's own catalogue, so the ")
                                append("$addons playback addon(s) that resolve it by id were asked.")
                            }
                        }
                        // Across the whole pass: how many extensions were asked,
                        // how many answered with servers, and why the rest came
                        // back empty. This is what separates "no extension has
                        // this title" from "most of them could not load".
                        // Extensions behind a verification wall are left out of
                        // the pass (and of this count) entirely, so no host name
                        // and no Cloudflare wording ever appears here.
                        if (!scoped && !linksOnly) {
                            com.hikari.app.data.ContentRepository
                                .crossSummary(title = m?.title)?.let {
                                    append("\n").append(it)
                                }
                        }
                        if (!reason.isNullOrBlank()) append("\n" + reason)
                    }
                    note
                }
                if (sweepBusy) {
                    // The background sweep is still working, so the search is
                    // still going — on purpose, while the video plays. Say the
                    // count so far AND that more is coming, so the number on
                    // screen is a running total rather than a result that later
                    // "jumps" to 36.
                    StreamsLive.setStatus(
                        sid,
                        if (foundCount > 0) {
                            "Found $foundCount server" + (if (foundCount == 1) "" else "s") +
                                " — still searching the remaining extensions…"
                        } else {
                            "Searching the remaining extensions in the background…"
                        },
                    )
                } else if (foundCount > 0 && !lookupComplete) {
                    // Servers are on the list, but this lookup never reached a
                    // VERDICT: it was cut short, or it joined a pass that is
                    // still running and came back before that pass finished.
                    // Saying "search finished" here is the lie the user kept
                    // reporting — "it says 5 servers and the search is finished,
                    // then on the second tap it shows all the servers" — because
                    // the pass it was waiting on was still pushing its servers
                    // into this very session while the cover said the search was
                    // over. The count is real, so it is shown; the verdict is
                    // not, so it is not claimed. The watcher below says when the
                    // list has genuinely stopped growing.
                    StreamsLive.setStatus(
                        sid,
                        "Found $foundCount server" + (if (foundCount == 1) "" else "s") +
                            " — still searching…",
                    )
                } else if (foundCount > 0) {
                    // Servers WERE found and nothing more is coming — say that
                    // the search is over, so the cover/hint never keeps reading
                    // "still searching…" after the search has actually finished
                    // (which looks exactly like a stuck search even though a full
                    // server list is in hand).
                    StreamsLive.setStatus(
                        sid,
                        "Found $foundCount server" + (if (foundCount == 1) "" else "s") +
                            " — search finished.",
                    )
                } else if (lookupComplete) {
                    StreamsLive.setStatus(sid, noResultNote())
                } else if (problemNote != null) {
                    // Already reported in the catch above; repeated here because a
                    // collector that died later could have overwritten it.
                    StreamsLive.setStatus(sid, problemNote!!)
                } else {
                    // Nothing found AND no verdict: the pass was cut short or the
                    // budget ran out while it was still working. This is the case
                    // that must never be dressed up as "no playable server found
                    // after searching N extensions" — the search may still be
                    // running and may still hand the player servers. The player
                    // is told the truth and left to its own timeout instead of
                    // being failed fast on a lie.
                    StreamsLive.setStatus(
                        sid,
                        "The search took longer than expected — it may still be running.",
                    )
                }
                // The search is only declared OVER when there is something to
                // play or a real answer: marking it done on an unfinished pass is
                // what let the player quit seconds into a search that was still
                // finding servers — and, with a sweep still running, it is what
                // made "search finished" appear over a list that was about to
                // grow from 2 servers to 36.
                if (sweepBusy) {
                    // The background sweep is still working: wait for it and THEN
                    // declare the search over (with the honest verdict when it came
                    // back empty), so the cover leaves "still searching" at the
                    // right moment — and with the FINAL count, so the number the
                    // user reads is the list they actually have.
                    app.appScope.launch {
                        val watchDeadline = System.currentTimeMillis() + SWEEP_WATCH_CAP_MS
                        while (System.currentTimeMillis() < watchDeadline &&
                            vm.backgroundSweepBusy(searchedEpisode)
                        ) {
                            delay(1_000)
                        }
                        val total = StreamsLive.flow(sid).value.size
                        if (total > 0) {
                            StreamsLive.setStatus(
                                sid,
                                "Found $total server" + (if (total == 1) "" else "s") +
                                    " — search finished.",
                            )
                        } else {
                            StreamsLive.setStatus(sid, noResultNote())
                        }
                        StreamsLive.markDone(sid)
                    }
                } else if (foundCount > 0 && !lookupComplete) {
                    // Servers are on the list and the lookup never reached a
                    // verdict — the same "still searching…" case as the status
                    // above. Declaring the search over here (as `foundCount > 0`
                    // used to do) is what let a live list be described as a
                    // finished one, so the search is followed to its real end
                    // instead: every server that arrives keeps it alive, and it
                    // ends after [STREAMS_QUIET_MS] with nothing new — or when
                    // the sweep watch cap is reached, for a list that keeps
                    // trickling servers for hours. The count is re-stated on
                    // every change, so the cover is a running total the whole
                    // time and never has to "jump" from 5 to 43.
                    app.appScope.launch {
                        var lastSeen = StreamsLive.flow(sid).value.size
                        var quietSince = System.currentTimeMillis()
                        val watchDeadline = System.currentTimeMillis() + SWEEP_WATCH_CAP_MS
                        while (System.currentTimeMillis() < watchDeadline) {
                            delay(1_500)
                            val nowCount = StreamsLive.flow(sid).value.size
                            if (nowCount != lastSeen) {
                                lastSeen = nowCount
                                quietSince = System.currentTimeMillis()
                                StreamsLive.setStatus(
                                    sid,
                                    "Found $nowCount server" +
                                        (if (nowCount == 1) "" else "s") +
                                        " — still searching…",
                                )
                                continue
                            }
                            // A sweep that started while this list was already
                            // growing is doing the same work on the same repos:
                            // while one is alive the search is not over, however
                            // quiet the list looks this second.
                            if (vm.backgroundSweepBusy(searchedEpisode)) {
                                quietSince = System.currentTimeMillis()
                                continue
                            }
                            if (System.currentTimeMillis() - quietSince >= STREAMS_QUIET_MS) break
                        }
                        val total = StreamsLive.flow(sid).value.size
                        if (total > 0) {
                            StreamsLive.setStatus(
                                sid,
                                "Found $total server" + (if (total == 1) "" else "s") +
                                    " — search finished.",
                            )
                        } else {
                            StreamsLive.setStatus(sid, noResultNote())
                        }
                        StreamsLive.markDone(sid)
                    }
                } else if (foundCount > 0 || lookupComplete) {
                    StreamsLive.markDone(sid)
                }
            }
        }
    }

    // Saved progress for the given video (movie = null episode), or null when
    // there is nothing worth resuming (never really started, or already
    // watched through to the credits window — see WatchProgress).
    val savedProgressFor: (Episode?) -> Pair<Long, Long>? = { ep ->
        val eid = ep?.id.orEmpty()
        val h = historyForTitle.firstOrNull { it.episodeId == eid }
        var pos = h?.positionMs ?: 0L
        var dur = h?.durationMs ?: 0L
        // Fallback: arrived from History with the position in the nav arg.
        if (h == null && eid == episodeId && startPositionMs > 0L) pos = startPositionMs
        if (pos < WatchProgress.STARTED_MS) null
        else if (WatchProgress.isWatched(pos, dur)) null
        else pos to dur
    }

    // Tap handler: play immediately; the PLAYER owns the "continue from where
    // you left off?" prompt now (it holds the same history and asks in-video),
    // so a tap never silently resumes and never asks twice. The saved position
    // rides along as a hint for the player's prompt.
    val scraperImageMode = remember(providers, livePid) {
        providers.firstOrNull { it.config.id == livePid }?.let {
            it.config.type == ProviderType.UNIVERSAL &&
                com.hikari.app.providers.UniversalScraper.isImageMode(it.config)
        } == true
    }
    var readingScraper by remember { mutableStateOf(false) }
    val openScraperReader: (Episode?) -> Unit = { ep ->
        app.appScope.launch {
            readingScraper = true
            try {
                val prov = providers.firstOrNull { it.config.id == livePid }
                val item = MediaItem(
                    providerId = livePid,
                    id = mediaId,
                    title = title,
                    type = type,
                    posterUrl = posterUrl,
                )
                val eps = vm.episodes.value
                val target = ep ?: eps?.sortedWith(compareBy({ it.season }, { it.number }))?.firstOrNull()
                val pages = prov?.getStreams(item, target).orEmpty()
                    .filter { it.url.startsWith("http", true) }
                if (pages.isNotEmpty()) {
                    val key = ScraperImageStore.put(
                        title = item.title,
                        images = pages.map { it.url },
                        referer = target?.id?.takeIf { it.startsWith("http", true) },
                    )
                    Routes.safeNavigate(nav, Routes.scraperReader(key, item.title))
                } else {
                    openStreams(ep, 0L, false)
                }
            } finally {
                readingScraper = false
            }
        }
    }
    // Unaired episodes never reach the player: the source lists them, but
    // there is nothing to resolve yet — tapping one used to report "no
    // playable source", which reads as broken. They wear a badge (see the
    // cards) and tapping one says it is not out yet instead.
    // Hoisted: `tr` is @Composable and this gate is a plain function.
    val notReleasedYet = tr("Not released yet")
    fun playBlocked(ep: Episode?): Boolean {
        if (!isEpisodeUnaired(ep?.released)) return false
        runCatching {
            // Application context (not the screen's): this lambda must work
            // wherever the episode cards are composed.
            android.widget.Toast.makeText(
                HikariApp.instance.applicationContext,
                notReleasedYet,
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
        return true
    }
    val tryPlay: (Episode?) -> Unit = { ep ->
        if (!playBlocked(ep)) {
            if (scraperImageMode) openScraperReader(ep)
            else {
                val saved = savedProgressFor(ep)
                resumeHint = saved
                openStreams(ep, 0L, false)
            }
        }
    }

    // The download buttons (the play row and every episode row). Identical to
    // [tryPlay] apart from the flag the player needs: it resolves servers and
    // opens the player the same way, and the player shows its own download
    // chooser the moment a server is ready — so "download episode 7" reaches
    // exactly the same code path as "play episode 7, then tap Download".
    val tryDownload: (Episode?) -> Unit = { ep ->
        if (!playBlocked(ep)) {
            if (scraperImageMode) openScraperReader(ep)
            else {
                resumeHint = savedProgressFor(ep)
                openStreams(ep, 0L, true)
            }
        }
    }

    // What the primary action button plays: the first episode with progress
    // worth continuing (the visible page first, then the rest of the season),
    // so a returning viewer gets a "Resume S1 E3" button instead of having to
    // remember where they stopped. Null = nothing to resume.
    val resumeEp = remember(shownEps, sortedEps, historyForTitle, episodeId) {
        shownEps.firstOrNull { savedProgressFor(it) != null }
            ?: sortedEps.firstOrNull { savedProgressFor(it) != null }
    }

    // The primary TV detail action is computed before the hero is built so the
    // living-room layout can put Play directly over the artwork. Phone layout keeps
    // the existing action row below the header.
    val detailUnknownVegaKind = m?.type == MediaType.UNKNOWN &&
        m?.providerId?.startsWith("vega|") == true &&
        !episodesLoaded
    val detailIsSeries = m?.type == MediaType.SERIES ||
        detailUnknownVegaKind ||
        // An UNKNOWN site-scraper row whose episodes are still resolving is
        // a series-in-waiting (the spinner section, Play holding for episode
        // 1): hiding the section until the lookup settles is what made every
        // freshly-opened series flash "movie with no episodes". Once the
        // lookup settles the truth below takes over — movies (including
        // movie-probe corrections) drop the section, series keep it.
        (m?.type == MediaType.UNKNOWN && !episodesLoaded) ||
        // A failed lookup is not a verdict either way: the section stays so
        // the page says so and offers its retry, instead of silently
        // dropping to a Play button whose search just came back empty.
        // (A probe-corrected movie clears episodesFailed, so movies never
        // land here.)
        (episodesFailed && (m?.type == MediaType.SERIES || m?.type == MediaType.UNKNOWN)) ||
        (episodes?.isNotEmpty() == true)
    val detailCanPlay = !detailIsSeries || episodes.isNullOrEmpty()
    // The Play button prefers an episode that is actually out: a season whose
    // first listed episode has not aired yet used to open straight into "no
    // playable source". Progress always wins first (a watched episode aired).
    val detailBtnEp = if (detailCanPlay) null else (resumeEp
        ?: sortedEps.firstOrNull { !isEpisodeUnaired(it.released) }
        ?: sortedEps.firstOrNull())
    // Poster hold-menu play arrives already wanting playback: fire the primary
    // action ONCE — exactly as if Play was tapped. Movies settle with no
    // episodes (see the view-model) so they fire at once; a series fires the
    // moment its lookup settles with the continue episode (history progress)
    // or the first aired episode. A series whose lookup never settles does
    // NOT sit on the detail page: after a bounded wait it opens the player
    // anyway, and the player resolves episode 1 behind its own title card
    // (see firstEpisodeOrNull) — so hold-play on a series always plays.
    // Unaired picks toast and stop here, like a manual tap.
    var autoplayFired by remember(autoplay) { mutableStateOf(false) }
    LaunchedEffect(autoplay, episodesLoaded, detailBtnEp) {
        if (!autoplay || autoplayFired) return@LaunchedEffect
        if (episodesLoaded) {
            autoplayFired = true
            if (!playBlocked(detailBtnEp)) tryPlay(detailBtnEp)
            return@LaunchedEffect
        }
        withTimeoutOrNull(8_000L) { vm.episodesLoaded.first { it } }
        autoplayFired = true
        if (!playBlocked(detailBtnEp)) tryPlay(detailBtnEp)
    }
    // A returning viewer lands here to continue, not to start over: once the
    // episodes are known, warm the CONTINUE episode's servers in the
    // background (the page prefetch only warms the first), so the Resume tap
    // is a cache hit and starts instantly. Skipped for hold-play arrivals —
    // autoplay is already playing the target directly.
    LaunchedEffect(episodesLoaded, resumeEp?.id) {
        if (!episodesLoaded || autoplay) return@LaunchedEffect
        val target = resumeEp ?: return@LaunchedEffect
        if (target.id == sortedEps.firstOrNull()?.id) return@LaunchedEffect
        val now = m ?: return@LaunchedEffect
        vm.prefetchEpisode(now, target)
    }
    val detailActionLabel = if (scraperImageMode) I18n.t("Read") else when {
        resumeEp != null ->
            I18n.t("Resume") + if (resumeEp.season > 1)
                " S"+resumeEp.season+" E"+resumeEp.number else " E"+resumeEp.number
        detailBtnEp == null -> I18n.t("Play")
        detailBtnEp.season > 1 -> I18n.t("Play") + " S"+detailBtnEp.season+" E"+detailBtnEp.number
        else -> I18n.t("Play") + " E"+detailBtnEp.number
    }
    // What the heart saves into the Library — built from the (type-corrected)
    // meta when it has arrived, and from the nav args before that, so the
    // button works even while the origin's /meta is still in flight.
    val savedItem = remember(m, livePid, mediaId, title, posterUrl, rawType, type) {
        MediaItem(
            providerId = livePid,
            id = mediaId,
            title = m?.title ?: title,
            type = m?.type ?: type,
            posterUrl = m?.posterUrl ?: posterUrl,
            year = m?.year,
            overview = m?.overview,
            genres = m?.genres.orEmpty(),
            backdropUrl = m?.backdropUrl,
            rawType = rawType.ifBlank { m?.rawType.orEmpty() },
        )
    }
    // Television wears TMDB art on every hero/backdrop surface (see
    // Artwork.tvHeroModel): kick that lookup off the moment the title opens,
    // from nav args alone, instead of waiting for the provider's own detail
    // response — so the high-quality backdrop is already in flight (usually
    // already cached) when the page paints. The phone does the same for its
    // own header art (see Hero): a donghua the matcher knows gets its
    // TMDB/AniList/Bangumi art even when the provider shipped nothing.
    LaunchedEffect(savedItem) { Artwork.prefetch(savedItem); Artwork.retryMiss(savedItem) }
    val isSaved = favorites.any { it.uniqueId == savedItem.uniqueId }
    // A title joins the Library THROUGH a category, so the button opens the
    // picker rather than saving blind: "Add to library" asks which categories
    // (the type-appropriate one is pre-ticked, so the common case is one tap),
    // and an already-saved title re-files from the same sheet — the "Move to"
    // of the Library screen, in the place the user actually is.
    val savedCategories: Set<String> = filings[savedItem.uniqueId].orEmpty()
    val defaultCategories: Set<String> = when (savedItem.type) {
        MediaType.MOVIE -> setOf(LibraryCategory.MOVIES)
        MediaType.SERIES -> setOf(LibraryCategory.SERIES)
        else -> emptySet()
    }
    val openLibrary: () -> Unit = { librarySheet = true }
    var showDlChoice by remember { mutableStateOf(false) }
    var showDlSelect by remember { mutableStateOf(false) }
    var showDlServer by remember { mutableStateOf(false) }
    // Destination asked once per batch (in-app or phone), with the picked
    // server stashed while it is open — see confirmDlServer below.
    var showDlDest by remember { mutableStateOf(false) }
    var dlPendingSrv by remember { mutableStateOf<StreamSource?>(null) }
    // Quality asked once per batch (see askBatchQuality): the picked server's
    // own variants, with the destination stashed alongside while open.
    var showDlQuality by remember { mutableStateOf(false) }
    var dlQualityOpts by remember { mutableStateOf(listOf<Pair<Int, Long>>()) }
    var dlPendingKind by remember { mutableStateOf(com.hikari.app.download.DownloadKind.OFFLINE) }
    // Audio asked once per batch after the quality (see askBatchAudio): the
    // picked server's own rendition names, with the chosen height/bandwidth
    // stashed alongside while open.
    var showDlAudio by remember { mutableStateOf(false) }
    var dlAudioOpts by remember { mutableStateOf(listOf<String>()) }
    var dlPendingH by remember { mutableStateOf(0) }
    var dlPendingBw by remember { mutableStateOf(0L) }
    var dlAudioClaimed by remember { mutableStateOf(false) }
    // ChoiceDialog fires onDismiss() and THEN onPick() on every tap — so a tap
    // on a quality would queue the batch twice (Highest from the dismiss,
    // then the pick). The dismiss path therefore only queues when no pick
    // claimed the dialog, checked after the tap's own pick has run.
    var dlQualityClaimed by remember { mutableStateOf(false) }
    var dlChecked by remember { mutableStateOf(setOf<String>()) }
   var dlTargets by remember { mutableStateOf(listOf<Episode>()) }
    var dlServers by remember { mutableStateOf(listOf<StreamSource>()) }
    // Per-episode downloadable cache: the picker sweep's own answers, keyed by
    // episode. The queue reuses these instead of re-searching from scratch, so a
    // picked server can never miss at queue time just because the second search
    // timed out or the origin died between the two passes.
    val dlCache = remember { mutableStateMapOf<String, List<StreamSource>>() }
    fun dlEpKey(ep: Episode): String = ep.id.ifBlank { "s" + ep.season + "e" + ep.number }
    // Normalized server name: case-insensitive, without the quality/audio tail
    // ("PlayIMDb | 1080p FHD | Original-Audio" vs "PlayIMDb | 1080p FHD") and
    // without dedup counts (" (2)"), so the same mirror matches across passes.
    fun normDlName(n: String): String = n.lowercase().trim().substringBefore("|").trim().replace(Regex("\\s*\\(\\d+\\)\\s*$"), "").trim()
    var dlBusy by remember { mutableStateOf(false) }
    // True while the batch union is still growing. Rows stay clickable the
    // whole time: the list opens on the first server instead of after the
    // whole sweep, and late servers append without disturbing a tap.
    var dlSearching by remember { mutableStateOf(false) }
    var dlLookupJob by remember { mutableStateOf<Job?>(null) }
    // Lookup generation: the server search runs in the background while the
    // episode picker stays open with its progress line. Tapping Cancel bumps
    // this, so a stale lookup can never pop the server picker (or its toast)
    // after the user has already backed out.
    var dlGen by remember { mutableStateOf(0) }
    var dlMsg by remember { mutableStateOf<String?>(null) }
    // The scope the batch search runs in (see the scope question below):
    // false searches every installed extension like playback does, true
    // confines the batch to the title's own extension. Set when the search
    // starts and threaded through every re-lookup of the chain.
    var dlOriginOnly by remember { mutableStateOf(false) }
    // Episodes waiting on the scope question (see showDlScope below).
    var dlScopePending by remember { mutableStateOf(listOf<Episode>()) }
    var showDlScope by remember { mutableStateOf(false) }
    val batchItem = m ?: savedItem
    // Television episode stills, straight from TMDB (see TmdbStills): the
    // TMDB still wins (desktop parity), the provider's own thumbnail is the
    // fallback, keyed by (season, episode) with a position fallback for
    // absolute-numbered donghua seasons. The shelf keeps its old art when
    // TMDB has nothing. Loaded once the episode list for the shown
    // season is known, so switching seasons re-asks for the new range.
    val tvEpStills = remember { mutableStateMapOf<Pair<Int, Int>, String>() }
    // TMDB episode titles/overviews keyed the same way, so a card whose
    // provider shipped no name or synopsis still prints something true (see
    // TvCs3EpisodeCard) instead of "Episode 12" + "No description available".
    val tvEpMeta = remember { mutableStateMapOf<Pair<Int, Int>, Pair<String?, String?>>() }
    // Television screenshots strip, straight from TMDB (see TmdbScreenshots):
    // the reference client's own Screenshots section. Backdrops only, capped
    // at 8 — a title with no backdrops simply shows no section.
    val tvScreenshots = remember { mutableStateListOf<String>() }
    // Television banner owns the desktop rule: the title's own TMDB
    // backdrops (plural) rotate behind the page; provider art only when
    // TMDB has nothing, and never flashing before TMDB lands.
    val tvBgUrls = remember { mutableStateListOf<String>() }
    var tvBgIndex by remember { mutableStateOf(0) }
    if (isTvLayout) {
        LaunchedEffect(m?.uniqueId, sortedEps) {
            tvEpStills.clear()
            tvEpMeta.clear()
            if (!detailIsSeries || sortedEps.isEmpty()) return@LaunchedEffect
            val data = TmdbStills.stillsDataFor(batchItem, sortedEps.map { it.season }.toSet())
            for ((season, ss) in data) {
                for ((num, rec) in ss.byNumber) {
                    if (rec.still?.isNotBlank() == true) tvEpStills[season to num] = rec.still
                    if (rec.name != null || rec.overview != null) {
                        tvEpMeta[season to num] = rec.name to rec.overview
                    }
                }
            }
            // Absolute-numbered seasons (a 300-episode donghua "season 1"
            // that TMDB splits into seasons of ~100) run past TMDB's
            // per-season count: fall back to the episode's POSITION in the
            // season, like the desktop client.
            val bySeason = sortedEps.groupBy { it.season }
            for ((season, eps) in bySeason) {
                val ordered = data[season]?.ordered ?: continue
                eps.forEachIndexed { idx, ep ->
                    val key = season to ep.number
                    val rec = ordered.getOrNull(idx)
                    if (!tvEpStills.containsKey(key)) {
                        rec?.still?.takeIf { it.isNotBlank() }?.let { tvEpStills[key] = it }
                    }
                    if (!tvEpMeta.containsKey(key) && (rec?.name != null || rec?.overview != null)) {
                        tvEpMeta[key] = rec?.name to rec?.overview
                    }
                }
            }
        }
        // The banner resolves with the title, not the episode list: a movie
        // has no seasons but still gets its rotating backdrop.
        LaunchedEffect(m?.uniqueId) {
            tvBgUrls.clear()
            tvBgIndex = 0
            val item = batchItem ?: return@LaunchedEffect
            Artwork.retryMiss(item)
            tvBgUrls.addAll(
                TmdbScreenshots.backdropsFor(item).map { it.replace("/w1280/", "/original/") }
            )
        }
        LaunchedEffect(m?.uniqueId, tvBgUrls.size) {
            while (tvBgUrls.size > 1) {
                delay(9_000L)
                tvBgIndex = (tvBgIndex + 1) % tvBgUrls.size
            }
        }
        // Screenshots ride the same title trigger, not the episode list: a
        // movie has no seasons but still gets its strip.
        LaunchedEffect(m?.uniqueId) {
            tvScreenshots.clear()
            val item = batchItem ?: return@LaunchedEffect
            tvScreenshots.addAll(TmdbScreenshots.backdropsFor(item))
        }
    }
    fun dlLabel(e: Episode): String {
        val base = if (e.season > 1) "S" + e.season + " E" + e.number else "E" + e.number
        val nm = e.name?.trim().orEmpty()
        return if (nm.isBlank()) base else base + " - " + nm.take(60)
    }
    // The batch's server search: EVERY selected episode is asked (not just
    // the first), and the picker opens on the UNION — the same servers the
    // single download lists — so the server page appears whenever ANY picked
    // episode has a downloadable server. The episode picker stays open
    // underneath with a live "(i/N)…" progress line, and Cancel there bumps
    // [dlGen] so a stale search can never pop the picker after backing out.
    // Cancels an in-flight batch server search (the "Finding servers…"
    // phase) and drops the stuck state with it: the progress line clears
    // and every button gated on the search lights up again. Backing out of
    // either dialog, or its own Cancel button, lands here.
    fun cancelDlLookup() {
        dlLookupJob?.cancel()
        dlLookupJob = null
        dlGen++
        dlSearching = false
        dlBusy = false
        dlMsg = null
    }
   fun resolveFirstServer(eps: List<Episode>, originOnly: Boolean) {
        if (eps.isEmpty() || dlBusy) return
        dlLookupJob?.cancel()
        dlTargets = eps
        dlCache.clear()
        dlOriginOnly = originOnly
        val gen = dlGen + 1
        dlGen = gen
        // Same downloadable rule the sweep filters by (see serversForBatch).
        fun dlOk(s: StreamSource) =
            !s.isTorrent && !s.externalUrl && s.ytId.isNullOrBlank() && s.url.startsWith("http", ignoreCase = true)
        val seen = LinkedHashSet<String>()
        val union = ArrayList<StreamSource>()
        dlServers = emptyList()
        dlMsg = "Finding servers (1/" + eps.size + ")…"
        dlBusy = false
        dlSearching = true
        showDlChoice = false
        showDlSelect = false
        showDlServer = true
    fun publishLive(servers: List<StreamSource>) {
        if (gen != dlGen) return
        var changed = false
        for (s in servers) {
            // The picker lists only what can actually download (same rule
            // the sweep filters by): a torrent, external or blank-URL row
            // used to sit in the picker and then toast "No downloadable
            // server found" the moment it was picked.
            if (!dlOk(s)) continue
            // One row per server, not one row per episode carrying it:
            // the same mirror repeats across episodes with a different
            // URL, and printing it N times is what made a small sweep
                // look like dozens of rows.
                if (seen.add(s.providerId + "|" + s.name)) {
                    union.add(s)
                    changed = true
                }
            }
            if (changed) {
                dlServers = ArrayList(union)
                dlMsg = "Found " + union.size + " server" + (if (union.size == 1) "" else "s") + " — still searching…"
            }
        }
        var job: Job? = null
        job = scope.launch {
            try {
                // Bounded per episode (see serversForBatch), and live:
                // every server the running lookup finds is published
                // through [publishLive] while slower providers — and later
                // episodes — keep searching behind it.
               suspend fun sweep(useOrigin: Boolean) {
                    for ((i, ep) in eps.withIndex()) {
                        if (gen != dlGen) return
                        if (union.isEmpty()) {
                            dlMsg = "Finding servers (" + (i + 1) + "/" + eps.size + ")…"
                        }
                        val epKey = dlEpKey(ep)
                        val servers = runCatching {
                            vm.serversForBatch(batchItem, ep, useOrigin, onServers = { prog ->
                                dlCache[epKey] = prog
                                withContext(Dispatchers.Main) { publishLive(prog) }
                            })
                        }.getOrDefault(emptyList())
                        if (servers.isNotEmpty()) dlCache[epKey] = servers
                        publishLive(servers)
                    }
                }
                sweep(originOnly)
                if (gen != dlGen) return@launch
                if (union.isEmpty() && originOnly) {
                    // "This extension" has nothing for this title (the origin
                    // itself answered empty): fall back to the all-extensions
                    // sweep before giving up, so a dead origin never sinks
                    // the batch picker.
                    dlOriginOnly = false
                    dlMsg = "This extension has nothing — searching all extensions…"
                    sweep(false)
                }
                if (gen != dlGen) return@launch
                if (union.isEmpty()) {
                    dlMsg = null
                    showDlServer = false
                    runCatching { android.widget.Toast.makeText(context, "No downloadable server found.", android.widget.Toast.LENGTH_SHORT).show() }
                } else {
                    dlMsg = null
                    dlServers = ArrayList(union)
                }
            } finally {
                if (dlLookupJob === job) dlLookupJob = null
                // A cancelled/superseded search must never touch the state its
                // replacement owns: backing out or picking already cleared or
                // replaced these. Only the live generation settles them.
                if (gen == dlGen) {
                    dlSearching = false
                    dlBusy = false
                    if (!showDlServer) dlMsg = null
                }
            }
        }
        dlLookupJob = job
    }
    // Server picked for a batch: stash it and ask the destination ONCE — the
    // same two the player's own Download button offers (in-app or phone) —
    // and every episode queues there. The queueing itself then runs in the
    // background with no modal: each episode lands in the Downloads section
    // the moment it is queued, where it can be paused, resumed or deleted,
    // and the app stays usable throughout.
    fun confirmDlServer(srv: StreamSource) {
        if (dlBusy) return
        // The tap wins over the tail: stop appending the moment a server is
        // picked, so the finished union can never pop the picker back over the
        // destination/quality questions. The queue pass re-resolves every
        // episode itself (see startBatchDownload).
        dlLookupJob?.cancel()
        dlLookupJob = null
        dlGen++
        dlSearching = false
        dlMsg = null
        dlBusy = false
        showDlServer = false
        if (dlTargets.isEmpty()) return
        dlPendingSrv = srv
        showDlDest = true
    }
    // The batch's per-episode match for the picked server: same name on the
    // same provider first, then either half, then whatever resolved — the
    // same preference the single-download queue uses.
    // Only servers with a real URL can win (a blank match used to queue
    // an unplayable task), and an empty list returns null instead of
    // crashing on [0].
   fun matchDlServer(servers: List<StreamSource>, srv: StreamSource): StreamSource? {
        // Only servers that can actually download can win: same downloadable
        // rule the sweep filters by (no torrents, no external opens, no blank
        // urls). The normalized-name step lets the same mirror match across
        // passes even when its quality/audio tail differs.
        val usable = servers.filter { s -> !s.isTorrent && !s.externalUrl && s.ytId.isNullOrBlank() && s.url.startsWith("http", ignoreCase = true) }
        if (usable.isEmpty()) return null
        return usable.firstOrNull { it.name == srv.name && it.providerId == srv.providerId }
            ?: usable.firstOrNull { it.providerId == srv.providerId }
            ?: usable.firstOrNull { it.name == srv.name }
            ?: usable.firstOrNull { normDlName(it.name) == normDlName(srv.name) }
            ?: usable[0]
    }
    fun startBatchDownload(srv: StreamSource, kind: com.hikari.app.download.DownloadKind, preferredHeight: Int, preferredBandwidth: Long, preferredAudio: String = "") {
        showDlDest = false
        showDlAudio = false
        val eps = dlTargets
        if (eps.isEmpty()) return
        fun epTag(ep: Episode): String =
            if (ep.season > 1) "S" + ep.season + " E" + ep.number else "E" + ep.number
        runCatching { android.widget.Toast.makeText(context, "Queueing " + eps.size + " episodes… see Downloads to manage them.", android.widget.Toast.LENGTH_SHORT).show() }
        scope.launch {
            dlBusy = true
            var done = 0
            val failed = ArrayList<String>()
            try {
               for (ep in eps) {
                    // The picker's own answers first: reuse the cached per-episode
                    // servers so a picked entry can never miss just because the
                   // second search timed out. Only episodes with no cache hit
                    // pay for a fresh lookup, with the same origin-only fallback.
                    val epCacheKey = dlEpKey(ep)
                    var servers = dlCache[epCacheKey]?.filter { s -> !s.isTorrent && !s.externalUrl && s.ytId.isNullOrBlank() && s.url.startsWith("http", ignoreCase = true) }.orEmpty()
                    if (servers.isEmpty()) {
                        servers = runCatching { vm.serversForBatch(batchItem, ep, dlOriginOnly) }.getOrDefault(emptyList())
                    }
                    if (servers.isEmpty()) {
                        runCatching { delay(1500L) }
                        servers = runCatching { vm.serversForBatch(batchItem, ep, dlOriginOnly) }.getOrDefault(emptyList())
                    }
                    if (servers.isEmpty() && dlOriginOnly) {
                        // Origin-only scope found nothing for this episode: fall
                        // back to the all-extensions sweep before giving up, so
                        // one missing title doesn't sink the batch.
                        runCatching { com.hikari.app.data.Logs.log("Download", "batch origin-only empty — sweeping all extensions") }
                        servers = runCatching { vm.serversForBatch(batchItem, ep, false) }.getOrDefault(emptyList())
                    }
                    if (servers.isNotEmpty()) dlCache[epCacheKey] = servers
                    if (servers.isEmpty()) {
                        failed.add(epTag(ep))
                        continue
                    }
                    val src = matchDlServer(servers, srv)
                    if (src == null) {
                        failed.add(epTag(ep))
                        continue
                    }
                    val label = epTag(ep)
                    // The task id is per episode (see enqueueBatch above): a
                    // blank provider episode id collapsed the whole batch onto
                    // ONE id, so every enqueue replaced the last and only a
                    // single download survived.
                    val epKey = ep.id.ifBlank { "s" + ep.season + "e" + ep.number }
                    val task = com.hikari.app.download.DownloadTask(
                        id = com.hikari.app.download.DownloadTask.idFor(batchItem.providerId.ifBlank { "detail" }, batchItem.id, epKey, kind),
                        title = batchItem.title.ifBlank { "Video" },
                        episodeLabel = label,
                        poster = batchItem.posterUrl,
                        providerId = batchItem.providerId,
                        mediaId = batchItem.id,
                        episodeId = ep.id,
                        sourceName = src.name,
                        url = src.url,
                        headers = src.headers,
                        isM3u8 = src.isM3u8 || src.url.substringBefore("?").lowercase().contains(".m3u8"),
                        subtitles = src.subtitles,
                        preferredHeight = preferredHeight,
                        preferredBandwidth = preferredBandwidth,
                        preferredAudio = preferredAudio,
                        kind = kind,
                        status = com.hikari.app.download.DownloadStatus.QUEUED,
                        createdAt = System.currentTimeMillis()
                    )
                    // enqueue() posts to its own scope and never throws
                    // synchronously, so the count is gated on the handover —
                    // and every skip is named, never silent.
                    if (runCatching { com.hikari.app.download.DownloadsRepository.enqueue(context, task) }.isSuccess) done++
                    else failed.add(label)
                }
            } finally {
                // The queueing pass must never strand the busy flag (a throw
                // or a cancelled screen had the same freezing effect as the
                // lookup's stuck state above).
                dlBusy = false
            }
            runCatching {
                android.widget.Toast.makeText(
                    context,
                    when {
                        done > 0 && failed.isEmpty() ->
                            "Queued " + done + " download" + (if (done == 1) "" else "s") + " from " + srv.name + " — see Downloads."
                        done > 0 ->
                            "Queued " + done + " of " + eps.size + " — no server for " + failed.take(4).joinToString(", ") + (if (failed.size > 4) "…" else "") + "."
                        else ->
                            "No episodes could be queued" + (if (failed.isNotEmpty()) " — no server for " + failed.take(3).joinToString(", ") + (if (failed.size > 3) "…" else "") + "." else ".")
                    },
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    // The second once-per-batch question (destination was the first): read
    // the picked server's own qualities off the first episode — the same
    // "Choose quality" the single download asks — and queue the whole batch
    // at the answer. A single-variant source skips the question and goes
    // straight in at Highest.
    fun askBatchQuality(srv: StreamSource, kind: com.hikari.app.download.DownloadKind) {
        showDlDest = false
        // The picked server is already in hand from the live list: probe ITS
        // own playlist directly instead of re-running the whole search, so a
        // tap on an instantly shown server starts the flow instantly.
        if (srv.url.isBlank() || dlTargets.isEmpty()) {
            runCatching { android.widget.Toast.makeText(context, "No downloadable server found.", android.widget.Toast.LENGTH_SHORT).show() }
            return
        }
        val src = srv
        scope.launch {
            val isHls = src.isM3u8 || src.url.substringBefore("?").lowercase().contains(".m3u8")
            if (!isHls) {
                startBatchDownload(srv, kind, 0, 0L)
                return@launch
            }
            val quals = runCatching {
                com.hikari.app.download.DownloadEngine.hlsQualities(src.url, src.headers)
            }.getOrDefault(emptyList())
            if (quals.size <= 1) {
                startBatchDownload(srv, kind, 0, 0L)
                return@launch
            }
            dlPendingSrv = srv
            dlPendingKind = kind
            dlQualityOpts = quals
            dlQualityClaimed = false
            showDlQuality = true
        }
    }
    // The third once-per-batch question (destination was first, quality
    // second): the picked server's own audio renditions off the first
    // episode — "English", "Hindi", the playlist's own default first. One
    // answer rides every episode; an episode whose playlist lacks the named
    // rendition falls back to its own first audio (see preferredAudio), so a
    // mixed batch never fails on this question. A single-audio source skips
    // the question and goes straight in at the default.
    fun askBatchAudio(srv: StreamSource, kind: com.hikari.app.download.DownloadKind, h: Int, bw: Long) {
        if (srv.url.isBlank() || dlTargets.isEmpty()) {
            runCatching { android.widget.Toast.makeText(context, "No downloadable server found.", android.widget.Toast.LENGTH_SHORT).show() }
            return
        }
        val src = srv
        scope.launch {
            val audios = if (src.isM3u8 || src.url.substringBefore("?").lowercase().contains(".m3u8")) {
                runCatching {
                    com.hikari.app.download.DownloadEngine.hlsAudios(src.url, src.headers)
                }.getOrDefault(emptyList())
            } else emptyList()
            if (audios.size <= 1) {
                startBatchDownload(srv, kind, h, bw, "")
                return@launch
            }
            dlPendingSrv = srv
            dlPendingKind = kind
            dlPendingH = h
            dlPendingBw = bw
            dlAudioOpts = audios
            dlAudioClaimed = false
            showDlAudio = true
        }
    }
    val openDlChoice: () -> Unit = {
        if (batchItem.type == MediaType.SERIES && sortedEps.size > 1) showDlChoice = true
        else tryDownload(detailBtnEp)
    }
    if (showDlChoice) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { cancelDlLookup(); showDlChoice = false },
            title = { androidx.compose.material3.Text("Download episodes") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    androidx.compose.material3.Text(sortedEps.size.toString() + " episodes. Pick specific ones or grab everything from one server.")
                    val msg = dlMsg
                    if (msg != null) {
                        Spacer(Modifier.height(6.dp))
                        androidx.compose.material3.Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(2.dp))
                        // The finding phase used to have no way out: a tap that
                        // only meant to peek at the episode list left the
                        // dialog counting with every button greyed. This ends
                        // the search and leaves the dialog open to re-tap.
                        androidx.compose.material3.TextButton(onClick = { cancelDlLookup() }) {
                            androidx.compose.material3.Text("Cancel search")
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { dlChecked = sortedEps.map { it.id }.toSet(); showDlChoice = false; showDlSelect = true }) { androidx.compose.material3.Text("Select episodes") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        dlScopePending = sortedEps.toList()
                        showDlChoice = false
                        showDlScope = true
                    },
                    enabled = !dlBusy
                ) { androidx.compose.material3.Text("Download all") }
            }
        )
    }
    if (showDlSelect) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { cancelDlLookup(); showDlSelect = false },
            title = { androidx.compose.material3.Text("Select episodes") },
            text = {
                Column(Modifier.heightIn(max = 420.dp)) {
                    val msg = dlMsg
                    if (msg != null) {
                        androidx.compose.material3.Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        androidx.compose.material3.TextButton(onClick = { cancelDlLookup() }) {
                            androidx.compose.material3.Text("Cancel search")
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        androidx.compose.material3.TextButton(onClick = { dlChecked = sortedEps.map { it.id }.toSet() }) { androidx.compose.material3.Text("All") }
                        androidx.compose.material3.TextButton(onClick = { dlChecked = emptySet() }) { androidx.compose.material3.Text("None") }
                    }
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(sortedEps, key = { it.id }) { ep ->
                            val on = dlChecked.contains(ep.id)
                            Row(Modifier.fillMaxWidth().clickable { dlChecked = if (on) dlChecked - ep.id else dlChecked + ep.id }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.Checkbox(checked = on, onCheckedChange = { c -> dlChecked = if (c) dlChecked + ep.id else dlChecked - ep.id })
                                Spacer(Modifier.width(8.dp))
                                androidx.compose.material3.Text(dlLabel(ep), maxLines = 2)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        val picked = sortedEps.filter { dlChecked.contains(it.id) }
                        if (picked.isEmpty()) {
                            runCatching { android.widget.Toast.makeText(context, "Select at least one episode.", android.widget.Toast.LENGTH_SHORT).show() }
                        } else {
                            showDlSelect = false
                            dlScopePending = picked
                            showDlScope = true
                        }
                    },
                    enabled = !dlBusy && dlChecked.isNotEmpty()
                ) { androidx.compose.material3.Text("Download (" + dlChecked.size + ")") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { cancelDlLookup(); showDlSelect = false }) { androidx.compose.material3.Text("Cancel") }
            }
        )
    }
    // The scope question (asked once per batch, before the search): the same
    // choice playback offers — this extension, or every installed extension
    // like the player's own server list. The single download shows "so many
    // servers" because it rides playback's all-extension sweep; the batch
    // used to ask only the title's own extension and reported none.
    if (showDlScope) {
        ChoiceDialog(
            title = tr("Search servers in"),
            items = listOf(
                ChoiceItem(
                    key = "all",
                    label = tr("All extensions"),
                    supporting = tr("Every installed source, like playback"),
                ),
                ChoiceItem(
                    key = "origin",
                    label = tr("This extension"),
                    supporting = tr("Only the extension this title was opened from"),
                ),
            ),
            selectedKey = "all",
            onPick = { key ->
                showDlScope = false
                resolveFirstServer(dlScopePending, key == "origin")
            },
            onDismiss = { showDlScope = false },
        )
    }
    if (showDlServer) {
        // Television wears the desktop dialog language (elevated black box,
        // 12dp corners, white/muted text); the phone keeps its own dialog.
        val tvDl = com.hikari.app.tv.TvMode.current()
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {
                if (!dlBusy) {
                    cancelDlLookup()
                    showDlServer = false
                }
            },
            shape = if (tvDl) RoundedCornerShape(com.hikari.app.tv.TvDesktop.CornerLarge)
            else androidx.compose.material3.AlertDialogDefaults.shape,
            containerColor = if (tvDl) com.hikari.app.tv.TvDesktop.SurfaceElevated
            else androidx.compose.material3.AlertDialogDefaults.containerColor,
            title = {
                androidx.compose.material3.Text(
                    "Pick server (" + dlTargets.size + " episodes)",
                    color = if (tvDl) com.hikari.app.tv.TvDesktop.TextPrimary
                    else androidx.compose.material3.AlertDialogDefaults.titleContentColor,
                )
            },
            text = {
                Column(Modifier.heightIn(max = 420.dp).fillMaxWidth()) {
                    if (dlSearching) {
                        androidx.compose.material3.Text(
                            if (dlServers.isEmpty()) "Finding servers…"
                            else "Found " + dlServers.size + " server" + (if (dlServers.size == 1) "" else "s") + " — still searching…",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (tvDl) com.hikari.app.tv.TvDesktop.TextMuted
                            else MaterialTheme.colorScheme.primary,
                        )
                        if (dlServers.isNotEmpty()) {
                            androidx.compose.material3.TextButton(onClick = { cancelDlLookup() }) {
                                androidx.compose.material3.Text("Stop search")
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    LazyColumn(Modifier.heightIn(max = 340.dp).fillMaxWidth()) {
                        items(dlServers, key = { it.providerId + "|" + it.name }) { srv ->
                            Column(Modifier.fillMaxWidth().clickable(enabled = !dlBusy) { confirmDlServer(srv) }.padding(vertical = 8.dp)) {
                                androidx.compose.material3.Text(
                                    srv.name,
                                    maxLines = 1,
                                    color = if (tvDl) com.hikari.app.tv.TvDesktop.TextPrimary else Color.Unspecified,
                                )
                                val sub = listOf(srv.providerName, srv.details).filter { it.isNotBlank() }.joinToString(" - ")
                                if (sub.isNotBlank()) androidx.compose.material3.Text(
                                    sub,
                                    maxLines = 1,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (tvDl) com.hikari.app.tv.TvDesktop.TextMuted
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        cancelDlLookup()
                        showDlServer = false
                    },
                    enabled = !dlBusy
                ) {
                    androidx.compose.material3.Text(if (dlSearching) "Stop and close" else "Cancel")
                }
            }
        )
    }
    if (showDlDest) {
        val pending = dlPendingSrv
        ChoiceDialog(
            title = tr("Download"),
            items = listOf(
                ChoiceItem(
                    key = "hikari",
                    label = tr("In Hikari"),
                    supporting = tr("Kept offline inside the app"),
                ),
                ChoiceItem(
                    key = "phone",
                    label = tr("Phone storage"),
                    supporting = tr("Saved to your device's Downloads folder"),
                ),
            ),
            selectedKey = null,
            onPick = { key ->
                if (pending != null) {
                    askBatchQuality(
                        pending,
                        if (key == "phone") com.hikari.app.download.DownloadKind.EXPORT
                        else com.hikari.app.download.DownloadKind.OFFLINE,
                    )
                } else {
                    showDlDest = false
                }
            },
            onDismiss = { showDlDest = false },
        )
    }
    if (showDlQuality) {
        val pending = dlPendingSrv
        ChoiceDialog(
            title = tr("Choose quality"),
            items = buildList {
                add(
                    ChoiceItem(
                        key = "best",
                        label = tr("Highest quality"),
                        supporting = tr("The best this server offers"),
                    )
                )
                dlQualityOpts.forEachIndexed { index, q ->
                    add(
                        ChoiceItem(
                            key = "q$index",
                            label = if (q.first > 0) q.first.toString() + "p" else tr("Default quality"),
                        )
                    )
                }
            },
            selectedKey = "best",
            onPick = { key ->
                // Backing out picks Highest: the batch still runs rather than
                // dying on a dismissed sheet. The quality answer chains into
                // the audio question (see askBatchAudio).
                showDlQuality = false
                dlQualityClaimed = true
                if (pending != null) {
                    val q = key.removePrefix("q").toIntOrNull()?.let { dlQualityOpts.getOrNull(it) }
                    askBatchAudio(pending, dlPendingKind, q?.first ?: 0, q?.second ?: 0L)
                }
            },
            onDismiss = {
                showDlQuality = false
                // A tap runs this BEFORE onPick (see dlQualityClaimed): queue
                // Highest only when no pick claimed the dialog — deferred past
                // the tap so the pick's own queue wins. The dismiss answer
                // chains into the audio question like a Highest pick.
                if (pending != null && !dlQualityClaimed) {
                    scope.launch {
                        delay(150)
                        if (!dlQualityClaimed && pending != null) {
                            dlQualityClaimed = true
                            askBatchAudio(pending, dlPendingKind, 0, 0L)
                        }
                    }
                }
            },
        )
    }
    if (showDlAudio) {
        val pending = dlPendingSrv
        ChoiceDialog(
            title = tr("Choose audio"),
            items = buildList {
                add(
                    ChoiceItem(
                        key = "orig",
                        label = tr("Original audio"),
                        supporting = tr("The server's own default track"),
                    )
                )
                dlAudioOpts.forEachIndexed { index, a ->
                    add(
                        ChoiceItem(
                            key = "a$index",
                            label = a,
                        )
                    )
                }
            },
            selectedKey = "orig",
            onPick = { key ->
                // Backing out picks the default track: the batch still runs
                // rather than dying on a dismissed sheet.
                showDlAudio = false
                dlAudioClaimed = true
                if (pending != null) {
                    val a = key.removePrefix("a").toIntOrNull()?.let { dlAudioOpts.getOrNull(it) } ?: ""
                    startBatchDownload(pending, dlPendingKind, dlPendingH, dlPendingBw, a)
                }
            },
            onDismiss = {
                showDlAudio = false
                // A tap runs this BEFORE onPick (see dlAudioClaimed): queue
                // at the default track only when no pick claimed the dialog,
                // deferred past the tap so the pick's own queue wins.
                if (pending != null && !dlAudioClaimed) {
                    scope.launch {
                        delay(150)
                        if (!dlAudioClaimed && pending != null) {
                            dlAudioClaimed = true
                            startBatchDownload(pending, dlPendingKind, dlPendingH, dlPendingBw, "")
                        }
                    }
                }
            },
        )
    }
    // NOTE: no "Preparing downloads" modal any more. It used to sit here with
    // no way to dismiss while the batch queued, making the app unusable until
    // every episode resolved — the queue now runs in the background (see
    // startBatchDownload) with a toast at the start and the end, and the
    // Downloads section owns everything in between.

    if (librarySheet) {
        CategoryPickerSheet(
            title = if (isSaved) tr("Move to") else tr("Add to library"),
            subtitle = savedItem.title,
            categories = categories,
            selected = if (isSaved) savedCategories else defaultCategories,
            confirmLabel = if (isSaved) tr("Done") else tr("Add to library"),
            onConfirm = { picked ->
                scope.launch {
                    if (!isSaved) app.store.addFavorite(savedItem)
                    app.store.setFavoriteCategories(savedItem.uniqueId, picked)
                }
                librarySheet = false
            },
            onCreateCategory = { name -> app.store.addLibraryCategory(name) },
            removeLabel = tr("Remove from library"),
            onRemove = if (isSaved) {
                {
                    scope.launch { app.store.removeFavorite(savedItem.uniqueId) }
                    librarySheet = false
                }
            } else null,
            onDismiss = { librarySheet = false },
        )
    }

    // ---- The mark sheet: watched / watching / for later ----------------------
    //
    // The reference client puts a check button beside Play that files the title
    // itself — watched, currently watching, or for later — and this is that button
    // over THIS app's own records. Nothing here is a second kind of state:
    //
    //  * "watched" and "watching" are watch-history entries (the watched test is
    //    the same one the Continue Watching shelf uses — nearly at the end of a
    //    known length — so a mark and a real play cannot disagree), and
    //  * "for later" is a LIBRARY FILING into a `Watch later` category, so the
    //    title shows up in the Library screen, in My Stuff and in a backup, and
    //    can be re-filed or removed there like any other saved title.
    //
    // Which episode a mark applies to is the episode the Play button is pointing
    // at (the one being resumed, or the first) — the same target the button names,
    // so the sheet never marks something other than what Play would open. A series
    // additionally gets "all episodes", which is the reference client's
    // "mark season as watched".
    val watchLaterName = tr("Watch later")
    // Found by NAME: "Watch later" is an ordinary, editable Library category
    // (created by the sheet's first use), so a user who renames it has renamed
    // their own shelf — no hidden id is holding a filing they cannot see.
    val watchLaterCat = categories.firstOrNull { it.name.equals(watchLaterName, ignoreCase = true) }
    val markIsSeries = m?.type == MediaType.SERIES || episodes?.isNotEmpty() == true
    val markTargetEp: Episode? = if (markIsSeries) (resumeEp ?: sortedEps.firstOrNull()) else null
    val markTargetHistory = historyForTitle.firstOrNull { it.episodeId == markTargetEp?.id.orEmpty() }
    // "Watched" is exactly the condition the Continue Watching shelf treats as
    // finished (see HomeScreen and WatchProgress): reached the credits window
    // of a known length, so a mark and a real play cannot disagree.
    val markWatched = markTargetHistory?.let {
        WatchProgress.isWatched(it.positionMs, it.durationMs)
    } ?: false
    val markWatching = savedProgressFor(markTargetEp) != null
    val markAllWatched = markIsSeries && sortedEps.isNotEmpty() && sortedEps.all { ep ->
        historyForTitle.firstOrNull { it.episodeId == ep.id }?.let {
            WatchProgress.isWatched(it.positionMs, it.durationMs)
        } == true
    }
    val markLater = watchLaterCat != null && savedCategories.contains(watchLaterCat.id)
    val markAnything = markWatched || markWatching || markLater

    /** Whether an episode counts as seen for the anti-spoiler toggle — the
     *  same finished condition the Continue Watching shelf uses. */
    fun isEpSeen(ep: Episode): Boolean =
        historyForTitle.firstOrNull { it.episodeId == ep.id }?.let {
            WatchProgress.isWatched(it.positionMs, it.durationMs)
        } == true

    /** Per-episode watch state for the rows below: 0 = untouched, 1 =
     *  watching (even a second counts), 2 = watched (credits window). A
     *  movie answers through its null episode, exactly like the marks. */
    fun epWatchState(ep: Episode?): Int {
        val h = historyForTitle.firstOrNull { it.episodeId == ep?.id.orEmpty() }
            ?: return 0
        return when {
            WatchProgress.isWatched(h.positionMs, h.durationMs) -> 2
            h.positionMs >= WatchProgress.STARTED_MS -> 1
            else -> 0
        }
    }

    /** Records one mark as a history entry. `position`/`duration` are what the
     *  shelf reads, so "watched" is written as a FINISHED entry (the title's own
     *  runtime when TMDB knows it, otherwise an equal pair that simply reads as
     *  finished) and "watching" as a just-started one — which is what makes it
     *  appear under Continue watching without inventing a position the viewer
     *  has not reached. */
    fun writeMark(ep: Episode?, watched: Boolean) {
        val runtimeMs = extras?.details?.runtimeMinutes
            ?.takeIf { it > 0 }?.toLong()?.times(60_000L) ?: 0L
        val pos: Long
        val dur: Long
        if (watched) {
            // A known length makes the History row draw a full progress bar;
            // without one, an equal pair is what "finished" looks like.
            dur = runtimeMs.takeIf { it > 0L } ?: 1_800_000L
            pos = dur
        } else {
            dur = runtimeMs
            pos = 2_000L
        }
        scope.launch {
            val entry = HistoryEntry(
                providerId = livePid,
                mediaId = mediaId,
                type = m?.type ?: type,
                title = m?.title ?: title,
                posterUrl = (m?.posterUrl ?: posterUrl).takeIf { !it.isNullOrBlank() },
                episodeId = ep?.id.orEmpty(),
                episodeName = ep?.name.orEmpty(),
                episodeNumber = ep?.number ?: 0,
                seasonNumber = ep?.season ?: 0,
                positionMs = pos,
                durationMs = dur,
                watchedAt = System.currentTimeMillis(),
            )
            app.store.addHistory(entry)
            // The trackers (Settings → Trackers) hear about this too: "mark as
            // watched" is exactly the fact the player reports at the end of an
            // episode. Reporting only from the player meant marking a season
            // watched here left the user's own list untouched, which reads as
            // tracking being broken.
            runCatching {
                TrackerSync.pushWatched(app.store, TrackerSync.mediaOf(entry), entry.uniqueKey)
            }
        }
    }

    if (markSheet) {
        val targetLabel = markTargetEp
            ?.let { if (it.season > 1) "S${it.season} E${it.number}" else "E${it.number}" }
        ChoiceDialog(
            title = tr("Mark"),
            items = buildList {
                add(
                    ChoiceItem(
                        key = "watched",
                        label = if (markWatched) tr("Mark as unwatched") else tr("Mark as watched"),
                        supporting = listOfNotNull(
                            targetLabel,
                            if (markWatched) tr("Watched") else tr("Not watched yet"),
                        ).joinToString(" · "),
                    )
                )
                add(
                    ChoiceItem(
                        key = "watching",
                        label = tr("Mark as watching"),
                        supporting = if (markWatching) tr("Shows in Continue watching")
                        else tr("Puts it in Continue watching"),
                    )
                )
                add(
                    ChoiceItem(
                        key = "later",
                        label = if (markLater) tr("Remove from watch later") else tr("Add to watch later"),
                        supporting = tr("Saved in the Library under Watch later"),
                    )
                )
                if (markIsSeries && sortedEps.isNotEmpty()) {
                    add(
                        ChoiceItem(
                            key = "all",
                            label = tr("Mark all episodes as watched"),
                            supporting = if (markAllWatched) tr("All episodes are watched")
                            else "${sortedEps.size} " + tr("episodes"),
                        )
                    )
                }
                if (historyForTitle.isNotEmpty()) {
                    add(
                        ChoiceItem(
                            key = "clear",
                            label = tr("Remove from history"),
                            supporting = tr("Forgets the saved position"),
                        )
                    )
                }
            },
            selectedKey = when {
                markWatched -> "watched"
                markWatching -> "watching"
                markLater -> "later"
                else -> null
            },
            onPick = { key ->
                when (key) {
                    "watched" -> {
                        val existing = markTargetHistory
                        if (markWatched && existing != null) {
                            // Unmarking is the record going away, which is also
                            // what clears it from Continue watching and History.
                            scope.launch { app.store.removeHistory(existing.uniqueKey) }
                        } else {
                            writeMark(markTargetEp, watched = true)
                        }
                    }
                    "watching" -> {
                        // Never resets a position that is already saved: "watching"
                        // is a STATUS, and clobbering someone's 40th minute with a
                        // freshly-started entry would be the opposite of helping.
                        if (!markWatching) writeMark(markTargetEp, watched = false)
                    }
                    "later" -> scope.launch {
                        if (markLater && watchLaterCat != null) {
                            app.store.setFavoriteCategories(
                                savedItem.uniqueId, savedCategories - watchLaterCat.id,
                            )
                        } else {
                            // Being "for later" means being in the Library, so the
                            // category is created on its first use and the title is
                            // saved into it — one tap, and the Library screen, My
                            // Stuff and a backup all agree about it.
                            val cat = watchLaterCat ?: app.store.addLibraryCategory(watchLaterName)
                            app.store.addFavorite(savedItem)
                            app.store.addFavoriteCategories(savedItem.uniqueId, setOf(cat.id))
                        }
                    }
                    "all" -> {
                        val eps = sortedEps.toList()
                        scope.launch { eps.forEach { writeMark(it, watched = true) } }
                    }
                    "clear" -> {
                        val keys = historyForTitle.map { it.uniqueKey }
                        scope.launch { keys.forEach { app.store.removeHistory(it) } }
                    }
                }
            },
            onDismiss = { markSheet = false },
        )
    }

    // Arriving from watch history: once metadata/episodes are loaded, offer to
    // resume the target episode (or the movie) instead of silently jumping in.
    //
    // `resumeHandled` is [rememberSaveable] and the claim is ALSO held
    // process-wide for a few minutes ([AutoResumeGuard]), so a page that is
    // re-created — back out of the player, rotate, rebuild, re-open the same
    // History row — shows its own Play/Resume button instead of starting the
    // player and a fresh server search again by itself. That re-play is what the
    // user was watching happen ("clicking back starts loading the screen all
    // over again with the server search"), and it is also how two and three
    // player instances ended up stacked on the same episode.
    var resumeHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(meta, episodes, episodeId, startPositionMs, historyForTitle) {
        if (resumeHandled) return@LaunchedEffect
        if (episodeId.isBlank() && startPositionMs <= 0L) return@LaunchedEffect
        if (meta == null) return@LaunchedEffect
        if (episodeId.isNotBlank()) {
            val eps = episodes ?: return@LaunchedEffect
            val ep = eps.firstOrNull { it.id == episodeId } ?: return@LaunchedEffect
            resumeHandled = true
            if (!AutoResumeGuard.claim("$providerId|$mediaId|${ep.id}")) return@LaunchedEffect
            tryPlay(ep)
        } else if (episodes.isNullOrEmpty()) {
            resumeHandled = true
            if (!AutoResumeGuard.claim("$providerId|$mediaId|movie")) return@LaunchedEffect
            tryPlay(null)
        }
    }

    // ---- The header art, and the title drawn as ART over it ----
    //
    // The reference client's detail page draws the title as a transparent
    // wordmark ON the header art, it keeps that wordmark ON SCREEN as the art
    // scrolls up and away (so the title you are reading belongs to the thing you
    // are looking at, at every scroll position), and it never repeats the title
    // as text below. That is what this pair of things does:
    //
    //  * the header art is the first item of the page's own list, so it scrolls
    //    with the page instead of sitting still above it;
    //  * the wordmark is drawn OUTSIDE that list, at a position interpolated from
    //    the scroll — over the lower part of the art when the page is at the top,
    //    and at the top of the screen once the art has gone — which is exactly
    //    "it does not move with the image, it stays over it".
    //
    // The art itself comes from TMDB (the title's own logo, `logo_path`), one
    // background request per title, cached: a title whose logo is missing —
    // every extension-only row, most non-English ones — keeps the plain text
    // title exactly as before, so this can only ever ADD something.
    // The wordmark STATE itself is declared far above (next to the loading
    // cover's settings), because the Play intent has to carry it; this is the
    // one place that fetches it.
    var headerPx by remember { mutableStateOf(0) }
    var logoSizePx by remember { mutableStateOf(0) }
    // Detail page position held across visits (see ScrollKeeper): leaving
    // for an episode and coming back lands where the page was.
    val detailList = com.hikari.app.ui.components.keptListState("detail:$providerId|$mediaId")
    LaunchedEffect(m?.uniqueId) {
        val item = m ?: return@LaunchedEffect
        heroLogo = runCatching { TmdbMeta.logo(item) }.getOrNull()
    }
    val tvHeroImage = m?.let { if (isTvLayout) Artwork.tvHeroModel(it) else Artwork.heroModel(it) }
        ?: (PosterLoader.model(posterUrl) to false)

                    val tvPlayRow: @Composable (androidx.compose.ui.unit.Dp) -> Unit = { topPad ->
                    Row(
                        Modifier.padding(top = topPad),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = { tryPlay(detailBtnEp) },
                            // The reference look: a solid white pill with a
                            // dark label, readable from the sofa at a glance.
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = Color.Black,
                            ),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
                            modifier = Modifier
                                .focusRequester(playFocus)
                                .height(46.dp)
                                .tvPress(
                                    previewPass = true,
                                    onClick = { tryPlay(detailBtnEp) }
                                )
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            // Single line, always: the label alone shrinks —
                            // and ADAPTIVELY: a long episode number
                            // ("Resume E2650") steps down further so it still
                            // fits instead of cutting off. No weight on the
                            // label: it used to claim every spare dp of the
                            // button's width and stretch the white pill
                            // across the whole hero.
                            Text(
                                detailActionLabel,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Visible,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontSize = 13.sp * (11f / detailActionLabel.length.toFloat()).coerceIn(0.45f, 1f)
                                ),
                            )
                        }
                        // Save square FIRST, like the reference client's row
                        // (Play, heart, download): it mirrors the phone row's
                        // library toggle below.
                        if (isSaved) {
                            FilledTonalButton(
                                onClick = openLibrary,
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(48.dp).tvPress(
                                    previewPass = true,
                                    onClick = openLibrary,
                                )
                            ) {
                                Icon(Icons.Filled.Favorite, contentDescription = null)
                            }
                        } else {
                            OutlinedButton(
                                onClick = openLibrary,
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(48.dp).tvPress(
                                    previewPass = true,
                                    onClick = openLibrary,
                                )
                            ) {
                                Icon(Icons.Filled.FavoriteBorder, contentDescription = tr("Save"))
                            }
                        }
                        FilledTonalButton(
                            onClick = { openDlChoice() },
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(48.dp).tvPress(
                                previewPass = true,
                                onClick = { openDlChoice() }
                            )
                        ) {
                            // Material glyph, not the drawable: the drawable's
                            // own white fill ignored the button's content color
                            // and vanished on some TV themes (the "button with
                            // no icon" report).
                            Icon(Icons.Filled.Download, contentDescription = tr("Download"))
                        }
                        // Trailer LAST: the title's own trailer instead of
                        // scrolling for it.
                        extras?.trailers?.firstOrNull()?.let { tvTrailer ->
                            FilledTonalButton(
                                onClick = {
                                    openYouTubeVideo(
                                        context,
                                        tvTrailer.youtubeKey,
                                        (m?.title ?: title) + " — " + tvTrailer.name
                                    )
                                },
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(48.dp).tvPress(
                                    previewPass = true,
                                    onClick = {
                                        openYouTubeVideo(
                                            context,
                                            tvTrailer.youtubeKey,
                                            (m?.title ?: title) + " — " + tvTrailer.name
                                        )
                                    }
                                )
                            ) {
                                Icon(Icons.Filled.OndemandVideo, contentDescription = tr("Trailer"))
                            }
                        }
                    }
                    }
    val heroBlock: @Composable () -> Unit = {
        if (isTvLayout) {
            // TV detail is a single cinematic hero: artwork, title, metadata,
            // overview and the primary action live in the same focusable scene.
            val tvHeight = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * if (episodePosterStyle) 0.52f else 0.60f).dp
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(tvHeight.coerceAtLeast(340.dp))
                    
                    .onSizeChanged { headerPx = it.height }
            ) {
                // The fixed cinematic backdrop behind the LazyColumn owns ALL
                // hero art (see the page Box below): painting it here too put
                // the same image on screen twice with different treatments —
                // the duplicate-image seam from the TV photos. The hero stays
                // transparent so the one backdrop shows through everywhere.
                // The TV hero artwork is rendered once by the fixed backdrop
                // behind the LazyColumn. Keeping a second HeroArtwork here caused
                // the exact duplicate-image seam visible when the episode shelf
                // entered the viewport.
                // The full-page backdrop owns the tonal treatment; the hero-local overlay
                // used to end at the hero boundary and create the visible color cut.
                // Do not paint an opaque page-background strip at the bottom of
                // the hero. The fixed cinematic backdrop must remain visible behind
                // the episode content as the single continuous image.
                // Circular glass back button, like the reference client.
                IconButton(onClick = { nav.popBackStack() }, modifier = Modifier.align(Alignment.TopStart).padding(18.dp).size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).border(1.dp, Color.White.copy(alpha = 0.30f), CircleShape).tvPress(
                    previewPass = true, onClick = { nav.popBackStack() })) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back"), tint = Color.White, modifier = Modifier.size(20.dp))
                }
                // In poster mode the episode shelf moves OUT of the hero. This leaves
                // the whole lower part of the screen for episodes instead of squeezing them
                // into the narrow right-side panel.
                if (detailIsSeries && !episodePosterStyle) {
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .padding(top = 58.dp, end = 14.dp, bottom = 24.dp)
                            .fillMaxWidth(0.37f)
                            .fillMaxHeight(0.88f)
                            .clip(RoundedCornerShape(18.dp))
                            // Keep this alternate list view translucent too, so
                            // the same backdrop remains visible behind every detail section.
                            .background(Color.Black.copy(alpha = 0.30f))
                            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(18.dp))
                    ) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .verticalScroll(com.hikari.app.ui.components.keptScrollState("detail:$providerId|${mediaId}:tv"))
                        ) {
                            // One action row per page (desktop parity): the
                            // hero's own row below owns Play/Download/Save.
                            Spacer(Modifier.height(6.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // The reference season header inside the panel.
                                Text(
                                    if (seasons.size > 1) tr("Season %s").replace("%s", activeSeason.toString()) + " \u00b7 " + tr("Episodes (%s)").replace("%s", shownEps.size.toString())
                                    else tr("Episodes (%s)").replace("%s", shownEps.size.toString()),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = com.hikari.app.tv.Cs3.TextPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { epsNewestFirst = !epsNewestFirst },
                                    modifier = Modifier.size(36.dp).tvPress(
                                        previewPass = true,
                                        onClick = { epsNewestFirst = !epsNewestFirst }
                                    )
                                ) {
                                    Icon(
                                        if (epsNewestFirst) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                                        contentDescription = if (epsNewestFirst) tr("Newest episode first") else tr("Oldest episode first"),
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { cycleEpisodeView() },
                                    modifier = Modifier.size(36.dp).tvPress(
                                        previewPass = true,
                                        onClick = { cycleEpisodeView() }
                                    )
                                ) {
                                    Icon(
                                        // The button shows the NEXT look: rows → grid → big cards → rows.
                                        when (episodeViewMode) {
                                            0 -> Icons.Filled.GridView
                                            1 -> Icons.Filled.Dashboard
                                            else -> Icons.AutoMirrored.Filled.ViewList
                                        },
                                        contentDescription = when (episodeViewMode) {
                                            0 -> tr("Use episode poster grid")
                                            1 -> tr("Use big episode cards")
                                            else -> tr("Use episode list")
                                        },
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            // Season actions, the reference TV client's header:
                            // "Mark Season Watched" (the mark sheet's all-episodes
                            // entry) and the Anti-spoiler toggle beside it.
                            Row(
                                Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (detailIsSeries) {
                                    TextButton(
                                        onClick = { markSheet = true },
                                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 3.dp),
                                        modifier = Modifier.tvPress(previewPass = true, onClick = { markSheet = true })
                                    ) {
                                        Text(
                                            if (markAllWatched) tr("Season watched ✓") else tr("Mark Season Watched"),
                                            color = Color.White.copy(alpha = 0.9f),
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                        )
                                    }
                                }
                                TextButton(
                                    onClick = { antiSpoiler = !antiSpoiler },
                                    contentPadding = PaddingValues(horizontal = 9.dp, vertical = 3.dp),
                                    modifier = Modifier.tvPress(previewPass = true, onClick = { antiSpoiler = !antiSpoiler })
                                ) {
                                    Text(
                                        (if (antiSpoiler) "✓ " else "") + tr("Anti-spoiler"),
                                        color = if (antiSpoiler) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.9f),
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                    )
                                }
                            }
                            Row(
                                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (seasons.size > 1) {
                                    Box {
                                        OutlinedButton(
                                            onClick = { seasonExpanded = true },
                                            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 3.dp),
                                            modifier = Modifier.tvPress(previewPass = true, onClick = { seasonExpanded = true })
                                        ) {
                                            Text(tr("Season %s").replace("%s", activeSeason.toString()), maxLines = 1)
                                        }
                                        DropdownMenu(
                                            expanded = seasonExpanded,
                                            onDismissRequest = { seasonExpanded = false }
                                        ) {
                                            seasons.forEach { s ->
                                                DropdownMenuItem(
                                                    modifier = Modifier.tvPress(previewPass = true, onClick = { selectedSeason = s; rangeStart = 0; seasonExpanded = false }),
                                                    text = { Text(tr("Season %s").replace("%s", s.toString())) },
                                                    onClick = {
                                                        selectedSeason = s
                                                        rangeStart = 0
                                                        seasonExpanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                                if (ranges.isNotEmpty()) {
                                    Box {
                                        OutlinedButton(
                                            onClick = { rangeExpanded = true },
                                            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 3.dp),
                                            modifier = Modifier.tvPress(previewPass = true, onClick = { rangeExpanded = true })
                                        ) {
                                            val end = (safeStart + epPageSize).coerceAtMost(shownEps.size)
                                            Text("${safeStart + 1}–$end", maxLines = 1)
                                        }
                                        DropdownMenu(
                                            expanded = rangeExpanded,
                                            onDismissRequest = { rangeExpanded = false }
                                        ) {
                                            ranges.forEach { start ->
                                                val end = (start + epPageSize).coerceAtMost(shownEps.size)
                                                DropdownMenuItem(
                                                    modifier = Modifier.tvPress(previewPass = true, onClick = { rangeStart = start; rangeExpanded = false }),
                                                    text = { Text("${start + 1}–$end") },
                                                    onClick = {
                                                        rangeStart = start
                                                        rangeExpanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            if (pageEps.isEmpty()) {
                                Text(
                                    if (episodesLoading || !episodesLoaded) tr("Loading episodes…")
                                    else if (episodesFailed) tr("Couldn't load the episode list from this extension.")
                                    else tr("No episode list available."),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.78f),
                                    modifier = Modifier.padding(vertical = 12.dp)
                                )
                            } else if (episodePosterStyle) {
                                LazyRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(horizontal = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    itemsIndexed(
                                        pageEps,
                                        key = { index, ep -> "tv-ep-poster-" + index + "-" + ep.season + "-" + ep.number }
                                    ) { _, ep ->
                                        EpisodePosterCard(
                                            ep = ep,
                                            fallbackImage = m?.backdropUrl ?: m?.posterUrl ?: posterUrl,
                                            tmdbStill = tvEpStills[ep.season to ep.number],
                                            tmdbFirst = true,
                                            onClick = { tryPlay(ep) },
                                            onDownload = { tryDownload(ep) },
                                            modifier = Modifier.width((150f * tvEpisodeScale).dp),
                                            sizeScale = tvEpisodeScale,
                                            glass = episodeGlass,
                                            hideSpoilers = antiSpoiler && !isEpSeen(ep),
                                        )
                                    }
                                }
                            } else if (episodeBigStyle) {
                                // Big cinema cards on TV: a horizontal rail of
                                // wide cards, D-pad traversable like the grid.
                                LazyRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(horizontal = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    itemsIndexed(
                                        pageEps,
                                        key = { index, ep -> "tv-ep-big-" + index + "-" + ep.season + "-" + ep.number }
                                    ) { _, ep ->
                                        EpisodeBigCard(
                                            ep = ep,
                                            fallbackImage = m?.backdropUrl ?: m?.posterUrl ?: posterUrl,
                                            tmdbStill = tvEpStills[ep.season to ep.number],
                                            onClick = { tryPlay(ep) },
                                            onDownload = { tryDownload(ep) },
                                            modifier = Modifier.width((300f * tvEpisodeScale).dp),
                                            sizeScale = tvEpisodeScale,
                                            glass = episodeGlass,
                                            hideSpoilers = antiSpoiler && !isEpSeen(ep),
                                        )
                                    }
                                }
                            } else {
                                pageEps.forEach { ep ->
                                    val h = historyForTitle.firstOrNull { it.episodeId == ep.id }
                                    val prog = if (h != null && h.durationMs > 0) (h.positionMs.toFloat() / h.durationMs).coerceIn(0f, 1f) else 0f
                                    TvCs3EpisodeCard(
                                        ep = ep,
                                        tmdbStill = tvEpStills[ep.season to ep.number],
                                        tmdbName = tvEpMeta[ep.season to ep.number]?.first,
                                        tmdbOverview = tvEpMeta[ep.season to ep.number]?.second,
                                        fallbackBlur = m?.backdropUrl ?: m?.posterUrl ?: posterUrl,
                                        watchState = epWatchState(ep),
                                        progress = prog,
                                        hideSpoilers = antiSpoiler && !isEpSeen(ep),
                                        isAnime = m?.rawType?.contains("anime", ignoreCase = true) == true,
                                        onPlay = { tryPlay(ep) },
                                        onDownload = { tryDownload(ep) },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Spacer(Modifier.height(10.dp))
                                }
                            }
                        }
                    }
                }
                Column(
                    Modifier
                        .align(if (episodePosterStyle) Alignment.BottomStart else Alignment.TopStart)
                        // Normal episode-list mode owns the right side of the TV hero.
                        // Keep the detail text inside the remaining left area instead of
                        // letting long overviews draw underneath the episode panel.
                        .fillMaxWidth(if (episodePosterStyle) 1f else 0.60f)
                        .padding(
                            start = 42.dp,
                            end = 12.dp,
                            top = if (episodePosterStyle) 0.dp else 68.dp,
                            bottom = if (episodePosterStyle) 12.dp else 0.dp,
                        )
                ) {
                    // Provider eyebrow, like the reference client's blue
                    // extension name above the wordmark.
                    val tvProviderName = remember(providers, livePid) {
                        providers.firstOrNull { it.config.id == livePid }
                            ?.config?.name?.trim().takeUnless { it.isNullOrEmpty() }
                    }
                    if (!tvProviderName.isNullOrBlank()) {
                        Text(
                            tvProviderName,
                            color = Color(0xFF4DA3FF),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    if (!heroLogo.isNullOrBlank()) {
                        AsyncImage(model = heroLogo, contentDescription = artTitle, contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth(if (episodePosterStyle) 0.62f else 0.58f).heightIn(max = 84.dp))
                    } else {
                        Text(artTitle, fontSize = 28.sp * tvTypeScale, lineHeight = 34.sp * tvTypeScale, fontWeight = FontWeight.Bold,
                            color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(8.dp))

                    // One meta line, like the reference client: year, type,
                    // cert chip inline, runtime, genres — a single small row
                    // (no production row, no extra badges).
                    val tvMetaHead = buildList {
                        m?.year?.let { add(it.toString()) }
                        when (m?.type) {
                            com.hikari.app.data.MediaType.MOVIE -> add(tr("Movie"))
                            com.hikari.app.data.MediaType.SERIES -> add(tr("TV Series"))
                            else -> {}
                        }
                    }.joinToString("  •  ")
                    val tvRuntime = extras?.details?.runtimeMinutes?.takeIf { it > 0 }?.let { mins ->
                        if (mins >= 60) (mins / 60).toString() + "h " + (mins % 60).toString() + "m" else mins.toString() + "m"
                    }
                    val tvGenreLine = m?.genres?.takeIf { it.isNotEmpty() }?.take(3)?.joinToString(", ")
                    val tvMetaTail = listOfNotNull(tvRuntime, tvGenreLine).joinToString("  •  ")
                    val tvCert = extras?.details?.certification?.trim()?.takeIf { it.isNotEmpty() }
                    if (tvMetaHead.isNotBlank() || !tvCert.isNullOrBlank() || tvMetaTail.isNotBlank()) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (tvMetaHead.isNotBlank()) Text(
                                tvMetaHead,
                                fontSize = 13.sp * tvTypeScale,
                                fontWeight = FontWeight.Medium,
                                color = Color.White.copy(alpha = 0.88f),
                                maxLines = 1,
                            )
                            if (!tvCert.isNullOrBlank()) com.hikari.app.tv.TvCertChip(tvCert)
                            if (tvMetaTail.isNotBlank()) Text(
                                (if (tvMetaHead.isNotBlank() || !tvCert.isNullOrBlank()) "•  " else "") + tvMetaTail,
                                fontSize = 13.sp * tvTypeScale,
                                fontWeight = FontWeight.Medium,
                                color = Color.White.copy(alpha = 0.72f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                    }
                    // The reference's ratings row: small IMDb + TMDB badges
                    // only (plus the AniList badge for anime). Genres moved
                    // up into the meta line; every other source is gone.
                    if (showDetailRating) {
                        val tvBadges = ratings
                            .filter {
                                it.source == com.hikari.app.data.RatingSource.IMDB ||
                                    it.source == com.hikari.app.data.RatingSource.TMDB ||
                                    it.source == com.hikari.app.data.RatingSource.ANILIST
                            }
                            .sortedBy {
                                when (it.source) {
                                    com.hikari.app.data.RatingSource.IMDB -> 0
                                    com.hikari.app.data.RatingSource.TMDB -> 1
                                    else -> 2
                                }
                            }
                        if (tvBadges.isNotEmpty()) {
                            Cs3RatingBadges(
                                ratings = tvBadges,
                                cert = null,
                                onRatingClick = { ratingInfo = it },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            )
                        }
                    }
                    if (!displayOverview.isNullOrBlank()) {
                        // Two lines like the reference client, with its own
                        // small More button underneath — tapping text or
                        // button toggles the full overview.
                        var tvOverviewFull by remember(displayOverview) { mutableStateOf(false) }
                        Text(
                            displayOverview!!,
                            fontSize = 14.sp * tvTypeScale,
                            lineHeight = 20.sp * tvTypeScale,
                            color = Color.White.copy(alpha = 0.88f),
                            maxLines = if (tvOverviewFull) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                                .clickable { tvOverviewFull = !tvOverviewFull },
                        )
                        TextButton(
                            onClick = { tvOverviewFull = !tvOverviewFull },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.padding(top = 6.dp).tvPress(
                                previewPass = true,
                                onClick = { tvOverviewFull = !tvOverviewFull }
                            )
                        ) {
                            Text(
                                if (tvOverviewFull) tr("Less") else tr("More"),
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.9f),
                                maxLines = 1,
                            )
                        }
                    }
                    // The reference's action row sits below the plot: white
                    // Play/Resume pill + save and download squares — for
                    // series as well as movies, so the next episode is one
                    // tap away without scrolling.
                    tvPlayRow(10.dp)
                }
            }
        } else {
            Box(Modifier.fillMaxWidth().onSizeChanged { headerPx = it.height }) {
                Hero(meta, posterUrl, onBack = { nav.popBackStack() }, style = heroStyle)
            }
        }
    }

    // ---- What stays on screen while the header art leaves ----
    //
    // How far the art has scrolled away: 0 at the top of the page, 1 once it is
    // gone. The art is the list's first item, so this is its own scroll distance
    // over its own measured height (which is why the height is read from the
    // layout rather than assumed from the style). Computed HERE, above the list,
    // because the page's first content item has to reserve room for the pinned
    // wordmark (see [pinnedLogoDp]) — the same number the wordmark itself is
    // positioned and sized with.
    val headerScroll = if (detailList.firstVisibleItemIndex > 0) {
        headerPx.coerceAtLeast(1)
    } else {
        detailList.firstVisibleItemScrollOffset
    }
    val headerProgress =
        (headerScroll.toFloat() / headerPx.coerceAtLeast(1)).coerceIn(0f, 1f)
    val density = LocalDensity.current

    // The user's own size for the wordmark (Settings → App Layout → Details
    // header → Title logo size), as a multiplier over the two widths it is drawn
    // at: [logoRestFrac] while it sits low in the header art, and
    // [logoPinnedFrac] once the art has scrolled away and it is acting as the
    // page's title.
    //
    // The PINNED end takes the setting at full strength — the same multiplier is
    // applied to the pinned width itself — because that end is the one the reader
    // looks at while reading the page, and it is the end that used to ignore the
    // setting entirely: the pinned width was a constant, so raising the slider
    // enlarged the wordmark on the header art and then handed back the same small
    // title the moment the art scrolled away (the reported "when I scroll, even
    // though the logo size from the setting changes on the image, on scrolling it
    // stays the same small size").
    //
    // So: "Title logo size: 130%" means 1.3x at BOTH ends — a plain size
    // multiplier, not a curve that disagrees with the number on the slider. At
    // the default 100% both ends are exactly what they always were (rest 0.62,
    // pinned 0.34), so nobody who never touched the slider sees any change.
    val logoScale = (detailLogoPercent / 100f).coerceIn(0.5f, 1.6f)
    val logoRestFrac = (0.62f * logoScale).coerceIn(0.2f, 1f)
    val logoPinnedFrac = (0.34f * logoScale).coerceIn(0.17f, 0.72f)
    val logoFrac = logoRestFrac + (logoPinnedFrac - logoRestFrac) * headerProgress
    // How much room the pinned wordmark needs at the top of the page, in dp. Its
    // height is measured from the layout (its aspect ratio is whatever TMDB's art
    // is) and its height follows its width, so the ratio of the two fractions is
    // the ratio of the two heights. Zero while there is no wordmark to draw.
    val pinnedLogoDp = if (logoSizePx > 0 && logoFrac > 0.01f && !heroLogo.isNullOrBlank()) {
        with(density) { (logoSizePx * logoPinnedFrac / logoFrac).toDp() }
    } else {
        0.dp
    }

    Box(Modifier.fillMaxSize()) {
        if (isTvLayout) {
            // ONE image behind the whole page, like the desktop client: the
            // title's own TMDB backdrops rotate behind every section as the
            // page scrolls over them. Provider art only when TMDB has
            // nothing — and it never flashes first: the banner starts on the
            // background colour and the TMDB art crossfades in.
            if (tvBgUrls.isNotEmpty()) {
                androidx.compose.animation.Crossfade(
                    targetState = tvBgIndex.coerceIn(tvBgUrls.indices),
                    label = "tvDetailBg",
                ) { idx ->
                    com.hikari.app.tv.Cs3BackdropArt(
                        model = PosterLoader.model(tvBgUrls[idx]),
                        wide = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                com.hikari.app.tv.Cs3BackdropArt(
                    model = tvHeroImage.first,
                    wide = tvHeroImage.second && !tvBlurDetail,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

    // The status bar's height, in dp (a composable read, so it is taken here
    // rather than inside the arithmetic that positions the wordmark).
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Column(Modifier.fillMaxSize()) {
        when {
            loading -> {
                heroBlock()
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            error != null && meta == null -> {
                heroBlock()
                Box(Modifier.fillMaxSize()) {
                    EmptyState(tr("Something went wrong"), error.orEmpty(), tr("Back"), { nav.popBackStack() })
                }
            }
            else -> {
                LazyColumn(state = detailList, modifier = Modifier.fillMaxSize()) {
                    // The header art is the page's FIRST item, so it scrolls up
                    // and away as the page is read — see the header note above
                    // for what the wordmark drawn over it does while it does.
                    item(key = "hero") { heroBlock() }
                    if (isTvLayout && detailIsSeries && episodePosterStyle) {
                        item(key = "tv-poster-episodes") {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .zIndex(2f)
                                    .padding(
                                        start = 20.dp,
                                        end = 20.dp,
                                        top = 6.dp,
                                        bottom = 10.dp,
                                    )
                            ) {
                                // One action row per page (desktop parity):
                                // the hero's row owns Play/Download/Save, so
                                // this section starts at the season header.
                                // The reference season header, one-to-one:
                                // Season N + episode count left; Mark Season
                                // Watched, the anti-spoiler switch, Sort and
                                // the view-mode button right.
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                                    ) {
                                        if (seasons.size > 1) {
                                            Text(
                                                text = tr("Season %s").replace("%s", activeSeason.toString()),
                                                fontSize = 18.sp * tvTypeScale,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                            )
                                        } else {
                                            Text(
                                                text = tr("Episodes"),
                                                fontSize = 18.sp * tvTypeScale,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                            )
                                        }
                                        if (shownEps.isNotEmpty()) {
                                            Text(
                                                text = "•   ${shownEps.size} " + if (shownEps.size == 1) tr("Episode") else tr("Episodes"),
                                                color = Color.White.copy(alpha = 0.55f),
                                                fontSize = 14.sp * tvTypeScale,
                                                fontWeight = FontWeight.Medium,
                                            )
                                        }
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        if (shownEps.isNotEmpty()) {
                                            val seasonWatched = markAllWatched
                                            Surface(
                                                onClick = { markSheet = true },
                                                shape = RoundedCornerShape(8.dp),
                                                color = if (seasonWatched) Color(0xFF4ADE80).copy(alpha = 0.18f)
                                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                                border = androidx.compose.foundation.BorderStroke(
                                                    1.dp,
                                                    if (seasonWatched) Color(0xFF4ADE80) else Color.White.copy(alpha = 0.1f),
                                                ),
                                            ) {
                                                Text(
                                                    text = if (seasonWatched) "✓ " + tr("Season Watched") else tr("Mark Season Watched"),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = if (seasonWatched) Color(0xFF4ADE80) else Cs3.TextPrimary,
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                                )
                                            }
                                        }
                                        Cs3AntiSpoilerPill(checked = antiSpoiler, onChange = { antiSpoiler = it })
                                        Cs3SortChip(ascending = !epsNewestFirst, onClick = { epsNewestFirst = !epsNewestFirst })
                                        Surface(
                                            onClick = { cycleEpisodeView() },
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                                            modifier = Modifier.size(38.dp),
                                        ) {
                                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                                Icon(
                                                    imageVector = when (episodeViewMode) {
                                                        0 -> Icons.Filled.GridView
                                                        1 -> Icons.Filled.Dashboard
                                                        else -> Icons.AutoMirrored.Filled.ViewList
                                                    },
                                                    contentDescription = null,
                                                    tint = Cs3.TextPrimary,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(top = 2.dp, bottom = 14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (seasons.size > 1) {
                                        Text(
                                            tr("Seasons"),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = Color.White.copy(alpha = 0.72f),
                                        )
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            seasons.forEach { s ->
                                                OutlinedButton(
                                                    onClick = {
                                                        selectedSeason = s
                                                        rangeStart = 0
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                                    modifier = Modifier.tvPress(
                                                        enabled = isTvLayout,
                                                        previewPass = true,
                                                        onClick = {
                                                            selectedSeason = s
                                                            rangeStart = 0
                                                        }
                                                    ),
                                                ) {
                                                    Text(
                                                        tr("Season %s").replace("%s", s.toString()),
                                                        maxLines = 1,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    if (ranges.isNotEmpty()) {
                                        Text(
                                            tr("Episodes"),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = Color.White.copy(alpha = 0.72f),
                                        )
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            ranges.forEach { start ->
                                                val end = (start + epPageSize).coerceAtMost(shownEps.size)
                                                OutlinedButton(
                                                    onClick = { rangeStart = start },
                                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                                    modifier = Modifier.tvPress(
                                                        enabled = isTvLayout,
                                                        previewPass = true,
                                                        onClick = { rangeStart = start }
                                                    ),
                                                ) {
                                                    Text("${start + 1}–$end", maxLines = 1)
                                                }
                                            }
                                        }
                                    }
                                }
                                if (pageEps.isEmpty()) {
                                    Text(
                                        if (episodesLoading || !episodesLoaded) tr("Loading episodes…")
                                        else if (episodesFailed) tr("Couldn't load the episode list from this extension.")
                                        else tr("No episode list available."),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.78f),
                                        modifier = Modifier.padding(vertical = 12.dp)
                                    )
                                } else {
                                    LazyRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentPadding = PaddingValues(horizontal = 2.dp),
                                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                                    ) {
                                        itemsIndexed(
                                            pageEps,
                                            key = { index, ep -> "tv-ep-poster-shelf-" + index + "-" + ep.season + "-" + ep.number }
                                        ) { _, ep ->
                                            val h = historyForTitle.firstOrNull { it.episodeId == ep.id }
                                            val prog = if (h != null && h.durationMs > 0) (h.positionMs.toFloat() / h.durationMs).coerceIn(0f, 1f) else 0f
                                            TvCs3EpisodeCard(
                                                ep = ep,
                                                tmdbStill = tvEpStills[ep.season to ep.number],
                                                tmdbName = tvEpMeta[ep.season to ep.number]?.first,
                                                tmdbOverview = tvEpMeta[ep.season to ep.number]?.second,
                                                fallbackBlur = m?.backdropUrl ?: m?.posterUrl ?: posterUrl,
                                                watchState = epWatchState(ep),
                                                progress = prog,
                                                hideSpoilers = antiSpoiler && !isEpSeen(ep),
                                                isAnime = m?.rawType?.contains("anime", ignoreCase = true) == true,
                                                onPlay = { tryPlay(ep) },
                                                onDownload = { tryDownload(ep) },
                                                modifier = Modifier.width(((if (episodeBigStyle) 420f else 300f) * tvEpisodeScale).dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // The episode shelf used to run straight into the release
                    // details below with no air between them (reported on a TV
                    // screenshot): one breathing gap, TV series only — 40dp so
                    // the shelf and the details read as separate blocks even
                    // on dense phone-width TV windows.
                    if (isTvLayout && detailIsSeries) {
                        item(key = "tv-episode-details-spacer") {
                            Spacer(Modifier.height(40.dp))
                        }
                    }
                    if (!isTvLayout) {
                    item {
                    // The first line of the page is spaced off the header art on
                    // purpose. It used to start flush against the artwork's
                    // bottom edge (the banner's gradient ends exactly where the
                    // title begins, so the two read as one block and the title
                    // looked like it had been pushed up into the header — the
                    // user circled it on a screenshot). Styles that fade the art
                    // into the background need the gap most.
                    Column(
                        Modifier.padding(
                            start = 16.dp,
                            end = 16.dp,
                            // The pinned wordmark is drawn OVER this list (it
                            // lives in the page's own box, which is what keeps
                            // it still while the art scrolls), so as the header
                            // leaves, the first line reserves exactly the height
                            // the wordmark needs at its pinned size — a big
                            // setting must not put the year, the genres and the
                            // Play button underneath the title.
                            top = 16.dp + pinnedLogoDp * headerProgress,
                        )
                    ) {
                        // The title as TEXT is drawn only when there is no logo to
                        // draw instead: the reference client never repeats the
                        // title twice on one page, and the wordmark above is the
                        // title at that point.
                        if (heroLogo.isNullOrBlank()) {
                            Text(
                                m?.title ?: title,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (m?.year != null) {
                            Text(
                                "${m.year}  ·  ${m.type.name.lowercase()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        if (!m?.genres.isNullOrEmpty()) {
                            // Scrollable, so four long genre names ("Ciencia
                            // ficción", "Animación"…) can never run off the
                            // right edge of the screen the way they did.
                            Row(
                                Modifier
                                    .padding(top = 8.dp)
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                m!!.genres.take(4).forEach { g ->
                                    // Tapping a tag asks WHERE to search:
                                    // "Search" stays inside this title's own
                                    // extension, "Global search" fans out to
                                    // every installed provider. Keeping both on
                                    // the pill means one tap is still enough to
                                    // discover the choice, without hijacking the
                                    // tap to a single behaviour.
                                    var tagOpen by remember { mutableStateOf(false) }
                                    Box {
                                        Row(
                                            Modifier
                                                .clip(RoundedCornerShape(20.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                                .clickable { tagOpen = true }
                                                .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                trTag(g),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(Modifier.width(2.dp))
                                            Icon(
                                                Icons.Filled.ArrowDropDown,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = tagOpen,
                                            onDismissRequest = { tagOpen = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text(tr("Search")) },
                                                leadingIcon = {
                                                    Icon(Icons.Filled.Search, contentDescription = null)
                                                },
                                                onClick = {
                                                    tagOpen = false
                                                    Routes.safeNavigate(
                                                        nav,
                                                        Routes.searchInProvider(livePid, g)
                                                    )
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(tr("Global search")) },
                                                leadingIcon = {
                                                    Icon(Icons.Filled.Public, contentDescription = null)
                                                },
                                                onClick = {
                                                    tagOpen = false
                                                    Routes.safeNavigate(nav, Routes.searchQuery(g))
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (!displayOverview.isNullOrBlank()) {
                            var expanded by remember { mutableStateOf(false) }
                            Text(
                                displayOverview,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = if (expanded) Int.MAX_VALUE else 4,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .padding(top = 12.dp)
                                    .clickable { expanded = !expanded }
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }
                    }
                // Type reporting varies wildly across .cs3 plugins, so only use
                // it as a hint: show the episode list whenever the item is a
                // series OR the provider actually returned episodes, and always
                // give mislabeled/unknown items a Play button so nothing is
                // ever unplayable.
                //
                // The Vega engines are the one case that needs more than that.
                // Their catalog rows carry no movie/series flag at all (see
                // VegaProvider.toItems: type UNKNOWN, the provider's own `type`
                // string kept as rawType), and the thing that DOES know — the
                // provider's meta.js — is the slow part of opening the page (one
                // engine boot that runs getMeta and then every season's
                // getEpisodes). So for those, an unknown kind is "show the
                // episode area in its LOADING state" rather than "hide it":
                // opening a series used to show nothing but a Play button for
                // those seconds, which reads as "this show has no episodes".
                // Once meta answers — series or movie — this collapses to the
                // truth, and the loading row is the same one any other engine
                // shows. Other engines' items are not UNKNOWN, so nothing
                // changes for them.
                val isSeries = detailIsSeries
                val btnEp = detailBtnEp
                val actionLabel = detailActionLabel
                if (!isTvLayout) {
                item {
                    // The remote lands on Play the moment this row exists: on a
                    // television the page opens with the D-pad already sitting
                    // here, so Enter plays the title. (On a phone this is a
                    // no-op.) The short delay is one layout pass — the requester
                    // cannot point at the button until it has been placed.
                    LaunchedEffect(Unit) {
                        if (!com.hikari.app.tv.TvMode.isTv) return@LaunchedEffect
                        delay(150L)
                        try {
                            playFocus.requestFocus()
                        } catch (t: Throwable) {
                            // not attached yet — the user can still walk to it
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { tryPlay(btnEp) },
                            modifier = Modifier
                                .weight(1f)
                                // The remote lands here when the page opens (see
                                // playFocus above); a no-op on a phone.
                                .focusRequester(playFocus),
                            // Tighter inside padding (width is weight-driven,
                            // so the pill is exactly as big — the label just
                            // gets more of it, for "Resume E2650" and friends).
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            // Always an action word, never a spinner: the
                            // source search keeps running in the background
                            // (prefetch + live feed) and the sheet shows its
                            // own loader, so the button must never sit on a
                            // "Preparing…" spinner of its own. And always ONE
                            // line: "Resume E1" wrapped and the pill came out
                            // tall beside Download/Library (screenshot-proven).
                            // The label alone shrinks — and ADAPTIVELY: a long
                            // episode number ("Resume E2650") steps down
                            // further so it still fits instead of cutting off.
                            // The pill itself is untouched.
                            Text(
                                actionLabel,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Visible,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontSize = 13.sp * (11f / actionLabel.length.toFloat()).coerceIn(0.45f, 1f)
                                ),
                            )
                        }
                        // The MARK state is deliberately NOT a button in this row
                        // any more. It was one (a check beside Play, mirroring the
                        // reference client) and it cost the Play button a quarter
                        // of its width: the row is `Play (weight 1f)` plus three
                        // fixed buttons, so Play's label — "Resume S1 E5" — wrapped
                        // onto a second line and the button came out as a tall
                        // pill. What the check was FOR is the strip under this row,
                        // which shows the state itself instead of hiding it behind
                        // a tap (see the mark strip item below).
                        // Download without watching first: opens the player on
                        // this episode and puts its download chooser up as soon
                        // as a server is ready (see launchPlayer's
                        // `openDownload`). The episode rows below offer the same
                        // per episode; this one also covers a movie, whose only
                        // affordance is this row.
                        FilledTonalButton(onClick = { openDlChoice() }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_download),
                                contentDescription = tr("Download"),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        // Library toggle, mirroring the player's heart: the same
                        // MediaItem and the same store calls, so the two views
                        // can never disagree about what is saved.
                        if (isSaved) {
                            FilledTonalButton(onClick = openLibrary) {
                                Icon(
                                    Icons.Filled.Favorite,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(tr("Saved"))
                            }
                        } else {
                            OutlinedButton(onClick = openLibrary) {
                                Icon(
                                    Icons.Filled.FavoriteBorder,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(tr("Library"))
                            }
                        }
                    }
                }
                }
                // A Vega movie's quality rows (the provider's own quality list —
                // "480p [650MB]", "1080p [9.3GB]" — the way the Vega app lists
                // the picked entry's rows). Play resolves through the picked
                // row's link, so a dead first row no longer sinks the title
                // while its other rows play fine.
                if (isVegaPage && !isSeries && vegaMovieOptions.size > 1) {
                    item {
                        VegaOptionPicker(
                            options = vegaMovieOptions.map { it.label },
                            selected = vegaMovieIndex.coerceIn(0, vegaMovieOptions.lastIndex),
                            onPick = { vm.setVegaMovie(it) },
                        )
                    }
                }
                // ---- The mark state, ON the page --------------------------------
                //
                // The reference client's mark button hides its own answer: the only
                // way to learn whether a title is watched, being watched, or filed
                // for later is to tap the button and read the sheet back. The state
                // is drawn here instead — every mark the title carries is a chip,
                // so one glance at the page answers "have I marked this?" — and the
                // sheet (which owns the actions that are not a plain on/off: mark
                // every episode, remove from history) opens from the same strip.
                if (!isTvLayout) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // In the order the sheet offers them, and only when they are
                        // set: the strip IS the answer, so an unset mark is a chip
                        // that is not there rather than a chip that says "no".
                        if (markWatched) {
                            MarkChip(tr("Watched"), active = true) { markSheet = true }
                        }
                        if (markWatching) {
                            MarkChip(tr("Watching"), active = true) { markSheet = true }
                        }
                        if (markLater) {
                            MarkChip(tr("Watch later"), active = true) { markSheet = true }
                        }
                        // The way into the sheet — and, when nothing is marked, the
                        // answer that nothing is. An outlined chip reads as "not
                        // set" as plainly as a filled one reads as "set", so the
                        // strip is never empty and never ambiguous.
                        MarkChip(
                            if (markAnything) tr("Change") else tr("Mark"),
                            active = false,
                        ) { markSheet = true }
                    }
                }
                }
                // "Show Details" block (Nuvio/Stremio style): the stat line
                // (year · runtime · certification · rating) plus status/country/
                // language and the director/writer credits. Renders only once
                // the background TMDB lookup has landed.
                extras?.details?.let { det ->
                    item {
                        if (isTvLayout) {
                            // The reference client's Show Details block: two
                            // columns of label/value rows plus the studio and
                            // network logo rows. The hero already carries the
                            // year/runtime/cert line and the rating badges, so
                            // they stay out of this block.
                            val detailRows = ArrayList<Pair<String, String>>(8)
                            det.status?.let { detailRows.add(tr("Status") to trStatus(it)) }
                            det.country?.let { detailRows.add(tr("Country") to it) }
                            det.language?.let { detailRows.add(tr("Language") to it) }
                            det.voteCount?.takeIf { it > 0 }?.let {
                                detailRows.add(tr("Votes") to it.toString())
                            }
                            det.releaseDate?.takeIf { it.isNotBlank() }?.let { iso ->
                                detailRows.add(tr("Release Info") to formatReleaseDate(iso))
                            }
                            det.director?.takeIf { it.isNotBlank() }?.let {
                                detailRows.add(tr("Director") to it)
                            }
                            if (det.writers.isNotEmpty()) {
                                detailRows.add(tr("Writers") to det.writers.joinToString(", "))
                            }
                            val allCompanies = extras?.companies.orEmpty()
                            Cs3ShowDetails(
                                rows = detailRows,
                                studioLogos = allCompanies
                                    .filter { !it.isNetwork }
                                    .mapNotNull { it.logoUrl }
                                    .distinct(),
                                networkLogos = allCompanies
                                    .filter { it.isNetwork }
                                    .mapNotNull { it.logoUrl }
                                    .distinct(),
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp),
                            )
                        } else {
                            DetailsBlock(
                                d = det,
                                // TV already shows ratings + year/runtime/cert in
                                // the hero (reference layout) — the block below
                                // keeps only status/country/language/votes/
                                // release/director/writers so nothing shows twice.
                                ratings = if (showDetailRating && !isTvLayout) ratings else emptyList(),
                                hideHeadline = isTvLayout,
                                // The callback stays wired for when the strip
                                // comes back (setting on, phone layout).
                                onRatingClick = { ratingInfo = it },
                            )
                        }
                    }
                }
                // Production companies / networks — one tap opens everything
                // that studio or network made (a TMDB entity grid; see the
                // TmdbSourceType.COMPANY/NETWORK queries). It sits ABOVE the cast
                // row, the order the reference client uses. Phone-only: TV
                // reads them in the hero's own plate row (reference layout),
                // so the page row below would show every studio twice — and
                // the row is skipped when the lookup found nothing.
                if (!isTvLayout) extras?.companies?.takeIf { it.isNotEmpty() }?.let { companies ->
                    item {
                        ProductionRow(companies) { c ->
                            val spec = TmdbSpec(
                                type = if (c.isNetwork) TmdbSourceType.NETWORK
                                else TmdbSourceType.COMPANY,
                                id = c.id,
                                // A network only ever has series; a studio has
                                // both, and "all" makes the grid merge its films
                                // and its shows into one row.
                                media = if (c.isNetwork) "tv" else "all",
                                sort = "popularity.desc",
                                title = c.name,
                            )
                            Routes.safeNavigate(nav, Routes.tmdbGridSpec(spec.encode(), c.name))
                        }
                    }
                }
                // Cast + Trailers sit ABOVE the episode list — the order the
                // Nuvio/Stremio detail page uses. Below it they were buried under
                // a 30-episode season (or below the fold of a long overview) and
                // read as "the sections are missing". Both come from the same
                // background TMDB call, and each row is skipped entirely when
                // that lookup found nothing.
                // A local read of the delegated property: a `by remember` value
                // cannot be smart-cast, so the null check happens on a plain val.
                val extrasNow = extras
                if (extrasNow != null && extrasNow.cast.isNotEmpty()) {
                    // For an anime, `cast` holds the characters (with the
                    // Japanese voice actors as their second line) — see
                    // AnimeCast. The row says which one it is showing.
                    val cast = extrasNow.cast
                    val characters = extrasNow.castIsCharacters
                    if (isTvLayout) {
                        // The reference client's cast rail: "Cast & Crew" (or
                        // "Characters" for anime) with the purple View All
                        // link, 140dp 2:3 actor cards, and the View All tile
                        // closing the rail when the roster runs long.
                        item {
                            val showAll = cast.size > 8
                            val visible = if (showAll) cast.take(8) else cast
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 20.dp, end = 20.dp, top = 18.dp)
                            ) {
                                Cs3CastHeader(
                                    title = tr(if (characters) "Characters" else "Cast & Crew"),
                                    showViewAll = showAll,
                                    onViewAll = { showFullCast = true },
                                )
                                Spacer(Modifier.height(10.dp))
                                LazyRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    items(visible, key = { "tv-cast-" + it.name }) { member ->
                                        // A character cell searches their ACTOR
                                        // (the searchable name); a cast cell
                                        // searches the actor it already is.
                                        val query = if (characters) member.character ?: member.name else member.name
                                        Cs3ActorCard(
                                            imageUrl = member.profileUrl,
                                            cornerUrl = null,
                                            name = member.name,
                                            sub = member.character?.let { role ->
                                                if (characters) "\uD83C\uDF99 " + tr("Voice: %s").replace("%s", role) else tr(role)
                                            },
                                            roleBadge = null,
                                            onClick = { Routes.safeNavigate(nav, Routes.searchQuery(query)) },
                                            modifier = Modifier.width(140.dp),
                                        )
                                    }
                                    if (showAll) {
                                        item(key = "tv-cast-viewall") {
                                            Cs3ViewAllCastCard(
                                                onClick = { showFullCast = true },
                                                modifier = Modifier.width(140.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (showFullCast) {
                            // The whole roster in a dialog grid — the TV
                            // equivalent of the reference's View All page.
                            item(key = "tv-cast-dialog") {
                                Dialog(onDismissRequest = { showFullCast = false }) {
                                    com.hikari.app.tv.Cs3DialogPanel(
                                        modifier = Modifier
                                            .fillMaxWidth(0.85f)
                                            .fillMaxHeight(0.85f)
                                    ) {
                                        Column(
                                            Modifier
                                                .fillMaxSize()
                                                .padding(20.dp)
                                        ) {
                                            Cs3CastHeader(
                                                title = tr(if (characters) "Characters" else "Cast & Crew"),
                                                showViewAll = false,
                                                onViewAll = {},
                                            )
                                            Spacer(Modifier.height(12.dp))
                                            LazyVerticalGrid(
                                                columns = GridCells.Adaptive(140.dp),
                                                modifier = Modifier.fillMaxSize(),
                                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                            ) {
                                                gridItems(cast, key = { "tv-castall-" + it.name }) { member ->
                                                    val query = if (characters) member.character ?: member.name else member.name
                                                    Cs3ActorCard(
                                                        imageUrl = member.profileUrl,
                                                        cornerUrl = null,
                                                        name = member.name,
                                                        sub = member.character?.let { role ->
                                                            if (characters) "\uD83C\uDF99 " + tr("Voice: %s").replace("%s", role) else tr(role)
                                                        },
                                                        roleBadge = null,
                                                        onClick = {
                                                            showFullCast = false
                                                            Routes.safeNavigate(nav, Routes.searchQuery(query))
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        item {
                            CastRow(cast, characters = characters) { member ->
                                // A character cell searches their ACTOR (the
                                // searchable name); a cast cell searches the actor
                                // it already is.
                                val query = if (characters) member.character ?: member.name else member.name
                                Routes.safeNavigate(nav, Routes.searchQuery(query))
                            }
                        }
                    }
                }
                extras?.trailers?.takeIf { it.isNotEmpty() }?.let { trailers ->
                    if (isTvLayout) {
                        // The reference client's trailer rail: count header
                        // plus 320dp 16:9 cards with OFFICIAL/type badges, the
                        // center play button and the title below.
                        item {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 20.dp, end = 20.dp, top = 18.dp)
                            ) {
                                Cs3CountHeader(title = tr("Trailers"), count = trailers.size)
                                Spacer(Modifier.height(10.dp))
                                LazyRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    items(trailers, key = { "tv-trailer-" + it.youtubeKey }) { trailer ->
                                        Cs3TrailerCard(
                                            name = trailer.name,
                                            type = trailer.type,
                                            isOfficial = trailer.type.equals("Trailer", ignoreCase = true) ||
                                                trailer.name.contains("official", ignoreCase = true),
                                            thumbnailUrl = trailer.thumbnailUrl,
                                            onClick = {
                                                openYouTubeVideo(
                                                    context,
                                                    trailer.youtubeKey,
                                                    (m?.title ?: title) + " — " + trailer.name
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        item {
                            TrailerRow(trailers) { trailer ->
                                // Trailers hand off to the YouTube app instead of
                                // playing in Hikari's WebView: YouTube redirects to
                                // m.youtube.com and the WebView's redirect
                                // protection blocks that, leaving a black page.
                                openYouTubeVideo(
                                    context,
                                    trailer.youtubeKey,
                                    (m?.title ?: title) + " — " + trailer.name
                                )
                            }
                        }
                    }
                }
                // Television Screenshots, the reference client's own strip:
                // TMDB backdrops in a 16:9 rail. Phone untouched — this
                // section never existed there, and the strip simply doesn't
                // render when TMDB has no backdrops for the title.
                if (isTvLayout && tvScreenshots.isNotEmpty()) {
                    item(key = "tv-screenshots") {
                        // The reference client's Screenshots section: count
                        // pill plus the mosaic — the featured shot at half
                        // width, the 2x2 grid beside it, the +N-more cell.
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp, end = 20.dp, top = 18.dp)
                        ) {
                            Cs3CountHeader(title = tr("Screenshots"), count = tvScreenshots.size)
                            Spacer(Modifier.height(10.dp))
                            Cs3ScreenshotsMosaic(
                                screenshots = tvScreenshots,
                                onShotClick = { /* Still: no viewer yet, rail only. */ },
                            )
                        }
                    }
                }
                if (isSeries && !isTvLayout) {
                    // A Vega series' pack picker (audio variants, seasons,
                    // season+quality rows — the provider's own titles, verbatim,
                    // the way the Vega app's dropdown lists its entries). Only
                    // the picked pack's episodes are below, so switching packs
                    // switches the list instead of re-filtering one merged blob.
                    if (isVegaPage && vegaPacks.size > 1) {
                        item {
                            // The picker's position is the LIST position; the
                            // provider's key is the pack's own index (blank
                            // entries are skipped when the list is built, so
                            // the two can differ).
                            val packPos = vegaPacks.indexOfFirst { it.index == vegaPackIndex }
                                .takeIf { it >= 0 } ?: 0
                            VegaOptionPicker(
                                options = vegaPacks.map {
                                    it.title.ifBlank { tr("Option %s").replace("%s", (it.index + 1).toString()) }
                                },
                                selected = packPos,
                                onPick = { vm.setVegaPack(vegaPacks[it].index) },
                            )
                        }
                    }
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.98f))
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                tr("Episodes (%s)").replace("%s", shownEps.size.toString()),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            // Still resolving (a richer list, or the episodes'
                            // own names, can land a moment after the first list
                            // painted) — say so beside the count instead of
                            // leaving the page looking settled.
                            if (episodesLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(14.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                            Row(
                                Modifier,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // The order arrow. Up = episode 1 first (the way
                                // the providers hand the list over), down = the
                                // last episode first for shows whose newest
                                // episode is the one you came for. The icon
                                // always shows the order the list is in, so one
                                // tap flips both.
                                IconButton(
                                    onClick = { epsNewestFirst = !epsNewestFirst },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        if (epsNewestFirst) Icons.Filled.ArrowDownward
                                        else Icons.Filled.ArrowUpward,
                                        contentDescription = if (epsNewestFirst) {
                                            tr("Newest episode first")
                                        } else {
                                            tr("Oldest episode first")
                                        },
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                IconButton(
                                    onClick = { cycleEpisodeView() },
                                    modifier = Modifier.size(32.dp).tvPress(
                                        previewPass = true,
                                        onClick = { cycleEpisodeView() }
                                    )
                                ) {
                                    Icon(
                                        // The button shows the NEXT look: rows → grid → big cards → rows.
                                        when (episodeViewMode) {
                                            0 -> Icons.Filled.GridView
                                            1 -> Icons.Filled.Dashboard
                                            else -> Icons.AutoMirrored.Filled.ViewList
                                        },
                                        contentDescription = when (episodeViewMode) {
                                            0 -> tr("Use episode poster grid")
                                            1 -> tr("Use big episode cards")
                                            else -> tr("Use episode list")
                                        },
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                if (seasons.size > 1) {
                                    Box {
                                        OutlinedButton(
                                            onClick = { seasonExpanded = true },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                        ) {
                                            Text(tr("Season %s").replace("%s", activeSeason.toString()), maxLines = 1)
                                        }
                                        DropdownMenu(
                                            expanded = seasonExpanded,
                                            onDismissRequest = { seasonExpanded = false }
                                        ) {
                                            seasons.forEach { s ->
                                                DropdownMenuItem(
                                                    modifier = Modifier.tvPress(previewPass = true, onClick = { selectedSeason = s; seasonExpanded = false }),
                                                    text = {
                                                        Text(tr("Season %s").replace("%s", s.toString()))
                                                    },
                                                    onClick = {
                                                        selectedSeason = s
                                                        seasonExpanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                                // Episode page picker — only needed once a single
                                // season exceeds 30 episodes (e.g. 600-ep donghua).
                                if (ranges.isNotEmpty()) {
                                    Box {
                                        OutlinedButton(
                                            onClick = { rangeExpanded = true },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                            modifier = Modifier.tvPress(previewPass = true, onClick = { rangeExpanded = true })
                                        ) {
                                            val end = (safeStart + epPageSize).coerceAtMost(shownEps.size)
                                            Text("${safeStart + 1}–$end", maxLines = 1)
                                        }
                                        DropdownMenu(
                                            expanded = rangeExpanded,
                                            onDismissRequest = { rangeExpanded = false }
                                        ) {
                                            ranges.forEach { start ->
                                                val end = (start + epPageSize).coerceAtMost(shownEps.size)
                                                DropdownMenuItem(
                                                    modifier = Modifier.tvPress(previewPass = true, onClick = { rangeStart = start; rangeExpanded = false }),
                                                    text = { Text("${start + 1}–$end") },
                                                    onClick = {
                                                        rangeStart = start
                                                        rangeExpanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (sortedEps.isEmpty()) {
                        item {
                            // Loading includes "a lookup is running, or one was
                            // never reported finished" — never the empty verdict.
                            // An empty answer used to be painted the instant a
                            // superseded (or cancelled) load cleared its flag,
                            // which is exactly how the page came to say
                            // "No episode list available." over a series whose
                            // episodes arrived a few seconds later.
                            if (episodesLoading || !episodesLoaded) {
                                Row(
                                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Text(
                                        tr("Loading episodes…"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                // "Couldn't load" and "there are none" are
                                // different facts, and the page must not present
                                // the first as the second: an extension whose
                                // runtime cold-started (or a request that
                                // dropped) used to leave "No episode list
                                // available." over a series with a full episode
                                // list, with no way out but closing the app. Now
                                // the failure says so and offers the retry.
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    Text(
                                        if (episodesFailed) {
                                            tr("Couldn't load the episode list from this extension.")
                                        } else {
                                            tr("No episode list available.")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (episodesFailed) {
                                        Spacer(Modifier.height(6.dp))
                                        OutlinedButton(
                                            onClick = { vm.retryEpisodes() },
                                            contentPadding = PaddingValues(
                                                horizontal = 14.dp,
                                                vertical = 4.dp,
                                            ),
                                        ) {
                                            Text(tr("Try again"))
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // key MUST be unique — plugins (MoviesMod, …) emit
                        // duplicate ids/numbers per quality group, and a
                        // duplicate Compose key crashes the whole screen.
                        if (episodeBigStyle) {
                            // Big cinema cards: one wide card per row — the
                            // trailer-style look, readable on a phone without
                            // shrinking three episodes into the width.
                            pageEps.forEachIndexed { index, ep ->
                                item(key = "ep-big-$index") {
                                    EpisodeBigCard(
                                        ep = ep,
                                        fallbackImage = m?.backdropUrl ?: m?.posterUrl ?: posterUrl,
                                        onClick = { tryPlay(ep) },
                                        onDownload = { tryDownload(ep) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 6.dp),
                                        sizeScale = tvEpisodeScale,
                                        glass = episodeGlass,
                                        hideSpoilers = antiSpoiler && !isEpSeen(ep),
                                    )
                                }
                            }
                        } else if (episodePosterStyle) {
                            pageEps.chunked(3).forEachIndexed { rowIndex, row ->
                                item(key = "ep-poster-$rowIndex") {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 5.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        row.forEach { ep ->
                                            EpisodePosterCard(
                                                ep = ep,
                                                fallbackImage = m?.backdropUrl ?: m?.posterUrl ?: posterUrl,
                                                onClick = { tryPlay(ep) },
                                                onDownload = { tryDownload(ep) },
                                                modifier = Modifier.weight(1f),
                                                sizeScale = tvEpisodeScale,
                                                glass = episodeGlass,
                                                hideSpoilers = antiSpoiler && !isEpSeen(ep),
                                            )
                                        }
                                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            }
                        } else {
                            pageEps.forEachIndexed { index, ep ->
                                item(key = "ep-$index") {
                                    EpisodeRow(
                                        ep,
                                        fallbackImage = m?.backdropUrl ?: m?.posterUrl ?: posterUrl,
                                        onClick = { tryPlay(ep) },
                                        onDownload = { tryDownload(ep) },
                                        sizeScale = tvEpisodeScale,
                                        glass = episodeGlass,
                                        hideSpoilers = antiSpoiler && !isEpSeen(ep),
                                        watchState = epWatchState(ep),
                                    )
                                }
                            }
                        }
                    }
                }
                // Live channels carry no TMDB shelves — no Related/Similar, no
                // collection — so the page below the mark strip was empty. The
                // reference app answers that space with the playlist itself:
                // more channels from the same list, same group first, so the
                // next channel is one tap away.
                if (isIptvPage && iptvSiblings.isNotEmpty()) {
                    item {
                        ShelfRow(
                            heading = tr("More from this playlist"),
                            shelf = iptvSiblings,
                            tv = isTvLayout,
                            onClick = { openShelfItem(it) },
                            onSearchHere = {
                                Routes.safeNavigate(nav, Routes.searchInProvider(livePid, it.title))
                            },
                            onGlobalSearch = {
                                Routes.safeNavigate(nav, Routes.searchQuery(it.title))
                            },
                        )
                    }
                }
                // The franchise this title belongs to — TMDB's own
                // `belongs_to_collection`, shown the way the reference client
                // shows it ("Shrek Collection"), with its other parts in release
                // order. It sits directly above Related/Similar: a collection is
                // the closest thing to "more of exactly this".
                extras?.collection?.takeIf { it.items.isNotEmpty() }?.let { coll ->
                    item {
                        ShelfRow(
                            heading = coll.name.ifBlank { tr("Collection") },
                            shelf = coll.items,
                            tv = isTvLayout,
                            onClick = { openShelfItem(it) },
                            onSearchHere = {
                                Routes.safeNavigate(nav, Routes.searchInProvider(livePid, it.title))
                            },
                            onGlobalSearch = {
                                Routes.safeNavigate(nav, Routes.searchQuery(it.title))
                            },
                        )
                    }
                }
                // Same-title shelves from TMDB — the Nuvio detail page's
                // Related/Similar tabs, as inline rows. They show up only once
                // the background lookup lands, and only when it found titles
                // that actually have artwork, so a miss leaves no empty row.
                if (related.isNotEmpty()) {
                    item {
                        ShelfRow(
                            heading = tr("Related"),
                            shelf = related,
                            tv = isTvLayout,
                            onClick = { openShelfItem(it) },
                            onSearchHere = {
                                Routes.safeNavigate(nav, Routes.searchInProvider(livePid, it.title))
                            },
                            onGlobalSearch = {
                                Routes.safeNavigate(nav, Routes.searchQuery(it.title))
                            },
                        )
                    }
                }
                if (similar.isNotEmpty()) {
                    item {
                        ShelfRow(
                            heading = tr("Similar"),
                            shelf = similar,
                            tv = isTvLayout,
                            onClick = { openShelfItem(it) },
                            onSearchHere = {
                                Routes.safeNavigate(nav, Routes.searchInProvider(livePid, it.title))
                            },
                            onGlobalSearch = {
                                Routes.safeNavigate(nav, Routes.searchQuery(it.title))
                            },
                        )
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
    }

    // The way OUT of the page is never allowed to scroll off: the header's own
    // back button is drawn inside the art, so this one takes over from the same
    // corner, at the same size, as soon as the art is mostly gone.
    if (headerProgress > 0.6f) {
        IconButton(
            onClick = { nav.popBackStack() },
            modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = tr("Back"),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }

    // The title as ART: it starts on the lower part of the header art (which is
    // where the reference client puts it — its bottom edge a little above the
    // art's gradient) and rises to the top of the screen as the art scrolls
    // away, shrinking a little on the way so a full-bleed wordmark does not read
    // as oversized once it is acting as the page's title. It is drawn HERE, in
    // the page's own box rather than inside the scrolling list, which is what
    // makes it "not move with the image": the art goes, the title stays.
    val logoArt = heroLogo
    if (!logoArt.isNullOrBlank() && !(isTvLayout && episodePosterStyle)) {
        // [logoFrac] (above) is the width this frame's wordmark is fitted into:
        // bigger setting = bigger logo, at BOTH ends of the scroll. Its height
        // follows the art's aspect ratio, so this is "bigger logo", never
        // "stretched logo".
        // The wordmark's own height, read from the LAYOUT (its aspect ratio is
        // whatever TMDB's art is, and there is no reason to guess it): the width
        // only decides the height, never the other way round, so this settles on
        // the first frame the image is laid out on.
        val logoHPx = logoSizePx.toFloat()
        val gapPx = with(density) { 10.dp.toPx() }
        val topInsetPx = with(density) { statusBarTop.toPx() }
        val pinnedTop = topInsetPx + gapPx / 2f
        // Bottom-aligned inside the art's lower edge — but never above the pinned
        // position, so a short header (or the no-art style) puts the wordmark
        // straight at the top instead of somewhere off the screen.
        val restTop = (headerPx - logoHPx - gapPx).coerceAtLeast(pinnedTop)
        val topPx = restTop + (pinnedTop - restTop) * headerProgress
        AsyncImage(
            model = PosterLoader.model(logoArt),
            // The title, for a screen reader: the visible title on this page IS
            // this image once it is drawn (the text one is not repeated).
            contentDescription = m?.title ?: title,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(logoFrac)
                .onSizeChanged { logoSizePx = it.height }
                .offset { IntOffset(0, topPx.roundToInt()) },
        )
    }

    // The plural TMDB header art for the loading card: the same image the
    // header settled on (first /images backdrop), so the hand-off into the
    // player never swaps the design the user was just looking at.
    val loadingTmdb = rememberHeaderTmdbArts(m).firstOrNull()
    if (showLoadingBanner) {
        PlayLoadingBanner(
            title = artTitle,
            episodeLabel = selectedEp?.let {
                if (it.season > 1) "S${it.season} E${it.number}"
                else tr("Episode %s").replace("%s", it.number.toString())
            },
            detail = selectedEp?.name?.takeIf { it.isNotBlank() },
            // Plural TMDB (/images) first — the header's own image — then the
            // single backdrop_path, then the provider art.
            image = loadingTmdb?.takeIf { it.isNotBlank() }
                ?: (m?.let { Artwork.backdrop(it) })?.takeIf { it.isNotBlank() }
                ?: (m?.backdropUrl?.takeIf { it.isNotBlank() }) ?: posterUrl,
            // The title's own wordmark, drawn instead of the text title when the
            // setting is on and this title has one (see LoadingTitleBlock).
            logo = if (loadingLogoOn) heroLogo else null,
            logoPercent = loadingLogoPercent,
            tvLayout = isTvLayout,
        )
    }

    // A Related/Similar cell opened inside an extension whose own ids we don't
    // have: the title lookup runs in the background, so cover the page instead
    // of leaving the tap looking dead.
    val opening = shelfOpening
    if (opening != null) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text(
                    tr("Opening %s…").replace("%s", opening),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp, start = 24.dp, end = 24.dp)
                )
            }
        }
    }

    ratingInfo?.let { info ->
        RatingDetailDialog(info) { ratingInfo = null }
    }

   if (scopeAskShow) {
        val originName = providers.firstOrNull { it.config.id == livePid }?.config?.name?.ifBlank { null } ?: livePid
        ChoiceDialog(
            title = if (scopeAskPending?.third == true) tr("Where should download search?") else tr("Where should playback search?"),
            items = listOf(
                com.hikari.app.ui.components.ChoiceItem(key = "origin", label = tr("This extension only"), supporting = originName),
                com.hikari.app.ui.components.ChoiceItem(key = "selected", label = tr("My selected servers"), supporting = tr("Your Server search choices")),
            ),
            selectedKey = null,
            onPick = { key ->
                if (key == "origin") {
                    com.hikari.app.data.SearchScope.oneShotOriginOnly = livePid
                    pendingNarrowTo = livePid
                } else {
                    com.hikari.app.data.SearchScope.oneShotOriginOnly = null
                    pendingNarrowTo = null
                }
                scopeAskArmed = true
                scopeAskShow = false
                val pending = scopeAskPending
                scopeAskPending = null
                if (pending != null) openStreams(pending.first, pending.second, pending.third)
            },
            onDismiss = { scopeAskShow = false },
        )
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            Text(
                selectedEp?.let { if (it.season > 1) "S${it.season} E${it.number}" else "Episode ${it.number}" }
                    ?: tr("Playback sources"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Text(
                m?.title ?: title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            Spacer(Modifier.height(8.dp))
            when {
                streams.isEmpty() && loadingStreams -> Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(10.dp))
                    Text(
                        run {
                            val originName = providers.firstOrNull { it.config.id == (m?.providerId ?: providerId) }
                                ?.config?.name?.ifBlank { null }
                            if (originName != null) "Please wait — searching $originName for servers…"
                            else tr("Please wait — servers are loading…")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        tr("Servers will appear here the moment the first one is found."),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                streams.isEmpty() -> Column(
                    Modifier
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        tr("No playable sources found."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!com.hikari.app.net.NetTuning.slowConnection) {
                        Text(
                            tr("On mobile data or a slow connection? Turn on " + "\"Slow connection mode\" in Settings, then search again."),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    // (the still-searching wait message lives in the
                    // streams.isEmpty() && loadingStreams branch above — this
                    // branch is only reached when the search finished empty.)
                    if (lastSearchNarrowed) {
                        // The narrowed pass ("this extension only") found
                        // nothing playable — offer the same title across the
                        // user's selected servers instead of leaving the dead
                        // end ("Playback failed … only the extension this
                        // title came from is searched").
                        Button(
                            onClick = {
                                com.hikari.app.data.SearchScope.oneShotOriginOnly = null
                                scopeAskArmed = true
                                lastSearchNarrowed = false
                                openStreams(selectedEp, 0L, false)
                            },
                            modifier = Modifier.padding(top = 12.dp),
                        ) {
                            Text(tr("Search all my servers instead"))
                        }
                    }
                    if (searchedProviders > 0) {
                        Text(
                            I18n.t(
                                    if (searchedProviders == 1) "Searched %s addon for sources."
                                    else "Searched %s addons for sources."
                                ).replace("%s", searchedProviders.toString()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    val err = streamError
                    if (err != null) {
                        Text(
                            err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    val diagLines = providers
                        .filter { it.config.enabled }
                        .mapNotNull { providerOutcomeLine(it) }
                        .take(20)
                    if (diagLines.isNotEmpty()) {
                        Text(
                            tr("Per-extension results:"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                        diagLines.forEach { l ->
                            Text(
                                l,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    val fLog = com.hikari.app.nuvio.NuvioRuntime.fetchLogSnapshot().takeLast(24)
                    if (fLog.isNotEmpty()) {
                        Text(
                            tr("Fetch log:"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                        fLog.forEach { l ->
                            Text(
                                l,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    val nuvioReport = nuvioReportLines(providers)
                    if (nuvioReport.isNotEmpty()) {
                        Text(
                            tr("Nuvio engines:"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                        nuvioReport.forEach { l ->
                            Text(
                                l,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
                else -> Column(Modifier.fillMaxWidth()) {
                    // Progressive by construction: rows above arrive as each
                    // provider answers (see the live feed), and while the
                    // sweep is still running the header says so with a live
                    // count — never a finished-looking list that silently
                    // grows, and never 15–20s of spinner followed by
                    // everything at once.
                    if (loadingStreams) {
                        Text(
                            I18n.t("Found %s — still searching…").replace("%s", streams.size.toString()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 400.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        itemsIndexed(streams) { index, s ->
                        val enabled = when {
                            s.ytId != null -> true
                            s.externalUrl -> s.url.isNotBlank()
                            s.isTorrent -> true
                            else -> s.url.isNotBlank()
                        }
                        ListItem(
                            headlineContent = { Text(s.name) },
                            supportingContent = {
                                Text(
                                    when {
                                        s.isTorrent -> tr("Torrent — streams from peers")
                                        s.ytId != null -> "YouTube"
                                        s.externalUrl -> tr("Opens in web view")
                                        s.url.contains(".m3u8", true) -> "HLS"
                                        else -> "Direct"
                                    }
                                )
                            },
                            leadingContent = {
                                Icon(
                                    if (s.isTorrent) Icons.Filled.Warning else Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    tint = if (enabled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(enabled = enabled) {
                                    when {
                                        s.ytId != null -> {
                                            showSheet = false
                                            // Main-release route: a YouTube link row opens in
                                            // Hikari's own WebView (the trailer BUTTON above
                                            // still uses openYouTubeVideo for the YouTube app —
                                            // this row is somebody else's watch page).
                                            context.startActivity(
                                                Intent(context, WebViewActivity::class.java).apply {
                                                    putExtra("url", "https://www.youtube.com/watch?v=${s.ytId}")
                                                    putExtra("title", (m?.title ?: title) + " — YouTube")
                                                }
                                            )
                                        }
                                        s.externalUrl -> {
                                            showSheet = false
                                            // Main-release route: an external watch page opens
                                            // in Hikari's own WebView (that is what the
                                            // "Opens in web view" line promises).
                                            context.startActivity(
                                                Intent(context, WebViewActivity::class.java).apply {
                                                    putExtra("url", s.url)
                                                    putExtra("title", m?.title ?: title)
                                                }
                                            )
                                        }
                                        else -> {
                                            showSheet = false
                                            // Play the tapped server first, but carry
                                            // every other found server in the payload so
                                            // the player's "Select server" dialog lists
                                            // them all.
                                            val key = s.infoHash ?: s.url
                                            val others = streams.filter { o ->
                                                (o.infoHash ?: o.url) != key &&
                                                    o.ytId == null && !o.externalUrl &&
                                                    (o.url.isNotBlank() || o.isTorrent)
                                            }
                                            launchPlayer(listOf(s) + others, selectedEp, sessionId, pendingStartPos, false)
                                        }
                                    }
                                }
                        )
                    }
                    }
                    val nuvioReport = nuvioReportLines(providers)
                    if (nuvioReport.isNotEmpty()) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Text(
                                (if (nuvioReportOpen) "▾ " else "▸ ") + nuvioReport.first(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable { nuvioReportOpen = !nuvioReportOpen }
                                    .padding(vertical = 6.dp)
                            )
                            if (nuvioReportOpen) {
                                nuvioReport.drop(1).forEach { l ->
                                    Text(
                                        l,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                    if (loadingStreams) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                tr("Searching for more servers…"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/**
 * A Vega pack / quality picker: one outlined button opening the provider's own
 * option titles, verbatim. The same OutlinedButton+DropdownMenu shape the
 * season and range pickers use, so the D-pad walks it the same way.
 */
@Composable
private fun VegaOptionPicker(
    options: List<String>,
    selected: Int,
    onPick: (Int) -> Unit,
) {
    if (options.size <= 1) return
    var expanded by remember { mutableStateOf(false) }
    val at = selected.coerceIn(0, options.lastIndex)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        OutlinedButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(options[at], maxLines = 1)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEachIndexed { i, label ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        onPick(i)
                    }
                )
            }
        }
    }
}

/**
 * Full-screen title-card cover shown from the instant the user taps Play until
 * the player activity takes over: the title's backdrop (Ken-Burns drift) under
 * a heavy scrim, the title breathing in/out, the episode line, and a "finding
 * the best server" spinner. Mirrors the in-player card, so the hand-off from
 * the detail screen into the player is seamless.
 */
@Composable
private fun PlayLoadingBanner(
    title: String,
    episodeLabel: String?,
    detail: String?,
    image: String?,
    /** The title's own wordmark art, or null/blank when it has none. */
    logo: String? = null,
    /** How big that wordmark is drawn, in percent of its default (see
     *  AppStore.DEFAULT_LOADING_LOGO_SIZE). */
    logoPercent: Int = 100,
    tvLayout: Boolean = false,
) {
    val style = rememberLoadingStyle()
    val effects = rememberLoadingEffect()
    // TV has a wide 16:9 canvas. Never put the phone-oriented poster/minimal
    // cards inside that canvas: use the same full-bleed cinematic treatment as
    // the movie reference, with the artwork fitted edge-to-edge and the title
    // wordmark centered over it.
    if (tvLayout) {
        CinematicLoadingCard(title, episodeLabel, detail, image, logo, logoPercent, effects, tvLayout = true)
        return
    }
    when (LoadingStyles.normalize(style)) {
        LoadingStyles.MINIMAL -> MinimalLoadingCard(title, episodeLabel, detail, logo, logoPercent, effects)
        LoadingStyles.SPOTLIGHT -> SpotlightLoadingCard(title, episodeLabel, detail, image, logo, logoPercent, effects)
        LoadingStyles.POSTER -> PosterLoadingCard(title, episodeLabel, detail, image, logo, logoPercent, effects)
        else -> CinematicLoadingCard(title, episodeLabel, detail, image, logo, logoPercent, effects)
    }
}

/** The chosen "finding your server" look (Settings → App Layout). Read once per
 *  composition, so the card is drawn in the right style from its first frame
 *  instead of flashing the default one. */
@Composable
private fun rememberLoadingStyle(): String {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as HikariApp
    val flow = remember(app) { app.store.loadingStyleFlow() }
    return remember(flow) { flow }.collectAsState(initial = LoadingStyles.POSTER).value
}

/** The treatment(s) drawn over that card (Settings → App Layout → Loading screen
 *  → Effect), read the same way — see [com.hikari.app.ui.LoadingEffects]. */
@Composable
private fun rememberLoadingEffect(): Set<String> {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as HikariApp
    val flow = remember(app) { app.store.loadingEffectsFlow() }
    return remember(flow) { flow }
        .collectAsState(initial = emptySet<String>()).value
}

/** The colour the cover's aura ring is drawn in (Settings → App Layout →
 *  Loading screen → Aura ring colour), read the same way as the style and the
 *  effects — one read per cover, so the ring is the right colour on its first
 *  frame. See [com.hikari.app.ui.AuraColors]. */
@Composable
private fun rememberLoadingAuraColor(): String {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as HikariApp
    val flow = remember(app) { app.store.loadingAuraColorFlow() }
    return remember(flow) { flow }
        .collectAsState(initial = com.hikari.app.ui.AuraColors.THEME).value
}

/**
 * The cover's own background for every loading style.
 *
 * With artwork to show it is plain black, exactly as it always was — the art
 * and its scrim cover it. With NO artwork it used to be black too, and that is
 * the bug: for a title whose provider simply never sends an image there was
 * nothing coming, so the whole search (which can run for minutes across every
 * installed extension) happened over a black screen that is indistinguishable
 * from a crashed app. Reported as "in some see the loading screen showing
 * black". The card still has the title, the episode line and the status spinner
 * on top; this just gives them something to sit on — a deep vertical wash in
 * the app's own surface tones, so the screen reads as "Hikari is working".
 */
@Composable
private fun rememberLoadingCoverBrush(hasArtwork: Boolean): Brush {
    val cs = MaterialTheme.colorScheme
    // Slightly lighter at the top (where the title block lives) than at the
    // very bottom, so the card has a direction instead of being a flat plate.
    val surfaceVariant = cs.surfaceVariant
    val surface = cs.surface
    val background = cs.background
    return remember(hasArtwork, surfaceVariant, surface, background) {
        if (hasArtwork) {
            SolidColor(Color.Black)
        } else {
            // A theme where all three stops collapse to the same near-black
            // would give us the very flatness we are trying to avoid: lift the
            // top one then.
            val top = if (surfaceVariant == surface && surface == background) {
                surfaceVariant.copy(alpha = 0.85f)
            } else {
                surfaceVariant
            }
            Brush.verticalGradient(listOf(top, surface, background))
        }
    }
}

/** The status line every loading style shares: a spinner plus the live status
 *  text the search keeps updating ("Found 4 servers — still searching the
 *  remaining extensions…", "…isn't responding — looking for another server…").
 *  Never removed by a style: a loading screen with no sign of life is
 *  indistinguishable from a hung app. */
@Composable
private fun LoadingStatusLine(
    tint: Color,
    dim: Color,
    centered: Boolean = true,
    statusText: String = tr("Finding the best server…"),
) {
    Column(
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
        modifier = Modifier.padding(horizontal = 24.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(26.dp),
            strokeWidth = 3.dp,
            color = tint,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            statusText,
            style = MaterialTheme.typography.labelMedium,
            color = dim,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        )
    }
}

/**
 * The chosen loading treatment(s), drawn over whichever card the user picked —
 * Settings → App Layout → Loading screen → Effect (see [LoadingEffects]).
 *
 * One implementation for all four styles, so "Minimal + gallery frame" and
 * "Spotlight + sheen" are exactly the same drawing code, and SEVERAL treatments
 * can be on at once (the sheen here, the aura ring around the card, the frame
 * around the cover): each one draws its own layer rather than replacing the
 * others. Every treatment is pure paint on top of the cover the style already
 * draws: no extra image load, no extra network request, and the style's own
 * motion (the breathing title, the drifting backdrop) keeps running underneath.
 *
 * Every one of them covers the whole screen — including the aura ring, which
 * hangs on the phone's own edge (see [LoadingAuraInset]) rather than around the
 * card it is drawn over.
 *
 * The two quiet styles were reported as too plain — "minimal and spotlight is so
 * simple, it just shows the title zooming in and out" — and this is the answer:
 * the same signature details a poster card can wear, on the loading card.
 *
 * [accent] is the colour the CARD is using for its own title and spinner (the
 * brand gold on Cinematic, the theme accent on the rest), so the treatment
 * belongs to the card it is drawn over instead of fighting it.
 */
@Composable
private fun LoadingCoverEffect(effects: Set<String>, accent: Color) {
    // The ring's colour: its own setting (Settings → App Layout → Loading screen
    // → Effect → Aura ring colour), or the card's accent when that is left on
    // "Accent" — see [com.hikari.app.ui.AuraColors].
    val auraColor = com.hikari.app.ui.AuraColors.color(rememberLoadingAuraColor(), accent)
    val chosen = com.hikari.app.ui.LoadingEffects.normalizeSet(effects)
    if (com.hikari.app.ui.LoadingEffects.AURA in chosen) {
        // The aura ring — hung on the SCREEN's own edge, not on the card.
        //
        // It used to hug the title card, which put it in the middle of a black
        // screen: a small rounded rectangle floating around some text, nowhere
        // near the phone's edges (the "the loading screen aura ring and gallery
        // frame are too far from the phone border" report). The ring is the
        // loading screen's frame lit up in the chosen colour, so it belongs at
        // the edge, wrapping the whole cover — a wide soft band of light under a
        // crisp hairline, breathing.
        val clock = rememberInfiniteTransition(label = "loading-aura")
        val breathe by clock.animateFloat(
            initialValue = 0.34f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1700, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "breathe",
        )
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = breathe },
        ) {
            // The light the ring stands in. A thick, faint stroke centred on the
            // same path, so the colour bleeds inwards instead of the ring being
            // a bare outline.
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(LoadingAuraInset)
                    .border(
                        14.dp,
                        auraColor.copy(alpha = 0.20f),
                        RoundedCornerShape(26.dp),
                    )
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(LoadingAuraInset)
                    .border(
                        2.dp,
                        Brush.linearGradient(listOf(auraColor, auraColor.copy(alpha = 0.45f), auraColor)),
                        RoundedCornerShape(26.dp),
                    )
            )
        }
    }

    if (com.hikari.app.ui.LoadingEffects.SHEEN in chosen) {
        // A band of light crossing the cover, the way a glossy print does when
        // the light catches it.
        val clock = rememberInfiniteTransition(label = "loading-sheen")
        val sweep by clock.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2800, easing = LinearEasing)),
            label = "sweep",
        )
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Read INSIDE the layer: a moving sheen invalidates this
                    // drawing layer only, never the composition.
                    translationX = -size.width + sweep * size.width * 2f
                    rotationZ = 16f
                }
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.16f),
                            Color.Transparent,
                        )
                    )
                )
        )
    }

    if (com.hikari.app.ui.LoadingEffects.FRAME in chosen) {
        // A gallery mat: a hairline mount around the cover, drawn just inside
        // the aura ring when both are chosen (see [LoadingAuraInset]).
        Box(
            Modifier
                .fillMaxSize()
                .padding(LoadingFrameInset)
                .border(
                    1.dp,
                    Color.White.copy(alpha = 0.22f),
                    RoundedCornerShape(18.dp),
                )
        )
    }

    if (com.hikari.app.ui.LoadingEffects.GLOW in chosen) {
        // The accent bloom behind the title, swelling and fading.
        val clock = rememberInfiniteTransition(label = "loading-glow")
        val pool by clock.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(3200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pool",
        )
        Canvas(Modifier.fillMaxSize()) {
            val r = kotlin.math.min(size.width, size.height) * (0.34f + pool * 0.10f)
            val center = Offset(size.width / 2f, size.height * 0.42f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.18f + 0.22f * pool),
                        accent.copy(alpha = 0.10f * pool),
                        Color.Transparent,
                    ),
                    center = center,
                    radius = r,
                ),
                radius = r,
                center = center,
            )
        }
    }
}

/**
 * Where the loading screen's "Aura ring" and "Gallery frame" sit.
 *
 * Both wrap the WHOLE cover, right out at the phone's own edge: the aura ring
 * is the outermost (a lit border around the screen), and the gallery frame is a
 * hairline mount just inside it, so choosing both gives one nested pair of
 * rings instead of two rings competing for the same path. The ring is a ROUNDED
 * rectangle — a circle drawn on a rectangular screen crosses it and reads as a
 * stray outline.
 *
 * These two numbers are shared by every loading style (they are drawn by
 * [LoadingCoverEffect], over whatever card the style put up) and are matched by
 * the player's own cover, which draws the same two rings in
 * activity_player.xml from its own margins — the detail screen's card and the
 * player's are meant to be the same picture, so the hand-off between them never
 * moves anything on screen.
 */
private val LoadingAuraInset = 8.dp
private val LoadingFrameInset = 18.dp

/** CINEMATIC — the original look: the backdrop drifting slowly under a heavy
 *  scrim, the title breathing in and out, the status line at the bottom. */
@Composable
private fun CinematicLoadingCard(
    title: String,
    episodeLabel: String?,
    detail: String?,
    image: String?,
    logo: String?,
    logoPercent: Int,
    effects: Set<String>,
    tvLayout: Boolean = false,
) {
    val transition = rememberInfiniteTransition()
    val breath by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    val model = PosterLoader.model(image?.takeIf { it.isNotBlank() })
    val cover = rememberLoadingCoverBrush(model != null)
    Box(Modifier.fillMaxSize().background(cover)) {
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val s = if (tvLayout) 1f else 1f + drift * 0.12f
                        scaleX = s
                        scaleY = s
                        alpha = if (tvLayout) 0.82f else 0.62f
                    }
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    // Over artwork the scrim keeps the title legible; over the
                    // no-artwork wash it would only re-blacken the screen we
                    // just lifted, so it is kept very light in that case.
                    if (model != null) {
                        Brush.verticalGradient(
                            listOf(Color(0xE6000000), Color(0x40000000), Color(0xE6000000))
                        )
                    } else {
                        Brush.verticalGradient(
                            listOf(Color(0x33000000), Color(0x00000000), Color(0x59000000))
                        )
                    }
                )
        )
        Column(
            Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp)
                .graphicsLayer {
                    scaleX = breath
                    scaleY = breath
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LoadingTitleBlock(
                title,
                episodeLabel,
                detail,
                logo,
                logoPercent,
                maxLogoHeight = if (tvLayout) 280.dp else 148.dp,
                logoWidthFraction = if (tvLayout) 0.82f else 0.78f,
            ) { Color(0xFFF5C569) }
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp)) {
            LoadingStatusLine(
                Color(0xFFF5C569),
                Color(0xCCFFFFFF),
                statusText = if (tvLayout) tr("Buffering…") else tr("Finding the best server…"),
            )
        }
        // The chosen loading treatments, over everything the style just drew
        // (Settings → App Layout → Loading screen → Effect).
        LoadingCoverEffect(effects, Color(0xFFF5C569))
    }
}

/**
 * The title / episode / detail block, shared by every style (only the accent
 * colour of the episode line differs).
 *
 * When the title has a WORDMARK ([logo]) it is drawn instead of the text title —
 * the same art the detail page's header draws, at the size the user chose for
 * the loading screen (Settings → App Layout → Loading screen → Title logo size).
 * Everything else about the block is unchanged, including the breathing its
 * caller applies: the wordmark grows and shrinks with the card exactly like the
 * text did.
 */
@Composable
private fun LoadingTitleBlock(
    title: String,
    episodeLabel: String?,
    detail: String?,
    /** The title's own wordmark art, or null/blank when it has none (or the
     *  user turned it off) — the text title is then drawn instead. */
    logo: String? = null,
    logoPercent: Int = 100,
    maxLogoHeight: androidx.compose.ui.unit.Dp = 148.dp,
    logoWidthFraction: Float = 0.78f,
    accent: () -> Color,
) {
    val logoModel = PosterLoader.model(logo?.takeIf { it.isNotBlank() })
    if (logoModel != null) {
        AsyncImage(
            model = logoModel,
            contentDescription = title,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                // 100% is a little under the full width of the card, which is
                // about the size a text title occupied; the slider moves it
                // either way from there (50%…160%).
                .fillMaxWidth(
                    (logoWidthFraction * (logoPercent.coerceIn(50, 160) / 100f)).coerceIn(0.2f, 1f)
                )
                .heightIn(max = maxLogoHeight)
        )
    } else {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )
    }
    if (!episodeLabel.isNullOrBlank()) {
        Text(
            episodeLabel,
            style = MaterialTheme.typography.titleMedium,
            color = accent(),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
    if (!detail.isNullOrBlank()) {
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xCCFFFFFF),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

/** SPOTLIGHT — no artwork: the accent blooms behind the title and a light
 *  sweeps across it, so the card is about the TITLE rather than the art. The
 *  title breathes more slowly and further than Cinematic's. */
@Composable
private fun SpotlightLoadingCard(
    title: String,
    episodeLabel: String?,
    detail: String?,
    image: String?,
    logo: String?,
    logoPercent: Int,
    effects: Set<String>,
) {
    val accent = MaterialTheme.colorScheme.primary
    val glowStart = MaterialTheme.colorScheme.tertiary
    val transition = rememberInfiniteTransition()
    val breath by transition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.10f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    // The pool of light behind the title grows and fades, in step with the
    // title's own breathing so the two read as one object.
    val pool by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Canvas(Modifier.fillMaxSize()) {
            val r = kotlin.math.min(size.width, size.height) * (0.34f + pool * 0.06f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.34f),
                        glowStart.copy(alpha = 0.16f),
                        Color.Transparent,
                    ),
                    center = Offset(size.width / 2f, size.height * 0.42f),
                    radius = r,
                ),
                radius = r,
                center = Offset(size.width / 2f, size.height * 0.42f),
            )
        }
        Column(
            Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp)
                .graphicsLayer {
                    scaleX = breath
                    scaleY = breath
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LoadingTitleBlock(title, episodeLabel, detail, logo, logoPercent) { accent }
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp)) {
            LoadingStatusLine(accent, Color(0xCCFFFFFF))
        }
        // The chosen loading treatments — on this style the accent blooms are
        // drawn over the title's own pool of light.
        LoadingCoverEffect(effects, accent)
    }
}

/** POSTER — the title's poster on a glass card, with a progress bar. The art
 *  is shown at its own 2:3 shape, so nothing is cropped and a portrait poster
 *  reads the way the user knows it. */
@Composable
private fun PosterLoadingCard(
    title: String,
    episodeLabel: String?,
    detail: String?,
    image: String?,
    logo: String?,
    logoPercent: Int,
    effects: Set<String>,
) {
    val accent = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition()
    // A slow rise-and-settle, so the card is alive without being busy.
    val lift by transition.animateFloat(
        initialValue = -4f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    val model = PosterLoader.model(image?.takeIf { it.isNotBlank() })
    val cover = rememberLoadingCoverBrush(model != null)
    Box(Modifier.fillMaxSize().background(cover)) {
        // The same art, blown up and dimmed, fills the frame so the card floats
        // on its own artwork instead of on flat black.
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.35f
                        scaleY = 1.35f
                        alpha = 0.20f
                    }
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    MaterialTheme.colorScheme.background
                        .copy(alpha = if (model != null) 0.55f else 0f)
                )
        )
        Column(
            Modifier
                .align(Alignment.Center)
                .padding(horizontal = 30.dp)
                .graphicsLayer { translationY = lift }
                    .clip(GlassShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), GlassShape)
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (model != null) {
                    AsyncImage(
                        model = model,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(width = 128.dp, height = 192.dp)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                    Spacer(Modifier.height(14.dp))
                }
                LoadingTitleBlock(title, episodeLabel, detail, logo, logoPercent) { accent }
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = accent,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    tr("Finding the best server…"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
        // The chosen loading treatments, over the glass card and its poster.
        LoadingCoverEffect(effects, accent)
    }
}

/** MINIMAL — flat background, a small title, the status line. Nothing moves
 *  except the spinner, which is the point: the quietest way to say "working". */
@Composable
private fun MinimalLoadingCard(
    title: String,
    episodeLabel: String?,
    detail: String?,
    logo: String?,
    logoPercent: Int,
    effects: Set<String>,
) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            Modifier
                .align(Alignment.Center)
                .padding(horizontal = 20.dp)
                .padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
                val logoModel = PosterLoader.model(logo?.takeIf { it.isNotBlank() })
                if (logoModel != null) {
                    // The wordmark takes its own line and keeps the quiet of this
                    // style: no breathing (Minimal draws nothing that moves) and
                    // no scrim behind it, just the art where the name was.
                    AsyncImage(
                        model = logoModel,
                        contentDescription = title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth(
                                (0.62f * (logoPercent.coerceIn(50, 160) / 100f))
                                    .coerceIn(0.2f, 1f)
                            )
                            .heightIn(max = 120.dp)
                    )
                } else {
                    Text(
                        title.uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val sub = listOfNotNull(
                    episodeLabel?.takeIf { it.isNotBlank() },
                    detail?.takeIf { it.isNotBlank() },
                ).joinToString("  ·  ")
                if (sub.isNotBlank()) {
                    Text(
                        sub,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Spacer(Modifier.height(26.dp))
                LoadingStatusLine(accent, MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // The chosen loading treatments. On this style they are the whole point:
        // without them the card is a title and a spinner and nothing else.
        LoadingCoverEffect(effects, accent)
    }
}

/** Builds the PlayerActivity "sources" JSON payload for the given streams,
 *  carrying torrent metadata so the player can spin up TorrServer. */
private fun playerPayload(streams: List<StreamSource>): String? = runCatching {
    JSONArray().apply {
        streams.forEach { s ->
            put(
                JSONObject()
                    .put("name", s.name)
                    .put("url", s.url)
                    .put("headers", JSONObject(s.headers))
                    .put("isM3u8", s.isM3u8)
                    .put("isMpd", s.isMpd)
                    .put("isTorrent", s.isTorrent)
                    .put("provider", s.provider)
                    // Which PROVIDER (repo plugin) produced this server, as
                    // opposed to which engine: the player gives the provider the
                    // user opened this title from its own section at the top of
                    // "Select server", and starts playback on its server.
                    .put("providerId", s.providerId)
                    .put("providerName", s.providerName)
                    .put("details", s.details)
                    .put("infoHash", s.infoHash ?: "")
                    .put("fileIdx", s.fileIdx ?: -1)
                    .put("torrentFileHint", s.torrentFileHint ?: "")
                    .put("torrentFileSize", s.torrentFileSize)
                    .put(
                        "trackers",
                        JSONArray().apply { s.trackers.forEach { put(it) } }
                    )
                    .put(
                        "subtitles",
                        JSONArray().apply {
                            s.subtitles.forEach {
                                put(JSONObject().put("lang", it.lang).put("url", it.url))
                            }
                        }
                    )
                    // DRM protection (ClearKey/Widevine) — required for the
                    // player to open a DRM session; without it a protected
                    // stream renders as a black screen.
                    .put(
                        "drm",
                        s.drm?.let { d ->
                            JSONObject()
                                .put("kid", d.kid ?: "")
                                .put("key", d.key ?: "")
                                .put("uuid", d.uuid ?: "")
                                .put("kty", d.kty ?: "")
                                .put("licenseUrl", d.licenseUrl ?: "")
                                .put("keyRequestParameters", JSONObject(d.keyRequestParameters))
                        } ?: JSONObject.NULL
                    )
            )
        }
    }.toString()
}.getOrNull()

/**
 * The shapes the detail page's header art can take — Settings → App Layout →
 * Details header.
 *
 *  - [WIDE]   the 16:9 banner the page has always opened with.
 *  - [SIDE]   a shorter art band with the poster standing beside it, so both the
 *             wide art and the real poster are visible at once.
 *  - [TALL]   tall cinematic art (3:4) that owns the top of the page.
 *  - [POSTER] just the poster, centred over a blurred copy of itself.
 *  - [PLAIN]  no art at all — the page starts at the title. The lightest header
 *             there is, for a slow connection or a data-saving mood.
 */
object DetailHeroStyles {
    const val WIDE = "wide"
    const val SIDE = "side"
    const val TALL = "tall"
    const val POSTER = "poster"
    const val PLAIN = "plain"

    val ALL = listOf(WIDE, SIDE, TALL, POSTER, PLAIN)

    fun normalize(key: String?): String = if (key != null && key in ALL) key else WIDE

    fun label(key: String): String = when (normalize(key)) {
        SIDE -> "Art + poster"
        TALL -> "Tall cinematic"
        POSTER -> "Poster only"
        PLAIN -> "No art"
        else -> "Wide banner"
    }

    fun description(key: String): String = when (normalize(key)) {
        SIDE -> "Wide art with the poster standing beside it"
        TALL -> "Tall 3:4 artwork owns the top of the page"
        POSTER -> "Just the poster, on a blurred copy of itself"
        PLAIN -> "No header art — the page starts at the title"
        else -> "A 16:9 banner that fades into the page"
    }
}

/** The plural TMDB header art for a title (the same /images backdrops list
 *  TV's banner rotates): loads once per title, empty while loading or when
 *  TMDB has nothing. Callers paint it first and fall back to their normal
 *  model — the single backdrop_path is often null while /images has art. */
@Composable
private fun rememberHeaderTmdbArts(item: MediaItem?): List<String> {
    var urls by remember(item?.uniqueId) { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(item?.uniqueId) {
        urls = emptyList()
        val it = item ?: return@LaunchedEffect
        if (it.type != MediaType.SERIES && it.type != MediaType.MOVIE) return@LaunchedEffect
        urls = runCatching { TmdbScreenshots.backdropsFor(it) }.getOrDefault(emptyList())
    }
    return urls
}

/** The page's header art, in whichever shape [style] asks for. */
@Composable
private fun Hero(
    meta: MediaItem?,
    fallbackPoster: String?,
    onBack: () -> Unit,
    style: String = DetailHeroStyles.WIDE,
) {
    // Item's own backdrop → the wide art we looked up → its poster, so a title
    // an extension left blank still gets a real banner here. The wide/poster
    // distinction matters: a portrait poster is never centre-cropped into a
    // 16:9 frame (that is what cut the art off).
    // A dead extension backdrop (hotlink-guarded CDN, expired signed URL)
    // falls back to the looked-up art once Coil proves it dead, instead of
    // sitting black for the whole visit (see [onImageError]).
    var heroBackdropDead by remember(meta?.uniqueId) { mutableStateOf(false) }
    // The plural TMDB backdrops first (what TV's banner shows): a dead
    // override falls back to the normal model below, never black.
    var tmdbHeaderDead by remember(meta?.uniqueId) { mutableStateOf(false) }
    // Phone rotates through the same plural list TV's banner rotates: one
    // TMDB image used to sit still here while TV kept changing every few
    // seconds. Every 2s, exactly as asked; a dead image falls back below.
    val tmdbHeaders = rememberHeaderTmdbArts(meta)
    var tmdbHeaderIndex by remember(meta?.uniqueId) { mutableStateOf(0) }
    LaunchedEffect(meta?.uniqueId, tmdbHeaders.size) {
        if (tmdbHeaders.size > 1) {
            while (true) {
                delay(2_000L)
                tmdbHeaderIndex = (tmdbHeaderIndex + 1) % tmdbHeaders.size
            }
        }
    }
    val tmdbHeader = tmdbHeaders.getOrNull(tmdbHeaderIndex)
    val image = if (!tmdbHeaderDead && !tmdbHeader.isNullOrBlank()) {
        PosterLoader.model(tmdbHeader) to true
    } else {
        meta?.let { Artwork.heroModel(it, skipExtensionBackdrop = heroBackdropDead) }
            ?: (PosterLoader.model(fallbackPoster) to false)
    }
    val posterModel = PosterLoader.model(fallbackPoster)
    // Adaptive: WIDE with only a portrait poster can never fill 16:9 without
    // cropping it, so it becomes the SIDE layout on its own — poster intact
    // on the left, art behind — instead of a beheaded banner.
    val adaptive = if (DetailHeroStyles.normalize(style) == DetailHeroStyles.WIDE && !image.second) {
        DetailHeroStyles.SIDE
    } else {
        style
    }
    when (DetailHeroStyles.normalize(adaptive)) {
        DetailHeroStyles.PLAIN -> Box(Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack, modifier = Modifier.padding(4.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = tr("Back"),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        DetailHeroStyles.TALL -> Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
        ) {
            PosterArt(
                model = image.first,
                contentDescription = meta?.title,
                style = rememberPosterStyle(),
                contentScale = if (image.second) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.45f),
                            0.30f to Color.Transparent,
                            0.62f to Color.Transparent,
                            1f to MaterialTheme.colorScheme.background,
                        )
                    )
            )
            IconButton(onClick = onBack, modifier = Modifier.padding(4.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = tr("Back"),
                    tint = Color.White,
                )
            }
        }

        DetailHeroStyles.POSTER -> Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f),
            contentAlignment = Alignment.Center,
        ) {
            // A plain dark wash fills the frame — the zoomed copy behind the
            // poster is what read as cropped faces and blurry sides.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.42f))
            )
            PosterArt(
                model = posterModel ?: image.first,
                contentDescription = meta?.title,
                style = rememberPosterStyle(),
                modifier = Modifier
                    .fillMaxHeight(0.86f)
                    .aspectRatio(2f / 3f),
            )
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).padding(4.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = tr("Back"),
                    tint = Color.White,
                )
            }
        }

        DetailHeroStyles.SIDE -> Box(
            Modifier
                .fillMaxWidth()
                .height(214.dp),
        ) {
            // The band behind the standing poster ALWAYS fills edge to edge in
            // 16:9: wide art crops as before, and a portrait poster is cropped
            // (anchored top, so heads survive) under a dark wash instead of
            // sitting Fit with blank bars on both sides. The band is backdrop
            // decor — the standing poster beside it keeps the art intact.
            PosterArt(
                model = image.first,
                contentDescription = meta?.title,
                style = rememberPosterStyle(),
                contentScale = ContentScale.Crop,
                imageAlignment = if (image.second) Artwork.CINEMA_ALIGNMENT else Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
                onImageError = { heroBackdropDead = true; tmdbHeaderDead = true },
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.40f),
                            0.45f to Color.Transparent,
                            1f to if (com.hikari.app.tv.TvMode.current()) com.hikari.app.tv.TvDesktop.Background
                            else MaterialTheme.colorScheme.background,
                        )
                    )
            )
            // The poster stands at the left, inset from the band's top and bottom
            // so the pair reads as "the artwork, and the poster you know".
            PosterArt(
                model = posterModel ?: image.first,
                contentDescription = null,
                style = rememberPosterStyle(),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp)
                    .fillMaxHeight(0.78f)
                    .aspectRatio(2f / 3f),
            )
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = tr("Back"),
                    tint = Color.White,
                )
            }
        }

        else -> Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
        ) {
            HeroArtwork(
                model = image.first,
                wide = image.second,
                modifier = Modifier.fillMaxSize(),
                onFinalError = { heroBackdropDead = true; tmdbHeaderDead = true },
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.55f to Color.Transparent,
                            1f to MaterialTheme.colorScheme.background
                        )
                    )
            )
            IconButton(onClick = onBack, modifier = Modifier.padding(4.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = tr("Back"),
                    tint = Color.White
                )
            }
        }
    }
}

/**
 * The Production row: the studios and networks behind this title, as tappable
 * logo tiles. Tapping one opens every title that company made (a TMDB entity
 * grid), which is what "clicking production loads all the series and movies from
 * that production" asks for.
 *
 * A company with no logo on TMDB still gets a tile — its initials on the app's
 * own surface — because the NAME is the useful part and a missing logo must not
 * remove a studio from the row.
 */
/**
 * The studio row for the TV hero: compact white chips (logo plate + name) in
 * one sideways row, instead of the phone's titled logo grid below the page.
 */
@Composable
private fun TvCompanyRow(
    companies: List<CompanyRef>,
    onClick: (CompanyRef) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        companies.distinctBy { (if (it.isNetwork) "n" else "c") + "|" + it.id }.forEach { c ->
            // Logo only, no name text: the tile already falls back to the
            // company's initials when no logo art exists, so the row never
            // shows an empty box — and never a redundant wordmark beside one.
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.12f))
                    .border(1.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(50))
                    .clickable { onClick(c) }
                    .padding(start = 5.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompanyLogoTile(
                    logoUrl = c.logoUrl,
                    name = c.name,
                    modifier = Modifier.height(26.dp).aspectRatio(2.4f),
                )
            }
        }
    }
}

@Composable
private fun ProductionRow(
    companies: List<CompanyRef>,
    onClick: (CompanyRef) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.padding(top = 12.dp)) {
        Text(
            tr("Production companies"),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val keyed = companies.distinctBy { (if (it.isNetwork) "n" else "c") + "|" + it.id }
            items(keyed, key = { (if (it.isNetwork) "n" else "c") + "|" + it.id }) { c ->
                Column(
                    Modifier
                        .width(92.dp)
                        .clip(shape)
                        .clickable { onClick(c) }
                        .padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(58.dp)
                            .clip(RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        CompanyLogoTile(
                            logoUrl = c.logoUrl,
                            name = c.name,
                            modifier = Modifier
                                .fillMaxWidth(0.84f)
                                .height(42.dp),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        c.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * One production company / network logo, on a plate that suits it.
 *
 * TMDB ships these as transparent PNGs, and it has two kinds: a WHITE wordmark
 * (drawn for a dark backdrop) and a COLOURED or dark one (drawn for a light
 * one). Drawn straight onto the page, one of the two is always wrong — a dark
 * navy Walt Disney script on a near-black card is the "the logo is a solid
 * black box" report, and a white wordmark on a white card is the same problem
 * inverted. So the tile MEASURES its logo: the average luminance of the opaque
 * pixels decides whether the plate behind it is near-black or off-white. That
 * makes every logo readable whatever colour it was drawn in, instead of fixing
 * one brand and breaking the next.
 *
 * The bitmap is fetched through Coil's own loader (the app's shared memory +
 * disk cache, so this costs one decode of an image the row would have loaded
 * anyway), and a logo that cannot be fetched or decoded falls back to the
 * company's initials so the row never shows an empty box.
 */
/**
 * The production-company logos already decoded this session, keyed by URL.
 *
 * The row they live in is a LazyRow, so a logo scrolled out of view has its
 * cell disposed and its `remember`ed bitmap dropped. Without this cache that
 * meant a visible hole (initials, or a bare plate) for the second or two it took
 * to fetch and decode the same image again every time the user scrolled back —
 * the reported "thumbnail on that provider becomes blank when I scroll, and
 * loads again when I come back".
 *
 * An [android.util.LruCache] of ~96 logos is a bounded few MB (these wordmarks
 * are small transparent PNGs) and is process-wide, so a studio's logo is also
 * instant on the NEXT detail page that lists it.
 */
private object CompanyLogoCache {
    private val cache = object :
        android.util.LruCache<String, Pair<android.graphics.Bitmap, Boolean>>(96) {}

    fun get(key: String): Pair<android.graphics.Bitmap, Boolean>? = cache.get(key)

    fun put(key: String, bitmap: android.graphics.Bitmap, light: Boolean) {
        runCatching { cache.put(key, bitmap to light) }
    }
}

@Composable
private fun CompanyLogoTile(
    logoUrl: String?,
    name: String,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // A logo that has been drawn once is kept in memory for the rest of the
    // session (see [CompanyLogoCache]). This row is a LazyRow: scrolling a logo
    // off-screen DISPOSES its cell, and re-entering it used to start from
    // nothing — so the tile flashed its initials (a "blank" thumbnail) until
    // Coil fetched and decoded the very same bytes again. That is the reported
    // "the one I just scrolled goes blank, and when I scroll back it loads
    // again". The cache is keyed by the logo URL alone: the same studio appears
    // on many detail pages, and the name only matters for the fallback.
    val key = logoUrl.orEmpty().ifBlank { "name|" + name }
    val memory = remember(key) { CompanyLogoCache.get(key) }
    var art by remember(key) { mutableStateOf(memory?.first) }
    var light by remember(key) { mutableStateOf(memory?.second) }

    LaunchedEffect(key) {
        if (art != null || logoUrl.isNullOrBlank()) return@LaunchedEffect
        val drawable = runCatching {
            withContext(Dispatchers.IO) {
                coil.Coil.imageLoader(context).execute(
                    coil.request.ImageRequest.Builder(context)
                        .data(logoUrl)
                        .allowHardware(false)
                        .build()
                ).drawable
            }
        }.getOrNull() ?: return@LaunchedEffect
        val bitmap = runCatching { drawableToBitmap(drawable) }.getOrNull()
            ?: return@LaunchedEffect
        val lit = logoIsLight(bitmap)
        CompanyLogoCache.put(key, bitmap, lit)
        light = lit
        art = bitmap
    }

    val picture = art
    if (picture == null) {
        // While the logo loads (or when there is none, or it will not decode):
        // the company's initials on the plain card, which is honest and never a
        // blank rectangle.
        Box(
            modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                compinitials(name),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    // The plate: off-white behind a dark logo, near-black behind a light one.
    val plate = if (light == true) Color(0xFF12151C) else Color(0xFFF2F3F7)
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(plate)
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = picture.asImageBitmap(),
            contentDescription = name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .height(32.dp),
        )
    }
}

/**
 * A Coil-loaded drawable as a [android.graphics.Bitmap], so [logoIsLight] can
 * read its pixels. A [android.graphics.drawable.BitmapDrawable] is already one;
 * anything else (a vector, an animated drawable, an SVG via coil-svg — TMDB
 * serves a few logos as SVG) is rasterised onto a fresh bitmap through a
 * Canvas. Written against `android.graphics` only, deliberately: this runs for
 * every logo on every detail page, and it must not depend on any of the
 * helper extensions a library update could move.
 */
private fun drawableToBitmap(drawable: android.graphics.drawable.Drawable): android.graphics.Bitmap {
    (drawable as? android.graphics.drawable.BitmapDrawable)?.let { return it.bitmap }
    val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 128
    val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 128
    val bitmap = android.graphics.Bitmap.createBitmap(
        w, h, android.graphics.Bitmap.Config.ARGB_8888
    )
    val canvas = android.graphics.Canvas(bitmap)
    drawable.setBounds(0, 0, w, h)
    drawable.draw(canvas)
    return bitmap
}

/** Two letters standing in for a logo that is missing or undrawable. */
private fun compinitials(name: String): String {
    val words = name.trim().split(' ').filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(2).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

/**
 * True when a logo's own pixels are bright enough to need a dark plate behind
 * them, false when they are dark enough to need a light one.
 *
 * A transparent PNG's colour channels are zeroed wherever the alpha is zero, so
 * the transparent pixels are skipped rather than averaged in (averaging them
 * would report every logo as black). The sample is a grid of at most ~40x40
 * pixels: a logo that is dark at its edge and light in its middle has to be
 * judged as a whole, and a full-resolution pass would cost more than the
 * download.
 */
private fun logoIsLight(bitmap: android.graphics.Bitmap): Boolean {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return false
    val stepX = (w / 40).coerceAtLeast(1)
    val stepY = (h / 40).coerceAtLeast(1)
    var total = 0.0
    var count = 0
    var x = 0
    while (x < w) {
        var y = 0
        while (y < h) {
            val px = bitmap.getPixel(x, y)
            val alpha = (px ushr 24) and 0xFF
            if (alpha >= 32) {
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                total += (0.299 * r + 0.587 * g + 0.114 * b) * (alpha / 255.0)
                count++
            }
            y += stepY
        }
        x += stepX
    }
    if (count == 0) return true // a fully transparent logo: assume a white one
    return (total / count) >= 120.0
}

/** A horizontal "Related"/"Similar" shelf of poster cells under the detail
 *  page's episode list (the Nuvio detail page's Related/Similar tabs, inline). */
@Composable
private fun ShelfRow(
    heading: String,
    shelf: List<MediaItem>,
    onClick: (MediaItem) -> Unit,
    onSearchHere: (MediaItem) -> Unit,
    onGlobalSearch: (MediaItem) -> Unit,
    /** The TV branch: the reference shelf header (title + scroll chevrons)
     *  over the same poster cells. Related/Similar have no grid destination
     *  of their own, so the View All pill stays hidden here. */
    tv: Boolean = false,
) {
    if (tv) {
        val rowState = remember { LazyListState() }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 18.dp)
        ) {
            Cs3ShelfHeader(title = heading, itemCount = shelf.size, scroll = rowState)
            Spacer(Modifier.height(10.dp))
            LazyRow(
                state = rowState,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // The shelf arrives from an extension; a repeated title would repeat
                // its Lazy key, which Compose treats as a crash rather than a
                // warning.
                items(shelf.distinctBy { it.uniqueId }, key = { it.uniqueId }) { item ->
                    ShelfCell(
                        item = item,
                        onClick = { onClick(item) },
                        onSearchHere = { onSearchHere(item) },
                        onGlobalSearch = { onGlobalSearch(item) },
                    )
                }
            }
        }
        return
    }
    Column(Modifier.padding(top = 12.dp)) {
        Text(
            heading,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // The shelf arrives from an extension; a repeated title would repeat
            // its Lazy key, which Compose treats as a crash rather than a
            // warning.
            items(shelf.distinctBy { it.uniqueId }, key = { it.uniqueId }) { item ->
                ShelfCell(
                    item = item,
                    onClick = { onClick(item) },
                    onSearchHere = { onSearchHere(item) },
                    onGlobalSearch = { onGlobalSearch(item) },
                )
            }
        }
    }
}

/**
 * One cell of a Related/Similar shelf.
 *
 * It draws through [PosterArt] like every other grid in the app, which is what
 * puts the score badge on it: these two rows used to be the only posters in
 * Hikari that ignored Settings → App Layout → "Show scores", because they
 * hand-rolled their own artwork box. Tapping the poster loads the title itself
 * (no trip through the Search tab).
 */
@Composable
private fun ShelfCell(
    item: MediaItem,
    onClick: () -> Unit,
    onSearchHere: () -> Unit,
    onGlobalSearch: () -> Unit,
) {
    val style = rememberPosterStyle()
    val badge = rememberPosterScore(item, style)
    var menuOpen by remember(item.uniqueId) { mutableStateOf(false) }
    Column(Modifier.width(112.dp)) {
        PosterArt(
            model = Artwork.model(item),
            contentDescription = item.title,
            style = style,
            rating = item.rating,
            imdb = badge,
            // The kebab owns the top-right corner of this cell.
            ratingAlignment = Alignment.TopStart,
            item = item,
            // …so the score badge has the top-LEFT here, and the type/quality
            // tags take the free corner below it.
            badgeAlignment = Alignment.BottomStart,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clickable { onClick() },
        ) {
            // The kebab in the corner carries the two ways to search instead
            // of open: this extension only, or every installed extension. Same
            // pair as the genre pills.
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
            ) {
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable { menuOpen = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = tr("Search options"),
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(tr("Search")) },
                        leadingIcon = {
                            Icon(Icons.Filled.Search, contentDescription = null)
                        },
                        onClick = {
                            menuOpen = false
                            onSearchHere()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(tr("Global search")) },
                        leadingIcon = {
                            Icon(Icons.Filled.Public, contentDescription = null)
                        },
                        onClick = {
                            menuOpen = false
                            onGlobalSearch()
                        }
                    )
                }
            }
        }
        Text(
            item.title,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** The "Show Details" block: year/runtime plus the age rating, then the
 *  coloured rating badges, then status/country/language, then the director/
 *  writer credits — the metadata Nuvio and Stremio show above their Cast row.
 *  Each line is skipped when the lookup had nothing for it, so a sparse TMDB
 *  record still renders cleanly. Badges are tappable: a tap opens
 *  [RatingDetailDialog], which explains the number the way the source site
 *  does. */
@Composable
private fun DetailsBlock(
    d: TitleDetails,
    ratings: List<TitleRating>,
    onRatingClick: (TitleRating) -> Unit,
    /** On TV the headline (year · runtime · age cert) already sits in the
     *  hero next to the ratings strip (reference layout) — hide it here so
     *  neither shows twice. Status/country/language/votes/release date/
     *  director/writers still render; only the duplicated top line goes. */
    hideHeadline: Boolean = false,
) {
    val stats = ArrayList<String>(4)
    d.year?.let { stats.add(it.toString()) }
    d.runtimeMinutes?.let { minutes ->
        val h = minutes / 60
        val mm = minutes % 60
        stats.add(if (h > 0) "${h}h ${mm}m" else "${mm}m")
    }

    val meta = ArrayList<String>(4)
    d.status?.let { meta.add(trStatus(it)) }
    d.country?.let { meta.add(it) }
    d.language?.let { meta.add(it) }
    d.voteCount?.takeIf { it > 0 }?.let {
        meta.add(tr("%s votes").replace("%s", it.toString()))
    }

    val cert = d.certification?.trim()?.takeIf { it.isNotEmpty() }

    // A TMDB record with nothing usable would otherwise render an empty block.
    // On TV the headline never counts toward "usable" — it lives in the hero.
    if ((hideHeadline || stats.isEmpty()) && meta.isEmpty() && ratings.isEmpty() && (hideHeadline || cert == null) &&
        d.director.isNullOrBlank() && d.writers.isEmpty()
    ) return

    Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
        // Year · runtime, with the age rating as its own bordered box next to
        // them — the way IMDb prints "PG-13". TV hides this whole line: the
        // hero already prints it (reference layout), and twice is the bug.
        if (!hideHeadline && (stats.isNotEmpty() || cert != null)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp)
            ) {
                if (stats.isNotEmpty()) {
                    Text(
                        stats.joinToString("  ·  "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium
                    )
                }
                if (cert != null) {
                    if (stats.isNotEmpty()) Spacer(Modifier.width(8.dp))
                    AgeChip(cert)
                }
            }
        }
        // The review-score strip: one badge per site, in that site's own colour
        // (see [RatingBadge]). Scrolls sideways so six of them still fit a
        // phone, and each one opens its own explanation.
        if (ratings.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ratings.forEach { r -> RatingBadge(r) { onRatingClick(r) } }
            }
        }
        if (meta.isNotEmpty()) {
            Text(
                meta.joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        // The full release date, the way the reference client's "Release Info"
        // row prints it. The stat line above carries only the year, which is all
        // a poster strip needs — but a date is what "when did this come out"
        // actually asks.
        d.releaseDate?.takeIf { it.isNotBlank() }?.let { iso ->
            Text(
                tr("Release date: %s").replace("%s", formatReleaseDate(iso)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        if (!d.director.isNullOrBlank()) {
            Text(
                tr("Director: %s").replace("%s", d.director),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        if (d.writers.isNotEmpty()) {
            Text(
                tr("Writers: %s").replace("%s", d.writers.joinToString(", ")),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/**
 * TMDB's `status` is always English — "Released", "Returning Series" — because
 * it is an enum rather than prose, so it is the one metadata field the app can
 * and must translate itself. The dictionary carries every value TMDB uses; a
 * status it invents later passes through untranslated, which is still better
 * than hiding the row.
 */
@Composable
private fun trStatus(status: String): String = when (status.trim().lowercase()) {
    "released" -> tr("Released")
    "returning series" -> tr("Ongoing series")
    "ended" -> tr("Ended")
    "canceled", "cancelled" -> tr("Canceled")
    "in production" -> tr("In production")
    "planned" -> tr("Planned")
    "post production" -> tr("Post production")
    "rumored" -> tr("Rumored")
    "pilot" -> tr("Pilot")
    else -> status
}

/**
 * TMDB's ISO release date, printed the way the device's/app's language prints a
 * date — "18 May 2001", "18 مايو 2001". The parsing and the formatting both go
 * through `java.text` (available on every API this app supports) rather than
 * `java.time`, which would need core-library desugaring.
 *
 * Anything unexpected is handed back exactly as TMDB sent it: a date the app
 * cannot format is still a date, and showing "2001-05-18" beats showing nothing.
 */
private fun formatReleaseDate(iso: String): String {
    val parsed = runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(iso)
    }.getOrNull() ?: return iso
    val formatted = runCatching {
        val tag = I18n.currentTag
        val locale = if (tag.isBlank()) java.util.Locale.getDefault()
        else java.util.Locale.forLanguageTag(tag)
        java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG, locale).format(parsed)
    }.getOrNull()
    return formatted ?: iso
}

/** The age rating ("PG-13", "R", "TV-MA") as a pill in the same tinted-glass
 *  style as the review badges next to it, so the two rows read as one family —
 *  but coloured by what the rating MEANS rather than by a site's brand:
 *  green for everyone, amber for guidance/teens, red for adults only, grey when
 *  no rating was published. The colour is the "can my kid watch this" signal at
 *  a glance; the letters are still the authority. */
@Composable
private fun AgeChip(label: String) {
    val tint = ageTint(label)
    val shape = RoundedCornerShape(9.dp)
    Box(
        Modifier
            .clip(shape)
            .background(tint.copy(alpha = 0.16f))
            .border(1.dp, tint.copy(alpha = 0.40f), shape)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            label,
            color = tint,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

// --------------------------------------------------------------- ratings --
// The sites' own brand colours, deliberately NOT the app's accent: a rating is
// recognised by the colour of the site that published it.

private val IMDbYellow = Color(0xFFF5C518)
private val TmdbCyan = Color(0xFF01B4E4)
private val TomatoRed = Color(0xFFFA320A)
private val TomatoGreen = Color(0xFF3FA33F)
private val MetacriticGreen = Color(0xFF00CE7A)
private val MetacriticYellow = Color(0xFFFFBD3F)
private val MetacriticRed = Color(0xFFFF6871)
private val LetterboxdGreen = Color(0xFF00C030)
private val AnilistBlue = Color(0xFF02A9FF)

// The age-rating chip's four bands. Not brand colours: the point is that the
// chip says the same thing on every title, whatever board issued the rating
// (MPAA "R", BBFC "18", FSK "16", TV-MA …).
private val AgeGreen = Color(0xFF34C759)
private val AgeAmber = Color(0xFFFFB020)
private val AgeRed = Color(0xFFE5484D)
private val AgeNeutral = Color(0xFF8A94A6)

/** The colour of an age rating: red = adults only, amber = guidance/teens,
 *  green = for everyone, grey = no rating published (NR, "Unrated", "N/A", or a
 *  board whose label this build doesn't know).
 *
 *  The named bands a viewer recognises come first ("R", "TV-MA", "PG-13" …);
 *  anything else falls back to the NUMBER in the label, which is how most
 *  boards outside the US grade — "FSK 16", "MA15+", "R18", "TV-14" — so a rating
 *  from a region this build has never seen still lands in the right band (18+
 *  red, 12–17 amber, 11 and under green) instead of coming out grey. */
private fun ageTint(label: String): Color {
    val v = label.trim().uppercase(java.util.Locale.US)
    when {
        // Adults only.
        v == "R" || v == "NC-17" || v == "X" || v == "XXX" || v == "TV-MA" ||
            v == "MA" || v == "M18" || v.startsWith("R18") -> return AgeRed
        // Guidance / teens: the US "PG" family, the MPAA-style "M", and the
        // certificate boards whose own word means "watch it with them".
        v.startsWith("PG") || v == "M" || v == "ATP" -> return AgeAmber
        // For everyone.
        v.startsWith("G") || v.startsWith("TV-Y") || v == "U" || v == "E" ||
            v == "ALL" || v == "TP" -> return AgeGreen
    }
    val number = Regex("\\d+").find(v)?.value?.toIntOrNull()
    return when {
        number == null -> AgeNeutral
        number >= 18 -> AgeRed
        number >= 12 -> AgeAmber
        else -> AgeGreen
    }
}

/** True when a tomatometer score sits in the site's "rotten" band. The band
 *  comes from the lookup itself (it reads RT's own sentiment), with the 60%
 *  rule as the fallback for a row cached before that was captured. */
private fun isRotten(r: TitleRating): Boolean = when (r.verdict) {
    RatingVerdict.ROTTEN -> true
    RatingVerdict.FRESH, RatingVerdict.CERTIFIED_FRESH -> false
    else -> percentOf(r.value) in 0..59
}

/** The colour a badge shows in: the site's brand colour, except for the sites
 *  whose number is itself score-dependent — Metacritic turns green/yellow/red
 *  with the Metascore, and a tomatometer number turns green when the title is
 *  rated "rotten". The tomato mark itself stays red at every score: it is the
 *  site's logo, and a green disc on the badge read as a bug. */
private fun ratingTint(r: TitleRating): Color = when (r.source) {
    RatingSource.IMDB -> IMDbYellow
    RatingSource.TMDB -> TmdbCyan
    RatingSource.ANILIST -> AnilistBlue
    RatingSource.TOMATOMETER -> if (isRotten(r)) TomatoGreen else TomatoRed
    RatingSource.POPCORN -> TomatoRed
    RatingSource.LETTERBOXD -> LetterboxdGreen
    RatingSource.METACRITIC -> when (r.verdict) {
        RatingVerdict.ACCLAIM, RatingVerdict.FAVORABLE -> MetacriticGreen
        RatingVerdict.MIXED -> MetacriticYellow
        RatingVerdict.UNFAVORABLE, RatingVerdict.DISASTER -> MetacriticRed
        else -> when (val s = r.value.trim().toIntOrNull()) {
            null -> MetacriticGreen
            in 61..Int.MAX_VALUE -> MetacriticGreen
            in 40..60 -> MetacriticYellow
            else -> MetacriticRed
        }
    }
}

private fun percentOf(value: String): Int =
    value.trim().removeSuffix("%").trim().toIntOrNull() ?: -1

/** One rating badge: the site's mark, then its number, in the site's colour on
 *  a tinted glass pill — so a row of six still reads as one strip. Tapping it
 *  opens the explanation dialog. */
@Composable
private fun RatingBadge(r: TitleRating, onClick: () -> Unit) {
    val tint = ratingTint(r)
    val shape = RoundedCornerShape(9.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(tint.copy(alpha = 0.16f))
            .border(1.dp, tint.copy(alpha = 0.40f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        RatingMark(r.source, tint)
        Text(
            r.value,
            color = tint,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/**
 * The site mark in front of the number, drawn from primitives — no image
 * assets and no network, so a badge can never be the thing that fails to load:
 * coloured wordmarks for IMDb and TMDB, Rotten Tomatoes' tomato, the striped
 * popcorn bucket for the audience score, a white M on the score's colour for
 * the Metascore, and Letterboxd's three dots. The tomato is red at every
 * score and the rotten/fresh split is carried by the number's colour.
 */
@Composable
private fun RatingMark(source: RatingSource, tint: Color) {
    when (source) {
        RatingSource.IMDB -> Text(
            "IMDb",
            color = IMDbYellow,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        RatingSource.TMDB -> Text(
            "TMDB",
            color = TmdbCyan,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        RatingSource.ANILIST -> Text(
            "AniList",
            color = AnilistBlue,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        RatingSource.TOMATOMETER -> Box(Modifier.size(width = 11.dp, height = 12.dp)) {
            Box(
                Modifier
                    .size(10.dp)
                    .align(Alignment.BottomCenter)
                    .clip(CircleShape)
                    .background(TomatoRed)
            )
            Box(
                Modifier
                    .size(width = 6.dp, height = 2.5.dp)
                    .align(Alignment.TopCenter)
                    .clip(RoundedCornerShape(1.dp))
                    .background(TomatoGreen)
            )
        }
        RatingSource.POPCORN -> Box(Modifier.size(width = 10.dp, height = 11.dp)) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .size(width = 9.dp, height = 8.dp)
                    .clip(RoundedCornerShape(1.dp))
            ) {
                Box(Modifier.weight(1f).height(8.dp).background(Color.White))
                Box(Modifier.weight(1f).height(8.dp).background(TomatoRed))
                Box(Modifier.weight(1f).height(8.dp).background(Color.White))
                Box(Modifier.weight(1f).height(8.dp).background(TomatoRed))
            }
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFFF0B8))
            )
        }
        RatingSource.METACRITIC -> Box(
            Modifier
                .size(14.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(tint),
            contentAlignment = Alignment.Center
        ) {
            Text("M", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        RatingSource.LETTERBOXD -> Row(horizontalArrangement = Arrangement.spacedBy(1.5.dp)) {
            listOf(Color(0xFFFF8000), Color(0xFF00E054), Color(0xFF40BCF4)).forEach { c ->
                Box(
                    Modifier
                        .size(4.5.dp)
                        .clip(CircleShape)
                        .background(c)
                )
            }
        }
    }
}

/** What the source calls the number it published. */
private fun ratingTitle(source: RatingSource): String = when (source) {
    RatingSource.IMDB -> "IMDb rating"
    RatingSource.TOMATOMETER -> "Rotten Tomatoes Tomatometer"
    RatingSource.POPCORN -> "Rotten Tomatoes Audience score"
    RatingSource.METACRITIC -> "Metacritic Metascore"
    RatingSource.LETTERBOXD -> "Letterboxd rating"
    RatingSource.TMDB -> "TMDB score"
    RatingSource.ANILIST -> "AniList score"
}

/** The site itself, for the "open the source" action. Brand names, so they are
 *  not translated. */
private fun ratingSite(source: RatingSource): String = when (source) {
    RatingSource.IMDB -> "IMDb"
    RatingSource.TOMATOMETER, RatingSource.POPCORN -> "Rotten Tomatoes"
    RatingSource.METACRITIC -> "Metacritic"
    RatingSource.LETTERBOXD -> "Letterboxd"
    RatingSource.TMDB -> "TMDB"
    RatingSource.ANILIST -> "AniList"
}

/** What the number actually measures, in the site's own terms. */
private fun ratingExplain(source: RatingSource): String = when (source) {
    RatingSource.IMDB ->
        "IMDb's score is the weighted average of every vote on the title, out of 10."
    RatingSource.TOMATOMETER ->
        "The share of professional critic reviews that were positive. 60% or more is Fresh; below 60% is Rotten."
    RatingSource.POPCORN ->
        "The share of audience ratings that were positive — what viewers thought, not critics."
    RatingSource.METACRITIC ->
        "A weighted average of professional critic reviews, out of 100. 61 and above is favourable, 40-60 mixed, below 40 unfavourable."
    RatingSource.LETTERBOXD ->
        "The average of Letterboxd members' ratings, out of 5."
    RatingSource.TMDB ->
        "The average user score on TMDB, shown as a percentage."
    RatingSource.ANILIST ->
        "The average user score on AniList, out of 10."
}

/** The scale the number is on, printed small next to it ("8.3 /10"). */
private fun ratingScale(source: RatingSource): String? = when (source) {
    RatingSource.IMDB -> "/10"
    RatingSource.METACRITIC -> "/100"
    RatingSource.LETTERBOXD -> "/5"
    else -> null
}

/** How the site counts what it is averaging, as a template the caller fills. */
private fun ratingVotesLine(source: RatingSource): String = when (source) {
    RatingSource.IMDB, RatingSource.TMDB -> "Based on %s votes"
    else -> "Based on %s ratings"
}

/**
 * The explanation behind a badge, the way a rating site itself explains a score:
 * where the number comes from, how that site's scale works, how many people it
 * is based on, and — for the sites that grade in words — the word for that band
 * ("Rotten", "Certified Fresh", "Acclaim"). It also offers to open the source
 * page, which is where the number's authority lives.
 */
@Composable
private fun RatingDetailDialog(r: TitleRating, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val tint = ratingTint(r)
    val context = androidx.compose.ui.platform.LocalContext.current
    val shape = RoundedCornerShape(20.dp)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .clip(shape)
                .background(scheme.surface)
                .border(1.dp, tint.copy(alpha = 0.45f), shape)
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RatingMark(r.source, tint)
                Spacer(Modifier.width(8.dp))
                Text(
                    tr(ratingTitle(r.source)),
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    r.value,
                    color = tint,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1
                )
                ratingScale(r.source)?.let { scale ->
                    Text(
                        " " + scale,
                        color = scheme.onSurfaceVariant,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
                r.verdict?.let { v ->
                    Spacer(Modifier.width(10.dp))
                    val chipShape = RoundedCornerShape(8.dp)
                    Box(
                        Modifier
                            .padding(bottom = 6.dp)
                            .clip(chipShape)
                            .background(tint.copy(alpha = 0.16f))
                            .border(1.dp, tint.copy(alpha = 0.45f), chipShape)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            tr(v.label),
                            color = tint,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                tr(ratingExplain(r.source)),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant
            )
            r.average?.let { avg ->
                if (r.source != RatingSource.LETTERBOXD) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        tr("Average score: %s").replace("%s", avg),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurface
                    )
                }
            }
            r.votes?.takeIf { it > 0 }?.let { votes ->
                Spacer(Modifier.height(8.dp))
                Text(
                    tr(ratingVotesLine(r.source)).replace("%s", formatCount(votes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurface
                )
            }
            r.url?.let { url ->
                Spacer(Modifier.height(12.dp))
                TextButton(
                    onClick = {
                        onDismiss()
                        openRatingPage(context, url, ratingSite(r.source))
                    }
                ) {
                    Text(tr("Open on %s").replace("%s", ratingSite(r.source)), color = tint)
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onDismiss) {
                Text(tr("Close"), color = scheme.onSurface)
            }
        }
    }
}

/** "14,283" — grouped, because a five- or six-figure review count is
 *  unreadable without separators. */
private fun formatCount(n: Long): String {
    val s = n.toString()
    val sb = StringBuilder(s.length + 4)
    for ((i, c) in s.withIndex()) {
        if (i > 0 && (s.length - i) % 3 == 0) sb.append(',')
        sb.append(c)
    }
    return sb.toString()
}

/** Opens a rating site in whatever the device uses for links, falling back to
 *  the in-app web view when nothing answers. The browser launch is stamped
 *  trusted (see ExternalLinks.launch): our own UI, never an extension. */
private fun openRatingPage(context: android.content.Context, url: String, title: String) {
    val opened = runCatching {
        com.hikari.app.net.ExtensionUiGuard.runTrusted {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }.isSuccess
    if (opened) return
    runCatching {
        context.startActivity(
            Intent(context, WebViewActivity::class.java).apply {
                putExtra("url", url)
                putExtra("title", title)
            }
        )
    }
}

/** Circular-headshot Cast row, matching the Nuvio/Stremio detail page. Tapping
 *  an actor runs a global search for their name — there is no person page in
 *  Hikari, and a search is the closest useful action.
 *
 *  [characters] switches the row to an anime's CHARACTER list (the faces the
 *  viewer knows), each cell's second line being the actor who voices them —
 *  see [com.hikari.app.data.AnimeCast]. */
@Composable
private fun CastRow(
    cast: List<CastMember>,
    characters: Boolean = false,
    onClick: (CastMember) -> Unit,
) {
    Column(Modifier.padding(top = 14.dp)) {
        Text(
            tr(if (characters) "Characters" else "Cast"),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            itemsIndexed(cast) { _, c ->
                Column(
                    Modifier
                        .width(84.dp)
                        .clickable { onClick(c) },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        val profile = PosterLoader.model(c.profileUrl)
                        if (profile != null) {
                            AsyncImage(
                                model = profile,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            // No headshot: the first initial of the name, so the
                            // circle never reads as an empty/broken cell.
                            Text(
                                c.name.trim().take(1).uppercase(),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Text(
                        c.name,
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    c.character?.let { role ->
                        Text(
                            // A role is content, so only our own dictionary can
                            // help (the dictionary carries the handful that
                            // appear again and again — "(voice)", "Himself",
                            // "Narrator"); a real character's name passes
                            // through untouched.
                            tr(role),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 1.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Trailer thumbnails (TMDB `videos` → YouTube stills). Tapping hands the
 *  video to the YouTube app (see [openYouTubeVideo]) instead of playing it in
 *  the in-app WebView, where YouTube's m.youtube.com redirect is blocked. */
@Composable
private fun TrailerRow(trailers: List<Trailer>, onClick: (Trailer) -> Unit) {
    Column(Modifier.padding(top = 14.dp)) {
        Text(
            tr("Trailers"),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(trailers) { _, t ->
                Column(
                    Modifier
                        .width(200.dp)
                        .clickable { onClick(t) }
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        val thumb = PosterLoader.model(t.thumbnailUrl)
                        if (thumb != null) {
                            AsyncImage(
                                model = thumb,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                        Box(
                            Modifier
                                .align(Alignment.Center)
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = Color.White
                            )
                        }
                    }
                    Text(
                        t.name,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        t.type,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/* The reference client's episode card on TV: a 16:9 still (the provider's
 * own thumbnail, else the TMDB still) with rating badge + watched check,
 * the S/E code, bold title, two-line synopsis and duration — over the dark
 * card with the hairline edge. Every TV episode mode renders through here,
 * so the stills, badges and progress read the same everywhere. A title with
 * no still at all gets the show art BLURRED, never a sharp-cropped poster. */
@Composable
private fun TvCs3EpisodeCard(
    ep: Episode,
    tmdbStill: String?,
    fallbackBlur: String?,
    watchState: Int,
    progress: Float,
    hideSpoilers: Boolean,
    isAnime: Boolean = false,
    /** TMDB's episode title/overview (see tvEpMeta): used ONLY when the
     *  provider shipped none of its own — never over provider text. */
    tmdbName: String? = null,
    tmdbOverview: String? = null,
    onPlay: () -> Unit,
    onDownload: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val epNum = ep.number
    val code = if (ep.season != null && ep.season > 0) "S" + ep.season + " \u00b7 " + tr("Episode %s").replace("%s", epNum.toString()).uppercase()
    else tr("Episode %s").replace("%s", epNum.toString()).uppercase()
    val title = ep.name?.takeIf { it.isNotBlank() }
        ?: tmdbName?.takeIf { it.isNotBlank() }
        ?: tr("Episode %s").replace("%s", epNum.toString())
    val desc = when {
        isEpisodeUnaired(ep.released) -> tr("Not released yet")
        !ep.overview.isNullOrBlank() -> ep.overview.trim()
        !tmdbOverview.isNullOrBlank() -> tmdbOverview.trim()
        else -> ""
    }
    val mins = ep.runtime?.takeIf { it > 0 }
    val duration = mins?.let { if (it >= 60) (it / 60).toString() + "h " + (it % 60).toString() + "m" else it.toString() + "m" }
    val dateText = ep.released?.trim()?.takeIf { it.isNotBlank() }?.take(10)
    Cs3EpisodeCard(
        // The desktop order: the TMDB still first, the episode's own
        // provider thumbnail only as the fallback.
        art = PosterLoader.model(tmdbStill?.takeIf { it.isNotBlank() } ?: ep.image?.takeIf { it.isNotBlank() }),
        fallbackBlur = PosterLoader.model(fallbackBlur),
        code = code,
        title = title,
        desc = desc,
        duration = duration,
        dateText = dateText,
        siteLabel = if (isAnime) "MAL" else "TMDB",
        rating10 = ep.rating?.takeIf { it > 0.0 },
        progress = if (watchState == 1) progress else 0f,
        watched = watchState == 2,
        hideSpoilers = hideSpoilers,
        onPlay = onPlay,
        onDownload = onDownload,
        modifier = modifier,
        textScale = 0.85f,
    )
}

@Composable
private fun EpisodePosterCard(
    ep: Episode,
    fallbackImage: String? = null,
    /** TMDB still for this episode (TV only): fills the frame when the
     *  provider shipped no thumbnail of its own. Null keeps the old art. */
    tmdbStill: String? = null,
    /** Television: the TMDB still wins over the provider thumbnail (desktop
     *  parity). Phones keep the old provider-first order. */
    tmdbFirst: Boolean = false,
    onClick: () -> Unit,
    onDownload: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    sizeScale: Float = 1f,
    /** Frosted-glass card variant (Settings → App Layout → Episode UI). */
    glass: Boolean = false,
    /** Anti-spoiler: blur the still and mask the title/overview. */
    hideSpoilers: Boolean = false,
) {
    val s = sizeScale.coerceIn(0.8f, 1.6f)
    val tokens = rememberGlassTokens()
    // Television wears the desktop card language (8dp corners,
    // elevated-black thumb, hairline edge, white/muted text).
    val tvEp = com.hikari.app.tv.TvMode.current()
    val epCard = if (tvEp) RoundedCornerShape(com.hikari.app.tv.TvDesktop.CornerCard)
    else RoundedCornerShape(10.dp)
    Column(
        (if (glass) {
            modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.verticalGradient(listOf(tokens.fillTop, tokens.fillBottom)))
                .border(1.dp, tokens.border, RoundedCornerShape(16.dp))
                .padding((8f * s).dp)
        } else {
            modifier
                .clip(epCard)
        })
            .clickable(onClick = onClick)
            .tvPress(previewPass = false, onClick = onClick)
            .padding(bottom = (4f * s).dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.55f)
                .clip(epCard)
                .background(
                    if (tvEp) com.hikari.app.tv.TvDesktop.SurfaceElevated
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .then(
                    if (tvEp) Modifier.border(
                        1.dp,
                        com.hikari.app.tv.TvDesktop.GlassEdge,
                        epCard,
                    ) else Modifier
                )
        ) {
            val thumb = PosterLoader.model(
                if (tmdbFirst) tmdbStill?.takeIf { it.isNotBlank() } ?: ep.image?.takeIf { it.isNotBlank() } ?: fallbackImage
                else ep.image?.takeIf { it.isNotBlank() } ?: tmdbStill?.takeIf { it.isNotBlank() } ?: fallbackImage
            )
            if (thumb != null) {
                AsyncImage(
                    model = thumb,
                    contentDescription = tr("Episode %s").replace("%s", ep.number.toString()),
                    modifier = Modifier.fillMaxSize()
                        .then(if (hideSpoilers) Modifier.blur(16.dp) else Modifier),
                    contentScale = ContentScale.Crop
                )
            }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding((6f * s).dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = (7f * s).dp, vertical = (3f * s).dp)
            ) {
                Text(
                    "E" + ep.number.toString().padStart(2, '0'),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            if (isEpisodeUnaired(ep.released)) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding((6f * s).dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.92f))
                        .padding(horizontal = (7f * s).dp, vertical = (3f * s).dp)
                ) {
                    Text(
                        tr("Not released yet"),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size((34f * s).dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.60f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = tr("Play"), tint = Color.White)
            }
            if (onDownload != null) {
                IconButton(
                    onClick = onDownload,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size((36f * s).dp)
                        .tvPress(previewPass = true, onClick = onDownload)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_download),
                        contentDescription = tr("Download"),
                        tint = Color.White,
                        modifier = Modifier.size((18f * s).dp)
                    )
                }
            }
        }
        Text(
            if (hideSpoilers) tr("Episode %s").replace("%s", ep.number.toString())
            else ep.name?.ifBlank { tr("Episode %s").replace("%s", ep.number.toString()) }
                ?: tr("Episode %s").replace("%s", ep.number.toString()),
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = (MaterialTheme.typography.labelMedium.fontSize.value * s).sp
            ),
            color = if (tvEp) com.hikari.app.tv.TvDesktop.TextPrimary else Color.Unspecified,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = (2f * s).dp, top = (5f * s).dp, end = (2f * s).dp)
        )
        // The TV grid never printed descriptions — only titles — so a TV
        // episode told the user nothing about itself. Gated by the same
        // Settings → TV switch as the list rows.
        val showDescription = HikariApp.instance.store.episodeDescriptionFlow().collectAsState(initial = true).value
        if (showDescription && (!ep.overview.isNullOrBlank() || hideSpoilers)) {
            Text(
                if (hideSpoilers) tr("Hidden to avoid spoilers") else ep.overview!!.trim(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = (MaterialTheme.typography.labelSmall.fontSize.value * s).sp
                ),
                color = if (tvEp) com.hikari.app.tv.TvDesktop.TextMuted
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = (2f * s).dp, top = (2f * s).dp, end = (2f * s).dp)
            )
        }
    }
}

/**
 * True when the episode's air date is in the future — an episode the source
 * lists but has not released yet. Only answers true for a date that actually
 * parses (yyyy-MM-dd, "August 16, 2026", dd/MM/yyyy, yyyy/MM/dd,
 * dd.MM.yyyy); anything unparseable or blank reads as released, so a weird
 * provider stamp can never hide a playable episode.
 */
private fun isEpisodeUnaired(released: String?): Boolean {
    val t = released?.trim().orEmpty()
    if (t.isBlank()) return false
    val today = java.util.Calendar.getInstance()
    today.set(java.util.Calendar.HOUR_OF_DAY, 0)
    today.set(java.util.Calendar.MINUTE, 0)
    today.set(java.util.Calendar.SECOND, 0)
    today.set(java.util.Calendar.MILLISECOND, 0)
    fun parsed(vararg formats: String): java.util.Date? {
        for (f in formats) {
            val d = runCatching {
                val sdf = java.text.SimpleDateFormat(f, java.util.Locale.US)
                sdf.isLenient = false
                sdf.parse(t.take(30))
            }.getOrNull()
            if (d != null) return d
        }
        return null
    }
    val head = t.take(10)
    val d = if (Regex("""\d{4}-\d{2}-\d{2}""").matches(head)) {
        parsed("yyyy-MM-dd", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss")
    } else {
        parsed(
            "MMMM d, yyyy", "MMM d, yyyy", "d MMMM yyyy", "d MMM yyyy",
            "dd/MM/yyyy", "d/M/yyyy", "yyyy/MM/dd", "dd.MM.yyyy",
        )
    } ?: return false
    return d.after(today.time)
}

/**
 * Big cinema episode card (episode view mode 2): one wide card per row — a
 * 16:9 still with the season/episode chip, center play and download, then the
 * title, the overview and the meta line underneath. The trailer-row look,
 * sized so a phone shows about one card at a time instead of three small
 * ones across the width.
 */
@Composable
private fun EpisodeBigCard(
    ep: Episode,
    fallbackImage: String? = null,
    /** TMDB still for this episode (TV only; desktop parity puts the still
     *  first). Null keeps the old provider-first art. */
    tmdbStill: String? = null,
    onClick: () -> Unit,
    onDownload: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    sizeScale: Float = 1f,
    /** Frosted-glass card variant (Settings → App Layout → Episode UI). */
    glass: Boolean = false,
    /** Anti-spoiler: blur the still and mask the title/overview. */
    hideSpoilers: Boolean = false,
) {
    val s = sizeScale.coerceIn(0.8f, 1.6f)
    val unaired = isEpisodeUnaired(ep.released)
    val tokens = rememberGlassTokens()
    Column(
        (if (glass) {
            modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.verticalGradient(listOf(tokens.fillTop, tokens.fillBottom)))
                .border(1.dp, tokens.border, RoundedCornerShape(16.dp))
                .padding((8f * s).dp)
        } else {
            modifier
                .clip(RoundedCornerShape(12.dp))
        })
            .clickable(onClick = onClick)
            .tvPress(previewPass = false, onClick = onClick)
            .padding(bottom = (6f * s).dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            val thumb = PosterLoader.model(tmdbStill?.takeIf { it.isNotBlank() } ?: ep.image?.takeIf { it.isNotBlank() } ?: fallbackImage)
            if (thumb != null) {
                AsyncImage(
                    model = thumb,
                    contentDescription = tr("Episode %s").replace("%s", ep.number.toString()),
                    modifier = Modifier.fillMaxSize()
                        .then(if (hideSpoilers) Modifier.blur(20.dp) else Modifier),
                    contentScale = ContentScale.Crop
                )
            }
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding((8f * s).dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = (8f * s).dp, vertical = (4f * s).dp)
            ) {
                Text(
                    "S" + ep.season.toString().padStart(2, '0') +
                        " E" + ep.number.toString().padStart(2, '0'),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            if (unaired) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding((8f * s).dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.92f))
                        .padding(horizontal = (8f * s).dp, vertical = (4f * s).dp)
                ) {
                    Text(
                        tr("Not released yet"),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            } else {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size((52f * s).dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.60f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = tr("Play"),
                        tint = Color.White,
                        modifier = Modifier.size((26f * s).dp)
                    )
                }
            }
            if (onDownload != null && !unaired) {
                IconButton(
                    onClick = onDownload,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size((40f * s).dp)
                        .tvPress(previewPass = true, onClick = onDownload)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_download),
                        contentDescription = tr("Download"),
                        tint = Color.White,
                        modifier = Modifier.size((20f * s).dp)
                    )
                }
            }
        }
        Text(
            if (hideSpoilers) tr("Episode %s").replace("%s", ep.number.toString())
            else ep.name?.ifBlank { tr("Episode %s").replace("%s", ep.number.toString()) }
                ?: tr("Episode %s").replace("%s", ep.number.toString()),
            style = MaterialTheme.typography.titleSmall.copy(
                fontSize = (MaterialTheme.typography.titleSmall.fontSize.value * s).sp
            ),
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = (4f * s).dp, top = (8f * s).dp, end = (4f * s).dp)
        )
        if (!ep.overview.isNullOrBlank() || hideSpoilers) {
            Text(
                if (hideSpoilers) tr("Hidden to avoid spoilers") else ep.overview!!.trim(),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = (MaterialTheme.typography.bodySmall.fontSize.value * s).sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = (4f * s).dp, top = (3f * s).dp, end = (4f * s).dp)
            )
        }
        val metaLine = buildString {
            formatEpisodeDate(ep.released)?.let { append(it) }
            ep.runtime?.takeIf { it > 0 }?.let {
                if (isNotEmpty()) append("  •  ")
                append("${it}m")
            }
            ep.rating?.takeIf { it > 0.0 }?.let {
                if (isNotEmpty()) append("  •  ")
                append(tr("Rated: %s").replace("%s", String.format(java.util.Locale.US, "%.1f", it)))
            }
        }.takeIf { it.isNotBlank() }
        if (!metaLine.isNullOrBlank()) {
            Text(
                metaLine,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = (4f * s).dp, top = (3f * s).dp, end = (4f * s).dp)
            )
        }
    }
}

/** "1999-10-20" (or a longer stamp starting with it) printed the way the
 *  reference clients print it — "October 20, 1999". Anything else passes
 *  through untouched; blank in, null out. */
private fun formatEpisodeDate(raw: String?): String? {
    val t = raw?.trim().orEmpty()
    if (t.isBlank()) return null
    val head = t.take(10)
    if (!Regex("""\d{4}-\d{2}-\d{2}""").matches(head)) return t
    return runCatching {
        val parsed = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(head)
            ?: return t
        java.text.SimpleDateFormat("MMMM d, yyyy", java.util.Locale.US).format(parsed)
    }.getOrNull() ?: t
}

@Composable
private fun EpisodeRow(
    ep: Episode,
    fallbackImage: String? = null,
    onClick: () -> Unit,
    /** Download this episode without watching it: the player opens on it and
     *  shows its own download chooser the moment a server is ready. Null hides
     *  the button (callers that have nowhere to send a download). */
    onDownload: (() -> Unit)? = null,
    sizeScale: Float = 1f,
    /** Frosted-glass card variant (Settings → App Layout → Episode UI): the
     *  CloudStream-style rounded translucent card instead of the flat row. */
    glass: Boolean = false,
    /** Anti-spoiler: blur the thumb and mask the title/overview. */
    hideSpoilers: Boolean = false,
    /** Per-episode watch state (see epWatchState): 0 = untouched, 1 =
     *  watching, 2 = watched. Drawn as a small pill under the buttons. */
    watchState: Int = 0,
) {
    val s = sizeScale.coerceIn(0.8f, 1.6f)
    val tokens = rememberGlassTokens()
    // Television rows sit flat on the black page (desktop language) with
    // white/muted text; phones keep the near-page fill.
    val tvRow = com.hikari.app.tv.TvMode.current()
    Row(
        (if (glass) {
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.verticalGradient(listOf(tokens.fillTop, tokens.fillBottom)))
                .border(1.dp, tokens.border, RoundedCornerShape(16.dp))
        } else {
            Modifier
                .fillMaxWidth()
                .background(
                    if (tvRow) Color.Transparent
                    else MaterialTheme.colorScheme.background.copy(alpha = 0.98f)
                )
        })
            .clickable(onClick = onClick)
            .tvPress(previewPass = false, onClick = onClick)
            .padding(horizontal = (16f * s).dp, vertical = if (glass) 12.dp else (8f * s).dp),
        // The whole card (thumb, text, buttons) is vertically centred: with Top
        // the play + download buttons sat visibly higher than the episode text
        // (screenshot-proven), and the buttons must sit in the middle of the box.
        verticalAlignment = Alignment.CenterVertically
    ) {
        val thumb = PosterLoader.model(ep.image?.takeIf { it.isNotBlank() } ?: fallbackImage)
        if (thumb != null) {
            AsyncImage(
                model = thumb,
                contentDescription = null,
                modifier = Modifier
                    .size((56f * s).dp)
                    .clip(RoundedCornerShape(8.dp))
                    .then(if (hideSpoilers) Modifier.blur(10.dp) else Modifier),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (tvRow) com.hikari.app.tv.TvDesktop.SurfaceElevated
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    ep.number.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.width((12f * s).dp))
        Column(Modifier.weight(1f)) {
            Text(
                ep.number.toString() + ". " + (
                    if (hideSpoilers) tr("Episode %s").replace("%s", ep.number.toString())
                    else ep.name?.ifBlank { tr("Episode %s").replace("%s", ep.number.toString()) }
                        ?: tr("Episode %s").replace("%s", ep.number.toString())
                    ),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = (MaterialTheme.typography.bodyMedium.fontSize.value * s).sp
                ),
                color = if (tvRow) com.hikari.app.tv.TvDesktop.TextPrimary else Color.Unspecified,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val aired = formatEpisodeDate(ep.released)
            if (isEpisodeUnaired(ep.released)) {
                Text(
                    tr("Not released yet"),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val ratedLine = buildString {
                ep.rating?.takeIf { it > 0.0 }?.let {
                    append(tr("Rated: %s").replace("%s", String.format(java.util.Locale.US, "%.1f", it)))
                }
                ep.runtime?.takeIf { it > 0 }?.let {
                    if (isNotEmpty()) append("  ")
                    append("${it}m")
                }
            }.takeIf { it.isNotBlank() }
            if (!ratedLine.isNullOrBlank()) {
                Text(
                    ratedLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (tvRow) com.hikari.app.tv.TvDesktop.TextMuted
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!aired.isNullOrBlank()) {
                Text(
                    aired,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (tvRow) com.hikari.app.tv.TvDesktop.TextMuted
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val showDescription = HikariApp.instance.store.episodeDescriptionFlow().collectAsState(initial = true).value
            if (showDescription && (!ep.overview.isNullOrBlank() || hideSpoilers)) {
                Text(
                    if (hideSpoilers) tr("Hidden to avoid spoilers") else ep.overview!!.trim(),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = (MaterialTheme.typography.bodySmall.fontSize.value * s).sp
                    ),
                    color = if (tvRow) com.hikari.app.tv.TvDesktop.TextMuted
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = (2f * s).dp)
                )
            }
        }
        // Play + download ride one centered row of equal buttons: a bare Icon
        // against a 44dp IconButton sat the triangle visibly higher than the
        // download glyph (screenshot-proven on Black Clover S2). The episode's
        // own watch state rides UNDER them as a small pill, centred under the
        // pair — equal space on both sides — instead of hugging one end.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onClick,
                modifier = Modifier.size((44f * s).dp).tvPress(previewPass = false, onClick = onClick),
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size((24f * s).dp),
                )
            }
            if (onDownload != null) {
                Spacer(Modifier.width((4f * s).dp))
                IconButton(onClick = onDownload, modifier = Modifier.size((44f * s).dp).tvPress(previewPass = true, onClick = onDownload)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_download),
                        contentDescription = tr("Download"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size((20f * s).dp)
                    )
                }
            }
        }
        if (watchState != 0) {
            Spacer(Modifier.height(2.dp))
            EpisodeWatchBadge(watchState = watchState, sizeScale = s)
        }
        }
    }
}

/**
 * The episode row's own watch pill: a small readout under the play + download
 * buttons (1 = watching, 2 = watched). Deliberately tiny — an 11sp label in
 * a tight pill — so it fits the row's corner without moving anything else.
 * Untouched episodes draw nothing at all rather than a pill that says "no".
 */
@Composable
private fun EpisodeWatchBadge(
    watchState: Int,
    sizeScale: Float = 1f,
) {
    if (watchState != 1 && watchState != 2) return
    val scheme = MaterialTheme.colorScheme
    val watched = watchState == 2
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (watched) scheme.primary.copy(alpha = 0.20f) else Color.Transparent
            )
            .border(
                width = 1.dp,
                color = if (watched) scheme.primary.copy(alpha = 0.60f)
                else scheme.outline.copy(alpha = 0.60f),
                shape = RoundedCornerShape(50),
            )
            .padding(horizontal = (8f * sizeScale).dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (watched) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size((11f * sizeScale).dp),
            )
            Spacer(Modifier.width(3.dp))
        }
        Text(
            if (watched) tr("Watched") else tr("Watching"),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = (11f * sizeScale).sp
            ),
            color = if (watched) scheme.primary else scheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * The name to SEARCH the extensions with, for a title the app is showing in a
 * language they do not index.
 *
 * The page keeps the name the user tapped — that is the whole point of the
 * title-language setting — but every provider the background lookup asks
 * (~250 of them) indexes the ORIGINAL, English name. A TMDB row carries that
 * name already ([com.hikari.app.data.MediaItem.originalTitle], filled from
 * TMDB's own response). An EXTENSION item does not: it is named whatever its
 * site calls it, so an Arabic repo's "فلم موانا" — a perfectly good display
 * name — was handed to every other repo as the search key, none of them
 * recognised it, and the movie played from the nuvio engines alone, because
 * those resolve by TMDB id and never read the title. In English the same
 * movie had servers everywhere. That is the reported asymmetry.
 *
 * So this translates the item's own name to English once (gtx, cached in
 * memory AND on disk by [com.hikari.app.data.Translator]), and the result rides
 * on the item as its original name — which is what
 * [com.hikari.app.data.MediaItem.searchTitle] prefers, and therefore what the
 * cross pass, the TMDB lookup behind the Cast/Trailers/Details rows and the
 * episode-name lookup all use.
 *
 * Bounded and cheap: a name that is already Latin/English is recognised
 * locally and never leaves the device (the common case, so the ordinary page
 * pays nothing), and a foreign one waits at most [NAME_LOOKUP_MS] — on the
 * first open of that title only, because the translation is cached. Null when
 * there is nothing better to search with, which keeps the display name in
 * place.
 */
private const val NAME_LOOKUP_MS = 2_500L

/**
 * The words a translator ADDS when it is handed a bare title: "فلم موانا" comes
 * back as "The movie Moana", not "Moana", and "مسلسل لوست" as "The series Lost".
 *
 * They cannot be left in the search key. It is the key every extension is asked
 * for and the name TMDB is asked to resolve, and no index holds "the movie
 * moana" — so the whole point of translating the name would be undone by the one
 * word the translator volunteered. It appends them as readily as it prefixes
 * them ("Breaking Bad" + "مسلسل" → "Breaking Bad series"), so both ends are
 * stripped, repeatedly, until neither matches.
 *
 * A strip is refused when it would leave fewer than three characters or nothing
 * but digits: "Movie 43" must stay "Movie 43", not "43", and a title that really
 * ends in one of these words is protected by that same rule.
 */
private val TITLE_DECORATION_LEAD = Regex(
    "(?i)^\\s*(?:the\\s+|a\\s+|an\\s+)?" +
        "(?:full\\s+)?(?:movies?|films?|tv\\s+series|tv\\s+shows?|series|shows?|" +
        "animes?|animations?|cartoons?|dramas?|documentar(?:y|ies)|officials?)\\s*[:\\-–—]?\\s+",
)

/** The same words when the translator puts them LAST — which is just as common:
 *  the arabic "مسلسل برايكينغ باد" comes back as "Breaking Bad series" and
 *  "انمي ناروتو" as "Naruto anime". */
private val TITLE_DECORATION_TAIL = Regex(
    "(?i)\\s+(?:full\\s+)?(?:movies?|films?|tv\\s+series|tv\\s+shows?|series|shows?|" +
        "animes?|animations?|cartoons?|dramas?|documentar(?:y|ies))\\s*$",
)

private fun stripSearchDecorations(name: String): String {
    var s = name.trim()
    repeat(3) {
        var next = s.replaceFirst(TITLE_DECORATION_LEAD, "").trim()
        next = next.replaceFirst(TITLE_DECORATION_TAIL, "").trim()
        if (next == s) return s
        if (next.length < 3 || next.all { it.isDigit() }) return s
        s = next
    }
    return s
}

private suspend fun englishSearchName(title: String, isTmdbRow: Boolean): String? {
    // A TMDB row resolves its own original name (see the call site), so this is
    // only ever the extension-item path.
    if (isTmdbRow) return null
    val own = title.trim()
    if (own.isBlank()) return null
    val english = withTimeoutOrNull(NAME_LOOKUP_MS) {
        withContext(Dispatchers.IO) {
            runCatching { Translator.translate(own).trim() }.getOrNull()
        }
    }?.takeIf { it.isNotBlank() && it != own } ?: return null
    return stripSearchDecorations(english).ifBlank { english }
}

/**
 * One chip of the detail page's mark strip.
 *
 * A mark that is SET is a filled chip in the accent colour with its tick; the
 * strip's own way into the sheet — which is also what the strip shows when
 * nothing is set — is an outlined, muted chip. Both are tappable and both open
 * the mark sheet, so the strip is a readout first and a control second: the user
 * sees WHICH mark a title carries without having to open anything (the whole
 * point of drawing it), and every action the sheet holds stays one tap away.
 */
@Composable
private fun MarkChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (active) scheme.primary.copy(alpha = 0.18f) else Color.Transparent
            )
            .border(
                width = 1.dp,
                color = if (active) scheme.primary.copy(alpha = 0.55f)
                else scheme.outline.copy(alpha = 0.6f),
                shape = RoundedCornerShape(50),
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (active) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (active) scheme.primary else scheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
