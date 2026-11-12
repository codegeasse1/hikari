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
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    private val retried = HashSet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            publish()
            _state.value.song?.let { MusicStore.recordPlay(it) }
            maybeMoreRadio()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = publish()
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = publish()
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = publish()
        override fun onRepeatModeChanged(repeatMode: Int) = publish()

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) retried.clear()
            publish()
        }

        override fun onPlayerError(error: PlaybackException) {
            handleError()
        }
    }

    /** Creates the player on first use. Always called on the main thread. */
    fun player(ctx: Context): ExoPlayer {
        exo?.let { return it }
        val app = ctx.applicationContext
        appContext = app
        MusicStore.ensure(app)
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val upstream = DefaultDataSource.Factory(app, http)
        val resolving = ResolvingDataSource.Factory(upstream, ResolvingDataSource.Resolver { spec ->
            if (spec.uri.scheme != "hikarimusic") {
                spec
            } else {
                val id = spec.uri.lastPathSegment.orEmpty()
                val url = MusicApi.resolveStreamBlocking(id)
                spec.buildUpon()
                    .setUri(Uri.parse(url))
                    .setHttpRequestHeaders(mapOf("User-Agent" to MusicApi.userAgentFor(url)))
                    .build()
            }
        })
        val p = ExoPlayer.Builder(app)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolving))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        p.addListener(listener)
        exo = p
        return p
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

    private fun handleError() {
        val p = exo ?: return
        val id = p.currentMediaItem?.mediaId ?: return
        MusicApi.invalidate(id)
        if (retried.add(id)) {
            // First failure: the saved stream address may simply have expired. Ask again.
            p.prepare()
            return
        }
        _state.value = _state.value.copy(error = "Couldn't play this song")
        if (p.hasNextMediaItem()) {
            p.seekToNextMediaItem()
            p.prepare()
            p.play()
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
        retried.clear()
        val first = start.coerceIn(0, songs.lastIndex)
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
        retried.clear()
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
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
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
