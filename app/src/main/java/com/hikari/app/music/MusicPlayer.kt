package com.hikari.app.music

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import com.hikari.app.data.Logs
import com.hikari.app.net.DohDns
import com.hikari.app.tv.TvMode
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The one music player of the app. It lives for the whole process (so music keeps playing
 * while Hikari's own screens are used), and [MusicService] publishes it to the system
 * notification / lock screen / headset buttons.
 */
@UnstableApi
object MusicPlayer {
    data class State(
        val queue: List<MSong> = emptyList(),
        val index: Int = -1,
        val playing: Boolean = false,
        val buffering: Boolean = false,
        val shuffle: Boolean = false,
        val repeat: Int = Player.REPEAT_MODE_OFF,
        val error: String? = null,
    ) {
        val song: MSong? get() = queue.getOrNull(index)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var exo: ExoPlayer? = null
    private var appContext: Context? = null
    private var controller: Any? = null
    private var radio = false
    private var radioBusy = false
    /** Automatic retries of the song that is failing (0, 1, 2). Cleared when a song starts playing. */
    private val retryCount = HashMap<String, Int>()

    /** The song the person asked for (tap, queue jump, skip button). It is never silently swapped for another one. */
    private var userPickedId: String? = null

    /** The next song an automatic skip is about to move to, so that move is not mistaken for a pick. */
    private var autoSkipTarget: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stallJob: Job? = null
    private var xfadeJob: Job? = null
    private const val TAG = "HikariMusic"

    /** How long a song may sit on "Loading" before it is asked for again (phone). */
    private const val STALL_MS = 45_000L

    /** Television, first load of a song: the lookup itself may take up to 30 s, so allow for it. */
    private const val TV_STALL_MS = 75_000L

    /** Television, a song that was already playing and went back to "Loading": ask for a fresh address sooner. */
    private const val TV_MID_STALL_MS = 15_000L

    /** Retries of one failing song before the player gives up on it (first try + 2 retries). */
    private const val MAX_RETRIES = 2

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val id = mediaItem?.mediaId
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                if (id != null && id == autoSkipTarget) autoSkipTarget = null else userPickedId = id
            }
            publish()
            _state.value.song?.let { MusicStore.recordPlay(it) }
            maybeMoreRadio()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = publish()
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = publish()
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = publish()
        override fun onRepeatModeChanged(repeatMode: Int) = publish()

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                retryCount.clear()
                exo?.currentMediaItem?.mediaId?.let { MusicApi.markPlayed(it) }
            }
            if (playbackState == Player.STATE_BUFFERING) armStall() else stallJob?.cancel()
            publish()
        }

        override fun onPlayerError(error: PlaybackException) {
            stallJob?.cancel()
            Logs.logError(TAG, "player error " + error.errorCodeName, error)
            handleError()
        }
    }

    /** Creates the player on first use. Always called on the main thread. */
    fun player(ctx: Context): ExoPlayer {
        exo?.let { return it }
        val app = ctx.applicationContext
        appContext = app
        MusicStore.ensure(app)
        val tv = TvMode.isTv
        val sources: DataSource.Factory = if (tv) {
            // Television: stream through the app's own OkHttp stack (DNS-over-HTTPS fallback, same as the
            // video player). The plain system HTTP stack uses the TV's own DNS, which on many networks
            // cannot resolve the audio servers. MusicDataSource looks the address up itself, reads
            // YouTube audio in 1 MB range requests (one long connection is throttled / dropped after
            // about half a minute) and replaces an address that YouTube refuses.
            val tvClient = OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .dns(DohDns)
                .build()
            val base = DefaultDataSource.Factory(app, OkHttpDataSource.Factory(tvClient))
            DataSource.Factory { MusicDataSource(base.createDataSource()) }
        } else {
            // Phone: unchanged.
            val http = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(20_000)
            ResolvingDataSource.Factory(DefaultDataSource.Factory(app, http), ResolvingDataSource.Resolver { spec ->
                if (spec.uri.scheme != "hikarimusic") {
                    spec
                } else {
                    val id = spec.uri.lastPathSegment.orEmpty()
                    // Pass queue metadata so Vx lossless can strict-match (remix/slowed fall back to YouTube).
                    val meta = _state.value.queue.firstOrNull { it.id == id }
                    val url = MusicApi.resolveStreamBlocking(id, meta)
                    spec.buildUpon()
                        .setUri(Uri.parse(url))
                        .setHttpRequestHeaders(mapOf("User-Agent" to MusicApi.userAgentFor(url)))
                        .build()
                }
            })
        }
        val mediaFactory = DefaultMediaSourceFactory(sources).let {
            // Television: a few more automatic network retries than the default 3.
            if (tv) it.setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(5)) else it
        }
        val builder = ExoPlayer.Builder(app)
            .setMediaSourceFactory(mediaFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
        if (tv) {
            // A bigger cushion on a television, so a slow patch of Wi-Fi does not drop to "Loading".
            builder.setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(20_000, 90_000, 1_500, 3_000)
                    .build(),
            )
        }
        val p = builder.build()
        p.addListener(listener)
        exo = p
        runCatching {
            val sid = p.audioSessionId
            if (sid != androidx.media3.common.C.AUDIO_SESSION_ID_UNSET) VxAudioFx.attach(app, sid)
        }
        // Re-apply FX when audio session changes (wired sets a new id).
        p.addListener(object : androidx.media3.common.Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                runCatching { VxAudioFx.attach(app, audioSessionId) }
            }
        })
        // Crossfade: fade-out before track end + fade-in after, when enabled.
        runCatching { VxAudioFx.apply(app) }
        startXfadeLoop(app, p)
        // Television: get the YouTube extractor and its PO token ready now, so the first song does not wait for them.
        if (tv) MusicApi.prewarmTelevision()
        return p
    }

    /** Fade-out the last seconds of a track and fade back in, when crossfade is on. */
    private fun startXfadeLoop(app: Context, p: ExoPlayer) {
        xfadeJob?.cancel()
        xfadeJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(500)
                val pl = exo ?: break
                if (pl !== p) break
                runCatching {
                    val on = VxSettings.crossfade(app)
                    val secs = VxSettings.xfadeSecs(app).coerceIn(1, 12)
                    if (!on) {
                        if (pl.volume < 1f) pl.volume = 1f
                    } else {
                        val dur = pl.duration
                        val pos = pl.currentPosition
                        if (dur > 0 && pl.playWhenReady && dur != androidx.media3.common.C.TIME_UNSET) {
                            val remain = dur - pos
                            if (remain <= secs * 1000L && remain > 0) {
                                val f = (remain.toFloat() / (secs * 1000f)).coerceIn(0f, 1f)
                                pl.volume = (f * f).coerceIn(0.05f, 1f)
                            } else if (pl.volume < 1f) {
                                pl.volume = (pl.volume + 0.12f).coerceAtMost(1f)
                            }
                        }
                    }
                }
            }
        }
    }

    fun init(ctx: Context) {
        player(ctx)
    }

    /** Connects the media notification service (once), so playback survives leaving the app. */
    private fun bindService() {
        if (controller != null) return
        val app = appContext ?: return
        runCatching {
            val token = SessionToken(app, ComponentName(app, MusicService::class.java))
            controller = MediaController.Builder(app, token).buildAsync()
        }
    }

    private fun item(s: MSong): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(s.title)
            .setArtist(s.artist)
        if (s.album.isNotBlank()) meta.setAlbumTitle(s.album)
        if (s.thumb.isNotBlank()) meta.setArtworkUri(Uri.parse(MusicApi.hq(s.thumb, 512)))
        return MediaItem.Builder()
            .setMediaId(s.id)
            .setUri("hikarimusic://s/" + s.id)
            .setMediaMetadata(meta.build())
            .build()
    }

    private fun publish() {
        val p = exo ?: return
        val st = _state.value
        val idx = if (st.queue.isEmpty()) -1 else p.currentMediaItemIndex.coerceIn(0, st.queue.lastIndex)
        val ps = p.playbackState
        _state.value = st.copy(
            index = idx,
            playing = p.playWhenReady && ps != Player.STATE_ENDED && ps != Player.STATE_IDLE,
            buffering = ps == Player.STATE_BUFFERING,
            shuffle = p.shuffleModeEnabled,
            repeat = p.repeatMode,
        )
    }

    /**
     * A song that never leaves "Loading" (an address that cannot be reached, a slow lookup) used
     * to hang there for good. After [STALL_MS] it is treated like a failed song: asked for again
     * once, then skipped.
     */
    private fun armStall() {
        stallJob?.cancel()
        val tv = TvMode.isTv
        // A song that was already playing and fell back to "Loading" is asked for again sooner than
        // one that never started (its lookup may legitimately still be running).
        val midSong = (exo?.currentPosition ?: 0L) > 1_500L
        val wait = when {
            !tv -> STALL_MS
            midSong -> TV_MID_STALL_MS
            else -> TV_STALL_MS
        }
        stallJob = scope.launch {
            delay(wait)
            val p = exo ?: return@launch
            if (p.playbackState == Player.STATE_BUFFERING && p.playWhenReady) {
                Logs.log(TAG, "still loading after " + (wait / 1000) + "s, asking again")
                handleError()
            }
        }
    }

    /**
     * Throws the current song's loader away and starts it again from the same spot.
     * Plain prepare() only works from the IDLE (error) state; while the song sits on "Loading"
     * (BUFFERING) it does nothing, so the old stall retry never actually asked for a new address.
     */
    private fun restartCurrent() {
        val p = exo ?: return
        val idx = p.currentMediaItemIndex
        val pos = p.currentPosition.coerceAtLeast(0L)
        p.stop()
        p.seekTo(idx, pos)
        p.prepare()
        p.play()
    }

    /**
     * A song failed or sat on "Loading" too long.
     *
     * 1. Up to [MAX_RETRIES] times the SAME song is asked for again from the same spot. Each retry goes
     *    one step further down the lookup chain (see [MusicApi.invalidate]), it is never handed the
     *    address that just failed.
     * 2. Then, if the song was only reached automatically (the previous one ended), the queue moves on.
     *    A song the person picked is NEVER swapped for another one: it stays selected, playback stops,
     *    a message says so, and pressing Play tries it again from scratch. (An earlier version skipped to
     *    the next queued song, which is how a Hindi song started playing after a Russian one was picked.)
     */
    private fun handleError() {
        val p = exo ?: return
        val id = p.currentMediaItem?.mediaId ?: return
        MusicApi.invalidate(id)
        val n = (retryCount[id] ?: 0) + 1
        val limit = if (TvMode.isTv) MAX_RETRIES else 1 // phone keeps its single retry
        if (n <= limit) {
            retryCount[id] = n
            Logs.log(TAG, "retry " + n + "/" + limit + " for " + id)
            restartCurrent()
            return
        }
        retryCount.remove(id)
        val title = _state.value.song?.title.orEmpty()
        _state.value = _state.value.copy(
            error = if (title.isBlank()) "Couldn't play this song" else "Couldn't play " + title,
        )
        val next = p.nextMediaItemIndex
        if (id != userPickedId && next != C.INDEX_UNSET) {
            autoSkipTarget = p.getMediaItemAt(next).mediaId
            p.seekToNextMediaItem()
            p.prepare()
            p.play()
        } else {
            p.stop()
            publish()
        }
    }

    // ------------------------------------------------------------------ radio (auto-continue)

    private fun loadRadio(seed: MSong) {
        radioBusy = true
        scope.launch {
            val list = runCatching { MusicApi.radio(seed.id) }.getOrNull().orEmpty()
            val p = exo
            if (p != null && radio) {
                val have = _state.value.queue.map { it.id }.toSet()
                val add = list.filter { it.id !in have }
                if (add.isNotEmpty()) {
                    p.addMediaItems(add.map { item(it) })
                    _state.value = _state.value.copy(queue = _state.value.queue + add)
                }
            }
            radioBusy = false
        }
    }

    private fun maybeMoreRadio() {
        val st = _state.value
        if (radio && !radioBusy && st.queue.isNotEmpty() && st.index >= st.queue.size - 2) {
            loadRadio(st.queue.last())
        }
    }

    // ------------------------------------------------------------------ commands (main thread)

    fun playQueue(ctx: Context, songs: List<MSong>, start: Int = 0, shuffle: Boolean = false, asRadio: Boolean = false) {
        if (songs.isEmpty()) return
        val p = player(ctx)
        bindService()
        radio = asRadio
        retryCount.clear()
        val first = start.coerceIn(0, songs.lastIndex)
        userPickedId = songs[first].id
        autoSkipTarget = null
        MusicApi.resetAttempts(songs[first].id)
        _state.value = _state.value.copy(queue = songs, index = first, error = null)
        p.shuffleModeEnabled = shuffle
        p.setMediaItems(songs.map { item(it) }, first, 0L)
        p.prepare()
        p.play()
        publish()
        if (asRadio) loadRadio(songs[first])
    }

    /** One song, then YouTube Music keeps going with similar songs. */
    fun playSong(ctx: Context, song: MSong) = playQueue(ctx, listOf(song), 0, false, true)

    fun playNext(ctx: Context, song: MSong) {
        val p = player(ctx)
        val st = _state.value
        if (st.queue.isEmpty()) {
            playSong(ctx, song)
            return
        }
        val at = (st.index + 1).coerceIn(0, st.queue.size)
        p.addMediaItem(at, item(song))
        _state.value = st.copy(queue = st.queue.toMutableList().apply { add(at, song) })
    }

    fun addToQueue(ctx: Context, song: MSong) {
        val p = player(ctx)
        val st = _state.value
        if (st.queue.isEmpty()) {
            playSong(ctx, song)
            return
        }
        p.addMediaItem(item(song))
        _state.value = st.copy(queue = st.queue + song)
    }

    fun removeAt(index: Int) {
        val p = exo ?: return
        val st = _state.value
        if (index !in st.queue.indices || st.queue.size <= 1) return
        p.removeMediaItem(index)
        _state.value = st.copy(queue = st.queue.toMutableList().apply { removeAt(index) })
        publish()
    }

    fun jumpTo(index: Int) {
        val p = exo ?: return
        if (index !in _state.value.queue.indices) return
        retryCount.clear()
        userPickedId = _state.value.queue[index].id
        autoSkipTarget = null
        MusicApi.resetAttempts(_state.value.queue[index].id)
        p.seekTo(index, 0L)
        p.prepare()
        p.play()
    }

    fun togglePlay() {
        val p = exo ?: return
        if (p.playbackState == Player.STATE_ENDED) {
            p.seekTo(0, 0L)
            p.play()
        } else if (p.playWhenReady && p.playbackState != Player.STATE_IDLE) {
            p.pause()
        } else {
            if (p.playbackState == Player.STATE_IDLE) {
                // Play pressed on a song that failed: a completely fresh try, from the first step.
                retryCount.clear()
                p.currentMediaItem?.mediaId?.let { MusicApi.resetAttempts(it) }
                p.prepare()
            }
            p.play()
        }
    }

    fun pause() {
        exo?.pause()
    }

    fun next() {
        val p = exo ?: return
        if (p.hasNextMediaItem()) p.seekToNextMediaItem()
    }

    fun previous() {
        val p = exo ?: return
        if (p.currentPosition > 3000 || !p.hasPreviousMediaItem()) p.seekTo(0L) else p.seekToPreviousMediaItem()
    }

    fun seekTo(ms: Long) {
        exo?.seekTo(ms.coerceAtLeast(0L))
    }

    fun toggleShuffle() {
        val p = exo ?: return
        p.shuffleModeEnabled = !p.shuffleModeEnabled
    }

    fun cycleRepeat() {
        val p = exo ?: return
        p.repeatMode = when (p.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /** The queued song with this id (the data source asks for it when it looks the stream address up). */
    fun songFor(id: String): MSong? = _state.value.queue.firstOrNull { it.id == id }

    fun position(): Long = exo?.currentPosition ?: 0L

    /** Track length in ms; falls back to the length the song list gave. */
    fun duration(fallbackSec: Int): Long {
        val d = exo?.duration ?: C.TIME_UNSET
        return if (d == C.TIME_UNSET || d <= 0L) fallbackSec * 1000L else d
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}
