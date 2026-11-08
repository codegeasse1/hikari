package com.hikari.app.manga

import android.content.Context
import com.hikari.app.HikariApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class MangaRecord(
    val providerId: String,
    val providerName: String,
    /** The source's own url for the title (the id every source call needs). */
    val url: String,
    val title: String,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val status: String? = null,
    val posterUrl: String? = null,
) {
    /** One stable key for the library, the progress map and the history list. */
    val key: String get() = "$providerId|$url"
}

/** One chapter of a manga, as the source published it. */
data class MangaChapter(
    val url: String,
    val name: String,
    val number: Float = -1f,
    val dateUpload: Long = 0L,
    val scanlator: String? = null,
) {
    /** "Chapter 12", "Vol. 3 Ch. 12", or the source's own name when it has none. */
    val label: String get() = name.ifBlank { if (number >= 0f) "Chapter $number" else "Chapter" }
}

data class NovelHighlight(
    val mangaKey: String,
    val chapterUrl: String,
    val paraIndex: Int,
    /** ARGB color int. */
    val color: Int,
    val start: Int = 0,
    val end: Int = -1,
) {
    companion object {
        /** The palette the reader offers. */
        val COLORS = listOf(
            0xFFFFFF00.toInt(), // yellow
            0xFF4CAF50.toInt(), // green
            0xFFE91E63.toInt(), // pink
            0xFF2196F3.toInt(), // blue
            0xFFFF9800.toInt(), // orange
            0xFF9C27B6.toInt(), // purple
            0xFF00BCD4.toInt(), // cyan
            0xFFCDDC39.toInt(), // lime
            0xFF009688.toInt(), // teal
            0xFFFF5722.toInt(), // deep orange
            0xFF3F51B5.toInt(), // indigo
            0xFFF44336.toInt(), // red
        )
    }
}

/** One chapter's read mark: opened vs read to the last page. */
data class MangaChapterRead(
    val chapterUrl: String,
    val finished: Boolean,
    val at: Long,
)

/** Manga-level mark derived from its chapters' marks. */
enum class MangaReadState { NONE, READING, COMPLETED }

/** Where the reader left off in one manga. */
data class MangaProgress(
    val mangaKey: String,
    val providerId: String,
    val providerName: String,
    val mangaUrl: String,
    val title: String,
    val posterUrl: String?,
    val chapterUrl: String,
    val chapterName: String,
    val page: Int,
    val pages: Int,
    val at: Long,
) {
    /** "12 / 40", for the continue-reading rows. */
    val pageLabel: String get() = if (pages > 0) "${page + 1} / $pages" else ""
}

object MangaStore {

    /** The followed titles, newest first. */
    @Volatile
    private var libraryCache: List<MangaRecord>? = null

    /** Reading progress per manga key. */
    @Volatile
    private var progressCache: MutableMap<String, MangaProgress>? = null

    /** Chapter lists, per manga key (memory only — the file is the durable copy). */
    private val chapters = java.util.concurrent.ConcurrentHashMap<String, List<MangaChapter>>()

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** Every change (follow, progress, chapters) pings this, so the screens that
     *  show manga rows redraw without a poll. */
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun ping() { listeners.forEach { runCatching { it() } } }

    private fun dir(context: Context): File = File(context.filesDir, "manga").apply { mkdirs() }
    private fun libraryFile(context: Context) = File(dir(context), "library.json")
    private fun progressFile(context: Context) = File(dir(context), "progress.json")
    private fun chaptersFile(context: Context, key: String) =
        File(dir(context), "chapters-" + key.hashCode().toUInt().toString(16) + ".json")

    private fun ctx(): Context = HikariApp.instance.applicationContext

    // ---- Library ----

