package com.hikari.app.ui.screens

import android.app.Application
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.hikari.app.HikariApp
import com.hikari.app.data.ContentRepository
import com.hikari.app.data.Episode
import com.hikari.app.data.HistoryEntry
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.ProviderType
import com.hikari.app.data.StreamSource
import com.hikari.app.net.StreamProbe
import com.hikari.app.player.PlayerActivity
import com.hikari.app.player.StreamsLive
import com.hikari.app.providers.ContentProvider
import com.hikari.app.ui.PosterLoader
import com.hikari.app.ui.components.EmptyState
import com.hikari.app.ui.navigation.Routes
import com.hikari.app.web.WebViewActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class DetailViewModel(app: Application) : AndroidViewModel(app) {
    private val manager = (app as HikariApp).providers
    private val repo = ContentRepository(manager)

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

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Streams resolved ahead of time (first episode / movie) so tapping Play
     *  or the first episode starts instantly instead of waiting 20-30s for
     *  extraction. Keyed by the target id. */
    private val streamCache = ConcurrentHashMap<String, List<StreamSource>>()

    private val _streamsReady = MutableStateFlow(false)
    val streamsReady: StateFlow<Boolean> = _streamsReady.asStateFlow()

    /** Growing list of sources found SO FAR for the current lookup, re-emitted
     *  after every provider answers — lets the UI start playback the instant
     *  the first server appears instead of waiting for all providers. */
    private val _liveStreams = MutableStateFlow<List<StreamSource>>(emptyList())
    val liveStreams: StateFlow<List<StreamSource>> = _liveStreams.asStateFlow()

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
                    (it.config.type == ProviderType.NUVIO &&
                        com.hikari.app.nuvio.TmdbResolver.isLikelyResolvable(item)))
        }

    private fun recordOutcome(result: List<StreamSource>, item: MediaItem) {
        _searchedProviders.value = streamTargets(item).size
        if (result.isEmpty()) {
            // Attribute the failure to the ORIGIN provider only — a global
            // "last error" from a different video (e.g. iStreamFlare on the
            // hstream title) used to leak into every other extension's "no
            // sources" message and made the whole app look broken.
            val origin = manager.byId(item.providerId)
            _streamError.value = when (origin?.config?.type) {
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
                else -> null
            }
        } else {
            _streamError.value = null
        }
    }

    fun load(providerId: String, type: MediaType, mediaId: String, title: String, posterUrl: String?, rawType: String) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            _streamsReady.value = false
            _streamError.value = null
            if (manager.byId(providerId) == null) {
                _error.value = "Provider not found"
                _loading.value = false
                return@launch
            }
            // The catalog row already carries the poster — render the page
            // immediately instead of waiting on the origin's /meta (which may
            // be slow or minimal). rawType keeps the addon's own type string
            // for meta/episode/stream URLs.
            val base = MediaItem(
                providerId, mediaId, title, type,
                posterUrl = posterUrl,
                rawType = rawType,
            )
            _meta.value = base
            _loading.value = false
            // Movies: start the multi-provider source search NOW — before the
            // origin's /meta and episode fetches — so the first server is
            // already resolving while the page renders. Previously the search
            // only began after meta+episodes landed, which is why tapping Play
            // sat on a spinner while the (slow) providers were still warming up.
            if (type != MediaType.SERIES) {
                launch { prefetchFirstStreams(base) }
            }
            withContext(Dispatchers.IO) {
                // Fetch meta FIRST — CS3 plugins can label a series/actor page
                // as a movie on their search results (LeakPorner actors are
                // NSFW→MOVIE), and getMeta corrects the type from the
                // LoadResponse. Episodes are then fetched against the
                // CORRECTED item (loadResponse is cached, so this stays a
                // single origin fetch) — fetching against the raw base would
                // leave the episode grid empty for every mis-typed item.
                val meta = runCatching { repo.metaFor(base) }.getOrDefault(base)
                _meta.value = meta
                _episodesLoading.value = true
                try {
                    _episodes.value = runCatching { repo.episodesFor(meta) }.getOrNull()
                } finally {
                    _episodesLoading.value = false
                }
            }
            prefetchFirstStreams(_meta.value ?: base)
        }
    }

    /** Streams currently being resolved, keyed the same as [streamCache]. A
     *  Play tap while the page is still prefetching joins the SAME extraction
     *  instead of launching a second one — two concurrent loadLinks runs on the
     *  same CS3 plugin instance can corrupt its state and make it return "no
     *  sources" for a movie that plays fine on its own. */
    private val inflight = ConcurrentHashMap<String, CompletableDeferred<List<StreamSource>>>()

    private suspend fun resolveStreams(
        item: MediaItem,
        ep: Episode?,
        onProgress: (suspend (List<StreamSource>) -> Unit)? = null,
    ): List<StreamSource> {
        val key = cacheKey(item, ep)
        streamCache[key]?.let { cached ->
            _liveStreams.value = cached
            return cached
        }
        val existing = inflight[key]
        if (existing != null) return existing.await()
        val deferred = CompletableDeferred<List<StreamSource>>()
        val prev = inflight.putIfAbsent(key, deferred)
        if (prev != null) return prev.await()
        try {
            // Every provider response is mirrored into the live feed so the UI
            // can start playback with the first server found, regardless of
            // which caller kicked off the search (prefetch or Play tap).
            val feed: (suspend (List<StreamSource>) -> Unit) = { partial ->
                _liveStreams.value = partial
                onProgress?.invoke(partial)
            }
            val result = withContext(Dispatchers.IO) {
                runCatching { repo.streamsFor(item, ep, feed) }.getOrDefault(emptyList())
            }
            streamCache[key] = result
            _liveStreams.value = result
            recordOutcome(result, item)
            deferred.complete(result)
            return result
        } catch (e: Throwable) {
            deferred.complete(emptyList())
            throw e
        } finally {
            inflight.remove(key)
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
        if (streamCache.containsKey(key)) {
            _streamsReady.value = true
            return
        }
        // Set "ready" as soon as the FIRST source arrives (not only after every
        // provider has been searched), so the Play button lights up early while
        // the slower providers keep adding servers in the background.
        resolveStreams(item, ep) { partial ->
            if (partial.isNotEmpty()) _streamsReady.value = true
        }
        _streamsReady.value = true
    }

    private fun cacheKey(item: MediaItem, ep: Episode?): String =
        item.providerId + "|" + item.id + "|" + (ep?.id ?: "")

    suspend fun getStreams(
        episode: Episode?,
        onProgress: (suspend (List<StreamSource>) -> Unit)? = null,
    ): List<StreamSource> {
        val m = _meta.value ?: return emptyList()
        return resolveStreams(m, episode, onProgress)
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
        else -> null
    }
    return msg?.let { "$name: $it" }
}

