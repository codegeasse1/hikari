package com.hikari.app.data

import com.hikari.app.HikariApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The best video quality Hikari KNOWS a title comes in, so a poster can badge it
 * (see [com.hikari.app.ui.PosterStyle.showQuality]).
 *
 * The app is a client for other people's sites: it has no quality metadata of
 * its own, and inventing one would put a lie on the poster. So the label is only
 * ever read from something the app really saw, cheapest source first:
 *
 *  1. the item's own text — extension and repo rows routinely name a title
 *     "Movie (2024) 1080p WEB-DL", which is the site's own answer;
 *  2. the SERVERS found the last time the title was opened ([remember] — the
 *     player and the detail page hand every completed source search over), whose
 *     names carry the quality the sites serve ("HdHub 4K", "NetMirror 720p").
 *
 * A title whose quality the app has never seen gets NO badge — the badge is
 * opt-in (Settings → Poster styling → Quality badge, off by default) precisely
 * because it cannot appear everywhere.
 *
 * Storage mirrors [Ratings]: one small JSON map in the app's files dir, loaded
 * once on first read and saved on every improvement. [revision] is bumped when a
 * new label lands so the poster cells showing that title repaint.
 */
object TitleQuality {

    private val memory = ConcurrentHashMap<String, String>()

    @Volatile
    private var loaded = false

    private val _revision = MutableStateFlow(0)

    /** Increments whenever a title's label appears or improves. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /**
     * The labels the badge can print, BEST FIRST — the order is the ranking, so
     * the first pattern that matches a given text is also the best answer that
     * text contains, and the best of several servers is the one with the lowest
     * index. A resolution always beats a release tag (a "1080p WEB-DL" is a
     * 1080p print), which is why the tags sit at the end.
     */
    private val RANKED: List<Pair<String, Regex>> = listOf(
        "4K" to Regex("""(^|[^a-z0-9])(4k|2160p|uhd)([^a-z0-9]|$)""", RegexOption.IGNORE_CASE),
        "1080p" to Regex("""(^|[^0-9])1080p""", RegexOption.IGNORE_CASE),
        "720p" to Regex("""(^|[^0-9])720p""", RegexOption.IGNORE_CASE),
        "480p" to Regex("""(^|[^0-9])480p""", RegexOption.IGNORE_CASE),
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

    /** What the poster should print for [item]: the best quality seen for the
     *  title, or the one its own name states. Null when there is nothing to
     *  say. */
    fun forItem(item: MediaItem): String? {
        ensureLoaded()
        memory[keyOf(item)]?.let { return it }
        return fromText(
            listOfNotNull(item.title, item.originalTitle, item.year?.toString())
                .joinToString(" ")
        )
    }

    /**
     * Files the best quality among [streams] for [item]. Called by every source
     * search that completes (the detail page and the player), so the badge is
     * there the next time the poster is drawn. A weaker label never overwrites a
     * stronger one: the sites' catalogue changes slowly, and a server list from a
     * bad day must not demote a title's badge.
     */
    fun remember(item: MediaItem, streams: List<StreamSource>) {
        ensureLoaded()
        val best = bestOf(streams) ?: return
        val key = keyOf(item)
        memory[key]?.let { if (rankOf(it) <= rankOf(best)) return }
        memory[key] = best
        save()
        _revision.value++
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
                    obj.optString(k).takeIf { it.isNotBlank() }?.let { memory[k] = it }
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
