package com.hikari.app.ui.screens

import com.hikari.app.data.ContentRepository
import com.hikari.app.data.MediaItem
import com.hikari.app.data.SearchResultsCache
import com.hikari.app.ui.PosterLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide owner of the current search.
 *
 * The problem this fixes: searching across every extension is a long multi-page
 * scan (page 1 shows ~20 hits, then more pages keep streaming in up to ~100+).
 * Tapping a result opens the player in a separate Activity, which recreates the
 * Search screen's ViewModel when the user comes back — and a ViewModel-scoped
 * search job dies with it, so the whole scan restarted from page 1 and the
 * already-found results were thrown away.
 *
 * Holding the job + results here (instead of in the ViewModel) means:
 *  - the scan keeps running in the background while a result is playing, and
 *  - returning to Search instantly re-shows everything found so far and lets
 *    the same scan continue, instead of starting over.
 *
 * Items are stored already tokenized (tiny disk-cache tokens, see
 * [PosterLoader.tokenize]) so holding a hundred posters in memory stays cheap.
 */
object SearchSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var currentKey: String? = null

    private val _results = MutableStateFlow<List<MediaItem>>(emptyList())
    val results: StateFlow<List<MediaItem>> = _results.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private fun key(query: String, providers: Set<String>): String =
        query.trim() + "\u0000" + providers.sorted().joinToString(",")

    /**
     * Starts the search for [query]/[providers], or — if that exact search is
     * already running or already finished — leaves the live session untouched so
     * the caller just renders what has accumulated so far. Only a genuinely new
     * query (or provider set) cancels the in-flight scan.
     */
    fun search(repo: ContentRepository, query: String, providers: Set<String>) {
        val k = key(query, providers)
        // Same search: keep the running job and its partial results. This is
        // what makes coming back from the player resume instead of restart.
        if (k == currentKey) return
        SearchResultsCache.get(k)?.let { cached ->
            job?.cancel()
            currentKey = k
            _results.value = cached
            _searching.value = false
            return
        }
        job?.cancel()
        currentKey = k
        _results.value = emptyList()
        _searching.value = true
        // Register the scan as background work: it is a long multi-page sweep,
        // and without this Android freezes the process the moment the user
        // leaves the app, stopping the search mid-page. See [BackgroundWork].
        val work = com.hikari.app.work.BackgroundWork.begin(
            "Searching \"${query.trim().take(60)}\""
        )
        val started = scope.launch {
            try {
                repo.searchStreaming(query, providerIds = providers).collect { raw ->
                    _results.value = raw.map { it.tokenizePoster() }
                }
                SearchResultsCache.put(k, _results.value)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Keep whatever pages already landed; the finally flips the flag.
            } finally {
                _searching.value = false
            }
        }
        // Covers cancellation too (a new query replaces this job), so the
        // service can never be left on by an aborted search.
        started.invokeOnCompletion { com.hikari.app.work.BackgroundWork.end(work) }
        job = started
    }

    /** Aborts the current search and clears the session (empty query box). */
    fun clear() {
        job?.cancel()
        job = null
        currentKey = null
        _results.value = emptyList()
        _searching.value = false
    }

    private fun MediaItem.tokenizePoster(): MediaItem {
        val p = PosterLoader.tokenize(posterUrl)
        val b = PosterLoader.tokenize(backdropUrl)
        return if (p == posterUrl && b == backdropUrl) this
        else copy(posterUrl = p, backdropUrl = b)
    }
}
