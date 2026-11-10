package com.hikari.app.player

import com.hikari.app.data.ContentRepository
import com.hikari.app.data.Episode
import com.hikari.app.data.MediaItem
import com.hikari.app.data.StreamSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

object StreamsLive {
    private val sessions = ConcurrentHashMap<String, MutableStateFlow<List<StreamSource>>>()
    private val episodes = ConcurrentHashMap<String, MutableStateFlow<Episode?>>()
    private val dones = ConcurrentHashMap<String, MutableStateFlow<Boolean>>()
    private val refreshes = ConcurrentHashMap<String, MutableStateFlow<Int>>()

    /** The video's background-sweep hold key, per session (see
     *  [ContentRepository.holdSweepFor]). */
    private val sweepKeys = ConcurrentHashMap<String, String>()

    /** The title each session's sweep belongs to, so the player can hand the
     *  sweep back to the hold when it closes (see [pauseSweep]). */
    private val sweepOwners = ConcurrentHashMap<String, Pair<MediaItem, Episode?>>()

    fun holdSweep(id: String, item: MediaItem, episode: Episode?) {
        sweepOwners[id] = item to episode
        sweepKeys[id] = ContentRepository.holdSweepFor(item, episode)
        com.hikari.app.data.SearchScope.serverPageOpen = true
    }

    /** Playback really started: let the held sweep run. */
    fun releaseSweep(id: String?) {
        if (id == null) return
        ContentRepository.releaseHeldSweepKey(sweepKeys.remove(id))
    }

    fun pauseSweep(id: String?) {
        if (id == null) return
        val owner = sweepOwners[id] ?: return
        ContentRepository.pauseSweepFor(owner.first, owner.second)
    }

    fun flow(id: String): MutableStateFlow<List<StreamSource>> =
        sessions.computeIfAbsent(id) { MutableStateFlow(emptyList()) }

    // Backpressure cap: a big title's sweep can offer hundreds of servers
    // across retries; an unbounded session list pins them all (plus their
    // headers/art) until the player closes. 250 keeps every realistic title
    // whole (the OOM title had ~104) while bounding the worst case.
    private const val SESSION_SERVER_CAP = 250

    fun append(id: String, sources: List<StreamSource>) {
        if (sources.isEmpty()) return
        val flow = sessions.computeIfAbsent(id) { MutableStateFlow(emptyList()) }
        flow.update { (it + sources).distinctBy { s -> s.infoHash ?: s.url }.take(SESSION_SERVER_CAP) }
    }

    /** The episode the detail screen settled on for this session. Sent when a
     *  Play tap happened before the origin addon finished listing episodes, so
     *  the already-open player can adopt the right episode (title card, resume
     *  key and watch-history entry) instead of treating it as a movie. */
    fun episodeFlow(id: String): MutableStateFlow<Episode?> =
        episodes.computeIfAbsent(id) { MutableStateFlow<Episode?>(null) }

    fun setEpisode(id: String, episode: Episode) {
        episodeFlow(id).value = episode
    }

    /** True once the detail screen's source search has finished (whether or not
     *  it found anything). The instantly-opened player uses this to stop
     *  waiting and report "no sources" the moment the search really is over. */
    fun doneFlow(id: String): MutableStateFlow<Boolean> =
        dones.computeIfAbsent(id) { MutableStateFlow(false) }

    fun markDone(id: String) {
        doneFlow(id).value = true
    }

    fun refreshFlow(id: String): MutableStateFlow<Int> =
        refreshes.computeIfAbsent(id) { MutableStateFlow(0) }

    fun requestRefresh(id: String) {
        val flow = refreshFlow(id)
        flow.value = flow.value + 1
    }

    private val originSettled = ConcurrentHashMap<String, MutableStateFlow<Boolean>>()

    fun originSettledFlow(id: String): MutableStateFlow<Boolean> =
        originSettled.computeIfAbsent(id) { MutableStateFlow(false) }

    fun settleOrigin(id: String) {
        originSettledFlow(id).value = true
    }

    private val statuses = ConcurrentHashMap<String, MutableStateFlow<String?>>()

    fun statusFlow(id: String): MutableStateFlow<String?> =
        statuses.computeIfAbsent(id) { MutableStateFlow(null) }

    fun setStatus(id: String, text: String?) {
        statusFlow(id).value = text
    }

    fun remove(id: String, holdSweep: Boolean = false) {
        if (holdSweep) pauseSweep(id) else releaseSweep(id)
        val owner = sweepOwners[id]
        if (owner != null) {
            // Remember disabled: next Play re-searches live from zero.
            runCatching { ContentRepository.forgetLiveServers(owner.first, owner.second) }
        }
        sessions.remove(id)
        episodes.remove(id)
        dones.remove(id)
        refreshes.remove(id)
        statuses.remove(id)
        originSettled.remove(id)
        sweepKeys.remove(id)
        sweepOwners.remove(id)
        if (sessions.isEmpty()) com.hikari.app.data.SearchScope.serverPageOpen = false
    }
}
