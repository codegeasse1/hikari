package com.hikari.app.sora

import com.hikari.app.data.CatalogRef
import com.hikari.app.data.Episode
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.StreamSource
import com.hikari.app.manga.MangaProvider
import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A Sora manga/novel module behind the manga tab's engine contract.
 *
 * Sora manga modules speak the kanzen mangas shape
 * (`searchResults` / `extractDetails` / `extractChapters` / `extractImages`,
 * pages as direct image URLs) and novel modules the novels shape
 * (`searchResults` / `extractDetails` / `extractChapters` / `extractText`,
 * chapter HTML) — see [SoraRuntime]. This provider maps that onto chapters
 * and pages/text so installed Sora manga modules browse, search and read
 * exactly like Aniyomi/LnReader engines:
 *
 *   search()      <- searchResults(keyword)
 *   getMeta()     <- extractDetails(url)        -> {description, tags}
 *   getEpisodes() <- extractChapters(url)       -> kanzen {en: [...]} / [{title, href, number}]
 *   manga pages   <- extractImages(chapterUrl)  -> [imageUrl, ...]
 *   novel text    <- extractText(chapterUrl)     -> HTML
 *
 * Novel-kind rows serve one text part per chapter (the same StreamSource
 * shape [MangaProvider] serves novel rows: blank url + text), so the novel
 * reader opens them without knowing the engine.
 */
class SoraMangaProvider(override val config: ProviderConfig) : MangaProvider(config) {

    companion object {
        const val MANGA_PREFIX = "sora-manga|"
        const val NOVEL_PREFIX = "sora-novel|"

        fun isSoraMangaId(id: String): Boolean = id.startsWith(MANGA_PREFIX)
        fun isSoraNovelId(id: String): Boolean = id.startsWith(NOVEL_PREFIX)
        fun isSoraMangaProviderId(id: String): Boolean = isSoraMangaId(id) || isSoraNovelId(id)
    }

    val isSoraNovel: Boolean get() = config.id.startsWith(NOVEL_PREFIX)

    private val moduleFile: File get() = File(config.url)

    private fun manifestBase(): String? = runCatching {
        val mf = File(moduleFile.parentFile, "manifest.json")
        if (!mf.isFile) return null
        val o = JSONObject(mf.readText())
        o.optString("baseUrl").trim()
            .ifBlank { o.optString("searchBaseUrl").trim() }
            .ifBlank { null }
    }.getOrNull()

    private fun pageHeaders(): Map<String, String> {
        val base = manifestBase()
        val origin = runCatching {
            val u = android.net.Uri.parse(base ?: return@runCatching null)
            if (u.scheme.isNullOrBlank() || u.host.isNullOrBlank()) null
            else u.scheme + "://" + u.host
        }.getOrNull()
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = Http.UA
        if (origin != null) {
            h["Referer"] = origin + "/"
            h["Origin"] = origin
        }
        return h
    }

    private fun dataOf(payload: String): Any? {
        val o = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        if (!o.optBoolean("ok", false)) return null
        return o.opt("data")
    }

    /** Modules disagree on envelope: raw array, raw object, or a JSON string. */
    private fun asArray(data: Any?): JSONArray? = when (data) {
        is JSONArray -> data
        is JSONObject -> JSONArray().put(data)
        is String -> runCatching { JSONArray(data) }.getOrNull()
            ?: runCatching { JSONObject(data) }.getOrNull()?.let { JSONArray().put(it) }
        else -> null
    }

    private fun asObject(data: Any?): JSONObject? = when (data) {
        is JSONObject -> data
        is JSONArray -> data.optJSONObject(0)
        is String -> runCatching { JSONObject(data) }.getOrNull()
            ?: runCatching { JSONArray(data) }.getOrNull()?.optJSONObject(0)
        else -> null
    }

    private fun toMangaItems(data: Any?): List<MediaItem> {
        val arr = asArray(data) ?: return emptyList()
        val out = ArrayList<MediaItem>(minOf(arr.length(), 120))
        for (i in 0 until minOf(arr.length(), 120)) {
            val o = arr.optJSONObject(i) ?: continue
            val link = o.optString("id").trim()
                .ifBlank { o.optString("href").trim() }
            val title = o.optString("title").trim()
                .ifBlank { o.optString("name").trim() }
            if (link.isBlank() || title.isBlank()) continue
            out += MediaItem(
                providerId = config.id,
                id = link,
                title = title,
                type = MediaType.SERIES,
                posterUrl = o.optString("imageURL").trim()
                    .ifBlank { o.optString("image").trim() }
                    .ifBlank { o.optString("poster").trim() }
                    .ifBlank { null },
                rawType = "manga",
            )
        }
        return out
    }

    private val mangaSeeds: List<Pair<String, List<String>>> = listOf(
        "browse" to listOf("", "a", "the", "one", "love", "world"),
        "popular" to listOf("popular", "top", "trending", "best"),
        "latest" to listOf("latest", "new", "updated", "recent"),
    )

    override suspend fun catalogs(): List<CatalogRef> {
        if (isSoraNovel) return emptyList()
        return listOf(
            CatalogRef(config.id, MediaType.SERIES, CATALOG_POPULAR, "Popular", "manga"),
            CatalogRef(config.id, MediaType.SERIES, CATALOG_LATEST, "Latest", "manga"),
        )
    }

