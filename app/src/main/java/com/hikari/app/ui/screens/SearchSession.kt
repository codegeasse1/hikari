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
            // Warm the image pipes before the first tile composes: the Coil
            // client resolves DNS over HTTPS, so the very first poster batch
            // pays a full DoH round trip + TLS before a single byte loads —
            // exactly the "blank on the first searches" window.
            runCatching {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    com.hikari.app.net.DohDns.lookup("image.tmdb.org")
                }
            }
            var accum = if (merge) _results.value else emptyList<MediaItem>()
            var lastPub = 0L
            try {
                repo.searchStreaming(query, providerIds = providersOf(k)).collect { raw ->
                    // A superseding search started: stop feeding a dead key.
                    if (currentKey != k) return@collect
                    val page = raw.map { it.tokenizePoster() }
                    accum = if (merge) mergeDistinct(accum, page) else page
                    val now = android.os.SystemClock.uptimeMillis()
                    // The stream emits every ~120ms; republishing the whole
                    // list that often cancels in-flight Coil loads faster
                    // than they complete (first contact is DoH + TLS + burst
                    // queue). Publish at most ~3x/sec; the finally below
                    // flushes whatever is left, so nothing is ever lost.
                    if (now - lastPub >= 350L) {
                        lastPub = now
                        _results.value = accum
                        if (now - lastDiskWrite > 2_000L) {
                            lastDiskWrite = now
                            writeSnapshot(k, accum)
                        }
                    }
                }
                if (currentKey == k) {
                    SearchResultsCache.put(k, accum)
                    writeSnapshot(k, accum)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Keep whatever pages already landed; the finally flushes.
            } finally {
                if (currentKey == k) {
                    _results.value = accum
                    // The scan is settled: give every tile that exhausted its
                    // retries while the pipes were still warming up one more
                    // round (see PosterLoader.retryEpoch).
                    PosterLoader.bumpRetryEpoch()
                    _searching.value = false
                }
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
