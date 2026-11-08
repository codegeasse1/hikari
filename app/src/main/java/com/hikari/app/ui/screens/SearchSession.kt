package com.hikari.app.ui.screens

import com.hikari.app.data.ContentRepository
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.SearchResultsCache
import com.hikari.app.HikariApp
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

open class SearchSessionScope(private val snapshotName: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var currentKey: String? = null

    private val _results = MutableStateFlow<List<MediaItem>>(emptyList())
    val results: StateFlow<List<MediaItem>> = _results.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private fun key(query: String, providers: Set<String>): String =
        query.trim() + "\u0000" + providers.sorted().joinToString(",")

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
        readSnapshot()?.let { snap ->
            if (snap.key == k && snap.items.isNotEmpty()) {
                job?.cancel()
                currentKey = k
                _results.value = snap.items
                _searching.value = true
                launchScan(repo, k, merge = true)
                return
            }
        }
        job?.cancel()
        currentKey = k
        _results.value = emptyList()
        _searching.value = true
        launchScan(repo, k, merge = false)
    }

    private fun launchScan(repo: ContentRepository, k: String, merge: Boolean) {
        var started: Job? = null
        val query = k.substringBefore(keySep())
        var lastDiskWrite = 0L
        val work = com.hikari.app.work.BackgroundWork.begin(
            "Searching \"${query.trim().take(60)}\""
        ) {
            // Cancelled outright when the user closes the app (see
            // [com.hikari.app.work.BackgroundWork.cancelAll]).
            started?.cancel()
        }
        started = scope.launch {
            try {
                repo.searchStreaming(query, providerIds = providersOf(k)).collect { raw ->
                    // A superseding search started: stop feeding a dead key.
                    if (currentKey != k) return@collect
                    val page = raw.map { it.tokenizePoster() }
                    _results.value = if (merge) mergeDistinct(_results.value, page) else page
                    // Snapshot what has landed (throttled): the return path
                    // in [search] reads exactly this file.
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastDiskWrite > 2_000L) {
                        lastDiskWrite = now
                        writeSnapshot(k, _results.value)
                    }
                }
                if (currentKey == k) {
                    SearchResultsCache.put(k, _results.value)
                    writeSnapshot(k, _results.value)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Keep whatever pages already landed; the finally flips the flag.
            } finally {
                if (currentKey == k) _searching.value = false
            }
        }
        // Covers cancellation too (a new query replaces this job), so the
        // service can never be left on by an aborted search.
        started?.invokeOnCompletion { com.hikari.app.work.BackgroundWork.end(work) }
        job = started
    }

    /** Aborts the current search and clears the session (empty query box). */
    fun clear() {
        job?.cancel()
        job = null
        currentKey = null
        _results.value = emptyList()
        _searching.value = false
        // Leaving the search fully drops the snapshot too, so the next
        // search starts clean instead of resurrecting the old list.
        scope.launch { runCatching { snapshotFile()?.delete() } }
    }

    /** The separator inside a session key (see [key]): the NUL char. */
    private fun keySep(): Char = 0.toChar()

    /** The provider set encoded in a key (see [key]). */
    private fun providersOf(k: String): Set<String> {
        val tail = k.substringAfter(keySep(), "")
        return if (tail.isBlank()) emptySet() else tail.split(",").toSet()
    }

    /** Folds [page] into [into], keeping order and dropping duplicates. */
    private fun mergeDistinct(
        into: List<MediaItem>,
        page: List<MediaItem>,
    ): List<MediaItem> {
        if (into.isEmpty()) return page
        val seen = HashSet<String>(into.size + page.size)
        for (item in into) seen.add(item.uniqueId)
        val out = ArrayList<MediaItem>(into.size + page.size)
        out.addAll(into)
        for (item in page) {
            if (seen.add(item.uniqueId)) out += item
        }
        return out
    }

    private data class Snapshot(val key: String, val items: List<MediaItem>)

    private fun snapshotFile(): File? = runCatching {
        val dir = File(HikariApp.instance.filesDir, "search").apply { mkdirs() }
        File(dir, snapshotName)
    }.getOrNull()

    private fun writeSnapshot(key: String, items: List<MediaItem>) {
        val f = snapshotFile() ?: return
        runCatching {
            val arr = JSONArray()
            for (item in items.take(150)) arr.put(encodeItem(item))
            f.writeText(JSONObject().put("key", key).put("items", arr).toString())
        }
    }

    private fun readSnapshot(): Snapshot? {
        val f = snapshotFile() ?: return null
        if (!f.isFile) return null
        return runCatching {
            val o = JSONObject(f.readText())
            val key = o.optString("key")
            if (key.isBlank()) return null
            val arr = o.optJSONArray("items") ?: return null
            val items = (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { decodeItem(it) }
            }
            Snapshot(key, items)
        }.getOrNull()
    }

    private fun encodeItem(item: MediaItem): JSONObject =
        JSONObject()
            .put("pid", item.providerId)
            .put("id", item.id)
            .put("title", item.title)
            .put("type", item.type.name)
            .put("poster", item.posterUrl ?: "")
            .put("year", item.year ?: -1)
            .put("overview", item.overview ?: "")
            .put("genres", JSONArray(item.genres))
            .put("backdrop", item.backdropUrl ?: "")
            .put("rawType", item.rawType)
            .put("rating", item.rating ?: -1.0)
            .put("originalTitle", item.originalTitle)
            .put("nsfw", item.nsfw)
            .put("quality", item.quality ?: "")
            .put("nextDate", item.nextEpisodeDate ?: "")
            .put("metaSrc", item.metadataSource ?: "")

    private fun decodeItem(o: JSONObject): MediaItem? {
        val pid = o.optString("pid")
        val id = o.optString("id")
        if (pid.isBlank() || id.isBlank()) return null
        val genres = o.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        } ?: emptyList()
        return MediaItem(
            providerId = pid,
            id = id,
            title = o.optString("title"),
            type = runCatching { MediaType.valueOf(o.optString("type")) }
                .getOrDefault(MediaType.UNKNOWN),
            posterUrl = o.optString("poster").ifBlank { null },
            year = o.optInt("year", -1).takeIf { it > 0 },
            overview = o.optString("overview").ifBlank { null },
            genres = genres,
            backdropUrl = o.optString("backdrop").ifBlank { null },
            rawType = o.optString("rawType"),
            rating = o.optDouble("rating", -1.0).takeIf { it >= 0 },
            originalTitle = o.optString("originalTitle"),
            nsfw = o.optBoolean("nsfw", false),
            quality = o.optString("quality").ifBlank { null },
            nextEpisodeDate = o.optString("nextDate").ifBlank { null },
            metadataSource = o.optString("metaSrc").ifBlank { null },
        )
    }

    private fun MediaItem.tokenizePoster(): MediaItem {
        val p = PosterLoader.tokenize(posterUrl)
        val b = PosterLoader.tokenize(backdropUrl)
        return if (p == posterUrl && b == backdropUrl) this
        else copy(posterUrl = p, backdropUrl = b)
    }
}

/**
 * The Search tab scan owner (see SearchSessionScope): a singleton so
 * existing callers are untouched.
 */
object SearchSession : SearchSessionScope("search_session.json")

object HomeSearchSession : SearchSessionScope("home_search_session.json")
