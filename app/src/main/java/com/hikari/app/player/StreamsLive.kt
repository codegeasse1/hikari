package com.hikari.app.player

import com.hikari.app.data.ContentRepository
import com.hikari.app.data.Episode
import com.hikari.app.data.MediaItem
import com.hikari.app.data.StreamSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
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

    /** The video's background-sweep hold key, per session (see
     *  [ContentRepository.holdSweepFor]). */
    private val sweepKeys = ConcurrentHashMap<String, String>()

    /** The title each session's sweep belongs to, so the player can hand the
     *  sweep back to the hold when it closes (see [pauseSweep]). */
    private val sweepOwners = ConcurrentHashMap<String, Pair<MediaItem, Episode?>>()

    /**
     * Keep this session's background extension search off the loading screen.
     *
     * The pass's sweep — "keep asking every installed extension" — must not run
     * while the user is looking at the loading cover waiting for the video: it
     * cold-starts plugin runtimes and keeps a "searching extensions…" line
     * alive over a screen whose question is "is my film starting?". It is held
     * here and released by the player on its first rendered frame, with a
     * backstop so unfinished work can never be stranded.
     */
    fun holdSweep(id: String, item: MediaItem, episode: Episode?) {
        sweepOwners[id] = item to episode
        sweepKeys[id] = ContentRepository.holdSweepFor(item, episode)
    }

    /** Playback really started: let the held sweep run. */
    fun releaseSweep(id: String?) {
        if (id == null) return
        ContentRepository.releaseHeldSweepKey(sweepKeys.remove(id))
    }

    /** The player is closing: stop this title's background sweep and hold the
     *  rest of its work (see [ContentRepository.pauseSweepFor]). A nuvio search
     *  boots a QuickJS engine per provider, and continuing it while the user is
     *  back on the app's own screens is what made leaving the player feel heavy
     *  for a few seconds. The title's servers are untouched; the sweep resumes
     *  the next time this title is played, or after the hold's own backstop. */
    fun pauseSweep(id: String?) {
        if (id == null) return
        val owner = sweepOwners[id] ?: return
        ContentRepository.pauseSweepFor(owner.first, owner.second)
    }

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
        // `update` (a compare-and-set loop), not `flow.value = flow.value + …`:
        // appends now arrive from the background resolution of an addon's link
        // rows as well as from the search pass itself, and a read-modify-write
        // from two threads loses one of the two batches.
        flow.update { (it + sources).distinctBy { s -> s.infoHash ?: s.url } }
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

    private val originSettled = ConcurrentHashMap<String, MutableStateFlow<Boolean>>()

    /**
     * True once the provider the title was opened FROM has answered for this
     * session — whether it produced servers or came back with nothing.
     *
     * The player holds its auto-start while that provider is still working (a
     * title opened inside an extension should play THAT extension's link, not
     * whichever other extension answered first). A fixed timeout cannot serve
     * that: a .hiki plugin's first stream call has to spin up its runtime and
     * its site session, so it can legitimately take tens of seconds — long
     * after the pass's own HTTP timeouts have fired — while waiting for a
     * genuinely dead provider would be just as bad. This flag is the honest
     * signal: the hold ends the moment the origin has actually given its
     * answer, and the fixed grace on the intent is only the backstop for a
     * search that died before it could report one.
     */
    fun originSettledFlow(id: String): MutableStateFlow<Boolean> =
        originSettled.computeIfAbsent(id) { MutableStateFlow(false) }

    fun settleOrigin(id: String) {
        originSettledFlow(id).value = true
    }

    private val statuses = ConcurrentHashMap<String, MutableStateFlow<String?>>()

    /** One human-readable line describing what the search is doing right now,
     *  written by the detail screen and read by the player's loading cover.
     *  The player opens BEFORE any server is found, so without this its
     *  "Finding the best server…" line is the only thing on screen for the
     *  whole search — with no sign of whether anything is even being asked, and
     *  no explanation at the end when nothing was found. */
    fun statusFlow(id: String): MutableStateFlow<String?> =
        statuses.computeIfAbsent(id) { MutableStateFlow(null) }

    fun setStatus(id: String, text: String?) {
        statusFlow(id).value = text
    }

    /** Drops the session. [holdSweep] is set by the player when the user left it
     *  on purpose: instead of releasing the session's background sweep — which
     *  would let a fresh nuvio search start the instant the player is destroyed,
     *  right as the app is busy tearing a player down — the sweep is held, and
     *  resumes on the next play of the same title (see [pauseSweep]). */
    fun remove(id: String, holdSweep: Boolean = false) {
        if (holdSweep) pauseSweep(id) else releaseSweep(id)
        sessions.remove(id)
        episodes.remove(id)
        dones.remove(id)
        refreshes.remove(id)
        statuses.remove(id)
        originSettled.remove(id)
        sweepKeys.remove(id)
        sweepOwners.remove(id)
    }
}
