package com.hikari.app.lnreader

import android.os.SystemClock
import com.hikari.app.data.CatalogRef
import com.hikari.app.data.Episode
import com.hikari.app.data.Logs
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.StreamSource
import com.hikari.app.manga.MangaChapter
import com.hikari.app.manga.MangaStore
import com.hikari.app.providers.ContentProvider
import com.hikari.app.providers.ProviderGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * One LNReader novel source, exposed to the rest of the app as an ordinary
 * [ContentProvider] of [com.hikari.app.data.ProviderType.MANGA].
 *
 * Everything Hikari already does with a manga provider therefore works for
 * novels too: the manga tab lists it (see MangaScreen's engine filter), the
 * detail screen opens it, chapters travel as [Episode]s cached in
 * [MangaStore], and chapter text travels as [StreamSource.text] parts — the
 * same shape the plain-Source novel extensions use, so the novel reader joins
 * them unchanged and the image reader (which skips blank urls) never sees
 * them.
 *
 * Mapping onto the plugin contract: a "catalog" is `popularNovels` (Latest via
 * `showLatestNovels`), search is `searchNovels`, meta + chapters are
 * `parseNovel` (plus `parsePage` for paginated chapter lists), and a chapter's
 * text is `parseChapter` HTML reduced to paragraphs.
 */
class LnReaderProvider(override val config: ProviderConfig) : ContentProvider {

    companion object {
        const val CATALOG_POPULAR = "popular"
        const val CATALOG_LATEST = "latest"
        val lastOutcome = ConcurrentHashMap<String, String>()

        private const val MAX_ITEMS = 120
        private const val MAX_EPISODES = 5000
        private const val MAX_PAGES = 50

        /** True for a provider id that came from the LNReader manager. */
        fun isLnReaderId(id: String): Boolean = id.startsWith(LnReaderPluginManager.PREFIX)
    }

    private val pluginFile: File get() = File(config.url)

    private val pluginId: String get() = config.id.removePrefix(LnReaderPluginManager.PREFIX)

    private fun missingReason(): String = when {
        !pluginFile.exists() -> "The source file is missing — reinstall it in Extensions"
        else -> "The source could not be loaded"
    }

    private fun <T> fail(msg: String, fallback: T): T {
        val short = "✗ " + msg.take(200)
        lastOutcome[config.id] = short
        Logs.log("LnReader", "${config.name}: $msg")
        return fallback
    }

    private fun reason(t: Throwable): String {
        var c: Throwable = t
        while (c.cause != null && c.cause !== c) c = c.cause!!
        val label = c.javaClass.simpleName.ifBlank { c.javaClass.name }
        val msg = c.message?.trim().orEmpty()
        return if (msg.isBlank()) label else "$label: $msg".take(200)
    }

    private suspend fun <T> gate(
        lane: ProviderGate.Lane = ProviderGate.Lane.INTERACTIVE,
        block: suspend () -> T,
    ): T = ProviderGate.withProvider(config.id, lane) {
        withContext(Dispatchers.IO) { block() }
    }

    private fun okData(payload: String): Any? {
        val o = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        if (!o.optBoolean("ok", false)) return null
        return o.opt("data")
    }

    private fun okError(payload: String): String? {
        val o = runCatching { JSONObject(payload) }.getOrNull() ?: return "unreadable answer"
        if (o.optBoolean("ok", false)) return null
        return o.optString("error").trim().ifBlank { "the source reported a failure" }
    }

    private fun novelItems(arr: JSONArray): List<MediaItem> {
        val out = ArrayList<MediaItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val path = o.optString("path").trim()
            if (path.isBlank()) continue
            val name = o.optString("name").trim().ifBlank { path }
            out += MediaItem(
                providerId = config.id,
                id = path,
                title = name,
                type = MediaType.SERIES,
                posterUrl = o.optString("cover").trim().ifBlank { null },
                rawType = "novel",
            )
            if (out.size >= MAX_ITEMS) break
        }
        return out
    }

    // ---- Catalogues ----

    override suspend fun catalogs(): List<CatalogRef> = gate {
        if (!pluginFile.exists()) return@gate emptyList()
        listOf(
            CatalogRef(config.id, MediaType.SERIES, CATALOG_POPULAR, "Popular", "novel"),
            CatalogRef(config.id, MediaType.SERIES, CATALOG_LATEST, "Latest", "novel"),
        )
    }

    override suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem> = gate {
        if (!pluginFile.exists()) return@gate fail(missingReason(), emptyList())
        val payload = runCatching {
            LnReaderRuntime.popularNovels(pluginFile, pluginId, page.coerceAtLeast(1), ref.id == CATALOG_LATEST)
        }.getOrNull()
        if (payload == null) return@gate fail("catalog failed: the source call was cancelled", emptyList())
        val data = okData(payload)
        if (data == null) return@gate fail("catalog failed: ${okError(payload)}", emptyList())
        val items = when (data) {
            is JSONArray -> novelItems(data)
            // Some sources wrap the list (`{novels: [...]}`) — take the first
            // array-shaped field instead of reporting an empty catalogue.
            is JSONObject -> listOfNotNull(
                data.optJSONArray("novels"),
                data.optJSONArray("list"),
                data.optJSONArray("results"),
                data.optJSONArray("data"),
                data.optJSONArray("items"),
            ).firstOrNull()?.let { novelItems(it) } ?: emptyList()
            else -> emptyList()
        }
        if (items.isEmpty()) {
            lastOutcome[config.id] = "✗ the source answered, but ${ref.name.lowercase()} came back with no titles (page $page)"
            return@gate emptyList()
        }
        lastOutcome[config.id] = "✓ ${items.size} title(s)"
        items
    }

    override suspend fun search(query: String, page: Int): List<MediaItem> = gate {
        if (query.isBlank()) return@gate emptyList()
        if (!pluginFile.exists()) return@gate fail(missingReason(), emptyList())
        val payload = runCatching {
            LnReaderRuntime.searchNovels(pluginFile, pluginId, query, page.coerceAtLeast(1))
        }.getOrNull()
        if (payload == null) return@gate fail("search failed: the source call was cancelled", emptyList())
        val data = okData(payload)
        if (data == null) return@gate fail("search failed: ${okError(payload)}", emptyList())
        val items = when (data) {
            is JSONArray -> novelItems(data)
            is JSONObject -> listOfNotNull(
                data.optJSONArray("novels"),
                data.optJSONArray("list"),
                data.optJSONArray("results"),
                data.optJSONArray("data"),
                data.optJSONArray("items"),
            ).firstOrNull()?.let { novelItems(it) } ?: emptyList()
            else -> emptyList()
        }
        lastOutcome[config.id] = "✓ ${items.size} result(s)"
        items
    }

    // ---- Details + chapters ----

    private data class NovelChapter(
        val name: String,
        val path: String,
        val number: Float,
        val released: String?,
    )

    private fun parseChapters(arr: JSONArray?): List<NovelChapter> {
        if (arr == null) return emptyList()
        val out = ArrayList<NovelChapter>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val path = o.optString("path").trim()
            if (path.isBlank()) continue
            out += NovelChapter(
                name = o.optString("name").trim(),
                path = path,
                number = o.optDouble("chapterNumber", Double.NaN).toFloat(),
                released = o.optString("releaseTime").trim().ifBlank { null },
            )
            if (out.size >= MAX_EPISODES) break
        }
        return out
    }

    private fun sortChapters(list: List<NovelChapter>): List<NovelChapter> {
        // LNReader chapter lists already run oldest-first; only re-order when
        // the source numbers them out of order (Mihon's rule).
        val numbered = list.filter { it.number.isFinite() && it.number > 0f }
        if (numbered.size >= 2 && list.zipWithNext().any { (a, b) ->
                a.number.isFinite() && b.number.isFinite() && a.number > b.number
            }
        ) {
            return list.sortedBy { if (it.number.isFinite() && it.number > 0f) it.number else Float.MAX_VALUE }
        }
        return list
    }

    override suspend fun getMeta(item: MediaItem): MediaItem = gate {
        if (!pluginFile.exists()) return@gate item
        try {
            val payload = LnReaderRuntime.parseNovel(pluginFile, pluginId, item.id)
            val data = okData(payload)
            if (data !is JSONObject) {
                Logs.log("LnReader", "${config.name}: details failed: ${okError(payload)}")
                return@gate item
            }
            lastOutcome[config.id] = "✓ details"
            item.copy(
                title = data.optString("name").trim().ifBlank { item.title },
                posterUrl = data.optString("cover").trim().ifBlank { null } ?: item.posterUrl,
                overview = data.optString("summary").trim().ifBlank { null } ?: item.overview,
                genres = runCatching {
                    val arr = data.optJSONArray("genres")
                    if (arr != null) {
                        (0 until arr.length()).mapNotNull {
                            arr.optString(it).trim().ifBlank { null }
                        }.ifEmpty { item.genres }
                    } else {
                        data.optString("genres").trim()
                            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
                            .ifEmpty { item.genres }
                    }
                }.getOrDefault(item.genres),
                rating = data.optDouble("rating", 0.0).takeIf { it > 0.0 } ?: item.rating,
            )
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Logs.log("LnReader", "${config.name}: details failed: ${reason(t)}")
            item
        }
    }

    override suspend fun getEpisodes(item: MediaItem): List<Episode>? = gate {
        if (!pluginFile.exists()) return@gate failEpisodes(missingReason())
        try {
            val payload = LnReaderRuntime.parseNovel(pluginFile, pluginId, item.id)
            val data = okData(payload)
            if (data !is JSONObject) return@gate failEpisodes(okError(payload) ?: "unknown error")
            val all = ArrayList(parseChapters(data.optJSONArray("chapters")))
            // Paginated chapter lists: first page rode along with parseNovel,
            // the rest come from parsePage (page numbers as strings).
            val totalPages = data.optInt("totalPages", 1).coerceIn(1, MAX_PAGES)
            for (n in 2..totalPages) {
                val pp = runCatching {
                    LnReaderRuntime.parsePage(pluginFile, pluginId, item.id, n.toString())
                }.getOrNull() ?: break
                val pd = okData(pp) as? JSONObject ?: break
                all += parseChapters(pd.optJSONArray("chapters"))
                if (all.size >= MAX_EPISODES) break
            }
            if (all.isEmpty()) return@gate failEpisodes("no chapters in this answer")
            val sorted = sortChapters(all.distinctBy { it.path })
            MangaStore.putChapters(
                item.providerId + "|" + item.id,
                sorted.map {
                    MangaChapter(
                        url = it.path,
                        name = it.name,
                        number = if (it.number.isFinite()) it.number else -1f,
                    )
                },
            )
            lastOutcome[config.id] = "✓ ${sorted.size} chapter(s)"
            sorted.mapIndexed { i, c ->
                Episode(
                    number = i + 1,
                    id = c.path,
                    name = c.name.ifBlank { "Chapter ${i + 1}" },
                    image = null,
                    season = 1,
                    released = c.released,
                )
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            failEpisodes(reason(t))
        }
    }

    private fun failEpisodes(msg: String): List<Episode>? {
        lastOutcome[config.id] = "✗ " + msg.take(200)
        Logs.log("LnReader", "${config.name}: $msg")
        return null
    }

    // ---- Chapter text ----

    /**
     * The chapter's HTML reduced to paragraphs. Block elements become
     * paragraph breaks (the novel reader splits on blank lines); script,
     * style and navigation nodes are dropped, never read aloud as text.
     */
    private fun htmlToText(html: String): String {
        val doc = runCatching { Jsoup.parseBodyFragment(html) }.getOrNull()
            ?: return html.trim().take(200_000)
        runCatching { doc.select("script, style, noscript, nav, header, footer").remove() }
        val body = doc.body() ?: return html.trim().take(200_000)
        val blocks = body.select("p, h1, h2, h3, h4, h5, h6, li, blockquote, div")
        val paras = if (blocks.isEmpty()) {
            listOf(body.text().trim())
        } else {
            // Nested divs double-report their children's text through .text()
            // on both levels — leaf blocks only.
            blocks.filter { el ->
                el.select("p, h1, h2, h3, h4, h5, h6, li, blockquote").isEmpty()
            }.map { it.text().trim() }.filter { it.isNotBlank() }
        }.filter { it.isNotBlank() }
        val text = paras.joinToString("\n\n").trim()
        if (text.isNotBlank()) return text.take(200_000)
        return body.text().trim().take(200_000)
    }

    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> =
        gate(ProviderGate.Lane.BACKGROUND) {
            if (!pluginFile.exists()) return@gate fail(missingReason(), emptyList())
            val chapter = episode ?: return@gate fail("No chapter selected", emptyList())
            val lt0 = SystemClock.elapsedRealtime()
            Logs.log("LnReader", "${config.name} novel chapter start id=${chapter.id.take(80)}")
            try {
                val payload = LnReaderRuntime.parseChapter(pluginFile, pluginId, chapter.id)
                Logs.log("LnReader", "${config.name} novel parseChapter id=${chapter.id.take(60)} in ${SystemClock.elapsedRealtime() - lt0}ms payloadChars=${payload.length}")
                val data = okData(payload)
                val html = when (data) {
                    is String -> data
                    is JSONObject -> data.optString("html").ifBlank { data.optString("content") }
                    else -> ""
                }.trim()
                if (html.isBlank()) return@gate fail(okError(payload) ?: "chapter text unavailable", emptyList())
                val text = htmlToText(html)
                if (text.isBlank()) return@gate fail("chapter text unavailable", emptyList())
                lastOutcome[config.id] = "✓ 1 part"
                Logs.log("LnReader", "${config.name} novel chapter OK id=${chapter.id.take(60)} htmlChars=${html.length} textChars=${text.length} in ${SystemClock.elapsedRealtime() - lt0}ms")
                listOf(
                    StreamSource(
                        name = chapter.name ?: "Chapter",
                        url = "",
                        text = text,
                        pageUrl = chapter.id,
                    )
                )
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                fail("chapter text failed: ${reason(t)}", emptyList())
            }
        }
}
