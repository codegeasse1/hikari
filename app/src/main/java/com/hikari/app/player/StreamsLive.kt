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
        sessions.getOrPut(id) { MutableStateFlow(emptyList()) }

    fun append(id: String, sources: List<StreamSource>) {
        val flow = sessions[id] ?: return
        if (sources.isEmpty()) return
        flow.value = (flow.value + sources).distinctBy { it.infoHash ?: it.url }
    }

    fun remove(id: String) {
        sessions.remove(id)
    }
}
