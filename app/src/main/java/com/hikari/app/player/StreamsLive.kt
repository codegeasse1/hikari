package com.hikari.app.player

import com.hikari.app.data.Episode
import com.hikari.app.data.StreamSource
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

/** Live bridge between the Detail screen's ongoing multi-provider search and
 *  the player. When playback starts with the FIRST server found, the detail
 *  screen keeps appending servers (as slower providers answer) to the session's
 *  flow; the player observes it and its "Select server" dialog grows live, so
 *  the user gets instant playback plus every server from every installed
 *  provider to switch between. */
object StreamsLive {
    private val sessions = ConcurrentHashMap<String, MutableStateFlow<List<StreamSource>>>()
    private val episodes = ConcurrentHashMap<String, MutableStateFlow<Episode?>>()
    private val dones = ConcurrentHashMap<String, MutableStateFlow<Boolean>>()
    private val refreshes = ConcurrentHashMap<String, MutableStateFlow<Int>>()

    fun flow(id: String): MutableStateFlow<List<StreamSource>> =
        sessions.computeIfAbsent(id) { MutableStateFlow(emptyList()) }

    fun append(id: String, sources: List<StreamSource>) {
        if (sources.isEmpty()) return
        // Create the session if the player hasn't subscribed yet. The detail
        // screen now opens the player the instant Play is tapped and keeps
        // appending servers, so the first batch can land before the player's
        // collector attaches. A MutableStateFlow replays its current value, so
        // nothing is lost.
        val flow = sessions.computeIfAbsent(id) { MutableStateFlow(emptyList()) }
        flow.value = (flow.value + sources).distinctBy { it.infoHash ?: it.url }
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

    /** Monotonic "please run the providers again" counter for a session. The
     *  detail screen is still attached to the session while the player is up, so
     *  a player whose every server has died can ask for a fresh extraction
     *  instead of replaying a dead link forever. Hubcloud/4KHDHub hand out
     *  signed, time-limited workers.dev URLs, and a remembered source from an
     *  earlier play is stale by definition — replaying it can only 403. Each
     *  increment is one request. */
    fun refreshFlow(id: String): MutableStateFlow<Int> =
        refreshes.computeIfAbsent(id) { MutableStateFlow(0) }

    fun requestRefresh(id: String) {
        val flow = refreshFlow(id)
        flow.value = flow.value + 1
    }

    fun remove(id: String) {
        sessions.remove(id)
        episodes.remove(id)
        dones.remove(id)
        refreshes.remove(id)
    }
}
