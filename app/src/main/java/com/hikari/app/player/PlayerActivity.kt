package com.hikari.app.player

import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.pm.ActivityInfo
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
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
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.google.common.collect.ImmutableList
import com.hikari.app.R
import com.hikari.app.data.SubtitleSource
import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private var topBar: View? = null

    private var sources: List<PlayerSource> = emptyList()
    private var currentIndex = 0

    /** True while the current source is retried with text tracks disabled
     *  (its HLS manifest carried a garbage subtitle track that made media3
     *  crash with "Expected WEBVTT. Got 1"). */
    private var noSubsRetry = false

    private val bufferingWatchdog = Handler(Looper.getMainLooper())
    private var watchdogTask: Runnable? = null

    private var speedChip: TextView? = null
    private var rotateBtn: ImageButton? = null
    private var qualityBtn: TextView? = null
    private var sourcesBtn: TextView? = null
    private var errorPanel: View? = null
    private var errorText: TextView? = null
    private var nextBtn: TextView? = null
    private var speedIndex = 2

    private var torrentDialog: android.app.ProgressDialog? = null

    private lateinit var client: OkHttpClient

    private val longPressDetector: GestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                if (player?.playWhenReady == true) applySpeed(2f)
            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        playerView = findViewById(R.id.player_view)
        topBar = findViewById(R.id.top_bar)
        speedChip = findViewById(R.id.speed_btn)
        rotateBtn = findViewById(R.id.rotate_btn)
        qualityBtn = findViewById(R.id.quality_btn)
        sourcesBtn = findViewById(R.id.sources_btn)
        errorPanel = findViewById(R.id.error_panel)
        errorText = findViewById(R.id.error_text)
        nextBtn = findViewById(R.id.next_btn)
        findViewById<TextView>(R.id.title_text).text = intent.getStringExtra("title").orEmpty()

        findViewById<View>(R.id.back_btn).setOnClickListener { finish() }

        speedChip?.setOnClickListener { cycleSpeed() }
        rotateBtn?.setOnClickListener { cycleRotation() }
        qualityBtn?.setOnClickListener { showQualityDialog() }
        sourcesBtn?.setOnClickListener { showSourcesDialog() }

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
            longPressDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                applySpeed(SPEEDS[speedIndex])
            }
            false
        }

        // Hide our top bar whenever the media3 controller hides, so the screen
        // stays clean while watching. Tapping the video brings controls back.
        @Suppress("DEPRECATION")
        playerView?.setControllerVisibilityListener(object : PlayerView.ControllerVisibilityListener {
            override fun onVisibilityChanged(visibility: Int) {
                topBar?.visibility = if (visibility == View.VISIBLE) View.VISIBLE else View.GONE
            }
        })

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

    private fun playSource(index: Int) {
        if (index < 0 || index >= sources.size) {
            showError("No more servers to try.", false)
            return
        }
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

    private fun playDirect(index: Int) {
        if (index < 0 || index >= sources.size) {
            showError("No more servers to try.", false)
            return
        }
        currentIndex = index
        val src = sources[index]

        sourcesBtn?.text = src.name
        errorPanel?.visibility = View.GONE

        player?.let { old ->
            old.removeListener(listener)
            old.release()
        }
        playerView?.player = null

        val dataSourceFactory = OkHttpDataSource.Factory(client)
            .setUserAgent("Hikari/" + Http.UA)
            .setDefaultRequestProperties(src.headers)

        val player = ExoPlayer.Builder(this)
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
        scheduleBufferingWatchdog()

        if (noSubsRetry) return@playDirect

        val playedIndex = index
        lifecycleScope.launch {
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
        }
    }

    /**
     * If the current server still hasn't started delivering video 20s after
     * prepare, skip to the next one — CloudStream plays in ~5s, but some
     * servers genuinely take 15-20s to spin up (cold CDN edge, slow origin),
     * so give them that long before declaring them too slow. Only fires while
     * nothing has been played yet. Torrents get a longer budget: TorrServer
     * must discover peers and pull the first pieces from cold, which regularly
     * takes 30s+.
     */
    private fun scheduleBufferingWatchdog() {
        watchdogTask?.let { bufferingWatchdog.removeCallbacks(it) }
        watchdogTask = null
        val torrent = currentIndex in sources.indices && sources[currentIndex].torrentStream
        val budget = if (torrent) 50_000L else 20_000L
        val task = Runnable {
            watchdogTask = null
            val p = player ?: return@Runnable
            if (p.playbackState == Player.STATE_BUFFERING || p.playbackState == Player.STATE_IDLE) {
                if (p.currentPosition > 0) return@Runnable
                val hasNext = currentIndex + 1 < sources.size
                if (hasNext) {
                    noSubsRetry = false
                    Toast.makeText(
                        this,
                        if (torrent) "Torrent still fetching from peers — trying next"
                        else "Server too slow — trying next",
                        Toast.LENGTH_SHORT
                    ).show()
                    playSource(currentIndex + 1)
                } else {
                    showError(
                        if (torrent) "Torrent did not start streaming (no peers?)"
                        else "Server is not responding (still buffering after 20s).",
                        false
                    )
                }
            }
        }
        watchdogTask = task
        bufferingWatchdog.postDelayed(task, budget)
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
        override fun onTracksChanged(tracks: Tracks) {
            if (noSubsRetry) return
            selectFirstTextTrack(player ?: return, tracks)
        }

        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                watchdogTask?.let { bufferingWatchdog.removeCallbacks(it) }
                watchdogTask = null
            }
        }

        override fun onPlayerError(error: PlaybackException) {
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

    private fun showError(message: String, hasNext: Boolean) {
        var text = message
        // px.* / tracker domains that resolve to 0.0.0.0 are the signature of
        // a system-level ad-blocker or DNS filter — tell the user, since it
        // isn't something Hikari can fix from inside the app.
        if (message.contains("0.0.0.0", true) ||
            message.contains("Failed to connect", true) ||
            message.contains("network connection failed", true)
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
        if (com.lagradost.cloudstream3.CommonActivity.activity === this) {
            com.lagradost.cloudstream3.CommonActivity.setActivityInstance(null)
        }
        super.onStop()
    }

    override fun onDestroy() {
        watchdogTask?.let { bufferingWatchdog.removeCallbacks(it) }
        watchdogTask = null
        torrentDialog?.let { runCatching { it.dismiss() } }
        torrentDialog = null
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
