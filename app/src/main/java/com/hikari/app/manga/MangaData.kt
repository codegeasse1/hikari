package com.hikari.app.manga

import android.content.Context
import com.hikari.app.HikariApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A manga, as this app holds it. The manga engines hand back an `SManga` per
 * item, but a manga has to survive being written into a route, saved in the
 * reading library and shown on three screens, so it is converted once — here —
 * and every screen works from this.
 */
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

/**
 * Manga library + reading progress + the chapter list of every title opened.
 *
 * Deliberately NOT the video store: a manga is followed ("in my library"),
 * read chapter by chapter, and its chapter list must not be re-fetched from a
 * site every time the reader opens — none of which has a video equivalent. Three
 * small JSON files in `filesDir/manga/` keep it out of the DataStore preference
 * blob (which is read on every launch) and make it durable without a migration.
 */
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

    /**
     * Forgets where the reader was in EVERY title — the "Clear all" action on
     * the reading history.
     *
     * Only the reading POSITIONS go: the library (what is followed) and the
     * chapter lists stay exactly as they are, because "clear my reading history"
     * means the history, not the collection. The in-memory cache is emptied in
     * the same breath as the file so a screen reading the store right after this
     * call cannot resurrect a row the user just deleted.
     */
    fun clearAllProgress() {
        progressCache = HashMap()
        runCatching { progressFile(ctx()).writeText("[]") }
        ping()
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
        chapters[mangaKey] = list
        runCatching {
            val arr = JSONArray()
            list.forEach { arr.put(jsonOf(it)) }
            chaptersFile(ctx(), mangaKey).writeText(arr.toString())
        }
        ping()
    }

    fun dropChapters(mangaKey: String) {
        chapters.remove(mangaKey)
        runCatching { chaptersFile(ctx(), mangaKey).delete() }
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

/**
 * The `SManga.status` integer as a word. The numbering is the extension API's
 * own (0 unknown, 1 ongoing, 2 completed, 3 licensed, 4 publishing finished,
 * 5 cancelled, 6 on hiatus) and it is what every manga site's status maps to.
 */
fun mangaStatusLabel(status: Int): String? = when (status) {
    1 -> "Ongoing"
    2 -> "Completed"
    3 -> "Licensed"
    4 -> "Publishing finished"
    5 -> "Cancelled"
    6 -> "On hiatus"
    else -> null
}

/**
 * How the reader advances through a chapter.
 *
 *  * [PAGED_LTR] — one page per screen, swiped/arrowed left to right. What a
 *    western comic and a translated manhwa want.
 *  * [PAGED_RTL] — the same, mirrored: swiping right shows the NEXT page, and
 *    the page slider still reads left-to-right. What Japanese manga wants, and
 *    the only one of the three that looks wrong when it is not offered.
 *  * [WEBTOON] — one continuous vertical strip, which is how Korean webtoons and
 *    a lot of long-strip releases are drawn (separate page images that are only
 *    correct when butted together with no gap and no page break).
 *
 * [WEBTOON] is the DEFAULT — the user's own request ("make webtoon mode as
 * default reader mode"). Manhwa and webtoon releases are what almost every
 * installed manga extension carries, and a released chapter of one is a single
 * vertical strip: opening it one page at a time shows a tall page cut at the
 * knee and makes the reader swipe four times per panel. The two paged modes are
 * still one tap away in the reader's settings (and stick once chosen — see
 * [com.hikari.app.data.AppStore.mangaReadModeFlow]).
 */
object MangaReadMode {
    const val PAGED_LTR = "ltr"
    const val PAGED_RTL = "rtl"
    const val WEBTOON = "webtoon"

    val ALL = listOf(PAGED_LTR, PAGED_RTL, WEBTOON)

    fun normalize(raw: String?): String =
        ALL.firstOrNull { it.equals(raw, ignoreCase = true) } ?: WEBTOON
}

/**
 * How a page is fitted to the screen in paged modes.
 *
 *  * [WIDTH] — fill the width, scroll/pan vertically for the rest: the default,
 *    and what a tall page wants on a phone.
 *  * [HEIGHT] — the whole page visible, letterboxed: what a double-page spread
 *    wants, and the only mode that needs no scrolling at all.
 *  * [WHOLE] — fit whichever axis is tighter so nothing is ever off-screen (what
 *    a television uses).
 */
object MangaFit {
    const val WIDTH = "width"
    const val HEIGHT = "height"
    const val WHOLE = "whole"

    val ALL = listOf(WIDTH, HEIGHT, WHOLE)

    fun normalize(raw: String?): String =
        ALL.firstOrNull { it.equals(raw, ignoreCase = true) } ?: WIDTH
}
