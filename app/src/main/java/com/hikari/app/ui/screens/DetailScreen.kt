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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.hikari.app.HikariApp
import com.hikari.app.R
import com.hikari.app.data.ContentRepository
import com.hikari.app.data.ContentRepository.StreamLookup
import com.hikari.app.data.CastMember
import com.hikari.app.data.CompanyRef
import com.hikari.app.data.Episode
import com.hikari.app.data.HistoryEntry
import com.hikari.app.data.LibraryCategory
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
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
import com.hikari.app.data.Translator
import com.hikari.app.data.Trailer
import com.hikari.app.net.StreamProbe
import com.hikari.app.player.PlayerActivity
import com.hikari.app.player.StreamsLive
import com.hikari.app.providers.ContentProvider
import com.hikari.app.ui.Artwork
import com.hikari.app.ui.LoadingStyles
import com.hikari.app.ui.PosterArt
import com.hikari.app.ui.PosterLoader
import com.hikari.app.ui.openYouTubeVideo
import com.hikari.app.ui.rememberPosterScore
import com.hikari.app.ui.rememberPosterStyle
import com.hikari.app.ui.components.EmptyState
import com.hikari.app.ui.components.CategoryPickerSheet
import com.hikari.app.ui.components.GlassShape
import com.hikari.app.ui.components.HeroArtwork
import com.hikari.app.ui.navigation.Routes
import com.hikari.app.web.WebViewActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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

    /** True when the episode lookup came back empty even after every retry —
     *  i.e. the extension could not answer, rather than "this show has no
     *  episodes". The page shows a real, tappable "couldn't load" line for that
     *  instead of the flat "No episode list available.", which is what made a
     *  cold plugin look like an empty series (see [loadEpisodesFor]). */
    private val _episodesFailed = MutableStateFlow(false)
    val episodesFailed: StateFlow<Boolean> = _episodesFailed.asStateFlow()

    /** The page's own "retry the episode list" tap (see [retryEpisodes]). */
    private var episodeRetryJob: Job? = null

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
            val activeProvider = if (manager.byId(providerId) != null) {
                providerId
            } else {
                remapMissingProvider(providerId, lookupNames) ?: providerId
            }
            _activeProviderId.value = activeProvider
            if (manager.byId(activeProvider) == null && !isTmdbRow) {
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
            // Movies: start the multi-provider source search NOW — before the
            // origin's /meta and episode fetches — so the first server is
            // already resolving while the page renders. Previously the search
            // only began after meta+episodes landed, which is why tapping Play
            // sat on a spinner while the (slow) providers were still warming up.
            if (type != MediaType.SERIES) {
                launch { prefetchFirstStreams(base) }
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
            withContext(Dispatchers.IO) {
                // The origin's own meta corrects the item's TYPE before the
                // episode list is asked for — CS3 plugins can label a series page
                // as a movie on their search results (LeakPorner actors are
                // NSFW→MOVIE), and getMeta corrects it from the LoadResponse, so
                // episodes must be fetched against the CORRECTED item
                // (loadResponse is cached, so this stays a single origin fetch).
                // The fetch itself was started above, together with the shelves.
                val meta = metaDeferred.await()
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
                _episodesLoading.value = true
                val gen = newEpisodeGeneration()
                try {
                    // Retried, never silently final — see [loadEpisodesFor]. This
                    // used to be a bare `runCatching { repo.episodesFor(meta) }`
                    // whose null (a failed load, or a CANCELLED one, since
                    // runCatching swallows CancellationException too) painted
                    // "Episodes (0) — No episode list available." over a series
                    // that has plenty of episodes — and that verdict stayed for
                    // the life of the screen.
                    val list = loadEpisodesFor(meta, gen)
                    if (gen == episodeGeneration) _episodes.value = list
                } finally {
                    if (gen == episodeGeneration) {
                        _episodesLoading.value = false
                        _episodesLoaded.value = true
                    }
                }
            }
            val item = _meta.value ?: base
            // (The shelves — ratings, cast, trailers, Related/Similar — were
            // started above, in parallel with the episode list: they are a bonus
            // that must never gate the page, but they must also never wait for
            // the episodes to finish before they begin.)
            prefetchFirstStreams(item)
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
        val tries = if (item.type == MediaType.SERIES) EPISODE_LOAD_TRIES else 1
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
        val item = _meta.value ?: return
        episodeRetryJob?.cancel()
        episodeRetryJob = viewModelScope.launch {
            _episodesLoading.value = true
            val gen = newEpisodeGeneration()
            try {
                val list = loadEpisodesFor(item, gen)
                if (gen == episodeGeneration) _episodes.value = list
            } finally {
                if (gen == episodeGeneration) {
                    _episodesLoading.value = false
                    _episodesLoaded.value = true
                }
            }
        }
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
         *  ContentRepository.streamsForInner) — the player is holding its
         *  auto-start until then, so this is what releases it early instead of
         *  making the user watch the whole grace window. */
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
                com.hikari.app.data.Logs.log(
                    "Search",
                    "cache hit \"${item.title}\" (fresh) → ${cached.list.size} servers",
                )
                _liveStreams.value = cached.list
                return StreamLookup(cached.list, complete = true)
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
        StreamCache.joined(key)?.let { pending ->
            return withTimeoutOrNull(JOIN_WAIT_MS) { pending.await() }
                ?: StreamLookup(emptyList(), complete = false)
        }
        val deferred = CompletableDeferred<StreamLookup>()
        if (!StreamCache.claim(key, deferred)) {
            val pending = StreamCache.joined(key)
                ?: return StreamLookup(emptyList(), complete = false)
            return withTimeoutOrNull(JOIN_WAIT_MS) { pending.await() }
                ?: StreamLookup(emptyList(), complete = false)
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
                val partial = cached?.list?.takeIf { it.isNotEmpty() }
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
                val fallback = cached?.list?.takeIf { it.isNotEmpty() }
                    ?: _liveStreams.value.takeIf { it.isNotEmpty() }
                if (fallback != null) {
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
        else -> null
    }
    return msg?.takeIf { !com.hikari.app.net.CloudflareVerifier.isVerificationMessage(it) }
        ?.let { "$name: $it" }
}

/** How long a replay waits for the server it was last played with to appear in
 *  the multi-provider source search before falling back to the first server
 *  found. Long enough for a slower provider to answer, short enough that a tap
 *  never appears to hang. */
private const val PREFERRED_GRACE_MS = 10_000L

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
 *  way to re-run every extension in a loop. */
private const val STREAMS_FINAL_RETRIES = 3

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

/** The longest the player will hold playback waiting for the extension the
 *  title was opened FROM, before it takes whichever other extension answered
 *  first. Passed to the player as the `originGraceMs` extra, and set to 0 when
 *  the origin is disabled or gone, so a dead extension can never cost a wait.
 *
 *  This used to be a 1.2s nudge, on the theory that the user's rule is "start
 *  playing the instant ANY server is found". It is a real hold now, because
 *  that theory produced the wrong video: a title opened inside a Chinese .hiki
 *  repo had its own link still cold-loading, an adult tube repo that echoes the
 *  search text back into its page titles answered in a second, and playback
 *  started on THAT — "it selected XFree and played the wrong video instead of
 *  the MRDS server, and the MRDS server never even showed up". Waiting for the
 *  provider the user actually tapped is worth seconds; a wrong video is worth
 *  nothing.
 *
 *  What makes this bearable is that the number is only the BACKSTOP: the pass
 *  signals the moment the origin has answered — with servers or with nothing —
 *  and the player releases the hold right then (StreamsLive.settleOrigin). So a
 *  warm origin costs ~0s, a cold one costs its cold start, and only a provider
 *  that never comes back at all costs the full 45s. */
private const val ORIGIN_PLAY_GRACE_MS = 45_000L

/**
 * How long playback waits for the title's own extension when the user chose
 * "play as soon as the first server is found" (the default) and servers are
 * ALREADY available from somewhere else.
 *
 * This is the fix for "it had 70 servers and was still on the searching screen":
 * the origin's own link is worth a moment — it is the extension the user opened
 * the title from, and by then its runtime is warm from browsing — but it is not
 * worth a server list sitting in hand. Three seconds is long enough for a warm
 * repo to answer and short enough that the video starts while the rest of the
 * search continues in the background (its finds still stream into "Select
 * server", and an origin server that lands later is still moved to the top of
 * the list).
 */
private const val ORIGIN_HEAD_START_MS = 3_000L

/**
 * The BACKSTOP for that case: how long the origin is given in total for "play as
 * soon as the first server is found" when no other server has arrived either.
 * With nothing to play there is nothing to start, so this window costs the user
 * nothing at all — it only decides when the player stops waiting for the origin
 * and says so.
 */
private const val ORIGIN_INSTANT_GRACE_MS = 20_000L

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
    // True once the episode lookup has FINISHED (success or failure). The page
    // keeps its spinner until then, so a lookup that is still running can never
    // be painted as "this series has no episodes".
    val episodesLoaded by vm.episodesLoaded.collectAsState()
    // True when the episode lookup failed outright (every retry came back
    // empty) — the page then says "couldn't load" and offers a retry instead of
    // claiming the series has no episodes (see DetailViewModel.loadEpisodesFor).
    val episodesFailed by vm.episodesFailed.collectAsState()
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
    // Look of the "finding your server" card — the same choice the player's own
    // cover wears, handed over as an intent extra so the two screens cannot
    // disagree (see [LoadingStyles] and PlayerActivity.showLoadingBanner).
    val loadingStyleFlow = remember { app.store.loadingStyleFlow() }
    val loadingStyleSetting by loadingStyleFlow.collectAsState(initial = LoadingStyles.CINEMATIC)
    // The treatment drawn over that card — handed over the same way, so the
    // detail page's cover and the player's cover stay identical through the
    // hand-off (see [com.hikari.app.ui.LoadingEffects]).
    val loadingEffectsFlow = remember { app.store.loadingEffectsFlow() }
    val loadingEffectsSetting by loadingEffectsFlow
        .collectAsState(initial = setOf(com.hikari.app.ui.LoadingEffects.SHEEN))
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
    // The Library's categories themselves, and which ones this title is filed
    // under. Both are flows, so a category invented from inside the sheet — or
    // a filing changed on the Library screen — is reflected here at once.
    val categoriesFlow = remember { app.store.libraryCategoriesFlow() }
    val categories by categoriesFlow.collectAsState(initial = LibraryCategory.DEFAULTS)
    val filingsFlow = remember { app.store.favoriteCategoriesFlow() }
    val filings by filingsFlow.collectAsState(initial = emptyMap())
    var librarySheet by remember { mutableStateOf(false) }

    var playerLaunched by remember { mutableStateOf(false) }
    // Resets the once-only launch guard the moment the player activity returns
    // to this screen — without this, the FIRST play set the flag and every
    // later tap (episode 2..N, another server) was silently swallowed, so a
    // 10-episode melon list only ever played its first video.
    val playerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { playerLaunched = false; showLoadingBanner = false }
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
                // multi-MB base64 string.
                putExtra(
                    "bannerBackdrop",
                    PosterLoader.tokenize(((m?.backdropUrl ?: posterUrl)).orEmpty()).orEmpty()
                )
                putExtra("showLoadingBanner", showLoadingCoverSetting)
                putExtra("loadingStyle", loadingStyleSetting)
                putExtra("loadingEffect", com.hikari.app.ui.LoadingEffects.encode(loadingEffectsSetting))
                // The aura ring's colour, resolved to the same ARGB this page
                // draws it in, so the player's cover continues the exact picture
                // the detail page put up (see loadingAuraArgb above).
                putExtra("loadingAuraColor", loadingAuraArgb)
                putExtra("startAfterServers", startAfterServers)
                // Ask before playing: the player shows every server it found,
                // grouped by engine, instead of starting one by itself.
                putExtra("askServer", askServerOnPlay)
                // "Your own extension goes first": the player holds the first
                // start until the extension this title was opened from has
                // answered (see ORIGIN_PLAY_GRACE_MS for the full reasoning), so
                // the link the user actually asked for is the link that plays.
                // 0 when that extension is disabled or uninstalled — it is not
                // in [streamTargets], so there would be nothing to wait for.
                //
                // HOW LONG that hold lasts depends on the choice the user made,
                // and that is the fix for "it had 70 servers and still sat on the
                // loading screen": with "play as soon as the first server is
                // found" (the default) the hold is only a HEAD START — a few
                // seconds for the origin's already-warm runtime to answer — and
                // the moment any playable server is in hand the video starts,
                // while the origin keeps working in the background and its
                // servers still land at the top of the list. The long backstop
                // is for "wait for more servers first", where the user has asked
                // for exactly that patience.
                val originSearched =
                    providers.firstOrNull { it.config.id == livePid }?.config?.enabled == true
                putExtra(
                    "originGraceMs",
                    if (!originSearched) 0
                    else if (playWaitServers) ORIGIN_PLAY_GRACE_MS.toInt()
                    // Short: the head start below is what actually ends the
                    // hold. This is only the "the origin never answers at all"
                    // backstop, so it can be generous without costing the user
                    // anything.
                    else ORIGIN_INSTANT_GRACE_MS.toInt()
                )
                // The head start itself: how long playback will wait for the
                // origin once servers are ALREADY available. 0 with "wait for
                // more servers first" (that path uses the full grace window).
                putExtra(
                    "originHeadStartMs",
                    if (originSearched && !playWaitServers) ORIGIN_HEAD_START_MS.toInt() else 0
                )
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
        val mightBeSeries = t == MediaType.SERIES || (eps?.isNotEmpty() == true)
        if (mightBeSeries && eps == null && !vm.episodesLoaded.value) {
            withTimeoutOrNull(EPISODE_WAIT_MS) { vm.episodesLoaded.first { it } }
        }
        vm.episodes.value
            ?.sortedWith(compareBy({ it.season }, { it.number }))
            ?.firstOrNull()
    }

    val openStreams: (Episode?, Long, Boolean) -> Unit = { ep, startPos, wantsDownload ->
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
        // the immediate launch below — or later from the feed, the
        // preferred-server grace period, or the final batch); afterwards new
        // servers are appended to the player's live session, never re-launched.
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
                s.ytId == null && !s.externalUrl && (s.url.isNotBlank() || s.isTorrent)
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
                        exceptionN > 0 ->
                            "Searching your extension + $exceptionN more…"
                        !com.hikari.app.data.SearchScope.allExtensions ->
                            "Searching your extension for servers…"
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
                        sid,
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
                grace.cancel()
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
                val foundCount = playableEvery(found).size
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
                // The "nothing playable, and that is a real answer" note, built as
                // a lambda so the sweep's own watcher below can use the very same
                // wording if the sweep comes back empty a minute later.
                val noResultNote = {
                    // Truly scoped only when "only this extension" is on AND no
                    // exception repos are being searched (see [SearchScope]).
                    val scoped = !com.hikari.app.data.SearchScope.allExtensions &&
                        com.hikari.app.data.SearchScope.exceptions.isEmpty()
                    val enabledN = providers.count { it.config.enabled }
                    val installedN = providers.size
                    val reason = vm.streamError.value?.takeIf { it.isNotBlank() }
                    val note = buildString {
                        if (scoped) {
                            // "Only this extension" is on, so the lookup's verdict
                            // is about ONE repo — say that, and say why it might
                            // be empty when the user knows other extensions have
                            // the title.
                            append("No playable server found in the extension this title came from")
                        } else {
                            append("No playable server found after searching $enabledN ")
                            append(if (enabledN == 1) "extension" else "extensions")
                        }
                        // Only worth saying when a real number of extensions is
                        // switched off — "only 256 of your 257" is noise, and it
                        // made a normal empty result read like a configuration
                        // problem.
                        if (!scoped && installedN - enabledN >= 5) {
                            append(" — ${installedN - enabledN} of your installed extensions are turned off")
                        }
                        if (scoped) {
                            append(
                                "\nOnly the extension this title came from is searched " +
                                    "(Settings → Playback & Servers → Server search)."
                            )
                        }
                        // Across the whole pass: how many extensions were asked,
                        // how many answered with servers, and why the rest came
                        // back empty. This is what separates "no extension has
                        // this title" from "most of them could not load".
                        // Extensions behind a verification wall are left out of
                        // the pass (and of this count) entirely, so no host name
                        // and no Cloudflare wording ever appears here.
                        if (!scoped) {
                            com.hikari.app.data.ContentRepository.crossSummary()?.let {
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
        openStreams(ep, 0L, false)
    }

    // The download buttons (the play row and every episode row). Identical to
    // [tryPlay] apart from the flag the player needs: it resolves servers and
    // opens the player the same way, and the player shows its own download
    // chooser the moment a server is ready — so "download episode 7" reaches
    // exactly the same code path as "play episode 7, then tap Download".
    val tryDownload: (Episode?) -> Unit = { ep ->
        resumeHint = savedProgressFor(ep)
        openStreams(ep, 0L, true)
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

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        // Header renders immediately from the poster we already have, so the hero
        // image shows at once instead of waiting for the slow meta fetch.
        Hero(meta, posterUrl, onBack = { nav.popBackStack() }, style = detailHeroStyle)
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null && meta == null -> Box(Modifier.fillMaxSize()) {
                EmptyState(tr("Something went wrong"), error.orEmpty(), tr("Back"), { nav.popBackStack() })
            }
            else -> {
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                    // The first line of the page is spaced off the header art on
                    // purpose. It used to start flush against the artwork's
                    // bottom edge (the banner's gradient ends exactly where the
                    // title begins, so the two read as one block and the title
                    // looked like it had been pushed up into the header — the
                    // user circled it on a screenshot). Styles that fade the art
                    // into the background need the gap most.
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
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
                        I18n.t("Resume") + if (resumeEp.season > 1)
                            " S${resumeEp.season} E${resumeEp.number}" else " E${resumeEp.number}"
                    btnEp == null -> I18n.t("Play")
                    btnEp.season > 1 -> I18n.t("Play") + " S${btnEp.season} E${btnEp.number}"
                    else -> I18n.t("Play") + " E${btnEp.number}"
                }
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
                                .focusRequester(playFocus)
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
                        // Download without watching first: opens the player on
                        // this episode and puts its download chooser up as soon
                        // as a server is ready (see launchPlayer's
                        // `openDownload`). The episode rows below offer the same
                        // per episode; this one also covers a movie, whose only
                        // affordance is this row.
                        FilledTonalButton(onClick = { tryDownload(btnEp) }) {
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
                // "Show Details" block (Nuvio/Stremio style): the stat line
                // (year · runtime · certification · rating) plus status/country/
                // language and the director/writer credits. Renders only once
                // the background TMDB lookup has landed.
                extras?.details?.let { det ->
                    item {
                        DetailsBlock(
                            d = det,
                            ratings = if (showDetailRating) ratings else emptyList(),
                            // Even with the strip hidden the block's own rows
                            // (year, certification, director) still render, so
                            // the callback stays wired for when it comes back.
                            onRatingClick = { ratingInfo = it },
                        )
                    }
                }
                // Production companies / networks — one tap opens everything
                // that studio or network made (a TMDB entity grid; see the
                // TmdbSourceType.COMPANY/NETWORK queries). It sits ABOVE the cast
                // row, the order the reference client uses.
                extras?.companies?.takeIf { it.isNotEmpty() }?.let { companies ->
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
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        pageEps.forEachIndexed { index, ep ->
                            item(key = "ep-$index") {
                                EpisodeRow(
                                    ep,
                                    onClick = { tryPlay(ep) },
                                    onDownload = { tryDownload(ep) },
                                )
                            }
                        }
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
            title = artTitle,
            episodeLabel = selectedEp?.let {
                if (it.season > 1) "S${it.season} E${it.number}"
                else tr("Episode %s").replace("%s", it.number.toString())
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
                                            launchPlayer(listOf(s) + others, selectedEp, sessionId, pendingStartPos, false)
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
    val style = rememberLoadingStyle()
    val effects = rememberLoadingEffect()
    when (LoadingStyles.normalize(style)) {
        LoadingStyles.MINIMAL -> MinimalLoadingCard(title, episodeLabel, detail, effects)
        LoadingStyles.SPOTLIGHT -> SpotlightLoadingCard(title, episodeLabel, detail, image, effects)
        LoadingStyles.POSTER -> PosterLoadingCard(title, episodeLabel, detail, image, effects)
        else -> CinematicLoadingCard(title, episodeLabel, detail, image, effects)
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
        .collectAsState(initial = setOf(com.hikari.app.ui.LoadingEffects.SHEEN)).value
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
private fun LoadingStatusLine(tint: Color, dim: Color, centered: Boolean = true) {
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
            tr("Finding the best server…"),
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
    effects: Set<String>,
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
            LoadingTitleBlock(title, episodeLabel, detail) { Color(0xFFF5C569) }
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp)) {
            LoadingStatusLine(Color(0xFFF5C569), Color(0xCCFFFFFF))
        }
        // The chosen loading treatments, over everything the style just drew
        // (Settings → App Layout → Loading screen → Effect).
        LoadingCoverEffect(effects, Color(0xFFF5C569))
    }
}

/** The title / episode / detail block, shared by every style (only the accent
 *  colour of the episode line differs). */
@Composable
private fun LoadingTitleBlock(
    title: String,
    episodeLabel: String?,
    detail: String?,
    accent: () -> Color,
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
            LoadingTitleBlock(title, episodeLabel, detail) { accent }
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
                LoadingTitleBlock(title, episodeLabel, detail) { accent }
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
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
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
    val image = meta?.let { Artwork.heroModel(it) }
        ?: (PosterLoader.model(fallbackPoster) to false)
    val posterModel = PosterLoader.model(fallbackPoster)
    when (DetailHeroStyles.normalize(style)) {
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
                contentScale = if (image.second) ContentScale.Crop else ContentScale.Crop,
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
            // A scaled, dimmed copy of the very same art fills the frame, so a
            // portrait poster is never cropped and there is still no hard edge.
            PosterArt(
                model = posterModel ?: image.first,
                contentDescription = null,
                style = rememberPosterStyle(),
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.3f
                        scaleY = 1.3f
                        alpha = 0.42f
                    },
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
            PosterArt(
                model = image.first,
                contentDescription = meta?.title,
                style = rememberPosterStyle(),
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.40f),
                            0.45f to Color.Transparent,
                            1f to MaterialTheme.colorScheme.background,
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
    if (stats.isEmpty() && meta.isEmpty() && ratings.isEmpty() && cert == null &&
        d.director.isNullOrBlank() && d.writers.isEmpty()
    ) return

    Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
        // Year · runtime, with the age rating as its own bordered box next to
        // them — the way IMDb prints "PG-13". TMDB often has a rating only for
        // a region other than the US, and any of them beats showing nothing.
        if (stats.isNotEmpty() || cert != null) {
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
}

/** The site itself, for the "open the source" action. Brand names, so they are
 *  not translated. */
private fun ratingSite(source: RatingSource): String = when (source) {
    RatingSource.IMDB -> "IMDb"
    RatingSource.TOMATOMETER, RatingSource.POPCORN -> "Rotten Tomatoes"
    RatingSource.METACRITIC -> "Metacritic"
    RatingSource.LETTERBOXD -> "Letterboxd"
    RatingSource.TMDB -> "TMDB"
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
 *  the in-app web view when nothing answers. */
private fun openRatingPage(context: android.content.Context, url: String, title: String) {
    val opened = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
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

@Composable
private fun EpisodeRow(
    ep: Episode,
    onClick: () -> Unit,
    /** Download this episode without watching it: the player opens on it and
     *  shows its own download chooser the moment a server is ready. Null hides
     *  the button (callers that have nowhere to send a download). */
    onDownload: (() -> Unit)? = null,
) {
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
            ep.name?.ifBlank { tr("Episode %s").replace("%s", ep.number.toString()) }
                ?: tr("Episode %s").replace("%s", ep.number.toString()),
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
        if (onDownload != null) {
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onDownload, modifier = Modifier.size(36.dp)) {
                Icon(
                    painter = painterResource(R.drawable.ic_download),
                    contentDescription = tr("Download"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
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
