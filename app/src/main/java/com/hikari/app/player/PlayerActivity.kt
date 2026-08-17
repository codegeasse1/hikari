package com.hikari.app.player

import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
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
    )

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var topBar: View? = null

    private var sources: List<PlayerSource> = emptyList()
    private var currentIndex = 0
    private var autoFallback = true

    private var speedChip: TextView? = null
    private var rotateChip: TextView? = null
    private var qualityBtn: TextView? = null
    private var sourcesBtn: TextView? = null
    private var errorPanel: View? = null
    private var errorText: TextView? = null
    private var nextBtn: TextView? = null
    private var speedIndex = 2

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
        rotateChip = findViewById(R.id.rotate_btn)
        qualityBtn = findViewById(R.id.quality_btn)
        sourcesBtn = findViewById(R.id.sources_btn)
        errorPanel = findViewById(R.id.error_panel)
        errorText = findViewById(R.id.error_text)
        nextBtn = findViewById(R.id.next_btn)
        findViewById<TextView>(R.id.title_text).text = intent.getStringExtra("title").orEmpty()

        findViewById<View>(R.id.back_btn).setOnClickListener { finish() }

        speedChip?.setOnClickListener { cycleSpeed() }
        rotateChip?.setOnClickListener { cycleRotation() }
        qualityBtn?.setOnClickListener { showQualityDialog() }
        sourcesBtn?.setOnClickListener { showSourcesDialog() }

        nextBtn?.setOnClickListener {
            if (currentIndex + 1 < sources.size) {
                autoFallback = true
                playSource(currentIndex + 1)
            } else {
                finish()
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
                PlayerSource(
                    o.optString("name", "Source ${i + 1}"),
                    // Normalize Google Drive URLs to the direct-download form so
                    // the player never hits the drive virus-scan HTML page.
                    Http.normalizeDriveUrl(o.optString("url")),
                    headers,
                    subs,
                    o.optBoolean("isM3u8"),
                    o.optBoolean("isMpd"),
                )
            }
        }.getOrDefault(emptyList())

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
        rotateChip?.text = if (next == SCREEN_ORIENTATION_PORTRAIT) "⤡" else "↻"
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
                    autoFallback = true
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
            selectFirstTextTrack(player ?: return, tracks)
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
            val hasNext = currentIndex + 1 < sources.size
            if (autoFallback && hasNext) {
                autoFallback = false
                Toast.makeText(this@PlayerActivity, "Server failed — trying next", Toast.LENGTH_SHORT).show()
                playSource(currentIndex + 1)
            } else {
                showError(details, hasNext)
            }
        }
    }

    private fun showError(message: String, hasNext: Boolean) {
        errorText?.text = message
        nextBtn?.text = if (hasNext) "Try next server" else "Close"
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

    override fun onDestroy() {
        player?.let { p ->
            p.removeListener(listener)
            p.release()
        }
        player = null
        super.onDestroy()
    }

    companion object {
        private val SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    }
}
