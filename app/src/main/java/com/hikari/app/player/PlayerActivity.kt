package com.hikari.app.player

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.Dialog
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Rational
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import coil.load
import com.google.common.collect.ImmutableList
import com.hikari.app.HikariApp
import com.hikari.app.R
import com.hikari.app.data.ContentRepository
import com.hikari.app.data.HistoryEntry
import com.hikari.app.data.DrmSpec
import com.hikari.app.data.Episode
import com.hikari.app.data.MediaType
import com.hikari.app.data.MediaItem as AppMediaItem
import com.hikari.app.data.StreamSource
import com.hikari.app.data.SubtitleSource
import com.hikari.app.download.DownloadEngine
import com.hikari.app.download.DownloadKind
import com.hikari.app.download.DownloadStatus
import com.hikari.app.download.DownloadTask
import com.hikari.app.download.DownloadsRepository
import com.hikari.app.i18n.I18n
import com.hikari.app.net.Http
import com.hikari.app.net.NetTuning
import com.hikari.app.net.PlayerHttp
import com.hikari.app.net.SlowNetTip
import com.hikari.app.net.StreamProbe
import com.hikari.app.ui.AccentStore
import com.hikari.app.ui.PosterLoader
import com.hikari.app.ui.UiScale
import com.hikari.app.ui.theme.HikariAccent
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap

/** The `Format.label` every user-added subtitle carries, so a track in the
 *  picker can be recognised as one the user brought themselves (and so a
 *  remembered pick can find it again after a re-prepare). The file's own name
 *  follows it, which is what identifies the track to the user. */
private const val USER_SUB_PREFIX = "Added · "

/** Language names that commonly appear in subtitle file names, mapped to the
 *  ISO code the subtitle sheet shows. Anything not listed is only used when it
 *  already looks like a code ("film.fr.srt"). */
private val FULL_LANG_NAMES = mapOf(
    "english" to "en", "spanish" to "es", "latino" to "es", "french" to "fr",
    "german" to "de", "italian" to "it", "portuguese" to "pt",
    "brazilian" to "pt", "russian" to "ru", "ukrainian" to "uk",
    "turkish" to "tr", "arabic" to "ar", "hindi" to "hi", "urdu" to "ur",
    "bengali" to "bn", "tamil" to "ta", "telugu" to "te", "malayalam" to "ml",
    "indonesian" to "id", "malay" to "ms", "vietnamese" to "vi", "thai" to "th",
    "korean" to "ko", "japanese" to "ja", "chinese" to "zh", "cantonese" to "zh",
    "polish" to "pl", "dutch" to "nl", "greek" to "el", "hebrew" to "he",
    "swedish" to "sv", "danish" to "da", "norwegian" to "no", "finnish" to "fi",
    "czech" to "cs", "hungarian" to "hu", "romanian" to "ro", "persian" to "fa",
    "farsi" to "fa", "filipino" to "fil", "tagalog" to "fil", "swahili" to "sw",
)

class PlayerActivity : ComponentActivity() {

    private data class PlayerSource(
        val name: String,
        val url: String,
        val headers: Map<String, String>,
        val subtitles: List<SubtitleSource>,
        val isM3u8: Boolean = false,
        val isMpd: Boolean = false,
        val isTorrent: Boolean = false,
        val infoHash: String? = null,
        val fileIdx: Int? = null,
        val trackers: List<String> = emptyList(),
        /** True once the source is a TorrServer URL (raw file streaming). */
        val torrentStream: Boolean = false,
        /** DRM protection info (ClearKey/Widevine) — null for ordinary streams. */
        val drm: DrmSpec? = null,
        /** True for a locally-downloaded copy (a file:// URL or a local
         *  .m3u8 built by the downloader). Local playback skips the network
         *  probe and reads straight off disk. */
        val local: Boolean = false,
        /** Which engine found this server ("CloudStream", "Hikari", "Nuvio",
         *  "Stremio") — the section it is listed under in the server chooser. */
        val provider: String = "",
        /** The installed provider that produced this server (`cs3|…`, `nuvio|…`).
         *  Lets the chooser give the provider the user opened the title from its
         *  own section, ahead of the engine sections. */
        val providerId: String = "",
        /** That provider's display name (the repo plugin's name). */
        val providerName: String = "",
    )

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null

    /** The Player UI skin in force for this playback session (Settings → Player
     *  → Player UI). Read once here from [PlayerSkins.current] — the mirror that
     *  HikariApp keeps up to date from AppStore — so the controller can be
     *  styled while it is being built, before any coroutine could have run. */
    private var skin: String = PlayerSkins.FALLBACK

    private var sources: List<PlayerSource> = emptyList()
    private var currentIndex = 0

    /** True once [playSource] has actually chosen a server this session. Until
     *  then nothing may be drawn as "already selected": `currentIndex` starts
     *  at its 0 default, so a plain `index == currentIndex` check painted the
     *  FIRST row of the chooser as the current one — and the row's tap handler
     *  then treated a tap on that row as a no-op, closing the chooser without
     *  playing anything. */
    private var playbackCommitted = false

    /** Set when the player was opened by a "download this" tap from OUTSIDE the
     *  player (an episode row's download button, or the detail page's download
     *  action — see DetailScreen's `openDownload` intent extra).
     *
     *  Nothing about playback changes: the video plays exactly as it does after
     *  a normal tap, and the download chooser simply comes up the moment a
     *  server has actually been prepared — byte-for-byte the dialog the
     *  in-player Download button shows, so there is only one download flow in
     *  the app. Cleared once it has been shown, so failing over to another
     *  server later in the same session never re-opens it. */
    private var openDownloadPending = false

    /** True while the startMode chooser is up waiting for the user's pick, so
     *  that backing out of it falls back to the remembered server. A dismiss
     *  caused by the user's own tap must NOT also trigger that fallback. */
    private var startChoicePending = false

    /** Set the instant a server row is TAPPED in the chooser, before the dismiss
     *  it triggers. The dismiss listener runs on the next loop turn (the
     *  dismissal is posted), so it cannot tell "the user picked a row and moved
     *  on" from "the user backed out" by looking at playback state alone — and in
     *  download mode nothing ever plays, so `playbackCommitted` stays false and
     *  every tap looked like a back-out. That made the player finish itself while
     *  the download sheet was still coming up, which is the crash-out-to-home bug
     *  on download-from-outside. This flag is the answer the row tap gives. */
    private var serverPickActed = false

    /** The startMode chooser is presented at most once per Activity. The live
     *  search keeps appending servers, and a late [startOrAsk] used to re-open
     *  the chooser the user had already answered (or backed out of), which is
     *  the "server list comes back by itself" bug. */
    private var startChooserShown = false

    /** Set when the user rotates with the button, so the once-per-source
     *  auto-rotate never overrides their choice. */
    private var userRotated = false

    /** Mirrors the Settings "Don't play directly" toggle: when on, a freshly
     *  found server list is never auto-played — the grouped chooser opens
     *  instead and playback waits for a pick. */
    private var askServerOnPlay = false

    /** Whether [askServerOnPlay] has been read from DataStore yet (it is read
     *  lazily, on the first start decision, so an entry point that never starts
     *  playback pays nothing). */
    private var askServerPrefLoaded = false

    /** Mirrors the Settings "Ask me when a chosen server fails" toggle: when on
     *  (the default), a server the USER picked that then dies brings up a choice
     *  — try the next one, pick another, or switch automatically from now on —
     *  instead of the player sliding onto another server by itself. Read lazily
     *  like [askServerOnPlay]. */
    private var failoverAskOnFailure = true

    /** Whether [failoverAskOnFailure] has been read from DataStore yet. */
    private var failoverAskPrefLoaded = false

    /** True while the server currently playing is one the user tapped in the
     *  chooser (as opposed to one the instant-play start or an automatic
     *  failover landed on). Only such a server asks the user before the player
     *  moves on — see [failoverFromCurrent] and AppStore's
     *  [AppStore.failoverAskOnFailureFlow]. Cleared as soon as an automatic
     *  advance happens, so a run of dead servers never becomes a run of
     *  dialogs. */
    private var pickedByUser = false

    /** The "server failed — what next?" prompt while it is up, and its
     *  countdown ticker (identical lifecycle to [slowDialog]). */
    private var failDialog: Dialog? = null
    private var failDialogTicker: Runnable? = null

    /** True when THIS launch came from a Download tap outside the player (the
     *  detail page's download button, a downloads-list retry). The download
     *  chooser is then offered on the first server that actually starts
     *  PLAYING, not on whatever was prepared first: a download started from a
     *  dead link is a download that fails a minute later, and offering it on a
     *  source that never rendered is exactly the "downloaded file is broken"
     *  report. */
    private var downloadFlowActive = false

    /** True when the player was opened ONLY to download something — a Download
     *  tap on the detail page or an episode row. Nothing is ever prepared or
     *  played in this mode: the server chooser comes up (filling in live, so it
     *  is on screen the instant the player is) and the download starts from the
     *  row the user picks. That is what a Download button has to do; the old
     *  behaviour — start playing the first server found and only then offer the
     *  download — played a video the user never asked to watch, and could offer
     *  the download for a source that never actually worked.
     *
     *  Once the user picks a row, [showDownloadDialog] runs exactly as it does
     *  from the in-player Download button, so there is still one download flow. */
    private var downloadPickMode = false

    /** Whether THIS launch asked for the chooser. The detail screen passes the
     *  setting through the intent, so flipping the toggle mid-session can't
     *  change what an already-running play does. */
    private val askServerThisLaunch: Boolean
        get() = intent?.getBooleanExtra("askServer", false) == true

    /** Rebuild callbacks for open server choosers, so a list that grows while
     *  the chooser is up (the detail screen keeps searching) re-renders live
     *  instead of showing a frozen snapshot. */
    private val sourcesWatchers = ArrayList<() -> Unit>()

    /** The live "Select server" sheet while it is up — the "don't play
     *  directly" chooser can be opened before the first server has landed, so
     *  the search-finished-with-nothing path needs a handle on it to close it
     *  before showing the "no servers" error over it. */
    private var serverChooserDialog: Dialog? = null

    private fun notifySourcesChanged() {
        // Servers can be appended (and re-probed) from background threads, and a
        // watcher touches views — always rebuild on the main looper.
        val rebuildAll = Runnable { sourcesWatchers.toList().forEach { runCatching { it() } } }
        if (Looper.myLooper() == Looper.getMainLooper()) rebuildAll.run()
        else Handler(Looper.getMainLooper()).post(rebuildAll)
    }

    /** The detail screen's live-search session id, when the player was opened
     *  through it. Lets a player whose every server has died ask the still-
     *  attached detail screen to re-run the providers with fresh, freshly-
     *  signed links (see [refreshSources]) instead of replaying a dead one. */
    private var liveSessionId: String? = null

    /** The provider the user opened this title FROM (the detail screen passes
     *  it as `histProviderId`) and its display name. Its own servers are listed
     *  in their own section ahead of the engine sections and are the ones
     *  playback starts on — "if I am on MovieBox, play MovieBox's server
     *  first". Both blank when the player was opened without that context. */
    private var originProviderId: String = ""
    private var originProviderName: String = ""

    /** Every URL this player has already tried this session. A re-extraction
     *  usually returns the same links (same mirror) plus a few new ones, so
     *  [freshIndex] uses this to avoid handing back a URL we know is dead. */
    private val triedUrls = HashSet<String>()

    /** Hosts that answered a terminal failure (an HTTP 5xx, a refused
     *  connection, a DNS failure) this session. Providers hand out several
     *  qualities of the SAME file from the SAME mirror — MovieBlast's 1080p,
     *  720p, 360p rows all live on `mbfiles.mbaccess.site` — so when that host
     *  answers 500 for one quality it answers 500 for the rest. Walking the
     *  list one dead row at a time cost ~13s per row (the "Found 63 servers but
     *  it never plays" report: it was grinding through one broken mirror the
     *  whole time). Servers on a failed host are now tried LAST, not first. */
    private val deadHosts = HashSet<String>()

    /** Host of [url], or blank when it cannot be parsed. */
    private fun mirrorHostOf(url: String): String = runCatching {
        java.net.URI(url).host.orEmpty().lowercase()
    }.getOrDefault("")

    /** The HTTP status behind a playback error, when there was one. media3 puts
     *  "Response code: 500" in the cause chain, which [onPlayerError] already
     *  stringifies into its details blob. */
    private fun httpStatusOf(details: String): Int? =
        Regex("Response code: (\\d{3})").find(details)?.groupValues?.get(1)?.toIntOrNull()

    /** How many times [refreshSources] has already asked for fresh sources —
     *  bounded so a genuinely dead video fails instead of looping forever. */
    private var refreshAttempts = 0

    /** True when playback started on a server that arrived while the detail
     *  screen's search was still running (the common case: Play is tapped, the
     *  first server shows up, the rest are still being extracted). Such a link
     *  can be a stale/partial extraction, which is why the same server often
     *  plays fine a moment later — see [onPlayerError]'s reconnect. */
    private var startedWhileSearching = false

    /** The live search has finished (every installed provider answered). */
    private var liveSearchDone = false

    /** The once-per-session "ask for a fresh link for THIS server before
     *  failing over" reconnect has already been used. */
    private var sameServerRelinkUsed = false

    /** Live-update subscription to the detail screen's ongoing server search
     *  (playback starts with the first server found; this keeps appending the
     *  rest as slower providers answer). */
    private var liveStreamsJob: Job? = null

    /** Subscription to the episode the detail screen settled on when a Play tap
     *  happened before the episode list had finished loading. */
    private var liveEpisodeJob: Job? = null

    /** Subscription to the detail screen's live search-progress text, shown
     *  under the loading cover's "Finding the best server…" line. */
    private var liveStatusJob: Job? = null

    /** Ticking elapsed-seconds suffix on the loading cover, so a long search
     *  visibly is still running instead of looking frozen. */
    private var loadingTickerJob: Job? = null

    /** Latest text received from the detail screen's search (without the
     *  elapsed-seconds suffix the ticker adds). */
    private var loadingStatusBase: String? = null

    /** API 33+ notification permission prompt for the download notification.
     *  Registered in onCreate (the only place an Activity may register a
     *  launcher). */
    private var notificationPermLauncher: ActivityResultLauncher<String>? = null

    /** Converts a detail-screen source (data layer) into a player source —
     *  mirrors the JSON payload parser so live-appended servers land in the
     *  "Select server" list exactly like the initial batch. */
    private fun StreamSource.toPlayerSource() = PlayerSource(
        name,
        Http.normalizeDriveUrl(url),
        headers,
        subtitles,
        isM3u8,
        isMpd,
        isTorrent,
        infoHash,
        fileIdx,
        trackers,
        drm = drm,
        provider = provider,
        providerId = providerId,
        providerName = providerName,
    )

    /** The inverse of [toPlayerSource]: a player source as a data-layer source,
     *  so it can go through the shared [StreamProbe] cache (which speaks
     *  [StreamSource]). */
    private fun PlayerSource.toStreamSource() = StreamSource(
        name,
        url,
        headers,
        subtitles,
        isTorrent,
        infoHash,
        isM3u8,
        isMpd,
        fileIdx,
        trackers,
        drm = drm,
        provider = provider,
        providerId = providerId,
        providerName = providerName,
    )

    /** Which header set the CURRENT source is being tried with, when a CDN
     *  keeps rejecting our requests. 0 = the extractor's full headers,
     *  1 = without Referer, 2 = no custom headers at all. Some CDNs (often
     *  Cloudflare-fronted) 403 a request that carries a Referer/Origin they
     *  don't expect even though the bare URL works in a browser — the player
     *  walks these variants before giving up on a server. */
    private var headerVariant = 0

    /** True while the current source is retried with text tracks disabled
     *  (its HLS manifest carried a garbage subtitle track that made media3
     *  crash with "Expected WEBVTT. Got 1"). */
    private var noSubsRetry = false

    private val bufferingWatchdog = Handler(Looper.getMainLooper())
    private var watchdogTask: Runnable? = null

    /** True once the current source has drawn its first video frame. */
    private var renderedFirstFrame = false

    /** True once the current source has been restarted by the first-frame
     *  watchdog (guards against an infinite restart loop). */
    private var firstFrameRetried = false

    /** First-frame watchdog: a video source that reaches READY but never draws
     *  a frame is a silently-hanging decoder (black screen) — the buffering
     *  watchdog can't catch it because playbackState is READY. Cancelled on
     *  onRenderedFirstFrame. */
    private var firstFrameTask: Runnable? = null

    /** True while the activity is in picture-in-picture mode — every overlay
     *  is stripped so only the video shows in the small window. */
    private var inPip = false

    /** "Server too slow" dialog: a 3s auto-switch countdown with Wait/Switch.
     *  Wait re-arms the watchdog for 30 more seconds, then re-prompts. */
    private var slowDialog: Dialog? = null
    private var slowDialogTicker: Runnable? = null

    /** "Connection looks slow" tip — offered while the loading cover is up, with
     *  a one-tap way to switch Slow connection mode on. Decided by [SlowNetTip]
     *  (background measurement + real playback struggle), never by a guess. */
    private var slowNetDialog: Dialog? = null

    private var speedChip: TextView? = null
    private var qualityBtn: TextView? = null
    private var sourcesBtn: TextView? = null
    private var episodesBtn: TextView? = null
    private var subsBtn: TextView? = null
    private var audioBtn: TextView? = null
    private var errorPanel: View? = null
    private var errorText: TextView? = null
    private var nextBtn: TextView? = null
    private var lockBtn: ImageButton? = null
    private var favBtn: ImageButton? = null
    private var resizeBtn: ImageButton? = null
    private var skipBtn: TextView? = null
    private var rotateBtn: TextView? = null
    private var unlockBtn: ImageButton? = null
    private var playHint: TextView? = null

    /** The favourite toggled by the top-bar heart button, and whether it is
     *  currently on. Built from the launch intent's history extras. */
    private var favouriteItem: AppMediaItem? = null
    private var isFavourite = false

    /** Episode listing / in-player episode switching, built lazily so the
     *  provider stack isn't touched until the Episodes pill is actually used. */
    private val contentRepo by lazy { ContentRepository((applicationContext as HikariApp).providers) }

    /** Full-screen title-card cover shown while the first server is being
     *  found / buffered (Nuvio/Stremio style). See [showLoadingBanner]. */
    private var loadingBanner: View? = null
    private var loadingSpinner: View? = null
    private var loadingBackdrop: ImageView? = null
    private var loadingTitleBox: View? = null
    private var loadingTitle: TextView? = null
    private var loadingEpisode: TextView? = null
    private var loadingDetail: TextView? = null
    private var loadingStatus: TextView? = null
    private var loadingSpinnerStatus: TextView? = null
    /** POSTER style's poster tile and SPOTLIGHT style's accent bloom — both
     *  GONE unless the user picked that style in Settings (see
     *  [com.hikari.app.ui.LoadingStyles] and [showLoadingBanner]). */
    private var loadingCardPoster: ImageView? = null
    private var loadingGlow: View? = null
    /** The chosen loading EFFECT's views (Settings → App Layout → Loading screen
     *  → Effect): a ring behind the title, a band of light sweeping across the
     *  cover, and the gallery frame around it. All GONE unless that effect is
     *  chosen — see [com.hikari.app.ui.LoadingEffects] and [showLoadingBanner]. */
    private var loadingEffectRing: View? = null
    private var loadingEffectSheen: View? = null
    private var loadingEffectFrame: View? = null
    /** The darkening layer over the backdrop. Kept weak when there is no
     *  artwork underneath (see [showLoadingBanner]) so it does not re-blacken
     *  the fallback wash and put us back to the flat black screen. */
    private var loadingScrim: View? = null
    private var bannerAnimators: List<android.animation.Animator> = emptyList()

    /** True while playback should be covered by the loading banner until the
     *  first frame lands (set from the launch intent, default ON). */
    private var bannerMode = true

    /** Which look the cover wears — the same choice the detail screen's
     *  full-screen card uses, so the hand-off from the detail page into the
     *  player never changes the design under the user. See
     *  [com.hikari.app.ui.LoadingStyles]. */
    private var loadingStyle = com.hikari.app.ui.LoadingStyles.CINEMATIC

    /** Which treatment is drawn over that cover (Settings → App Layout →
     *  Loading screen → Effect) — one of
     *  [com.hikari.app.ui.LoadingEffects], handed over by the detail screen so
     *  the two covers stay identical through the hand-off. */
    private var loadingEffect = com.hikari.app.ui.LoadingEffects.NONE

    private var speedIndex = 2

    /** True while the controls are locked — the media3 controller stays hidden
     *  and only the small top-right unlock button remains touchable. */
    private var controlsLocked = false

    /** Last time the "Locked — …" toast was shown, so a burst of taps while the
     *  controls are locked cannot stack a dozen toasts. */
    private var lockToastAt = 0L

    /** Auto-rotation already applied for the current source (once the screen
     *  matched the video's aspect we stop fighting the user's rotate button). */
    private var autoRotated = false

    /** True once the user has explicitly picked a subtitle/audio setting; while
     *  set, onTracksChanged must NOT re-assert the default (first) track. */
    private var userPickedSubs = false

    /** The user's explicit subtitle / audio choice, remembered as a descriptor
     *  rather than as a Tracks.Group reference. Attaching provider subtitles —
     *  and pressing Sync — REBUILDS the media item, and a rebuilt source
     *  exposes brand-new TrackGroup instances; an override keyed on the old
     *  group then matches nothing, so the pick silently stopped having any
     *  effect (the classic "I selected a subtitle and nothing ever appears",
     *  and the same reason a second audio track never switched language).
     *  [applyStickyPicks] re-applies these to whatever groups exist after every
     *  rebuild. */
    private var pickText: TrackPick? = null
    private var pickAudio: TrackPick? = null

    /** The user chose "Off" in the subtitle sheet. */
    private var textOff = false

    /** 0 = fit, 1 = crop. Mirrors the Resize chip label. */
    private var resizeIndex = 0

    /** Subtitle preferences (size scale + sync offset + vertical position),
     *  persisted per device. */
    private val subsPrefs by lazy { getSharedPreferences("player_subs", MODE_PRIVATE) }
    private var subtitleScale = 1f
    private var subtitleOffsetMs = 0L

    /** How far up the subtitles sit, as a fraction of the player height that is
     *  kept clear below them (SubtitleView's bottom padding fraction). Bigger =
     *  higher up the screen. The stock value sits the captions right on the
     *  bottom edge (inside the letterbox bar), which is why fullscreen subs
     *  looked like they were "falling off" the video — this default lifts them
     *  a little, and the Subtitles panel's Position row raises/lowers them. */
    private var subtitlePosition = 0.14f

    /** Caption appearance — text colour, outline/shadow + colour, background,
     *  bold/italic and font. Persisted next to the size/sync/position settings,
     *  and applied to the SubtitleView on every change (see
     *  [showSubtitleStyleDialog]). */
    private var subtitleStyle = SubtitleStyle()

    /** Set while the caption-style panel is open: a font picked from the system
     *  file picker lands asynchronously, and this lets the panel refresh its
     *  font row (and is cleared when the panel closes). */
    private var fontPicked: (() -> Unit)? = null

    /** The system file picker for "Pick a font file" (.ttf/.otf/.ttc). */
    private var fontLauncher: ActivityResultLauncher<Array<String>>? = null

    /** url -> raw subtitle text, cached so a sync offset can re-time existing
     *  subtitles without re-fetching them over the network. */
    private val subtitleRawCache = HashMap<String, String>()

    /** A subtitle file the user added by hand from this device — the answer to
     *  "this film's extensions have no subtitles at all". The bytes are kept in
     *  memory and the timed file is written to the subtitle cache, so a sync
     *  change re-times it exactly like a provider's subtitle, and no remote
     *  fetch is ever involved (which is also why attaching one can never break
     *  playback the way a dead subtitle URL can). */
    private class UserSubtitle(
        /** The unique `Format.label` this track carries, used to find it again
         *  after a re-prepare (TrackGroup instances are always new). */
        val label: String,
        /** The file's name on the user's device, shown in the picker's sub-line. */
        val fileName: String,
        val text: String,
        val mime: String,
    ) {
        var uri: Uri? = null
        var forOffset: Long = Long.MIN_VALUE
    }

    /** User subtitles, in the order they were added. Session-scoped: they stay
     *  attached across a server switch or an automatic failover, and are gone
     *  when the player closes. */
    private val userSubs = mutableListOf<UserSubtitle>()

    /** The system file picker for "Add external subtitle". */
    private var externalSubLauncher: ActivityResultLauncher<Array<String>>? = null

    private var torrentDialog: Dialog? = null

    /** Shown while an extension-less / container-unknown stream URL is probed
     *  to discover its real mime/URL before ExoPlayer sees it. */
    private var probeDialog: Dialog? = null

    private lateinit var client: OkHttpClient

    /** History key of the current video ("pid|type|mediaId|episodeId") — the
     *  identity used to remember which server the user last played it on. */
    private var historyKey: String = ""

    /** Index whose "last used server" has already been persisted, so walking
     *  servers (retries/failover) doesn't spam the store. */
    private var lastSavedSourceIndex = -1

    /** Header variant that accompanied [lastSavedSourceIndex] when it was
     *  persisted — a later successful variant re-saves once. */
    private var lastSavedVariant = -1

    /** Watch-history context passed by the detail screen. When non-null the
     *  player records resume positions into the app store. */
    private var historyEntry: HistoryEntry? = null

    /** Resume position (ms) from a history tap — seeked to on first ready. */
    private var startPositionMs = 0L

    /** The in-video "continue from where you left off?" prompt already fired
     *  (or is firing) — so switching servers never re-asks. */
    private var resumeOffered = false

    /** Saved progress offered by the detail screen as a cross-provider fallback
     *  (history was recorded under another extension's id). */
    private var resumeHintMs = 0L
    private var resumeHintDurMs = 0L

    /** Whether the startPosition seek has been applied yet. */
    private var seekPending = true

    /** Position of the last persisted progress — throttles DataStore writes. */
    private var lastSavedPos = -1L

    /** Periodic (5s) progress saver so even a force-kill keeps resume position. */
    private val saveHandler = Handler(Looper.getMainLooper())
    private var saveTask: Runnable? = null

    /** Main-thread handler driving the press-and-hold (≥2s → 2×) timer. */
    private val speedHandler = Handler(Looper.getMainLooper())
    private var holdSpeedTimer: Runnable? = null
    private var holdingFast = false

    /** True right after a ≥2s hold is released — the ensuing single-tap must
     *  NOT toggle the controls (the lift is part of the hold, not a tap). */
    private var suppressNextTap = false

    /** Mirrors media3's controller show/hide (kept in sync via the visibility
     *  listener, which also fires on the automatic 3s auto-hide). */
    private var controllerVisible = false

    /** YouTube/mpv-style gestures: single tap toggles the controls, double tap
     *  on the left/right half seeks −/+10s (with a visual feedback flash), and
     *  press-and-hold ≥2s plays at 2× until the finger lifts. */
    private val gestureDetector: GestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (suppressNextTap) {
                    suppressNextTap = false
                    return true
                }
                if (controlsLocked) {
                    // Locked = watch only: playback carries on and the small
                    // lock icon stays touchable, but nothing else reacts — no
                    // controls, no double-tap seek, no brightness/volume drags.
                    // Say so once in a while so a tap doesn't look like a dead
                    // screen.
                    if (System.currentTimeMillis() - lockToastAt > 4_000L) {
                        lockToastAt = System.currentTimeMillis()
                        Toast.makeText(this@PlayerActivity, I18n.t("Locked — tap the small lock icon to unlock"), Toast.LENGTH_SHORT).show()
                    }
                    return true
                }
                toggleController()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                suppressNextTap = false
                seekByTap(e.x)
                return true
            }
        }).apply { setIsLongpressEnabled(false) }
    }

    private var seekFeedback: View? = null
    private var seekIcon: TextView? = null
    private var seekText: TextView? = null

    // ---- Brightness / volume vertical-swipe gestures ----------------------
    // Dragging up/down on the LEFT half of the video changes the window
    // brightness, on the RIGHT half it changes the media volume (swipe up =
    // increase). The HUD sliders fade in while dragging and out shortly after
    // the finger lifts.
    private var gestureHud: View? = null
    private var hudBright: View? = null
    private var hudVol: View? = null
    private var hudBrightTrack: View? = null
    private var hudVolTrack: View? = null
    private var hudBrightFill: View? = null
    private var hudVolFill: View? = null
    private var hudBrightThumb: View? = null
    private var hudVolThumb: View? = null
    private var hudBrightValue: TextView? = null
    private var hudVolValue: TextView? = null
    private val hudHandler = Handler(Looper.getMainLooper())
    private var hudHideTask: Runnable? = null
    /** 0 = no vertical gesture in progress, 1 = brightness, 2 = volume. */
    private var verticalMode = 0
    private var downX = 0f
    private var downY = 0f
    private var startBrightness = -1f
    private var startVolume = 0
    private var maxVolume = 1
    private var audioManager: AudioManager? = null

    // ---- Top-bar metadata badges -----------------------------------------
    private var badgeDuration: TextView? = null
    private var badgeQuality: TextView? = null
    private var badgeSource: TextView? = null

    /** In-app UI scale: when the user turns it on (Settings → Appearance & Theme → In-app UI scale)
     *  the whole app stops following the phone's font/display size settings —
     *  including this View-based player, which is outside the Compose tree. */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(UiScale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        // The app font (Settings → Appearance & Theme → App font) reaches this View-based
        // screen by walking the inflated hierarchy — the Compose theme cannot.
        com.hikari.app.ui.AppFonts.applyToContent(this, window.decorView)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        playerView = findViewById(R.id.player_view)
        // The Player UI skin is read from the synchronous mirror HikariApp keeps
        // in step with the saved preference (see PlayerSkins), so the controller
        // can be styled the moment it exists.
        skin = PlayerSkins.current()
        subtitleScale = subsPrefs.getFloat("sub_scale", 1f)
        subtitleOffsetMs = subsPrefs.getLong("sub_offset", 0L)
        subtitlePosition = subsPrefs.getFloat("sub_pos", subtitlePosition)
        subtitleStyle = SubtitleStyle.load(subsPrefs)
        applySubtitleSize(subtitleScale)
        applySubtitlePosition(subtitlePosition)
        applySubtitleStyle()
        // YouTube-style: fade the controls out after 3s instead of media3's 5s.
        playerView?.controllerShowTimeoutMs = 3000
        // Keep our own mirror of the controller visibility (media3's
        // PlayerControlView field is private) for the tap-to-toggle logic.
        playerView?.setControllerVisibilityListener(object : PlayerView.ControllerVisibilityListener {
            override fun onVisibilityChanged(visibility: Int) {
                controllerVisible = visibility == View.VISIBLE
                // The pill row is a HorizontalScrollView. A focused pill (the
                // media3 control view asks for focus, and a scroll view reveals
                // a focused descendant) could pull the row to one end and leave
                // it parked there for the rest of the session — the first pill
                // sat half cut off in portrait. Every fresh appearance of the
                // controls starts the row at its left edge again.
                if (controllerVisible) resetPillScroll()
            }
        })
        speedChip = findViewById(R.id.speed_btn)
        favBtn = findViewById(R.id.fav_btn)
        playHint = findViewById(R.id.play_hint)
        qualityBtn = findViewById(R.id.quality_btn)
        sourcesBtn = findViewById(R.id.sources_btn)
        episodesBtn = findViewById(R.id.episodes_btn)
        subsBtn = findViewById(R.id.subs_btn)
        audioBtn = findViewById(R.id.audio_btn)
        lockBtn = findViewById(R.id.lock_btn)
        resizeBtn = findViewById(R.id.resize_btn)
        skipBtn = findViewById(R.id.skip_btn)
        rotateBtn = findViewById(R.id.rotate_btn)
        unlockBtn = findViewById(R.id.unlock_btn)
        errorPanel = findViewById(R.id.error_panel)
        errorText = findViewById(R.id.error_text)
        nextBtn = findViewById(R.id.next_btn)
        seekFeedback = findViewById(R.id.seek_feedback)
        seekIcon = findViewById(R.id.seek_icon)
        seekText = findViewById(R.id.seek_text)
        gestureHud = findViewById(R.id.gesture_hud)
        hudBright = findViewById(R.id.hud_bright)
        hudVol = findViewById(R.id.hud_vol)
        hudBrightTrack = findViewById(R.id.hud_bright_track)
        hudVolTrack = findViewById(R.id.hud_vol_track)
        hudBrightFill = findViewById(R.id.hud_bright_fill)
        hudVolFill = findViewById(R.id.hud_vol_fill)
        hudBrightThumb = findViewById(R.id.hud_bright_thumb)
        hudVolThumb = findViewById(R.id.hud_vol_thumb)
        hudBrightValue = findViewById(R.id.hud_bright_value)
        hudVolValue = findViewById(R.id.hud_vol_value)
        audioManager = runCatching { getSystemService(AUDIO_SERVICE) as? AudioManager }.getOrNull()
        maxVolume = runCatching {
            audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1)
        }.getOrNull() ?: 1
        badgeDuration = findViewById(R.id.badge_duration)
        badgeQuality = findViewById(R.id.badge_quality)
        badgeSource = findViewById(R.id.badge_source)
        findViewById<TextView>(R.id.title_text).text = intent.getStringExtra("title").orEmpty()

        // Top-bar episode line (e.g. "S1E2 · Freedom Day"), matching the
        // reference player's two-line title block. Hidden for movies.
        val epSeason = intent.getIntExtra("histEpisodeSeason", 0)
        val epNumber = intent.getIntExtra("histEpisodeNumber", 0)
        val epName = intent.getStringExtra("histEpisodeName").orEmpty()
        val subtitle = findViewById<TextView>(R.id.subtitle_text)
        subtitle.text = when {
            epSeason > 1 && epNumber > 0 ->
                "S$epSeason E$epNumber" + episodeNameSuffix(epName)
            epNumber > 0 -> "Episode $epNumber" + episodeNameSuffix(epName)
            else -> epName
        }
        subtitle.visibility = if (subtitle.text.isBlank()) View.GONE else View.VISIBLE

        findViewById<View>(R.id.back_btn).setOnClickListener { finish() }

        loadingBanner = findViewById(R.id.loading_banner)
        loadingSpinner = findViewById(R.id.loading_spinner)
        loadingBackdrop = findViewById(R.id.loading_backdrop)
        loadingTitleBox = findViewById(R.id.loading_title_box)
        loadingTitle = findViewById(R.id.loading_title)
        loadingEpisode = findViewById(R.id.loading_episode)
        loadingDetail = findViewById(R.id.loading_detail)
        loadingStatus = findViewById(R.id.loading_status)
        loadingSpinnerStatus = findViewById(R.id.loading_spinner_status)
        loadingCardPoster = findViewById(R.id.loading_card_poster)
        loadingGlow = findViewById(R.id.loading_glow)
        loadingEffectRing = findViewById(R.id.loading_effect_ring)
        loadingEffectSheen = findViewById(R.id.loading_effect_sheen)
        loadingEffectFrame = findViewById(R.id.loading_effect_frame)
        loadingScrim = findViewById(R.id.loading_scrim)

        bannerMode = intent.getBooleanExtra("showLoadingBanner", true)
        loadingStyle = com.hikari.app.ui.LoadingStyles.normalize(
            intent.getStringExtra("loadingStyle")
        )
        // The treatment over that card — same hand-off, same look (see
        // [com.hikari.app.ui.LoadingEffects]). Falls back to the default when an
        // older screen launched this player without the extra.
        loadingEffect = com.hikari.app.ui.LoadingEffects.normalize(
            intent.getStringExtra("loadingEffect")
        )
        // A Download tap from outside the player opens the server chooser first
        // and never plays anything (see [downloadPickMode]); the in-player
        // Download button needs no flag because playback is already running.
        openDownloadPending = intent.getBooleanExtra("openDownload", false)
        downloadPickMode = openDownloadPending
        downloadFlowActive = false

        // The cover stays up by design until real video is on screen, so tapping
        // it does nothing. (It used to skip straight to the player/controls,
        // which made an accidental tap look like it had dismissed the title card
        // and left the user staring at a black player.)
        loadingBanner?.setOnClickListener { }
        loadingSpinner?.setOnClickListener { }

        // This play just started: let the slow-connection tip measure in the
        // BACKGROUND (in parallel with the server search that is about to
        // happen anyway, so it delays nothing) and watch for evidence that the
        // play is struggling. It only ever speaks up with real evidence and
        // retracts itself the moment video appears — see SlowNetTip.
        SlowNetTip.onPlaybackStart()
        lifecycleScope.launch {
            SlowNetTip.suggestion.collect { reason ->
                if (reason != null) showSlowNetTip() else dismissSlowNetTip()
            }
        }

        speedChip?.setOnClickListener { cycleSpeed() }
        qualityBtn?.setOnClickListener { showQualityDialog() }
        sourcesBtn?.setOnClickListener { showSourcesDialog() }
        // The Episodes pill is only wireable when the player knows which title
        // it is playing (launched from the detail screen) and the title is a
        // series — it stays hidden otherwise, so it is never a dead button.
        episodesBtn?.visibility = View.GONE
        episodesBtn?.setOnClickListener { showEpisodesDialog() }
        subsBtn?.setOnClickListener { showSubsDialog() }
        audioBtn?.setOnClickListener { showAudioDialog() }
        findViewById<ImageButton>(R.id.download_btn)?.setOnClickListener { showDownloadDialog() }
        favBtn?.setOnClickListener { toggleFavourite() }
        // Top-bar gear: the player options that don't deserve a pill of their
        // own (video fit and rotation).
        findViewById<ImageButton>(R.id.options_btn)?.setOnClickListener {
            // Declared as a function so toggling the server-chooser row can
            // re-open the menu with its new state (a static option list would
            // need a live flow just to move one checkmark).
            fun openOptions() {
                showGlassMenu(
                    getString(R.string.player_options),
                    listOf(
                        GlassOption(
                            getString(R.string.player_fit_video), getString(R.string.player_fit_video_desc),
                            iconRes = R.drawable.ic_resize, marker = RowMarker.ICON,
                            selected = resizeIndex == 0,
                        ),
                        GlassOption(
                            getString(R.string.player_crop_to_fill), getString(R.string.player_crop_to_fill_desc),
                            iconRes = R.drawable.ic_resize, marker = RowMarker.ICON,
                            selected = resizeIndex == 1,
                        ),
                        GlassOption(
                            getString(R.string.player_rotate_screen), getString(R.string.player_rotate_screen_desc),
                            iconRes = R.drawable.ic_rotate, marker = RowMarker.ICON, chevron = true,
                        ),
                        GlassOption(
                            getString(R.string.player_server_chooser),
                            if (askServerOnPlay) {
                                getString(R.string.player_server_on)
                            } else {
                                getString(R.string.player_server_off)
                            },
                            iconRes = R.drawable.ic_server, marker = RowMarker.ICON,
                            selected = askServerOnPlay,
                        ),
                    ),
                    hint = getString(R.string.player_options_hint),
                    iconRes = R.drawable.ic_settings,
                ) { which ->
                    when (which) {
                        0, 1 -> {
                            resizeIndex = which
                            playerView?.resizeMode = if (which == 0) {
                                C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                            } else {
                                C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                            }
                            updateResizeButton()
                        }
                        2 -> cycleRotation()
                        3 -> {
                            // Same setting as Settings -> Playback start -> the
                            // "Don't play directly" switch, so the player can
                            // flip it without leaving the video.
                            askServerOnPlay = !askServerOnPlay
                            askServerPrefLoaded = true
                            val ask = askServerOnPlay
                            lifecycleScope.launch {
                                runCatching {
                                    (applicationContext as HikariApp).store.setAskServerOnPlay(ask)
                                }
                            }
                            openOptions()
                        }
                    }
                }
            }
            openOptions()
        }

        // The download notification needs POST_NOTIFICATIONS on API 33+; the
        // launcher must be registered here, before the first download starts.
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermLauncher = registerForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { }
        }

        // "Add external subtitle": the system picker hands back a content:// URI
        // to a .srt/.vtt/.ass/.ttml the user downloaded (or copied over from a
        // USB stick). It is copied into Hikari's own subtitle cache immediately —
        // the picked URI's permission is not persisted, and a cache copy is what
        // keeps the subtitle reading after a failover to another server.
        externalSubLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> if (uri != null) lifecycleScope.launch { addUserSubtitle(uri) } }

        // "Pick a font file": a .ttf/.otf for the captions. No mime filter can
        // be trusted for fonts on every device, so the pick is validated after
        // the fact (SubtitleFonts.importFile) and rejected with a message.
        fontLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri == null) return@registerForActivityResult
            val key = SubtitleFonts.importFile(this, uri)
            if (key == null) {
                Toast.makeText(
                    this,
                    I18n.t("That file isn't a caption font Hikari can use"),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                subtitleStyle = subtitleStyle.copy(font = key, enabled = true).copySaved(subsPrefs)
                applySubtitleStyle()
                fontPicked?.invoke()
            }
        }

        lockBtn?.setOnClickListener { lockControls() }
        resizeBtn?.setOnClickListener { cycleResize() }
        // Rotate is the same action as the gear menu's "Rotate screen" row, so
        // it is reachable without opening a dialog.
        rotateBtn?.setOnClickListener { cycleRotation() }
        skipBtn?.setOnClickListener {
            val p = player ?: return@setOnClickListener
            val target = (p.currentPosition + 85_000L).coerceIn(
                0L, p.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
            )
            p.seekTo(target)
        }
        unlockBtn?.setOnClickListener { unlockControls() }

        // ---- Player accent, control layout, video enhance ------------------
        // The accent comes from the AccentStore mirror (Settings → Appearance & Theme →
        // Player color, or the app accent while the two are linked). It is read
        // SYNCHRONOUSLY so the very first frame is already the right colour —
        // no flash of the default violet. Everything else in the player that is
        // accent-coloured derives from it already; applyAccentPalette covers the
        // pieces that were baked into XML drawables.
        runCatching { setAccent(AccentStore.player(this)) }
        applyPlayerSkin()

        // The Enhance pill: realtime colour grading of the video itself.
        findViewById<TextView>(R.id.enhance_btn)?.setOnClickListener { showEnhanceMenu() }

        // Both remaining preferences are read asynchronously and applied as soon
        // as they land. Until then the player keeps the layout it shipped with
        // and applies no enhancement, so nothing here can delay first playback.
        lifecycleScope.launch {
            controlLayout = PlayerControlsConfig.decode(
                runCatching { (applicationContext as HikariApp).store.playerControls() }
                    .getOrNull()
            )
            applyControlLayout()
        }
        lifecycleScope.launch {
            enhancePresetKey = EnhancePreset.fromKey(
                runCatching { (applicationContext as HikariApp).store.enhancePreset() }
                    .getOrNull()
            ).key
            // A device that once refused the effects pipeline is remembered, so
            // the player never arms it again (arming on such a device failed
            // every play, not just the one where a preset was picked).
            enhanceUnsupported = runCatching {
                (applicationContext as HikariApp).store.enhanceUnsupported()
            }.getOrDefault(false)
            applyVideoEnhance(force = true)
        }

        // Picture-in-picture: explicit pip button (top bar) plus YouTube-style
        // auto-enter when the user leaves the player with video playing (12+).
        // minSdk is 24, so the whole feature is gated on SDK >= 26 (API 26
        // introduced PiP).
        val pipBtn = findViewById<ImageButton>(R.id.pip_btn)
        if (Build.VERSION.SDK_INT >= 26) {
            pipBtn?.setOnClickListener { enterPip() }
            if (Build.VERSION.SDK_INT >= 31) {
                // API 31+ prefers setAutoEnterEnabled over onUserLeaveHint so
                // the enter fires exactly once.
                setPictureInPictureParams(
                    PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(16, 9))
                        .setAutoEnterEnabled(true)
                        .build()
                )
            }
        } else {
            pipBtn?.visibility = View.GONE
        }

        nextBtn?.setOnClickListener {
            if (currentIndex + 1 < sources.size) {
                noSubsRetry = false
                playSource(currentIndex + 1)
            } else {
                // Last server failed — start over, but on a server we haven't
                // tried yet if there is one. Servers found by the
                // cross-extension pass arrive LATE and are appended at the END
                // of the list, so restarting at index 0 would just replay the
                // dead link we started with instead of the repo that works.
                noSubsRetry = false
                resetHeaderWalk()
                val fresh = freshIndex("")
                playSource(if (fresh >= 0) fresh else 0)
            }
        }

        playerView?.setOnTouchListener { _, event ->
            // Consume every touch on the video surface so the YouTube-style
            // gestures below own the interaction (media3's built-in click-to-
            // toggle never fires). Touches on the controller's own buttons /
            // seekbar go to those children first and never reach us.
            gestureDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    holdingFast = false
                    verticalMode = 0
                    downX = event.x
                    downY = event.y
                    holdSpeedTimer?.let { speedHandler.removeCallbacks(it) }
                    val task = Runnable {
                        // Finger has stayed down ≥2s → play at 2× until lift.
                        // Not while the controls are locked: the lock exists to
                        // stop accidental interaction, and a speed change is
                        // very audible.
                        if (controlsLocked) return@Runnable
                        holdingFast = true
                        applySpeed(2f)
                    }
                    holdSpeedTimer = task
                    speedHandler.postDelayed(task, 2000)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (verticalMode == 0) {
                        val dx = event.x - downX
                        val dy = event.y - downY
                        val slop = 18 * resources.displayMetrics.density
                        // A mostly-vertical drag takes over from the tap/hold
                        // gestures: cancel the pending speed-up, drop the
                        // controls and bring up the brightness/volume HUD.
                        // NOT while locked: the lock is "watch only", so a
                        // stray drag must not change the brightness or the
                        // volume either (only the small unlock button reacts).
                        if (!controlsLocked && abs(dy) > slop && abs(dy) > abs(dx)) {
                            holdSpeedTimer?.let { speedHandler.removeCallbacks(it) }
                            holdSpeedTimer = null
                            if (holdingFast) {
                                holdingFast = false
                                applySpeed(SPEEDS[speedIndex])
                            }
                            suppressNextTap = true
                            playerView?.hideController()
                            beginVerticalGesture()
                        }
                    }
                    if (verticalMode != 0) {
                        val travel = playerView?.height?.toFloat()?.takeIf { it > 0f }
                            ?: resources.displayMetrics.heightPixels.toFloat()
                        // Swipe UP (a negative dy) increases the value. The gain
                        // is deliberately high: with a 1:1 mapping the sliders
                        // moved so slowly that the user had to swipe the whole
                        // screen 8-9 times to reach the end. GESTURE_SWIPE_GAIN
                        // makes roughly a quarter of a screen-height swipe cover
                        // the full range.
                        val delta = -((event.y - downY) / travel) * GESTURE_SWIPE_GAIN
                        if (verticalMode == 1) {
                            applyBrightness(startBrightness + delta)
                        } else {
                            applyVolume(startVolume + (delta * maxVolume).roundToInt())
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    holdSpeedTimer?.let { speedHandler.removeCallbacks(it) }
                    holdSpeedTimer = null
                    if (holdingFast) {
                        holdingFast = false
                        suppressNextTap = true
                        applySpeed(SPEEDS[speedIndex])
                    }
                    if (verticalMode != 0) endVerticalGesture()
                }
            }
            true
        }

        // All our controls (Back/Title/Favourite/Download/PiP/Options/Lock in the
        // top bar, Speed/Source/Quality/Audio/Subtitles/Skip Intro in the pill
        // row) live INSIDE the media3 controller layout now, so they appear and
        // fade together with the playback controls on tap.

        // Watch-history context (set by the detail screen). When present, the
        // player periodically persists resume position into the app store.
        val histProvider = intent.getStringExtra("histProviderId")
        originProviderId = histProvider.orEmpty()
        originProviderName = runCatching {
            (applicationContext as HikariApp).providers.byId(originProviderId)?.config?.name
        }.getOrNull().orEmpty()
        if (!histProvider.isNullOrBlank()) {
            historyEntry = HistoryEntry(
                providerId = histProvider,
                mediaId = intent.getStringExtra("histMediaId").orEmpty(),
                type = runCatching { MediaType.valueOf(intent.getStringExtra("histType").orEmpty()) }
                    .getOrDefault(MediaType.UNKNOWN),
                title = intent.getStringExtra("histTitle").orEmpty(),
                posterUrl = intent.getStringExtra("histPoster").takeIf { !it.isNullOrBlank() },
                episodeId = intent.getStringExtra("histEpisodeId").orEmpty(),
                episodeName = intent.getStringExtra("histEpisodeName").orEmpty(),
            )
            historyKey = historyEntry!!.uniqueKey
            startPositionMs = intent.getLongExtra("startPosition", 0L).coerceAtLeast(0L)
            resumeHintMs = intent.getLongExtra("histResumePosition", 0L).coerceAtLeast(0L)
            resumeHintDurMs = intent.getLongExtra("histResumeDuration", 0L).coerceAtLeast(0L)
            saveTask = object : Runnable {
                override fun run() {
                    recordProgress()
                    saveHandler.postDelayed(this, 5000)
                }
            }
            saveHandler.postDelayed(saveTask!!, 5000)

            // The top-bar heart works on the same title the history entry was
            // opened for. Its initial state comes from the stored favourites; we
            // keep observing so a toggle on the detail screen is reflected here.
            if (historyEntry!!.mediaId.isNotBlank()) {
                favouriteItem = AppMediaItem(
                    providerId = histProvider,
                    id = historyEntry!!.mediaId,
                    title = historyEntry!!.title,
                    type = historyEntry!!.type,
                    posterUrl = historyEntry!!.posterUrl,
                    backdropUrl = intent.getStringExtra("bannerBackdrop")?.takeIf { it.isNotBlank() },
                )
                lifecycleScope.launch {
                    runCatching {
                        (applicationContext as HikariApp).store.favoritesFlow().collect { list ->
                            val on = list.any { it.uniqueId == favouriteItem?.uniqueId }
                            if (on != isFavourite) {
                                isFavourite = on
                                favBtn?.setImageResource(
                                    if (on) R.drawable.ic_heart_filled else R.drawable.ic_heart
                                )
                                favBtn?.imageTintList = tintOf(on)
                            }
                        }
                    }
                }
            }
        }

        // Reveal the Episodes pill only when we know the title and it is a
        // series — a movie (or playback with no provider context) has no
        // episode list to show, so the pill stays hidden rather than dead.
        episodesBtn?.visibility =
            if (favouriteItem != null && favouriteItem?.type != MediaType.MOVIE) View.VISIBLE
            else View.GONE

        sources = runCatching {
            val arr = JSONArray(intent.getStringExtra("sources").orEmpty())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val headersObj = o.optJSONObject("headers") ?: JSONObject()
                val headers = HashMap<String, String>()
                val keys = headersObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    headers[k] = headersObj.getString(k)
                }
                val subsObj = o.optJSONArray("subtitles") ?: JSONArray()
                val subs = (0 until subsObj.length()).map { j ->
                    val s = subsObj.getJSONObject(j)
                    SubtitleSource(s.optString("lang"), s.optString("url"))
                }
                val trackersObj = o.optJSONArray("trackers") ?: JSONArray()
                val trackers = (0 until trackersObj.length()).mapNotNull { j ->
                    trackersObj.optString(j).ifBlank { null }
                }
                PlayerSource(
                    o.optString("name", "Source ${i + 1}"),
                    // Normalize Google Drive URLs to the direct-download form so
                    // the player never hits the drive virus-scan HTML page.
                    Http.normalizeDriveUrl(o.optString("url")),
                    headers,
                    subs,
                    o.optBoolean("isM3u8"),
                    o.optBoolean("isMpd"),
                    o.optBoolean("isTorrent"),
                    o.optString("infoHash").ifBlank { null },
                    o.optInt("fileIdx", -1).takeIf { it >= 0 },
                    trackers,
                    drm = parseDrmSpec(o.optJSONObject("drm")),
                    local = o.optBoolean("local"),
                    provider = o.optString("provider"),
                    providerId = o.optString("providerId"),
                    providerName = o.optString("providerName"),
                )
            }
        }.getOrDefault(emptyList())
            // The same video surfaced by both extraction engines / addons = one
            // entry. Torrents carry url="" and share their identity by infoHash,
            // so keying on url alone would collapse every torrent source into a
            // single row.
            .distinctBy { it.infoHash ?: it.url }
            // The provider the user opened this title from goes to the FRONT, so
            // the server playback starts on (and the top of "Select server") is
            // one it actually came from — "on MovieBox, play MovieBox". Stable
            // sort, so every other engine keeps the order the repo sent.
            .sortedBy { if (it.isFromOrigin()) 0 else 1 }

        val liveId = intent.getStringExtra("streamsLiveId")
        liveSessionId = liveId
        // One line per playback in the on-device log: which title, from which
        // provider, with how many servers — the starting point of every "why
        // didn't it play / why was this server missing" report.
        com.hikari.app.data.Logs.log(
            "Player",
            "open \"${intent.getStringExtra("title")}\" origin=" +
                "$originProviderName ($originProviderId) sources=${sources.size} live=${!liveId.isNullOrBlank()}",
        )
        // The detail screen now opens the player the instant Play is tapped,
        // BEFORE any server is found, and streams servers to us over
        // [StreamsLive]. An empty list plus a live session id therefore means
        // "wait for the first server", not "nothing to play".
        val awaitLive = sources.isEmpty() && liveId != null
        if (sources.isEmpty() && !awaitLive) {
            showError(I18n.t("No playable sources received."), false)
            return
        }

        // Cover the very first frames with the title card: the detail screen
        // showed the same card while it searched, so this keeps the "finding
        // your server" screen continuous until real video is on screen.
        showLoadingCover()

        if (liveId != null) {
            // The detail screen keeps searching every installed provider while
            // playback runs; append each newly found server here so "Select
            // server" lists everything. When we opened with no servers yet, the
            // FIRST batch that arrives also starts playback.
            liveStreamsJob = lifecycleScope.launch {
                // In download-pick mode nothing ever starts playing: the
                // chooser is the destination, and it fills in as servers land.
                var pendingStart = awaitLive && !downloadPickMode
                // How many servers must be known before playback starts. 1 (the
                // default) means "the instant the first server is found"; a
                // higher value is the Settings "wait for more servers" choice.
                // The search FINISHING always counts as enough too, so a title
                // that only ever finds 2 servers starts as soon as every
                // installed extension has answered, instead of waiting forever
                // for a 3rd..5th one that does not exist.
                val startAfter = intent.getIntExtra("startAfterServers", 1).coerceIn(1, 8)
                // "Don't play directly": the chooser is the destination, so ONE
                // server is already enough to put the list on screen — the
                // "wait for N servers" setting must not hold the chooser back.
                val askMode = shouldAskServer()
                var searchDone = false
                // ---- "Search your own extension first", at the start level --
                // The detail screen already asks the provider the title was
                // opened from BEFORE the others (and holds the cross-extension
                // pass back a moment for it). This is the matching half of that
                // promise in the player: when the origin is one of the providers
                // being searched, playback waits a bounded moment for a server
                // from IT rather than jumping onto whichever other extension
                // answered first — tapping a movie inside an extension plays
                // that extension's link. The hold ends the instant the origin's
                // server lands, and the whole rest of the search keeps streaming
                // into "Select server" in the background either way (see the
                // live collector below). [originGraceMs] comes from the intent
                // and is 0 unless the origin really is being searched (disabled
                // and uninstalled extensions must not cost a wait).
                val originGraceMs = intent.getIntExtra("originGraceMs", 0).coerceAtLeast(0)
                val originHoldUntil = System.currentTimeMillis() + originGraceMs
                // How long playback waits for the origin once servers are already
                // IN HAND — see ORIGIN_HEAD_START_MS (DetailScreen). This is what
                // makes the default "play as soon as the first server is found"
                // actually instant: the origin gets a few seconds' head start, not
                // the whole backstop, and its servers keep arriving afterwards.
                // 0 = the "wait for more servers first" choice, which keeps the
                // old behaviour of holding for the full grace window.
                val originHeadStartMs = intent.getIntExtra("originHeadStartMs", 0).coerceAtLeast(0)
                // When the first server arrived. Written by the live collector
                // below; the head start is measured from here, not from launch.
                var firstServersAt = 0L
                var headStartLogged = false
                val originFound = {
                    originProviderId.isNotBlank() &&
                        sources.any { it.providerId == originProviderId }
                }
                // The pass reports the moment the origin has ANSWERED — with
                // servers or with nothing (see StreamsLive.settleOrigin). That,
                // not the clock, is what ends the hold in practice: a repo that
                // plainly has no links for this episode must not cost the user
                // the whole backstop, and a .hiki plugin that needs twenty
                // seconds to answer must not be cut off at two.
                var originSettled = false
                // Assigned once [tryStart] exists (below). The settle signal is
                // not just a flag that the hold's condition reads — it has to
                // RELEASE a start that is already pending. The origin answering
                // "nothing for this episode" produces no servers, so it wakes no
                // feed batch and nothing else would call [tryStart] until the
                // grace window's own alarm fired: the user would wait out the
                // whole backstop for an answer that had already come.
                var onOriginSettled: (() -> Unit)? = null
                if (!liveId.isNullOrBlank()) launch {
                    StreamsLive.originSettledFlow(liveId).collect { settled ->
                        originSettled = settled
                        if (settled) onOriginSettled?.invoke()
                    }
                }
                /** True once there is no reason left to hold for the origin. */
                val originReady = {
                    originGraceMs <= 0 || originProviderId.isBlank() || originFound() ||
                        originSettled || searchDone ||
                        System.currentTimeMillis() >= originHoldUntil ||
                        // The head start has run out with servers in hand: play
                        // one of them instead of holding the whole server list
                        // hostage to one repo. The origin's answer is not lost —
                        // its servers still stream in below and are still placed
                        // at the top of the list.
                        (originHeadStartMs > 0 && firstServersAt > 0 &&
                            System.currentTimeMillis() - firstServersAt >= originHeadStartMs)
                }
                val waitTimeout = if (awaitLive) launch {
                    delay(LIVE_WAIT_TIMEOUT_MS)
                    if (sources.isEmpty()) {
                        // Give up on the search: close a still-empty chooser
                        // first so the error isn't buried behind it.
                        runCatching { serverChooserDialog?.dismiss() }
                        showError(I18n.t("No playable sources received."), false)
                    }
                } else null
                // Nothing will call [tryStart] again once the servers stop
                // arriving, so the hold needs its own alarm — armed just below,
                // once [tryStart] exists: at the deadline the first server from
                // anywhere starts playback, exactly as if the origin had
                // answered with nothing.
                val tryStart: suspend () -> Unit = tryStart@{
                    if (pendingStart && sources.isNotEmpty() &&
                        (searchDone || askMode || sources.size >= startAfter)
                    ) {
                        if (!originReady()) {
                            // Servers are here, but not the origin's yet: say so
                            // on the cover, or a two-second pause while a
                            // perfect-looking server list is already in hand
                            // reads as the app being stuck.
                            val line = I18n.t("Checking your own extension first…")
                            loadingStatusBase = line
                            loadingStatus?.text = line
                            return@tryStart
                        }
                        pendingStart = false
                        waitTimeout?.cancel()
                        // Remember that this link was extracted mid-search: if
                        // it turns out to be a dud, the same server gets one
                        // re-resolve before the player walks on (see
                        // onPlayerError) — that is what makes a second Play tap
                        // work, done here instead of making the user back out.
                        startedWhileSearching = !searchDone
                        startOrAsk()
                    }
                }
                // [tryStart] exists now, so the settle signal can release a
                // pending start the moment it arrives (and again here, in case
                // the origin had already answered before this block ran — a
                // MutableStateFlow replays, and the collector above fires
                // eagerly, before the hook was assigned).
                onOriginSettled = { if (pendingStart) launch { tryStart() } }
                if (originSettled) onOriginSettled?.invoke()
                // The hold's own alarm (see the note above): if the origin never
                // answers, this fires at the deadline and starts the first
                // server from anywhere — no other call to [tryStart] is coming
                // once the servers stop arriving.
                if (awaitLive && originGraceMs > 0) launch {
                    delay((originHoldUntil - System.currentTimeMillis()).coerceAtLeast(0L) + 60L)
                    // The origin was given its grace window and the window has
                    // now run out with nothing from it (originSettled would
                    // have ended the hold early, and an origin server landing
                    // would have started playback — see originReady). Say so,
                    // because the very next thing that happens is the user
                    // being shown a server from a DIFFERENT extension, and
                    // that looks like the wrong link being played for no
                    // reason unless the log explains the origin never
                    // answered (which is what happened in the "it played a
                    // XFree/AFree source instead of the MRDS one" report).
                    if (!originFound()) {
                        val why = if (originSettled) {
                            "answered with nothing for this episode"
                        } else {
                            "never answered in the ${originGraceMs}ms grace window"
                        }
                        com.hikari.app.data.Logs.log(
                            "Player",
                            "origin \"$originProviderName\" ($originProviderId) $why — " +
                                "starting on the first server from anywhere",
                        )
                    }
                    tryStart()
                }
                // The head start's own alarm (see ORIGIN_HEAD_START_MS).
                //
                // The rule inside [originReady] is only consulted when something
                // calls [tryStart], and after the first batch nothing may call it
                // again for a while — a slow repo that answered late is exactly
                // the case where the servers are in hand and the next arrival is
                // far off. So the hold gets a deadline measured from the moment
                // there was something to play, and this is it.
                if (awaitLive && originHeadStartMs > 0) launch {
                    // Wait for the first servers (bounded by the origin's own
                    // backstop, in case nothing ever arrives — there is then
                    // nothing for this to start either).
                    while (firstServersAt == 0L && System.currentTimeMillis() < originHoldUntil) {
                        if (sources.isNotEmpty()) {
                            firstServersAt = System.currentTimeMillis()
                            break
                        }
                        delay(150L)
                    }
                    if (firstServersAt == 0L) return@launch
                    delay(originHeadStartMs)
                    if (!originFound() && !originSettled && !headStartLogged) {
                        headStartLogged = true
                        com.hikari.app.data.Logs.log(
                            "Player",
                            "origin \"$originProviderName\" ($originProviderId) has not " +
                                "answered yet — starting on a server that has " +
                                "(${sources.size} in the list). Its own servers are still " +
                                "being searched for and will be added to \"Select server\".",
                        )
                    }
                    tryStart()
                }
                // The detail screen signals when its whole search is finished;
                // if it ended with nothing, fail fast instead of waiting out
                // the safety timeout above — and if it ended with fewer servers
                // than we were told to wait for, start with what we have.
                if (awaitLive) launch {
                    StreamsLive.doneFlow(liveId).collect { done ->
                        if (!done || searchDone) return@collect
                        searchDone = true
                        liveSearchDone = true
                        // Append happens before markDone, so a non-empty live
                        // flow means servers are on the way.
                        if (sources.isEmpty() && StreamsLive.flow(liveId).value.isEmpty()) {
                            // The detail screen says its search is over and there
                            // is nothing to hand over. That is only a real verdict
                            // when IT says so: its "No playable server found after
                            // searching N extensions…" note is written only for a
                            // pass that genuinely FINISHED empty. Anything else
                            // means the pass was cut short or hit a problem, and
                            // telling the user "no playable sources" for that is
                            // what made a search that was still finding servers
                            // look like an empty catalogue ~9 seconds into a tap.
                            val last = StreamsLive.statusFlow(liveId).value
                            // The "don't play directly" chooser may already be
                            // up with nothing in it (it opens the instant the
                            // player does). Close it before showing the error,
                            // so the message isn't buried behind an empty sheet.
                            runCatching { serverChooserDialog?.dismiss() }
                            if (last != null && last.startsWith(NO_RESULT_PREFIX)) {
                                showError(I18n.t("No playable sources received."), false)
                            } else {
                                // Honest: the search did not get to answer. The
                                // panel's "Retry all" button re-runs it.
                                showError(
                                    I18n.t("The search was cut short before it found anything."),
                                    false,
                                )
                            }
                        } else {
                            tryStart()
                        }
                    }
                }
                StreamsLive.flow(liveId).collect { incoming ->
                    if (incoming.isEmpty()) return@collect
                    val have = sources.map { it.infoHash ?: it.url }.toHashSet()
                    val fresh = incoming
                        .map { it.toPlayerSource() }
                        .filter { (it.infoHash ?: it.url) !in have }
                    if (fresh.isEmpty()) return@collect
                    sources = sources + fresh
                    // When the first server arrived — the head start (see
                    // [originReady]) is measured from here: the clock only starts
                    // once there is actually something to play.
                    if (firstServersAt == 0L) firstServersAt = System.currentTimeMillis()
                    notifySourcesChanged()
                    // Resolve the new servers in the background too, so picking
                    // one from "Select server" doesn't fall back to a probe wait.
                    lifecycleScope.launch(Dispatchers.IO) {
                        runCatching { StreamProbe.warm(fresh.map { it.toStreamSource() }) }
                    }
                    // A stalled server (see [awaitReplacementForStalledServer])
                    // is replaced by the first thing that lands here, so a
                    // playback that opened on one slow server recovers by
                    // itself as the search keeps finding servers.
                    if (playReplacementIfWaiting()) return@collect
                    tryStart()
                }
            }
            // Live search progress for the loading cover. Playback is launched
            // before a single server exists, so this is the only thing telling
            // the user *what* the search is doing (and, when nothing is found,
            // *why* nothing played) instead of one frozen sentence.
            liveStatusJob = lifecycleScope.launch {
                StreamsLive.statusFlow(liveId).collect { s ->
                    // A stalled server (see [awaitReplacementForStalledServer])
                    // owns the cover's line while it waits: "X isn't responding
                    // — looking for another server…" explains why THIS server
                    // is not playing, and the running search count would only
                    // bury that explanation (the cover's ticker re-draws from
                    // [loadingStatusBase], so the base is left alone too).
                    if (!awaitingReplacement) {
                        loadingStatusBase = s
                        val line = s ?: DEFAULT_LOADING_STATUS
                        loadingStatus?.text = line
                        loadingSpinnerStatus?.text = line
                    }
                    // Belt-and-braces: the detail screen reports "found nothing"
                    // as a status line a beat before it signals completion. If
                    // that signal ever goes missing (its search coroutine is
                    // cancelled — the exact case that used to leave this cover
                    // spinning for the full safety timeout), the text alone is
                    // enough to fail here in the same second, WITH the reason.
                    if (awaitLive && !liveSearchDone && sources.isEmpty() &&
                        s != null && s.startsWith(NO_RESULT_PREFIX)
                    ) {
                        liveSearchDone = true
                        runCatching { serverChooserDialog?.dismiss() }
                        showError(I18n.t("No playable sources received."), false)
                    }
                }
            }
            // A Play tap made before the origin addon finished listing episodes:
            // adopt the episode the detail screen settles on, so the title card,
            // resume key and watch history are per-episode rather than the
            // movie-level entry.
            liveEpisodeJob = lifecycleScope.launch {
                StreamsLive.episodeFlow(liveId).collect { ep ->
                    if (ep != null) applyLiveEpisode(ep)
                }
            }
        }

        // The process-wide playback client (see [PlayerHttp]): its connection
        // pool + dispatcher are shared with [StreamProbe], so the CDN
        // connection the probe already opened and the TLS session it already
        // negotiated are reused for the first media request instead of being
        // paid again when ExoPlayer starts pulling.
        client = PlayerHttp.client

        // Resolve every not-yet-known server while the first one starts: the
        // probe cache then answers instantly for a "Select server" pick, a
        // failover, a retry, or a later replay of the same video. Skipped in
        // download-pick mode: nothing is going to play there, so probing (and
        // holding network bandwidth) while the user chooses a server is pure
        // waste.
        if (!downloadPickMode) lifecycleScope.launch(Dispatchers.IO) {
            runCatching { StreamProbe.warm(sources.map { it.toStreamSource() }) }
        }

        // If servers are already here (e.g. WebView playback or a source list
        // handed in directly), start on the server this video was last played
        // with (matched by URL then by name) so a replay picks up on a
        // known-good, already-resolved source. On the instant open (no servers
        // yet) the live collector above starts playback the moment the first
        // server arrives.
        //
        // With "don't play directly" ON, [startOrAsk] opens the server chooser
        // instead — and it is called here for the instant open too (empty list
        // + a live session), so the chooser comes up the moment the player does
        // and fills in as servers are found, rather than after the first (or
        // fifth) one finally lands.
        if (sources.isNotEmpty() || liveId != null) startOrAsk()
    }

    /** Index of the server the user last played this video with — matched by
     *  URL first (same link across runs), then by server name (signed/tokenized
     *  URLs that differ per run) — or 0 when nothing is remembered. */
    private suspend fun preferredStartIndex(): Int {
        if (sources.isEmpty()) return 0
        val last = if (historyKey.isBlank()) null else runCatching {
            (applicationContext as HikariApp).store.lastSource(historyKey)
        }.getOrNull()
        val byUrl = if (last != null && last.url.isNotBlank()) {
            sources.indexOfFirst { it.url == last.url }
        } else -1
        val byName = if (last != null && byUrl < 0 && last.name.isNotBlank()) {
            sources.indexOfFirst { it.name.equals(last.name, ignoreCase = true) }
        } else -1
        // Restore the header variant that actually played last time — but ONLY
        // on an identical URL. A name-only match is a freshly signed link (or a
        // different mirror) that may need a completely different header set, so
        // restoring the remembered variant there could pin the player to the
        // wrong variant and skip the full → no-Referer → none walk entirely.
        if (last != null && byUrl >= 0) headerVariant = last.headerVariant.coerceIn(0, 2)
        return when {
            byUrl >= 0 -> byUrl
            byName >= 0 -> byName
            // Nothing remembered: do not blindly start on row 1. A host that
            // already answered a terminal failure this session (or a URL a probe
            // found dead) is skipped, so the tap lands on something that can
            // actually play instead of burning a full error cycle first.
            else -> healthyStartIndex()
        }
    }

    /** First server that is neither on a host that already failed terminally
     *  this session nor already known-dead from a probe — the best row to start
     *  playback on. Falls back to row 1 so something always plays. */
    private fun healthyStartIndex(): Int {
        val healthy = { s: PlayerSource ->
            !s.isTorrent && s.url.isNotBlank() &&
                mirrorHostOf(s.url) !in deadHosts && !StreamProbe.knownBad(s.url)
        }
        // "If I am on MovieBox, play MovieBox's server first": among the servers
        // that can actually play, the one from the extension the title was
        // opened from wins. This is the same preference the detail screen's
        // search applies (the origin is asked first) — it just also has to be
        // honoured at the moment playback commits to a row.
        val origin = sources.indexOfFirst { it.isFromOrigin() && healthy(it) }
        if (origin >= 0) return origin
        val i = sources.indexOfFirst { healthy(it) }
        return if (i >= 0) i else 0
    }

    /** Starts playback — or, when the "don't play directly" setting is on,
     *  opens the grouped server chooser and waits for the user's pick. */
    private fun startOrAsk() {
        lifecycleScope.launch {
            if (downloadPickMode) {
                // "Download this": list the servers and let the user choose.
                showServerChooser(startMode = true, forDownload = true)
            } else if (shouldAskServer()) {
                // The chooser is the destination, so open it even while the
                // list is still empty: the player is already on screen (its own
                // title card sits behind the sheet), and opening now means the
                // user never waits for a slow provider before they can see —
                // and start adding to — the server list. Every server that lands
                // afterwards is appended live (see [sourcesWatchers]).
                showServerChooser(startMode = true)
            } else if (sources.isNotEmpty()) {
                playSource(preferredStartIndex())
            }
        }
    }

    /** True when a server list should stop at the chooser instead of starting
     *  on its own. Reads the persisted setting when the launching screen did
     *  not pass it through, so every entry point (downloads, favourites,
     *  history, a re-open) honours the toggle too. */
    private suspend fun shouldAskServer(): Boolean {
        if (askServerThisLaunch) return true
        if (!askServerPrefLoaded) {
            askServerOnPlay = runCatching {
                (applicationContext as HikariApp).store.askServerOnPlay()
            }.getOrDefault(false)
            askServerPrefLoaded = true
        }
        return askServerOnPlay
    }

    /** Whether a server the user picked that then fails should ask before the
     *  player moves on (Settings → Player → Playback start). Read lazily, like
     *  [shouldAskServer], so an entry point that never fails pays nothing. */
    private suspend fun shouldAskOnFailure(): Boolean {
        if (!failoverAskPrefLoaded) {
            failoverAskOnFailure = runCatching {
                (applicationContext as HikariApp).store.failoverAskOnFailure()
            }.getOrDefault(true)
            failoverAskPrefLoaded = true
        }
        return failoverAskOnFailure
    }

    /** Enters picture-in-picture mode (SDK 26+). The window is sized to the
     *  video's actual aspect ratio (16:9 until the video is known), so the
     *  user gets a properly-proportioned mini window instead of letterboxed
     *  bars. No-ops when already in PiP or when the source is audio-only. */
    @Suppress("DEPRECATION")
    private fun enterPip() {
        if (Build.VERSION.SDK_INT < 26 || inPip) return
        val p = player ?: return
        val hasVideo = p.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO }
        if (!hasVideo) {
            Toast.makeText(this, I18n.t("No video track to keep in the background"), Toast.LENGTH_SHORT).show()
            return
        }
        val builder = PictureInPictureParams.Builder()
        val ratio = if (p.videoSize.width > 0 && p.videoSize.height > 0) {
            Rational(p.videoSize.width, p.videoSize.height)
        } else Rational(16, 9)
        builder.setAspectRatio(ratio)
        if (Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(true)
        try {
            enterPictureInPictureMode(builder.build())
        } catch (t: Throwable) {
            Toast.makeText(this, I18n.t("Picture-in-picture unavailable"), Toast.LENGTH_SHORT).show()
        }
    }

    /** YouTube-style: leaving the player (Home, another app) while video is
     *  actually playing drops into a PiP window instead of stopping playback.
     *  Only used on API 26-30 — API 31+ has setAutoEnterEnabled(true) set in
     *  onCreate, which would make this fire twice. */
    @Suppress("DEPRECATION")
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT in 26..30) {
            if (inPip || isFinishing) return
            if (player?.isPlaying == true) enterPip()
        }
    }

    /** Strip every overlay in PiP so only the video shows in the small window,
     *  and restore the controller / unlock button when back on the full screen. */
    @Suppress("DEPRECATION")
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        val pv = playerView ?: return
        if (isInPictureInPictureMode) {
            hideLoadingBanner(immediate = true)
            pv.useController = false
            pv.hideController()
            unlockBtn?.visibility = View.GONE
            seekFeedback?.visibility = View.GONE
        } else {
            pv.useController = true
            if (controlsLocked) {
                pv.hideController()
                unlockBtn?.visibility = View.VISIBLE
            }
        }
    }

    private fun cycleSpeed() {
        speedIndex = (speedIndex + 1) % SPEEDS.size
        val newSpeed = SPEEDS[speedIndex]
        applySpeed(newSpeed)
        speedChip?.text = "${newSpeed}x"
    }

    private fun cycleRotation() {
        val next = when (requestedOrientation) {
            SCREEN_ORIENTATION_UNSPECIFIED, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> SCREEN_ORIENTATION_PORTRAIT
        }
        // The user has taken over: the once-per-source auto-rotate must not
        // spin the screen back to the video's own orientation afterwards.
        userRotated = true
        requestedOrientation = next
        Toast.makeText(
            this,
            if (next == SCREEN_ORIENTATION_PORTRAIT) I18n.t("Portrait") else I18n.t("Landscape"),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun applySpeed(speed: Float) {
        val p = player ?: return
        p.playbackParameters = p.playbackParameters.withSpeed(speed)
    }

    /** Locks the controls: the media3 controller stays hidden and only the
     *  small top-right unlock button remains touchable (like the reference
     *  player's Lock button), so a locked film is not covered by a padlock. */
    private fun lockControls() {
        controlsLocked = true
        val pv = playerView ?: return
        pv.useController = false
        pv.hideController()
        unlockBtn?.visibility = View.VISIBLE
        hideSystemUi()
    }

    private fun unlockControls() {
        controlsLocked = false
        val pv = playerView ?: return
        pv.useController = true
        unlockBtn?.visibility = View.GONE
        pv.showController()
    }

    /** Toggles the video resize mode between Fit and Crop (zoom to fill). */
    private fun cycleResize() {
        val pv = playerView ?: return
        resizeIndex = (resizeIndex + 1) % 2
        pv.resizeMode = if (resizeIndex == 0) {
            C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        } else {
            C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
        }
        updateResizeButton()
    }

    /** The fit/crop button has no label any more, so the state is shown by the
     *  accent tint (accent = cropping/zoomed, white = fitting). */
    private fun updateResizeButton() {
        resizeBtn?.imageTintList = tintOf(resizeIndex == 0)
    }

    /** White = off, the player accent = on. Used by the mute-style state icons
     *  (resize, favourite) so a toggled control is readable at a glance. */
    private fun tintOf(on: Boolean) = ColorStateList.valueOf(
        if (on) accentMidColor else android.graphics.Color.WHITE
    )

    /** Top-bar heart: add/remove this title from the app's Library. */
    private fun toggleFavourite() {
        val item = favouriteItem ?: return
        val next = !isFavourite
        isFavourite = next
        favBtn?.setImageResource(if (next) R.drawable.ic_heart_filled else R.drawable.ic_heart)
        favBtn?.imageTintList = tintOf(next)
        val app = applicationContext as HikariApp
        app.appScope.launch {
            runCatching {
                if (next) {
                    // Never downgrade an entry the detail screen saved with full
                    // metadata: only add when this title isn't a favourite yet.
                    if (app.store.favorites().none { it.uniqueId == item.uniqueId }) {
                        app.store.addFavorite(item)
                    }
                } else {
                    app.store.removeFavorite(item.uniqueId)
                }
            }
        }
        Toast.makeText(this, if (next) I18n.t("Added to library") else I18n.t("Removed from library"), Toast.LENGTH_SHORT).show()
    }

    private fun toggleController() {
        if (holdingFast || controlsLocked) return
        val pv = playerView ?: return
        if (controllerVisible) pv.hideController() else pv.showController()
    }

    /** Double-tap seek: left half rewinds 10s, right half forwards 10s
     *  (matching the 10s shown on the centre rewind/forward buttons). */
    private fun seekByTap(x: Float) {
        // Locked = watch only: a stray double-tap (very easy with a thumb
        // resting on the screen) must not jump the position.
        if (controlsLocked) return
        val p = player ?: return
        val mid = (playerView?.width ?: resources.displayMetrics.widthPixels) / 2f
        val forward = x >= mid
        val delta = if (forward) 10_000L else -10_000L
        val target = (p.currentPosition + delta)
            .coerceIn(0L, p.duration.takeIf { it > 0L } ?: Long.MAX_VALUE)
        p.seekTo(target)
        playerView?.showController()
        showSeekFeedback(delta)
    }

    /** Flash the double-tap seek indicator (arrow + +10s/−10s) like YouTube. */
    private fun showSeekFeedback(deltaMs: Long) {
        val v = seekFeedback ?: return
        seekIcon?.text = if (deltaMs >= 0) "\u25B6\u25B6" else "\u25C0\u25C0"
        seekText?.text = (if (deltaMs >= 0) "+" else "-") + (kotlin.math.abs(deltaMs) / 1000) + "s"
        v.visibility = View.VISIBLE
        v.animate().cancel()
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(120).withEndAction {
            v.postDelayed({
                v.animate().alpha(0f).setDuration(250).withEndAction {
                    v.visibility = View.GONE
                }.start()
            }, 450)
        }.start()
    }

    /** Starts the brightness/volume HUD for a vertical drag. Which slider shows
     *  depends on where the finger went down: left half = brightness, right
     *  half = volume. */
    private fun beginVerticalGesture() {
        val half = (playerView?.width ?: resources.displayMetrics.widthPixels) / 2f
        verticalMode = if (downX < half) 1 else 2
        hudHideTask?.let { hudHandler.removeCallbacks(it) }
        hudHideTask = null
        val hud = gestureHud ?: return
        hud.animate().cancel()
        hud.alpha = 1f
        if (verticalMode == 1) {
            hudVol?.visibility = View.INVISIBLE
            hudBright?.visibility = View.VISIBLE
            startBrightness = currentBrightness()
            applyBrightness(startBrightness)
        } else {
            hudBright?.visibility = View.INVISIBLE
            hudVol?.visibility = View.VISIBLE
            startVolume = runCatching {
                audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC)
            }.getOrNull() ?: 0
            applyVolume(startVolume)
        }
    }

    /** The window's current brightness, falling back to the system setting for
     *  the common "no override set yet" state (-1). */
    private fun currentBrightness(): Float {
        val win = window.attributes.screenBrightness
        if (win >= 0f) return win.coerceIn(0.02f, 1f)
        val system = runCatching {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrNull() ?: 128
        return (system / 255f).coerceIn(0.02f, 1f)
    }

    private fun applyBrightness(fraction: Float) {
        val f = fraction.coerceIn(0.02f, 1f)
        val lp = window.attributes
        lp.screenBrightness = f
        window.attributes = lp
        setHudFraction(hudBrightFill, hudBrightThumb, hudBrightTrack, f)
        hudBrightValue?.text = "${(f * 100).roundToInt()}%"
    }

    private fun applyVolume(level: Int) {
        val v = level.coerceIn(0, maxVolume)
        runCatching { audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) }
        val fraction = if (maxVolume > 0) v.toFloat() / maxVolume else 0f
        setHudFraction(hudVolFill, hudVolThumb, hudVolTrack, fraction)
        hudVolValue?.text = "${(fraction * 100).roundToInt()}%"
    }

    /** Sizes the slider's gradient fill and parks the white thumb on its top
     *  edge (the fill grows upward from the bottom of the track). */
    private fun setHudFraction(fill: View?, thumb: View?, track: View?, fraction: Float) {
        val h = track?.height ?: 0
        if (h <= 0 || fill == null) return
        val fillPx = (h * fraction.coerceIn(0f, 1f)).toInt().coerceIn(0, h)
        val lp = fill.layoutParams
        if (lp != null && lp.height != fillPx) {
            lp.height = fillPx
            fill.layoutParams = lp
        }
        thumb?.let { it.translationY = -(fillPx - it.height / 2f) }
    }

    /** Fades the gesture HUD out a moment after the finger lifts (cancelled if
     *  the user starts another drag). */
    private fun endVerticalGesture() {
        verticalMode = 0
        hudHideTask?.let { hudHandler.removeCallbacks(it) }
        val task = Runnable {
            hudHideTask = null
            gestureHud?.animate()?.alpha(0f)?.setDuration(220)?.start()
        }
        hudHideTask = task
        hudHandler.postDelayed(task, 700)
    }

    /** "1:39:45" (or "12:34" for sub-hour videos) — the duration badge. */
    private fun formatDurationBadge(ms: Long): String {
        if (ms <= 0L) return ""
        val total = ms / 1000L
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val s = total % 60L
        return if (h > 0L) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    /** The quality badge for a rendered video height. */
    private fun qualityBadgeFor(height: Int): String = when {
        height <= 0 -> ""
        height >= 2000 -> "4K"
        height >= 1000 -> "FHD"
        height >= 700 -> "HD"
        else -> "${height}p"
    }

    /** The accent the player is drawn with: Settings → Appearance & Theme → Player
     *  color, or the app accent while "Match app & player theme" is on. Read
     *  synchronously from the [AccentStore] mirror at the top of onCreate,
     *  BEFORE anything is coloured, so the first frame is already right
     *  (no flash of the default violet). */
    private var accent = HikariAccent.DEFAULT_PLAYER

    /** Cached ARGB ints for [accent]. These are read from layout/draw paths, so
     *  they must be plain fields rather than re-derived on every access. Their
     *  names are the ones the whole player already colours itself from
     *  (spinners, the loading banner, gesture HUD fills, glass-menu chips,
     *  subtitle highlight, error panel) — so changing [accent] recolours all of
     *  it at once. */
    private var accentStartColor: Int = HikariAccent.DEFAULT_PLAYER.start.toArgb()
    private var accentEndColor: Int = HikariAccent.DEFAULT_PLAYER.end.toArgb()
    private var accentMidColor: Int = HikariAccent.DEFAULT_PLAYER.mid.toArgb()

    private fun setAccent(next: HikariAccent) {
        accent = next
        accentStartColor = next.start.toArgb()
        accentEndColor = next.end.toArgb()
        accentMidColor = next.mid.toArgb()
    }

    /** The pill controls whose fill is the accent gradient (recoloured at
     *  runtime, so they follow the picked accent instead of the old XML one). */
    private val accentPillIds = intArrayOf(
        R.id.sources_btn, R.id.skip_btn, R.id.enhance_btn
    )

    /** Every labelled pill in the control bar (accent ones included), so the
     *  Player UI skin can restyle the whole row in one pass. */
    private val pillIds = intArrayOf(
        R.id.speed_btn, R.id.episodes_btn, R.id.sources_btn, R.id.quality_btn,
        R.id.audio_btn, R.id.subs_btn, R.id.rotate_btn, R.id.skip_btn, R.id.enhance_btn
    )

    /** The cyan -> violet player gradient as a shape (the signature accent). */
    private fun accentShape(radiusDp: Float): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(accentStartColor, accentEndColor)
    ).apply { cornerRadius = radiusDp * resources.displayMetrics.density }

    /** [color] with its alpha replaced by [fraction] — for translucent accents. */
    private fun withAlpha(color: Int, fraction: Float): Int =
        (color and 0x00FFFFFF) or (fraction.coerceIn(0f, 1f) * 255f).roundToInt().shl(24)

    // ---- Accent palette (Settings → Appearance & Theme) ----------------------------

    /** The accent gradient as a shape (the player's signature fill). */
    private fun accentBadgeDrawable(radiusDp: Float): Drawable = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(accentStartColor, accentEndColor)
    ).apply { cornerRadius = radiusDp * resources.displayMetrics.density }

    /** A ripple over the accent gradient — the accent pill (sources/skip/enhance).
     *  [radiusDp] comes from the active Player UI skin, so the accent pills keep
     *  the same corner language as their neighbours on every skin. */
    private fun accentPillRipple(radiusDp: Float = 22f): Drawable = RippleDrawable(
        ColorStateList.valueOf(0x4DFFFFFF.toInt()),
        accentShape(radiusDp),
        null
    )

    /**
     * The big centre play button, in the active skin's treatment:
     *
     *  - RING  (Glass)  — accent gradient ring around a translucent dark disc.
     *  - PLAIN (Minimal)— just a dark disc; no ring, no decoration.
     *  - SOLID (Cinema) — the whole button is the accent gradient.
     *  - GLOW  (Neon)   — a thicker ring inside a soft accent halo.
     */
    private fun playRingDrawable(): Drawable {
        val d = resources.displayMetrics.density
        val treatment = PlayerSkins.spec(skin).playTreatment
        val discColor = 0x66060A14.toInt()
        val ripple = ColorStateList.valueOf(0x4DFFFFFF.toInt())

        if (treatment == PlayerSkins.PlayTreatment.SOLID) {
            val solid = GradientDrawable(
                GradientDrawable.Orientation.BL_TR,
                intArrayOf(accentStartColor, accentEndColor)
            ).apply { shape = GradientDrawable.OVAL }
            return RippleDrawable(ripple, solid, null)
        }

        val ring = GradientDrawable(
            GradientDrawable.Orientation.BL_TR,
            intArrayOf(accentStartColor, accentEndColor)
        ).apply { shape = GradientDrawable.OVAL }
        val disc = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(discColor)
        }

        if (treatment == PlayerSkins.PlayTreatment.PLAIN) {
            return RippleDrawable(ripple, disc, null)
        }

        if (treatment == PlayerSkins.PlayTreatment.GLOW) {
            // halo (accent at ~22% alpha), then ring, then disc.
            val halo = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(withAlpha(accentMidColor, 0.22f))
            }
            val layers = LayerDrawable(arrayOf(halo, ring, disc))
            val ringInset = (5f * d).roundToInt()
            val discInset = (8f * d).roundToInt()
            layers.setLayerInset(1, ringInset, ringInset, ringInset, ringInset)
            layers.setLayerInset(2, discInset, discInset, discInset, discInset)
            return RippleDrawable(ripple, layers, null)
        }

        val layers = LayerDrawable(arrayOf(ring, disc))
        val inset = (3f * d).roundToInt()
        layers.setLayerInset(1, inset, inset, inset, inset)
        return RippleDrawable(ripple, layers, null)
    }

    /**
     * Applies the Player UI skin (Settings → Player → Player UI) to the already
     * inflated controller. Called once from onCreate (after [applyAccentPalette]
     * would have run) and again from [applyControlLayout], because a pill that
     * moves between containers has to be restyled with its neighbours.
     *
     * The skin only touches presentation — backgrounds, paddings, margins, text
     * sizes and the play button's decoration — so no button can be lost or
     * rebound by picking a skin, and media3's layout ids and ordering (which its
     * own PlayerControlViewLayoutManager relies on) are untouched.
     *
     * Ends by calling [applyAccentPalette], which repaints the accent-coloured
     * pieces on top of the skin (accent has to win over the skin's own fills).
     */
    private fun applyPlayerSkin() {
        val spec = PlayerSkins.spec(skin)
        val d = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * d).roundToInt()

        val topBar = findViewById<View>(R.id.player_top_bar)
        val bottomBar = exoView("exo_bottom_bar")

        // Bar backgrounds. Minimal has no panel at all, just a feather-light
        // scrim so the title and the time labels stay readable over bright video.
        for ((bar, drawable, bottomAnchored) in listOf(
            Triple(topBar, spec.topBarBackground, false),
            Triple(bottomBar, spec.bottomBarBackground, true),
        )) {
            if (bar == null) continue
            bar.background = when {
                drawable != 0 -> ContextCompat.getDrawable(this, drawable)
                PlayerSkins.normalize(skin) == PlayerSkins.MINIMAL -> softScrim(bottomAnchored)
                else -> null
            }
        }

        // Floating decks (Neon): keep the panels clear of the screen edges, and
        // give the top bar a little headroom so the rounded chip never clips.
        val deckMargin = dp(spec.deckMarginDp)
        for (bar in listOfNotNull(topBar, bottomBar)) {
            (bar.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.marginStart = deckMargin
                lp.marginEnd = deckMargin
                lp.topMargin = if (bar === topBar) deckMargin else 0
                lp.bottomMargin = if (bar === bottomBar) deckMargin else 0
                bar.layoutParams = lp
            }
        }
        topBar?.let { it.setPadding(it.paddingLeft, it.paddingTop, it.paddingRight, dp(spec.topBarPadBottom)) }

        // The pill row: background, text size, inner padding and the gap between
        // pills. A pill that has been compacted into the top bar is skipped — it
        // is drawn as a round icon button there, exactly like its neighbours.
        for (id in pillIds) {
            val v = findViewById<TextView>(id) ?: continue
            if ((v.parent as? View)?.id == R.id.player_top_actions) continue
            v.background = ContextCompat.getDrawable(this, spec.pillBackground)
            v.setTextSize(TypedValue.COMPLEX_UNIT_DIP, spec.pillTextDp)
            v.setPadding(dp(spec.pillPadH), dp(spec.pillPadV), dp(spec.pillPadH), dp(spec.pillPadV))
            (v.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.marginStart = dp(spec.pillMargin)
                lp.marginEnd = dp(spec.pillMargin)
                v.layoutParams = lp
            }
        }

        // The centre play button: size + the inset of its glyph (a solid fill
        // needs a bigger inset than a ring, or the icon overflows the disc).
        exoView("exo_play_pause")?.let { v ->
            val side = dp(spec.playSizeDp)
            val lp = v.layoutParams
            lp.width = side
            lp.height = side
            v.layoutParams = lp
            if (v is ImageButton) {
                val pad = when (spec.playTreatment) {
                    PlayerSkins.PlayTreatment.SOLID -> dp((spec.playSizeDp * 0.26f).roundToInt())
                    PlayerSkins.PlayTreatment.GLOW -> dp((spec.playSizeDp * 0.21f).roundToInt())
                    PlayerSkins.PlayTreatment.PLAIN -> dp((spec.playSizeDp * 0.19f).roundToInt())
                    PlayerSkins.PlayTreatment.RING -> dp((spec.playSizeDp * 0.17f).roundToInt())
                }
                v.setPadding(pad, pad, pad, pad)
            }
        }

        applyAccentPalette()
    }

    /** A single-colour vertical fade used by the Minimal skin's bars. */
    private fun softScrim(bottomAnchored: Boolean): Drawable = GradientDrawable(
        if (bottomAnchored) GradientDrawable.Orientation.BOTTOM_TOP
        else GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(0x99000000.toInt(), 0x00000000),
    )

    /**
     * Repaints the parts of the player that are coloured by the accent but could
     * not be derived from [accentStartColor] automatically, because they are
     * drawables baked into XML (@drawable/pill_accent, /badge_accent,
     * /player_play_ring, /hud_fill and the @color/hikari_accent_mid labels,
     * spinners and progress bar). Called once the preference has been read, and
     * again after any control-layout change (a pill moved out of the top bar
     * gets its accent fill back).
     */
    private fun applyAccentPalette() {
        val d = resources.displayMetrics.density

        // Accent pills — skipped while compacted into the top bar, where they
        // are drawn as plain glass round buttons like their neighbours. The
        // corner radius follows the active skin.
        val accentRadius = PlayerSkins.spec(skin).accentPillRadius
        for (id in accentPillIds) {
            val v = findViewById<TextView>(id) ?: continue
            if ((v.parent as? View)?.id == R.id.player_top_actions) continue
            v.background = accentPillRipple(accentRadius)
        }

        // The highlighted metadata badge (video quality).
        findViewById<TextView>(R.id.badge_quality)?.background = accentBadgeDrawable(11f)
        // The centre play/pause ring.
        exoView("exo_play_pause")?.background = playRingDrawable()

        // Gesture-HUD fills (brightness / volume).
        val fill = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(accentStartColor, accentEndColor)
        ).apply { cornerRadius = 5f * d }
        hudBrightFill?.background = fill
        hudVolFill?.background = fill.constantState?.newDrawable() ?: fill

        // Progress bar: the played portion + scrubber follow the accent.
        (exoView("exo_progress") as? androidx.media3.ui.DefaultTimeBar)
            ?.setPlayedColor(accentMidColor)

        // Labels/spinners the layout colours from @color/hikari_accent_mid, which
        // no runtime accent can reach.
        loadingEpisode?.setTextColor(accentMidColor)
        seekText?.setTextColor(withAlpha(accentMidColor, 0.95f))
        findViewById<View>(R.id.loading_banner)?.let { tintProgressBars(it) }
        findViewById<View>(R.id.loading_spinner)?.let { tintProgressBars(it) }
    }

    private fun tintProgressBars(v: View) {
        if (v is ProgressBar) {
            v.indeterminateTintList = ColorStateList.valueOf(accentMidColor)
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) tintProgressBars(v.getChildAt(i))
        }
    }

    /**
     * Looks up a view by an id that media3-ui declares in ITS OWN resources
     * (`exo_play_pause`, `exo_progress`, …). The app is built with
     * `android.nonTransitiveRClass=true`, so `R.id` here only holds this app's
     * own ids — the library's ids live in the merged resource table, where the
     * name still resolves at runtime. Resolving by name therefore reaches the
     * same view the library inflated, without hard-coding a library R class.
     */
    private fun exoView(name: String): View? {
        val id = resources.getIdentifier(name, "id", packageName)
        return if (id != 0) findViewById<View>(id) else null
    }

    // ---- Control layout (Settings → Player → Player controls) --------------

    /** Where each movable button goes. Defaults reproduce the shipped layout, so
     *  an install that never opens the editor is unchanged. */
    private var controlLayout: Map<PlayerControl, PlayerControlSlot> =
        PlayerControlsConfig.defaults()

    /** Original look of a pill, stashed the first time it is compacted for the
     *  top bar, so moving it back to a bottom row restores it exactly. */
    private class PillOriginal(
        val text: CharSequence,
        val start: Drawable?,
        val end: Drawable?,
        val background: Drawable?,
        val padStart: Int,
        val padTop: Int,
        val padEnd: Int,
        val padBottom: Int,
        val params: ViewGroup.LayoutParams,
    )

    private val pillOriginals = HashMap<Int, PillOriginal>()

    /** The layout order inside each slot. Matches the XML order, so the default
     *  layout comes out exactly as shipped (and the resize button stays pinned
     *  at the far right, after the enhance pill). */
    private val controlOrder = listOf(
        PlayerControl.FAVORITE, PlayerControl.DOWNLOAD, PlayerControl.PIP,
        PlayerControl.OPTIONS, PlayerControl.LOCK,
        PlayerControl.SPEED, PlayerControl.EPISODES, PlayerControl.SOURCES,
        PlayerControl.QUALITY, PlayerControl.AUDIO, PlayerControl.SUBS,
        PlayerControl.ROTATE, PlayerControl.SKIP,
        PlayerControl.ENHANCE, PlayerControl.RESIZE,
    )

    private fun controlView(c: PlayerControl): View? = when (c) {
        PlayerControl.FAVORITE -> findViewById(R.id.fav_btn)
        PlayerControl.DOWNLOAD -> findViewById(R.id.download_btn)
        PlayerControl.PIP -> findViewById(R.id.pip_btn)
        PlayerControl.OPTIONS -> findViewById(R.id.options_btn)
        PlayerControl.LOCK -> findViewById(R.id.lock_btn)
        PlayerControl.SPEED -> findViewById(R.id.speed_btn)
        PlayerControl.EPISODES -> findViewById(R.id.episodes_btn)
        PlayerControl.SOURCES -> findViewById(R.id.sources_btn)
        PlayerControl.QUALITY -> findViewById(R.id.quality_btn)
        PlayerControl.AUDIO -> findViewById(R.id.audio_btn)
        PlayerControl.SUBS -> findViewById(R.id.subs_btn)
        PlayerControl.ROTATE -> findViewById(R.id.rotate_btn)
        PlayerControl.SKIP -> findViewById(R.id.skip_btn)
        PlayerControl.RESIZE -> findViewById(R.id.resize_btn)
        PlayerControl.ENHANCE -> findViewById(R.id.enhance_btn)
    }

    /**
     * Puts every button in its configured slot. Buttons the player itself hides
     * (Episodes on a movie, PiP below API 26) stay hidden whatever the layout
     * says — the setting can never resurrect a dead button.
     */
    private fun applyControlLayout() {
        val top = findViewById<ViewGroup>(R.id.player_top_actions) ?: return
        val left = findViewById<ViewGroup>(R.id.player_pills) ?: return
        val right = findViewById<ViewGroup>(R.id.player_right_actions) ?: return

        val managedHidden = listOf(R.id.episodes_btn, R.id.pip_btn).filter {
            findViewById<View>(it)?.visibility == View.GONE
        }

        for (c in controlOrder) {
            val v = controlView(c) ?: continue
            (v.parent as? ViewGroup)?.removeView(v)
            when (controlLayout[c] ?: c.defaultSlot) {
                PlayerControlSlot.TOP_BAR -> {
                    top.addView(v)
                    compactForTopBar(v)
                    v.visibility = View.VISIBLE
                }
                PlayerControlSlot.BOTTOM_LEFT -> {
                    left.addView(v)
                    restorePill(v)
                    v.visibility = View.VISIBLE
                }
                PlayerControlSlot.BOTTOM_RIGHT -> {
                    right.addView(v)
                    restorePill(v)
                    v.visibility = View.VISIBLE
                }
                PlayerControlSlot.HIDDEN -> {
                    // Kept in the pill row (GONE) so the view tree stays stable
                    // and unhiding it later is a plain visibility flip.
                    left.addView(v)
                    restorePill(v)
                    v.visibility = View.GONE
                }
            }
        }
        for (id in managedHidden) findViewById<View>(id)?.visibility = View.GONE

        // Keep the pill row centred: its left spacer mirrors the width of the
        // right container, which the loop above may have resized.
        right.post { syncLeftSpacer() }

        // A pill that just moved out of the top bar needs its accent fill back.
        applyPlayerSkin()

        // Start the scrollable pill row at its left edge, never wherever a
        // focus jump (media3's control view) left it.
        resetPillScroll()
    }

    /** Puts the pill row back at its left edge. */
    private fun resetPillScroll() {
        val sc = findViewById<HorizontalScrollView>(R.id.player_pill_scroll) ?: return
        if (sc.scrollX != 0) sc.scrollTo(0, 0)
    }

    private fun syncLeftSpacer() {
        val right = findViewById<View>(R.id.player_right_actions) ?: return
        val spacer = findViewById<View>(R.id.player_left_spacer) ?: return
        // Mirroring the right container's width keeps a centred pill row on the
        // screen's centre line — but only while the pills fit. When the row is
        // wider than the space left for it (portrait, with eight pills) the
        // mirror is dead weight that eats another ~31dp of a strip that already
        // has to be scrolled, so the spacer drops back to its minimum instead.
        val pills = findViewById<View>(R.id.player_pills)
        val scroll = findViewById<HorizontalScrollView>(R.id.player_pill_scroll)
        val overflows = pills != null && scroll != null && scroll.measuredWidth > 0 &&
            pills.measuredWidth > scroll.measuredWidth
        val target = if (overflows) (2 * resources.displayMetrics.density).roundToInt()
        else right.measuredWidth
        if (target <= 0) return
        val lp = spacer.layoutParams
        if (lp.width != target) {
            lp.width = target
            spacer.layoutParams = lp
        }
    }

    /**
     * A pill drawn in the top bar becomes a compact round icon button: the top
     * bar is a single non-scrolling row, so a wide labelled pill there could
     * push the buttons off the screen. The original look is stashed and restored
     * by [restorePill] when the button leaves the top bar.
     */
    private fun compactForTopBar(v: View) {
        if (v !is TextView) return
        val orig = pillOriginals.getOrPut(v.id) {
            PillOriginal(
                text = v.text,
                start = v.compoundDrawablesRelative.getOrNull(0),
                end = v.compoundDrawablesRelative.getOrNull(2),
                background = v.background,
                padStart = v.paddingStart,
                padTop = v.paddingTop,
                padEnd = v.paddingEnd,
                padBottom = v.paddingBottom,
                params = ViewGroup.LayoutParams(v.layoutParams),
            )
        }
        val d = resources.displayMetrics.density
        val side = (26f * d).roundToInt()
        v.text = ""
        v.setCompoundDrawablesRelativeWithIntrinsicBounds(orig.start, null, null, null)
        v.background = ContextCompat.getDrawable(this, R.drawable.circle_glass_ripple)
        v.setPadding(0, 0, 0, 0)
        v.gravity = android.view.Gravity.CENTER
        v.visibility = View.VISIBLE
        val lp = v.layoutParams
        lp.width = side
        lp.height = side
        if (lp is ViewGroup.MarginLayoutParams) {
            lp.marginStart = (3f * d).roundToInt()
            lp.marginEnd = 0
        }
        v.layoutParams = lp
    }

    private fun restorePill(v: View) {
        if (v !is TextView) return
        val orig = pillOriginals[v.id] ?: return
        v.text = orig.text
        v.setCompoundDrawablesRelativeWithIntrinsicBounds(orig.start, null, orig.end, null)
        v.background = orig.background
        v.setPadding(orig.padStart, orig.padTop, orig.padEnd, orig.padBottom)
        v.layoutParams = orig.params
    }

    // ---- Video enhance (Settings → Player → Video enhance) -----------------

    private var enhancePresetKey: String = EnhancePreset.DEFAULT.key
    private var appliedEnhanceKey: String? = null
    private var appliedEnhanceHdr: Boolean? = null

    /** Set when the device/stream refused the effects pipeline, so the menu can
     *  say so instead of silently doing nothing. */
    private var enhanceUnsupported = false

    /**
     * True when THIS player instance's video renderer was enabled with an
     * effects pipeline attached. media3 can only attach one while the renderer
     * is being enabled (it builds the video sink from the effect list present at
     * that instant — see MediaCodecVideoRenderer.onEnabled), so this flag says
     * whether a preset can be applied live or needs the source re-opened.
     * Reset for every new player instance in [playDirectInner].
     */
    private var videoSinkArmed = false

    /**
     * Hands the chosen preset to media3's video-effects pipeline. Idempotent:
     * it only talks to the player when the preset or the video's HDR-ness really
     * changed, so it is safe to call from onTracksChanged.
     *
     * Colour grading is applied to the decoded frames on the GPU, and the
     * matrix-based effects cannot touch HDR video at all (media3 asserts on
     * it), so the HDR part of a preset is dropped automatically — a 4K HDR
     * stream can never be broken by picking a preset.
     */
    private fun applyVideoEnhance(force: Boolean = false) {
        val p = player ?: return
        val preset = EnhancePreset.fromKey(enhancePresetKey)
        val hdr = isCurrentVideoHdr()
        if (!force && preset.key == appliedEnhanceKey && hdr == appliedEnhanceHdr) return
        appliedEnhanceKey = preset.key
        appliedEnhanceHdr = hdr
        // Nothing to apply and no pipeline to apply it to: skip the call
        // entirely, so Natural can never drag an unused GL pass into playback.
        if (preset == EnhancePreset.NATURAL && !videoSinkArmed) return
        runCatching { p.setVideoEffects(preset.effects(hdr)) }
            .onFailure {
                enhanceUnsupported = true
                com.hikari.app.data.Logs.logError("Player", "video effects unavailable", it)
                android.util.Log.w("HikariPlayer", "video effects unavailable", it)
            }
    }

    /** True while the stream on screen is HDR (PQ/HLG, or BT.2020 primaries). */
    private fun isCurrentVideoHdr(): Boolean {
        val tracks = player?.currentTracks ?: return false
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            val mediaGroup = group.mediaTrackGroup
            for (i in 0 until mediaGroup.length) {
                val ci = mediaGroup.getFormat(i).colorInfo ?: continue
                if (ci.colorTransfer == C.COLOR_TRANSFER_ST2084 ||
                    ci.colorTransfer == C.COLOR_TRANSFER_HLG ||
                    ci.colorSpace == C.COLOR_SPACE_BT2020
                ) {
                    return true
                }
            }
        }
        return false
    }

    private fun setEnhancePreset(preset: EnhancePreset) {
        enhancePresetKey = preset.key
        val needsPipeline = preset != EnhancePreset.NATURAL
        val p = player
        if (needsPipeline && !enhanceUnsupported && !videoSinkArmed &&
            p != null && currentIndex in sources.indices
        ) {
            // The player was opened on Natural, so media3 never created the
            // effects pipeline — and a running renderer cannot be given one
            // later (the factory is only consulted while it is being enabled).
            // Re-open the SAME source with the pipeline armed, keeping the
            // position in the film, so the preset actually reaches the screen.
            val position = p.currentPosition
            if (position > 2_000L) {
                startPositionMs = position
                seekPending = true
            }
            // Let onTracksChanged re-apply the exact SDR/HDR effect list once
            // the new player knows the tracks.
            appliedEnhanceKey = null
            appliedEnhanceHdr = null
            noSubsRetry = false
            com.hikari.app.data.Logs.log(
                "Player",
                "arming video effects pipeline for ${preset.key} (reopening current source)"
            )
            Toast.makeText(this, I18n.t("Applying %s…").replace("%s", preset.label), Toast.LENGTH_SHORT).show()
            playSource(currentIndex)
            lifecycleScope.launch {
                runCatching {
                    (applicationContext as HikariApp).store.setEnhancePreset(preset.key)
                }
            }
            return
        }
        applyVideoEnhance(force = true)
        if (enhanceUnsupported && preset != EnhancePreset.NATURAL) {
            Toast.makeText(
                this,
                I18n.t("This device can't apply video effects — the preset was skipped."),
                Toast.LENGTH_SHORT
            ).show()
        } else if (needsPipeline) {
            Toast.makeText(this, I18n.t("%s applied").replace("%s", preset.label), Toast.LENGTH_SHORT).show()
        }
        lifecycleScope.launch {
            runCatching {
                (applicationContext as HikariApp).store.setEnhancePreset(preset.key)
            }
        }
    }

    /** The Enhance button's menu: every preset, with the active one ticked. */
    private fun showEnhanceMenu() {
        val presets = EnhancePreset.entries
        val current = EnhancePreset.fromKey(enhancePresetKey)
        showGlassMenu(
            I18n.t("Video enhance"),
            presets.map { p ->
                GlassOption(
                    label = p.label,
                    sub = p.desc,
                    iconRes = R.drawable.ic_enhance,
                    marker = RowMarker.ICON,
                    selected = p == current,
                )
            },
            hint = if (enhanceUnsupported) {
                "Not available on this device — its video pipeline refused " +
                    "media3's effects engine, so Natural is used instead."
            } else {
                "Realtime colour grading of the video itself. " +
                    "Natural applies nothing at all."
            },
            iconRes = R.drawable.ic_enhance,
        ) { which ->
            val picked = presets.getOrNull(which) ?: return@showGlassMenu
            setEnhancePreset(picked)
        }
    }

    /**
     * Sets a [TextView]'s size in dp — deliberately NOT sp — so the player's
     * overlay chrome keeps the reference design's compact proportions even when
     * the phone's system font size is turned up. The video overlay is chrome,
     * not body copy, so it should not follow the text-accessibility scale:
     * that scaling is what made every menu and pill read as oversized.
     */
    private fun TextView.dpText(sizeDp: Float) {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sizeDp * resources.displayMetrics.density)
    }

    /** How a glass menu row draws its leading marker. */
    private enum class RowMarker { RADIO, ICON, NONE }

    /**
     * One row of a glass menu. A row is a rounded glass capsule carrying a
     * primary [label], an optional secondary [sub] line, an optional
     * right-aligned [badge] pill (a bitrate, a codec, a source type…) and a
     * leading marker — a radio disc for "pick one" lists, [iconRes] in a glass
     * disc for action lists, nothing at all for plain text.
     *
     * A [selected] row is the cyan -> violet tint with a gradient stroke, a
     * filled radio dot and a plain white checkmark on the right, which is how
     * the quality/audio/subtitle pickers show the active track.
     */
    private class GlassOption(
        val label: String,
        val sub: String? = null,
        val badge: String? = null,
        val iconRes: Int = 0,
        val chevron: Boolean = false,
        val selected: Boolean = false,
        val marker: RowMarker = RowMarker.RADIO,
    ) {
        /** The same row with a different selection state. */
        fun withSelected(value: Boolean): GlassOption = GlassOption(
            label, sub, badge, iconRes, chevron, value, marker
        )
    }

    /** One pickable media track, flattened out of media3's Traks so a menu can
     *  be built (and its selection state decided) in a single pass. */
    private data class TrackRow(
        val label: String,
        val sub: String?,
        val badge: String?,
        val group: Tracks.Group,
        val index: Int,
    )

    /** A remembered subtitle/audio choice: the track's own identity (the format
     *  language/label the provider or manifest declared), plus the position it
     *  had in the list, so it can be found again on a rebuilt track list. */
    private data class TrackPick(
        val type: Int,
        val lang: String?,
        val label: String?,
        val index: Int,
    ) {
        fun matches(format: androidx.media3.common.Format, i: Int): Boolean {
            if (!lang.isNullOrBlank() && format.language == lang) return true
            if (!label.isNullOrBlank() && format.label == label) return true
            return lang.isNullOrBlank() && label.isNullOrBlank() && i == index
        }
    }

    /** "~2.6 Mbps" / "~759 kbps" — the data-use badge on a quality row. */
    private fun bitrateBadge(bitsPerSecond: Long): String? = when {
        bitsPerSecond <= 0L -> null
        bitsPerSecond >= 1_000_000L ->
            "~" + String.format(
                java.util.Locale.US, "%.1f", Math.floor(bitsPerSecond / 100_000.0) / 10.0
            ) + " Mbps"
        else -> "~" + ((bitsPerSecond + 500L) / 1000L) + " kbps"
    }

    /** "AAC" / "SRT" / "TTML" … — a short badge for a track's mime type. */
    private fun codecBadge(mime: String?): String? = when {
        mime.isNullOrBlank() -> null
        mime.contains("subrip", true) -> "SRT"
        mime.contains("vtt", true) -> "VTT"
        mime.contains("ssa", true) -> "ASS"
        mime.contains("ttml", true) -> "TTML"
        mime.contains("mp4a", true) -> "AAC"
        mime.contains("eac3", true) -> "E-AC-3"
        mime.contains("ac3", true) -> "AC-3"
        mime.contains("opus", true) -> "Opus"
        mime.contains("vorbis", true) -> "Vorbis"
        mime.contains("flac", true) -> "FLAC"
        else -> null
    }

    /** "Stereo" / "5.1" — a short badge for an audio track's channel layout. */
    private fun channelsBadge(count: Int): String? = when (count) {
        0 -> null
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> "${count}ch"
    }

    /** The display host of [url] ("cdn.example.com"), or null when there is none. */
    private fun hostOf(url: String): String? =
        runCatching { Uri.parse(url).host }.getOrNull()
            ?.removePrefix("www.")?.takeIf { it.isNotBlank() }

    /** The language of a track as the user would name it ("English"), or null. */
    private fun languageOf(language: String?): String? {
        if (language.isNullOrBlank()) return null
        val pretty = runCatching {
            java.util.Locale(language).getDisplayLanguage(java.util.Locale.ENGLISH)
        }.getOrNull()
        return pretty?.takeIf { it.isNotBlank() && !it.equals(language, true) } ?: language
    }

    /** A track's secondary line, or null when it would just be noise — a bare
     *  format id ("1", "1/8219"), a blank label, or a repeat of [primary]. At
     *  least two letters are required, so id-ish strings never become a row's
     *  subtitle (that used to print stray "1/8219" lines under the labels). */
    private fun trackSub(primary: String, vararg candidates: String?): String? =
        candidates.asSequence()
            .mapNotNull { it?.takeIf { c -> c.isNotBlank() && c != primary } }
            .firstOrNull { c -> c.count { ch -> ch.isLetter() } >= 2 }

    /** A usable display name for a media track: [label] when it reads like a
     *  name, else "Track N" — some streams expose only bare ids ("1/8219"). */
    private fun trackLabel(label: String?, fallbackIndex: Int): String =
        label?.takeIf { it.count { ch -> ch.isLetter() } >= 2 } ?: "Track ${fallbackIndex + 1}"

    /** A small glass pill: the right-aligned value badge on a row. */
    private fun glassPill(text: String, sizeDp: Float = 8.5f): TextView {
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            this.text = text
            dpText(sizeDp)
            includeFontPadding = false
            setTextColor(0xFFC9D2E0.toInt())
            gravity = Gravity.CENTER
            setPadding(
                (6 * density).toInt(), (2 * density).toInt(),
                (6 * density).toInt(), (2 * density).toInt()
            )
            // No outline: the badge reads as a soft grey chip sitting on the
            // row, exactly like the reference player's bitrate pills.
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999f
                setColor(0x1FFFFFFF)
            }
        }
    }

    /** The plain white check that marks the active row. */
    private fun checkMark(): View {
        val density = resources.displayMetrics.density
        return ImageView(this).apply {
            setImageResource(R.drawable.ic_check)
            imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(
                (12 * density).toInt(), (12 * density).toInt()
            ).apply { marginStart = (6 * density).toInt() }
        }
    }

    /** The leading marker of a row, or null for [RowMarker.NONE]. */
    private fun rowMarker(option: GlassOption): View? {
        val density = resources.displayMetrics.density
        val size = (16 * density).toInt()
        return when (option.marker) {
            RowMarker.RADIO -> {
                // The reference player's radio: a filled gradient disc with a
                // small white dot when active, a light hollow ring otherwise.
                val marker = if (option.selected) {
                    LayerDrawable(
                        arrayOf(
                            accentShape(10f).apply { shape = GradientDrawable.OVAL },
                            GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(0xFFFFFFFF.toInt())
                            }
                        )
                    ).apply {
                        val inset = (4.5f * density).roundToInt()
                        setLayerInset(1, inset, inset, inset, inset)
                    }
                } else {
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0x00000000)
                        setStroke((1.5f * density).roundToInt().coerceAtLeast(1), 0x8CFFFFFF.toInt())
                    }
                }
                View(this).apply {
                    background = marker
                    layoutParams = LinearLayout.LayoutParams(size, size)
                        .apply { marginEnd = (10 * density).toInt() }
                }
            }
            RowMarker.ICON -> {
                if (option.iconRes == 0) {
                    null
                } else {
                    val disc = (26 * density).toInt()
                    FrameLayout(this).apply {
                        layoutParams = LinearLayout.LayoutParams(disc, disc)
                            .apply { marginEnd = (10 * density).toInt() }
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(0x1FFFFFFF)
                            setStroke((1 * density).toInt().coerceAtLeast(1), 0x2EFFFFFF)
                        }
                        addView(ImageView(this@PlayerActivity).apply {
                            setImageResource(option.iconRes)
                            imageTintList = ColorStateList.valueOf(0xFFE6EAF3.toInt())
                            scaleType = ImageView.ScaleType.CENTER_INSIDE
                            setPadding(
                                (6 * density).toInt(), (6 * density).toInt(),
                                (6 * density).toInt(), (6 * density).toInt()
                            )
                        }, FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        ))
                    }
                }
            }
            RowMarker.NONE -> null
        }
    }

    /**
     * One tappable row of the glass menus. [onClick] null draws a static row
     * (used for the read-only rows a menu may need).
     */
    private fun glassRow(option: GlassOption, onClick: (() -> Unit)?): View {
        val density = resources.displayMetrics.density
        // Rows are capsules: the radius is deliberately larger than half the
        // row height, so the shape is clamped to a stadium and every row reads
        // as a pill — the "curved" look the whole player menu set uses.
        val rowShape = if (option.selected) {
            GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(withAlpha(accentStartColor, 0.30f), withAlpha(accentEndColor, 0.34f))
            ).apply {
                cornerRadius = 999f
                setStroke(
                    (1.5f * density).roundToInt().coerceAtLeast(1),
                    withAlpha(accentMidColor, 0.85f)
                )
            }
        } else {
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999f
                setColor(0x14FFFFFF.toInt())
            }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = onClick != null
            isFocusable = onClick != null
            setPadding(
                (10 * density).toInt(), (6 * density).toInt(),
                (10 * density).toInt(), (6 * density).toInt()
            )
            background = if (onClick == null) rowShape
            else RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), rowShape, null)
        }
        rowMarker(option)?.let { row.addView(it) }
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@PlayerActivity).apply {
                text = option.label
                dpText(11.5f)
                // One line, always. A row is a capsule, so its HEIGHT decides how
                // round it reads (the corner radius is clamped to half of it), and
                // a label that wrapped to a second line made those rows a visibly
                // fatter, rounder pill than the rows beside them — the long
                // "Provider (Repo) · Plugin" server names sat next to the short
                // CloudStream ones and looked like a different component. The tail
                // is ellipsised instead of pushing the capsule taller.
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(if (option.selected) 0xFFFFFFFF.toInt() else 0xFFDCE3EE.toInt())
            })
            option.sub?.takeIf { it.isNotBlank() }?.let { sub ->
                addView(TextView(this@PlayerActivity).apply {
                    text = sub
                    dpText(9f)
                    // Same rule for the secondary line, so every row on screen is
                    // exactly one label line plus one sub line: identical capsules.
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    includeFontPadding = false
                    setTextColor(0xFF98A3B5.toInt())
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (1 * density).toInt() })
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        option.badge?.takeIf { it.isNotBlank() }?.let { badge ->
            row.addView(glassPill(badge), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (7 * density).toInt() })
        }
        if (option.selected) {
            row.addView(checkMark())
        } else if (option.chevron) {
            row.addView(TextView(this).apply {
                text = "\u203A"
                dpText(13f)
                includeFontPadding = false
                setTextColor(0xFF7E8AA0.toInt())
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (5 * density).toInt() })
        }
        if (onClick != null) row.setOnClickListener { onClick() }
        return row
    }

    /** A centred row container matching [showGlassMenu]'s list padding. */
    private fun optionList(): LinearLayout {
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (8 * density).toInt(), (4 * density).toInt(),
                (8 * density).toInt(), (2 * density).toInt()
            )
        }
    }

    /** Adds [option] as a row to [list] using the shared row style. */
    private fun addOptionRow(list: LinearLayout, option: GlassOption, onClick: (() -> Unit)?) {
        val density = resources.displayMetrics.density
        list.addView(
            glassRow(option, onClick),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (3 * density).toInt() }
        )
    }

    /** Adds a plain labelled row to [list]. */
    private fun addOptionRow(list: LinearLayout, label: String, selected: Boolean, onClick: (() -> Unit)?) {
        addOptionRow(list, GlassOption(label, selected = selected), onClick)
    }

    /** The window's CURRENT size, in px.
     *
     *  [resources.displayMetrics] is not that: it reports the display's natural
     *  (portrait) metrics, so in the landscape player it answers 1080x2460 even
     *  though the window is 2460x1080. Every "shrink to fit the screen" cap
     *  below was therefore measured against the wrong axis and never bit, which
     *  is why the dialogs grew past the bottom of the video. WindowMetrics (API
     *  30+) and getRealSize both follow the current rotation. */
    private fun windowSize(): Point {
        val density = resources.displayMetrics.density
        val size = Point()
        // The activity's own window is the authority: it is exactly the area a
        // dialog has to fit inside.
        val decor = window?.decorView
        if (decor != null && decor.width > 0 && decor.height > 0) {
            size.set(decor.width, decor.height)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
            size.set(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(size)
        }
        // Cross-check with the configuration, which always follows the current
        // rotation: on some devices `currentWindowMetrics` answers with the
        // display's NATURAL (portrait) bounds even while the activity sits in
        // landscape. Trusting that made win.y 2460 inside a 1080-tall window,
        // so every cap below ("0.58 of the window", "the window minus chrome")
        // never bit: the panel grew past 1400px, hung off the bottom of the
        // screen, and its scroll view ended up taller than its own content —
        // i.e. a subtitle sheet whose last rows were unreachable and which
        // could not be scrolled at all. Taking the smaller figure per axis is
        // the safe side: on a correct device the two agree, and this one is
        // wrong in the too-large direction only.
        val cfgW = (resources.configuration.screenWidthDp * density).toInt()
        val cfgH = (resources.configuration.screenHeightDp * density).toInt()
        if (cfgW > 0 && cfgW < size.x) size.x = cfgW
        if (cfgH > 0 && cfgH < size.y) size.y = cfgH
        return size
    }

    /**
     * Room a glass panel keeps inside its own bounds for the neon edge to bloom
     * into. The panel paints that glow along its own silhouette (see
     * [CurvedGlassPanel]), so a dialog is just the panel plus this much space
     * around it — there is no separate ring view parked behind it. Wide enough
     * for the widest glow stroke (44dp) to fade out before the view edge: its
     * half-width is 22dp, so 26dp covers it with a little to spare. It is also
     * the visible gap between the hint line above the panel and the panel's own
     * top edge, so it is kept as tight as the glow allows.
     */
    private val glassHaloPx: Int
        get() = (26 * resources.displayMetrics.density).toInt()

    /**
     * Presents the rounded glass panel that shells every player dialog. Above
     * the panel sit the contextual [hint] line (with [iconRes] drawn beside it)
     * and the round glass close button, exactly like the reference player; the
     * panel itself carries only the scrollable [content] — no title bar and no
     * footer button — so the rows ARE the dialog.
     *
     * The panel is capped to the screen (see [preferredHeightDp]) and the window
     * is WRAP_CONTENT + centred, so a long list scrolls inside a panel that
     * always fits instead of running off the top and bottom of the video.
     *
     * Returns the hint [TextView] so a caller can keep its text live (the
     * "server too slow" countdown), or null when no hint was requested.
     */
    private fun presentGlass(
        dialog: Dialog,
        title: String,
        content: View,
        preferredHeightDp: Float,
        hint: String? = null,
        iconRes: Int = 0,
        cancelable: Boolean = true,
        rowHosts: List<ViewGroup> = emptyList(),
    ): TextView? {
        val density = resources.displayMetrics.density
        // The halo is where the curved pane's neon blooms. A flat panel (every
        // skin but Default — see [CurvedGlassPanel.applySkin]) has no glow to
        // make room for, so it only keeps a small margin and the panel itself
        // grows into the rest of the screen: more rows visible, less clipping.
        val flatPanel = PlayerSkins.isFlat(skin)
        val halo = if (flatPanel) (10 * density).toInt() else glassHaloPx
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // A dialog with no hint of its own still gets a line up here: the title
        // is the natural stand-in, so nothing inside the panel is a header.
        val hintView = (hint?.takeIf { it.isNotBlank() } ?: title.takeIf { it.isNotBlank() })
            ?.let { line ->
                TextView(this).apply {
                    text = line
                    dpText(10f)
                    includeFontPadding = false
                    // Four lines, not three: the cross-extension status line
                    // ("Asked 254 other repos (Aniyomi 13, CloudStream 57,
                    // Hikari 181, SkyStream 3) — done, 5 with servers · 146
                    // didn't answer in time, 88 don't carry it, 12 couldn't
                    // load") is four lines on a phone — the tallies are shown
                    // whether or not servers came back, because that line is how
                    // the user verifies that every installed extension really
                    // was asked — and at three it was sliced mid-fact
                    // ("… done, 17 with s…"). The hint is the only place the
                    // search explains itself, so it gets the room. A short hint
                    // still takes the one line it needs: this is a cap.
                    maxLines = 4
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(0xFF9AA5B5.toInt())
                }
            }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Start the line in from the panel's own left edge (the halo is
            // where the panel's glow lives), not from the window's. No bottom
            // padding: the halo alone is the gap to the panel, so the hint sits
            // right on top of the glass instead of floating well above it.
            setPadding(halo + (8 * density).toInt(), 0, halo, 0)
            if (hintView != null) {
                if (iconRes != 0) {
                    addView(ImageView(this@PlayerActivity).apply {
                        setImageResource(iconRes)
                        imageTintList = ColorStateList.valueOf(withAlpha(accentMidColor, 0.95f))
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                    }, LinearLayout.LayoutParams(
                        (13 * density).toInt(), (13 * density).toInt()
                    ).apply { marginEnd = (6 * density).toInt() })
                }
                addView(hintView, LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginEnd = (8 * density).toInt() })
            } else {
                addView(View(this@PlayerActivity), LinearLayout.LayoutParams(0, 1, 1f))
            }
            if (cancelable) {
                addView(TextView(this@PlayerActivity).apply {
                    text = "\u2715"
                    dpText(12f)
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                    setTextColor(0xE6FFFFFF.toInt())
                    background = ContextCompat.getDrawable(
                        this@PlayerActivity, R.drawable.circle_glass_ripple
                    )
                    isClickable = true
                    setOnClickListener { dialog.dismiss() }
                }, LinearLayout.LayoutParams((24 * density).toInt(), (24 * density).toInt()))
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // A permanent thin scrollbar makes it obvious the panel scrolls — the
        // old fixed-height panel hid its last rows with no affordance at all.
        val scroll = ScrollView(this).apply {
            addView(content)
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
            // Android 12's "stretch" overscroll effect scales and translates
            // the content while the user keeps dragging past the end. The rows
            // are bent to the panel's curve from their measured positions, so a
            // stretch changes those positions on every frame and the bend keeps
            // chasing them — which is the up/down shudder at the end of a list.
            // The glass panel already has its own edge light; the platform's
            // stretch adds nothing but the jitter.
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        val panel = CurvedGlassPanel(this).apply {
            haloPx = halo.toFloat()
            startColor = accentStartColor
            midColor = accentMidColor
            endColor = accentEndColor
            // The panel wears the Player UI skin, so the sheet that opens on
            // Source / Subtitles / Audio / Speed matches the control bar the
            // user picked (Settings -> Player -> Player UI): the bowed neon
            // glass for Glass, and a flat slab/deck/card for the other three.
            applySkin(skin)
            // The rows bend to the panel's curve (see CurvedGlassPanel). The
            // caller hands over the containers that actually hold them — when
            // the whole list fits that is the row container itself, and the row
            // stack's outline then IS the shape. A dialog with rows in two
            // places (track list + control rows) hands over both.
            rowHosts.forEach { bendHost(it) }
            // …and anything else the content holds that is not part of a host:
            // a message line above the list (the download sheet's "Episode 683
            // · …"), which otherwise sits at the panel's full inner width and
            // gets sliced by the bowed edge. The content is a plain view by
            // signature; only a container can hold loose children.
            (content as? ViewGroup)?.let { bendLoose(it) }
        }
        panel.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        // Rows are bent to the curve at their CURRENT height inside the panel, so
        // a scroll changes which part of the curve each one sits on. Re-bend on
        // every scroll: without this a row that scrolls up into the panel keeps
        // the (wider) margins it was given while it was still off-screen, and
        // the bowed edges slice it — which is what cut the lower quality rows
        // ("1080p" -> "0p") in a list long enough to scroll.
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> panel.rebend() }

        val win = windowSize()
        // Width of the PANEL's own silhouette (the halo is added around it), so
        // the glass and the glow along it are sized against a known width.
        // A flat panel can afford to be wider: the curved pane has to stay
        // narrow for its bow to read as a pane rather than a wall, while a
        // slab/deck/card is mostly a container for rows — and a wider one fits
        // the longer option labels without clipping them.
        val panelW = minOf(
            // Wider than it used to be, for both the pane and the flat slabs:
            // the rows, the engine chips ("All / CloudStream / Hikari / Nuvio /
            // SkyStream") and the longer server names are all laid out inside
            // this width, and at 0.86 the glass pane was clipping the leading
            // edge of its own rows and pushing the last chip off the strip.
            // Raised again on the user's report that the server box wants to be
            // LONGER so everything under it reads perfectly ("make it length
            // longer"): a long engine name, its quality and its host all have to
            // fit on one row, and the panel is the only place that width can
            // come from. Still short of the window so the panel keeps floating
            // with both its rounded sides visible.
            (win.x * if (flatPanel) 0.97f else 0.95f).toInt(),
            // The height axis is only a "do not become a wall" guard, and in the
            // LANDSCAPE player it is the binding one (the window is three times
            // wider than it is tall), which is what kept the engine chips — five
            // of them — wider than the panel on a phone. Raised so the chips and
            // the longer server names fit on one line.
            (win.y * if (flatPanel) 0.97f else 0.93f).toInt(),
            (560 * density).toInt(),
        ).coerceAtMost(win.x - 2 * halo - (8 * density).toInt())
            .coerceAtLeast((140 * density).toInt())
        // The panel must FLOAT on the video with all four rounded corners (and
        // the light sweeping around them) visible: it is capped against the hint
        // line plus the halo's own room above it, and against a fraction of the
        // window, so it never runs off the top/bottom edge — which used to clip
        // its bottom curve and hide the last rows. Anything longer scrolls.
        val chrome = (96 * density).toInt()
        val fitsScreen = (win.y - chrome).coerceAtLeast((110 * density).toInt())
        // Taller too: a server list scrolled four rows at a time is the other
        // half of "there isn't room" — the panel's own rounded bottom stays on
        // screen, and anything longer still scrolls.
        val maxFraction = (win.y * if (flatPanel) 0.76f else 0.66f).toInt()
        val minPanel = (110 * density).toInt()
        val panelH = (preferredHeightDp * density).toInt()
            .coerceAtMost(fitsScreen)
            .coerceAtMost(maxFraction)
            .coerceAtLeast(minPanel)
        // The panel view carries its own halo, so its silhouette comes out
        // exactly panelW x panelH in the middle of it.
        root.addView(panel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, panelH + 2 * halo
        ))

        dialog.setContentView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        com.hikari.app.ui.AppFonts.applyToViewTree(root, com.hikari.app.ui.AppFonts.appTypeface(this))
        dialog.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(cancelable)
        dialog.setCancelable(cancelable)
        // A panel can be asked for in the same turn the Activity is going away
        // (the download flow closes the player once its task is queued, and a
        // queued tap can land after that). Adding a window to a finished
        // Activity throws BadTokenException, so the show is guarded and a
        // refusal is simply "no panel" rather than a crash.
        if (isFinishing || isDestroyed) return null
        if (runCatching { dialog.show() }.isFailure) return null
        // Narrower than a stock dialog: the reference panel is ~3/4 of the window
        // height wide and never spans the full width, which is a large part of
        // why it reads as a lightweight overlay instead of a full-screen sheet.
        // The halo is added back on top so the PANEL keeps that width.
        dialog.window?.apply {
            setLayout(panelW + 2 * halo, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            setDimAmount(0.65f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }

        // A cap TALLER than the rows it holds is just empty glass: the panel's
        // bottom keeps its curve while the rows stop well above it, which is
        // what a bare band of panel between two groups of rows is. Measure the
        // content at the panel's own width and shrink the panel onto it — the
        // cap above stays as an upper bound, and anything longer still scrolls.
        val panelLp = panel.layoutParams as? LinearLayout.LayoutParams
        var appliedSil = -1
        fun fitToContent() {
            if (panelLp == null) return
            // The panel's own padding is what the content is laid out inside —
            // not just the halo. A flat panel adds its side gap (and the corner
            // clearance) on top of the halo (see CurvedGlassPanel.onSizeChanged),
            // so measuring the content at `width - 2*halo` handed it a column
            // ~16dp wider than it will actually get, under-counting the wrapped
            // lines and closing the panel before its last row. The curved pane
            // keeps the old, deliberately narrow measure: its rows are bent in
            // from that width, so measuring wide there under-counts too.
            val innerW = if (flatPanel) {
                panel.width - panel.paddingLeft - panel.paddingRight
            } else {
                panel.width - 2 * halo
            }
            if (innerW <= 0 || panel.height - 2 * halo <= 0) return
            content.measure(
                View.MeasureSpec.makeMeasureSpec(innerW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val contentH = content.measuredHeight
            if (contentH <= 0) return
            // The panel's own padding (halo + row gap, top and bottom) is part
            // of the silhouette, so the wanted height is the rows plus it — the
            // halo is added AROUND the silhouette, not inside it.
            val wanted = contentH + scroll.paddingTop + scroll.paddingBottom +
                panel.paddingTop + panel.paddingBottom
            val sil = wanted.coerceIn(minPanel, minOf(fitsScreen, maxFraction))
            if (sil == appliedSil) return
            appliedSil = sil
            panelLp.height = sil + 2 * halo
            panel.layoutParams = panelLp
        }
        // Widths only exist after the dialog is shown, and the rows can change
        // height while it is up (a server landing mid-search), so fit now and
        // again on every content layout change.
        scroll.post { fitToContent() }
        content.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitToContent() }
        return hintView
    }

    /**
     * Builds + shows a glass menu. Each tap dismisses the panel and reports the
     * tapped row's index. [hint] is the contextual line above the panel (with
     * [iconRes] drawn beside it) and [message] an optional muted paragraph above
     * the rows. Returns the created dialog, so a caller can attach its own
     * listeners.
     */
    private fun showGlassMenu(
        title: String,
        options: List<GlassOption>,
        hint: String? = null,
        iconRes: Int = 0,
        message: String? = null,
        cancelable: Boolean = true,
        onDialog: ((Dialog) -> Unit)? = null,
        onHint: ((TextView) -> Unit)? = null,
        onPick: (Int) -> Unit,
    ): Dialog {
        val density = resources.displayMetrics.density
        val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (!message.isNullOrBlank()) {
            content.addView(TextView(this).apply {
                text = message
                dpText(10.5f)
                includeFontPadding = false
                setLineSpacing(3f * density, 1f)
                setTextColor(0xFF9AA5B5.toInt())
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(
                    (11.5f * density).toInt(), (5 * density).toInt(),
                    (11.5f * density).toInt(), (2 * density).toInt()
                )
            })
        }
        val list = optionList()
        options.forEachIndexed { i, option ->
            addOptionRow(list, option) {
                dialog.dismiss()
                onPick(i)
            }
        }
        content.addView(list, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        // Capsule rows are ~34dp tall (46dp when they carry a second line), 3dp
        // apart inside the list's own padding — mirrored here so the panel opens
        // at its natural height instead of always filling the screen. presentGlass
        // still caps this against the screen, and anything longer scrolls.
        val height = options.sumOf { if (it.sub.isNullOrBlank()) 34.0 else 46.0 }.toFloat() +
            options.size * 3f + 12f +
            (if (!message.isNullOrBlank()) 40f else 0f)
        onDialog?.invoke(dialog)
        val hintView = presentGlass(dialog, title, content, height, hint, iconRes, cancelable, rowHosts = listOf(list))
        if (hintView != null) onHint?.invoke(hintView)
        return dialog
    }

    /**
     * The glass progress panel shown while the player waits on a network step
     * (finding servers, starting the torrent engine, probing a stream). Same
     * shell as every other player dialog, so a tap never drops back to a stock
     * Android spinner. [onCancel] fires when the user backs out / taps away.
     */
    private fun showGlassProgress(
        title: String,
        message: String,
        cancelable: Boolean,
        onCancel: (() -> Unit)? = null,
    ): Dialog {
        val density = resources.displayMetrics.density
        val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        // Same skin-aware margin as [presentGlass]: a flat panel keeps a small
        // one, the curved pane keeps room for its neon.
        val halo = if (PlayerSkins.isFlat(skin)) (10 * density).toInt() else glassHaloPx
        val panel = CurvedGlassPanel(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            haloPx = halo.toFloat()
            startColor = accentStartColor
            midColor = accentMidColor
            endColor = accentEndColor
            applySkin(skin)
        }
        panel.addView(ProgressBar(this).apply {
            indeterminateTintList = ColorStateList.valueOf(accentMidColor)
        }, LinearLayout.LayoutParams((34 * density).toInt(), (34 * density).toInt()))
        panel.addView(TextView(this).apply {
            text = title
            dpText(14f)
            includeFontPadding = false
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (18 * density).toInt() })
        panel.addView(TextView(this).apply {
            text = message
            dpText(11f)
            includeFontPadding = false
            setTextColor(0xFF9AA5B5.toInt())
            gravity = Gravity.CENTER
            setLineSpacing(3f * density, 1f)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (8 * density).toInt() })
        val win = windowSize()
        val w = minOf(
            (win.x * 0.62f).toInt(),
            (win.y * 0.6f).toInt(),
            (300 * density).toInt(),
        ).coerceAtMost(win.x - 2 * halo - (8 * density).toInt())
            .coerceAtLeast((140 * density).toInt())
        dialog.setContentView(
            panel,
            ViewGroup.LayoutParams(w + 2 * halo, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        com.hikari.app.ui.AppFonts.applyToViewTree(panel, com.hikari.app.ui.AppFonts.appTypeface(this))
        dialog.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(cancelable)
        if (onCancel != null) dialog.setOnCancelListener { onCancel() }
        // Same guard as presentGlass: never add a window to a dying Activity.
        if (isFinishing || isDestroyed) return dialog
        runCatching { dialog.show() }
        dialog.window?.apply {
            setLayout(w + 2 * halo, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            setDimAmount(0.55f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        return dialog
    }

    /** The Episodes pill: lists the title's episodes (fetched from the provider
     *  stack on demand) and switches playback to the one the user picks, without
     *  leaving the player. */
    private fun showEpisodesDialog() {
        val item = favouriteItem ?: return
        val repo = contentRepo
        Toast.makeText(this, I18n.t("Loading episodes…"), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val eps = runCatching { repo.episodesFor(item) }.getOrNull().orEmpty()
            if (eps.isEmpty()) {
                Toast.makeText(this@PlayerActivity, I18n.t("No episode list available"), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val options = eps.map { ep ->
                val number = when {
                    ep.season > 1 && ep.number > 0 -> "S${ep.season} E${ep.number}"
                    ep.number > 0 -> "Episode ${ep.number}"
                    else -> ""
                }
                val named = episodeNameSuffix(ep.name).removePrefix(" · ")
                GlassOption(
                    label = when {
                        named.isNotEmpty() && number.isNotBlank() -> "$number \u00B7 $named"
                        named.isNotEmpty() -> named
                        number.isNotBlank() -> number
                        else -> "Episode"
                    },
                    selected = ep.id == historyEntry?.episodeId,
                )
            }
            showGlassMenu(
                I18n.t("Episodes"),
                options,
                hint = I18n.t("Switching keeps you inside the player."),
                iconRes = R.drawable.ic_episodes,
            ) { which ->
                eps.getOrNull(which)?.let { switchToEpisode(it) }
            }
        }
    }

    /** Switches playback to [ep] in place: fetches that episode's servers, stops
     *  the previous episode's live session, adopts the new episode's history key
     *  and starts on the first server. Shows a cancellable progress dialog while
     *  the providers search. */
    private fun switchToEpisode(ep: Episode) {
        val item = favouriteItem ?: return
        val repo = contentRepo
        var cancelled = false
        val dialog = showGlassProgress(
            "Loading episode",
            "Finding servers for this episode…",
            cancelable = true,
        ) { cancelled = true }
        lifecycleScope.launch {
            val streams = runCatching { repo.streamsFor(item, ep) }.getOrNull().orEmpty()
            runCatching { dialog.dismiss() }
            if (cancelled) return@launch
            if (streams.isEmpty()) {
                Toast.makeText(
                    this@PlayerActivity, I18n.t("No servers found for this episode"), Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            // The origin session's servers belong to the episode we just left —
            // stop appending them, and stop restoring its remembered server.
            liveStreamsJob?.cancel()
            liveStreamsJob = null
            liveStatusJob?.cancel()
            liveStatusJob = null
            liveSessionId = null
            // Adopt the new episode (top-bar episode line + watch-history key).
            applyLiveEpisode(ep)
            // A fresh episode starts fresh: no resume position, no remembered
            // server, and no memory of the old episode's failed URLs.
            startPositionMs = 0L
            seekPending = false
            resumeHintMs = 0L
            resumeHintDurMs = 0L
            refreshAttempts = 0
            noSubsRetry = false
            resetHeaderWalk()
            triedUrls.clear()
            sources = streams.map { it.toPlayerSource() }
            notifySourcesChanged()
            currentIndex = 0
            // "Don't play directly" applies to an in-player episode switch too:
            // this is a brand-new server list, so it gets its own chooser
            // instead of auto-starting on the first server.
            if (shouldAskServer()) {
                startChooserShown = false
                showServerChooser(startMode = true)
            } else {
                playSource(0)
            }
        }
    }

    /** The Source pill: the same grouped picker, dismissible without a pick. */
    private fun showSourcesDialog() = showServerChooser()

    /** The fixed section order: the engine the user opened the title from first
     *  — the other repos of THAT engine are the closest thing to "my
     *  provider", and used to land behind forty Hikari servers — then
     *  CloudStream, Hikari's own providers, Nuvio and Stremio, with anything
     *  the repository could not attribute last. */
    private val serverGroupOrder: List<String>
        get() {
            val base = listOf("CloudStream", "Hikari", "Nuvio", "Stremio", "Other")
            val originEngine = sources.firstOrNull { it.isFromOrigin() && it.provider.isNotBlank() }
                ?.provider
                ?: return base
            return listOf(originEngine) + base.filter { it != originEngine }
        }

    /** Which section a server belongs to. [PlayerSource.provider] is stamped by
     *  the repository from the provider that produced it; a blank one falls back
     *  to the "Repo · Server" name prefix, and to "Other" when even that says
     *  nothing. */
    private fun serverGroup(src: PlayerSource): String {
        src.provider.takeIf { it.isNotBlank() }?.let { return it }
        val prefix = src.name.substringBefore(" \u00B7 ").trim()
        return prefix.takeIf { it.isNotBlank() && prefix.length < src.name.length } ?: "Other"
    }

    /**
     * True when this server came from the provider the user opened the title
     * from — matched by provider id first, then by the provider's display name
     * (also as the "Provider · Server" prefix a cross-extension server carries).
     */
    private fun PlayerSource.isFromOrigin(): Boolean {
        if (originProviderId.isNotBlank() && providerId == originProviderId) return true
        if (originProviderName.isNotBlank() && providerName.equals(originProviderName, true)) return true
        if (originProviderName.isNotBlank()) {
            val prefix = name.substringBefore(" \u00B7 ").trim()
            if (prefix.equals(originProviderName, ignoreCase = true)) return true
        }
        return false
    }

    /** Heading for the origin provider's servers, named by its ENGINE — the
     *  section the user opened the title from leads, but it reads like every
     *  other section ("CLOUDSTREAM" over the CloudStream repos' servers, then
     *  "HIKARI", "NUVIO"…), so the heading is a category rather than one repo's
     *  name. The repo a server came from stays visible on the row itself
     *  ("MovieBoxIN (Hindi Audio) 1080p"), which is where it belongs: the
     *  heading above it groups the engine, not the one repo.
     *
     *  Falls back to the provider's display name when the engine was not stamped
     *  on the source, and is blank with no origin context (the chooser then just
     *  shows the engine sections). */
    private val originLabel: String
        get() {
            val originRow = sources.firstOrNull { it.isFromOrigin() }
            if (originRow != null) {
                val engine = serverGroup(originRow)
                if (engine.isNotBlank() && engine != "Other") return engine
            }
            if (originProviderName.isNotBlank()) return originProviderName
            if (originProviderId.isNotBlank()) {
                return sources.firstOrNull { it.providerId == originProviderId }
                    ?.providerName.orEmpty()
            }
            return ""
        }

    /**
     * A server row's label, made UNIQUE when several entries share a name.
     *
     * Two different links from the same engine routinely arrive with the same
     * label ("DahmerMovies 1080p"), because the label is built from the repo and
     * the resolution and says nothing about which file it points at — so the
     * chooser showed what looked like the same server listed twice (the reported
     * duplicate; each row still played a different link). A repeated name gets
     * its host appended, or — when even the hosts match — its position among the
     * twins, so every row is distinguishable.
     */
    private fun serverLabel(source: PlayerSource): String {
        val name = source.name
        if (name.isBlank()) return name
        val twins = sources.filter { it.name == name }
        if (twins.size < 2) return name
        val key = source.infoHash ?: source.url
        val host = hostOf(source.url)
        if (host != null && host.isNotBlank() &&
            twins.count { hostOf(it.url) == host } == 1 &&
            !name.contains(host, ignoreCase = true)
        ) {
            return "$name \u00B7 $host"
        }
        val ordinal = twins.indexOfFirst { (it.infoHash ?: it.url) == key } + 1
        return "$name ($ordinal)"
    }

    /** One server's row — the same capsule the flat picker used. */
    private fun serverOption(source: PlayerSource, index: Int): GlassOption = GlassOption(
        label = serverLabel(source),
        sub = when {
            source.local -> "Saved on this device"
            else -> hostOf(source.url)
        },
        badge = when {
            source.local -> "Offline"
            source.torrentStream || source.isTorrent -> "Torrent"
            source.isM3u8 -> "HLS"
            source.isMpd -> "DASH"
            else -> null
        },
        // Only mark a row as current once playback has actually been committed
        // to a server — see [playbackCommitted].
        selected = playbackCommitted && index == currentIndex,
    )

    /**
     * The grouped server picker: a scrollable row of engine chips (All, then
     * every engine that actually returned something) above a list divided into
     * sections — "CloudStream" over its servers, then "Hikari", "Nuvio",
     * "Stremio" — so a long merged list reads like the reference app's source
     * sheet instead of one undifferentiated column.
     *
     * The list is rebuilt whenever [notifySourcesChanged] fires, so servers that
     * land while the sheet is open (the detail screen keeps searching) appear
     * without a re-open. [startMode] is the "don't play directly" chooser: it
     * stays up until the user picks, and backing out of it falls back to the
     * remembered/best server rather than leaving the player blank.
     */
    private fun showServerChooser(startMode: Boolean = false, forDownload: Boolean = false) {
        // startMode is the "don't play directly" chooser, which is opened the
        // instant the player does — before the first server has landed — so it
        // may legitimately be empty and fill in live. The Source pill
        // (non-startMode) only makes sense with a list, so it still no-ops.
        // forDownload is the same chooser used as the first step of a Download
        // tap: nothing plays, and the pick starts the download instead (see
        // [downloadPickMode]).
        if (sources.isEmpty() && !startMode) return
        if (startMode) {
            // Present the start chooser at most once per Activity: the live
            // search keeps growing the list, and re-opening the chooser after
            // the user already picked (or backed out) is the bug where the
            // server list reappears by itself and playback restarts on the
            // fastest server instead of the one that was chosen.
            if (startChooserShown) return
            startChooserShown = true
            // Backing out of a download chooser must NOT fall back to playing
            // the remembered server — there is no playback to fall back to.
            startChoicePending = !forDownload
        }
        val density = resources.displayMetrics.density
        val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        serverChooserDialog = dialog
        var chip = "All"

        /** Sections that actually have servers, in the fixed order above. */
        fun groups(): List<String> {
            val have = sources.map { serverGroup(it) }.toSet()
            return serverGroupOrder.filter { it in have } +
                have.filter { it !in serverGroupOrder }.sorted()
        }

        /**
         * The list's sections as (heading, source indices): the ENGINE the user
         * opened the title from FIRST — its own servers are the ones they expect
         * ("on MovieBox, play MovieBox") — then each other engine that found
         * something. The heading is the engine's name (CLOUDSTREAM / HIKARI /
         * NUVIO…), never one repo's name: the repo is on the row. A server only
         * ever appears in one section, so the counts add up to the number of
         * servers on screen.
         */
        fun sections(): List<Pair<String, List<Int>>> {
            // Origin rows lead (they are what the user opened the title from),
            // then every engine group. When the origin's own engine is ALSO a
            // group — a CloudStream title plus the other installed CloudStream
            // repos — the two are one section named after the engine: two
            // "CLOUDSTREAM" headings (and two identical chips) would be a bug of
            // their own, and the other repos' servers belong beside the origin's
            // anyway.
            val byName = LinkedHashMap<String, MutableList<Int>>()
            val origin = sources.indices.filter { sources[it].isFromOrigin() }
            if (origin.isNotEmpty() && originLabel.isNotBlank()) {
                byName.getOrPut(originLabel) { ArrayList() }.addAll(origin)
            }
            for (name in groups()) {
                val idx = sources.indices.filter {
                    !sources[it].isFromOrigin() && serverGroup(sources[it]) == name
                }
                if (idx.isEmpty()) continue
                byName.getOrPut(name) { ArrayList() }.addAll(idx)
            }
            return byName.map { it.key to it.value.toList() }
        }

        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Chips with labels of different lengths all sit centred in their
            // own pill instead of being nudged onto a shared baseline.
            isBaselineAligned = false
        }
        // The engine chips swipe sideways for the engines that don't fit: with
        // four extensions installed the row is wider than the panel, and before
        // this there was no way to reach the chips past the edge — and no hint
        // that anything was out there. OVER_SCROLL_ALWAYS adds the stretch glow
        // that says "this row moves".
        val chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_ALWAYS
            isFillViewport = false
            clipToPadding = false
            addView(chipRow, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        // One container for the chip strip, the headers AND the rows: the panel
        // bends a registered host's children to the glass's curve, so the strip
        // has to be one of them. Kept outside the list it was measured against
        // the panel's full width, so its first chip sat under the concave left
        // edge and the bowed glass sliced it into an empty stub — the "All"
        // button that looked collapsed and cut off. As a bent child the whole
        // strip is pulled inside the silhouette at its own height, so the first
        // chip always clears the curve, and the strip scrolls within that.
        val list = optionList()
        list.addView(chipScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (2 * density).roundToInt() })

        /**
         * One engine chip. Its box is measured from the TEXT rather than left to
         * the TextView's own WRAP_CONTENT: a chip that wraps inside a
         * HorizontalScrollView which is itself inside the panel's scroll view
         * could be handed a zero-width measure spec somewhere up that chain and
         * collapse to an empty sliver — which is what the first chip ("All")
         * was doing. A width taken from the glyphs cannot collapse: the pill is
         * always at least its label plus the side pads.
         */
        fun chipPill(label: String, selected: Boolean, onClick: () -> Unit): TextView {
            val bg = if (selected) {
                GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(
                        withAlpha(accentStartColor, 0.34f),
                        withAlpha(accentEndColor, 0.38f)
                    )
                ).apply {
                    cornerRadius = 999f
                    setStroke(
                        (1.2f * density).roundToInt().coerceAtLeast(1),
                        withAlpha(accentMidColor, 0.8f)
                    )
                }
            } else {
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 999f
                    setColor(0x14FFFFFF.toInt())
                }
            }
            val padX = (8 * density).roundToInt()
            val probe = TextView(this).apply { dpText(9.5f) }
            val textW = ceil(probe.paint.measureText(label)).toInt()
            val w = (textW + padX * 2).coerceAtLeast((28 * density).roundToInt())
            val h = (21 * density).roundToInt()
            return TextView(this).apply {
                text = label
                dpText(9.5f)
                isSingleLine = true
                includeFontPadding = false
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFFC9D2E0.toInt())
                background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null)
                isClickable = true
                isFocusable = false
                setOnClickListener { onClick() }
                layoutParams = LinearLayout.LayoutParams(w, h).apply {
                    marginEnd = (5 * density).roundToInt()
                }
            }
        }

        /** A flat signature of one row, so a rebuild can tell "the list only
         *  grew" (append) from "the layout really changed" (re-render). */
        fun itemKey(i: Int): String {
            val s = sources[i]
            return s.name + "\u0001" + s.url
        }

        // What the list holds right now (headings + rows, in order) and the
        // selection those rows were drawn with.
        var builtSig = ArrayList<String>()
        var builtSelected = -1

        fun rebuildList() {
            val all = sections()
            val visible = if (chip == "All") all else all.filter { it.first == chip }
            val sig = ArrayList<String>()
            visible.forEach { (name, memberIdx) ->
                if (chip == "All") sig.add("#" + name + "\u0001" + memberIdx.size)
                memberIdx.forEach { sig.add(itemKey(it)) }
            }
            // A live search keeps appending servers for a minute or two. Before
            // this, every rebuild re-created every row, which tore down the row
            // a finger was already pressing: the tap arrived as an ACTION_CANCEL
            // and was silently swallowed — the chooser closed and nothing
            // played. When the new layout only APPENDS to the rendered one, keep
            // what is on screen and add just the new rows.
            val grew = sig.size >= builtSig.size &&
                builtSig.indices.all { builtSig[it] == sig[it] }
            val keep = if (grew && builtSelected == currentIndex &&
                builtSig.isNotEmpty()) builtSig.size else 0
            if (keep == 0) {
                // The chip strip is this container's FIRST child (see above), so
                // drop only the headers and rows — removeAllViews would take the
                // chips with them and leave an empty strip behind.
                while (list.childCount > 1) list.removeViewAt(list.childCount - 1)
            }
            var pos = 0
            visible.forEach { (name, memberIdx) ->
                if (chip == "All") {
                    val isNew = pos >= keep
                    pos++
                    if (isNew) {
                        // The header names the engine and counts its servers;
                        // it is skipped for a single-chip view (the chip
                        // already says it).
                        val first = list.childCount == 1
                        list.addView(TextView(this).apply {
                            text = name.uppercase() + "  \u00B7  " + memberIdx.size
                            dpText(9f)
                            includeFontPadding = false
                            isSingleLine = true
                            ellipsize = TextUtils.TruncateAt.END
                            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                            setTextColor(withAlpha(accentMidColor, 0.95f))
                        }, LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            val topMargin = if (first) (2 * density).toInt() else (7 * density).toInt()
                            setMargins(
                                (4 * density).toInt(),
                                topMargin,
                                (4 * density).toInt(),
                                (4 * density).toInt()
                            )
                        })
                    }
                }
                memberIdx.forEach { i ->
                    val isNew = pos >= keep
                    pos++
                    if (isNew) {
                        val src = sources[i]
                        addOptionRow(list, serverOption(src, i)) {
                            // The tap IS the answer: never let the
                            // dismiss-induced fallback start a different server
                            // on the way out, and never let it finish the player
                            // out from under the download flow (see
                            // [serverPickActed]).
                            startChoicePending = false
                            serverPickActed = true
                            dialog.dismiss()
                            if (forDownload) {
                                // Download step 1: remember the chosen server and
                                // move to the destination/quality steps. Nothing
                                // is prepared or played.
                                currentIndex = i
                                pickedByUser = true
                                showDownloadDialog()
                            } else if (i != currentIndex || !playbackCommitted) {
                                // Before anything has played, `currentIndex` is
                                // still its 0 default, so a tap on the first row
                                // must play it like any other row rather than being
                                // treated as "already on this one".
                                noSubsRetry = false
                                playSource(i)
                                // A tap here IS the user's choice: if this
                                // server dies, offer a way out instead of
                                // sliding onto another one silently.
                                pickedByUser = true
                            }
                        }
                    }
                }
            }
            if (visible.isEmpty()) {
                // Opened before the first server landed ("don't play directly"
                // opens the chooser the instant the player does): show that the
                // search is running and that this list is where the servers
                // will appear, instead of a blank panel.
                val stateRow = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(0, (26 * density).roundToInt(), 0, (12 * density).roundToInt())
                }
                stateRow.addView(ProgressBar(this).apply {
                    isIndeterminate = true
                    indeterminateTintList = ColorStateList.valueOf(
                        withAlpha(accentMidColor, 0.95f)
                    )
                }, LinearLayout.LayoutParams(
                    (22 * density).roundToInt(), (22 * density).roundToInt()
                ))
                val who = originProviderName.takeIf { it.isNotBlank() }
                stateRow.addView(TextView(this).apply {
                    text = if (who != null) "Searching $who\u2026" else "Searching your providers\u2026"
                    dpText(11.5f)
                    includeFontPadding = false
                    setTextColor(0xFFD5DCE8.toInt())
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (11 * density).roundToInt() })
                stateRow.addView(TextView(this).apply {
                    text = "Your provider is searched first. Every server shows here the moment it is found."
                    dpText(10f)
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                    setTextColor(0x99FFFFFF.toInt())
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (5 * density).roundToInt() })
                list.addView(stateRow, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
            }
            builtSig = sig
            builtSelected = currentIndex
        }

        fun rebuildChips() {
            chipRow.removeAllViews()
            (listOf("All") + sections().map { it.first }).forEach { name ->
                // The pill carries its own measured LayoutParams (see chipPill),
                // so it is added bare — it can neither collapse nor be squeezed.
                chipRow.addView(
                    chipPill(if (name == "All") "All" else name, name == chip) {
                        chip = name
                        rebuildChips()
                        rebuildList()
                    }
                )
            }
        }

        rebuildChips()
        rebuildList()
        val watcher: () -> Unit = {
            // A rebuild changes the content height, so put the scroll offsets
            // back AFTER the new rows are laid out (scrollTo clamps to the new
            // maximum) — otherwise a server landing while the user reads the
            // list would yank them to the top, or a re-created chip row would
            // throw away the chip they had scrolled to.
            val sv = list.parent as? ScrollView
            val keepY = sv?.scrollY ?: 0
            val keepX = chipScroll.scrollX
            rebuildChips()
            rebuildList()
            sv?.post { sv.scrollTo(0, keepY) }
            chipScroll.post { chipScroll.scrollTo(keepX, 0) }
        }
        sourcesWatchers.add(watcher)
        var hintTicker: Job? = null
        dialog.setOnDismissListener {
            hintTicker?.cancel()
            sourcesWatchers.remove(watcher)
            if (serverChooserDialog === dialog) serverChooserDialog = null
            // Closed without a pick (the back button, the ✕, a tap outside):
            // fall back to the remembered/best server rather than leaving the
            // player blank on the loading card. A tap on a row has already
            // cleared the flag and gone on to play that row, so this never
            // starts a *different* server than the one the user chose.
            if (startChoicePending) {
                startChoicePending = false
                if (sources.isNotEmpty()) {
                    lifecycleScope.launch { playSource(preferredStartIndex()) }
                } else {
                    // Backed out of the chooser before ANY server had landed —
                    // there was nothing to pick. Don't fail the play; let the
                    // live search keep running and re-open the chooser (once)
                    // when the first server actually arrives.
                    startChooserShown = false
                }
            }
            // Backed out of the DOWNLOAD chooser without picking a server: there
            // is no playback to fall back to, so leave the player instead of
            // stranding the user on a loading card. A row tap is NOT a back-out
            // — it has already set [serverPickActed] and moved on to the
            // download sheet, which must be allowed to finish (that flow leaves
            // the player itself, via leaveAfterDownloadPick, once the task is
            // queued or the pick is abandoned).
            if (forDownload && !serverPickActed && !playbackCommitted && !isFinishing) finish()
        }
        val baseHint = if (forDownload) {
            I18n.t("Pick the server to download from \u2014 nothing starts playing.")
        } else if (sources.isEmpty()) {
            val who = originProviderName.takeIf { it.isNotBlank() }
            if (who != null) "Searching $who \u2014 servers appear as they are found."
            else "Searching your providers \u2014 servers appear as they are found."
        } else if (playbackCommitted) {
            // Re-opened mid-playback (the Source pill, or "Choose another
            // server" after a failure): this is a CONTINUATION of the list the
            // user already saw, not a new search — say so, and say that more
            // keep arriving.
            "${sources.size} found so far \u2014 still adding any new ones."
        } else {
            "Your provider first, then every engine that found a server."
        }
        val hintView = presentGlass(
            dialog,
            if (forDownload) I18n.t("Download from") else "Select server",
            list,
            700f,
            hint = baseHint,
            iconRes = if (forDownload) R.drawable.ic_download else R.drawable.ic_server,
            rowHosts = listOf(list),
        )
        if (hintView != null) {
            // The hint doubles as the live status of the OTHER extensions: which
            // ones are still being asked right now, and which came back without a
            // single server (and why). A repo that carries the title but cannot be
            // searched — or one that is simply slower than the Hikari/Nuvio pass
            // the user waited for — used to leave no trace at all in this sheet.
            hintTicker = lifecycleScope.launch {
                var shown = ""
                while (dialog.isShowing) {
                    val text = crossSearchHint() ?: baseHint
                    if (text != shown) {
                        shown = text
                        hintView.text = text
                    }
                    delay(600)
                }
            }
        }
    }

    /**
     * One line for the hint above the server chooser describing what the other
     * installed extensions are doing. It leads with NUMBERS — how many repos
     * were asked (out of how many are installed, per engine), how many found
     * servers, how many are still going — so the line can never look "stuck" on
     * one repo while the rest keep working. The old version was a " · "-joined
     * list of the failures, and the two-line hint truncated it after the first
     * repo (a repo that said "no matching title" sat there looking like the end
     * of the search). The per-repo detail is still in the log, and one example
     * reason is appended when nothing at all was found.
     */
    private fun crossSearchHint(): String? {
        // One pass's snapshot (see [ContentRepository.CrossTally]): reading the
        // live per-pass maps gave a mix of every search that happened to be
        // running, which is why the numbers could not add up ("93 asked · 164 no
        // such title").
        val tally = ContentRepository.crossTally
        // EVERY engine, not only the cross repos: the pass's primary targets —
        // the title's own extension and the nuvio engines — are the ones the
        // player is actually waiting for, and leaving them out of this tally made
        // the line announce "done" (and list engines that looked complete) while
        // the nuvio engines were still cold-booting. The user's own words: the
        // first play showed every server except nuvio and said the search was
        // done; the second play showed nuvio. Now the line says "Nuvio 2 of 13"
        // and keeps saying "still searching" until those engines have answered.
        val asked = tally.asked.values.toList() + tally.primaryAsked.values.toList()
        if (asked.isEmpty()) return null
        val running = tally.running.size + tally.primaryRunning.size
        val found = tally.found.size + tally.primaryFound.size
        // NOTE: the line is shown even when the picker already HAS servers.
        // Hiding it on a non-empty list was tried and rejected by the user: this
        // line is how they can tell that the app really did ask every installed
        // extension ("no, show the failure report — that's how people know if it
        // is really searching all servers"). What needed fixing was the WORDING,
        // not its presence: "82 no answer in time" read as a verdict about the
        // title, so the buckets are now printed in plain English (see
        // [ContentRepository.crossBucketLabel]) and the ones that are still
        // being re-asked say so.
        val byEngine = asked.groupingBy { it }.eachCount().entries
            .sortedBy { it.key }
            .joinToString(", ") { e ->
                val total = tally.installed[e.key]
                if (total != null && total > e.value) "${e.key} ${e.value} of $total" else "${e.key} ${e.value}"
            }
        // Cloudflare verdicts are dropped, and so is anything that describes how
        // far the PASS got rather than what a repo said (see
        // [ContentRepository.CROSS_QUIET_BUCKETS]): the pass is time-bounded, so
        // a tail of slow repos is normal, and putting that on screen as
        // "stopped early (131 never finished)" reads as a broken search even
        // when every repo that answered did so normally. The raw extension
        // Cloudflare wording ("Cloudflare blocked. Go to Settings 'n Bypass
        // Cloudflare.") is likewise not something to read in a server chooser.
        val allVerdicts = tally.verdict.values.toList()
        val verdicts = allVerdicts.filterNot {
            com.hikari.app.net.CloudflareVerifier.isVerificationMessage(it.substringAfter(" — ", it)) ||
                ContentRepository.crossReasonBucket(it.substringAfter(" — ", it)) in
                ContentRepository.CROSS_QUIET_BUCKETS
        }
        // Bare "no server" rather than "none with servers": the line is about the
        // OTHER extensions, and the picker's own list is the thing the user is
        // looking at.
        val servers = if (found > 0) ", $found with servers" else ", no server from them"
        // Say WHAT the pass ran into, counted by kind — "200 don't carry it, 28
        // couldn't load" answers "was it even asked? did it break?" on screen,
        // without needing a log. Shown even when servers DID come back, because
        // seeing the whole tally is how the user verifies that every installed
        // extension really was searched (their words: "that's how people are able
        // to know if it's really searching all servers"). The labels are the
        // plain ones (see [ContentRepository.crossBucketLabel]); the log keeps the
        // exact bucket keys. The single "e.g." example below is still reserved for
        // the empty case, where the plugin's own text is the actionable detail.
        val breakdown = if (verdicts.isNotEmpty()) {
            val buckets = LinkedHashMap<String, Int>()
            for (v in verdicts) {
                val b = ContentRepository.crossReasonBucket(v.substringAfter(" — ", v))
                buckets[b] = (buckets[b] ?: 0) + 1
            }
            buckets.entries.sortedByDescending { it.value }.take(3)
                .joinToString(", ") {
                    "${it.value} ${ContentRepository.crossBucketLabel(it.key)}"
                }
        } else ""
        val why = if (running == 0 && found == 0) {
            val actionable = verdicts.firstOrNull {
                val b = ContentRepository.crossReasonBucket(it)
                b == "could not load" || b == "search error" || b == "timed out"
            } ?: verdicts.firstOrNull()
            actionable?.let { " · e.g. " + oneLine(it).take(56) }.orEmpty()
        } else ""
        // Repos the pass never got to (or that were still working when its budget
        // ran out) are re-asked by the background sweep while the video plays, so
        // "the pass ended" and "the search ended" are different things — and the
        // user reads a line that says "done" over a list that is missing servers
        // as a broken search (which is the point of this line: it is how they
        // check the app really did ask everything). The sweep's own liveness is
        // what makes the word honest.
        val state = when {
            // A count that has not MOVED for a minute is not a search that is
            // progressing — it is the frozen "30 still searching" the user
            // reported, which used to sit there until they left the player. The
            // tally changing means a repo started or finished, so this is a real
            // liveness test rather than a timer on the search itself.
            running > 0 && ContentRepository.crossStatusQuietForMs() < SEARCH_QUIET_MS ->
                "$running still searching"
            ContentRepository.anySweepBusy() -> "pass over, still searching"
            else -> "done"
        }
        return "Asked ${asked.size} other repos ($byEngine) — $state$servers" +
            (if (breakdown.isBlank()) "" else " · $breakdown") + why
    }

    /** A reason can come straight from a plugin's exception text — collapse it
     *  onto one line so the two-line hint above the panel cannot be blown up. */
    private fun oneLine(s: String): String = s.replace(Regex("\\s+"), " ").trim()

    private fun showQualityDialog() {
        val p = player ?: return
        val groups = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
        val rows = mutableListOf<TrackRow>()
        var overrideSelected = false
        for (group in groups) {
            val mediaGroup = group.mediaTrackGroup
            for (i in 0 until mediaGroup.length) {
                val f = mediaGroup.getFormat(i)
                val label = listOfNotNull(
                    f.height.takeIf { it > 0 }?.let { "${it}p" },
                    f.width.takeIf { it > 0 }?.let { "${it}px" },
                ).joinToString(" \u00B7 ").ifBlank { "Track ${i + 1}" }
                val bitrate = (if (f.averageBitrate > 0) f.averageBitrate else f.bitrate).toLong()
                if (isTrackSelected(p, group, i)) overrideSelected = true
                rows.add(
                    TrackRow(
                        label = label,
                        // No secondary line: the label already carries the
                        // resolution, and the variant's format id is a bare
                        // number ("1", "2"…) on most HLS streams.
                        sub = null,
                        badge = bitrateBadge(bitrate),
                        group = group,
                        index = i,
                    )
                )
            }
        }
        val indexMap = HashMap<Int, Pair<Tracks.Group, Int>>()
        val options = mutableListOf(
            GlassOption(
                I18n.t("Auto (adaptive)"),
                I18n.t("Automatically adjusts to your connection"),
                selected = !overrideSelected,
            )
        )
        rows.forEachIndexed { i, row ->
            indexMap[i + 1] = row.group to row.index
            options.add(
                GlassOption(
                    label = row.label,
                    sub = row.sub,
                    badge = row.badge,
                    selected = overrideSelected && isTrackSelected(p, row.group, row.index),
                )
            )
        }
        showGlassMenu(
            I18n.t("Video quality"),
            options,
            hint = I18n.t("Higher quality uses more data"),
            iconRes = R.drawable.ic_quality,
        ) { which ->
            if (which == 0) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                    .build()
            } else {
                val (group, ti) = indexMap[which] ?: return@showGlassMenu
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setOverrideForType(
                        TrackSelectionOverride(group.mediaTrackGroup, ImmutableList.of(ti))
                    )
                    .build()
            }
        }
    }

    /** True when [group]'s track [index] is the one explicitly selected. */
    private fun isTrackSelected(player: ExoPlayer, group: Tracks.Group, index: Int): Boolean {
        val mediaGroup = group.mediaTrackGroup
        val override = player.trackSelectionParameters.overrides[mediaGroup]
        if (override != null && override.trackIndices.any { it == index }) return true
        // A rebuilt media item invalidates the override until [applyStickyPicks]
        // re-applies it a moment later — read the remembered pick as well, so
        // the sheet never claims the user's choice was forgotten.
        val pick = when (group.type) {
            C.TRACK_TYPE_TEXT -> pickText
            C.TRACK_TYPE_AUDIO -> pickAudio
            else -> null
        } ?: return false
        return pick.matches(mediaGroup.getFormat(index), index)
    }

    /**
     * Subtitle control. Lists every available text track (HLS/DASH subtitle
     * groups AND the provider-supplied .srt/.vtt), plus Off and Auto — so a
     * stream that forces subtitles on can finally be muted, and a stream with
     * several languages gets a real picker. Below the track list sit three
     * settings rows: text size (A−/A+, applied live to the SubtitleView), sync
     * (slow/fast, re-times the provider subtitle data so it lines up with the
     * audio when a source's subs are off by a fraction of a second), and
     * position (Lower/Higher, lifts the captions off the bottom edge so
     * fullscreen subtitles no longer sit in the letterbox bar).
     */
    private fun showSubsDialog() {
        val p = player ?: return
        val groups = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        // A server can advertise subtitles that all failed to fetch or carried
        // no cues — with them filtered out the picker would silently show only
        // Off/Auto, which reads as "the app lost my subtitles". Say so instead.
        if (groups.isEmpty() &&
            sources.getOrNull(currentIndex)?.subtitles?.isNotEmpty() == true
        ) {
            Toast.makeText(
                this,
                I18n.t("This server's subtitles couldn't be loaded"),
                Toast.LENGTH_SHORT,
            ).show()
        }
        val params = p.trackSelectionParameters
        val textDisabled = params.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val density = resources.displayMetrics.density

        val rows = mutableListOf<TrackRow>()
        var overrideSelected = false
        for (group in groups) {
            val mediaGroup = group.mediaTrackGroup
            for (i in 0 until mediaGroup.length) {
                val f = mediaGroup.getFormat(i)
                val lang = languageOf(f.language)
                // A subtitle file the user brought themselves is named by the
                // file: "Movie.English.srt" (with the guessed language as its
                // second line) rather than by its internal marker label.
                val userFile = f.label?.takeIf { isUserSubLabel(it) }?.removePrefix(USER_SUB_PREFIX)
                val primary = when {
                    userFile != null -> userFile
                    else -> lang ?: trackLabel(f.label ?: f.id, i)
                }
                val sub = if (userFile != null) lang else trackSub(primary, f.label, f.id)
                if (!textDisabled && isTrackSelected(p, group, i)) overrideSelected = true
                rows.add(
                    TrackRow(
                        label = primary,
                        sub = sub,
                        badge = codecBadge(f.sampleMimeType),
                        group = group,
                        index = i,
                    )
                )
            }
        }
        val options = mutableListOf(
            GlassOption(I18n.t("Off"), I18n.t("Hide captions completely"), selected = textDisabled && !overrideSelected),
            GlassOption(
                I18n.t("Auto"), I18n.t("Follow the stream's default captions"),
                selected = !textDisabled && !overrideSelected,
            ),
        )
        // The answer to "no extension has subtitles for this film": bring your
        // own file. It sits directly under Off/Auto so it is seen before a long
        // track list, and it is an action row like any other. The rows'
        // positions are captured here rather than written as literals below, so
        // adding one can never silently shift the meaning of another.
        val addSubRow = options.size
        options.add(
            GlassOption(
                I18n.t("Add external subtitle"),
                I18n.t("Pick a subtitle file from this device"),
                iconRes = R.drawable.ic_download,
                marker = RowMarker.ICON,
            )
        )
        // Offered only when there is something to remove, so the sheet never
        // carries a dead row.
        val removeSubRow = if (userSubs.isNotEmpty()) options.size else -1
        if (removeSubRow >= 0) {
            options.add(
                GlassOption(
                    I18n.t("Remove added subtitles"),
                    userSubs.joinToString(", ") { it.fileName }.take(64),
                    iconRes = R.drawable.ic_close,
                    marker = RowMarker.ICON,
                )
            )
        }
        val trackBase = options.size
        val indexMap = HashMap<Int, Pair<Tracks.Group, Int>>()
        rows.forEachIndexed { i, row ->
            indexMap[trackBase + i] = row.group to row.index
            options.add(
                GlassOption(
                    label = row.label,
                    sub = row.sub,
                    badge = row.badge,
                    selected = !textDisabled && isTrackSelected(p, row.group, row.index),
                )
            )
        }

        // Compact pill-shaped translucent +/- buttons, matching the app's glass
        // theme. They MUST stay narrow: the dialog's content area is only a few
        // hundred dp wide, and wider pills used to push the −/+ buttons past the
        // dialog's edge where they got clipped (looked like the Sync row was
        // "collapsing").
        fun pill(text: String, onClick: () -> Unit): TextView {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999f
                setColor(0x1AFFFFFF.toInt())
                setStroke((1 * density).toInt().coerceAtLeast(1), withAlpha(accentMidColor, 0.55f))
            }
            return TextView(this).apply {
                this.text = text
                dpText(11f)
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
                background = bg
                includeFontPadding = false
                setPadding((9 * density).toInt(), (4 * density).toInt(), (9 * density).toInt(), (4 * density).toInt())
                setOnClickListener { onClick() }
            }
        }
        fun rowLabel(text: String): TextView = TextView(this).apply {
            this.text = text
            dpText(12f)
            setTextColor(0xFFE6EAF3.toInt())
            // The label keeps a fixed slice of the row so the controls' scroll
            // area is the same on every row (and always the wider part).
            maxWidth = (104 * density).toInt()
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        fun valueLabel(text: String): TextView = TextView(this).apply {
            this.text = text
            dpText(11f)
            setTextColor(0xFF9AA5B5.toInt())
            gravity = Gravity.CENTER
            minWidth = (38 * density).toInt()
        }
        fun weightSpacer(): View = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }

        // Track rows use the shared accent list, then the three settings rows
        // (size / sync / position) sit below them.
        val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        val trackList = optionList()
        options.forEachIndexed { idx, option ->
            addOptionRow(trackList, option) {
                userPickedSubs = true
                when (idx) {
                    0 -> {
                        textOff = true
                        pickText = null
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                            .build()
                    }
                    1 -> {
                        textOff = false
                        pickText = null
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                            .build()
                    }
                    addSubRow -> {
                        // Hand over to the system picker: whatever the user
                        // downloaded or copied onto the device. "*/*" because an
                        // .srt or .ass file usually has no subtitle mime type at
                        // all — the content is validated after the pick instead.
                        // The sheet closes first so the picker is not stacked
                        // behind it (see the dismiss below).
                        externalSubLauncher?.launch(arrayOf("*/*"))
                    }
                    removeSubRow -> removeUserSubs()
                    else -> {
                        val (group, ti) = indexMap[idx] ?: return@addOptionRow
                        val format = group.mediaTrackGroup.getFormat(ti)
                        textOff = false
                        // Remember WHAT was picked (language/label), not the
                        // TrackGroup object — the group dies with the next
                        // re-prepare, the language does not.
                        pickText = TrackPick(C.TRACK_TYPE_TEXT, format.language, format.label, ti)
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                            .setOverrideForType(
                                TrackSelectionOverride(group.mediaTrackGroup, ImmutableList.of(ti))
                            )
                            .build()
                    }
                }
                dialog.dismiss()
            }
        }

        val sizeValue = valueLabel("${(subtitleScale * 100).toInt()}%")
        fun applySize() {
            sizeValue.text = "${(subtitleScale * 100).toInt()}%"
            subsPrefs.edit().putFloat("sub_scale", subtitleScale).apply()
            applySubtitleSize(subtitleScale)
        }
        val syncValue = valueLabel(syncLabel(subtitleOffsetMs))
        fun applySync() {
            syncValue.text = syncLabel(subtitleOffsetMs)
            subsPrefs.edit().putLong("sub_offset", subtitleOffsetMs).apply()
            attachExternalSubtitles()
        }
        val posValue = valueLabel("${(subtitlePosition * 100).toInt()}%")
        fun applyPos() {
            posValue.text = "${(subtitlePosition * 100).toInt()}%"
            subsPrefs.edit().putFloat("sub_pos", subtitlePosition).apply()
            applySubtitlePosition(subtitlePosition)
        }
        // Tapping the value restores the lifted default position.
        posValue.setOnClickListener { subtitlePosition = 0.14f; applyPos() }

        // Tapping the value resets it — cheaper than a whole extra "0" pill,
        // which was what pushed the −/+ buttons off the dialog's edge.
        syncValue.setOnClickListener { subtitleOffsetMs = 0L; applySync() }

        fun controlRow(label: String, vararg controls: View): LinearLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipToPadding = false
                setPadding(
                    (10 * density).toInt(), (6 * density).toInt(),
                    (10 * density).toInt(), (6 * density).toInt()
                )
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 999f
                    setColor(0x14FFFFFF.toInt())
                }
                addView(rowLabel(label))
                addView(weightSpacer())
                controls.forEach { addView(it) }
            }

        // The three control rows live in their own container so the panel can
        // bend them to the curve independently of the track list above them
        // (see CurvedGlassPanel.bendHost). Registering a container AND one of
        // its ancestors would bend the same rows twice.
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(trackList, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(controls, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        fun addControl(row: LinearLayout) {
            controls.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(
                    (10 * density).toInt(), (7 * density).toInt(),
                    (10 * density).toInt(), 0
                )
            })
        }
        addControl(controlRow(
            "Caption style",
            valueLabel(subtitleStyle.summary()),
            pill("Change") { dialog.dismiss(); showSubtitleStyleDialog() },
        ))
        addControl(controlRow(
            "Text size",
            pill("A−") { subtitleScale = (subtitleScale - 0.1f).coerceIn(0.5f, 2.5f); applySize() },
            sizeValue,
            pill("A+") { subtitleScale = (subtitleScale + 0.1f).coerceIn(0.5f, 2.5f); applySize() },
        ))
        addControl(controlRow(
            "Sync",
            pill("−0.5s") { subtitleOffsetMs = (subtitleOffsetMs - 500L).coerceIn(-30000L, 30000L); applySync() },
            syncValue,
            pill("+0.5s") { subtitleOffsetMs = (subtitleOffsetMs + 500L).coerceIn(-30000L, 30000L); applySync() },
        ))
        // Vertical position: "Higher" keeps more of the player's height
        // clear below the captions, lifting them off the bottom edge (and
        // out of the letterbox bar on a fitted/letterboxed video).
        addControl(controlRow(
            "Position",
            pill("Lower") { subtitlePosition = (subtitlePosition - 0.02f).coerceIn(0.02f, 0.60f); applyPos() },
            posValue,
            pill("Higher") { subtitlePosition = (subtitlePosition + 0.02f).coerceIn(0.02f, 0.60f); applyPos() },
        ))

        // The whole panel scrolls (see presentGlass), so the Track rows plus the
        // size/sync controls can never be cut off the bottom on a short screen.
        presentGlass(
            dialog,
            "Subtitles",
            root,
            1000f,
            hint = I18n.t("Applies while captions are on."),
            iconRes = R.drawable.ic_subtitles,
            rowHosts = listOf(trackList, controls),
        )
    }

    /**
     * Caption appearance. Everything media3's [CaptionStyleCompat] can express
     * is here — the text colour, an outline or drop shadow plus its colour, a
     * background that can be removed entirely, bold/italic, and the font (the
     * platform's families, or a .ttf/.otf the user brings from their device) —
     * plus a few ready-made presets, because "white text with a black outline"
     * or "yellow with a shadow" is what most people actually want and
     * assembling that from three colour pickers is busywork.
     *
     * Every change is applied to the player's SubtitleView the moment it is
     * made, so the panel needs no preview pane: the captions on the video ARE
     * the preview.
     */
    private fun showSubtitleStyleDialog() {
        val density = resources.displayMetrics.density
        val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        fun save(next: SubtitleStyle) {
            subtitleStyle = next.copySaved(subsPrefs)
            applySubtitleStyle()
        }

        fun hex(color: Int): String = if (android.graphics.Color.alpha(color) == 255) {
            String.format("#%06X", color and 0xFFFFFF)
        } else {
            String.format("#%02X%06X", android.graphics.Color.alpha(color), color and 0xFFFFFF)
        }

        fun paintPill(v: TextView, active: Boolean) {
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999f
                if (active) {
                    setColor(withAlpha(accentMidColor, 0.22f))
                    setStroke((1 * density).toInt().coerceAtLeast(1), withAlpha(accentMidColor, 0.85f))
                } else {
                    setColor(0x1AFFFFFF.toInt())
                    setStroke((1 * density).toInt().coerceAtLeast(1), 0x22FFFFFF)
                }
            }
            v.setTextColor(if (active) 0xFFFFFFFF.toInt() else 0xFFC9D2E0.toInt())
        }

        // Pills stay narrow on purpose: the panel's content area is only a few
        // hundred dp wide, and wider controls got clipped at its edge.
        fun pill(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
            this.text = text
            dpText(11f)
            setTextColor(0xFFC9D2E0.toInt())
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(
                (9 * density).toInt(), (5 * density).toInt(),
                (9 * density).toInt(), (5 * density).toInt()
            )
            isClickable = true
            setOnClickListener { onClick() }
        }

        val toggles = ArrayList<() -> Unit>()
        fun refresh() {
            toggles.forEach { it() }
        }

        /** A pill that shows its active state and repaints the whole panel's
         *  toggles after it is tapped (one tap can change several of them — a
         *  preset, or a setter that switches custom styling on). */
        fun toggle(text: String, active: () -> Boolean, onClick: () -> Unit): TextView {
            val v = pill(text) { onClick(); refresh() }
            toggles.add { paintPill(v, active()) }
            return v
        }

        /** A round colour chip showing the current value; tapping it opens the
         *  HSV picker. [color] is a getter so the chip follows live edits. */
        fun swatch(color: () -> Int, onClick: () -> Unit): TextView {
            val v = TextView(this).apply {
                text = ""
                includeFontPadding = false
                isClickable = true
                layoutParams = LinearLayout.LayoutParams((32 * density).toInt(), (22 * density).toInt())
                setOnClickListener { onClick(); refresh() }
            }
            toggles.add {
                val c = color()
                v.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 999f
                    setColor(c)
                    setStroke((1 * density).toInt().coerceAtLeast(1), 0x66FFFFFF)
                }
            }
            return v
        }

        fun rowLabel(text: String): TextView = TextView(this).apply {
            this.text = text
            dpText(12f)
            setTextColor(0xFFE6EAF3.toInt())
        }

        fun valueLabel(text: String): TextView = TextView(this).apply {
            this.text = text
            dpText(10.5f)
            setTextColor(0xFF9AA5B5.toInt())
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            maxWidth = (108 * density).toInt()
        }

        /**
         * One labelled row of controls.
         *
         * The pills scroll HORIZONTALLY inside the row when they do not all fit.
         * They used to be laid out at their natural width with nothing to scroll
         * them: on a phone in landscape the row is a few hundred dp wide, so a
         * row with four options ("Classic / Cinema / Boxed / Mono", or "None /
         * Outline / Shadow" plus its read-out) ran past the panel's edge, where
         * the panel's own clip cut the last pill in half — the reported "the
         * Custom button is going out of the curve, and the ones below it too,
         * and it won't scroll so I can't pick them". A row that scrolls can
         * always be reached, whatever the skin's width or the system font size.
         */
        fun controlRow(label: String, vararg controls: View): LinearLayout {
            val pills = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = LinearLayout.LAYOUT_DIRECTION_LTR
            }
            controls.forEachIndexed { i, control ->
                // The control's OWN params are kept (a colour swatch is a fixed
                // 32x22dp box, not a wrap-content pill); only the gap between
                // them is added.
                val lp = (control.layoutParams as? LinearLayout.LayoutParams)
                    ?: LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                if (i > 0) lp.marginStart = (5 * density).toInt()
                control.layoutParams = lp
                pills.addView(control)
            }
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipToPadding = false
                setPadding(
                    (10 * density).toInt(), (6 * density).toInt(),
                    (10 * density).toInt(), (6 * density).toInt()
                )
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 999f
                    setColor(0x14FFFFFF.toInt())
                }
                addView(rowLabel(label))
                addView(
                    android.widget.HorizontalScrollView(this@PlayerActivity).apply {
                        isHorizontalScrollBarEnabled = false
                        overScrollMode = View.OVER_SCROLL_NEVER
                        clipToPadding = false
                        addView(pills, LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ))
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginStart = (6 * density).toInt() }
                )
            }
        }

        fun addRow(row: View) {
            panel.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins((10 * density).toInt(), (7 * density).toInt(), (10 * density).toInt(), 0)
            })
        }

        // ---- the rows -------------------------------------------------------

        // Custom styling off = media3's own default captions, with whatever the
        // subtitle file itself asks for.
        addRow(controlRow(
            "Caption style",
            toggle("Stream default", { !subtitleStyle.enabled }) {
                save(subtitleStyle.copy(enabled = false))
            },
            toggle("Custom", { subtitleStyle.enabled }) {
                save(subtitleStyle.copy(enabled = true))
            },
        ))

        // Presets: the looks people pick in practice, one tap each. Every one
        // of them switches custom styling on (that is the point of tapping it).
        val classic = SubtitleStyle(
            enabled = true, textColor = 0xFFFFFFFF.toInt(), edge = SubtitleStyle.EDGE_OUTLINE,
            edgeColor = 0xFF000000.toInt(), background = 0x00000000,
        )
        val cinema = SubtitleStyle(
            enabled = true, textColor = 0xFFFFEB3B.toInt(), edge = SubtitleStyle.EDGE_SHADOW,
            edgeColor = 0xFF000000.toInt(), background = 0x00000000,
        )
        val boxed = SubtitleStyle(
            enabled = true, textColor = 0xFFFFFFFF.toInt(), edge = SubtitleStyle.EDGE_NONE,
            background = 0xCC000000.toInt(),
        )
        val mono = SubtitleStyle(
            enabled = true, textColor = 0xFFE6EAF3.toInt(), edge = SubtitleStyle.EDGE_OUTLINE,
            edgeColor = 0xFF000000.toInt(), background = 0x00000000,
            bold = true, font = "monospace",
        )
        fun isPreset(s: SubtitleStyle): Boolean = subtitleStyle.enabled &&
            subtitleStyle.copy(enabled = true) == s
        addRow(controlRow(
            "Presets",
            toggle("Classic", { isPreset(classic) }) { save(classic) },
            toggle("Cinema", { isPreset(cinema) }) { save(cinema) },
            toggle("Boxed", { isPreset(boxed) }) { save(boxed) },
            toggle("Mono", { isPreset(mono) }) { save(mono) },
        ))

        fun edgeName(): String = when (subtitleStyle.edge) {
            SubtitleStyle.EDGE_OUTLINE -> I18n.t("Outline")
            SubtitleStyle.EDGE_SHADOW -> I18n.t("Shadow")
            else -> I18n.t("None")
        }
        val textValue = valueLabel(hex(subtitleStyle.textColor))
        val edgeColorValue = valueLabel(hex(subtitleStyle.edgeColor))
        val bgValue = valueLabel(hex(subtitleStyle.background))
        val fontValue = valueLabel(SubtitleFonts.label(subtitleStyle.font))
        val edgeValue = valueLabel(edgeName())
        // The read-outs are refreshed through the same mechanism as the pills,
        // so nothing has to remember to update them by hand.
        toggles.add { textValue.text = hex(subtitleStyle.textColor) }
        toggles.add { edgeColorValue.text = hex(subtitleStyle.edgeColor) }
        toggles.add { bgValue.text = hex(subtitleStyle.background) }
        toggles.add { fontValue.text = SubtitleFonts.label(subtitleStyle.font) }
        toggles.add { edgeValue.text = edgeName() }

        /** Opens the HSV picker and applies what comes back as [apply]. */
        fun pickColor(title: String, initial: Int, allowAlpha: Boolean, apply: (Int) -> Unit) {
            ColorPickerDialog(this, accentMidColor).show(title, initial, allowAlpha) { picked ->
                apply(picked)
                refresh()
            }
        }

        addRow(controlRow(
            "Text colour",
            swatch({ subtitleStyle.textColor }) {
                pickColor(I18n.t("Caption colour"), subtitleStyle.textColor, false) {
                    save(subtitleStyle.copy(textColor = it, enabled = true))
                }
            },
            textValue,
        ))

        addRow(controlRow(
            "Text",
            toggle("Bold", { subtitleStyle.bold }) {
                save(subtitleStyle.copy(bold = !subtitleStyle.bold, enabled = true))
            },
            toggle("Italic", { subtitleStyle.italic }) {
                save(subtitleStyle.copy(italic = !subtitleStyle.italic, enabled = true))
            },
        ))

        // Outline vs drop shadow vs nothing. media3 strokes an outline at a
        // fixed width, so there is no width slider here — only on/off and the
        // colour, both of which really are honoured.
        addRow(controlRow(
            "Edge",
            toggle("None", { subtitleStyle.edge == SubtitleStyle.EDGE_NONE }) {
                save(subtitleStyle.copy(edge = SubtitleStyle.EDGE_NONE, enabled = true))
            },
            toggle("Outline", { subtitleStyle.edge == SubtitleStyle.EDGE_OUTLINE }) {
                save(subtitleStyle.copy(edge = SubtitleStyle.EDGE_OUTLINE, enabled = true))
            },
            toggle("Shadow", { subtitleStyle.edge == SubtitleStyle.EDGE_SHADOW }) {
                save(subtitleStyle.copy(edge = SubtitleStyle.EDGE_SHADOW, enabled = true))
            },
            edgeValue,
        ))

        addRow(controlRow(
            "Edge colour",
            swatch({ subtitleStyle.edgeColor }) {
                pickColor(I18n.t("Edge colour"), subtitleStyle.edgeColor, false) {
                    save(subtitleStyle.copy(edgeColor = it, enabled = true))
                }
            },
            edgeColorValue,
        ))

        addRow(controlRow(
            "Background",
            swatch({ subtitleStyle.background }) {
                pickColor(I18n.t("Caption background"), subtitleStyle.background, true) {
                    save(subtitleStyle.copy(background = it, enabled = true))
                }
            },
            bgValue,
            toggle("Remove", { subtitleStyle.background == 0 }) {
                save(subtitleStyle.copy(background = 0, enabled = true))
            },
        ))

        // Font: the platform's families or a file of the user's own. The row
        // shows the current choice; picking happens in a nested glass menu.
        addRow(controlRow(
            "Font",
            fontValue,
            pill("Change") { showFontMenu(dialog) { label -> fontValue.text = label } },
        ))

        addRow(controlRow(
            "Reset every caption setting",
            pill("Reset") {
                save(SubtitleStyle())
                refresh()
            },
        ))

        // The font row has to follow a file picked from the system picker
        // (which lands long after this function returned).
        fontPicked = {
            fontValue.text = SubtitleFonts.label(subtitleStyle.font)
            refresh()
        }
        dialog.setOnDismissListener { fontPicked = null }

        presentGlass(
            dialog,
            "Caption style",
            panel,
            1000f,
            hint = I18n.t("Applies to every subtitle, from any server."),
            iconRes = R.drawable.ic_subtitles,
            rowHosts = listOf(panel),
        )
    }

    /**
     * The caption font chooser: the platform's families plus "bring your own
     * file". Shown on top of the caption-style panel, which stays open behind
     * it — closing the chooser with a back press therefore lands back on the
     * settings, not on the video. [onPicked] refreshes the caller's font row.
     */
    private fun showFontMenu(parent: Dialog, onPicked: (String) -> Unit) {
        val options = ArrayList<GlassOption>()
        SubtitleFonts.FAMILIES.forEachIndexed { i, key ->
            options.add(
                GlassOption(
                    SubtitleFonts.label(key),
                    if (i == 0) I18n.t("The player's built-in caption font") else null,
                    selected = !SubtitleFonts.isFile(subtitleStyle.font) && subtitleStyle.font == key,
                )
            )
        }
        options.add(
            GlassOption(
                I18n.t("Pick a font file"),
                I18n.t("Use a .ttf or .otf from this device"),
                iconRes = R.drawable.ic_download,
                marker = RowMarker.ICON,
                selected = SubtitleFonts.isFile(subtitleStyle.font),
            )
        )
        showGlassMenu(
            I18n.t("Caption font"),
            options,
            hint = I18n.t("Applies to every subtitle, from any server."),
            iconRes = R.drawable.ic_subtitles,
            onDialog = { it.setOnDismissListener { parent.show() } },
        ) { i ->
            if (i < SubtitleFonts.FAMILIES.size) {
                subtitleStyle = subtitleStyle.copy(font = SubtitleFonts.FAMILIES[i], enabled = true)
                    .copySaved(subsPrefs)
                applySubtitleStyle()
                onPicked(SubtitleFonts.label(subtitleStyle.font))
            } else {
                fontLauncher?.launch(arrayOf("*/*"))
            }
        }
    }

    private fun syncLabel(offsetMs: Long): String = if (offsetMs == 0L) "0.0s" else String.format("%+.1fs", offsetMs / 1000.0)

    /** Applies the saved text-size scale to the player's subtitle view. */
    private fun applySubtitleSize(scale: Float) {
        runCatching { playerView?.getSubtitleView()?.setFractionalTextSize(0.0533f * scale) }
    }

    /** Applies the saved vertical position to the player's subtitle view:
     *  [fraction] of the player height is kept clear below the captions, so a
     *  larger value lifts the subtitles further up off the bottom edge. */
    private fun applySubtitlePosition(fraction: Float) {
        runCatching { playerView?.getSubtitleView()?.setBottomPaddingFraction(fraction) }
    }

    /**
     * Pushes [subtitleStyle] onto the player's SubtitleView.
     *
     * With custom styling off the style handed to media3 is its own default AND
     * the styles embedded in the subtitle file are re-enabled — so a subtitle
     * that ships its own font/colours keeps looking the way its author intended
     * until the user opts into overriding it. With a custom style on, embedded
     * styling is switched off, otherwise the file's styling would win over the
     * user's choice.
     *
     * The size scale is re-applied afterwards: both settings live on the same
     * view, and re-applying is idempotent.
     */
    private fun applySubtitleStyle() {
        runCatching {
            val view = playerView?.getSubtitleView() ?: return
            view.setStyle(SubtitleFonts.captionStyle(this, subtitleStyle))
            view.setApplyEmbeddedStyles(!subtitleStyle.enabled)
            view.setApplyEmbeddedFontSizes(!subtitleStyle.enabled)
            applySubtitleSize(subtitleScale)
        }
    }

    /**
     * Audio track switcher — for dual-audio releases (Hindi/Tamil/Telugu audio
     * on the same video, etc). Lists every audio group the current source
     * exposes, plus Default, and switches with an ExoPlayer track override.
     *
     * A language an extension delivers as its OWN stream rather than as an
     * extra rendition inside one manifest ("MovieBox (Hindi Audio) 1080p",
     * "… (Original Audio) 1080p") is offered here too, as a server row: the
     * track list alone shows a single track on a release that plainly has two
     * audio languages, and reaching the other language otherwise meant going
     * through the server sheet and losing your place in the film.
     *
     * The button sits in the SAME bottom chip row as Quality/Sub so it never
     * overlaps any other control.
     */
    private fun showAudioDialog(waitedForTracks: Boolean = false) {
        val p = player ?: return
        val groups = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        // A manifest's audio renditions are only known once it has been parsed,
        // so opening this sheet during the first buffer used to report a single
        // track — or none at all — on a stream that really carries two. Give
        // the player a moment to finish parsing before answering.
        if (groups.isEmpty() && !waitedForTracks && p.playbackState != Player.STATE_READY) {
            lifecycleScope.launch {
                for (i in 0 until 12) {
                    val done = player?.let { pl ->
                        pl.playbackState == Player.STATE_READY ||
                            pl.currentTracks.groups.any { it.type == C.TRACK_TYPE_AUDIO }
                    } ?: true
                    if (done) break
                    delay(200)
                }
                if (isFinishing || isDestroyed) return@launch
                showAudioDialog(waitedForTracks = true)
            }
            return
        }
        val rows = mutableListOf<TrackRow>()
        var overrideSelected = false
        for (group in groups) {
            val mediaGroup = group.mediaTrackGroup
            for (i in 0 until mediaGroup.length) {
                val f = mediaGroup.getFormat(i)
                val label = languageOf(f.language) ?: trackLabel(f.label ?: f.id, i)
                val sub = trackSub(label, f.label, f.id)
                if (isTrackSelected(p, group, i)) overrideSelected = true
                rows.add(
                    TrackRow(
                        label = label,
                        sub = sub,
                        badge = channelsBadge(f.channelCount) ?: codecBadge(f.sampleMimeType),
                        group = group,
                        index = i,
                    )
                )
            }
        }
        val indexMap = HashMap<Int, Pair<Tracks.Group, Int>>()
        val options = mutableListOf(
            GlassOption(
                I18n.t("Default (adaptive)"),
                I18n.t("Use the track this stream marks as default"),
                selected = !overrideSelected,
            )
        )
        rows.forEachIndexed { i, row ->
            indexMap[i + 1] = row.group to row.index
            options.add(
                GlassOption(
                    label = row.label,
                    sub = row.sub,
                    badge = row.badge,
                    selected = overrideSelected && isTrackSelected(p, row.group, row.index),
                )
            )
        }
        // Audio languages the extension ships as separate servers, so the
        // language can be switched from HERE and the position kept.
        val variantMap = HashMap<Int, Int>()
        audioVariantsFor(currentIndex).forEach { (tag, index) ->
            variantMap[options.size] = index
            options.add(
                GlassOption(
                    label = tag,
                    sub = if (index == currentIndex) I18n.t("Playing now \u2014 %s").replace("%s", sources[index].name)
                    else sources[index].name,
                    badge = I18n.t("Server"),
                    selected = index == currentIndex,
                )
            )
        }
        if (groups.isEmpty() && variantMap.isEmpty()) {
            Toast.makeText(this, I18n.t("No separate audio tracks on this stream"), Toast.LENGTH_SHORT).show()
            return
        }
        showGlassMenu(
            I18n.t("Audio"),
            options,
            hint = if (variantMap.isEmpty()) I18n.t("Some releases ship more than one audio track.")
            else I18n.t("Pick a language \u2014 some servers carry the audio."),
            iconRes = R.drawable.ic_audio,
        ) { which ->
            variantMap[which]?.let { switchAudioVariant(it); return@showGlassMenu }
            if (which == 0) {
                pickAudio = null
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                    .build()
            } else {
                val (group, ti) = indexMap[which] ?: return@showGlassMenu
                val format = group.mediaTrackGroup.getFormat(ti)
                // Remember the LANGUAGE, not the TrackGroup: the group is
                // replaced when the provider subtitles are attached, the
                // language survives.
                pickAudio = TrackPick(C.TRACK_TYPE_AUDIO, format.language, format.label, ti)
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setOverrideForType(
                        TrackSelectionOverride(group.mediaTrackGroup, ImmutableList.of(ti))
                    )
                    .build()
            }
        }
    }

    /** Language words an extension bakes into a server name when it ships a
     *  release in several audio languages as separate streams. */
    private val audioLangWords = listOf(
        "hindi", "tamil", "telugu", "malayalam", "kannada", "bengali", "marathi",
        "punjabi", "gujarati", "bhojpuri", "urdu", "english", "original", "multi",
    )

    /** Tokens a name may carry after its audio marker ("1080p", "Dub") — skipped
     *  when looking for a bare language word at the end of a name. */
    private val audioTrailerWords = setOf(
        "audio", "dub", "dubbed", "dual", "1080p", "720p", "480p", "2160p", "4k",
        "hd", "fhd", "sd", "uhd",
    )

    /** The audio language a server name advertises ("MovieBox (Hindi Audio)
     *  1080p" -> "Hindi Audio"), or null when the name says nothing about it.
     *  A bracketed marker is taken as-is; a bare language word only counts as
     *  the last meaningful token, so a title that merely CONTAINS the word
     *  "Hindi" — or a server named "TamilBlasters · Server 1" — never reads as
     *  an audio variant. */
    private fun audioTagOf(name: String): String? {
        Regex("""[\(\[]([^\)\]]*?(?:audio|dub)[^\)\]]*?)[\)\]]""", RegexOption.IGNORE_CASE)
            .find(name)?.let { return it.groupValues[1].trim() }
        val tokens = name.split(Regex("[\\s\u00B7|\\-_/]+")).filter { it.isNotBlank() }
        for (i in tokens.indices.reversed()) {
            val token = tokens[i].trim(',', ':', '.')
            val low = token.lowercase()
            if (low in audioTrailerWords) continue
            if (low in audioLangWords) return token
            break
        }
        return null
    }

    /** A server name with its audio marker, brackets and resolution suffix
     *  stripped — two servers of one film in different languages reduce to the
     *  same string, which is how the variants are matched. */
    private fun audioBaseName(name: String, tag: String): String {
        val at = name.indexOf(tag, ignoreCase = true)
        val stripped = if (at >= 0) name.removeRange(at, at + tag.length) else name
        return stripped
            .replace(Regex("""[\(\)\[\]]"""), " ")
            .replace(Regex("(?i)\\b\\d{3,4}p\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()
    }

    /** Servers for the CURRENT title that differ from each other only by audio
     *  language — the "(Hindi Audio)" / "(Original Audio)" pair an extension
     *  emits when it delivers a multi-audio film as several streams. Kept
     *  deliberately conservative: a candidate only counts when at least two
     *  servers reduce to the same base name, so a stray language word can never
     *  invent a row. Returns (display tag, source index) pairs. */
    private fun audioVariantsFor(activeIndex: Int): List<Pair<String, Int>> {
        val activeTag = sources.getOrNull(activeIndex)?.let { audioTagOf(it.name) }
        val activeBase = if (activeTag != null) audioBaseName(sources[activeIndex].name, activeTag) else null
        val candidates = sources.mapIndexedNotNull { i, s ->
            val tag = audioTagOf(s.name) ?: return@mapIndexedNotNull null
            val base = audioBaseName(s.name, tag)
            if (base.length < 3) return@mapIndexedNotNull null
            Triple(i, tag, base)
        }.filter { activeBase == null || it.third == activeBase }
        if (candidates.size < 2) return emptyList()
        return candidates.map { (i, tag, _) -> tag.replaceFirstChar { it.uppercase() } to i }
    }

    /** Switches to a sibling server that carries a different audio language,
     *  keeping the position in the film (and the remembered subtitle pick). */
    private fun switchAudioVariant(index: Int) {
        if (index == currentIndex || index !in sources.indices) return
        val position = player?.currentPosition ?: 0L
        if (position > 2_000L) {
            // Same film, same place: an audio change must not restart it.
            startPositionMs = position
            seekPending = true
        }
        noSubsRetry = false
        val name = sources[index].name
        playSource(index)
        Toast.makeText(this, I18n.t("Switching audio \u2014 %s").replace("%s", name), Toast.LENGTH_SHORT).show()
    }

    /** Resets the per-server header walk, so the next attempt starts from the
     *  full header set instead of resuming at the variant that just failed on a
     *  different (and now dead) server. Also clears the persisted-source marker
     *  since the source about to be tried is a new one. */
    private fun resetHeaderWalk() {
        headerVariant = 0
        lastSavedSourceIndex = -1
        lastSavedVariant = -1
    }

    private fun playSource(index: Int) {
        if (index < 0 || index >= sources.size) {
            showError(I18n.t("No more servers to try."), false)
            return
        }
        if (index != currentIndex) headerVariant = 0
        autoRotated = false
        userRotated = false
        userPickedSubs = false
        currentIndex = index
        // Every route into playback that is NOT an explicit pick in the chooser
        // clears this: only a server the user tapped themselves is allowed to
        // ask before the player moves on (see [failoverFromCurrent]). The
        // chooser's row tap re-sets it after calling this.
        pickedByUser = false
        // From here on the chooser may show this row as the current one.
        playbackCommitted = true
        val src = sources[index]
        // Persisting here would remember a source that has NOT proven itself —
        // a signed link that turns out to be expired, or a URL/host whose right
        // header set we haven't found yet, would then be "the last working
        // server" and get restored on the next play. Only a source that
        // actually rendered (see onRenderedFirstFrame) is remembered.
        if (src.isTorrent && src.infoHash != null) {
            playTorrent(index)
            return
        }
        playDirect(index)
    }

    /** Remembers [src] as the server this video was last played with, so a
     *  replay continues on the same server (see [preferredStartIndex]). */
    private fun rememberPlayedSource(index: Int, src: PlayerSource) {
        if (historyKey.isBlank()) return
        if (index == lastSavedSourceIndex && headerVariant == lastSavedVariant) return
        lastSavedSourceIndex = index
        lastSavedVariant = headerVariant
        val key = historyKey
        val url = src.url
        val name = src.name
        val variant = headerVariant
        // Process-wide scope: this must survive Activity destruction (the
        // record fired from onStop/onDestroy otherwise dies with lifecycleScope).
        (applicationContext as HikariApp).appScope.launch {
            runCatching { (applicationContext as HikariApp).store.setLastSource(key, url, name, variant) }
        }
    }

    /**
     * Torrent source: builds a magnet link from the infoHash and hands it to the
     * CloudStream runtime's Torrent engine (TorrServer, bundled in the APK).
     * The engine boots once, fetches the torrent, and returns a local HLS URL
     * that ExoPlayer then plays like any other stream.
     */
    @Suppress("DEPRECATION")
    private fun playTorrent(index: Int) {
        val src = sources[index]
        errorPanel?.visibility = View.GONE

        torrentDialog?.let { runCatching { it.dismiss() } }
        torrentDialog = showGlassProgress(
            "Torrent stream",
            "Starting torrent engine…\nFirst play can take a few seconds.",
            cancelable = false,
        )

        lifecycleScope.launch {
            val res = try {
                Result.success(withContext(Dispatchers.IO) { transformTorrent(src) })
            } catch (t: Throwable) {
                Result.failure(t)
            }
            torrentDialog?.let { runCatching { it.dismiss() } }
            torrentDialog = null

            res.onSuccess { playable ->
                // TorrServer's /stream/<file>?…&play endpoint serves the torrent
                // file as RAW BYTES (progressive download with Range support) —
                // NOT an HLS manifest. Forcing isM3u8 made ExoPlayer parse the
                // video bytes as a playlist ("Input does not start with the
                // #EXTM3U header"). Leave the mime unset and let ExoPlayer sniff
                // the container, exactly like CloudStream/Aniyomi do.
                val converted = src.copy(
                    url = playable.url,
                    headers = playable.referer?.takeIf { it.isNotBlank() }
                        ?.let { mapOf("Referer" to it) } ?: emptyMap(),
                    isM3u8 = false,
                    isTorrent = false,
                    torrentStream = true,
                )
                val list = sources.toMutableList()
                list[index] = converted
                sources = list
                notifySourcesChanged()
                Toast.makeText(
                    this@PlayerActivity,
                    I18n.t("Torrent ready — streaming from peers"),
                    Toast.LENGTH_SHORT
                ).show()
                playDirect(index)
            }
            res.onFailure { e ->
                val msg = rootMessage(e)
                val hasNext = currentIndex + 1 < sources.size
                if (hasNext) {
                    noSubsRetry = false
                    Toast.makeText(this@PlayerActivity, I18n.t("Torrent failed — trying next"), Toast.LENGTH_SHORT).show()
                    playSource(currentIndex + 1)
                } else {
                    showError(I18n.t("Torrent playback failed:\n%s").replace("%s", msg), false)
                }
            }
        }
    }

    /** Builds a magnet and asks the CloudStream runtime's Torrent engine to
     *  turn it into a local streamable URL. */
    private suspend fun transformTorrent(src: PlayerSource): com.lagradost.cloudstream3.utils.ExtractorLink {
        val magnet = buildMagnet(src)
        val link = com.lagradost.cloudstream3.utils.newExtractorLink(
            source = "Torrent",
            name = src.name,
            url = magnet,
        )
        val (playable, _) = com.lagradost.cloudstream3.ui.player.Torrent.transformLink(link)
        return playable
    }

    private fun buildMagnet(src: PlayerSource): String {
        // CS3 plugins sometimes hand us a ready magnet link — use it as-is,
        // only making sure the file index is present.
        if (src.url.startsWith("magnet:", true)) {
            return if (src.fileIdx != null && !src.url.contains("index=")) {
                src.url + (if (src.url.contains("?")) "&" else "?") + "index=" + src.fileIdx
            } else src.url
        }
        val hash = src.infoHash ?: return ""
        val sb = StringBuilder("magnet:?xt=urn:btih:$hash")
        if (src.name.isNotBlank()) {
            sb.append("&dn=").append(java.net.URLEncoder.encode(src.name, "UTF-8"))
        }
        val trackers = (src.trackers + TORRENT_TRACKERS).distinct()
        for (t in trackers) {
            val clean = t.removePrefix("tracker:")
            if (clean.startsWith("http://") || clean.startsWith("https://") || clean.startsWith("udp://")) {
                sb.append("&tr=").append(java.net.URLEncoder.encode(clean, "UTF-8"))
            }
        }
        // TorrServer picks the video file inside the torrent by this index.
        src.fileIdx?.let { sb.append("&index=").append(it) }
        return sb.toString()
    }

    private fun rootMessage(e: Throwable): String {
        var t: Throwable? = e
        val sb = StringBuilder()
        var depth = 0
        while (t != null && depth < 4) {
            val m = t.message
            if (!m.isNullOrBlank()) {
                if (sb.isNotEmpty()) sb.append(" → ")
                sb.append(m)
            }
            t = t.cause
            depth++
        }
        return sb.toString().ifBlank { e.javaClass.simpleName }
    }

    /** Parses the "drm" object of the sources payload (see `playerPayload`). */
    private fun parseDrmSpec(o: JSONObject?): DrmSpec? {
        o ?: return null
        val paramsObj = o.optJSONObject("keyRequestParameters") ?: JSONObject()
        val params = HashMap<String, String>()
        val keys = paramsObj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            params[k] = paramsObj.optString(k)
        }
        val spec = DrmSpec(
            kid = o.optString("kid").ifBlank { null },
            key = o.optString("key").ifBlank { null },
            uuid = o.optString("uuid").ifBlank { null },
            kty = o.optString("kty").ifBlank { null },
            licenseUrl = o.optString("licenseUrl").ifBlank { null },
            keyRequestParameters = params,
        )
        if (spec.kid == null && spec.key == null && spec.uuid == null &&
            spec.licenseUrl == null && spec.keyRequestParameters.isEmpty()
        ) return null
        return spec
    }

    /** Maps a DRM scheme UUID (any case, optional "urn:uuid:" prefix) to one of
     *  the three schemes media3/Android can open, or null when unknown. */
    private fun drmSchemeUuid(uuid: String?): java.util.UUID? {
        val u = uuid?.trim()?.lowercase()?.removePrefix("urn:uuid:") ?: return null
        return when (u) {
            C.CLEARKEY_UUID.toString().lowercase() -> C.CLEARKEY_UUID
            C.WIDEVINE_UUID.toString().lowercase() -> C.WIDEVINE_UUID
            C.PLAYREADY_UUID.toString().lowercase() -> C.PLAYREADY_UUID
            else -> null
        }
    }

    /**
     * Builds a media3 DRM session manager for a DRM-protected source, mirroring
     * CloudStream's own player: ClearKey streams are unlocked from the local
     * key (no network round-trip), everything else asks the license server.
     * Returns null for ordinary sources (or when no usable key material exists),
     * so the player then behaves exactly as before.
     */
    private fun buildDrmSessionManager(
        drm: DrmSpec?,
        dataSourceFactory: DataSource.Factory,
    ): DefaultDrmSessionManager? {
        drm ?: return null
        val declared = drmSchemeUuid(drm.uuid)
        val hasKey = !drm.key.isNullOrBlank()
        val hasLicense = !drm.licenseUrl.isNullOrBlank()
        if (!hasKey && !hasLicense) return null
        // Scheme: an explicit UUID wins; otherwise a local key means ClearKey
        // and a license URL means Widevine (the common case).
        val uuid = declared ?: if (hasKey) C.CLEARKEY_UUID else C.WIDEVINE_UUID
        val callback: MediaDrmCallback = if (uuid == C.CLEARKEY_UUID && hasKey) {
            // Exact ClearKey response format CloudStream feeds media3.
            val kty = drm.kty?.takeIf { it.isNotBlank() } ?: "oct"
            val json = "{\"keys\":[{\"kty\":\"$kty\",\"k\":\"${drm.key}\",\"kid\":\"${drm.kid.orEmpty()}\"}]," +
                "\"type\":\"temporary\"}"
            LocalMediaDrmCallback(json.toByteArray(Charsets.UTF_8))
        } else if (hasLicense) {
            HttpMediaDrmCallback(drm.licenseUrl!!, dataSourceFactory)
        } else {
            return null
        }
        return runCatching {
            DefaultDrmSessionManager.Builder()
                .setMultiSession(true)
                .setKeyRequestParameters(drm.keyRequestParameters)
                .setUuidAndExoMediaDrmProvider(uuid, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .build(callback)
        }.onFailure {
            android.util.Log.e("HikariPlayer", "DRM session setup failed (uuid=$uuid)", it)
        }.getOrNull()
    }

    /**
     * Deep-buffer load control tuned for aggregator CDNs.
     *
     * media3's defaults cap the buffer at 50 s (`minBufferMs == maxBufferMs`)
     * and stop loading there. That is fine for a CDN that always delivers
     * faster than real time, but it leaves no reserve for the ones that only
     * burst: the moment throughput dips below the stream's bitrate the 50 s
     * drains away and the user sees the spinner. Raising the ceiling lets
     * ExoPlayer keep downloading ahead whenever the source can outrun
     * playback, banking minutes of runway on a link that has the headroom.
     *
     * This cannot grow memory without bound: media3's own allocator byte
     * target (≈125 MB video + ≈12 MB audio, which `largeHeap="true"` comfortably
     * covers) is still enforced, so a high-bitrate stream stops at the byte cap
     * exactly as it did before — only low/medium-bitrate streams, which have
     * the memory to spare, get the deeper time buffer.
     *
     * Start/resume thresholds keep media3's snappy defaults (1 s to start,
     * 2 s to resume after a stall): a longer resume threshold would only make
     * the spinner itself last longer.
     */
    private fun buildLoadControl(): DefaultLoadControl =
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                60_000,   // minBufferMs — the steady-state bank to keep topped up
                150_000,  // maxBufferMs — ceiling when the link can outrun playback
                1_000,    // bufferForPlaybackMs — how little we need to start
                2_000,    // bufferForPlaybackAfterRebufferMs — how little to resume
            )
            .build()

    /** Safe entry point: any unexpected exception during player setup (a bad
     *  source URL, a plugin-supplied header, an ExoPlayer hiccup) must surface
     *  as "try the next server" or an error panel — never an uncaught crash
     *  that leaves a frozen black screen. */
    private fun playDirect(index: Int) {
        try {
            val src = sources[index]
            // A downloaded copy lives on local storage — nothing to probe, no
            // headers to negotiate, no CDN to fail over from. Straight to
            // ExoPlayer.
            if (src.local) {
                playDirectInner(index)
                return
            }
            // Archive links (.mkv.zip / .rar etc.) are not videos at all —
            // providers occasionally leak them through (4KHDHub's isDirectVideo
            // filters on hostname only, so its hubcloud ".mkv.zip" links pass).
            // Trying one costs a full prepare+error cycle before the failover,
            // so skip to a real server instead.
            if (!src.torrentStream && !src.isM3u8 && !src.isMpd &&
                StreamProbe.isArchive(src.url)
            ) {
                triedUrls.add(src.url)
                if (index + 1 < sources.size) {
                    Toast.makeText(this, I18n.t("Archive link (not a video) — trying next server"), Toast.LENGTH_SHORT).show()
                    noSubsRetry = false
                    playSource(index + 1)
                } else if (!refreshSources(index)) {
                    showError(I18n.t("Only archive links (.zip) were found for this title — no playable video."), false)
                }
                return
            }
            // Extension-less / container-unknown URLs — HLS & DASH manifests
            // served at API paths, and JSON/HTML wrapper pages — get probed
            // once before playback so the real mime/URL is known. Otherwise
            // ExoPlayer treats them as a progressive container and reports
            // ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED on streams that are
            // perfectly playable (the "every yt-dlp source fails" symptom).
            // A URL a probe has already proved dead is skipped outright when
            // there is anywhere else to go, instead of paying ExoPlayer's own
            // error timeout to re-discover it.
            if (!src.torrentStream && !src.local && StreamProbe.knownBad(src.url)) {
                val next = nextUntriedIndex()
                if (next >= 0) {
                    triedUrls.add(src.url)
                    noSubsRetry = false
                    playSource(next)
                    return
                }
            }
            val needsProbe = !src.torrentStream &&
                StreamProbe.needsResolve(src.url, src.isTorrent, src.isM3u8, src.isMpd)
            if (needsProbe) {
                probeAndPlay(index)
                return
            }
            playDirectInner(index)
        } catch (t: Throwable) {
            android.util.Log.e("HikariPlayer", "playDirect failed", t)
            if (index + 1 < sources.size) {
                noSubsRetry = false
                playSource(index + 1)
            } else {
                showError(I18n.t("Playback failed to start:\n%s").replace("%s", rootMessage(t)), false)
            }
        }
    }

    /** Probes the source (via the app-wide [StreamProbe] cache, so any earlier
     *  resolution — this source search, a previous play, a previous session —
     *  makes this instant) and, when it resolves to a real media URL, rewrites
     *  the source before handing it to ExoPlayer. On a cache miss it shows the
     *  progress dialog while resolving. When the probe can't resolve the URL it
     *  moves on to the NEXT server instead of handing ExoPlayer a wrapper page
     *  it is guaranteed to choke on (that wasted a full probe plus a full
     *  player error timeout before the failover, which is what made a broken
     *  4KHDHub wrapper feel twice as slow). */
    private fun probeAndPlay(index: Int) {
        if (index < 0 || index >= sources.size) {
            playDirectInner(index)
            return
        }
        val src = sources[index]
        val cached = StreamProbe.cached(src.url)
        if (cached != null) {
            applyProbe(index, src, cached)
            playDirectInner(index)
            return
        }
        // The full-screen title card already signals "finding a server", so the
        // little probe dialog would just flicker on top of it.
        if (loadingBanner?.visibility != View.VISIBLE) {
            probeDialog = showGlassProgress(src.name, "Preparing stream…", cancelable = false)
        }
        lifecycleScope.launch {
            val clean = sanitizeHeaders(src.headers)
            val headers = when (headerVariant) {
                1 -> clean.filterKeys { !it.equals("Referer", ignoreCase = true) }
                2 -> emptyMap()
                else -> clean
            }
            val ua = headers["User-Agent"]?.takeIf { it.isNotBlank() } ?: Http.UA
            val resolved = StreamProbe.resolve(src.url, headers + mapOf("User-Agent" to ua))
            probeDialog?.let { runCatching { it.dismiss() } }
            probeDialog = null
            if (currentIndex != index) return@launch
            if (resolved == null && StreamProbe.knownBad(src.url)) {
                // The probe reached the host and was told the URL is dead (an
                // HTTP 5xx/404/410). Handing that to ExoPlayer anyway cost a
                // full prepare + error cycle per dead server — with a provider
                // like MovieBlast handing out four dead qualities, that is what
                // "it keeps loading server and then fails" was. Walk on now,
                // while other servers are still waiting to be tried.
                triedUrls.add(src.url)
                val next = nextUntriedIndex()
                if (next >= 0) {
                    noSubsRetry = false
                    playSource(next)
                    return@launch
                }
            }
            if (resolved != null) applyProbe(index, src, resolved)
            // ALWAYS hand the source to ExoPlayer — resolved when the probe
            // identified a real media URL, otherwise the ORIGINAL url. This is
            // the 0.3.65 behavior and it matters: 4KHDHub's HubCloud wrapper
            // pages are served at extension-less paths, and ExoPlayer follows
            // the redirect chain itself and sniffs the container, so playing
            // the raw URL works even when our probe can't classify it. Skipping
            // to the next server on an inconclusive probe was what made 4KHDHub
            // "just skip" on every source. If the raw URL really is unplayable,
            // the player's own error handler advances to the next server.
            playDirectInner(index)
        }
    }

    private fun applyProbe(index: Int, src: PlayerSource, resolved: StreamProbe.Resolved) {
        val list = sources.toMutableList()
        list[index] = StreamProbe.apply(src.toStreamSource(), resolved).toPlayerSource()
        sources = list
        notifySourcesChanged()
    }

    private fun playDirectInner(index: Int) {
        if (index < 0 || index >= sources.size) {
            showError(I18n.t("No more servers to try."), false)
            return
        }
        dismissSlowDialog()
        currentIndex = index
        val src = sources[index]
        // Remember what we've actually handed to ExoPlayer this session — a
        // later re-extraction usually repeats most of these URLs, and freshIndex
        // must not pick one we already know dies.
        triedUrls.add(src.url)

        // The Source pill keeps its static label; the active server's name is
        // shown by the top-bar source chip below.
        val sourceBadge = src.name.substringBefore("|").trim().ifBlank { src.name }
        if (sourceBadge.isNotBlank()) {
            badgeSource?.text = sourceBadge
            badgeSource?.visibility = View.VISIBLE
        }
        errorPanel?.visibility = View.GONE
        if (loadingBanner?.visibility != View.VISIBLE && loadingSpinner?.visibility != View.VISIBLE)
            showLoadingCover()

        player?.let { old ->
            old.removeListener(listener)
            old.release()
        }
        playerView?.player = null
        firstFrameTask?.let { bufferingWatchdog.removeCallbacks(it) }
        firstFrameTask = null
        renderedFirstFrame = false
        firstFrameRetried = false
        // A brand-new player instance means a brand-new video renderer, which
        // starts with no effects pipeline attached (see [videoSinkArmed]).
        videoSinkArmed = false

        // Send the SOURCE's own User-Agent when it declares one (extractors like
        // TamilBlasters' StreamHG set a specific Chrome UA their CDN's WAF
        // requires), falling back to our Chrome UA. Never brand-mangle it with
        // a "Hikari/" prefix — a malformed UA gets those hosts to answer 403.
        // When a CDN keeps rejecting the request, headerVariant walks the header
        // set down to nothing (some CDNs 403 any request carrying a Referer).
        // Header values are sanitized FIRST: some addons' extractors ship a
        // User-Agent with non-ASCII characters (a Cyrillic look-alike 'µ' inside
        // an otherwise-ASCII Chrome UA is the classic one), and OkHttp rejects
        // any header value with chars > 127 via IllegalArgumentException — which
        // media3 surfaces as a fatal playback error even though the stream is
        // fine. Sanitizing here means a sloppy extension can never crash the
        // player, now or in the future.
        val cleanHeaders = sanitizeHeaders(src.headers)
        val sourceHeaders = when (headerVariant) {
            1 -> cleanHeaders.filterKeys { !it.equals("Referer", ignoreCase = true) }
            2 -> emptyMap()
            else -> cleanHeaders
        }
        val ua = sourceHeaders["User-Agent"]?.takeIf { it.isNotBlank() } ?: Http.UA
        // Local downloads read off the filesystem through DefaultDataSource
        // (which handles file:// and any local .m3u8's relative segment paths);
        // network sources keep the header-aware OkHttp factory.
        val networkFactory: DataSource.Factory = OkHttpDataSource.Factory(client)
            .setUserAgent(ua)
            .setDefaultRequestProperties(sourceHeaders)
        // DefaultDataSource sits IN FRONT of the OkHttp factory, and that is
        // what makes the provider subtitles work at all: they are handed to
        // ExoPlayer as local (file://) URIs, and OkHttpDataSource alone only
        // speaks http(s) — it throws on any other scheme, so every subtitle
        // listed in the picker failed to load and drew nothing. DefaultDataSource
        // routes file:/data:/content: locally and hands everything else to
        // OkHttp, so the network behaviour (UA, headers, retry policy) is
        // unchanged.
        val dataSourceFactory: DataSource.Factory = DefaultDataSource.Factory(this, networkFactory)

        // DRM-protected sources (ClearKey/Widevine) get a matching media3 DRM
        // session manager; without it ExoPlayer opens the encrypted manifest
        // with no keys and renders a black screen while the timeline still runs.
        val drmManager = buildDrmSessionManager(src.drm, dataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            // Ride out transient CDN hiccups quietly — a fresh connection and a
            // Range-resumed read — instead of letting one dropped socket tear
            // the whole player down, while still failing FAST on terminal ones
            // (expired 403 links, malformed data) so the failover to the next
            // server stays snappy (see RetryFriendlyLoadErrorPolicy).
            .setLoadErrorHandlingPolicy(RetryFriendlyLoadErrorPolicy())
        if (drmManager != null) {
            val manager: DefaultDrmSessionManager = drmManager
            mediaSourceFactory.setDrmSessionManagerProvider { manager }
        }

        val player = ExoPlayer.Builder(this)
            .setRenderersFactory(
                // nextlib's NextRenderersFactory is a drop-in for
                // DefaultRenderersFactory that ALSO registers FFmpeg software
                // decoders (media3-extractor not needed for it; it's built
                // against media3 1.7.1, matching libs.versions.toml). Mode ON =
                // FFmpeg is only used when the platform MediaCodec can't handle
                // a track — e.g. the EAC-3/DDP 5.1 audio on many 4kHDHub MKV
                // streams, which otherwise plays with NO sound on devices
                // lacking an EAC-3 hardware decoder. Hardware decoding of
                // H.264/HEVC video is still preferred (avoids software-decoding
                // 4K), and decoder fallback degrades a choking hardware codec to
                // a software one instead of freezing into a black screen.
                NextRenderersFactory(this)
                    .setEnableDecoderFallback(true)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            )
            .setMediaSourceFactory(mediaSourceFactory)
            // Deep-buffer, stall-resistant buffering policy — see buildLoadControl.
            .setLoadControl(buildLoadControl())
            // Hold the CPU + Wi-Fi radio awake for the whole session (including
            // PiP/background audio). A radio that drops into power-save
            // mid-stream is a classic "it randomly stops to buffer" cause on
            // some devices, and media3's default wake mode is NONE.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            // 10s steps on the centre rewind/forward buttons (and media3's own
            // seek handling), matching the reference player. Set here rather
            // than via PlayerView XML attrs, which this media3 version lacks.
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        this.player = player
        if (noSubsRetry) {
            // The previous attempt crashed on a garbage in-manifest subtitle
            // track — disable text tracks for this retry.
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        }
        player.addListener(listener)
        playerView?.player = player

        // Start the video IMMEDIATELY, without subtitles. A broken/expired
        // subtitle URL must never kill playback (some providers emit subtitle
        // URLs that return junk like "1", which media3 treats as a fatal parse
        // error). Subtitles are fetched and validated in the background and
        // only added if their content is actually a subtitle.
        val mime = mainMimeOf(src)
        val itemBuilder = MediaItem.Builder().setUri(src.url)
        if (mime != null) itemBuilder.setMimeType(mime)
        // A subtitle the user added themselves is attached EAGERLY, unlike the
        // provider's (which are deferred and validated in the background because
        // a dead subtitle URL must never kill playback). These are local files
        // that were already validated when they were picked, so they can be
        // trusted on the item from the very first frame — and a server change
        // keeps them without a second round trip.
        if (userSubs.isNotEmpty()) {
            runCatching { itemBuilder.setSubtitleConfigurations(userSubConfigs()) }
        }

        // ---- Video enhance: arm the effects pipeline BEFORE prepare() -------
        // media3 only builds the video-effects pipeline while the video renderer
        // is being ENABLED, from the effect list present at that instant
        // (MediaCodecVideoRenderer.onEnabled); a setVideoEffects() call made
        // afterwards is silently dropped when the renderer was enabled without
        // one. prepare() is what enables it, so a preset that should be in
        // effect from the very first frame has to be handed over right here —
        // this is exactly why the presets used to do nothing at all.
        //
        // Natural deliberately arms NOTHING: media3 then copies every decoded
        // frame straight to the surface, with no GL pass. The HDR-safe variant
        // of the preset is handed over because the stream's colour transfer is
        // not known until prepare() has run; onTracksChanged refines it to the
        // exact SDR/HDR effect list a moment later.
        val armPreset = EnhancePreset.fromKey(enhancePresetKey)
        if (armPreset != EnhancePreset.NATURAL && !enhanceUnsupported) {
            runCatching {
                player.setVideoEffects(armPreset.effects(hdr = true))
                videoSinkArmed = true
            }.onFailure {
                videoSinkArmed = false
                com.hikari.app.data.Logs.logError(
                    "Player",
                    "could not arm the video effects pipeline",
                    it
                )
                android.util.Log.w("HikariPlayer", "could not arm video effects", it)
            }
        }

        player.setMediaItem(itemBuilder.build())
        player.prepare()
        player.playWhenReady = true
        applySpeed(SPEEDS[speedIndex])
        // Opened via a "download this" tap: the chooser is put up once a server
        // is actually PLAYING (see the STATE_READY branch of the player
        // listener), not here. Handing it to whatever was prepared first meant a
        // download could be started from a source that never rendered — a file
        // that fails to convert a minute later. The flag stays armed across an
        // automatic failover, so the first source that does play is the one the
        // download is offered on (and it is cleared for good once shown, so
        // backing out of the chooser leaves normal playback alone).
        // A video source that reaches READY but never draws a frame is a
        // silently-hanging decoder (black screen) — the buffering watchdog
        // can't catch it because playbackState is already READY. Give it 20s
        // to render its first frame, then recover (next server, or restart)
        // instead of stranding the user on a dead black screen. A DRM source is
        // armed too: a missing/unsupported key fails exactly this way.
        if (mime != null || drmManager != null) {
            firstFrameTask?.let { bufferingWatchdog.removeCallbacks(it) }
            val task = Runnable {
                firstFrameTask = null
                val p = player ?: return@Runnable
                if (renderedFirstFrame) return@Runnable
                val hasVideo = p.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO }
                if (!hasVideo) return@Runnable // audio-only: no video frames expected
                if (p.playbackState == Player.STATE_ENDED) return@Runnable
                android.util.Log.w("HikariPlayer", "No first frame rendered in 20s — decoder hang")
                if (currentIndex + 1 < sources.size) {
                    Toast.makeText(this@PlayerActivity, I18n.t("Video stuck — trying next server"), Toast.LENGTH_SHORT).show()
                    noSubsRetry = false
                    playSource(currentIndex + 1)
                } else if (!firstFrameRetried) {
                    firstFrameRetried = true
                    Toast.makeText(this@PlayerActivity, I18n.t("Video stuck — restarting"), Toast.LENGTH_SHORT).show()
                    noSubsRetry = false
                    playSource(currentIndex)
                } else {
                    showError(I18n.t("Playback started but no video frame was rendered."), false)
                }
            }
            firstFrameTask = task
            bufferingWatchdog.postDelayed(task, 20_000L)
        }
        scheduleBufferingWatchdog()

        if (noSubsRetry) return@playDirectInner

        val playedIndex = index
        lifecycleScope.launch {
            try {
                val configs = buildSubtitleConfigs(src)
                if (configs.isEmpty()) return@launch
                if (currentIndex != playedIndex) return@launch
                val p = player ?: return@launch
                // Re-prepare with the validated subtitle tracks. This rebuilds
                // the media item, so the track groups are brand new — the
                // user's remembered audio/subtitle pick is re-applied to them
                // by applyStickyPicks from onTracksChanged.
                p.setMediaItem(mediaItemWithSubtitles(src, configs), false)
                p.prepare()
            } catch (t: Throwable) {
                android.util.Log.e("HikariPlayer", "subtitle attach failed", t)
            }
        }
    }

    /**
     * If the current server still hasn't started delivering video 20s after
     * prepare, ask the user: switch to the next server or keep waiting — and
     * auto-switch after 3s if they don't answer. CloudStream plays in ~5s, but
     * some servers genuinely take 15-20s to spin up (cold CDN edge, slow
     * origin), so give them that long first. Only fires while nothing has been
     * played yet. Torrents get a longer budget: TorrServer must discover peers
     * and pull the first pieces from cold, which regularly takes 30s+. A
     * "Wait 30s" answer re-arms the watchdog for another 30s, after which the
     * same prompt reappears if the server still isn't playing.
     */
    private fun scheduleBufferingWatchdog(waitBudget: Long? = null) {
        watchdogTask?.let { bufferingWatchdog.removeCallbacks(it) }
        watchdogTask = null
        val torrent = currentIndex in sources.indices && sources[currentIndex].torrentStream
        val budget = waitBudget ?: if (torrent) 50_000L else 20_000L
        val task = Runnable {
            watchdogTask = null
            val p = player ?: return@Runnable
            if (p.playbackState == Player.STATE_BUFFERING || p.playbackState == Player.STATE_IDLE) {
                if (p.currentPosition > 0) return@Runnable
                promptSlowServer(torrent)
            }
        }
        watchdogTask = task
        bufferingWatchdog.postDelayed(task, budget)
    }

    /** "Server too slow" prompt: Wait 30s or switch to the next server, with a
     *  3-second countdown after which it switches automatically if the user
     *  doesn't answer. Switching instantly moves to the next source. */
    private fun promptSlowServer(torrent: Boolean) {
        // This server is a dud: nothing has played after its whole budget. Mark
        // it — the URL as tried (so no failover hands it back) and its HOST as
        // failed for the session (the same mirror serves every quality of the
        // same file, and the start-index pick consults this). The user's report
        // was exactly this: "if server not responding then skip it".
        sources.getOrNull(currentIndex)?.let { src ->
            if (!src.isTorrent && !src.local && src.url.isNotBlank()) {
                triedUrls.add(src.url)
                val h = mirrorHostOf(src.url)
                if (h.isNotBlank()) deadHosts.add(h)
            }
        }
        if (currentIndex + 1 >= sources.size) {
            // Nothing left to walk to — YET. A player that has just opened is
            // routinely holding one server while the search is still working
            // (the detail screen opens it on the first hit and the cross pass +
            // sweep keep finding more for minutes), so declaring playback dead
            // here stops the user on a server that is merely early, and the
            // "Playback failed / Server is not responding (still buffering after
            // 20s)" panel then sits over a search that is still delivering
            // servers — the "why does it get stuck and stop all the other
            // server searches" report. Wait for the search instead, and start
            // the first replacement the moment it lands.
            if (!torrent && liveSessionId != null && !liveSearchDone) {
                awaitReplacementForStalledServer(sources.getOrNull(currentIndex))
                return
            }
            showError(
                if (torrent) I18n.t("Torrent did not start streaming (no peers?)")
                else I18n.t("Server is not responding (still buffering after 20s)."),
                false
            )
            return
        }
        if (slowDialog != null) return
        var countdown: TextView? = null
        val dialog = showGlassMenu(
            I18n.t("Server too slow"),
            listOf(
                GlassOption(
                    I18n.t("Switch now"),
                    I18n.t("Jump to the next server"),
                    iconRes = R.drawable.ic_server,
                    chevron = true,
                    marker = RowMarker.ICON,
                ),
                GlassOption(
                    I18n.t("Wait 30s"),
                    I18n.t("Give this server more time"),
                    iconRes = R.drawable.ic_speed,
                    chevron = true,
                    marker = RowMarker.ICON,
                ),
            ),
            hint = I18n.t("Switching to the next server in 3s…"),
            iconRes = R.drawable.ic_server,
            cancelable = false,
            onHint = { countdown = it },
        ) { which ->
            dismissSlowDialog()
            noSubsRetry = false
            if (which == 0) {
                Toast.makeText(this@PlayerActivity, I18n.t("Switching server"), Toast.LENGTH_SHORT).show()
                playSource(currentIndex + 1)
            } else {
                // Stay on this server; the same prompt reappears after 30s if
                // it still hasn't started playing.
                scheduleBufferingWatchdog(30_000L)
            }
        }
        slowDialog = dialog
        val start = System.currentTimeMillis()
        val ticker = object : Runnable {
            override fun run() {
                if (slowDialog != dialog) return
                val remaining = 3_000 - (System.currentTimeMillis() - start)
                if (remaining <= 0) {
                    dismissSlowDialog()
                    noSubsRetry = false
                    Toast.makeText(
                        this@PlayerActivity,
                        I18n.t("Server too slow — switching to next"),
                        Toast.LENGTH_SHORT
                    ).show()
                    playSource(currentIndex + 1)
                    return
                }
                countdown?.text = "Switching to the next server in ${(remaining / 1000) + 1}s…"
                bufferingWatchdog.postDelayed(this, 250)
            }
        }
        slowDialogTicker = ticker
        bufferingWatchdog.post(ticker)
    }

    private fun dismissSlowDialog() {
        slowDialogTicker?.let { bufferingWatchdog.removeCallbacks(it) }
        slowDialogTicker = null
        slowDialog?.let { runCatching { it.dismiss() } }
        slowDialog = null
    }

    /** How long [awaitReplacementForStalledServer] waits for the search to bring
     *  ANY other server before it admits the playback cannot start. Long on
     *  purpose: the alternative is the failure panel the user reported, shown
     *  over a search that was still finding servers. */
    private val stalledReplacementWaitMs: Long = 180_000L

    /** True while the current server has stalled and playback is waiting for the
     *  search to hand over a replacement (see
     *  [awaitReplacementForStalledServer]). Also mirrored on the cover's status
     *  line, so the wait is visible instead of looking frozen. */
    @Volatile
    private var awaitingReplacement = false

    /** How many servers were known when the wait started — a wait that ends with
     *  the search finished and no new server has nothing left to hope for. */
    private var awaitingReplacementAtSize = 0

    /**
     * The current server never started and there is no other server to try yet.
     *
     * Instead of failing the playback, wait for the search — the cross pass and
     * the background sweep keep finding servers for minutes after the first one
     * lands, and every one of them is streamed into this player's own list. The
     * first replacement starts automatically (the live collector calls
     * [playReplacementIfWaiting]), so the user never has to tap "Retry all".
     *
     * The wait ends early the moment the search says it is finished AND nothing
     * new arrived, and at worst after [stalledReplacementWaitMs] — either way
     * the honest failure panel is shown, never a lie about the search.
     */
    private fun awaitReplacementForStalledServer(stalled: PlayerSource?) {
        if (awaitingReplacement) return
        awaitingReplacement = true
        awaitingReplacementAtSize = sources.size
        val name = stalled?.name?.substringBefore("|")?.trim().orEmpty()
        val line = if (name.isBlank()) {
            I18n.t("That server isn't responding — looking for another one…")
        } else {
            I18n.t("%s isn't responding — looking for another server…").replace("%s", name)
        }
        loadingStatusBase = line
        loadingStatus?.text = line
        loadingSpinnerStatus?.text = line
        if (loadingBanner?.visibility != View.VISIBLE &&
            loadingSpinner?.visibility != View.VISIBLE
        ) {
            showLoadingCover()
        }
        Toast.makeText(this, line, Toast.LENGTH_LONG).show()
        // Ask the detail screen for a fresh extraction straight away as well: the
        // providers hand out different (live) links on a second ask, so this is a
        // second source of replacements besides the search that is still running.
        liveSessionId?.let { StreamsLive.requestRefresh(it) }
        lifecycleScope.launch {
            val deadline = System.currentTimeMillis() + stalledReplacementWaitMs
            while (System.currentTimeMillis() < deadline) {
                delay(400)
                if (!awaitingReplacement) return@launch
                val idx = replacementIndexOf(stalled)
                if (idx >= 0) {
                    playReplacement(idx)
                    return@launch
                }
                if (liveSearchDone && sources.size == awaitingReplacementAtSize) break
            }
            if (!awaitingReplacement) return@launch
            awaitingReplacement = false
            hideLoadingBanner(immediate = true)
            showError(I18n.t("Server is not responding (still buffering after 20s)."), false)
        }
    }

    /** The first server that can be tried in place of the stalled one: not the
     *  stalled server itself, not already tried, and not on a host this session
     *  has already seen fail. -1 when there is nothing new to try. */
    private fun replacementIndexOf(stalled: PlayerSource?): Int {
        val stalledUrl = stalled?.url.orEmpty()
        for (i in sources.indices) {
            val s = sources[i]
            if (i == currentIndex && sources.size > 1) continue
            if (s.url.isBlank()) continue
            if (s.url == stalledUrl) continue
            if (s.url in triedUrls) continue
            if (!s.isTorrent && mirrorHostOf(s.url) in deadHosts) continue
            return i
        }
        return -1
    }

    /** Starts the replacement the search has just delivered, ending the wait for
     *  a stalled server. Called by the live server collector. Returns true when
     *  it took over (so the caller stops there). */
    private fun playReplacementIfWaiting(): Boolean {
        if (!awaitingReplacement) return false
        val idx = replacementIndexOf(sources.getOrNull(currentIndex))
        if (idx < 0) return false
        playReplacement(idx)
        return true
    }

    private fun playReplacement(idx: Int) {
        awaitingReplacement = false
        noSubsRetry = false
        val name = sources.getOrNull(idx)?.name?.substringBefore("|")?.trim().orEmpty()
        Toast.makeText(
            this,
            if (name.isBlank()) I18n.t("Trying another server")
            else I18n.t("Trying another server — %s").replace("%s", name),
            Toast.LENGTH_SHORT,
        ).show()
        playSource(idx)
    }

    /** The silent half of a failover: hand [nextIndex] to the player. */
    private fun advanceToServer(nextIndex: Int) {
        noSubsRetry = false
        SlowNetTip.onServerFailed()
        Toast.makeText(this, I18n.t("Server failed — trying next"), Toast.LENGTH_SHORT).show()
        playSource(nextIndex)
    }

    /**
     * A server the user picked themselves failed, and the setting to ask is on:
     * offer the three ways forward instead of switching silently. This is the
     * "10 servers, I tapped the 4th, it didn't start" case.
     *
     * "Choose another server" re-opens the SAME grouped chooser the user picked
     * from — it is not a fresh search: it lists every server found so far and
     * keeps appending the ones still arriving (the live search never stopped),
     * so the list continues where it left off instead of restarting. When this
     * launch came from a Download tap, the download chooser is re-armed too, so
     * whichever server the user lands on offers to download it.
     *
     * Unanswered after 8s it advances on its own — a failure must never strand
     * playback on the title card.
     */
    private fun promptServerFailed(nextIndex: Int, details: String) {
        if (failDialog != null) return
        val nextName = sources.getOrNull(nextIndex)?.name
            ?: I18n.t("Next server")
        val count = sources.size
        var countdown: TextView? = null
        val dialog = showGlassMenu(
            I18n.t("Server failed"),
            listOf(
                GlassOption(
                    I18n.t("Try next server"),
                    nextName,
                    iconRes = R.drawable.ic_server,
                    chevron = true,
                    marker = RowMarker.ICON,
                ),
                GlassOption(
                    I18n.t("Choose another server"),
                    I18n.t("All servers found so far (%s)").replace("%s", count.toString()),
                    iconRes = R.drawable.ic_server,
                    chevron = true,
                    marker = RowMarker.ICON,
                ),
                GlassOption(
                    I18n.t("Always switch automatically"),
                    I18n.t("Never ask again — switch servers on its own"),
                    iconRes = R.drawable.ic_speed,
                    chevron = true,
                    marker = RowMarker.ICON,
                ),
            ),
            hint = I18n.t("Switching to the next server automatically…") + " (8s)",
            iconRes = R.drawable.ic_server,
            message = details,
            cancelable = true,
            onHint = { countdown = it },
        ) { which ->
            dismissFailDialog()
            when (which) {
                1 -> {
                    // Back to the list the tap came from. Clear the "picked"
                    // flag first: the chooser's own row tap sets it again for
                    // whichever server the user lands on next.
                    pickedByUser = false
                    noSubsRetry = false
                    if (downloadFlowActive) openDownloadPending = true
                    showServerChooser()
                }
                2 -> {
                    // Stop asking from now on — persist it so the next video
                    // fails over silently too.
                    failoverAskOnFailure = false
                    failoverAskPrefLoaded = true
                    lifecycleScope.launch {
                        runCatching {
                            (applicationContext as HikariApp).store.setFailoverAskOnFailure(false)
                        }
                    }
                    advanceToServer(nextIndex)
                }
                else -> {
                    // "Try next server": the user is still steering, so the
                    // next failure asks again (the escape is option 2).
                    pickedByUser = true
                    if (downloadFlowActive) openDownloadPending = true
                    advanceToServer(nextIndex)
                }
            }
        }
        failDialog = dialog
        val start = System.currentTimeMillis()
        val ticker = object : Runnable {
            override fun run() {
                if (failDialog != dialog) return
                val remaining = 8_000 - (System.currentTimeMillis() - start)
                if (remaining <= 0) {
                    dismissFailDialog()
                    pickedByUser = false
                    advanceToServer(nextIndex)
                    return
                }
                countdown?.text = I18n.t("Switching to the next server automatically…") +
                    " (${(remaining / 1000) + 1}s)"
            }
        }
        failDialogTicker = ticker
        bufferingWatchdog.post(ticker)
    }

    private fun dismissFailDialog() {
        failDialogTicker?.let { bufferingWatchdog.removeCallbacks(it) }
        failDialogTicker = null
        failDialog?.let { runCatching { it.dismiss() } }
        failDialog = null
    }

    /**
     * Fetches a subtitle file with the given headers, validates it, and caches
     * its raw text (so a later sync-offset change can re-time it without a
     * second network fetch). Returns null for anything that 404s, errors, or
     * returns junk, so a dead provider subtitle is silently dropped instead of
     * crashing the player.
     */
    private fun fetchSubtitleText(s: SubtitleSource, headers: Map<String, String>): String? {
        subtitleRawCache[s.url]?.let { return it }
        // Two attempts: with the source's own headers, then bare. Plenty of
        // subtitle hosts 403 a request that carries a Referer (or an
        // extension's cookies) while others only answer WITH it, and a
        // subtitle that fails to load is invisible to the user — the picker row
        // is there, choosing it just shows nothing.
        for (attempt in 0..1) {
            val h = if (attempt == 0) headers else emptyMap()
            val bytes = Http.getBytes(s.url, h) ?: continue
            val text = decodeSubtitleBytes(bytes) ?: continue
            if (!isSubtitleText(text)) continue
            subtitleRawCache[s.url] = text
            return text
        }
        return null
    }

    /** Bytes → subtitle text. Handles the containers providers really wrap
     *  subtitles in: raw UTF-8, GZIP (".srt.gz"), a ZIP holding the subtitle
     *  file, and UTF-16 (BOM, or NUL-padded ASCII). UTF-16 read as UTF-8 looks
     *  like line after line of NULs, which is another way a perfectly good
     *  subtitle used to be thrown away as junk. */
    private fun decodeSubtitleBytes(bytes: ByteArray): String? {
        if (bytes.size < 4 || bytes.size > 8 * 1024 * 1024) return null
        if (bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            val inner = runCatching {
                java.util.zip.GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
            }.getOrNull() ?: return null
            return decodeSubtitleBytes(inner)
        }
        if (bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            val inner = runCatching {
                java.util.zip.ZipInputStream(bytes.inputStream()).use { zin ->
                    var entry = zin.nextEntry
                    while (entry != null && entry.isDirectory) entry = zin.nextEntry
                    if (entry == null) ByteArray(0) else zin.readBytes()
                }
            }.getOrNull() ?: return null
            return decodeSubtitleBytes(inner)
        }
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val charset = when {
            b0 == 0xFF && b1 == 0xFE -> Charsets.UTF_16LE
            b0 == 0xFE && b1 == 0xFF -> Charsets.UTF_16BE
            // NUL every other byte = UTF-16 with no BOM.
            bytes.take(64).count { it == 0.toByte() } > 24 ->
                if (b0 == 0) Charsets.UTF_16BE else Charsets.UTF_16LE
            else -> Charsets.UTF_8
        }
        return String(bytes, charset).trimStart('\uFEFF', '\u0000', ' ', '\n', '\r')
    }

    /** True when [text] really is a subtitle: a recognisable format AND at least
     *  one cue. The URL's extension is only a hint — providers serve ASS behind
     *  ".srt" paths and VTT behind "?format=srt" — and a file whose header
     *  survives but which carries no cues parses to ZERO subtitles in media3:
     *  a row in the picker that shows nothing when selected, which is exactly
     *  the "I selected the subtitle and it never appeared" report. Cue-less
     *  files are rejected here so they never become a phantom row. */
    private fun isSubtitleText(text: String): Boolean {
        if (text.isBlank()) return false
        val cue = Regex("\\d{1,2}:\\d{2}(:\\d{2})?[,.]\\d{1,3}\\s*-->").containsMatchIn(text)
        return when {
            text.contains("WEBVTT", true) -> cue
            text.contains("Dialogue:", true) -> true
            // an ASS/SSA header with no Dialogue line = no subtitles in it
            text.contains("Script Info", true) -> false
            text.contains("<tt", true) -> Regex("<p[ >]").containsMatchIn(text)
            cue -> true
            else -> false
        }
    }

    /** Shifts every cue timestamp in an SRT/VTT/ASS subtitle by offsetMs
     *  (negative = earlier / "slow" the subtitles, positive = later / "fast"),
     *  clamped to ≥ 0. Unrecognised formats are returned unchanged. */
    private fun shiftSubtitleText(text: String, offsetMs: Long, url: String): String {
        if (offsetMs == 0L) return text
        val isAss = text.contains("Dialogue:", true) ||
            url.contains(".ass", true) || url.contains(".ssa", true)
        if (isAss) {
            return Regex("(Dialogue:\\s*[^,]*,\\s*)(\\d+:\\d{2}:\\d{2}\\.\\d{2})(,)(\\s*\\d+:\\d{2}:\\d{2}\\.\\d{2})")
                .replace(text) { m ->
                    m.groupValues[1] + shiftAssClock(m.groupValues[2], offsetMs) +
                        m.groupValues[3] + shiftAssClock(m.groupValues[4], offsetMs)
                }
        }
        val isVtt = text.contains("WEBVTT", true) && !text.contains("X-TIMESTAMP-MAP", true)
        return if (isVtt) {
            Regex("(\\d{1,2}):(\\d{2}):(\\d{2})\\.(\\d{3})").replace(text) { m ->
                formatClock(shiftClock(m, offsetMs), ".")
            }
        } else {
            Regex("(\\d{1,2}):(\\d{2}):(\\d{2}),(\\d{3})").replace(text) { m ->
                formatClock(shiftClock(m, offsetMs), ",")
            }
        }
    }

    private fun shiftClock(m: MatchResult, offsetMs: Long): Long {
        val ms = m.groupValues[1].toLong() * 3_600_000L +
            m.groupValues[2].toLong() * 60_000L +
            m.groupValues[3].toLong() * 1000L +
            m.groupValues[4].toLong()
        return (ms + offsetMs).coerceAtLeast(0L)
    }

    private fun formatClock(ms: Long, sep: String): String = String.format(
        "%d:%02d:%02d%s%03d",
        ms / 3_600_000L, (ms % 3_600_000L) / 60_000L, (ms % 60_000L) / 1000L, sep, ms % 1000L
    )

    private fun shiftAssClock(clock: String, offsetMs: Long): String {
        val m = Regex("(\\d+):(\\d{2}):(\\d{2})\\.(\\d{2})").find(clock) ?: return clock
        val ms = m.groupValues[1].toLong() * 3_600_000L +
            m.groupValues[2].toLong() * 60_000L +
            m.groupValues[3].toLong() * 1000L +
            m.groupValues[4].toLong() * 10L
        val shifted = (ms + offsetMs).coerceAtLeast(0L)
        return String.format(
            "%d:%02d:%02d.%02d",
            shifted / 3_600_000L, (shifted % 3_600_000L) / 60_000L,
            (shifted % 60_000L) / 1000L, (shifted % 1000L) / 10L
        )
    }

    /** Re-attaches the current source's external subtitles shifted by the
     *  saved sync offset, keeping the current playback position. */
    private fun attachExternalSubtitles() {
        val p = player ?: return
        if (noSubsRetry && userSubs.isEmpty()) return
        val src = sources.getOrNull(currentIndex) ?: return
        if (src.subtitles.isEmpty()) {
            if (userSubs.isEmpty()) {
                Toast.makeText(this, I18n.t("Sync applies to downloaded subtitles"), Toast.LENGTH_SHORT).show()
                return
            }
            // This server's provider has no subtitles at all, but the user added
            // one of their own: re-time that (there is nothing to re-fetch, so
            // the "downloaded subtitles" notice would be wrong here).
            reattachSubtitles(null)
            return
        }
        val playedIndex = currentIndex
        lifecycleScope.launch {
            try {
                val configs = buildSubtitleConfigs(src)
                if (configs.isEmpty() && userSubs.isEmpty()) return@launch
                if (currentIndex != playedIndex) return@launch
                p.setMediaItem(mediaItemWithSubtitles(src, configs), false)
                p.prepare()
            } catch (t: Throwable) {
                android.util.Log.e("HikariPlayer", "subtitle sync attach failed", t)
            }
        }
    }

    // ------------------------------------------- user-added subtitle files --

    /** The system name of a picked document ("Movie.English.srt"), or "". */
    private fun displayNameOf(uri: Uri): String = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    }.getOrNull().orEmpty()

    /** An ISO code guessed from a subtitle's file name ("Film.en.srt",
     *  "Film.English.srt"), or null when the name says nothing useful. Worth
     *  guessing: it is what the sheet shows as the track's name, and it is what
     *  the remembered pick matches on when a server changes. */
    private fun langFromFileName(fileName: String): String? {
        val base = fileName.substringBeforeLast('.', fileName).substringAfterLast('.', "")
        if (base.isBlank()) return null
        val token = base.lowercase().trim()
        FULL_LANG_NAMES[token]?.let { return it }
        return token.takeIf { it.length in 2..3 && it.all { ch -> ch in 'a'..'z' } }
    }

    /** True for a track that came from a file the user added rather than from
     *  the stream or the provider. */
    private fun isUserSubLabel(label: String?): Boolean =
        label != null && label.startsWith(USER_SUB_PREFIX)

    /** [sub]'s configuration, re-timed for the current sync offset. The file is
     *  rewritten (and so gets a fresh URI) only when the offset really changed,
     *  which keeps attaching one off the hot path. */
    private fun userSubConfig(sub: UserSubtitle): MediaItem.SubtitleConfiguration? {
        val uri = if (sub.uri != null && sub.forOffset == subtitleOffsetMs) {
            sub.uri
        } else {
            writeSubtitleFile(
                shiftSubtitleText(sub.text, subtitleOffsetMs, "user:" + sub.label),
                "user:" + sub.label,
                sub.mime,
            )?.also {
                sub.uri = it
                sub.forOffset = subtitleOffsetMs
            }
        } ?: return null
        // Two identical chains rather than a stored builder: the setters' return
        // type is media3's self-typed Builder, so keeping a reference to the
        // intermediate only works as long as every setter keeps returning the
        // concrete type — chaining to build() is what the provider-subtitle path
        // above already does, and is the shape that is guaranteed to compile.
        val language = langFromFileName(sub.fileName)
        return if (language != null) {
            MediaItem.SubtitleConfiguration.Builder(uri)
                .setMimeType(sub.mime)
                .setLanguage(language)
                .setLabel(sub.label)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
        } else {
            MediaItem.SubtitleConfiguration.Builder(uri)
                .setMimeType(sub.mime)
                .setLabel(sub.label)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
        }
    }

    /** Every user-added subtitle, ready to hand ExoPlayer. */
    private fun userSubConfigs(): List<MediaItem.SubtitleConfiguration> =
        userSubs.mapNotNull { runCatching { userSubConfig(it) }.getOrNull() }

    /**
     * Takes the file the user picked and starts showing it. The bytes get the
     * same treatment a provider's subtitle gets — the same gzip/zip/UTF-16
     * container decoding and the same "does it really carry cues" test — but a
     * failure is REPORTED instead of silently dropped: the user chose one
     * specific file, so "nothing happened" is not an acceptable answer.
     */
    private suspend fun addUserSubtitle(uri: Uri) {
        val name = displayNameOf(uri)
        val checked = withContext(Dispatchers.IO) {
            val bytes = runCatching {
                contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            val decoded = bytes?.let { decodeSubtitleBytes(it) }
            if (decoded != null && isSubtitleText(decoded)) decoded else null
        }
        if (checked == null) {
            Toast.makeText(
                this, I18n.t("That file doesn't look like a subtitle file"), Toast.LENGTH_LONG
            ).show()
            return
        }
        val label = USER_SUB_PREFIX + name.ifBlank { "subtitle" }
        val mime = subtitleMimeOf(checked, name.ifBlank { uri.toString() })
        // Write the timed copy now, off the main thread, so the re-prepare below
        // is a pure in-memory step and the new track is up in the same breath.
        val written = withContext(Dispatchers.IO) {
            writeSubtitleFile(
                shiftSubtitleText(checked, subtitleOffsetMs, "user:" + label), "user:" + label, mime
            )
        }
        if (written == null) {
            Toast.makeText(this, I18n.t("Couldn't read that file"), Toast.LENGTH_LONG).show()
            return
        }
        userSubs.removeAll { it.label == label }
        userSubs.add(UserSubtitle(label, name, checked, mime).also {
            it.uri = written
            it.forOffset = subtitleOffsetMs
        })
        // The user just asked for subtitles: undo a previous "Off" and re-enable
        // the text track type. A subtitle they brought themselves is local and
        // already validated, so the "the provider's subtitle broke playback"
        // bail-out must not suppress it either.
        textOff = false
        noSubsRetry = false
        Toast.makeText(this, I18n.t("Subtitle added"), Toast.LENGTH_SHORT).show()
        reattachSubtitles(label)
    }

    /** Forgets every subtitle the user added and re-prepares without them. */
    private fun removeUserSubs() {
        if (userSubs.isEmpty()) return
        userSubs.clear()
        // The remembered pick pointed at a track that no longer exists, which
        // would make the next track change try to override a group that isn't
        // there — drop it and let the provider's own tracks win again.
        pickText = null
        Toast.makeText(this, I18n.t("Added subtitles removed"), Toast.LENGTH_SHORT).show()
        reattachSubtitles(null)
    }

    /**
     * Rebuilds the current media item with the provider's subtitles PLUS the
     * user's, and — when [selectLabel] is given — remembers that track so the
     * rebuilt track list selects it. Rebuilding the item (rather than restarting
     * the source) is what makes a subtitle added mid-film appear immediately
     * without losing the playback position: `setMediaItem(item, false)` keeps it.
     */
    private fun reattachSubtitles(selectLabel: String?) {
        val src = sources.getOrNull(currentIndex) ?: return
        val playedIndex = currentIndex
        if (selectLabel != null) {
            pickText = TrackPick(C.TRACK_TYPE_TEXT, null, selectLabel, 0)
        }
        lifecycleScope.launch {
            val configs = runCatching { buildSubtitleConfigs(src) }.getOrDefault(emptyList())
            if (currentIndex != playedIndex) return@launch
            val p = player ?: return@launch
            runCatching {
                p.setMediaItem(mediaItemWithSubtitles(src, configs), false)
                p.prepare()
            }.onFailure {
                android.util.Log.e("HikariPlayer", "user subtitle attach failed", it)
            }
        }
    }

    /** The main-media mime for [src] (HLS/DASH), or null to let media3 sniff the
     *  container itself. */
    private fun mainMimeOf(src: PlayerSource): String? = when {
        src.isM3u8 || src.url.contains(".m3u8", true) || src.url.contains("master.txt", true) ->
            MimeTypes.APPLICATION_M3U8
        src.isMpd || src.url.contains(".mpd", true) -> MimeTypes.APPLICATION_MPD
        else -> null
    }

    /** The playback item for [src] with [configs] attached as side-loaded
     *  subtitles (and the source's own mime preserved). Any subtitle the user
     *  added themselves is appended after the provider's, so a file they brought
     *  survives every re-prepare — including a failover to another server. */
    private fun mediaItemWithSubtitles(
        src: PlayerSource,
        configs: List<MediaItem.SubtitleConfiguration>,
    ): MediaItem {
        val all = if (userSubs.isEmpty()) configs else configs + userSubConfigs()
        val item = MediaItem.Builder().setUri(src.url).setSubtitleConfigurations(all)
        mainMimeOf(src)?.let { item.setMimeType(it) }
        return item.build()
    }

    /** Fetches, validates, re-times and caches this source's provider subtitles,
     *  returning the configurations to hand ExoPlayer. Runs on an IO thread. */
    private suspend fun buildSubtitleConfigs(src: PlayerSource): List<MediaItem.SubtitleConfiguration> =
        withContext(Dispatchers.IO) {
            src.subtitles.mapNotNull { s ->
                val raw = fetchSubtitleText(s, src.headers)
                if (raw == null) {
                    android.util.Log.w(
                        "HikariPlayer",
                        "subtitle dropped (unfetchable or no cues): ${s.lang} ${s.url}"
                    )
                    return@mapNotNull null
                }
                val shifted = shiftSubtitleText(raw, subtitleOffsetMs, s.url)
                val mime = subtitleMimeOf(shifted, s.url)
                val uri = writeSubtitleFile(shifted, s.url, mime) ?: return@mapNotNull null
                MediaItem.SubtitleConfiguration.Builder(uri)
                    .setMimeType(mime)
                    .setLanguage(s.lang)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
            }
        }

    /** Which subtitle mime to hand ExoPlayer, sniffed from the CONTENT first and
     *  the URL second. The URL is not trustworthy: providers serve .srt behind
     *  extension-less API paths and .vtt behind "?format=srt" query strings, and
     *  media3 picks its subtitle parser from this mime — a wrong one makes the
     *  track parse to zero cues, which is exactly "the subtitle is selected but
     *  nothing ever appears". */
    private fun subtitleMimeOf(text: String, url: String): String {
        val head = text.take(4000)
        return when {
            head.contains("WEBVTT", true) -> MimeTypes.TEXT_VTT
            head.contains("Script Info", true) || head.contains("Dialogue:", true) -> MimeTypes.TEXT_SSA
            head.contains("<tt", true) && head.contains("<p", true) -> MimeTypes.APPLICATION_TTML
            Regex("\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{1,3}\\s*-->").containsMatchIn(head) ->
                if (url.contains(".vtt", true)) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP
            else -> mimeFor(url)
        }
    }

    /** Writes a validated subtitle into the app's subtitle cache and returns its
     *  file:// URI — a local file is what DefaultDataSource can read, and unlike
     *  a huge base64 data: URI it costs no extra copy of the subtitle inside the
     *  MediaItem. Returns null when the file cannot be written. */
    private fun writeSubtitleFile(text: String, url: String, mime: String): Uri? = runCatching {
        val ext = when (mime) {
            MimeTypes.TEXT_VTT -> "vtt"
            MimeTypes.TEXT_SSA -> "ass"
            MimeTypes.APPLICATION_TTML -> "ttml"
            else -> "srt"
        }
        val dir = java.io.File(cacheDir, "subs").apply { mkdirs() }
        // The sync offset is part of the name so a re-timed subtitle gets a
        // fresh URI and can never be served from a stale read.
        val stamp = Integer.toHexString((url + "|" + subtitleOffsetMs + "|" + text.length).hashCode())
        val file = java.io.File(dir, "sub_$stamp.$ext")
        file.writeText(text, Charsets.UTF_8)
        // Yesterday's session leftovers are dead weight — clear them out as we
        // write today's.
        val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
        dir.listFiles()?.forEach { f ->
            if (f != file && f.lastModified() < cutoff) runCatching { f.delete() }
        }
        Uri.fromFile(file)
    }.getOrNull()

    /** The video's real pixel size taken from the video track's own format, for
     *  the case where media3 never reports a size: with the video-effects
     *  pipeline armed, `PlaybackVideoGraphWrapper.onVideoSizeChanged` is an
     *  empty override, so the player's size callback never fires — which left
     *  the quality badge missing AND the screen stuck in portrait with a
     *  letterboxed landscape video. The track format still carries the coded
     *  size (rotation included), so the real size can be derived from it. */
    private fun videoFormatSize(tracks: Tracks): Pair<Int, Int>? {
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO || group.length == 0) continue
            val fmt = runCatching { group.getTrackFormat(0) }.getOrNull() ?: continue
            var w = fmt.width
            var h = fmt.height
            if (w <= 0 || h <= 0) continue
            if (fmt.rotationDegrees == 90 || fmt.rotationDegrees == 270) {
                val t = w
                w = h
                h = t
            }
            return w to h
        }
        return null
    }

    /** Everything that depends on knowing the video's real size: the quality
     *  badge, the render aspect ratio, and the once-per-source auto-rotate.
     *  Called from the size callback, and — when that callback never comes —
     *  from the tracks callback. */
    private fun onKnownVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        // Quality badge: the rendered video's height (updates per server,
        // since a different source can be a different resolution).
        val q = qualityBadgeFor(height)
        if (q.isNotBlank()) {
            badgeQuality?.text = q
            badgeQuality?.visibility = View.VISIBLE
        }
        // Keep the surface at the video's shape. PlayerView exposes no setter
        // for this (its field and update method are private), so it is reached
        // through the AspectRatioFrameLayout it inflates as `exo_content_frame`
        // — id resolved by name, like the other media3 controls above.
        runCatching {
            (exoView("exo_content_frame") as? AspectRatioFrameLayout)
                ?.setAspectRatio(width.toFloat() / height.toFloat())
        }
        if (autoRotated || userRotated) return
        autoRotated = true
        val landscape = width > height
        requestedOrientation = if (landscape) {
            SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private val listener = object : Player.Listener {
        // Auto-rotate to match the video: landscape videos play landscape,
        // portrait videos play portrait — once, per source. After that the
        // rotate button is entirely in the user's hands.
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            onKnownVideoSize(videoSize.width, videoSize.height)
        }

        override fun onTracksChanged(tracks: Tracks) {
            // A source rendered through the video-effects pipeline never gets an
            // onVideoSizeChanged, so take the size from the video track itself.
            videoFormatSize(tracks)?.let { (w, h) -> onKnownVideoSize(w, h) }
            applyVideoEnhance()
            applyStickyPicks(C.TRACK_TYPE_AUDIO)
            if (noSubsRetry) return
            val textApplied = applyStickyPicks(C.TRACK_TYPE_TEXT)
            selectFirstTextTrack(player ?: return, tracks, textApplied)
        }

        override fun onRenderedFirstFrame() {
            renderedFirstFrame = true
            // Real video is on screen — retract any "your connection looks slow"
            // verdict, measured or not.
            SlowNetTip.onFirstFrame()
            firstFrameTask?.let { bufferingWatchdog.removeCallbacks(it) }
            firstFrameTask = null
            hideLoadingBanner()
            // Playback actually started — persist this server + the header
            // variant that got us here, so the next replay of this video jumps
            // straight onto it (no re-probe, no header trial-and-error).
            sources.getOrNull(currentIndex)?.let { rememberPlayedSource(currentIndex, it) }
            com.hikari.app.data.Logs.log(
                "Player",
                "playing ${sources.getOrNull(currentIndex)?.name ?: "?"} " +
                    "(server ${currentIndex + 1}/${sources.size})",
            )
            maybeOfferResume()
        }

        // The "Tap to play" hint under the centre play button is visible only
        // while playback is paused (or before it has started).
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playHint?.visibility = if (isPlaying) View.GONE else View.VISIBLE
        }

        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                // Duration badge (total runtime) — known once media is ready.
                val durBadge = formatDurationBadge(this@PlayerActivity.player?.duration ?: 0L)
                if (durBadge.isNotBlank()) {
                    badgeDuration?.text = durBadge
                    badgeDuration?.visibility = View.VISIBLE
                }
                dismissSlowDialog()
                // The failure prompt has either been answered or is moot now
                // that something is actually playing.
                dismissFailDialog()
                // A download launch waits for exactly this: a server that really
                // started. The chooser is offered here (and only here), so a
                // download is never started from a link that was only alive
                // during prepare.
                if (openDownloadPending) {
                    openDownloadPending = false
                    showDownloadDialog()
                }
                // Audio-only streams never fire onRenderedFirstFrame, so the same
                // "playback really did start" signal applies here.
                SlowNetTip.onFirstFrame()
                // Fallback: audio-only streams never fire onRenderedFirstFrame,
                // so drop the title card shortly after playback is ready.
                bufferingWatchdog.postDelayed({ hideLoadingBanner() }, 1200L)
                watchdogTask?.let { bufferingWatchdog.removeCallbacks(it) }
                watchdogTask = null
                // Resume from history: seek once the first frame is ready.
                if (seekPending && startPositionMs > 0L) {
                    seekPending = false
                    val p = player ?: return
                    val dur = p.duration
                    val target = if (dur > 0L) {
                        startPositionMs.coerceAtMost(dur - 1000L).coerceAtLeast(0L)
                    } else startPositionMs
                    if (target > 0L) p.seekTo(target)
                }
                // Also offer the in-video resume prompt here (audio-only streams
                // never fire onRenderedFirstFrame).
                maybeOfferResume()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            hideLoadingBanner(immediate = true)
            firstFrameTask?.let { bufferingWatchdog.removeCallbacks(it) }
            firstFrameTask = null
            val details = buildString {
                append(error.javaClass.simpleName)
                append(" [").append(PlaybackException.getErrorCodeName(error.errorCode)).append("]")
                var cause = error.cause
                var depth = 0
                while (cause != null && depth < 4) {
                    val m = cause.message
                    if (!m.isNullOrBlank()) append("\n").append(m)
                    cause = cause.cause
                    depth++
                }
                if (currentIndex in sources.indices) {
                    append("\nURL: ").append(sources[currentIndex].url)
                }
            }
            // Every playback error is recorded with the server it came from, so
            // a "the play button spins and then it fails / goes black" report is
            // a readable line in the shared log instead of a guess.
            com.hikari.app.data.Logs.log(
                "Player",
                "playback error on ${sources.getOrNull(currentIndex)?.name ?: "?"} " +
                    "(server ${currentIndex + 1}/${sources.size}): " +
                    details.replace("\n", " | ").take(600),
            )
            // HLS manifests often declare a subtitle track whose URL returns
            // junk ("Expected WEBVTT. Got 1" / contentIsMalformed). media3
            // treats that as a fatal parse error — retry the SAME server with
            // text tracks disabled before giving up on it.
            val code = error.errorCode
            val httpStatus = httpStatusOf(details)
            // A 5xx — or a refused/timed-out connection — is the HOST saying
            // "not this file, not now". The same mirror hands out every quality
            // of the same video, so it answers the same way for all of them,
            // and no header set can fix it. Remember the host so the failover
            // below skips its siblings instead of grinding through them one
            // 13-second error at a time.
            val terminalHostFailure = (httpStatus != null && httpStatus >= 500) ||
                httpStatus == 404 || httpStatus == 410 ||
                code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                code == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
            if (terminalHostFailure) {
                val h = mirrorHostOf(sources.getOrNull(currentIndex)?.url.orEmpty())
                if (h.isNotBlank()) deadHosts.add(h)
            }
            // The video-effects pipeline itself failed — usually a device whose
            // GL stack cannot run media3's frame processor, occasionally an
            // HDR stream we mis-classified. That is NOT the server's fault, so
            // walking to the next server would just fail the same way (and burn
            // the whole server list). Turn the pipeline off, remember it, and
            // re-open the SAME source clean.
            val effectsIssue =
                code == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED ||
                    code == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED
            if (effectsIssue && videoSinkArmed) {
                videoSinkArmed = false
                enhanceUnsupported = true
                appliedEnhanceKey = null
                appliedEnhanceHdr = null
                enhancePresetKey = EnhancePreset.NATURAL.key
                com.hikari.app.data.Logs.logError(
                    "Player",
                    "video effects failed on this device — enhance disabled",
                    error
                )
                lifecycleScope.launch {
                    runCatching {
                        val store = (applicationContext as HikariApp).store
                        store.setEnhanceUnsupported(true)
                        store.setEnhancePreset(EnhancePreset.NATURAL.key)
                    }
                }
                Toast.makeText(
                    this@PlayerActivity,
                    I18n.t("This device can't apply video effects — turning them off."),
                    Toast.LENGTH_LONG
                ).show()
                noSubsRetry = false
                playSource(currentIndex)
                return
            }
            val subtitleIssue = !noSubsRetry &&
                (code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                    code == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED) &&
                (details.contains("WEBVTT", true) || details.contains("Expected", true) ||
                    details.contains("subtitle", true) || details.contains("TextDecoder", true))
            if (subtitleIssue) {
                // Silent retry: the user asked not to be told about every
                // internal retry — only real server failures (below) speak up.
                noSubsRetry = true
                playSource(currentIndex)
                return
            }
            // Some CDNs 403 the request as long as it carries a Referer / other
            // extractor headers, even though the bare URL plays fine in a
            // browser. And some addons hand us a header with non-ASCII chars
            // (a Cyrillic look-alike User-Agent), which OkHttp rejects with
            // IllegalArgumentException. Both are header problems, not server
            // problems — walk the header set down (full → no Referer → none)
            // before declaring the server dead.
            val headerIssue = details.contains("Unexpected char", true) ||
                (details.contains("IllegalArgumentException", true) &&
                    (details.contains("User-Agent", true) || details.contains("Header", true)))
            if ((code == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS || headerIssue) &&
                headerVariant < 2 && !terminalHostFailure
            ) {
                headerVariant++
                // Silent retry — same server, next header set down. The only
                // message the user sees is "Server failed — trying next" once
                // this server is finally abandoned.
                noSubsRetry = false
                playSource(currentIndex)
                return
            }
            // A dud link extracted mid-search: the SAME server is very often
            // fine a moment later, once the provider search has finished and
            // re-handed out its links — "back out and press Play again" is how
            // users have been working around it. Do the equivalent here: ask
            // for fresh links and, if one for THIS server arrives, play it
            // (keeping the position). Once per session, and only while the
            // search is still running AND nothing else is waiting to be tried:
            // a dead link on server 1 of 8 must not hold playback on that one
            // server for the whole re-extraction while seven already-found
            // servers sit untouched — that is the "Found 8 servers but the
            // video never played" report (server 1 answered HTTP 500, and the
            // player stayed on it, cover still counting servers, instead of
            // moving on). With other untried servers in hand the normal
            // failover below runs immediately; the re-extraction still happens
            // once the list is exhausted.
            val hasOtherUntried = sources.indices.any { i ->
                i != currentIndex && sources[i].url.isNotBlank() &&
                    sources[i].url !in triedUrls
            }
            if (startedWhileSearching && !liveSearchDone && !sameServerRelinkUsed &&
                !hasOtherUntried && !terminalHostFailure &&
                refreshAttempts < MAX_REFRESH_ATTEMPTS && isIoFailure(code, headerIssue) &&
                currentIndex < sources.size
            ) {
                sameServerRelinkUsed = true
                refreshAttempts++
                noSubsRetry = false
                errorPanel?.visibility = View.GONE
                val wantName = sources[currentIndex].name
                val wantUrl = sources[currentIndex].url
                // Say what is actually happening: the cover used to keep the
                // search's own line ("Finding the best server… (23s)") while
                // this server was being re-resolved, so a dead link looked like
                // a search that was still going nowhere.
                loadingStatusBase = "Reconnecting — $wantName"
                loadingStatus?.text = loadingStatusBase
                loadingSpinnerStatus?.text = loadingStatusBase
                if (loadingBanner?.visibility != View.VISIBLE &&
                    loadingSpinner?.visibility != View.VISIBLE
                ) showLoadingCover()
                val keepPosition = player?.currentPosition?.takeIf { it > 2_000L } ?: 0L
                Toast.makeText(
                    this@PlayerActivity,
                    I18n.t("Reconnecting — %s").replace("%s", wantName),
                    Toast.LENGTH_SHORT
                ).show()
                liveSessionId?.let { StreamsLive.requestRefresh(it) }
                lifecycleScope.launch {
                    val deadline = System.currentTimeMillis() + RELINK_WAIT_MS
                    while (System.currentTimeMillis() < deadline) {
                        delay(350)
                        val fresh = sources.indexOfFirst { s ->
                            s.url.isNotBlank() && s.url != wantUrl && s.url !in triedUrls &&
                                s.name.equals(wantName, ignoreCase = true)
                        }
                        if (fresh >= 0) {
                            if (keepPosition > 0L) {
                                startPositionMs = keepPosition
                                seekPending = true
                            }
                            playSource(fresh)
                            return@launch
                        }
                        if (liveSearchDone) break
                    }
                    // Nothing fresher arrived — carry on exactly as before.
                    failoverFromCurrent(details, code, headerIssue)
                }
                return
            }
            failoverFromCurrent(details, code, headerIssue)
        }
    }

    /**
     * The next server to walk to after the current one failed: the first server
     * not yet tried and not on a host that has already failed terminally this
     * session — else the first untried server — else the next row in the list.
     * Servers on a dead host are tried LAST, never first: one broken mirror
     * (MovieBlast's `mbfiles.mbaccess.site`) usually serves several qualities of
     * the same file, and walking them in order burned the failover on a host we
     * had already proven useless while a working server sat further down the
     * list. -1 when there is nothing left to try.
     */
    private fun nextUntriedIndex(): Int {
        var fallback = -1
        for (i in sources.indices) {
            if (i == currentIndex) continue
            val s = sources[i]
            if (!s.isTorrent && s.url.isBlank()) continue
            if (s.url.isNotEmpty() && s.url in triedUrls) continue
            if (fallback < 0) fallback = i
            val h = mirrorHostOf(s.url)
            if (h.isBlank() || h !in deadHosts) return i
        }
        if (fallback >= 0) return fallback
        return if (currentIndex + 1 < sources.size) currentIndex + 1 else -1
    }

    /**
     * Every attempt on the current server is spent: advance to the next one, or
     * — when that was the last — ask the detail screen for a fresh extraction
     * before reporting failure.
     */
    private fun failoverFromCurrent(details: String, code: Int, headerIssue: Boolean) {
        // Like CloudStream: never strand the user — keep trying the next
        // server automatically on every failure. The ONE exception is a server
        // the user picked themselves in the chooser: silently sliding onto a
        // different link there hides a whole-server-list problem, so the user
        // gets the choice (try the next one now, pick another from the list so
        // far, or stop asking). See [promptServerFailed].
        val nextIndex = nextUntriedIndex()
        if (nextIndex >= 0) {
            if (pickedByUser) {
                lifecycleScope.launch {
                    // The pref read is a DataStore hit on first use only.
                    if (shouldAskOnFailure()) promptServerFailed(nextIndex, details)
                    else advanceToServer(nextIndex)
                }
            } else {
                advanceToServer(nextIndex)
            }
            return
        }
        // No server left. If this looks like the servers simply died — expired
        // signed links (HTTP 403) or a DNS/connect failure at the CDN — rather
        // than a genuinely unplayable file, ask the detail screen for a fresh
        // extraction before giving up: replaying a signed 4KHDHub/hubcloud URL
        // after a few minutes can only 403, but a re-run hands out live links.
        // Expired signed links are the classic reason a whole list dies
        // (ioLike), but a re-extraction is also the ONLY way another repo's
        // servers can be brought in — and with the cross-extension pass those
        // are exactly the ones that may actually play a title this repo can't.
        // So whenever the detail screen is still attached, ask it for fresh
        // sources before declaring failure.
        val ioLike = isIoFailure(code, headerIssue)
        val canRefresh = ioLike || liveSessionId != null
        if (!(canRefresh && refreshSources(currentIndex, details))) {
            showError(details, false)
        }
    }

    /** Errors a fresh extraction can plausibly fix — a stale/expired link, a
     *  host that refused us, bytes that were never a video — as opposed to a
     *  file that is simply unplayable. */
    private fun isIoFailure(code: Int, headerIssue: Boolean): Boolean =
        headerIssue ||
            code == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
            code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            code == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
            code == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
            code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
            code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED

    /** Drop non-ASCII characters from a header value. OkHttp throws
     *  IllegalArgumentException on any header value containing chars > 127,
     *  and some addon extractors ship headers (User-Agent most often) that
     *  contain Cyrillic look-alikes — a player crash that has nothing to do
     *  with the actual stream. */
    private fun sanitizeHeaderValue(v: String): String = v.filter { it.code < 128 }

    /** Sanitize every header; blank results are dropped entirely. */
    private fun sanitizeHeaders(h: Map<String, String>): Map<String, String> =
        h.mapNotNull { (k, v) ->
            val c = sanitizeHeaderValue(v)
            if (c.isBlank()) null else k to c
        }.toMap()

    /**
     * Shows the full-screen title-card cover: the title's backdrop with its
     * name slowly breathing (zoom in/out), plus a "finding the best server"
     * line. It is the first thing on screen when the player opens and stays up
     * until the first frame of video is drawn, so tapping Play never reads as
     * "nothing happened". Purely decorative — playback state is untouched.
     */
    /** The cover shown while a server is being found/prepared: the full-screen
     *  title card, or — when the user turned it off in Settings — just a round
     *  spinner on black. */
    private fun showLoadingCover() {
        // Restore whatever the search last reported (or the default line) so a
        // cover re-shown mid-session (failover, second attempt) doesn't look
        // like the app went back to square one.
        val line = loadingStatusBase ?: DEFAULT_LOADING_STATUS
        loadingStatus?.text = line
        loadingSpinnerStatus?.text = line
        startLoadingTicker()
        if (bannerMode) showLoadingBanner() else showLoadingSpinner()
    }

    /** Re-renders the cover's status line once a second with the number of
     *  seconds the search has been running. Without it a slow provider leaves
     *  "Finding the best server…" frozen on screen, which is indistinguishable
     *  from a hung app. */
    private fun startLoadingTicker() {
        stopLoadingTicker()
        val startedAt = android.os.SystemClock.elapsedRealtime()
        loadingTickerJob = lifecycleScope.launch {
            while (true) {
                delay(1000L)
                val secs = (android.os.SystemClock.elapsedRealtime() - startedAt) / 1000
                val line = (loadingStatusBase ?: DEFAULT_LOADING_STATUS) + "  ($secs" + "s)"
                loadingStatus?.text = line
                loadingSpinnerStatus?.text = line
            }
        }
    }

    private fun stopLoadingTicker() {
        loadingTickerJob?.cancel()
        loadingTickerJob = null
    }

    /** Spinner-only cover (Settings: "Show banner until servers load" = off). */
    private fun showLoadingSpinner() {
        val spin = loadingSpinner ?: return
        spin.animate().cancel()
        spin.alpha = 1f
        spin.visibility = View.VISIBLE
    }

    private fun showLoadingBanner() {
        val banner = loadingBanner ?: return
        val box = loadingTitleBox ?: return
        val style = com.hikari.app.ui.LoadingStyles.normalize(loadingStyle)
        loadingTitle?.text = intent.getStringExtra("title").orEmpty().ifBlank { "Loading" }.uppercase()

        // Episode line, mirroring the player's own two-line title block.
        val epText = findViewById<TextView>(R.id.subtitle_text)?.text?.toString().orEmpty()
        loadingEpisode?.apply {
            text = epText
            visibility = if (epText.isBlank()) View.GONE else View.VISIBLE
        }
        val epName = intent.getStringExtra("histEpisodeName").orEmpty()
        loadingDetail?.apply {
            val show = epName.isNotBlank() && !epText.contains(epName)
            text = epName
            visibility = if (show) View.VISIBLE else View.GONE
        }

        // Backdrop (or the poster as a fallback) — already tokenized by the
        // detail screen, so this never carries a multi-MB base64 string.
        val bannerModel = PosterLoader.model(
            intent.getStringExtra("bannerBackdrop")?.takeIf { it.isNotBlank() }
                ?: intent.getStringExtra("histPoster")
        )
        // The title's own POSTER, for the styles that show it as art in its own
        // right (POSTER), falling back to the backdrop so a title whose addon
        // gave no poster still shows something.
        val posterModel = PosterLoader.model(
            intent.getStringExtra("histPoster")?.takeIf { it.isNotBlank() }
                ?: intent.getStringExtra("bannerBackdrop")
        )

        // Each loading style dresses the very same XML cover differently. The
        // choice is made once, in Settings, and the detail screen's full-screen
        // card uses the identical rules — so the hand-off from the detail page
        // into the player never changes the design while the user is watching
        // it (which would read as a flash/glitch).
        var backdropAlpha = 1f
        var backdropVisible = true
        var breath = true
        // Set below once we know whether any artwork actually reaches the
        // screen; drives how strong the scrim is drawn (see below).
        var artShown = false
        when (style) {
            com.hikari.app.ui.LoadingStyles.MINIMAL -> {
                // Flat and quiet: no artwork at all, a small title, the spinner.
                backdropVisible = false
                breath = false
                loadingGlow?.visibility = View.GONE
                loadingCardPoster?.apply {
                    setImageDrawable(null)
                    visibility = View.GONE
                }
                loadingTitle?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            }
            com.hikari.app.ui.LoadingStyles.SPOTLIGHT -> {
                // No artwork either — an accent bloom behind the title instead.
                backdropVisible = false
                loadingGlow?.visibility = View.VISIBLE
                loadingCardPoster?.apply {
                    setImageDrawable(null)
                    visibility = View.GONE
                }
                loadingTitle?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            }
            com.hikari.app.ui.LoadingStyles.POSTER -> {
                // The poster itself, on a glass card, over a dimmed blown-up
                // copy of the same art so the card floats on its own poster.
                loadingGlow?.visibility = View.GONE
                backdropAlpha = 0.22f
                if (posterModel != null) {
                    loadingCardPoster?.apply {
                        visibility = View.VISIBLE
                        load(posterModel)
                    }
                } else {
                    loadingCardPoster?.visibility = View.GONE
                }
                loadingTitle?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            }
            else -> {
                // CINEMATIC: the way it has always looked.
                loadingGlow?.visibility = View.GONE
                loadingCardPoster?.apply {
                    setImageDrawable(null)
                    visibility = View.GONE
                }
                loadingTitle?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
            }
        }

        // ---- The loading EFFECT (Settings → App Layout → Loading screen →
        // Effect) ----
        //
        // Drawn over whichever style was chosen, so all four styles offer the
        // same treatments (see [com.hikari.app.ui.LoadingEffects]). Applied here
        // rather than inside the style branches above: the effect is independent
        // of the style by design ("Minimal + gallery frame" is one setting each).
        val effect = com.hikari.app.ui.LoadingEffects.normalize(loadingEffect)
        loadingEffectRing?.visibility =
            if (effect == com.hikari.app.ui.LoadingEffects.AURA) View.VISIBLE else View.GONE
        loadingEffectSheen?.visibility =
            if (effect == com.hikari.app.ui.LoadingEffects.SHEEN) View.VISIBLE else View.GONE
        loadingEffectFrame?.visibility =
            if (effect == com.hikari.app.ui.LoadingEffects.FRAME) View.VISIBLE else View.GONE
        // The accent bloom behind the title is drawn for two reasons — the
        // SPOTLIGHT style (above) and the "Accent glow" effect — so an effect
        // can only ever switch it ON, never off.
        if (com.hikari.app.ui.LoadingEffects.usesGlow(effect)) {
            loadingGlow?.visibility = View.VISIBLE
        }

        loadingBackdrop?.let { iv ->
            if (backdropVisible && bannerModel != null) {
                artShown = true
                iv.alpha = backdropAlpha
                iv.visibility = View.VISIBLE
                iv.load(bannerModel)
            } else if (backdropVisible && posterModel != null) {
                // No wide art for this title: its poster is the only picture
                // there is, so it fills the frame instead of nothing.
                artShown = true
                iv.alpha = backdropAlpha
                iv.visibility = View.VISIBLE
                iv.load(posterModel)
            } else {
                iv.setImageDrawable(null)
                iv.visibility = View.GONE
            }
        }

        // The scrim's job is to keep the white title legible over artwork. With
        // NO artwork under it, at full strength it would darken the fallback
        // wash straight back to flat black — exactly the screen the user
        // reported ("in some see the loading screen showing black"). Thinned
        // rather than removed, so the top/bottom of the cover still have a
        // little weight behind the title and the status line.
        loadingScrim?.alpha = if (artShown) 1f else 0.35f

        stopBannerAnimators()
        // The name breathes in and out, exactly like Nuvio/Stremio's title card.
        // MINIMAL (the quietest style) deliberately does not move at all.
        val titleScale = if (breath) ObjectAnimator.ofPropertyValuesHolder(
            box,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.94f, 1.06f, 0.94f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.94f, 1.06f, 0.94f)
        ).apply {
            duration = 2600L
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            repeatCount = android.animation.ValueAnimator.INFINITE
        } else {
            box.scaleX = 1f
            box.scaleY = 1f
            null
        }
        // Slow Ken-Burns drift on the artwork (zooming in only, so a
        // centre-cropped image never reveals its edges). Only when a backdrop
        // is actually being drawn.
        val backdropScale = if (backdropVisible) loadingBackdrop?.let { iv ->
            ObjectAnimator.ofPropertyValuesHolder(
                iv,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.12f, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.12f, 1f)
            ).apply {
                duration = 12_000L
                interpolator = android.view.animation.LinearInterpolator()
                repeatCount = android.animation.ValueAnimator.INFINITE
            }
        } else {
            loadingBackdrop?.scaleX = 1f
            loadingBackdrop?.scaleY = 1f
            null
        }
        // SPOTLIGHT's bloom swells with the title's own breathing, so the light
        // and the name read as one object.
        val glowPulse = loadingGlow?.takeIf { it.visibility == View.VISIBLE }?.let { g ->
            ObjectAnimator.ofPropertyValuesHolder(
                g,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 0.92f, 1.06f, 0.92f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.92f, 1.06f, 0.92f)
            ).apply {
                duration = 3600L
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                repeatCount = android.animation.ValueAnimator.INFINITE
            }
        }
        // SHEEN: the band of light crosses the cover. Driven by a ValueAnimator
        // rather than a property animator because the distance depends on the
        // view's measured width, which is not known until the first layout —
        // reading it on every frame means the sweep is right on every screen
        // size (and needs no dimension maths here).
        val sheenSweep = loadingEffectSheen?.takeIf { it.visibility == View.VISIBLE }?.let { v ->
            android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 2600L
                interpolator = android.view.animation.LinearInterpolator()
                repeatCount = android.animation.ValueAnimator.INFINITE
                addUpdateListener { a ->
                    val w = v.width.coerceAtLeast(1).toFloat()
                    v.translationX = -w + ((a.animatedValue as Float) * w * 2f)
                }
            }
        }
        // AURA: the ring breathes — brighter and a little larger, then back.
        val auraRing = loadingEffectRing?.takeIf { it.visibility == View.VISIBLE }?.let { v ->
            ObjectAnimator.ofPropertyValuesHolder(
                v,
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.35f, 1f, 0.35f),
                PropertyValuesHolder.ofFloat(View.SCALE_X, 0.92f, 1.06f, 0.92f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.92f, 1.06f, 0.92f),
            ).apply {
                duration = 3400L
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                repeatCount = android.animation.ValueAnimator.INFINITE
            }
        }
        // (The gallery frame is deliberately still — it is the quietest of the
        // effects, and a moving frame around a loading screen is a distraction.)
        bannerAnimators = listOfNotNull(titleScale, backdropScale, glowPulse, sheenSweep, auraRing)
        bannerAnimators.forEach { runCatching { it.start() } }
        banner.animate().cancel()
        banner.alpha = 1f
        banner.visibility = View.VISIBLE
    }

    /** Fades the title card away (or removes it instantly) once real video is
     *  on screen. Safe to call repeatedly and from any state. */
    private fun hideLoadingBanner(immediate: Boolean = false) {
        stopLoadingTicker()
        hideLoadingSpinner(immediate)
        val banner = loadingBanner ?: return
        if (banner.visibility != View.VISIBLE) return
        stopBannerAnimators()
        banner.animate().cancel()
        if (immediate || isFinishing || isDestroyed) {
            banner.alpha = 0f
            banner.visibility = View.GONE
        } else {
            banner.animate().alpha(0f).setDuration(320L).withEndAction {
                banner.visibility = View.GONE
            }.start()
        }
    }

    private fun stopBannerAnimators() {
        bannerAnimators.forEach { runCatching { it.cancel() } }
        bannerAnimators = emptyList()
    }

    /** Fades the spinner-only cover away once real video is on screen (see
     *  [hideLoadingBanner], which always calls this). */
    private fun hideLoadingSpinner(immediate: Boolean = false) {
        val spin = loadingSpinner ?: return
        if (spin.visibility != View.VISIBLE) return
        stopLoadingTicker()
        spin.animate().cancel()
        if (immediate || isFinishing || isDestroyed) {
            spin.alpha = 0f
            spin.visibility = View.GONE
        } else {
            spin.animate().alpha(0f).setDuration(320L).withEndAction {
                spin.visibility = View.GONE
            }.start()
        }
    }

    /** "Your connection looks slow?" — offered over the loading cover while the
     *  source search runs, with a one-tap way to switch Settings' Slow
     *  connection mode on (which is exactly what rescues a search that keeps
     *  timing out on a weak link). Never shown once real video is on screen.
     *  [SlowNetTip] decides, on measured evidence, whether this is worth
     *  saying at all. */
    private fun showSlowNetTip() {
        if (isFinishing || isDestroyed || renderedFirstFrame) return
        if (slowNetDialog?.isShowing == true) return
        val dialog = showGlassMenu(
            I18n.t("Your connection looks slow"),
            listOf(
                GlassOption(
                    I18n.t("Turn on"),
                    I18n.t("Keep waiting for slow sources"),
                    iconRes = R.drawable.ic_speed,
                    chevron = true,
                    marker = RowMarker.ICON,
                ),
                GlassOption(
                    I18n.t("Not now"),
                    I18n.t("Ask me again later"),
                    iconRes = R.drawable.ic_skip,
                    chevron = true,
                    marker = RowMarker.ICON,
                ),
                GlassOption(
                    I18n.t("Don't ask again"),
                    I18n.t("Only the Settings switch turns it back on"),
                    iconRes = R.drawable.ic_close,
                    chevron = true,
                    marker = RowMarker.ICON,
                ),
            ),
            message = I18n.t("Sources and video are taking a long time to answer. Slow connection mode lets Hikari keep waiting for them instead of giving up."),
            hint = I18n.t("You can change this any time in Settings."),
            iconRes = R.drawable.ic_settings,
            onDialog = { it.setOnCancelListener { dismissSlowNetTip(remember = true) } },
        ) { which ->
            when (which) {
                0 -> enableSlowModeFromTip()
                1 -> dismissSlowNetTip(remember = true)
                else -> dismissSlowNetTip(remember = true, always = true)
            }
        }
        slowNetDialog = dialog
    }

    /** One tap: the setting is flipped for real (persisted AND live for the
     *  requests already in flight). If this play hasn't managed to get anything
     *  playing yet, the search is re-run with the longer budgets — that is the
     *  actual rescue, not just a nicer next attempt. */
    private fun enableSlowModeFromTip() {
        dismissSlowNetTip()
        val app = applicationContext as HikariApp
        NetTuning.setSlowConnection(true)
        app.appScope.launch {
            runCatching { app.store.setSlowConnection(true) }
        }
        Toast.makeText(this, I18n.t("Slow connection mode on"), Toast.LENGTH_SHORT).show()
        if (!renderedFirstFrame && sources.isEmpty()) refreshSources(-1)
    }

    /** Hides the tip. [remember] starts the "Not now" cooldown; [always] is the
     *  "Don't ask again" choice, which silences it for good (the Settings
     *  switch does the same and is the way back). */
    private fun dismissSlowNetTip(remember: Boolean = false, always: Boolean = false) {
        val dialog = slowNetDialog
        slowNetDialog = null
        runCatching { dialog?.dismiss() }
        SlowNetTip.clear()
        if (!remember && !always) return
        val app = applicationContext as HikariApp
        app.appScope.launch {
            runCatching { app.store.setSlowTipLastDismiss(System.currentTimeMillis()) }
            if (always) runCatching { app.store.setSlowTipDontAsk(true) }
        }
    }

    /** Adopts an episode that arrived AFTER launch — the user tapped Play while
     *  the origin addon was still listing episodes, so the player opened with
     *  no episode. Updates the top-bar episode line, the loading card's episode
     *  text, and the watch-history key so progress is stored against this
     *  episode instead of the movie-level entry. */
    private fun applyLiveEpisode(ep: Episode) {
        val label = when {
            ep.season > 1 && ep.number > 0 ->
                "S${ep.season} E${ep.number}" + episodeNameSuffix(ep.name)
            ep.number > 0 ->
                "Episode ${ep.number}" + episodeNameSuffix(ep.name)
            else -> ep.name.orEmpty()
        }
        findViewById<TextView>(R.id.subtitle_text)?.apply {
            text = label
            visibility = if (label.isBlank()) View.GONE else View.VISIBLE
        }
        loadingEpisode?.apply {
            text = label
            visibility = if (label.isBlank()) View.GONE else View.VISIBLE
        }
        if (historyEntry != null) {
            historyEntry = historyEntry?.copy(episodeId = ep.id, episodeName = ep.name.orEmpty())
            historyEntry?.let { historyKey = it.uniqueKey }
        }
    }

    /** Every server the player was handed has died — typically because the
     *  provider's signed links expired, or the mirror serving them went away.
     *  Ask the still-attached detail screen to re-run the providers, wait for
     *  fresh servers to arrive on the live session, then continue on one we
     *  haven't tried yet. Returns true when a re-fetch was kicked off (the
     *  caller must then do nothing else), false when refreshing isn't possible
     *  or has already been exhausted. */
    private fun refreshSources(failedIndex: Int, originalError: String? = null): Boolean {
        val session = liveSessionId ?: return false
        if (refreshAttempts >= MAX_REFRESH_ATTEMPTS) return false
        refreshAttempts++
        resetHeaderWalk()
        noSubsRetry = false
        val failedName = sources.getOrNull(failedIndex)?.name.orEmpty()
        // Hide the error panel and put the title card back up: from the user's
        // point of view this is another "finding your server" moment, not a
        // failure — and the providers may take a few seconds to answer.
        errorPanel?.visibility = View.GONE
        if (loadingBanner?.visibility != View.VISIBLE &&
            loadingSpinner?.visibility != View.VISIBLE
        ) showLoadingCover()
        Toast.makeText(this, I18n.t("Looking for other servers…"), Toast.LENGTH_SHORT).show()
        StreamsLive.requestRefresh(session)
        lifecycleScope.launch {
            val deadline = System.currentTimeMillis() + REFRESH_WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                delay(300)
                val idx = freshIndex(failedName)
                if (idx >= 0) {
                    playSource(idx)
                    return@launch
                }
            }
            // Nothing new arrived — but the detail screen's own search may still
            // be running: the cross pass and its background sweep keep finding
            // servers for MINUTES after the first one lands (see
            // ContentRepository's sweep), and every one of them is streamed into
            // this player. Declaring failure here is what stopped the user on a
            // server that was merely early ("it says the server is not
            // responding and gives up while other servers were still being
            // searched"). So when the search is not finished, wait for it with
            // [awaitReplacementForStalledServer] — which starts the first
            // replacement automatically and only reports failure once the
            // search has genuinely ended.
            if (liveSessionId != null && !liveSearchDone) {
                awaitReplacementForStalledServer(sources.getOrNull(currentIndex))
            } else {
                showError(
                    originalError
                        ?: I18n.t("Servers expired and no fresh sources were found.\nTry again in a moment."),
                    false
                )
            }
        }
        return true
    }

    /** Index of a not-yet-tried source on the CURRENT list, preferring one with
     *  the same server name as [preferredName] (the same provider/mirror is the
     *  likeliest to still work), else the first untried one. -1 when every
     *  server has already been tried. */
    private fun freshIndex(preferredName: String): Int {
        var fallback = -1
        for (i in sources.indices) {
            val s = sources[i]
            if (!s.isTorrent && s.url.isBlank()) continue
            if (s.url.isNotEmpty() && s.url in triedUrls) continue
            if (preferredName.isNotBlank() && s.name.equals(preferredName, ignoreCase = true)) return i
            if (fallback < 0) fallback = i
        }
        return fallback
    }

    // ------------------------------------------------------------ Downloads --

    /** Offers the two download destinations: an in-app copy kept for offline
     *  viewing, or a copy dropped into the phone's Downloads folder. */
    private fun showDownloadDialog() {
        // A queued tap can arrive after the player has already been closed (the
        // download flow finishes it as soon as its task is queued): showing a
        // sheet then would be a window on a dead Activity.
        if (isFinishing || isDestroyed) return
        val src = sources.getOrNull(currentIndex)
        if (src == null || src.url.isBlank() || src.isTorrent || src.torrentStream) {
            Toast.makeText(this, I18n.t("This server can't be downloaded."), Toast.LENGTH_SHORT).show()
            leaveAfterDownloadPick()
            return
        }
        val label = episodeLabel()
        // Backing out of this sheet in download-pick mode means the whole
        // download was abandoned: close the player rather than leaving the user
        // on a screen playing nothing. A row tap sets [picked] first, so the
        // dismiss it causes doesn't close the player out from under the flow.
        var picked = false
        showGlassMenu(
            I18n.t("Download"),
            listOf(
                GlassOption(
                    I18n.t("In Hikari"), I18n.t("Kept offline inside the app"),
                    iconRes = R.drawable.ic_download, marker = RowMarker.ICON, chevron = true,
                ),
                GlassOption(
                    I18n.t("Phone storage"), I18n.t("Saved to your device's Downloads folder"),
                    iconRes = R.drawable.ic_download, marker = RowMarker.ICON, chevron = true,
                ),
            ),
            message = (if (label.isBlank()) "" else "$label\n") +
                I18n.t("Where do you want to save this video?"),
            hint = I18n.t("The in-app copy plays without internet."),
            iconRes = R.drawable.ic_download,
            onDialog = { dlg ->
                dlg.setOnDismissListener { if (!picked && downloadPickMode) leaveAfterDownloadPick() }
            },
        ) { which ->
            picked = true
            chooseQualityThenDownload(if (which == 0) DownloadKind.OFFLINE else DownloadKind.EXPORT)
        }
    }

    /** A video quality the current stream offers: its height (0 when the
     *  playlist doesn't declare one) and its bandwidth (HLS BANDWIDTH). */
    private data class VideoQuality(val height: Int, val bandwidth: Long)

    /** The video qualities the current stream exposes, highest first. Comes
     *  from the tracks the player has already parsed, so it works for HLS
     *  variants and for a single-file source alike. */
    private fun availableVideoQualities(): List<VideoQuality> {
        val p = player ?: return emptyList()
        val byKey = LinkedHashMap<Int, VideoQuality>()
        for (group in p.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            val mediaGroup = group.mediaTrackGroup
            for (i in 0 until mediaGroup.length) {
                val f = mediaGroup.getFormat(i)
                val bw = (if (f.averageBitrate > 0) f.averageBitrate else f.bitrate).toLong()
                val key = if (f.height > 0) f.height else bw.toInt()
                if (key != 0 && !byKey.containsKey(key)) byKey[key] = VideoQuality(f.height, bw)
            }
        }
        return byKey.values.sortedByDescending { if (it.height > 0) it.height else it.bandwidth.toInt() }
    }

    /** After the destination is chosen, offer the stream's qualities when it
     *  exposes more than one; a single-quality source goes straight to the
     *  download. */
    private fun chooseQualityThenDownload(kind: DownloadKind) {
        val src = sources.getOrNull(currentIndex)
        val isHls = src != null &&
            (src.isM3u8 || src.url.substringBefore('?').lowercase().contains(".m3u8"))
        val known = availableVideoQualities()
        // Nothing has played (the normal download path: picked straight from the
        // detail page), so the player has no parsed tracks to read the qualities
        // from — ask the server's own master playlist instead. Without this a
        // multi-quality source silently downloaded at whatever the engine
        // defaults to, with no way to ask for 720p.
        if (known.isEmpty() && isHls && src != null) {
            val progress = showGlassProgress(
                I18n.t("Choose quality"),
                I18n.t("Asking the server which qualities it has\u2026"),
                cancelable = true,
            ) { leaveAfterDownloadPick() }
            lifecycleScope.launch {
                val fromPlaylist = withContext(Dispatchers.IO) {
                    runCatching { DownloadEngine.hlsQualities(src.url, src.headers) }
                        .getOrDefault(emptyList())
                }
                runCatching { progress.dismiss() }
                offerQualitiesThenDownload(kind, fromPlaylist.map { VideoQuality(it.first, it.second) })
            }
            return
        }
        offerQualitiesThenDownload(kind, known)
    }

    /** The quality menu itself, shared by both ways of learning the qualities
     *  (the player's parsed tracks, the master playlist). */
    private fun offerQualitiesThenDownload(kind: DownloadKind, qualities: List<VideoQuality>) {
        if (qualities.size <= 1) {
            startDownload(kind, 0, 0L)
            return
        }
        val options = mutableListOf(
            GlassOption(
                I18n.t("Highest quality"),
                I18n.t("The best this server offers"),
                selected = true
            )
        )
        qualities.forEach { q ->
            options.add(
                GlassOption(
                    label = if (q.height > 0) "${q.height}p" else I18n.t("Default quality"),
                    badge = bitrateBadge(q.bandwidth),
                )
            )
        }
        var picked = false
        showGlassMenu(
            I18n.t("Choose quality"),
            options,
            hint = I18n.t("Used only for this download."),
            iconRes = R.drawable.ic_quality,
            onDialog = { dlg ->
                dlg.setOnDismissListener { if (!picked && downloadPickMode) leaveAfterDownloadPick() }
            },
        ) { which ->
            picked = true
            if (which == 0) {
                startDownload(kind, 0, 0L)
            } else {
                qualities.getOrNull(which - 1)?.let { startDownload(kind, it.height, it.bandwidth) }
            }
        }
    }

    /** A download launched from outside the player is finished once the task is
     *  queued (or impossible): there is nothing to watch here, so the player
     *  hands the user back to the screen they came from. A no-op for the
     *  in-player Download button, where playback is running. */
    private fun leaveAfterDownloadPick() {
        if (!downloadPickMode) return
        downloadPickMode = false
        window?.decorView?.postDelayed({ if (!isFinishing && !isDestroyed) finish() }, 1200L)
    }

    /**
     * The " · name" tail of an episode label, or "" when the episode's own name
     * adds nothing.
     *
     * Extensions routinely name a row after its number — literally "Episode
     * 158", "Ep 158", "第158集" — and the label around it already says which
     * episode this is, so the old unconditional " · ${ep.name}" produced the
     * reported "Episode 158 · Episode 158" (and, on the loading card, the same
     * string twice). A name that is only an episode tag is dropped; a real title
     * ("Freedom Day") still shows.
     */
    private fun episodeNameSuffix(name: String?): String {
        val n = name?.trim().orEmpty()
        if (n.isEmpty() || com.hikari.app.nuvio.EpisodeTitles.isGeneric(n)) return ""
        return " · $n"
    }

    /** The top bar's second line (e.g. "S1 E2 · Freedom Day") — the episode
     *  label a download is filed under. */
    private fun episodeLabel(): String =
        findViewById<TextView>(R.id.subtitle_text)?.text?.toString().orEmpty()

    /** Queues a download of the CURRENT server. The task id is per episode +
     *  destination, so re-downloading an episode replaces the old entry rather
     *  than piling up duplicates. */
    private fun startDownload(kind: DownloadKind, preferredHeight: Int, preferredBandwidth: Long) {
        val src = sources.getOrNull(currentIndex) ?: return
        if (src.url.isBlank() || src.isTorrent || src.torrentStream) {
            Toast.makeText(this, I18n.t("This server can't be downloaded."), Toast.LENGTH_SHORT).show()
            leaveAfterDownloadPick()
            return
        }
        val providerId = historyEntry?.providerId.orEmpty()
        val mediaId = historyEntry?.mediaId ?: src.url
        val episodeId = historyEntry?.episodeId.orEmpty()
        val task = DownloadTask(
            id = DownloadTask.idFor(providerId.ifBlank { "player" }, mediaId, episodeId, kind),
            title = historyEntry?.title
                ?: intent.getStringExtra("title").orEmpty().ifBlank { "Video" },
            episodeLabel = episodeLabel(),
            poster = historyEntry?.posterUrl,
            providerId = providerId,
            mediaId = mediaId,
            episodeId = episodeId,
            sourceName = src.name,
            url = src.url,
            headers = src.headers,
            isM3u8 = src.isM3u8 ||
                src.url.substringBefore('?').lowercase().contains(".m3u8"),
            subtitles = src.subtitles,
            preferredHeight = preferredHeight,
            preferredBandwidth = preferredBandwidth,
            kind = kind,
            status = DownloadStatus.QUEUED,
            createdAt = System.currentTimeMillis(),
            resumePartial = false,
        )
        val queued = runCatching { DownloadsRepository.enqueue(this, task) }
        if (queued.isFailure) {
            // Never let a queueing failure take the app down with it; the user
            // gets a sentence instead.
            Toast.makeText(this, I18n.t("Could not start the download."), Toast.LENGTH_SHORT).show()
            leaveAfterDownloadPick()
            return
        }
        requestNotificationPermission()
        Toast.makeText(
            this,
            if (kind == DownloadKind.EXPORT) I18n.t("Downloading to phone storage…")
            else I18n.t("Downloading for offline watch…"),
            Toast.LENGTH_SHORT,
        ).show()
        // A download picked from outside the player is done: hand the user back
        // rather than leaving them on a player that is playing nothing.
        leaveAfterDownloadPick()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            runCatching {
                notificationPermLauncher?.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun showError(message: String, hasNext: Boolean) {
        hideLoadingBanner(immediate = true)
        var text = message
        // Why nothing played. The detail screen's search reports how many
        // extensions were actually asked and what the providers answered; read
        // it straight off the session (the status collector may not have
        // delivered yet). This is the difference between "the app is broken"
        // and "the only extension you have enabled doesn't carry this title".
        if (message.contains("No playable sources", true)) {
            val why = intent.getStringExtra("streamsLiveId")
                ?.let { StreamsLive.statusFlow(it).value }
                ?.trim()
                ?: loadingStatusBase?.trim()
            if (!why.isNullOrBlank() && !text.contains(why)) text += "\n\n" + why
        }
        // px.* / tracker domains that resolve to 0.0.0.0 are the signature of
        // a system-level ad-blocker or DNS filter — tell the user, since it
        // isn't something Hikari can fix from inside the app. Only match real
        // resolution/connect failures: "Failed to connect" alone is too broad
        // (it also wraps CDN-side 403s and read timeouts, which are NOT the
        // user's network).
        if (message.contains("Unable to resolve host", true) ||
            message.contains("Failed to resolve", true) ||
            message.contains("UnknownHost", true) ||
            message.contains("0.0.0.0", true) ||
            message.contains("network is unreachable", true)
        ) {
            text += "\n\nThis server's CDN is blocked or unreachable from your network " +
                "(a system-level ad-blocker or DNS filter may be resolving it to 0.0.0.0). " +
                "Pick another server, or retry."
        }
        errorText?.text = text
        nextBtn?.text = if (hasNext) "Try next server" else "Retry all"
        errorPanel?.visibility = View.VISIBLE
    }

    private fun selectFirstTextTrack(player: ExoPlayer, tracks: Tracks, pickApplied: Boolean = false) {
        if (userPickedSubs) return
        // A remembered pick that IS present on this source outranks the default
        // — without this, the auto-select re-asserts itself on the rebuilt
        // track list and wipes the subtitle the user just chose. When the pick
        // isn't available here (a failover to a server without that language),
        // the source's own best track is shown instead of nothing.
        if (pickApplied || textOff) return
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            val mediaGroup = group.mediaTrackGroup
            // Prefer an English track when the stream offers several: the first
            // one is often a forced/foreign track that only captions a line or
            // two of the whole film.
            val best = (0 until mediaGroup.length).firstOrNull { i ->
                val f = mediaGroup.getFormat(i)
                (f.language ?: "").startsWith("en", true) ||
                    (f.language ?: "").contains("english", true) ||
                    (f.label ?: "").contains("english", true)
            } ?: 0
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(
                    TrackSelectionOverride(mediaGroup, ImmutableList.of(best))
                )
                .build()
            return
        }
    }

    /** Re-applies the user's remembered subtitle / audio pick to the CURRENT
     *  track list, returning whether the pick is in effect afterwards.
     *  Attaching provider subtitles — and pressing Sync — rebuilds the media
     *  item, and a rebuilt source exposes brand-new TrackGroup instances; an
     *  override keyed on the old group matches nothing, which is exactly how a
     *  chosen subtitle stopped having any effect and a second audio track never
     *  switched. Never fights a pick that is already in effect, so it is safe
     *  to call on every track change. */
    private fun applyStickyPicks(type: Int): Boolean {
        val p = player ?: return false
        val pick = if (type == C.TRACK_TYPE_TEXT) pickText else pickAudio
        if (pick == null) return false
        if (type == C.TRACK_TYPE_TEXT && textOff) return false
        val groups = p.currentTracks.groups.filter { it.type == type }
        if (groups.isEmpty()) return false
        for (group in groups) {
            val mediaGroup = group.mediaTrackGroup
            for (i in 0 until mediaGroup.length) {
                if (!pick.matches(mediaGroup.getFormat(i), i)) continue
                val params = p.trackSelectionParameters
                if (params.overrides[mediaGroup]?.trackIndices?.contains(i) == true) return true
                p.trackSelectionParameters = params.buildUpon()
                    .setTrackTypeDisabled(type, false)
                    .clearOverridesOfType(type)
                    .setOverrideForType(TrackSelectionOverride(mediaGroup, ImmutableList.of(i)))
                    .build()
                return true
            }
        }
        return false
    }

    private fun mimeFor(url: String): String = when {
        url.contains(".vtt", true) -> MimeTypes.TEXT_VTT
        url.contains(".ass", true) -> MimeTypes.TEXT_SSA
        url.contains(".srt", true) -> MimeTypes.APPLICATION_SUBRIP
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    /**
     * Persists current playback position into the watch history (if the
     * detail screen supplied history context and the user hasn't paused
     * history). Skips the very start of a video (<10s — a quick peek shouldn't
     * litter the history) and throttles to one write per 10s of progress.
     */
    private fun recordProgress() {
        val entry = historyEntry ?: return
        val p = player ?: return
        val pos = p.currentPosition
        // 5s (not 10s) is "meaningfully started" — the earlier threshold meant a
        // short-but-real watch left NO history at all, which is exactly how the
        // Continue Watching shelf and the resume prompt stayed empty.
        if (pos < 5_000) return
        if (kotlin.math.abs(pos - lastSavedPos) < 5_000) return
        lastSavedPos = pos
        // 0 (not pos) when the duration isn't known yet — otherwise the entry
        // looks "finished" (pos == dur) to the Continue Watching filter and is
        // silently dropped from the Home shelf.
        val dur = p.duration.takeIf { it > 0 } ?: 0L
        val h = entry.copy(positionMs = pos, durationMs = dur, watchedAt = System.currentTimeMillis())
        // Process-wide scope: the final write fired from onStop/onDestroy must
        // not be cancelled with the Activity (it used to be, silently).
        val app = applicationContext as HikariApp
        app.appScope.launch {
            try {
                if (!app.store.historyPaused()) app.store.addHistory(h)
            } catch (_: Throwable) {
                // history is best-effort — never let it break playback
            }
        }
    }

    /**
     * The in-video "Continue from where you left off?" prompt. Fires once per
     * play session, after the first frame is on screen, whenever this video has
     * saved progress that is worth resuming — the saved position is read from
     * the store (same identity the detail screen uses), falling back to the
     * cross-provider hint the detail screen passed. Suppressed when the launch
     * already decided (an explicit resume seek) or opted out.
     */
    private fun maybeOfferResume() {
        if (resumeOffered || historyKey.isBlank()) return
        if (startPositionMs > 0L || !intent.getBooleanExtra("histAskResume", true)) return
        resumeOffered = true
        val key = historyKey
        val he = historyEntry
        val hintPos = resumeHintMs
        val hintDur = resumeHintDurMs
        (applicationContext as HikariApp).appScope.launch {
            val all = runCatching {
                (applicationContext as HikariApp).store.history()
            }.getOrDefault(emptyList())
            // Exact identity first. The user very often reopens the SAME video
            // through a DIFFERENT extension, so fall back to matching on the
            // media + episode id (ignoring provider/type) and take the most
            // recent — otherwise a cross-provider replay never saw its saved
            // progress and the "continue?" prompt silently never appeared.
            val h = all.firstOrNull { it.uniqueKey == key }
                ?: he?.let { e ->
                    all.filter { it.mediaId == e.mediaId && it.episodeId == e.episodeId }
                        .maxByOrNull { it.watchedAt }
                }
            var pos = h?.positionMs ?: 0L
            var dur = h?.durationMs ?: 0L
            if (pos <= 0L && hintPos > 0L) {
                pos = hintPos
                dur = hintDur
            }
            if (!resumable(pos, dur)) return@launch
            if (isFinishing || isDestroyed) return@launch
            runOnUiThread { showResumeDialog(pos) }
        }
    }

    private fun resumable(pos: Long, dur: Long): Boolean =
        pos >= 5_000L && (dur <= 0L || pos < dur - 10_000L)

    private fun showResumeDialog(positionMs: Long) {
        if (isFinishing || isDestroyed) return
        val clock = fmtResumeClock(positionMs)
        showGlassMenu(
            I18n.t("Continue from where you left off?"),
            listOf(
                GlassOption(
                    I18n.t("Resume"),
                    I18n.t("Pick up at %s").replace("%s", clock),
                    iconRes = R.drawable.hikari_play,
                    chevron = true,
                    marker = RowMarker.ICON,
                ),
                GlassOption(
                    I18n.t("Start over"),
                    I18n.t("Play this video from the beginning"),
                    iconRes = R.drawable.ic_back,
                    chevron = true,
                    marker = RowMarker.ICON,
                ),
            ),
            hint = I18n.t("You can seek to %s any time.").replace("%s", clock),
            iconRes = R.drawable.ic_skip,
        ) { which ->
            if (which == 0) applyResume(positionMs)
        }
    }

    private fun applyResume(positionMs: Long) {
        val p = player ?: return
        val dur = p.duration
        val target = if (dur > 0L) {
            positionMs.coerceAtMost(dur - 1000L).coerceAtLeast(0L)
        } else positionMs.coerceAtLeast(0L)
        p.seekTo(target)
    }

    private fun fmtResumeClock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0L)
        return if (s >= 3600) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        } else {
            String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60)
        }
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * Immersive fullscreen is not sticky: coming back from the background, or
     * closing one of the player's own dialogs (resume prompt, server picker,
     * download sheet), hands focus back with the system bars shown again —
     * which leaves a blank, status-bar-sized band at the top of the video
     * ("fullscreen mode leaves a blank bar in the status bar"). Re-hide the
     * bars every time this activity is resumed or regains focus.
     */
    override fun onResume() {
        super.onResume()
        hideSystemUi()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onStart() {
        super.onStart()
        // The CloudStream Torrent engine resolves its cache dir from the
        // activity reference (throws "No activity" otherwise).
        com.lagradost.cloudstream3.CommonActivity.setActivityInstance(this)
    }

    override fun onStop() {
        // Persist the final position as soon as the activity goes to the
        // background (home button, lock screen, app switch) — onDestroy may
        // come later or never (background process death).
        recordProgress()
        if (com.lagradost.cloudstream3.CommonActivity.activity === this) {
            com.lagradost.cloudstream3.CommonActivity.setActivityInstance(null)
        }
        super.onStop()
    }

    override fun onDestroy() {
        stopBannerAnimators()
        recordProgress()
        liveStreamsJob?.cancel()
        liveStreamsJob = null
        liveEpisodeJob?.cancel()
        liveEpisodeJob = null
        liveStatusJob?.cancel()
        liveStatusJob = null
        stopLoadingTicker()
        intent.getStringExtra("streamsLiveId")?.let { StreamsLive.remove(it) }
        saveTask?.let { saveHandler.removeCallbacks(it) }
        saveTask = null
        dismissSlowDialog()
        dismissFailDialog()
        dismissSlowNetTip()
        hudHideTask?.let { hudHandler.removeCallbacks(it) }
        hudHideTask = null
        SlowNetTip.onPlaybackEnd()
        watchdogTask?.let { bufferingWatchdog.removeCallbacks(it) }
        watchdogTask = null
        firstFrameTask?.let { bufferingWatchdog.removeCallbacks(it) }
        firstFrameTask = null
        torrentDialog?.let { runCatching { it.dismiss() } }
        torrentDialog = null
        probeDialog?.let { runCatching { it.dismiss() } }
        probeDialog = null
        player?.let { p ->
            p.removeListener(listener)
            p.release()
        }
        player = null
        super.onDestroy()
    }

    companion object {
        /** Safety ceiling on how long an instantly-opened player waits for the
         *  first server from the detail screen's live search. The detail screen
         *  normally signals completion ([StreamsLive.markDone]) long before
         *  this; the timeout only covers the search never reporting back. */
        private const val LIVE_WAIT_TIMEOUT_MS = 90_000L

        /** The cover's default line while the detail screen hasn't reported any
         *  search progress yet (matches the layout's initial text). */
        private const val DEFAULT_LOADING_STATUS = "Finding the best server…"

        /** Prefix the detail screen gives a search that ended with nothing to
         *  play (see DetailScreen's "no playable server" note). Kept in sync
         *  with that string so the player can fail fast on the text alone. */
        private const val NO_RESULT_PREFIX = "No playable server found"

        /** How many times a player whose every server died may ask the detail
         *  screen for a fresh extraction before finally reporting failure.
         *  Bounded so a genuinely dead video can't loop forever. */
        private const val MAX_REFRESH_ATTEMPTS = 2

        /** How long to wait for re-extracted servers to arrive on the live
         *  session before giving up and showing the error panel. Generous
         *  because the fresh extraction may include a title search across the
         *  other installed extensions, which takes longer than re-running one
         *  repo. */
        private const val REFRESH_WAIT_MS = 40_000L

        /** How long to wait for a fresh link for the server that just failed
         *  (see onPlayerError's reconnect). Short: the user is sitting on the
         *  title card with no video, and the normal failover must not be held
         *  back for long. */
        private const val RELINK_WAIT_MS = 12_000L

        private val SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

        /** How much a vertical drag moves the brightness/volume sliders, in
         *  "screen heights". 4 means roughly a quarter of a screen-height swipe
         *  covers the whole 0..100% range (the previous 1:1 mapping was reported
         *  as needing 8-9 full-screen swipes, i.e. far too insensitive). */
        private const val GESTURE_SWIPE_GAIN = 4f

        /** Fallback public trackers for addons that don't ship their own. */
        private val TORRENT_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.demonii.com:1337/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://exodus.desync.com:6969/announce",
            "https://tracker.gbitt.info:443/announce",
            "http://tracker.openbittorrent.com:80/announce",
        )
    }
}

/**
 * Load-error handling tuned for aggregator CDNs — the reason a stream stalls or
 * dies mid-playback on these sources in the first place.
 *
 * media3's [DefaultLoadErrorHandlingPolicy] retries everything it doesn't
 * explicitly exclude on a fixed 1 s → 5 s ladder and gives up after 3 tries.
 * That shape is wrong for these sources in both directions:
 *
 *  - a transient drop (one hung socket, a 502 from an overloaded edge, a
 *    connection reset mid-segment) gets only 3 retries — often not enough — so
 *    it surfaces as a fatal playback error: the player tears the stream down
 *    and fails over, even though re-requesting the same bytes on a fresh
 *    connection would have been seamless;
 *  - a genuinely dead link (expired signed URL answering 403/410, a 404)
 *    *also* burns those retries first, delaying the failover by seconds.
 *
 * So: terminal errors fail immediately (no delay at all), and transient ones
 * retry on a short 0.5 s → 2 s ladder for at most [MAX_RETRY_WINDOW_MS] of
 * wall-clock time, then escalate. Bounding by TIME rather than by attempt count
 * also fixes the worst case of an unreachable host: a 15 s connect timeout can
 * only be paid once inside that window instead of once per attempt.
 *
 * media3's variant/location fallback for adaptive (HLS/DASH) streams is left
 * intact — it is genuinely useful when one rendition of a master playlist is
 * broken while the others are fine.
 */
private class RetryFriendlyLoadErrorPolicy :
    DefaultLoadErrorHandlingPolicy(TRANSIENT_RETRIES) {

    /** When each load task first reported an error, so its retry ladder can be
     *  bounded by total wall-clock time rather than a raw attempt count. */
    private val firstErrorAt = ConcurrentHashMap<Long, Long>()

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        if (isTerminal(loadErrorInfo.exception)) return C.TIME_UNSET
        val now = android.os.SystemClock.elapsedRealtime()
        val startedAt = firstErrorAt.getOrPut(loadErrorInfo.loadEventInfo.loadTaskId) { now }
        if (now - startedAt > MAX_RETRY_WINDOW_MS) {
            firstErrorAt.remove(loadErrorInfo.loadEventInfo.loadTaskId)
            return C.TIME_UNSET
        }
        return minOf(loadErrorInfo.errorCount * 500L, 2_000L)
    }

    override fun onLoadTaskConcluded(loadTaskId: Long) {
        firstErrorAt.remove(loadTaskId)
    }

    /** Errors that re-requesting cannot fix: the URL is expired or rejected, the
     *  bytes aren't media at all, or the server was asked for a range it cannot
     *  satisfy. Escalate immediately so the failover is instant. */
    private fun isTerminal(e: java.io.IOException): Boolean = when (e) {
        is HttpDataSource.CleartextNotPermittedException -> true
        is FileNotFoundException -> true
        is ParserException -> true
        // A 4xx is the server saying "no" (expired token, forbidden, gone);
        // 408/429 mean "come back in a moment" and are worth retrying.
        is HttpDataSource.InvalidResponseCodeException ->
            e.responseCode in 400..499 && e.responseCode != 408 && e.responseCode != 429
        else -> DataSourceException.isCausedByPositionOutOfRange(e)
    }
}

/** Attempts allowed before a *transient* error is treated as fatal (also the
 *  ceiling the Loader itself consults; the time window below usually stops the
 *  ladder first). */
private const val TRANSIENT_RETRIES = 8

/** How long one load task may keep retrying a transient error before it is
 *  escalated to the app's own failover / source-refresh logic. */
private const val MAX_RETRY_WINDOW_MS = 15_000L

/** How long the live progress tally may stay completely unchanged before the
 *  Sources panel stops describing the search as still running. A search that is
 *  genuinely working changes the tally constantly (a repo starts, a repo
 *  finishes, servers land), so a count that has not moved for this long is the
 *  frozen "N still searching" that used to sit on screen forever. */
private const val SEARCH_QUIET_MS = 60_000L
