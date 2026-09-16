package com.hikari.app.ui.screens
import com.hikari.app.i18n.tr

import android.app.Application
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.hikari.app.HikariApp
import com.hikari.app.data.ContentRepository
import com.hikari.app.data.CastMember
import com.hikari.app.data.Episode
import com.hikari.app.data.HistoryEntry
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.ProviderType
import com.hikari.app.data.StreamCache
import com.hikari.app.data.StreamSource
import com.hikari.app.data.TitleDetails
import com.hikari.app.data.TitleExtras
import com.hikari.app.data.TmdbMeta
import com.hikari.app.data.Trailer
import com.hikari.app.net.StreamProbe
import com.hikari.app.player.PlayerActivity
import com.hikari.app.player.StreamsLive
import com.hikari.app.providers.ContentProvider
import com.hikari.app.ui.Artwork
import com.hikari.app.ui.PosterLoader
import com.hikari.app.ui.openYouTubeVideo
import com.hikari.app.ui.components.EmptyState
import com.hikari.app.ui.components.HeroArtwork
import com.hikari.app.ui.navigation.Routes
import com.hikari.app.web.WebViewActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
     * Finds an installed provider that carries [title], for a page whose origin
     * provider id no longer exists. Local History is checked first (instant, no
     * network), then the installed providers of the same engine (a renamed
     * plugin usually re-registers the same sources), then the rest — bounded to
     * a handful of searches so this can never become a long stall. Returns null
     * when nothing matches, which keeps the old "Provider not found" state.
     */
    private suspend fun remapMissingProvider(missingId: String, title: String): String? {
        if (title.isBlank()) return null
        return withContext(Dispatchers.IO) {
            // 1) Watch history / library: the same title may still be recorded
            //    against a provider that exists (the user opened it there once).
            runCatching {
                val wanted = title.lowercase().trim()
                HikariApp.instance.store.historyFlow().first()
                    .firstOrNull {
                        it.title.lowercase().trim() == wanted &&
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
                val hits = runCatching {
                    withTimeoutOrNull(5_000) { p.search(title, 1) }.orEmpty()
                }.getOrDefault(emptyList())
                for (hit in hits) {
                    val score = titleScoreFor(title, hit.title)
                    if (score >= 55 && (best == null || score > best!!.second)) {
                        best = p.config.id to score
                    }
                }
                if ((best?.second ?: 0) >= 100) break
            }
            val found = best?.first
            if (found != null) {
                com.hikari.app.data.Logs.log(
                    "Detail",
                    "provider $missingId no longer exists — remapped \"$title\" to $found",
                )
            } else {
                com.hikari.app.data.Logs.log(
                    "Detail",
                    "provider $missingId no longer exists and \"$title\" was not found elsewhere",
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
                else -> null
            }
            // A Cloudflare block is the one failure the user can actually fix, so
            // it is never hidden behind a provider's generic "no links" note.
            _streamError.value = originMessage?.takeIf { it.isNotBlank() }
                ?: ContentRepository.crossNote
        } else {
            _streamError.value = null
        }
    }

    fun load(providerId: String, type: MediaType, mediaId: String, title: String, posterUrl: String?, rawType: String) {
        // Keeps the page's meta/episode/source work alive if the user leaves the
        // app (see [com.hikari.app.work.BackgroundWork]) — the process would
        // otherwise be frozen mid-fetch.
        val work = com.hikari.app.work.BackgroundWork.begin("Opening \"${title.take(60)}\"")
        val loadJob = viewModelScope.launch {
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
            val activeProvider = if (manager.byId(providerId) != null) {
                providerId
            } else {
                remapMissingProvider(providerId, title) ?: providerId
            }
            _activeProviderId.value = activeProvider
            if (manager.byId(activeProvider) == null) {
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
            // The catalog row already carries the poster — render the page
            // immediately instead of waiting on the origin's /meta (which may
            // be slow or minimal). rawType keeps the addon's own type string
            // for meta/episode/stream URLs.
            val base = MediaItem(
                activeProvider, mediaId, title, type,
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
                    _episodesLoaded.value = true
                }
            }
            val item = _meta.value ?: base
            // Shelves are a bonus, never a gate: they resolve in the background
            // so a slow (or failed) TMDB call can never delay the page or
            // playback. A miss simply leaves the rows out.
            launch { loadShelves(item) }
            prefetchFirstStreams(item)
        }
        loadJob.invokeOnCompletion { com.hikari.app.work.BackgroundWork.end(work) }
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
            _extras.value = runCatching { TmdbMeta.extras(item) }.getOrNull()
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
    ): List<StreamSource> {
        val key = cacheKey(item, ep)
        val cached = StreamCache.get(key)
        if (cached != null) {
            // Fresh enough to trust: serve it with no network at all (this is
            // what makes a Play tap instant right after the detail page opened).
            // A fresh list is safe to mirror onto the live feed, because those
            // signed links still work.
            val fresh = System.currentTimeMillis() - cached.at < STREAM_CACHE_TTL_MS
            if (!force && (cached.list.isEmpty() || fresh)) {
                com.hikari.app.data.Logs.log(
                    "Search",
                    "cache hit \"${item.title}\" (${if (fresh) "fresh" else "empty"}) " +
                        "→ ${cached.list.size} servers",
                )
                _liveStreams.value = cached.list
                return cached.list
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
            com.hikari.app.data.Logs.log(
                "Search",
                "cache stale/forced \"${item.title}\" — re-extracting",
            )
            if (cached.list.isNotEmpty()) onProgress?.invoke(cached.list)
        }
        // Someone (another instance of this screen for the same title, or a
        // prefetch that is still running) already owns this extraction: join it
        // instead of running the providers a second time.
        StreamCache.joined(key)?.let { return it.await() }
        val deferred = CompletableDeferred<List<StreamSource>>()
        if (!StreamCache.claim(key, deferred)) {
            return StreamCache.joined(key)?.await() ?: emptyList()
        }
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
            if (result.isEmpty() && cached != null && cached.list.isNotEmpty()) {
                // A re-extraction that finds nothing must not downgrade a list
                // we already have into "No playable sources found" — leave the
                // cache (and its old timestamp, so the next attempt tries
                // again) and hand the caller what we have.
                deferred.complete(cached.list)
                return cached.list
            }
            StreamCache.put(key, result)
            _liveStreams.value = result
            recordOutcome(result, item)
            deferred.complete(result)
            return result
        } catch (e: Throwable) {
            deferred.complete(emptyList())
            throw e
        } finally {
            StreamCache.release(key)
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

    private fun cacheKey(item: MediaItem, ep: Episode?): String =
        item.providerId + "|" + item.id + "|" + (ep?.id ?: "")

    suspend fun getStreams(
        episode: Episode?,
        onProgress: (suspend (List<StreamSource>) -> Unit)? = null,
        /** Re-run the providers even if a cached list exists — used when the
         *  player reports that every server it was given is dead. */
        force: Boolean = false,
    ): List<StreamSource> {
        val m = _meta.value ?: return emptyList()
        // The provider scan can run for a minute; keep it alive across a
        // background trip (see [com.hikari.app.work.BackgroundWork]).
        val work = com.hikari.app.work.BackgroundWork.begin(
            "Finding servers for \"${m.title.take(60)}\""
        )
        try {
            return resolveStreams(m, episode, onProgress, force)
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

private fun cfBlockedProviders(): Set<String> {
    val ids = HashSet<String>()
    fun scan(map: Map<String, String>) {
        for ((id, msg) in map) if (msg.contains("cloudflare", ignoreCase = true)) ids.add(id)
    }
    scan(com.hikari.app.cs3.Cs3MainApiProvider.streamErrors)
    scan(com.hikari.app.providers.HikariProviderAdapter.streamErrors)
    scan(com.hikari.app.providers.UniversalScraper.streamErrors)
    scan(com.hikari.app.providers.StremioAddon.streamErrors)
    scan(com.hikari.app.nuvio.NuvioScraper.streamErrors)
    return ids
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

/** How long a prefetched source list may be reused before it must be resolved
 *  again. 4KHDHub/hubcloud hand out SIGNED, time-limited workers.dev links, and
 *  a detail page left open for a few minutes used to replay those dead links on
 *  a Play tap (every server 403s → "No playable sources found"). Five minutes
 *  is comfortably under the rotation window while still making an immediate
 *  Play tap instant. */
private const val STREAM_CACHE_TTL_MS = 300_000L

/** How long a Play tap made while episodes are still loading waits for the
 *  episode list before falling back to a movie-style search. The player is
 *  already open on its title card for the whole wait, so the tap still feels
 *  instant — this only decides which episode the source search runs for. */
private const val EPISODE_WAIT_MS = 25_000L

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
    val related by vm.related.collectAsState()
    val similar by vm.similar.collectAsState()
    val extras by vm.extras.collectAsState()
    val m = meta
    // When the origin provider no longer exists, the ViewModel remaps this page
    // onto a live provider (see remapMissingProvider). Everything that RECORDS
    // or LOOKS UP state by provider must use that live id — the id this page was
    // opened with is precisely the dead one.
    val activeProviderId by vm.activeProviderId.collectAsState()
    val livePid = activeProviderId.ifBlank { providerId }

    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
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
                    val hits = withTimeoutOrNull(15_000) { origin.search(item.title, 1) }.orEmpty()
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
    // Settings that shape the Play tap: whether playback starts on the first
    // server found or waits for a chosen number of them, and whether the
    // full-screen title card covers the player until video is ready.
    val playWaitFlow = remember { app.store.playWaitServersFlow() }
    val playWaitServers by playWaitFlow.collectAsState(initial = false)
    val playMinFlow = remember { app.store.playMinServersFlow() }
    val playMinServers by playMinFlow.collectAsState(initial = 2)
    val bannerFlow = remember { app.store.showLoadingBannerFlow() }
    val showLoadingCoverSetting by bannerFlow.collectAsState(initial = true)
    // "Don't play directly — show all servers to choose": when on, the player
    // opens on its server list (grouped by engine) and never starts a server by
    // itself, so this screen must not hold playback back for a remembered
    // server either — the chooser should come up the moment servers exist.
    val askServerFlow = remember { app.store.askServerOnPlayFlow() }
    val askServerOnPlay by askServerFlow.collectAsState(initial = false)
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

    var playerLaunched by remember { mutableStateOf(false) }
    // Resets the once-only launch guard the moment the player activity returns
    // to this screen — without this, the FIRST play set the flag and every
    // later tap (episode 2..N, another server) was silently swallowed, so a
    // 10-episode melon list only ever played its first video.
    val playerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { playerLaunched = false; showLoadingBanner = false }
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
                // multi-MB base64 string.
                putExtra(
                    "bannerBackdrop",
                    PosterLoader.tokenize(((m?.backdropUrl ?: posterUrl)).orEmpty()).orEmpty()
                )
                putExtra("showLoadingBanner", showLoadingCoverSetting)
                putExtra("startAfterServers", startAfterServers)
                // Ask before playing: the player shows every server it found,
                // grouped by engine, instead of starting one by itself.
                putExtra("askServer", askServerOnPlay)
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
        val mightBeSeries = t == MediaType.SERIES || (eps?.isNotEmpty() == true)
        if (mightBeSeries && eps == null && !vm.episodesLoaded.value) {
            withTimeoutOrNull(EPISODE_WAIT_MS) { vm.episodesLoaded.first { it } }
        }
        vm.episodes.value
            ?.sortedWith(compareBy({ it.season }, { it.number }))
            ?.firstOrNull()
    }

    val openStreams: (Episode?, Long) -> Unit = { ep, startPos ->
        // Open the PLAYER on the very first frame of the tap (Nuvio/Stremio
        // style). The player has its own title-card screen, so instead of the
        // detail page sitting on a spinner for several seconds while the first
        // server is found, the player comes up instantly and starts playback the
        // moment a server lands on the live session. Source resolution keeps
        // running here in the background.
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
        sessionId = UUID.randomUUID().toString()
        vm.resetLiveStreams()
        // Launch the player NOW with an empty source list — it shows its own
        // title card and waits for the first servers on [sessionId]. If the
        // launch itself fails (the activity can't be resolved), the coroutine
        // below falls back to the old "resolve here, then open the player" path
        // and the source sheet.
        launchPlayer(emptyList<StreamSource>(), ep, sessionId, startPos)
        // Local once-only flag: playback launches exactly ONCE per tap (either
        // the feed, the preferred-server grace period, or the final batch) —
        // afterwards new servers are appended to the player's live session,
        // never re-launched. Atomic because this search now runs on the app
        // scope while the screen's own reads happen on the main thread.
        val launched = java.util.concurrent.atomic.AtomicBoolean(false)
        // The coroutine's own copy of everything found so far. Deliberately NOT
        // the Compose state: the search outlives this screen, so it keeps its
        // own list and only mirrors it into [streams] (for the source sheet)
        // while the screen is alive.
        var found: List<StreamSource> = emptyList()
        val playableEvery = { list: List<StreamSource> ->
            list.filter { s ->
                s.ytId == null && !s.externalUrl && (s.url.isNotBlank() || s.isTorrent) &&
                    // Servers behind a Cloudflare "verify you are human" wall are
                    // withheld until that host's clearance cookie exists — i.e.
                    // until the user has actually done the verification for it
                    // (manually in the extension's WebView, or via the automatic
                    // solver). Before, such a server was listed and failed with
                    // "Cloudflare challenge active" the moment it was picked,
                    // while the servers that play sat further down the list.
                    !com.hikari.app.net.CloudflareVerifier.needsVerification(s.url) &&
                    // A provider whose own answer was a Cloudflare block cannot
                    // hand out a playable link either.
                    !(s.providerId.isNotBlank() && s.providerId in cfBlockedProviders())
            }
                // Archive links (.zip/.rar/.7z …) are not videos: providers
                // (4KHDHub's isDirectVideo only checks the hostname, so its
                // ".mkv.zip" hubcloud links leak through) sometimes hand them
                // out, and they cost a full prepare+error cycle before the
                // player falls through. A stable sort keeps arrival order but
                // pushes archives to the back, so they are never server #1.
                .sortedBy { if (!it.isTorrent && StreamProbe.isArchive(it.url)) 1 else 0 }
        }
        // NOTE: application scope, NOT the composition's. See [screenAlive].
        app.appScope.launch {
            try {
                // Live progress for the player's loading cover. The player
                // opened the instant Play was tapped, so this line is the
                // only thing on screen saying that anything is happening at
                // all (and, in the log, the only record of it).
                val searchable = providers.count { it.config.enabled }
                StreamsLive.setStatus(
                    sessionId,
                    "Searching $searchable extension" + (if (searchable == 1) "" else "s") + "…",
                )
                // Which episode the search runs for: the tapped one, or episode 1
                // when the origin addon was still listing episodes. The player is
                // already open on its title card during this wait, so it opens and
                // starts playing the instant episode 1 resolves.
                val epForSearch: Episode? = ep ?: firstEpisodeOrNull()
                if (ep == null && epForSearch != null) {
                    onUi { selectedEp = epForSearch }
                    // Hand the already-open player the episode it ended up on, so
                    // its title card, resume key and watch history are per-episode
                    // rather than the movie-level entry.
                    StreamsLive.setEpisode(sessionId, epForSearch)
                }
                // The server this video was last played with, remembered by the
                // player under the same key as the watch-history entry. When it
                // exists we hold playback until that exact server shows up (up to
                // [PREFERRED_GRACE_MS]) instead of jumping onto whichever provider
                // answers first.
                val historyKey = "${livePid}|${(vm.meta.value ?: m)?.type?.name ?: type.name}|$mediaId|${epForSearch?.id.orEmpty()}"
                val last = runCatching { app.store.lastSource(historyKey) }.getOrNull()
                val prefUrl = last?.url.orEmpty()
                val prefName = last?.name.orEmpty()
                // Only when the user asked to WAIT for more servers. "Play as
                // soon as the first server is found" means exactly that: nothing
                // — not even the server this title was last played with — may
                // hold playback back. (Before, a remembered server suppressed
                // the first-server start even with that choice selected, so the
                // fastest option could still sit on "Finding the best server…"
                // until every extension had finished answering.)
                var wantPreferred =
                    !askServerOnPlay && playWaitServers &&
                        (prefUrl.isNotBlank() || prefName.isNotBlank())
                if (wantPreferred) {
                    StreamsLive.setStatus(
                        sessionId,
                        "Waiting for your last used server (up to " +
                            (PREFERRED_GRACE_MS / 1000) + "s)…",
                    )
                }
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
                // Live re-extraction. A play session can have all of its servers
                // die at once: 4KHDHub/hubcloud's signed workers.dev links expire,
                // and the mirror that served them can go away. The player (still
                // attached via [sessionId]) then requests fresh sources by bumping
                // the session's refresh counter instead of replaying a dead link
                // forever — we re-run the providers ignoring the cache and stream
                // the new servers straight to the player, which retries with them.
                var lastRefresh = StreamsLive.refreshFlow(sessionId).value
                launch {
                    StreamsLive.refreshFlow(sessionId).collect { n ->
                        if (n == lastRefresh) return@collect
                        lastRefresh = n
                        StreamsLive.setStatus(sessionId, "Re-extracting expired links…")
                        val fresh = vm.getStreams(epForSearch, force = true)
                        if (fresh.isNotEmpty()) {
                            found = fresh
                            onUi { streams = fresh }
                            val freshPlayable = playableEvery(fresh)
                            StreamProbe.warmAsync(freshPlayable)
                            StreamsLive.setStatus(
                                sessionId,
                                "Found " + freshPlayable.size + " fresh server" +
                                    (if (freshPlayable.size == 1) "" else "s") + " — retrying…",
                            )
                            StreamsLive.append(sessionId, freshPlayable)
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
                        started = launchPlayer(ordered(playable), epForSearch, sessionId, startPos)
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
                        StreamsLive.append(sessionId, playableEvery(found))
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
                            sessionId,
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
                    // The head start is over: stop holding out for the remembered
                    // server. Servers that answer after this moment then start
                    // playback at once, instead of waiting for the whole search
                    // to end (which is what made the cover sit on "Finding the
                    // best server…" for a slow provider).
                    wantPreferred = false
                    startNow()
                }
                val final = vm.getStreams(epForSearch)
                feed.cancel()
                grace.cancel()
                found = final
                onUi {
                    loadingStreams = false
                    streams = final
                }
                val playable = playableEvery(final)
                StreamProbe.warmAsync(playable)
                if (launched.get() || playerLaunched) {
                    // Player is up (or already was) — close the sheet and hand it the
                    // complete list.
                    onUi { showSheet = false }
                    StreamsLive.append(sessionId, playable)
                } else if (playable.isNotEmpty()) {
                    // Cached/instant result arrived before the feed attached.
                    var started = false
                    onUi {
                        if (!launched.get()) {
                            started = launchPlayer(ordered(playable), epForSearch, sessionId, startPos)
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
                        if (!screenAlive.get()) StreamsLive.append(sessionId, playable)
                    }
                } else {
                    // Nothing playable anywhere — keep the source sheet up, with the
                    // per-extension diagnostics explaining what failed.
                    onUi {
                        showLoadingBanner = false
                        showSheet = true
                    }
                }
                // The whole source search is over. The player (which opened the
                // moment Play was tapped) uses this to fail fast when nothing was
                // found, instead of waiting out its safety timeout.
            } catch (t: Throwable) {
                // A throw here (a provider blowing up, a cancelled child
                // collector) used to skip markDone entirely, so the player
                // kept spinning on an empty session until its 90s safety
                // timeout. Say what happened instead.
                StreamsLive.setStatus(
                    sessionId,
                    "The search stopped early (" + t.javaClass.simpleName + ").",
                )
            } finally {
                // ALWAYS declare the search over. The player only leaves its
                // "Finding the best server…" cover when a server arrives or
                // the search is declared finished, so this is what turns an
                // empty result into a clear message within a second instead
                // of a minute and a half of nothing.
                val foundCount = playableEvery(found).size
                if (foundCount == 0) {
                    val enabledN = providers.count { it.config.enabled }
                    val installedN = providers.size
                    val reason = vm.streamError.value?.takeIf { it.isNotBlank() }
                        ?: com.hikari.app.data.ContentRepository.crossNote?.takeIf { it.isNotBlank() }
                    val note = buildString {
                        append("No playable server found after searching $enabledN ")
                        append(if (enabledN == 1) "extension" else "extensions")
                        if (enabledN < installedN) {
                            append(" — only $enabledN of your $installedN installed extensions are enabled")
                        }
                        if (!reason.isNullOrBlank()) append("\n" + reason)
                    }
                    StreamsLive.setStatus(sessionId, note)
                }
                StreamsLive.markDone(sessionId)
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

    // What the primary action button plays: the first episode with progress
    // worth continuing (the visible page first, then the rest of the season),
    // so a returning viewer gets a "Resume S1 E3" button instead of having to
    // remember where they stopped. Null = nothing to resume.
    val resumeEp = remember(shownEps, sortedEps, historyForTitle, episodeId) {
        shownEps.firstOrNull { savedProgressFor(it) != null }
            ?: sortedEps.firstOrNull { savedProgressFor(it) != null }
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
    val isSaved = favorites.any { it.uniqueId == savedItem.uniqueId }
    val toggleSaved: () -> Unit = {
        scope.launch {
            if (isSaved) app.store.removeFavorite(savedItem.uniqueId)
            else app.store.addFavorite(savedItem)
        }
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
                                                g,
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
                // A movie (or a series whose provider exposes no episode list)
                // plays straight from this button. A real series gets the SAME
                // button, pointed at the episode the viewer is up to, so a
                // returning viewer never has to hunt through the list — while
                // the episode rows below still allow picking any other one.
                val canPlay = !isSeries || episodes.isNullOrEmpty()
                val btnEp = if (canPlay) null else (resumeEp ?: sortedEps.firstOrNull())
                val actionLabel = when {
                    resumeEp != null ->
                        if (resumeEp.season > 1) "Resume S${resumeEp.season} E${resumeEp.number}"
                        else "Resume E${resumeEp.number}"
                    btnEp == null -> "Play"
                    btnEp.season > 1 -> "Play S${btnEp.season} E${btnEp.number}"
                    else -> "Play E${btnEp.number}"
                }
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { tryPlay(btnEp) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            // Always an action word, never a spinner: the
                            // source search keeps running in the background
                            // (prefetch + live feed) and the sheet shows its
                            // own loader, so the button must never sit on a
                            // "Preparing…" spinner of its own.
                            Text(actionLabel)
                        }
                        // Library toggle, mirroring the player's heart: the same
                        // MediaItem and the same store calls, so the two views
                        // can never disagree about what is saved.
                        if (isSaved) {
                            FilledTonalButton(onClick = toggleSaved) {
                                Icon(
                                    Icons.Filled.Favorite,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(tr("Saved"))
                            }
                        } else {
                            OutlinedButton(onClick = toggleSaved) {
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
                // "Show Details" block (Nuvio/Stremio style): the stat line
                // (year · runtime · certification · rating) plus status/country/
                // language and the director/writer credits. Renders only once
                // the background TMDB lookup has landed.
                extras?.details?.let { det ->
                    item { DetailsBlock(det) }
                }
                // Cast + Trailers sit ABOVE the episode list — the order the
                // Nuvio/Stremio detail page uses. Below it they were buried under
                // a 30-episode season (or below the fold of a long overview) and
                // read as "the sections are missing". Both come from the same
                // background TMDB call, and each row is skipped entirely when
                // that lookup found nothing.
                extras?.cast?.takeIf { it.isNotEmpty() }?.let { cast ->
                    item {
                        CastRow(cast) { member ->
                            Routes.safeNavigate(nav, Routes.searchQuery(member.name))
                        }
                    }
                }
                extras?.trailers?.takeIf { it.isNotEmpty() }?.let { trailers ->
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
                                        tr("Loading episodes…"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                Text(
                                    tr("No episode list available."),
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
                // Same-title shelves from TMDB — the Nuvio detail page's
                // Related/Similar tabs, as inline rows. They show up only once
                // the background lookup lands, and only when it found titles
                // that actually have artwork, so a miss leaves no empty row.
                if (related.isNotEmpty()) {
                    item {
                        ShelfRow(
                            heading = tr("Related"),
                            shelf = related,
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

    if (showLoadingBanner) {
        PlayLoadingBanner(
            title = m?.title ?: title,
            episodeLabel = selectedEp?.let {
                if (it.season > 1) "S${it.season} E${it.number}" else "Episode ${it.number}"
            },
            detail = selectedEp?.name?.takeIf { it.isNotBlank() },
            image = (m?.backdropUrl?.takeIf { it.isNotBlank() }) ?: posterUrl
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
                    "Opening $opening…",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp, start = 24.dp, end = 24.dp)
                )
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
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val model = PosterLoader.model(image?.takeIf { it.isNotBlank() })
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val s = 1f + drift * 0.12f
                        scaleX = s
                        scaleY = s
                        alpha = 0.62f
                    }
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xE6000000), Color(0x40000000), Color(0xE6000000))
                    )
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
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            if (!episodeLabel.isNullOrBlank()) {
                Text(
                    episodeLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFFF5C569),
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
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 3.dp,
                color = Color(0xFFF5C569)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                tr("Finding the best server…"),
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xCCFFFFFF)
            )
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
                    .put("provider", s.provider)
                    // Which PROVIDER (repo plugin) produced this server, as
                    // opposed to which engine: the player gives the provider the
                    // user opened this title from its own section at the top of
                    // "Select server", and starts playback on its server.
                    .put("providerId", s.providerId)
                    .put("providerName", s.providerName)
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

@Composable
private fun Hero(meta: MediaItem?, fallbackPoster: String?, onBack: () -> Unit) {
    // A wide 16:9 banner — the same shape as the Home carousel and the Nuvio
    // detail page — instead of the old 240dp letterbox, which cropped the sides
    // off wide art and showed a blurry poster strip instead. With a full
    // 16:9 frame nothing is cut off at the top, and the bottom of the art fades
    // into the page background.
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
    ) {
        // Item's own backdrop → the wide art we looked up → its poster, so a
        // title an extension left blank still gets a real banner here. The
        // wide/poster distinction matters: a portrait poster is never
        // centre-cropped into this 16:9 frame (that is what cut the art off).
        val (img, wide) = meta?.let { Artwork.heroModel(it) }
            ?: (PosterLoader.model(fallbackPoster) to false)
        HeroArtwork(
            model = img,
            wide = wide,
            modifier = Modifier.fillMaxSize(),
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

/** A horizontal "Related"/"Similar" shelf of poster cells under the detail
 *  page's episode list (the Nuvio detail page's Related/Similar tabs, inline). */
@Composable
private fun ShelfRow(
    heading: String,
    shelf: List<MediaItem>,
    onClick: (MediaItem) -> Unit,
    onSearchHere: (MediaItem) -> Unit,
    onGlobalSearch: (MediaItem) -> Unit,
) {
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
            items(shelf, key = { it.uniqueId }) { item ->
                Column(Modifier.width(112.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(2f / 3f)
                    ) {
                        // Tapping the poster loads the title itself (no trip
                        // through the Search tab).
                        Box(
                            Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { onClick(item) }
                        ) {
                            AsyncImage(
                                model = Artwork.model(item),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                        // The kebab in the corner carries the two ways to
                        // search instead of open: this extension only, or every
                        // installed extension. Same pair as the genre pills.
                        var menuOpen by remember(item.uniqueId) { mutableStateOf(false) }
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
                                        onSearchHere(item)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(tr("Global search")) },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Public, contentDescription = null)
                                    },
                                    onClick = {
                                        menuOpen = false
                                        onGlobalSearch(item)
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
        }
    }
}

/** The "Show Details" block: year/runtime/certification/rating, then
 *  status/country/language, then the director/writer credits — the metadata
 *  Nuvio and Stremio show above their Cast row. Each line is skipped when the
 *  lookup had nothing for it, so a sparse TMDB record still renders cleanly. */
@Composable
private fun DetailsBlock(d: TitleDetails) {
    val stats = ArrayList<String>(5)
    d.year?.let { stats.add(it.toString()) }
    d.runtimeMinutes?.let { minutes ->
        val h = minutes / 60
        val mm = minutes % 60
        stats.add(if (h > 0) "${h}h ${mm}m" else "${mm}m")
    }
    d.certification?.let { stats.add(it) }
    d.rating?.takeIf { it > 0.0 }?.let { stats.add("★ " + (Math.round(it * 10) / 10.0)) }

    val meta = ArrayList<String>(4)
    d.status?.let { meta.add(it) }
    d.country?.let { meta.add(it) }
    d.language?.let { meta.add(it) }
    d.voteCount?.takeIf { it > 0 }?.let { meta.add("$it votes") }

    // A TMDB record with nothing usable would otherwise render an empty block.
    if (stats.isEmpty() && meta.isEmpty() && d.director.isNullOrBlank() && d.writers.isEmpty()) return

    Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
        if (stats.isNotEmpty()) {
            Text(
                stats.joinToString("  ·  "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (meta.isNotEmpty()) {
            Text(
                meta.joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (!d.director.isNullOrBlank()) {
            Text(
                "Director: ${d.director}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        if (d.writers.isNotEmpty()) {
            Text(
                "Writer: ${d.writers.joinToString(", ")}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/** Circular-headshot Cast row, matching the Nuvio/Stremio detail page. Tapping
 *  an actor runs a global search for their name — there is no person page in
 *  Hikari, and a search is the closest useful action. */
@Composable
private fun CastRow(cast: List<CastMember>, onClick: (CastMember) -> Unit) {
    Column(Modifier.padding(top = 14.dp)) {
        Text(
            tr("Cast"),
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
                    c.character?.let {
                        Text(
                            it,
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
