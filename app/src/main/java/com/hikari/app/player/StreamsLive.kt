package com.hikari.app.player

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

    fun remove(id: String) {
        sessions.remove(id)
    }
}
