package com.hikari.app.player

import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.app.ProgressDialog
import android.content.pm.ActivityInfo
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.google.common.collect.ImmutableList
import com.hikari.app.HikariApp
import com.hikari.app.R
import com.hikari.app.data.HistoryEntry
import com.hikari.app.data.MediaType
import com.hikari.app.data.SubtitleSource
import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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
    )

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null

    private var sources: List<PlayerSource> = emptyList()
    private var currentIndex = 0

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

    private var torrentDialog: android.app.ProgressDialog? = null

    /** Shown while an extension-less / container-unknown stream URL is probed
     *  to discover its real mime/URL before ExoPlayer sees it. */
    private var probeDialog: android.app.ProgressDialog? = null

    /** url -> probed (real url, mime). Probing an unknown-container URL is only
     *  done once per session; retries/switches back reuse the result. */
    private val probeCache = java.util.concurrent.ConcurrentHashMap<String, ProbeResult>()

    private lateinit var client: OkHttpClient

    /** Watch-history context passed by the detail screen. When non-null the
     *  player records resume positions into the app store. */
    private var historyEntry: HistoryEntry? = null

    /** Resume position (ms) from a history tap — seeked to on first ready. */
    private var startPositionMs = 0L

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

        findViewById<View>(R.id.back_btn).setOnClickListener { finish() }

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
                // hiccups / DNS glitches often clear on a second pass).
                noSubsRetry = false
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
            startPositionMs = intent.getLongExtra("startPosition", 0L).coerceAtLeast(0L)
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
                )
            }
        }.getOrDefault(emptyList())
            // The same video surfaced by both extraction engines / addons = one
            // entry. Torrents carry url="" and share their identity by infoHash,
            // so keying on url alone would collapse every torrent source into a
            // single row.
            .distinctBy { it.infoHash ?: it.url }

        if (sources.isEmpty()) {
            showError("No playable sources received.", false)
            return
        }

        client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        playSource(0)
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

    /** Double-tap seek: left half rewinds 10s, right half forwards 10s. */
    private fun seekByTap(x: Float) {
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

    private fun showSourcesDialog() {
        if (sources.isEmpty()) return
        val names = sources.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select server")
            .setSingleChoiceItems(names, currentIndex) { d, which ->
                if (which != currentIndex) {
                    noSubsRetry = false
                    playSource(which)
                }
                d.dismiss()
            }
            .setNegativeButton("Close", null)
            .show()
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
        AlertDialog.Builder(this)
            .setTitle("Video quality")
            .setSingleChoiceItems(items.toTypedArray(), checked) { d, which ->
                if (which == 0) {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                        .build()
                } else {
                    val (group, ti) = indexMap[which] ?: return@setSingleChoiceItems
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setOverrideForType(
                            TrackSelectionOverride(group.mediaTrackGroup, ImmutableList.of(ti))
                        )
                        .build()
                }
                d.dismiss()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /**
     * Subtitle control. Lists every available text track (HLS/DASH subtitle
     * groups AND the provider-supplied .srt/.vtt), plus Off and Auto — so a
     * stream that forces subtitles on can finally be muted, and a stream with
     * several languages gets a real picker.
     */
    private fun showSubsDialog() {
        val p = player ?: return
        val groups = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val params = p.trackSelectionParameters
        val textDisabled = params.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)

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
        AlertDialog.Builder(this)
            .setTitle("Subtitles")
            .setSingleChoiceItems(items.toTypedArray(), checked) { d, which ->
                userPickedSubs = true
                when (which) {
                    0 -> p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                    1 -> p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .build()
                    else -> {
                        val (group, ti) = indexMap[which] ?: return@setSingleChoiceItems
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                            .setOverrideForType(
                                TrackSelectionOverride(group.mediaTrackGroup, ImmutableList.of(ti))
                            )
                            .build()
                    }
                }
                d.dismiss()
            }
            .setNegativeButton("Close", null)
            .show()
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
        AlertDialog.Builder(this)
            .setTitle("Audio")
            .setSingleChoiceItems(items.toTypedArray(), checked) { d, which ->
                if (which == 0) {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                        .build()
                } else {
                    val (group, ti) = indexMap[which] ?: return@setSingleChoiceItems
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setOverrideForType(
                            TrackSelectionOverride(group.mediaTrackGroup, ImmutableList.of(ti))
                        )
                        .build()
                }
                d.dismiss()
            }
            .setNegativeButton("Close", null)
            .show()
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
        if (src.isTorrent && src.infoHash != null) {
            playTorrent(index)
            return
        }
        playDirect(index)
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

    /** Safe entry point: any unexpected exception during player setup (a bad
     *  source URL, a plugin-supplied header, an ExoPlayer hiccup) must surface
     *  as "try the next server" or an error panel — never an uncaught crash
     *  that leaves a frozen black screen. */
    private fun playDirect(index: Int) {
        try {
            val src = sources[index]
            // Extension-less / container-unknown URLs — HLS & DASH manifests
            // served at API paths, and JSON/HTML wrapper pages — get probed
            // once before playback so the real mime/URL is known. Otherwise
            // ExoPlayer treats them as a progressive container and reports
            // ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED on streams that are
            // perfectly playable (the "every yt-dlp source fails" symptom).
            val needsProbe = !src.isTorrent && !src.torrentStream && !src.isM3u8 && !src.isMpd &&
                !src.url.contains(".m3u8", true) && !src.url.contains("master.txt", true) &&
                !src.url.contains(".mpd", true) &&
                (src.url.startsWith("http://") || src.url.startsWith("https://")) &&
                !hasMediaExtension(src.url)
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

    /** A URL probed to its real media form: [url] is what to actually play and
     *  [mime] is the container to force (HLS/DASH) or null to let ExoPlayer
     *  sniff it. */
    private data class ProbeResult(val url: String, val mime: String?)

    /** Media file extensions ExoPlayer's progressive extractors sniff fine on
     *  their own — URLs ending in these skip the probe entirely. */
    private val PROBE_SKIP_EXTENSIONS = listOf(
        ".mp4", ".webm", ".mkv", ".flv", ".avi", ".mov", ".m4v", ".m4s",
        ".ts", ".mp3", ".aac", ".ogg", ".ogv", ".m4a", ".wav", ".flac",
        ".3gp", ".mpg", ".mpeg", ".opus", ".wmv",
    )

    private fun hasMediaExtension(url: String): Boolean {
        val clean = url.substringBefore('?').substringBefore('#')
        return PROBE_SKIP_EXTENSIONS.any { clean.endsWith(it, ignoreCase = true) }
    }

    /** Probes a container-unknown stream URL before ExoPlayer sees it: fetches
     *  the head of the response with the source's own headers and classifies it
     *  (HLS `#EXTM3U` / DASH `<MPD` / direct video), or — when the URL is a JSON
     *  API or HTML wrapper page — digs out the embedded media URLs and follows
     *  the most promising one. Returns the real URL + forced mime, or null when
     *  nothing resolvable was found. Never throws. */
    private fun probeStreamUrl(url: String, headers: Map<String, String>, depth: Int): ProbeResult? {
        if (depth > 3) return null
        val response = try {
            Http.get(url, headers)
        } catch (t: Throwable) {
            null
        } ?: return null
        response.use { r ->
            if (!r.isSuccessful) return null
            val ct = r.headers["Content-Type"]?.lowercase() ?: ""
            val body = r.body ?: return null
            val head: String = try {
                val buf = ByteArray(131_072)
                val input = body.byteStream()
                var read = 0
                while (read < buf.size) {
                    val n = input.read(buf, read, buf.size - read)
                    if (n < 0) break
                    read += n
                }
                // ISO-8859-1 keeps the raw bytes, so the "#EXTM3U"/"ftyp"
                // sniffs below are never thrown off by charset decoding.
                String(buf, 0, read, Charsets.ISO_8859_1)
            } catch (t: Throwable) {
                return null
            }
            val trimmed = head.trimStart()
            if (trimmed.startsWith("#EXTM3U") || ct.contains("mpegurl") || ct.contains("m3u8")) {
                return ProbeResult(url, MimeTypes.APPLICATION_M3U8)
            }
            if ((trimmed.startsWith("<?xml") && head.contains("<MPD")) || ct.contains("dash+xml")) {
                return ProbeResult(url, MimeTypes.APPLICATION_MPD)
            }
            // Direct binary container (mp4/fMP4 ftyp & friends) — ExoPlayer's
            // extractors sniff it fine, no mime to force.
            if (ct.startsWith("video/") || ct.startsWith("audio/") ||
                trimmed.startsWith("ftyp") || trimmed.startsWith("\u0000\u0000\u0000\u0018ftyp")
            ) {
                return ProbeResult(url, null)
            }
            // Wrapper page — JSON API / HTML player. Pull out the embedded
            // media URLs and follow the best candidate.
            for (c in extractMediaCandidates(head, url)) {
                val res = probeStreamUrl(c, headers, depth + 1)
                if (res != null) return res
            }
            return null
        }
    }

    private val URL_TOKEN_RE = Regex("""https?://[^\s"'<>\\]+""")

    /** Finds the media-looking URLs inside a wrapper page's head and ranks them
     *  (m3u8 > mpd > direct video > player paths) so the probe follows the most
     *  promising one first. */
    private fun extractMediaCandidates(head: String, pageUrl: String): List<String> {
        val scored = LinkedHashMap<String, Int>()
        for (m in URL_TOKEN_RE.findAll(head)) {
            val u = m.value.trimEnd(')', ']', '}', ',', ';', '.', '"', '\'')
            if (!u.startsWith("http")) continue
            if (u == pageUrl) continue
            val lower = u.lowercase()
            val score = when {
                "m3u8" in lower -> 100
                "mpd" in lower || "manifest" in lower -> 90
                lower.contains(".mp4") || lower.contains(".webm") || lower.contains(".mkv") ||
                    lower.contains(".m4s") -> 80
                "/get" in lower || "/stream" in lower || "/play" in lower || "/hls" in lower ||
                    "master" in lower || "/video" in lower -> 60
                "video" in lower || "media" in lower || "/embed" in lower -> 40
                else -> 10
            }
            if (score >= 40) scored.putIfAbsent(u, score)
        }
        return scored.entries.sortedByDescending { it.value }.take(8).map { it.key }
    }

    /** Probes the source and, when it resolves to a real media URL, rewrites
     *  the source before handing it to ExoPlayer. Always ends in playDirectInner
     *  — a probe failure falls through to the original (current) behavior. */
    private fun probeAndPlay(index: Int) {
        if (index < 0 || index >= sources.size) {
            playDirectInner(index)
            return
        }
        val src = sources[index]
        val cached = probeCache[src.url]
        if (cached != null) {
            applyProbe(index, src, cached)
            playDirectInner(index)
            return
        }
        probeDialog = ProgressDialog(this).apply {
            setTitle(src.name)
            setMessage("Preparing stream…")
            setCancelable(false)
            setIndeterminate(true)
            show()
        }
        lifecycleScope.launch {
            val resolved = withTimeoutOrNull(12_000) {
                withContext(Dispatchers.IO) {
                    val clean = sanitizeHeaders(src.headers)
                    val headers = when (headerVariant) {
                        1 -> clean.filterKeys { !it.equals("Referer", ignoreCase = true) }
                        2 -> emptyMap()
                        else -> clean
                    }
                    val ua = headers["User-Agent"]?.takeIf { it.isNotBlank() } ?: Http.UA
                    runCatching {
                        probeStreamUrl(src.url, headers + mapOf("User-Agent" to ua), 0)
                    }.getOrNull()
                }
            }
            probeDialog?.let { runCatching { it.dismiss() } }
            probeDialog = null
            if (currentIndex != index) return@launch
            if (resolved != null) probeCache[src.url] = resolved
            if (resolved != null) applyProbe(index, src, resolved)
            playDirectInner(index)
        }
    }

    private fun applyProbe(index: Int, src: PlayerSource, resolved: ProbeResult) {
        val newM3u8 = resolved.mime == MimeTypes.APPLICATION_M3U8
        val newMpd = resolved.mime == MimeTypes.APPLICATION_MPD
        if (resolved.url == src.url && src.isM3u8 == newM3u8 && src.isMpd == newMpd) return
        val list = sources.toMutableList()
        list[index] = src.copy(url = resolved.url, isM3u8 = newM3u8, isMpd = newMpd)
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

        sourcesBtn?.text = src.name
        errorPanel?.visibility = View.GONE

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

        val player = ExoPlayer.Builder(this)
            .setRenderersFactory(
                // Decoder fallback: a hardware codec that chokes on a (perfectly
                // valid) stream must degrade to a software decoder — not crash
                // the whole process into a frozen black screen (the classic
                // symptom of a native MediaCodec failure).
                DefaultRenderersFactory(this).setEnableDecoderFallback(true)
            )
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
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
        // instead of stranding the user on a dead black screen.
        if (mime != null) {
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
                        val data = validateSubtitle(s, src.headers)
                        if (data == null) null else s to data
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
     * Fetches a subtitle file with the given headers and returns it as a
     * self-contained data URI — but only if the content actually looks like a
     * subtitle. Returns null for anything that 404s, errors, or returns junk,
     * so a dead provider subtitle is silently dropped instead of crashing the
     * player.
     */
    private fun validateSubtitle(s: SubtitleSource, headers: Map<String, String>): String? {
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
        return "data:text/plain;base64," +
            Base64.encodeToString(bytes, Base64.NO_WRAP)
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
        }

        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                dismissSlowDialog()
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
            }
        }

        override fun onPlayerError(error: PlaybackException) {
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
                showError(details, false)
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

    private fun showError(message: String, hasNext: Boolean) {
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
        if (pos < 10_000) return
        if (kotlin.math.abs(pos - lastSavedPos) < 10_000) return
        lastSavedPos = pos
        val dur = p.duration.takeIf { it > 0 } ?: pos
        val h = entry.copy(positionMs = pos, durationMs = dur, watchedAt = System.currentTimeMillis())
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val app = applicationContext as HikariApp
                if (!app.store.historyPaused()) app.store.addHistory(h)
            } catch (_: Throwable) {
                // history is best-effort — never let it break playback
            }
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
        recordProgress()
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
