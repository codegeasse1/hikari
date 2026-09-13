package com.hikari.app.player

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.AlertDialog
import android.app.Dialog
import android.app.PictureInPictureParams
import android.app.ProgressDialog
import android.content.pm.ActivityInfo
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Base64
import android.util.Rational
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
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
import androidx.media3.datasource.DataSourceException
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
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import coil.load
import com.google.common.collect.ImmutableList
import com.hikari.app.HikariApp
import com.hikari.app.R
import com.hikari.app.data.HistoryEntry
import com.hikari.app.data.DrmSpec
import com.hikari.app.data.Episode
import com.hikari.app.data.MediaType
import com.hikari.app.data.StreamSource
import com.hikari.app.data.SubtitleSource
import com.hikari.app.net.Http
import com.hikari.app.net.PlayerHttp
import com.hikari.app.net.StreamProbe
import com.hikari.app.ui.PosterLoader
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap

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
    )

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null

    private var sources: List<PlayerSource> = emptyList()
    private var currentIndex = 0

    /** The detail screen's live-search session id, when the player was opened
     *  through it. Lets a player whose every server has died ask the still-
     *  attached detail screen to re-run the providers with fresh, freshly-
     *  signed links (see [refreshSources]) instead of replaying a dead one. */
    private var liveSessionId: String? = null

    /** Every URL this player has already tried this session. A re-extraction
     *  usually returns the same links (same mirror) plus a few new ones, so
     *  [freshIndex] uses this to avoid handing back a URL we know is dead. */
    private val triedUrls = HashSet<String>()

    /** How many times [refreshSources] has already asked for fresh sources —
     *  bounded so a genuinely dead video fails instead of looping forever. */
    private var refreshAttempts = 0

    /** Live-update subscription to the detail screen's ongoing server search
     *  (playback starts with the first server found; this keeps appending the
     *  rest as slower providers answer). */
    private var liveStreamsJob: Job? = null

    /** Subscription to the episode the detail screen settled on when a Play tap
     *  happened before the episode list had finished loading. */
    private var liveEpisodeJob: Job? = null

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
    private var slowDialog: android.app.AlertDialog? = null
    private var slowDialogTicker: Runnable? = null

    private var speedChip: TextView? = null
    private var rotateBtn: ImageButton? = null
    private var qualityBtn: TextView? = null
    private var sourcesBtn: TextView? = null
    private var subsBtn: TextView? = null
    private var audioBtn: TextView? = null
    private var errorPanel: View? = null
    private var errorText: TextView? = null
    private var nextBtn: TextView? = null
    private var lockBtn: ImageButton? = null
    private var resizeBtn: TextView? = null
    private var skipBtn: TextView? = null
    private var unlockBtn: TextView? = null

    /** Full-screen title-card cover shown while the first server is being
     *  found / buffered (Nuvio/Stremio style). See [showLoadingBanner]. */
    private var loadingBanner: View? = null
    private var loadingBackdrop: ImageView? = null
    private var loadingTitleBox: View? = null
    private var loadingTitle: TextView? = null
    private var loadingEpisode: TextView? = null
    private var loadingDetail: TextView? = null
    private var bannerAnimators: List<android.animation.Animator> = emptyList()

    /** True while playback should be covered by the loading banner until the
     *  first frame lands (set from the launch intent, default ON). */
    private var bannerMode = true

    private var speedIndex = 2

    /** True while the controls are locked — the media3 controller stays hidden
     *  and only the center unlock button remains touchable. */
    private var controlsLocked = false

    /** Auto-rotation already applied for the current source (once the screen
     *  matched the video's aspect we stop fighting the user's rotate button). */
    private var autoRotated = false

    /** True once the user has explicitly picked a subtitle/audio setting; while
     *  set, onTracksChanged must NOT re-assert the default (first) track. */
    private var userPickedSubs = false

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

    /** url -> raw subtitle text, cached so a sync offset can re-time existing
     *  subtitles without re-fetching them over the network. */
    private val subtitleRawCache = HashMap<String, String>()

    private var torrentDialog: android.app.ProgressDialog? = null

    /** Shown while an extension-less / container-unknown stream URL is probed
     *  to discover its real mime/URL before ExoPlayer sees it. */
    private var probeDialog: android.app.ProgressDialog? = null

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        playerView = findViewById(R.id.player_view)
        subtitleScale = subsPrefs.getFloat("sub_scale", 1f)
        subtitleOffsetMs = subsPrefs.getLong("sub_offset", 0L)
        subtitlePosition = subsPrefs.getFloat("sub_pos", subtitlePosition)
        applySubtitleSize(subtitleScale)
        applySubtitlePosition(subtitlePosition)
        // YouTube-style: fade the controls out after 3s instead of media3's 5s.
        playerView?.controllerShowTimeoutMs = 3000
        // Keep our own mirror of the controller visibility (media3's
        // PlayerControlView field is private) for the tap-to-toggle logic.
        playerView?.setControllerVisibilityListener(object : PlayerView.ControllerVisibilityListener {
            override fun onVisibilityChanged(visibility: Int) {
                controllerVisible = visibility == View.VISIBLE
            }
        })
        speedChip = findViewById(R.id.speed_btn)
        rotateBtn = findViewById(R.id.rotate_btn)
        qualityBtn = findViewById(R.id.quality_btn)
        sourcesBtn = findViewById(R.id.sources_btn)
        subsBtn = findViewById(R.id.subs_btn)
        audioBtn = findViewById(R.id.audio_btn)
        lockBtn = findViewById(R.id.lock_btn)
        resizeBtn = findViewById(R.id.resize_btn)
        skipBtn = findViewById(R.id.skip_btn)
        unlockBtn = findViewById(R.id.unlock_btn)
        errorPanel = findViewById(R.id.error_panel)
        errorText = findViewById(R.id.error_text)
        nextBtn = findViewById(R.id.next_btn)
        seekFeedback = findViewById(R.id.seek_feedback)
        seekIcon = findViewById(R.id.seek_icon)
        seekText = findViewById(R.id.seek_text)
        findViewById<TextView>(R.id.title_text).text = intent.getStringExtra("title").orEmpty()

        // Top-bar episode line (e.g. "S1E2 · Freedom Day"), matching the
        // reference player's two-line title block. Hidden for movies.
        val epSeason = intent.getIntExtra("histEpisodeSeason", 0)
        val epNumber = intent.getIntExtra("histEpisodeNumber", 0)
        val epName = intent.getStringExtra("histEpisodeName").orEmpty()
        val subtitle = findViewById<TextView>(R.id.subtitle_text)
        subtitle.text = when {
            epSeason > 1 && epNumber > 0 ->
                "S$epSeason E$epNumber" + if (epName.isNotBlank()) " · $epName" else ""
            epNumber > 0 -> "Episode $epNumber" + if (epName.isNotBlank()) " · $epName" else ""
            else -> epName
        }
        subtitle.visibility = if (subtitle.text.isBlank()) View.GONE else View.VISIBLE

        findViewById<View>(R.id.back_btn).setOnClickListener { finish() }

        loadingBanner = findViewById(R.id.loading_banner)
        loadingBackdrop = findViewById(R.id.loading_backdrop)
        loadingTitleBox = findViewById(R.id.loading_title_box)
        loadingTitle = findViewById(R.id.loading_title)
        loadingEpisode = findViewById(R.id.loading_episode)
        loadingDetail = findViewById(R.id.loading_detail)
        bannerMode = intent.getBooleanExtra("showLoadingBanner", true)

        // Tap the card to skip straight to the player/controls (and stop it
        // from re-appearing if a later server attempt would show it again).
        loadingBanner?.setOnClickListener {
            bannerMode = false
            hideLoadingBanner(immediate = true)
        }

        speedChip?.setOnClickListener { cycleSpeed() }
        rotateBtn?.setOnClickListener { cycleRotation() }
        qualityBtn?.setOnClickListener { showQualityDialog() }
        sourcesBtn?.setOnClickListener { showSourcesDialog() }
        subsBtn?.setOnClickListener { showSubsDialog() }
        audioBtn?.setOnClickListener { showAudioDialog() }

        lockBtn?.setOnClickListener { lockControls() }
        resizeBtn?.setOnClickListener { cycleResize() }
        skipBtn?.setOnClickListener {
            val p = player ?: return@setOnClickListener
            val target = (p.currentPosition + 85_000L).coerceIn(
                0L, p.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
            )
            p.seekTo(target)
        }
        unlockBtn?.setOnClickListener { unlockControls() }
        unlockBtn?.background = ContextCompat.getDrawable(this, R.drawable.ic_unlock)
        unlockBtn?.setPadding(0, 0, 0, 0)

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
                // Last server failed — retry the whole list (transient CDN
                // hiccups / DNS glitches often clear on a second pass). Reset
                // the header walk first: with a single-server list the retry
                // targets the SAME index, so playSource would keep the max
                // variant (2 = no headers) and 403 again immediately.
                noSubsRetry = false
                resetHeaderWalk()
                playSource(0)
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
                    holdSpeedTimer?.let { speedHandler.removeCallbacks(it) }
                    val task = Runnable {
                        // Finger has stayed down ≥2s → play at 2× until lift.
                        holdingFast = true
                        applySpeed(2f)
                    }
                    holdSpeedTimer = task
                    speedHandler.postDelayed(task, 2000)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    holdSpeedTimer?.let { speedHandler.removeCallbacks(it) }
                    holdSpeedTimer = null
                    if (holdingFast) {
                        holdingFast = false
                        suppressNextTap = true
                        applySpeed(SPEEDS[speedIndex])
                    }
                }
            }
            true
        }

        // All our controls (Back/Title/Server/Speed on top, Quality/Sub/Rotate
        // at the bottom) live INSIDE the media3 controller layout now, so they
        // appear and fade together with the playback controls on tap.

        // Watch-history context (set by the detail screen). When present, the
        // player periodically persists resume position into the app store.
        val histProvider = intent.getStringExtra("histProviderId")
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
        }

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
                )
            }
        }.getOrDefault(emptyList())
            // The same video surfaced by both extraction engines / addons = one
            // entry. Torrents carry url="" and share their identity by infoHash,
            // so keying on url alone would collapse every torrent source into a
            // single row.
            .distinctBy { it.infoHash ?: it.url }

        val liveId = intent.getStringExtra("streamsLiveId")
        liveSessionId = liveId
        // The detail screen now opens the player the instant Play is tapped,
        // BEFORE any server is found, and streams servers to us over
        // [StreamsLive]. An empty list plus a live session id therefore means
        // "wait for the first server", not "nothing to play".
        val awaitLive = sources.isEmpty() && liveId != null
        if (sources.isEmpty() && !awaitLive) {
            showError("No playable sources received.", false)
            return
        }

        // Cover the very first frames with the title card: the detail screen
        // showed the same card while it searched, so this keeps the "finding
        // your server" screen continuous until real video is on screen.
        if (bannerMode) showLoadingBanner()

        if (liveId != null) {
            // The detail screen keeps searching every installed provider while
            // playback runs; append each newly found server here so "Select
            // server" lists everything. When we opened with no servers yet, the
            // FIRST batch that arrives also starts playback.
            liveStreamsJob = lifecycleScope.launch {
                var pendingStart = awaitLive
                val waitTimeout = if (awaitLive) launch {
                    delay(LIVE_WAIT_TIMEOUT_MS)
                    if (sources.isEmpty()) showError("No playable sources received.", false)
                } else null
                // The detail screen signals when its whole search is finished;
                // if it ended with nothing, fail fast instead of waiting out
                // the safety timeout above.
                if (awaitLive) launch {
                    StreamsLive.doneFlow(liveId).collect { done ->
                        // Only fail when the session really ended up with no
                        // servers (append happens before markDone, so a
                        // non-empty live flow means servers are on the way).
                        if (done && sources.isEmpty() && StreamsLive.flow(liveId).value.isEmpty()) {
                            showError("No playable sources received.", false)
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
                    // Resolve the new servers in the background too, so picking
                    // one from "Select server" doesn't fall back to a probe wait.
                    lifecycleScope.launch(Dispatchers.IO) {
                        runCatching { StreamProbe.warm(fresh.map { it.toStreamSource() }) }
                    }
                    if (pendingStart) {
                        pendingStart = false
                        waitTimeout?.cancel()
                        playSource(preferredStartIndex())
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
        // failover, a retry, or a later replay of the same video.
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { StreamProbe.warm(sources.map { it.toStreamSource() }) }
        }

        // If servers are already here (e.g. WebView playback or a source list
        // handed in directly), start on the server this video was last played
        // with (matched by URL then by name) so a replay picks up on a
        // known-good, already-resolved source. On the instant open (no servers
        // yet) the live collector above starts playback the moment the first
        // server arrives.
        if (sources.isNotEmpty()) lifecycleScope.launch { playSource(preferredStartIndex()) }
    }

    /** Index of the server the user last played this video with — matched by
     *  URL first (same link across runs), then by server name (signed/tokenized
     *  URLs that differ per run) — or 0 when nothing is remembered. */
    private suspend fun preferredStartIndex(): Int {
        if (historyKey.isBlank() || sources.isEmpty()) return 0
        val last = runCatching { (applicationContext as HikariApp).store.lastSource(historyKey) }
            .getOrNull() ?: return 0
        val byUrl = if (last.url.isNotBlank()) {
            sources.indexOfFirst { it.url == last.url }
        } else -1
        val byName = if (byUrl < 0 && last.name.isNotBlank()) {
            sources.indexOfFirst { it.name.equals(last.name, ignoreCase = true) }
        } else -1
        // Restore the header variant that actually played last time — but ONLY
        // on an identical URL. A name-only match is a freshly signed link (or a
        // different mirror) that may need a completely different header set, so
        // restoring the remembered variant there could pin the player to the
        // wrong variant and skip the full → no-Referer → none walk entirely.
        if (byUrl >= 0) headerVariant = last.headerVariant.coerceIn(0, 2)
        return when {
            byUrl >= 0 -> byUrl
            byName >= 0 -> byName
            else -> 0
        }
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
            Toast.makeText(this, "No video track to keep in the background", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Picture-in-picture unavailable", Toast.LENGTH_SHORT).show()
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
        requestedOrientation = next
        // Phone-tilt icon tints gold while forced-landscape so the state is
        // readable at a glance (white = free/portrait).
        val gold = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#F5C569"))
        val white = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
        rotateBtn?.imageTintList = if (next == SCREEN_ORIENTATION_PORTRAIT) white else gold
    }

    private fun applySpeed(speed: Float) {
        val p = player ?: return
        p.playbackParameters = p.playbackParameters.withSpeed(speed)
    }

    /** Locks the controls: the media3 controller stays hidden and only the
     *  center unlock button remains touchable (like the reference player's
     *  Lock button). */
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
        resizeBtn?.text = if (resizeIndex == 0) "Fit" else "Crop"
    }

    private fun toggleController() {
        if (holdingFast || controlsLocked) return
        val pv = playerView ?: return
        if (controllerVisible) pv.hideController() else pv.showController()
    }

    /** Double-tap seek: left half rewinds 5s, right half forwards 5s (matching
     *  the 5s shown on the centre rewind/forward buttons). */
    private fun seekByTap(x: Float) {
        val p = player ?: return
        val mid = (playerView?.width ?: resources.displayMetrics.widthPixels) / 2f
        val forward = x >= mid
        val delta = if (forward) 5_000L else -5_000L
        val target = (p.currentPosition + delta)
            .coerceIn(0L, p.duration.takeIf { it > 0L } ?: Long.MAX_VALUE)
        p.seekTo(target)
        playerView?.showController()
        showSeekFeedback(delta)
    }

    /** Flash the double-tap seek indicator (arrow + +5s/−5s) like YouTube. */
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

    /** Thin translucent divider used inside the glass panels. */
    private fun hairline(density: Float): View = View(this).apply {
        setBackgroundColor(0x1FFFFFFF)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()
        )
    }

    /**
     * One tappable radio row for the glass menus (server / quality / audio).
     * The "paper" look the user complained about was the platform AlertDialog's
     * flat list; here every row is a rounded pill that highlights gold when it's
     * the active choice, so the selected server/quality is obvious at a glance.
     */
    private fun glassOptionRow(label: String, selected: Boolean, onClick: () -> Unit): View {
        val density = resources.displayMetrics.density
        val accent = 0xFFF5C569.toInt()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding((12 * density).toInt(), (11 * density).toInt(), (12 * density).toInt(), (11 * density).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12 * density
                if (selected) {
                    setColor(0x33F5C569.toInt())
                    setStroke((1 * density).toInt(), 0x66F5C569.toInt())
                } else {
                    setColor(0x14FFFFFF.toInt())
                }
            }
        }
        row.addView(TextView(this).apply {
            text = if (selected) "\u25CF" else "\u25CB"
            textSize = 15f
            includeFontPadding = false
            setTextColor(if (selected) accent else 0x99FFFFFF.toInt())
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = (12 * density).toInt()
        })
        row.addView(TextView(this).apply {
            text = label
            textSize = 15f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFFD7DEEA.toInt())
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.setOnClickListener { onClick() }
        return row
    }

    /**
     * Presents a rounded, dark, gold-accented panel — the shared shell for
     * every player menu. `content` goes inside a scrollable body (capped to the
     * screen so long server/quality lists scroll within the panel), with a
     * pinned CLOSE footer. Replaces the platform's flat "paper" AlertDialog.
     */
    private fun presentGlass(dialog: Dialog, title: String, content: View, preferredHeightDp: Float) {
        val density = resources.displayMetrics.density
        val accent = 0xFFF5C569.toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 18 * density
                setColor(0xF0121723.toInt())
                setStroke((1 * density).toInt(), 0x33FFFFFF)
            }
            clipToOutline = true
        }
        root.addView(TextView(this).apply {
            text = title
            textSize = 17f
            setTextColor(accent)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.02f
            setPadding((18 * density).toInt(), (15 * density).toInt(), (18 * density).toInt(), (12 * density).toInt())
        })
        root.addView(hairline(density))
        root.addView(ScrollView(this).apply {
            addView(content)
            isFillViewport = true
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(hairline(density))
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding((10 * density).toInt(), (5 * density).toInt(), (10 * density).toInt(), (5 * density).toInt())
            addView(TextView(this@PlayerActivity).apply {
                text = "CLOSE"
                textSize = 13f
                setTextColor(accent)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                letterSpacing = 0.06f
                setPadding((16 * density).toInt(), (9 * density).toInt(), (16 * density).toInt(), (9 * density).toInt())
                isClickable = true
                setOnClickListener { dialog.dismiss() }
            })
        })
        dialog.setContentView(
            root,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        dialog.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        val dm = resources.displayMetrics
        val w = (dm.widthPixels * 0.9f).coerceAtMost(460 * density).toInt()
        val h = (preferredHeightDp * density).coerceAtMost(dm.heightPixels * 0.86f).toInt()
        dialog.window?.apply {
            setLayout(w, h)
            setDimAmount(0.65f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
    }

    /** Builds + shows a radio list panel. Each tap dismisses and reports the index. */
    private fun showGlassOptionMenu(title: String, items: List<String>, checked: Int, onPick: (Int) -> Unit) {
        val density = resources.displayMetrics.density
        val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
        }
        items.forEachIndexed { i, label ->
            content.addView(
                glassOptionRow(label, i == checked) {
                    dialog.dismiss()
                    onPick(i)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (4 * density).toInt() }
            )
        }
        presentGlass(dialog, title, content, 48f + 44f + items.size * 47f + 20f)
    }

    private fun showSourcesDialog() {
        if (sources.isEmpty()) return
        showGlassOptionMenu("Select server", sources.map { it.name }, currentIndex) { which ->
            if (which != currentIndex) {
                noSubsRetry = false
                playSource(which)
            }
        }
    }

    private fun showQualityDialog() {
        val p = player ?: return
        val groups = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
        val items = mutableListOf<String>()
        val indexMap = HashMap<Int, Pair<Tracks.Group, Int>>()
        items.add("Auto (adaptive)")
        var base = 1
        var checked = 0
        for (group in groups) {
            val mediaGroup = group.mediaTrackGroup
            val override = p.trackSelectionParameters.overrides[mediaGroup]
            for (i in 0 until mediaGroup.length) {
                val f = mediaGroup.getFormat(i)
                val label = listOfNotNull(
                    f.height.takeIf { it > 0 }?.let { "${it}p" },
                    f.width.takeIf { it > 0 }?.let { "${it}px" },
                    f.averageBitrate.takeIf { it > 0 }?.let { "${it / 1000}kbps" }
                ).joinToString(" · ").ifBlank { "Track ${i + 1}" }
                items.add(label)
                indexMap[items.size - 1] = group to i
                if (checked == 0 && override != null && override.trackIndices.any { it == i }) {
                    checked = base + i
                }
            }
            base += mediaGroup.length
        }
        showGlassOptionMenu("Video quality", items, checked) { which ->
            if (which == 0) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                    .build()
            } else {
                val (group, ti) = indexMap[which] ?: return@showGlassOptionMenu
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setOverrideForType(
                        TrackSelectionOverride(group.mediaTrackGroup, ImmutableList.of(ti))
                    )
                    .build()
            }
        }
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
        val params = p.trackSelectionParameters
        val textDisabled = params.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val density = resources.displayMetrics.density

        val items = mutableListOf<String>()
        val indexMap = HashMap<Int, Pair<Tracks.Group, Int>>()
        items.add("Off")
        items.add("Auto")
        var checked = if (textDisabled) 0 else 1
        for (group in groups) {
            val mediaGroup = group.mediaTrackGroup
            val override = params.overrides[mediaGroup]
            for (i in 0 until mediaGroup.length) {
                val f = mediaGroup.getFormat(i)
                val lang = listOfNotNull(
                    f.language?.takeIf { it.isNotBlank() },
                    f.label?.takeIf { it.isNotBlank() },
                    f.id?.takeIf { it.isNotBlank() },
                ).joinToString(" · ").ifBlank { "Track ${i + 1}" }
                items.add(lang)
                indexMap[items.size - 1] = group to i
                if (!textDisabled && override != null && override.trackIndices.any { it == i }) {
                    checked = items.size - 1
                }
            }
        }

        // Compact pill-shaped translucent +/- buttons, matching the app's glass
        // theme. They MUST stay narrow: the dialog's content area is only a few
        // hundred dp wide, and wider pills used to push the −/+ buttons past the
        // dialog's edge where they got clipped (looked like the Sync row was
        // "collapsing").
        fun pill(text: String, onClick: () -> Unit): TextView {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = (15 * density).toFloat()
                setColor(0x1AFFFFFF.toInt())
            }
            return TextView(this).apply {
                this.text = text
                textSize = 13f
                setTextColor(0xFFF5C569.toInt())
                gravity = Gravity.CENTER
                background = bg
                includeFontPadding = false
                setPadding((12 * density).toInt(), (7 * density).toInt(), (12 * density).toInt(), (7 * density).toInt())
                setOnClickListener { onClick() }
            }
        }
        fun rowLabel(text: String): TextView = TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(0xFFE6EAF3.toInt())
        }
        fun valueLabel(text: String): TextView = TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(0xFF9AA5B5.toInt())
            gravity = Gravity.CENTER
            minWidth = (48 * density).toInt()
        }
        fun weightSpacer(): View = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }

        var initializing = true
        val radioGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        items.forEachIndexed { idx, label ->
            radioGroup.addView(RadioButton(this).apply {
                text = label
                textSize = 15f
                setTextColor(0xFFE6EAF3.toInt())
                buttonTintList = ColorStateList.valueOf(0xFFF5C569.toInt())
                id = View.generateViewId()
                isChecked = idx == checked
                setOnCheckedChangeListener { _, isChecked ->
                    if (!initializing && isChecked) {
                        userPickedSubs = true
                        when (idx) {
                            0 -> p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                .build()
                            1 -> p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                .build()
                            else -> {
                                val (group, ti) = indexMap[idx] ?: return@setOnCheckedChangeListener
                                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                    .setOverrideForType(
                                        TrackSelectionOverride(group.mediaTrackGroup, ImmutableList.of(ti))
                                    )
                                    .build()
                            }
                        }
                    }
                }
            })
        }
        initializing = false

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
                addView(rowLabel(label))
                addView(weightSpacer())
                controls.forEach { addView(it) }
            }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (4 * density).toInt(), (16 * density).toInt(), (4 * density).toInt())
            addView(radioGroup, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * density).toInt() })
            addView(controlRow(
                "Text size",
                pill("A−") { subtitleScale = (subtitleScale - 0.1f).coerceIn(0.5f, 2.5f); applySize() },
                sizeValue,
                pill("A+") { subtitleScale = (subtitleScale + 0.1f).coerceIn(0.5f, 2.5f); applySize() },
            ).also { it.setPadding(0, (14 * density).toInt(), 0, 0) })
            addView(controlRow(
                "Sync",
                pill("−0.5s") { subtitleOffsetMs = (subtitleOffsetMs - 500L).coerceIn(-30000L, 30000L); applySync() },
                syncValue,
                pill("+0.5s") { subtitleOffsetMs = (subtitleOffsetMs + 500L).coerceIn(-30000L, 30000L); applySync() },
            ).also { it.setPadding(0, (10 * density).toInt(), 0, 0) })
            // Vertical position: "Higher" keeps more of the player's height
            // clear below the captions, lifting them off the bottom edge (and
            // out of the letterbox bar on a fitted/letterboxed video).
            addView(controlRow(
                "Position",
                pill("Lower") { subtitlePosition = (subtitlePosition - 0.02f).coerceIn(0.02f, 0.60f); applyPos() },
                posValue,
                pill("Higher") { subtitlePosition = (subtitlePosition + 0.02f).coerceIn(0.02f, 0.60f); applyPos() },
            ).also { it.setPadding(0, (10 * density).toInt(), 0, 0) })
        }

        // The whole panel scrolls (see presentGlass), so the Track rows plus the
        // size/sync controls can never be cut off the bottom on a short screen.
        val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        presentGlass(dialog, "Subtitles", root, 1000f)
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
     * Audio track switcher — for dual-audio releases (Hindi/Tamil/Telugu audio
     * on the same video, etc). Lists every audio group the current source
     * exposes, plus Default, and switches with an ExoPlayer track override.
     * The button sits in the SAME bottom chip row as Quality/Sub so it never
     * overlaps any other control.
     */
    private fun showAudioDialog() {
        val p = player ?: return
        val groups = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (groups.isEmpty()) {
            Toast.makeText(this, "No separate audio tracks on this stream", Toast.LENGTH_SHORT).show()
            return
        }
        val items = mutableListOf<String>()
        val indexMap = HashMap<Int, Pair<Tracks.Group, Int>>()
        items.add("Default (adaptive)")
        var checked = 0
        for (group in groups) {
            val mediaGroup = group.mediaTrackGroup
            val override = p.trackSelectionParameters.overrides[mediaGroup]
            for (i in 0 until mediaGroup.length) {
                val f = mediaGroup.getFormat(i)
                val label = listOfNotNull(
                    f.language?.takeIf { it.isNotBlank() }?.let { lang ->
                        java.util.Locale(lang).getDisplayLanguage(java.util.Locale.ENGLISH)
                            .takeIf { it.isNotBlank() } ?: lang
                    },
                    f.label?.takeIf { it.isNotBlank() },
                    f.id?.takeIf { it.isNotBlank() },
                ).joinToString(" · ").ifBlank { "Track ${i + 1}" }
                items.add(label)
                indexMap[items.size - 1] = group to i
                if (checked == 0 && override != null && override.trackIndices.any { it == i }) {
                    checked = items.size - 1
                }
            }
        }
        showGlassOptionMenu("Audio", items, checked) { which ->
            if (which == 0) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                    .build()
            } else {
                val (group, ti) = indexMap[which] ?: return@showGlassOptionMenu
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setOverrideForType(
                        TrackSelectionOverride(group.mediaTrackGroup, ImmutableList.of(ti))
                    )
                    .build()
            }
        }
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
            showError("No more servers to try.", false)
            return
        }
        if (index != currentIndex) headerVariant = 0
        autoRotated = false
        userPickedSubs = false
        currentIndex = index
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
        sourcesBtn?.text = src.name
        errorPanel?.visibility = View.GONE

        torrentDialog?.let { runCatching { it.dismiss() } }
        torrentDialog = ProgressDialog(this).apply {
            setTitle("Torrent stream")
            setMessage("Starting torrent engine…\nFirst play can take a few seconds.")
            setCancelable(false)
            setIndeterminate(true)
            show()
        }

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
                Toast.makeText(
                    this@PlayerActivity,
                    "Torrent ready — streaming from peers",
                    Toast.LENGTH_SHORT
                ).show()
                playDirect(index)
            }
            res.onFailure { e ->
                val msg = rootMessage(e)
                val hasNext = currentIndex + 1 < sources.size
                if (hasNext) {
                    noSubsRetry = false
                    Toast.makeText(this@PlayerActivity, "Torrent failed — trying next", Toast.LENGTH_SHORT).show()
                    playSource(currentIndex + 1)
                } else {
                    showError("Torrent playback failed:\n$msg", false)
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
        dataSourceFactory: OkHttpDataSource.Factory,
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
                    Toast.makeText(this, "Archive link (not a video) — trying next server", Toast.LENGTH_SHORT).show()
                    noSubsRetry = false
                    playSource(index + 1)
                } else if (!refreshSources(index)) {
                    showError("Only archive links (.zip) were found for this title — no playable video.", false)
                }
                return
            }
            // Extension-less / container-unknown URLs — HLS & DASH manifests
            // served at API paths, and JSON/HTML wrapper pages — get probed
            // once before playback so the real mime/URL is known. Otherwise
            // ExoPlayer treats them as a progressive container and reports
            // ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED on streams that are
            // perfectly playable (the "every yt-dlp source fails" symptom).
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
                showError("Playback failed to start:\n${rootMessage(t)}", false)
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
            probeDialog = ProgressDialog(this).apply {
                setTitle(src.name)
                setMessage("Preparing stream…")
                setCancelable(false)
                setIndeterminate(true)
                show()
            }
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
    }

    private fun playDirectInner(index: Int) {
        if (index < 0 || index >= sources.size) {
            showError("No more servers to try.", false)
            return
        }
        dismissSlowDialog()
        currentIndex = index
        val src = sources[index]
        // Remember what we've actually handed to ExoPlayer this session — a
        // later re-extraction usually repeats most of these URLs, and freshIndex
        // must not pick one we already know dies.
        triedUrls.add(src.url)

        sourcesBtn?.text = src.name
        errorPanel?.visibility = View.GONE
        if (bannerMode && loadingBanner?.visibility != View.VISIBLE) showLoadingBanner()

        player?.let { old ->
            old.removeListener(listener)
            old.release()
        }
        playerView?.player = null
        firstFrameTask?.let { bufferingWatchdog.removeCallbacks(it) }
        firstFrameTask = null
        renderedFirstFrame = false
        firstFrameRetried = false

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
        val dataSourceFactory = OkHttpDataSource.Factory(client)
            .setUserAgent(ua)
            .setDefaultRequestProperties(sourceHeaders)

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
            // 5s steps on the centre rewind/forward buttons (and media3's own
            // seek handling), matching the reference player. Set here rather
            // than via PlayerView XML attrs, which this media3 version lacks.
            .setSeekBackIncrementMs(5_000)
            .setSeekForwardIncrementMs(5_000)
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
        val mime = when {
            src.isM3u8 || src.url.contains(".m3u8", true) || src.url.contains("master.txt", true) ->
                MimeTypes.APPLICATION_M3U8
            src.isMpd || src.url.contains(".mpd", true) -> MimeTypes.APPLICATION_MPD
            else -> null
        }
        val itemBuilder = MediaItem.Builder().setUri(src.url)
        if (mime != null) itemBuilder.setMimeType(mime)

        player.setMediaItem(itemBuilder.build())
        player.prepare()
        player.playWhenReady = true
        applySpeed(SPEEDS[speedIndex])
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
                    Toast.makeText(this@PlayerActivity, "Video stuck — trying next server", Toast.LENGTH_SHORT).show()
                    noSubsRetry = false
                    playSource(currentIndex + 1)
                } else if (!firstFrameRetried) {
                    firstFrameRetried = true
                    Toast.makeText(this@PlayerActivity, "Video stuck — restarting", Toast.LENGTH_SHORT).show()
                    noSubsRetry = false
                    playSource(currentIndex)
                } else {
                    showError("Playback started but no video frame was rendered.", false)
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
                val valid = withContext(Dispatchers.IO) {
                    src.subtitles.mapNotNull { s ->
                        val raw = fetchSubtitleText(s, src.headers) ?: return@mapNotNull null
                        s to encodeSubtitle(shiftSubtitleText(raw, subtitleOffsetMs, s.url))
                    }
                }
                if (valid.isEmpty()) return@launch
                if (currentIndex != playedIndex) return@launch
                val p = player ?: return@launch
                val configs = valid.map { (s, data) ->
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(data))
                        .setMimeType(mimeFor(s.url))
                        .setLanguage(s.lang)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                }
                val item = MediaItem.Builder()
                    .setUri(src.url)
                    .setSubtitleConfigurations(configs)
                if (mime != null) item.setMimeType(mime)
                p.setMediaItem(item.build(), false)
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
        if (currentIndex + 1 >= sources.size) {
            showError(
                if (torrent) "Torrent did not start streaming (no peers?)"
                else "Server is not responding (still buffering after 20s).",
                false
            )
            return
        }
        if (slowDialog != null) return
        val countdown = TextView(this).apply {
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFFB8B8B8.toInt())
            setPadding(48, 0, 48, 24)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Server too slow")
            .setMessage(
                "This server is still buffering. Switch to the next server, " +
                    "or wait a little longer?"
            )
            .setView(countdown)
            .setPositiveButton("Switch now") { _, _ ->
                dismissSlowDialog()
                noSubsRetry = false
                Toast.makeText(this@PlayerActivity, "Switching server", Toast.LENGTH_SHORT).show()
                playSource(currentIndex + 1)
            }
            .setNegativeButton("Wait 30s") { _, _ ->
                dismissSlowDialog()
                // Stay on this server; the same prompt reappears after 30s if
                // it still hasn't started playing.
                scheduleBufferingWatchdog(30_000L)
            }
            .setCancelable(false)
            .create()
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
                        "Server too slow — switching to next",
                        Toast.LENGTH_SHORT
                    ).show()
                    playSource(currentIndex + 1)
                    return
                }
                countdown.text = "Switching to the next server in ${(remaining / 1000) + 1}s…"
                bufferingWatchdog.postDelayed(this, 250)
            }
        }
        slowDialogTicker = ticker
        bufferingWatchdog.post(ticker)
        dialog.show()
    }

    private fun dismissSlowDialog() {
        slowDialogTicker?.let { bufferingWatchdog.removeCallbacks(it) }
        slowDialogTicker = null
        slowDialog?.let { runCatching { it.dismiss() } }
        slowDialog = null
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
        val bytes = Http.getBytes(s.url, headers) ?: return null
        if (bytes.size > 4 * 1024 * 1024) return null
        val text = String(bytes, Charsets.UTF_8).trimStart('\uFEFF')
        val ok = when {
            s.url.contains(".vtt", true) || s.url.contains("webvtt", true) ->
                text.contains("WEBVTT", ignoreCase = true)
            s.url.contains(".ass", true) || s.url.contains(".ssa", true) ->
                text.contains("Script Info") || text.contains("Dialogue:")
            s.url.contains(".srt", true) ->
                Regex("\\d+\\s*\\n\\s*\\d{1,2}:\\d{2}:\\d{2}").containsMatchIn(text)
            else ->
                text.contains("WEBVTT", ignoreCase = true) ||
                    text.contains("Dialogue:") ||
                    Regex("\\d+\\s*\\n\\s*\\d{1,2}:\\d{2}:\\d{2}").containsMatchIn(text)
        }
        if (!ok) return null
        subtitleRawCache[s.url] = text
        return text
    }

    private fun encodeSubtitle(text: String): String =
        "data:text/plain;base64," +
            Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

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
        if (noSubsRetry) return
        val src = sources.getOrNull(currentIndex) ?: return
        if (src.subtitles.isEmpty()) {
            Toast.makeText(this, "Sync applies to downloaded subtitles", Toast.LENGTH_SHORT).show()
            return
        }
        val playedIndex = currentIndex
        val mime = when {
            src.isM3u8 || src.url.contains(".m3u8", true) || src.url.contains("master.txt", true) ->
                MimeTypes.APPLICATION_M3U8
            src.isMpd || src.url.contains(".mpd", true) -> MimeTypes.APPLICATION_MPD
            else -> null
        }
        lifecycleScope.launch {
            try {
                val configs = withContext(Dispatchers.IO) {
                    src.subtitles.mapNotNull { s ->
                        val raw = fetchSubtitleText(s, src.headers) ?: return@mapNotNull null
                        val shifted = shiftSubtitleText(raw, subtitleOffsetMs, s.url)
                        MediaItem.SubtitleConfiguration.Builder(Uri.parse(encodeSubtitle(shifted)))
                            .setMimeType(mimeFor(s.url))
                            .setLanguage(s.lang)
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .build()
                    }
                }
                if (configs.isEmpty() || currentIndex != playedIndex) return@launch
                val item = MediaItem.Builder().setUri(src.url).setSubtitleConfigurations(configs)
                if (mime != null) item.setMimeType(mime)
                p.setMediaItem(item.build(), false)
                p.prepare()
            } catch (t: Throwable) {
                android.util.Log.e("HikariPlayer", "subtitle sync attach failed", t)
            }
        }
    }

    private val listener = object : Player.Listener {
        // Auto-rotate to match the video: landscape videos play landscape,
        // portrait videos play portrait — once, per source. After that the
        // rotate button is entirely in the user's hands.
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (autoRotated) return
            if (videoSize.width <= 0 || videoSize.height <= 0) return
            autoRotated = true
            val landscape = videoSize.width > videoSize.height
            requestedOrientation = if (landscape) {
                SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                SCREEN_ORIENTATION_PORTRAIT
            }
            val gold = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#F5C569"))
            val white = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            rotateBtn?.imageTintList = if (landscape) gold else white
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (noSubsRetry) return
            selectFirstTextTrack(player ?: return, tracks)
        }

        override fun onRenderedFirstFrame() {
            renderedFirstFrame = true
            firstFrameTask?.let { bufferingWatchdog.removeCallbacks(it) }
            firstFrameTask = null
            hideLoadingBanner()
            // Playback actually started — persist this server + the header
            // variant that got us here, so the next replay of this video jumps
            // straight onto it (no re-probe, no header trial-and-error).
            sources.getOrNull(currentIndex)?.let { rememberPlayedSource(currentIndex, it) }
            maybeOfferResume()
        }

        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                dismissSlowDialog()
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
            // HLS manifests often declare a subtitle track whose URL returns
            // junk ("Expected WEBVTT. Got 1" / contentIsMalformed). media3
            // treats that as a fatal parse error — retry the SAME server with
            // text tracks disabled before giving up on it.
            val code = error.errorCode
            val subtitleIssue = !noSubsRetry &&
                (code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                    code == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED) &&
                (details.contains("WEBVTT", true) || details.contains("Expected", true) ||
                    details.contains("subtitle", true) || details.contains("TextDecoder", true))
            if (subtitleIssue) {
                Toast.makeText(this@PlayerActivity, "Bad subtitle track — retrying without subtitles", Toast.LENGTH_SHORT).show()
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
            if ((code == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS || headerIssue) && headerVariant < 2) {
                headerVariant++
                Toast.makeText(
                    this@PlayerActivity,
                    "Source rejected our request — retrying with fewer headers",
                    Toast.LENGTH_SHORT
                ).show()
                noSubsRetry = false
                playSource(currentIndex)
                return
            }
            // Like CloudStream: never strand the user — keep trying the next
            // server automatically on every failure.
            val hasNext = currentIndex + 1 < sources.size
            if (hasNext) {
                noSubsRetry = false
                Toast.makeText(this@PlayerActivity, "Server failed — trying next", Toast.LENGTH_SHORT).show()
                playSource(currentIndex + 1)
            } else {
                // No server left. If this looks like the servers simply died —
                // expired signed links (HTTP 403) or a DNS/connect failure at
                // the CDN — rather than a genuinely unplayable file, ask the
                // detail screen for a fresh extraction before giving up:
                // replaying a signed 4KHDHub/hubcloud URL after a few minutes
                // can only 403, but a re-run hands out live links.
                val ioLike = code == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                    code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                    code == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                    code == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
                    headerIssue
                if (!(ioLike && refreshSources(currentIndex))) {
                    showError(details, false)
                }
            }
        }
    }

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
    private fun showLoadingBanner() {
        val banner = loadingBanner ?: return
        val box = loadingTitleBox ?: return
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
        val model = PosterLoader.model(
            intent.getStringExtra("bannerBackdrop")?.takeIf { it.isNotBlank() }
                ?: intent.getStringExtra("histPoster")
        )
        loadingBackdrop?.let { iv ->
            if (model != null) {
                iv.visibility = View.VISIBLE
                iv.load(model)
            } else {
                iv.setImageDrawable(null)
                iv.visibility = View.GONE
            }
        }

        stopBannerAnimators()
        // The name breathes in and out, exactly like Nuvio/Stremio's title card.
        val titleScale = ObjectAnimator.ofPropertyValuesHolder(
            box,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.94f, 1.06f, 0.94f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.94f, 1.06f, 0.94f)
        ).apply {
            duration = 2600L
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            repeatCount = android.animation.ValueAnimator.INFINITE
        }
        // Slow Ken-Burns drift on the artwork (zooming in only, so a
        // centre-cropped image never reveals its edges).
        val backdropScale = loadingBackdrop?.let { iv ->
            ObjectAnimator.ofPropertyValuesHolder(
                iv,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.12f, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.12f, 1f)
            ).apply {
                duration = 12_000L
                interpolator = android.view.animation.LinearInterpolator()
                repeatCount = android.animation.ValueAnimator.INFINITE
            }
        }
        bannerAnimators = listOfNotNull(titleScale, backdropScale)
        bannerAnimators.forEach { runCatching { it.start() } }
        banner.animate().cancel()
        banner.alpha = 1f
        banner.visibility = View.VISIBLE
    }

    /** Fades the title card away (or removes it instantly) once real video is
     *  on screen. Safe to call repeatedly and from any state. */
    private fun hideLoadingBanner(immediate: Boolean = false) {
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

    /** Adopts an episode that arrived AFTER launch — the user tapped Play while
     *  the origin addon was still listing episodes, so the player opened with
     *  no episode. Updates the top-bar episode line, the loading card's episode
     *  text, and the watch-history key so progress is stored against this
     *  episode instead of the movie-level entry. */
    private fun applyLiveEpisode(ep: Episode) {
        val label = when {
            ep.season > 1 && ep.number > 0 ->
                "S${ep.season} E${ep.number}" + if (!ep.name.isNullOrBlank()) " · ${ep.name}" else ""
            ep.number > 0 ->
                "Episode ${ep.number}" + if (!ep.name.isNullOrBlank()) " · ${ep.name}" else ""
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
    private fun refreshSources(failedIndex: Int): Boolean {
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
        if (bannerMode) showLoadingBanner()
        Toast.makeText(this, "Servers have expired — re-fetching fresh sources…", Toast.LENGTH_SHORT).show()
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
            // Nothing new arrived — report the failure we were already holding.
            showError("Servers expired and no fresh sources were found.\nTry again in a moment.", false)
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

    private fun showError(message: String, hasNext: Boolean) {
        hideLoadingBanner(immediate = true)
        var text = message
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

    private fun selectFirstTextTrack(player: ExoPlayer, tracks: Tracks) {
        if (userPickedSubs) return
        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_TEXT) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(
                        TrackSelectionOverride(group.mediaTrackGroup, ImmutableList.of(0))
                    )
                    .build()
                return
            }
        }
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
        android.app.AlertDialog.Builder(this)
            .setTitle("Continue from where you left off?")
            .setMessage("Resume from ${fmtResumeClock(positionMs)}?")
            .setPositiveButton("Resume") { _, _ -> applyResume(positionMs) }
            .setNegativeButton("Start over", null)
            .show()
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
        intent.getStringExtra("streamsLiveId")?.let { StreamsLive.remove(it) }
        saveTask?.let { saveHandler.removeCallbacks(it) }
        saveTask = null
        dismissSlowDialog()
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

        /** How many times a player whose every server died may ask the detail
         *  screen for a fresh extraction before finally reporting failure.
         *  Bounded so a genuinely dead video can't loop forever. */
        private const val MAX_REFRESH_ATTEMPTS = 2

        /** How long to wait for re-extracted servers to arrive on the live
         *  session before giving up and showing the error panel. */
        private const val REFRESH_WAIT_MS = 25_000L

        private val SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

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