    fun library(): List<MangaRecord> {
        libraryCache?.let { return it }
        val list = runCatching {
            val f = libraryFile(ctx())
            if (!f.exists()) return@runCatching emptyList()
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                runCatching { recordOf(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
        libraryCache = list
        return list
    }

    fun isFollowed(key: String): Boolean = library().any { it.key == key }

    fun toggleFollow(record: MangaRecord): Boolean {
        val now = library().toMutableList()
        val had = now.any { it.key == record.key }
        if (had) now.removeAll { it.key == record.key } else now.add(0, record)
        libraryCache = now
        saveLibrary(now)
        ping()
        return !had
    }

    private fun saveLibrary(list: List<MangaRecord>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { arr.put(jsonOf(it)) }
            libraryFile(ctx()).writeText(arr.toString())
        }
    }

    // ---- Reading progress ----

    fun progress(): List<MangaProgress> = progressMap().values.sortedByDescending { it.at }

    fun progressFor(mangaKey: String): MangaProgress? = progressMap()[mangaKey]

    fun setProgress(p: MangaProgress) {
        val map = progressMap()
        map[p.mangaKey] = p
        progressCache = map
        runCatching {
            val arr = JSONArray()
            map.values.sortedByDescending { it.at }.forEach { arr.put(jsonOf(it)) }
            progressFile(ctx()).writeText(arr.toString())
        }
        ping()
    }

    fun clearProgress(mangaKey: String) {
        val map = progressMap()
        map.remove(mangaKey)
        progressCache = map
        runCatching {
            val arr = JSONArray()
            map.values.forEach { arr.put(jsonOf(it)) }
            progressFile(ctx()).writeText(arr.toString())
        }
        ping()
    }

    fun clearAllProgress() {
        progressCache = HashMap()
        runCatching { progressFile(ctx()).writeText("[]") }
        ping()
    }

    // ---- Chapter read marks (Reading / Completed) ----

    @Volatile
    private var chapterReadCache: MutableMap<String, MutableMap<String, MangaChapterRead>>? = null

    private fun chapterReadFile(context: Context) = File(dir(context), "chapters-read.json")

    fun chapterReadState(mangaKey: String, chapterUrl: String): MangaChapterRead? =
        chapterReadMap()[mangaKey]?.get(chapterUrl)

    fun markChapterStarted(mangaKey: String, chapterUrl: String) {
        if (mangaKey.isBlank() || chapterUrl.isBlank()) return
        val map = chapterReadMap()
        val per = map.getOrPut(mangaKey) { HashMap() }
        if (per[chapterUrl]?.finished == true) return
        per[chapterUrl] = MangaChapterRead(chapterUrl, finished = false, at = System.currentTimeMillis())
        chapterReadCache = map
        saveChapterRead(map)
        ping()
    }

    fun markChapterFinished(mangaKey: String, chapterUrl: String) {
        if (mangaKey.isBlank() || chapterUrl.isBlank()) return
        val map = chapterReadMap()
        val per = map.getOrPut(mangaKey) { HashMap() }
        per[chapterUrl] = MangaChapterRead(chapterUrl, finished = true, at = System.currentTimeMillis())
        chapterReadCache = map
        saveChapterRead(map)
        ping()
    }

    fun catalogReadState(mangaKey: String): MangaReadState {
        if (mangaKey.isBlank()) return MangaReadState.NONE
        val per = chapterReadMap()[mangaKey] ?: return MangaReadState.NONE
        if (per.isEmpty()) return MangaReadState.NONE
        return if (per.values.any { !it.finished }) MangaReadState.READING else MangaReadState.COMPLETED
    }

    /** Manga-level mark from its chapters: none started, some in progress, or all finished. */
    fun mangaReadState(mangaKey: String, chapterUrls: List<String>): MangaReadState {
        val per = chapterReadMap()[mangaKey] ?: return MangaReadState.NONE
        if (chapterUrls.isEmpty()) return MangaReadState.NONE
        var anyStarted = false
        for (u in chapterUrls) {
            val s = per[u] ?: return MangaReadState.READING.takeIf { anyStarted } ?: MangaReadState.NONE
            if (!s.finished) return MangaReadState.READING
            anyStarted = true
        }
        return if (anyStarted) MangaReadState.COMPLETED else MangaReadState.NONE
    }

    private fun chapterReadMap(): MutableMap<String, MutableMap<String, MangaChapterRead>> {
        chapterReadCache?.let { return it }
        val map = HashMap<String, MutableMap<String, MangaChapterRead>>()
        runCatching {
            val f = chapterReadFile(ctx())
            if (!f.exists()) return@runCatching
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                runCatching {
                    val o = arr.getJSONObject(i)
                    val k = o.optString("mangaKey")
                    val u = o.optString("chapterUrl")
                    if (k.isBlank() || u.isBlank()) return@runCatching
                    map.getOrPut(k) { HashMap() }[u] = MangaChapterRead(
                        chapterUrl = u,
                        finished = o.optBoolean("finished", false),
                        at = o.optLong("at"),
                    )
                }
            }
        }
        chapterReadCache = map
        return map
    }

    private fun saveChapterRead(map: Map<String, Map<String, MangaChapterRead>>) {
        runCatching {
            val arr = JSONArray()
            for ((k, per) in map) {
                for ((_, s) in per) {
                    arr.put(
                        JSONObject().apply {
                            put("mangaKey", k)
                            put("chapterUrl", s.chapterUrl)
                            put("finished", s.finished)
                            put("at", s.at)
                        }
                    )
                }
            }
            chapterReadFile(ctx()).writeText(arr.toString())
        }
    }

    private fun progressMap(): MutableMap<String, MangaProgress> {
        progressCache?.let { return it }
        val map = HashMap<String, MangaProgress>()
        runCatching {
            val f = progressFile(ctx())
            if (!f.exists()) return@runCatching
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                runCatching {
                    val o = arr.getJSONObject(i)
                    val p = progressOf(o)
                    map[p.mangaKey] = p
                }
            }
        }
        progressCache = map
        return map
    }

