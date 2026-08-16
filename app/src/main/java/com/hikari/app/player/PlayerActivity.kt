package com.hikari.app.player

import android.content.pm.ActivityInfo
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class PlayerActivity : ComponentActivity() {

    private data class PlayerSource(
        val name: String,
        val url: String,
        val headers: Map<String, String>,
        val subtitles: List<SubtitleSource>
    )

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null

    private var sources: List<PlayerSource> = emptyList()
    private var currentIndex = 0
    private var autoFallback = true

    private var speedChip: TextView? = null
    private var rotateChip: TextView? = null
    private var sourcesRow: LinearLayout? = null
    private var errorPanel: View? = null
    private var errorText: TextView? = null
    private var nextBtn: TextView? = null
    private var speedIndex = 2

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
        speedChip = findViewById(R.id.speed_btn)
        rotateChip = findViewById(R.id.rotate_btn)
        sourcesRow = findViewById(R.id.sources_row)
        errorPanel = findViewById(R.id.error_panel)
        errorText = findViewById(R.id.error_text)
        nextBtn = findViewById(R.id.next_btn)
        findViewById<TextView>(R.id.title_text).text = intent.getStringExtra("title").orEmpty()

        findViewById<View>(R.id.back_btn).setOnClickListener { finish() }

        speedChip?.setOnClickListener { cycleSpeed() }
        rotateChip?.setOnClickListener { cycleRotation() }

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
                PlayerSource(o.optString("name", "Source ${i + 1}"), o.optString("url"), headers, subs)
            }
        }.getOrDefault(emptyList())

        if (sources.isEmpty()) {
            showError("No playable sources received.", false)
            return
        }

        buildChips()
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

    private fun playSource(index: Int) {
        if (index < 0 || index >= sources.size) {
            showError("No more servers to try.", false)
            return
        }
        currentIndex = index
        val src = sources[index]

        errorPanel?.visibility = View.GONE

        player?.let { old ->
            old.removeListener(listener)
            old.release()
        }
        playerView?.player = null

        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val dataSourceFactory = OkHttpDataSource.Factory(client)
            .setUserAgent("Hikari/" + Http.UA)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(src.headers)

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
        this.player = player
        player.addListener(listener)
        playerView?.player = player

        val subtitleConfigs = src.subtitles.map { s ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(s.url))
                .setMimeType(mimeFor(s.url))
                .setLanguage(s.lang)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
        }

        val mime = when {
            src.url.contains(".m3u8", true) -> MimeTypes.APPLICATION_M3U8
            src.url.contains(".mpd", true) -> MimeTypes.APPLICATION_MPD
            else -> null
        }
        val itemBuilder = MediaItem.Builder()
            .setUri(src.url)
            .setSubtitleConfigurations(subtitleConfigs)
        if (mime != null) itemBuilder.setMimeType(mime)

        player.setMediaItem(itemBuilder.build())
        player.prepare()
        player.playWhenReady = true
        applySpeed(SPEEDS[speedIndex])

        updateChips()
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

    private fun buildChips() {
        sourcesRow?.removeAllViews()
        sources.forEachIndexed { i, src ->
            val chip = TextView(this).apply {
                text = src.name
                textSize = 13f
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = getDrawable(android.R.drawable.selectableItemBackground)
                setOnClickListener {
                    autoFallback = false
                    errorPanel?.visibility = View.GONE
                    playSource(i)
                }
            }
            sourcesRow?.addView(chip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(4); marginEnd = dp(4) })
        }
        updateChips()
    }

    private fun updateChips() {
        for (i in 0 until (sourcesRow?.childCount ?: 0)) {
            val chip = sourcesRow?.getChildAt(i) as? TextView ?: continue
            chip.setBackgroundColor(if (i == currentIndex) 0xFF1E88E5.toInt() else 0x66000000)
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

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
