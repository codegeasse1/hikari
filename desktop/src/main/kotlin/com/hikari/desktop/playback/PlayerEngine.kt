package com.hikari.desktop.playback

import com.hikari.desktop.engine.EngineStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration as JavaDuration
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class PlayerState(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = false,
    val buffering: Boolean = true,
    val error: String? = null,
    val volume: Double = 1.0,
    val rate: Float = 1f,
    val title: String = "",
    val streamName: String = "",
)

/** Engine contract for in-app playback. JavaFX backs it; tests use the fake. */
interface PlayerEngine {
    fun state(): StateFlow<PlayerState>
    fun open(stream: EngineStream, title: String)
    fun play()
    fun pause()
    fun toggle()
    fun seekTo(positionMs: Long)
    fun seekBy(deltaMs: Long)
    fun setRate(rate: Float)
    fun setVolume(volume: Double)
    fun currentStream(): EngineStream?
    fun close()
}

/** Headless fake for unit tests — scripted state, no media stack. */
class FakePlayerEngine : PlayerEngine {
    private val holder = MutableStateFlow(PlayerState())
    private var stream: EngineStream? = null

    override fun state(): StateFlow<PlayerState> = holder

    override fun open(stream: EngineStream, title: String) {
        this.stream = stream
        holder.value = PlayerState(durationMs = 3_600_000, title = title, streamName = stream.name, buffering = false)
    }

    override fun play() {
        holder.value = holder.value.copy(playing = true, buffering = false)
    }

    override fun pause() {
        holder.value = holder.value.copy(playing = false)
    }

    override fun toggle() {
        val s = holder.value
        holder.value = s.copy(playing = !s.playing, buffering = false)
    }

    override fun seekTo(positionMs: Long) {
        holder.value = holder.value.copy(positionMs = positionMs.coerceIn(0, holder.value.durationMs))
    }

    override fun seekBy(deltaMs: Long) = seekTo(holder.value.positionMs + deltaMs)

    override fun setRate(rate: Float) {
        holder.value = holder.value.copy(rate = rate)
    }

    override fun setVolume(volume: Double) {
        holder.value = holder.value.copy(volume = volume.coerceIn(0.0, 1.0))
    }

    override fun currentStream(): EngineStream? = stream

    override fun close() {
        holder.value = PlayerState()
        stream = null
    }
}

/**
 * JavaFX-backed player (progressive + HLS). All JavaFX contact stays inside
 * this class and only runs when the user actually opens it — startup, tests
 * and the packaged runtime never touch the toolkit otherwise.
 */