    // ---- Chapter lists ----

    fun chaptersFor(mangaKey: String): List<MangaChapter>? {
        chapters[mangaKey]?.let { return it }
        val list = runCatching {
            val f = chaptersFile(ctx(), mangaKey)
            if (!f.exists()) return@runCatching null
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                runCatching { chapterOf(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrNull()
        if (list != null) chapters[mangaKey] = list
        return list
    }

    fun putChapters(mangaKey: String, list: List<MangaChapter>) {
        val clean = list.distinctBy { it.url }
        chapters[mangaKey] = clean
        runCatching {
            val arr = JSONArray()
            clean.forEach { arr.put(jsonOf(it)) }
            chaptersFile(ctx(), mangaKey).writeText(arr.toString())
        }
        ping()
    }

    fun dropChapters(mangaKey: String) {
        chapters.remove(mangaKey)
        runCatching { chaptersFile(ctx(), mangaKey).delete() }
    }

    // ---- Novel highlights ----

    /** Range highlights in the novel reader, per novel key. Kept in one
     *  small JSON file per novel (same pattern as the chapter lists), so they
     *  survive restarts and are never mixed into the progress file. */
    @Volatile
    private var highlightsCache: MutableMap<String, MutableList<NovelHighlight>>? = null

    private fun highlightsFile(context: Context, key: String) =
        File(dir(context), "highlights-" + key.hashCode().toUInt().toString(16) + ".json")

    fun highlightsFor(mangaKey: String): List<NovelHighlight> =
        highlightsMap()[mangaKey]?.toList().orEmpty()

    fun setHighlight(mangaKey: String, h: NovelHighlight) {
        val map = highlightsMap()
        val list = map.getOrPut(mangaKey) { mutableListOf() }
        list.removeAll {
            it.chapterUrl == h.chapterUrl && it.paraIndex == h.paraIndex &&
                it.start == h.start && it.end == h.end
        }
        list.add(h)
        highlightsCache = map
        saveHighlights(mangaKey, list)
        ping()
    }

    fun clearHighlight(mangaKey: String, chapterUrl: String, paraIndex: Int) {
        val map = highlightsMap()
        val list = map[mangaKey] ?: return
        if (list.removeAll { it.chapterUrl == chapterUrl && it.paraIndex == paraIndex }) {
            highlightsCache = map
            saveHighlights(mangaKey, list)
            ping()
        }
    }

    /** Removes one exact highlighted range (the reader's tap-toggle-off). */
    fun clearHighlightExact(mangaKey: String, chapterUrl: String, paraIndex: Int, start: Int, end: Int) {
        val map = highlightsMap()
        val list = map[mangaKey] ?: return
        if (list.removeAll {
                it.chapterUrl == chapterUrl && it.paraIndex == paraIndex &&
                    it.start == start && it.end == end
            }
        ) {
            highlightsCache = map
            saveHighlights(mangaKey, list)
            ping()
        }
    }

    private fun highlightsMap(): MutableMap<String, MutableList<NovelHighlight>> {
        highlightsCache?.let { return it }
        val map = HashMap<String, MutableList<NovelHighlight>>()
        runCatching {
            val d = dir(ctx())
            d.listFiles { f -> f.name.startsWith("highlights-") && f.name.endsWith(".json") }
                .orEmpty()
                .forEach { f ->
                    runCatching {
                        val arr = JSONArray(f.readText())
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            val h = NovelHighlight(
                                mangaKey = o.optString("mangaKey"),
                                chapterUrl = o.optString("chapterUrl"),
                                paraIndex = o.optInt("paraIndex", -1),
                                color = o.optInt("color", 0xFFFFFF00.toInt()),
                                start = o.optInt("start", 0),
                                end = o.optInt("end", -1),
                            )
                            if (h.mangaKey.isNotBlank() && h.paraIndex >= 0) {
                                map.getOrPut(h.mangaKey) { mutableListOf() }.add(h)
                            }
                        }
                    }
                }
        }
        highlightsCache = map
        return map
    }

    private fun saveHighlights(mangaKey: String, list: List<NovelHighlight>) {
        runCatching {
            val arr = JSONArray()
            list.forEach {
                arr.put(
                    JSONObject().apply {
                        put("mangaKey", it.mangaKey)
                        put("chapterUrl", it.chapterUrl)
                        put("paraIndex", it.paraIndex)
                        put("color", it.color)
                        put("start", it.start)
                        put("end", it.end)
                    }
                )
            }
            highlightsFile(ctx(), mangaKey).writeText(arr.toString())
        }
    }

    // ---- JSON ----

    private fun jsonOf(r: MangaRecord): JSONObject = JSONObject().apply {
        put("providerId", r.providerId)
        put("providerName", r.providerName)
        put("url", r.url)
        put("title", r.title)
        put("author", r.author)
        put("artist", r.artist)
        put("description", r.description)
        put("status", r.status)
        put("posterUrl", r.posterUrl)
        put("genres", JSONArray(r.genres))
    }

    private fun recordOf(o: JSONObject): MangaRecord = MangaRecord(
        providerId = o.optString("providerId"),
        providerName = o.optString("providerName"),
        url = o.optString("url"),
        title = o.optString("title"),
        author = o.optString("author").ifBlank { null },
        artist = o.optString("artist").ifBlank { null },
        description = o.optString("description").ifBlank { null },
        status = o.optString("status").ifBlank { null },
        posterUrl = o.optString("posterUrl").ifBlank { null },
        genres = o.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.optString(it) } }
            ?: emptyList(),
    )

