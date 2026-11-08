package com.hikari.app.data

import com.hikari.app.HikariApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object TitleQuality {

    private val memory = ConcurrentHashMap<String, String>()

    private val byTitle = ConcurrentHashMap<String, String>()

    @Volatile
    private var loaded = false

    /** The scope the one-time disk read and every write run on, so neither ever
     *  happens on the caller's thread — [forItem] is called from COMPOSITION (a
     *  poster cell reading its badge) and [remember] from the player's own
     *  main-scoped coroutines, and file I/O belongs on neither. */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadStarted = AtomicBoolean(false)

    /** Guards the load-then-write pair. `save()` writes the WHOLE map, so two
     *  unguarded writers could interleave inside the same file; holding one lock
     *  across load+mutate+write makes the last write the complete truth. */
    private val lock = Any()

    private val _revision = MutableStateFlow(0)

    /** Increments whenever a title's label appears or improves. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val RANKED: List<Pair<String, Regex>> = listOf(
        "4K" to Regex("""(^|[^a-z0-9])(4k|2160p|uhd)([^a-z0-9]|$)""", RegexOption.IGNORE_CASE),
        "1080p" to Regex("""(^|[^0-9])1080p""", RegexOption.IGNORE_CASE),
        "720p" to Regex("""(^|[^0-9])720p""", RegexOption.IGNORE_CASE),
        "480p" to Regex("""(^|[^0-9])480p""", RegexOption.IGNORE_CASE),
        "DVD" to Regex("""dvd-?(rip|scr)""", RegexOption.IGNORE_CASE),
        "360p" to Regex("""(^|[^0-9])360p""", RegexOption.IGNORE_CASE),
        "HDR" to Regex("""(^|[^a-z0-9])hdr(10\+?)?([^a-z0-9]|$)""", RegexOption.IGNORE_CASE),
        "Blu-ray" to Regex("""blu-?ray""", RegexOption.IGNORE_CASE),
        "Web" to Regex("""(^|[^a-z0-9])web-?(dl|rip)([^a-z0-9]|$)""", RegexOption.IGNORE_CASE),
        "HD" to Regex("""(^|[^a-z0-9])hd([^a-z0-9]|$)""", RegexOption.IGNORE_CASE),
        "CAM" to Regex("""(^|[^a-z0-9])(hd)?cam([^a-z0-9]|$)|predvd""", RegexOption.IGNORE_CASE),
    )

    /** Where a label sits in [RANKED] (lower is better); [UNKNOWN] for a label
     *  this build no longer knows. */
    private fun rankOf(label: String): Int =
        RANKED.indexOfFirst { it.first == label }.let { if (it < 0) Int.MAX_VALUE else it }

    /** The best quality label [text] mentions, or null when it mentions none. */
    fun fromText(text: String?): String? {
        if (text.isNullOrBlank()) return null
        for ((label, pattern) in RANKED) {
            if (pattern.containsMatchIn(text)) return label
        }
        return null
    }

    fun fromExtensionQuality(name: String?): String? = when (name) {
        "FourK", "UHD", "UltraHD" -> "4K"
        "HDR" -> "HDR"
        "BlueRay", "BluRay" -> "Blu-ray"
        "WebRip", "Web" -> "Web"
        "DVD" -> "DVD"
        "Cam", "CamRip", "HdCam", "Telesync", "WorkPrint", "Telecine" -> "CAM"
        else -> null
    }

    /** The best label among a set of server names, or null when none of them
     *  says anything about quality. */
    fun bestOf(streams: Iterable<StreamSource>): String? =
        streams.mapNotNull { fromText(it.name) }.minByOrNull { rankOf(it) }

    /** The key a title's label is filed under: the name it is known by plus its
     *  year, so the same film seen from two extensions shares one badge and two
     *  different titles that share a name do not. */
    private fun keyOf(item: MediaItem): String {
        val title = item.searchTitle.ifBlank { item.title }.trim().lowercase()
        return title + "|" + (item.year ?: 0)
    }

    /** The title-only keys a lookup may fall back to: the display name and the
     *  original name, both lowercased and trimmed — whether an item carries both
     *  depends on which provider's row it came from. */
    private fun titleKeysOf(item: MediaItem): List<String> =
        listOf(item.title, item.originalTitle)
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()

    /** Starts the one-time background load of the persisted map. Does no I/O
     *  itself, so it is safe — and cheap — to call from composition, which is
     *  what [forItem] does on every poster cell. */
    fun warm() {
        if (!loadStarted.compareAndSet(false, true)) return
        io.launch { synchronized(lock) { ensureLoaded() } }
    }

    fun forItem(item: MediaItem): String? {
        warm()
        item.quality?.takeIf { it.isNotBlank() }?.let { return it }
        memory[keyOf(item)]?.let { return it }
        titleKeysOf(item).firstNotNullOfOrNull { byTitle[it] }?.let { return it }
        return fromText(
            listOfNotNull(item.title, item.originalTitle, item.year?.toString())
                .joinToString(" ")
        )
    }

    fun remember(item: MediaItem, streams: List<StreamSource>) {
        val best = bestOf(streams) ?: return
        val key = keyOf(item)
        val titleKeys = titleKeysOf(item)
        io.launch {
            val improved = synchronized(lock) {
                ensureLoaded()
                var better = false
                if (memory[key]?.let { rankOf(it) <= rankOf(best) } != true) {
                    memory[key] = best
                    better = true
                }
                for (t in titleKeys) {
                    if (byTitle[t]?.let { rankOf(it) <= rankOf(best) } != true) {
                        byTitle[t] = best
                        better = true
                    }
                }
                if (better) save()
                better
            }
            if (improved) _revision.value++
        }
    }

    // ---- one small JSON map in the files dir (see [Ratings] for the pattern) ----

    private val cacheFile: File
        get() = File(HikariApp.instance.filesDir, "known-quality.json")

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            runCatching {
                val f = cacheFile
                if (!f.exists()) return@runCatching
                val obj = JSONObject(f.readText())
                for (k in obj.keys()) {
                    obj.optString(k).takeIf { it.isNotBlank() }?.let { label ->
                        memory[k] = label
                        k.substringBeforeLast('|').takeIf { it.isNotBlank() }?.let { t ->
                            if (byTitle[t]?.let { rankOf(it) <= rankOf(label) } != true) {
                                byTitle[t] = label
                            }
                        }
                    }
                }
            }
        }
    }

    private fun save() {
        runCatching {
            val obj = JSONObject()
            var kept = 0
            for ((k, v) in memory) {
                if (kept >= 2_000) break
                obj.put(k, v)
                kept++
            }
            val f = cacheFile
            f.parentFile?.mkdirs()
            f.writeText(obj.toString())
        }
    }
}