class JavaFxPlayerEngine(
    private val scope: CoroutineScope,
) : PlayerEngine {
    private val holder = MutableStateFlow(PlayerState())
    private val streamRef = AtomicReference<EngineStream?>(null)
    private var poll: Job? = null

    // JavaFX objects, touched only on their own threads.
    private var panelRef: Any? = null
    private var playerRef: Any? = null
    private var viewRef: Any? = null

    override fun state(): StateFlow<PlayerState> = holder

    override fun open(stream: EngineStream, title: String) {
        close()
        streamRef.set(stream)
        holder.value = PlayerState(title = title, streamName = stream.name, buffering = true)
        try {
            ensureToolkit()
            val panel = createPanel()
            panelRef = panel
            val ready = java.util.concurrent.CountDownLatch(1)
            var failed: String? = null
            javafx.application.Platform.runLater {
                try {
                    val media = javafx.scene.media.Media(stream.url)
                    val player = javafx.scene.media.MediaPlayer(media)
                    playerRef = player
                    val view = javafx.scene.media.MediaView(player)
                    view.isPreserveRatio = true
                    viewRef = view
                    val root = javafx.scene.layout.StackPane(view)
                    root.style = "-fx-background-color: black;"
                    val scene = javafx.scene.Scene(root, 960.0, 540.0, javafx.scene.paint.Color.BLACK)
                    (panel as javafx.embed.swing.JFXPanel).scene = scene
                    player.setOnReady {
                        holder.value = holder.value.copy(
                            durationMs = player.totalDuration.toMillis().toLong().coerceAtLeast(0),
                            buffering = false,
                        )
                        player.play()
                    }
                    player.setOnPlaying { holder.value = holder.value.copy(playing = true, buffering = false, error = null) }
                    player.setOnPaused { holder.value = holder.value.copy(playing = false) }
                    player.setOnStalled { holder.value = holder.value.copy(buffering = true) }
                    player.setOnError {
                        holder.value = holder.value.copy(
                            buffering = false,
                            playing = false,
                            error = player.error?.message?.take(300) ?: "playback failed",
                        )
                    }
                    media.setOnError {
                        holder.value = holder.value.copy(
                            buffering = false,
                            playing = false,
                            error = media.error?.message?.take(300) ?: "could not open this stream",
                        )
                    }
                    player.volume = holder.value.volume
                    player.rate = holder.value.rate.toDouble()
                } catch (e: Exception) {
                    failed = e.message?.take(300) ?: "player failed to start"
                } finally {
                    ready.countDown()
                }
            }
            ready.await()
            if (failed != null) {
                holder.value = holder.value.copy(buffering = false, error = failed)
                return
            }
            startPoll()
        } catch (e: Exception) {
            holder.value = holder.value.copy(buffering = false, error = (e.message ?: "player failed to start").take(300))
        }
    }

    /** The Swing surface for Compose's SwingPanel — null until open(). */
    fun surface(): java.awt.Component? = panelRef as? java.awt.Component

    override fun play() = fxRun {
        (playerRef as? javafx.scene.media.MediaPlayer)?.play()
    }

    override fun pause() = fxRun {
        (playerRef as? javafx.scene.media.MediaPlayer)?.pause()
    }

    override fun toggle() {
        val playing = holder.value.playing
        if (playing) pause() else play()
    }

    override fun seekTo(positionMs: Long) = fxRun {
        (playerRef as? javafx.scene.media.MediaPlayer)?.seek(javafx.util.Duration.millis(positionMs.toDouble()))
    }

    override fun seekBy(deltaMs: Long) = seekTo(holder.value.positionMs + deltaMs)

    override fun setRate(rate: Float) {
        holder.value = holder.value.copy(rate = rate)
        fxRun { (playerRef as? javafx.scene.media.MediaPlayer)?.rate = rate.toDouble() }
    }

    override fun setVolume(volume: Double) {
        holder.value = holder.value.copy(volume = volume.coerceIn(0.0, 1.0))
        fxRun { (playerRef as? javafx.scene.media.MediaPlayer)?.volume = volume.coerceIn(0.0, 1.0) }
    }

    override fun currentStream(): EngineStream? = streamRef.get()

    override fun close() {
        poll?.cancel()
        poll = null
        streamRef.set(null)
        val player = playerRef as? javafx.scene.media.MediaPlayer
        playerRef = null
        viewRef = null
        panelRef = null
        if (player != null) {
            fxRun { runCatching { player.dispose() } }
        }
    }

    private fun startPoll() {
        poll?.cancel()
        poll = scope.launch(Dispatchers.Default) {
            while (true) {
                val player = playerRef as? javafx.scene.media.MediaPlayer
                if (player == null) break
                runCatching {
                    val pos = player.currentTime.toMillis().toLong().coerceAtLeast(0)
                    val dur = player.totalDuration.toMillis().toLong().coerceAtLeast(0)
                    val st = player.status
                    holder.value = holder.value.copy(
                        positionMs = pos,
                        durationMs = if (dur > 0) dur else holder.value.durationMs,
                        playing = st == javafx.scene.media.MediaPlayer.Status.PLAYING,
                        buffering = st == javafx.scene.media.MediaPlayer.Status.STALLED ||
                            st == javafx.scene.media.MediaPlayer.Status.UNKNOWN,
                    )
                }
                delay(500)
            }
        }
    }

    private fun fxRun(block: () -> Unit) {
        if (playerRef == null) return
        if (javafx.application.Platform.isFxApplicationThread()) {
            runCatching { block() }
        } else {
            javafx.application.Platform.runLater { runCatching { block() } }
        }
    }

    companion object {
        @Volatile
        private var toolkitUp = false
        private val toolkitLock = Any()

        fun ensureToolkit() {
            if (toolkitUp) return
            synchronized(toolkitLock) {
                if (toolkitUp) return
                val latch = java.util.concurrent.CountDownLatch(1)
                var err: Throwable? = null
                try {
                    javafx.application.Platform.startup { latch.countDown() }
                } catch (e: IllegalStateException) {
                    // Toolkit already started elsewhere.
                    latch.countDown()
                } catch (e: Throwable) {
                    err = e
                    latch.countDown()
                }
                latch.await()
                if (err != null) throw IllegalStateException(err.message ?: "JavaFX failed to start")
                toolkitUp = true
            }
        }

        private fun createPanel(): javafx.embed.swing.JFXPanel {
            val ref = AtomicReference<javafx.embed.swing.JFXPanel?>()
            val err = AtomicReference<Throwable?>()
            javax.swing.SwingUtilities.invokeAndWait {
                try {
                    ref.set(javafx.embed.swing.JFXPanel())
                } catch (e: Throwable) {
                    err.set(e)
                }
            }
            err.get()?.let { throw IllegalStateException(it.message ?: "surface failed") }
            return ref.get() ?: throw IllegalStateException("surface failed")
        }
    }
}

/** Fetches a subtitle/caption file as text (best-effort, short timeout). */
internal fun fetchSubtitleText(url: String, headers: Map<String, String> = emptyMap()): String? {
    return runCatching {
        val builder = HttpRequest.newBuilder(URI.create(url.trim()))
            .timeout(JavaDuration.ofSeconds(20))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .GET()
        for ((k, v) in headers) runCatching { builder.header(k, v) }
        val client = HttpClient.newBuilder()
            .connectTimeout(JavaDuration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
        val resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) null else resp.body()?.take(500_000)
    }.getOrNull()
}