    override suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            if (isSoraNovel) return@withContext emptyList()
            if (!moduleFile.exists()) return@withContext emptyList()
            val seeds = mangaSeeds.firstOrNull { it.first == ref.id }?.second
                ?: mangaSeeds.first().second
            val idx = (page.coerceAtLeast(1) - 1).coerceAtMost(seeds.lastIndex)
            if (page > seeds.size) return@withContext emptyList()
            val seen = LinkedHashSet<String>()
            val out = ArrayList<MediaItem>()
            for (i in (listOf(idx) + ((idx + 1) until seeds.size) + ((idx - 1) downTo 0)).distinct()) {
                val payload = SoraRuntime.search(moduleFile, config.id, seeds[i])
                for (item in toMangaItems(dataOf(payload))) {
                    if (item.id in seen) continue
                    seen += item.id
                    out += item
                    if (out.size >= 60) break
                }
                if (out.isNotEmpty()) break
            }
            out
        }

    override suspend fun search(query: String, page: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            if (query.isBlank() || !moduleFile.exists()) return@withContext emptyList()
            toMangaItems(dataOf(SoraRuntime.search(moduleFile, config.id, query)))
        }

    override suspend fun getMeta(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        if (item.id.isBlank() || !moduleFile.exists()) return@withContext item
        val first = asObject(dataOf(SoraRuntime.details(moduleFile, config.id, item.id)))
            ?: return@withContext item
        val tags = first.optJSONArray("tags")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf { s -> s.isNotBlank() } }
        }.orEmpty()
        item.copy(
            title = item.title,
            overview = first.optString("description").trim().ifBlank { item.overview },
            genres = tags,
        )
    }

    override suspend fun getEpisodes(item: MediaItem): List<Episode>? =
        withContext(Dispatchers.IO) {
            if (item.id.isBlank() || !moduleFile.exists()) return@withContext null
            val data = dataOf(SoraRuntime.mangaChapters(moduleFile, config.id, item.id))
                ?: return@withContext null
            // Kanzen grouped: {en: [[num, [{id, title, chapter}]]]}
            (data as? JSONObject)?.opt("en")?.let { en ->
                val groups = if (en is JSONArray) en else return@let null
                val out = ArrayList<Episode>()
                for (g in 0 until groups.length()) {
                    val pair = groups.optJSONArray(g) ?: continue
                    val list = pair.optJSONArray(1) ?: continue
                    for (c in 0 until list.length()) {
                        val o = list.optJSONObject(c) ?: continue
                        val link = o.optString("id").trim()
                            .ifBlank { o.optString("href").trim() }
                        if (link.isBlank()) continue
                        out += Episode(
                            number = o.optInt("chapter", -1).takeIf { it > 0 }
                                ?: o.optDouble("chapter", Double.NaN).takeIf { !it.isNaN() }?.toInt()
                                ?: (out.size + 1),
                            id = link,
                            name = o.optString("title").trim().ifBlank { null },
                            image = null,
                            season = 1,
                        )
                    }
                }
                if (out.isNotEmpty()) return@withContext out
            }
            // Novel flat: [{title, href, number}]
            val arr = asArray(data) ?: return@withContext null
            val out = ArrayList<Episode>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val link = o.optString("href").trim()
                    .ifBlank { o.optString("id").trim() }
                if (link.isBlank()) continue
                out += Episode(
                    number = o.optInt("number", -1).takeIf { it > 0 } ?: (out.size + 1),
                    id = link,
                    name = o.optString("title").trim().ifBlank { null },
                    image = null,
                    season = 1,
                )
            }
            if (out.isEmpty()) null else out
        }

    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> =
        withContext(Dispatchers.IO) {
            val chapter = episode ?: return@withContext emptyList()
            if (chapter.id.isBlank() || !moduleFile.exists()) return@withContext emptyList()
            if (isSoraNovel) {
                val payload = SoraRuntime.novelText(moduleFile, config.id, chapter.id)
                val html = dataOf(payload)?.let {
                    when (it) {
                        is String -> it
                        is JSONObject -> it.optString("body").ifBlank { it.toString() }
                        else -> it.toString()
                    }
                }.orEmpty().trim()
                if (html.isBlank()) return@withContext emptyList()
                return@withContext listOf(
                    StreamSource(
                        name = chapter.name ?: "Chapter",
                        url = "",
                        text = html,
                        pageUrl = chapter.id,
                    )
                )
            }
            val imgs = asArray(dataOf(SoraRuntime.mangaImages(moduleFile, config.id, chapter.id)))
                ?: return@withContext emptyList()
            val headers = pageHeaders()
            val out = ArrayList<StreamSource>()
            for (i in 0 until imgs.length()) {
                val url = when (val e = imgs.opt(i)) {
                    is String -> e.trim()
                    is JSONObject -> e.optString("url").trim().ifBlank { e.optString("image").trim() }
                    else -> ""
                }
                if (url.isBlank()) continue
                out += StreamSource(
                    name = "Page ${out.size + 1}",
                    url = url,
                    headers = headers,
                    pageUrl = chapter.id,
                )
            }
            out
        }
}
