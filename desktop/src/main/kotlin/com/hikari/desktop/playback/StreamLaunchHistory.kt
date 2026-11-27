package com.hikari.desktop.playback

import com.hikari.desktop.data.AddonEpisode
import com.hikari.desktop.data.AddonTitle
import com.hikari.desktop.data.WatchHistoryEntry
import com.hikari.desktop.data.WatchHistoryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class WatchHistoryLaunchOutcome(
    val destination: String,
    val entries: List<WatchHistoryEntry>?,
    val historyError: Exception?,
)

internal suspend fun launchAndRecordHistory(
    store: WatchHistoryStore,
    title: AddonTitle,
    episode: AddonEpisode? = null,
    clock: () -> Long = System::currentTimeMillis,
    launch: suspend () -> String,
): WatchHistoryLaunchOutcome {
    val destination = launch()
    return try {
        val entries = withContext(Dispatchers.IO) {
            store.record(title, episode, clock())
        }
        WatchHistoryLaunchOutcome(destination, entries, null)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        WatchHistoryLaunchOutcome(destination, null, error)
    }
}