/** How long a replay waits for the server it was last played with to appear in
 *  the multi-provider source search before falling back to the first server
 *  found. Long enough for a slower provider to answer, short enough that a tap
 *  never appears to hang. */
private const val PREFERRED_GRACE_MS = 10_000L

/** How long the "finding server" overlay may stay up before the source sheet is
 *  revealed as a safety net. Auto-play still wins if a playable server appears
 *  first; this only guarantees the tap is never a dead end when the search is
 *  slow, hangs, or the player can't be opened. */
private const val SHEET_FALLBACK_MS = 4_500L

@OptIn(ExperimentalMaterial3Api::class)
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
) {
    val vm: DetailViewModel = viewModel()
    val meta by vm.meta.collectAsState()
    val episodes by vm.episodes.collectAsState()
    val episodesLoading by vm.episodesLoading.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val searchedProviders by vm.searchedProviders.collectAsState()
    val streamError by vm.streamError.collectAsState()
    val providers by vm.providers.collectAsState()
    val m = meta

    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var showSheet by remember { mutableStateOf(false) }
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
    // True while a tap is resolving servers. A full-screen "finding server"
    // overlay covers the detail page and playback then starts on its own — the
    // source list is no longer shown up-front. It only appears if the search
    // genuinely finds nothing playable.
    var preparingSources by remember { mutableStateOf(false) }
    /** Live-update session handed to the player: while playback runs, the
     *  ongoing multi-provider search keeps appending servers to it. */
    var sessionId by remember { mutableStateOf("") }
    var selectedSeason by rememberSaveable { mutableStateOf<Int?>(null) }
    var seasonExpanded by remember { mutableStateOf(false) }
    var rangeExpanded by remember { mutableStateOf(false) }

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
    // Episode pagination: a long-running donghua can have 600+ episodes in a
    // single season, which used to force one enormous scroll. Split the current
    // season into 30-episode pages and expose a page picker (just like the
    // season picker) right next to the episode count. `remember(activeSeason)`
    // snaps back to page 1 whenever the user switches season.
    val epPageSize = 30
    var rangeStart by remember(activeSeason) { mutableStateOf(0) }
    val ranges = remember(shownEps) {
        if (shownEps.size <= epPageSize) emptyList()
        else (0 until shownEps.size step epPageSize).toList()
    }
    val safeStart = if (ranges.isEmpty()) 0 else rangeStart.coerceIn(0, ranges.last())
    val pageEps = remember(shownEps, safeStart) {
        shownEps.drop(safeStart).take(epPageSize)
    }

    LaunchedEffect(providerId, mediaId) {
        vm.load(providerId, type, mediaId, title, posterUrl, rawType)
    }

    // Watch-history for this title (every episode), so a tapped video can offer
    // "continue from where you left off?" no matter how the user got here
    // (History tab, Home, Continue Watching, or a catalog).
    val app = context.applicationContext as HikariApp
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

    var playerLaunched by remember { mutableStateOf(false) }
    // Resets the once-only launch guard the moment the player activity returns
    // to this screen — without this, the FIRST play set the flag and every
    // later tap (episode 2..N, another server) was silently swallowed, so a
    // 10-episode melon list only ever played its first video.
    val playerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { playerLaunched = false }
    val launchPlayer: (List<StreamSource>, Episode?, String, Long) -> Boolean = launchPlayer@{ playable, ep, liveId, startPos ->
        if (playerLaunched) return@launchPlayer false
        // Build the payload BEFORE flipping the once-only guard. It used to be
        // the other way round: one malformed source list set `playerLaunched`
        // and then bailed out, so the player never opened AND every later tap
        // was swallowed by the guard — the Play button looked completely dead.
        val payload = playerPayload(playable)
        if (payload == null) return@launchPlayer false
        playerLaunched = true
        // History context rides along so the player can record resume position
        // and remember which server this video was last played with (so a
        // replay continues on that server and starts instantly).
        val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra("title", m?.title ?: title)
                putExtra("sources", payload)
                // Live server feed: playback starts with the first server found
                // while the detail screen keeps searching every installed
                // provider; the player appends them to its "Select server" list.
                putExtra("streamsLiveId", liveId)
                putExtra("histTitle", m?.title ?: title)
                putExtra("histProviderId", providerId)
                putExtra("histMediaId", mediaId)
                putExtra("histType", (m?.type ?: type).name)
                putExtra("histPoster", (m?.posterUrl ?: posterUrl).orEmpty())
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

    val openStreams: (Episode?, Long) -> Unit = { ep, startPos ->
        // Show the "finding server" overlay IMMEDIATELY, then resolve sources in
        // the background and play the first playable server automatically. The
        // source list is deliberately NOT shown up-front.
        selectedEp = ep
        pendingStartPos = startPos
        streams = emptyList()
        loadingStreams = true
        showSheet = false
        preparingSources = true
        // A fresh tap must always be allowed to open the player. If an earlier
        // launch never reported back (activity result lost, process reshuffle),
        // the once-only guard could stay stuck ON and silently swallow every
        // later play — the Play button then looked completely dead.
        playerLaunched = false
        // One live-update session per play tap: the player subscribes to it and
        // keeps receiving servers as slower providers answer, so its "Select
        // server" dialog shows every source from every installed provider.
        sessionId = UUID.randomUUID().toString()
        vm.resetLiveStreams()
        // Local once-only flag: playback launches exactly ONCE per tap (either
        // the feed, the preferred-server grace period, or the final batch) —
        // afterwards new servers are appended to the player's live session,
        // never re-launched.
        var launched = false
        // The server this video was last played with, remembered by the player
        // under the same key as the watch-history entry. When it exists we hold
        // playback until that exact server shows up (up to [PREFERRED_GRACE_MS])
        // instead of jumping onto whichever provider answers first — this is
        // what made a replay always land on "the first server found".
        val historyKey = "${providerId}|${(m?.type ?: type).name}|$mediaId|${ep?.id.orEmpty()}"
        val playableEvery = { list: List<StreamSource> ->
            list.filter { s -> s.ytId == null && !s.externalUrl && (s.url.isNotBlank() || s.isTorrent) }
        }
        scope.launch {
            val last = runCatching { app.store.lastSource(historyKey) }.getOrNull()
            val prefUrl = last?.url.orEmpty()
            val prefName = last?.name.orEmpty()
            val wantPreferred = prefUrl.isNotBlank() || prefName.isNotBlank()
            val preferredIndex = { list: List<StreamSource> ->
                if (prefUrl.isBlank() && prefName.isBlank()) -1
                else list.indexOfFirst { s ->
                    (prefUrl.isNotBlank() && s.url == prefUrl) ||
                        (prefName.isNotBlank() && s.name.equals(prefName, ignoreCase = true))
                }
            }
            // Remembered server first, everything else in arrival order, so the
            // player's own preferredStartIndex() (which matches against the list
            // it was handed) lands on it too.
            val ordered = { list: List<StreamSource> ->
                val i = preferredIndex(list)
                if (i <= 0) list else listOf(list[i]) + list.filterIndexed { idx, _ -> idx != i }
            }
            val startNow = startNow@{
                if (launched || playerLaunched) return@startNow
                val playable = playableEvery(streams)
                if (playable.isEmpty()) return@startNow
                if (launchPlayer(ordered(playable), ep, sessionId, startPos)) {
                    launched = true
                    showSheet = false
                    preparingSources = false
                    loadingStreams = false
                } else {
                    // Player could not be opened (bad payload / launch failure)
                    // — surface the source sheet instead of leaving the user on
                    // a dimmed, dead screen.
                    preparingSources = false
                    loadingStreams = false
                    showSheet = true
                }
            }
            // Live feed: start the instant a playable server appears — unless a
            // preferred server is remembered, in which case keep waiting for it.
            val feed = launch {
                vm.liveStreams.collect { current ->
                    val playable = playableEvery(current)
                    if (playable.isEmpty()) return@collect
                    streams = current
                    // Resolve wrapper URLs ahead of playback so "Select server"
                    // and any failover are instant.
                    StreamProbe.warmAsync(playable)
                    if (launched || playerLaunched) {
                        // Player already up — hand it the newly found servers.
                        StreamsLive.append(sessionId, playable)
                    } else if (!wantPreferred || preferredIndex(playable) >= 0) {
                        startNow()
                    }
                }
            }
            // Give a slow-but-remembered provider a bounded head start, then
            // fall back to whatever has been found so the tap never hangs.
            val grace = launch {
                delay(PREFERRED_GRACE_MS)
                startNow()
            }
            // Safety net: if the search is slow or hung and nothing has launched
            // within [SHEET_FALLBACK_MS], reveal the source sheet so a tap can
            // never dead-end on a dimmed spinner. Auto-play still wins whenever
            // a playable server shows up first (the sheet then just closes).
            val watchdog = launch {
                delay(SHEET_FALLBACK_MS)
                if (!launched && !playerLaunched) {
                    preparingSources = false
                    showSheet = true
                }
            }
            val final = vm.getStreams(ep)
            feed.cancel()
            grace.cancel()
            watchdog.cancel()
            loadingStreams = false
            streams = final
            val playable = playableEvery(final)
            StreamProbe.warmAsync(playable)
            if (launched || playerLaunched) {
                // Search finished — hand the player the complete list.
                preparingSources = false
                StreamsLive.append(sessionId, playable)
            } else if (playable.isNotEmpty()) {
                // Cached/instant result arrived before the feed attached.
                if (launchPlayer(ordered(playable), ep, sessionId, startPos)) {
                    launched = true
                    preparingSources = false
                    showSheet = false
                } else {
                    preparingSources = false
                    showSheet = true
                }
            } else {
                // Nothing playable anywhere — surface the source sheet, with the
                // per-extension diagnostics explaining what failed.
                preparingSources = false
                showSheet = true
            }
        }
    }

    // Saved progress for the given video (movie = null episode), or null when
    // there is nothing worth resuming (never really started / basically done).
    val savedProgressFor: (Episode?) -> Pair<Long, Long>? = { ep ->
        val eid = ep?.id.orEmpty()
        val h = historyForTitle.firstOrNull { it.episodeId == eid }
        var pos = h?.positionMs ?: 0L
        var dur = h?.durationMs ?: 0L
        // Fallback: arrived from History with the position in the nav arg.
        if (h == null && eid == episodeId && startPositionMs > 0L) pos = startPositionMs
        if (pos <= 1_000L) null
        else if (dur > 0L && pos > dur - 10_000L) null
        else pos to dur
    }

    // Tap handler: play immediately; the PLAYER owns the "continue from where
    // you left off?" prompt now (it holds the same history and asks in-video),
    // so a tap never silently resumes and never asks twice. The saved position
    // rides along as a hint for the player's prompt.
    val tryPlay: (Episode?) -> Unit = { ep ->
        val saved = savedProgressFor(ep)
        resumeHint = saved
        openStreams(ep, 0L)
    }

    // Arriving from watch history: once metadata/episodes are loaded, offer to
    // resume the target episode (or the movie) instead of silently jumping in.
    var resumeHandled by remember { mutableStateOf(false) }
    LaunchedEffect(meta, episodes, episodeId, startPositionMs, historyForTitle) {
        if (resumeHandled) return@LaunchedEffect
        if (episodeId.isBlank() && startPositionMs <= 0L) return@LaunchedEffect
        if (meta == null) return@LaunchedEffect
        if (episodeId.isNotBlank()) {
            val eps = episodes ?: return@LaunchedEffect
            val ep = eps.firstOrNull { it.id == episodeId } ?: return@LaunchedEffect
            resumeHandled = true
            tryPlay(ep)
        } else if (episodes.isNullOrEmpty()) {
            resumeHandled = true
            tryPlay(null)
        }
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        // Header renders immediately from the poster we already have, so the hero
        // image shows at once instead of waiting for the slow meta fetch.
        Hero(meta, posterUrl, onBack = { nav.popBackStack() })
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null && meta == null -> Box(Modifier.fillMaxSize()) {
                EmptyState("Something went wrong", error.orEmpty(), "Back", { nav.popBackStack() })
            }
            else -> {
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            m?.title ?: title,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (m?.year != null) {
                            Text(
                                "${m.year}  ·  ${m.type.name.lowercase()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        if (!m?.genres.isNullOrEmpty()) {
                            Row(
                                Modifier.padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                m!!.genres.take(4).forEach { g ->
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                            .clickable { Routes.safeNavigate(nav, Routes.searchQuery(g)) }
                                    ) {
                                        Text(
                                            g,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                        if (!m?.overview.isNullOrBlank()) {
                            var expanded by remember { mutableStateOf(false) }
                            Text(
                                m!!.overview!!,
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
                // Type reporting varies wildly across .cs3 plugins, so only use
                // it as a hint: show the episode list whenever the item is a
                // series OR the provider actually returned episodes, and always
                // give mislabeled/unknown items a Play button so nothing is
                // ever unplayable.
                val isSeries = m?.type == MediaType.SERIES || (episodes?.isNotEmpty() == true)
                // Show the Play button whenever there's no episode list to pick
                // from (a genuine movie, or a series whose provider exposes no
                // episode list) — and ONLY the episode list once episodes exist,
                // even if the provider mislabelled the item as a movie.
                val canPlay = !isSeries || episodes.isNullOrEmpty()
                if (canPlay) {
                    item {
                        Button(
                            onClick = { tryPlay(null) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            // Always "Play": the source search keeps running in
                            // the background (prefetch + live feed) and the
                            // sheet shows its own loader, so the button must
                            // never sit on a "Preparing…" spinner of its own.
                            Text("Play")
                        }
                    }
                }
                if (isSeries) {
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Episodes (${shownEps.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (seasons.size > 1) {
                                    Box {
                                        OutlinedButton(
                                            onClick = { seasonExpanded = true },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                        ) {
                                            Text("Season $activeSeason", maxLines = 1)
                                        }
                                        DropdownMenu(
                                            expanded = seasonExpanded,
                                            onDismissRequest = { seasonExpanded = false }
                                        ) {
                                            seasons.forEach { s ->
                                                DropdownMenuItem(
                                                    text = { Text("Season $s") },
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
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
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
                                                    text = { Text("$start–$end") },
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
                            if (episodesLoading) {
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
                                        "Loading episodes…",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                Text(
                                    "No episode list available.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                        }
                    } else {
                        // key MUST be unique — plugins (MoviesMod, …) emit
                        // duplicate ids/numbers per quality group, and a
                        // duplicate Compose key crashes the whole screen.
                        pageEps.forEachIndexed { index, ep ->
                            item(key = "ep-$index") {
                                EpisodeRow(ep) { tryPlay(ep) }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
        if (preparingSources) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.62f))
                    // Tapping the dim layer reveals the source list, so a server
                    // search that gets stuck can never trap the user on a spinner.
                    .clickable { preparingSources = false; showSheet = true },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        selectedEp?.let { if (it.season > 1) "S${it.season} E${it.number}" else "Episode ${it.number}" }
                            ?: (m?.title ?: title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Finding the best server…",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Tap to show all sources",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.55f)
                    )
                }
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            Text(
                selectedEp?.let { if (it.season > 1) "S${it.season} E${it.number}" else "Episode ${it.number}" }
                    ?: "Playback sources",
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
                streams.isEmpty() && loadingStreams -> Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
                streams.isEmpty() -> Column(
                    Modifier
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "No playable sources found.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (searchedProviders > 0) {
                        Text(
                            "Searched $searchedProviders addon${if (searchedProviders == 1) "" else "s"} for sources.",
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
                            "Per-extension results:",
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
                            "Fetch log:",
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
                }
                else -> Column(Modifier.fillMaxWidth()) {
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
                                        s.isTorrent -> "Torrent — streams from peers"
                                        s.ytId != null -> "YouTube"
                                        s.externalUrl -> "Opens in web view"
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
                                            context.startActivity(
                                                Intent(context, WebViewActivity::class.java).apply {
                                                    putExtra("url", "https://www.youtube.com/watch?v=${s.ytId}")
                                                    putExtra("title", (m?.title ?: title) + " — YouTube")
                                                }
                                            )
                                        }
                                        s.externalUrl -> {
                                            showSheet = false
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
                                            launchPlayer(listOf(s) + others, selectedEp, sessionId, pendingStartPos)
                                        }
                                    }
                                }
                        )
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
                                "Searching for more servers…",
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
                    .put("infoHash", s.infoHash ?: "")
                    .put("fileIdx", s.fileIdx ?: -1)
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
            )
        }
    }.toString()
}.getOrNull()

@Composable
private fun Hero(meta: MediaItem?, fallbackPoster: String?, onBack: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(240.dp)
    ) {
        val img = PosterLoader.model(meta?.backdropUrl ?: fallbackPoster)
        if (img != null) {
            AsyncImage(
                model = img,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, MaterialTheme.colorScheme.background)
                    )
                )
        )
        IconButton(onClick = onBack, modifier = Modifier.padding(4.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White
            )
        }
    }
}

@Composable
private fun EpisodeRow(ep: Episode, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val thumb = PosterLoader.model(ep.image)
        if (thumb != null) {
            AsyncImage(
                model = thumb,
                contentDescription = null,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    ep.number.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            ep.name?.ifBlank { "Episode ${ep.number}" } ?: "Episode ${ep.number}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
    }
}