    private fun jsonOf(c: MangaChapter): JSONObject = JSONObject().apply {
        put("url", c.url)
        put("name", c.name)
        put("number", c.number.toDouble())
        put("dateUpload", c.dateUpload)
        put("scanlator", c.scanlator)
    }

    private fun chapterOf(o: JSONObject): MangaChapter = MangaChapter(
        url = o.optString("url"),
        name = o.optString("name"),
        number = o.optDouble("number", -1.0).toFloat(),
        dateUpload = o.optLong("dateUpload"),
        scanlator = o.optString("scanlator").ifBlank { null },
    )

    private fun jsonOf(p: MangaProgress): JSONObject = JSONObject().apply {
        put("mangaKey", p.mangaKey)
        put("providerId", p.providerId)
        put("providerName", p.providerName)
        put("mangaUrl", p.mangaUrl)
        put("title", p.title)
        put("posterUrl", p.posterUrl)
        put("chapterUrl", p.chapterUrl)
        put("chapterName", p.chapterName)
        put("page", p.page)
        put("pages", p.pages)
        put("at", p.at)
    }

    private fun progressOf(o: JSONObject): MangaProgress = MangaProgress(
        mangaKey = o.optString("mangaKey"),
        providerId = o.optString("providerId"),
        providerName = o.optString("providerName"),
        mangaUrl = o.optString("mangaUrl"),
        title = o.optString("title"),
        posterUrl = o.optString("posterUrl").ifBlank { null },
        chapterUrl = o.optString("chapterUrl"),
        chapterName = o.optString("chapterName"),
        page = o.optInt("page"),
        pages = o.optInt("pages"),
        at = o.optLong("at"),
    )
}

fun mangaStatusLabel(status: Int): String? = when (status) {
    1 -> "Ongoing"
    2 -> "Completed"
    3 -> "Licensed"
    4 -> "Publishing finished"
    5 -> "Cancelled"
    6 -> "On hiatus"
    else -> null
}

object MangaReadMode {
    const val PAGED_LTR = "ltr"
    const val PAGED_RTL = "rtl"
    const val WEBTOON = "webtoon"

    val ALL = listOf(PAGED_LTR, PAGED_RTL, WEBTOON)

    fun normalize(raw: String?): String =
        ALL.firstOrNull { it.equals(raw, ignoreCase = true) } ?: WEBTOON
}

object MangaFit {
    const val WIDTH = "width"
    const val HEIGHT = "height"
    const val WHOLE = "whole"

    val ALL = listOf(WIDTH, HEIGHT, WHOLE)

    fun normalize(raw: String?): String =
        ALL.firstOrNull { it.equals(raw, ignoreCase = true) } ?: WIDTH
}
