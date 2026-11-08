package com.hikari.app.data

import org.json.JSONArray
import org.json.JSONObject

object NuvioCatalogImport {

    class ImportedList(val name: String, val items: List<MediaItem>)

    fun parse(text: String): List<ImportedList> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()

        // A bare array is either a list of titles or a list of named lists.
        if (trimmed.startsWith("[")) {
            val arr = runCatching { JSONArray(trimmed) }.getOrNull() ?: return emptyList()
            val direct = itemsFromArray(arr, "", "")
            if (direct.isNotEmpty()) return listOf(ImportedList("", direct))
            return namedLists(arr, "")
        }

        if (!trimmed.startsWith("{")) return emptyList()
        val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return emptyList()

        val out = ArrayList<ImportedList>()
        val emptyGroups = ArrayList<ImportedList>()

        // Named groups first: a manifest/export that carries several catalogs
        // keeps them separate, so each becomes its own shelf with its own name.
        for (key in listOf("catalogs", "collections", "lists", "folders", "rows")) {
            val arr = root.optJSONArray(key) ?: continue
            for (group in namedLists(arr, key)) {
                if (group.items.isNotEmpty()) out += group else emptyGroups += group
            }
        }
        if (out.isNotEmpty()) return out

        // A single un-named list: the titles sit directly under a list key.
        val name = root.optString("name").ifBlank { root.optString("title") }
        for (key in listOf("items", "metas", "titles", "results", "entries", "list")) {
            val arr = root.optJSONArray(key) ?: continue
            val items = itemsFromArray(arr, "", "")
            if (items.isNotEmpty()) return listOf(ImportedList(name, items))
        }

        if (emptyGroups.isNotEmpty()) return emptyGroups.distinctBy { it.name }

        // A lone title object.
        val single = itemFrom(root, "", "")
        return if (single != null) listOf(ImportedList(name, listOf(single))) else emptyList()
    }

    /** Every title in a raw JSON array — for a caller that has already found the
     *  array itself (a Nuvio collection's folder carries its titles inline under
     *  `items`, rather than naming a catalog to fetch), instead of a whole
     *  document for [parse]. */
    fun itemsOf(arr: JSONArray): List<MediaItem> = itemsFromArray(arr, "", "")

    /** Every object in [arr] that carries titles, either as a named group or as
     *  a bare title. [groupKey] is the JSON key the array came from, used only to
     *  decide whether an element is a group or a title. */
    private fun namedLists(arr: JSONArray, groupKey: String): List<ImportedList> {
        val out = ArrayList<ImportedList>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").ifBlank { o.optString("title") }
            var items = emptyList<MediaItem>()
            for (key in listOf("items", "metas", "titles", "results", "entries", "list")) {
                val inner = o.optJSONArray(key) ?: continue
                items = itemsFromArray(inner, o.optString("type"), o.optString("id"))
                if (items.isNotEmpty()) break
            }
            if (items.isNotEmpty()) {
                out += ImportedList(name, items)
            } else if (groupKey == "catalogs") {
                out += ImportedList(name, emptyList())
            }
        }
        return out
    }

    private fun itemsFromArray(
        arr: JSONArray,
        fallbackRawType: String,
        fallbackId: String,
    ): List<MediaItem> {
        val out = ArrayList<MediaItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            itemFrom(o, fallbackRawType, fallbackId)?.let { out += it }
        }
        return out.distinctBy { it.uniqueId }
    }

    /** One title object → a [MediaItem], or null when it carries no usable
     *  identity (no id and no title). */
    private fun itemFrom(o: JSONObject, fallbackRawType: String, fallbackId: String): MediaItem? {
        val title = firstString(o, "name", "title", "original_name", "original_title")
        val rawType = firstString(o, "type", "media_type", "mediaType", "kind")
            .ifBlank { fallbackRawType }
        val tmdbId = firstString(o, "tmdb_id", "tmdbId", "tmdb")
            .takeIf { it.isNotBlank() && it.all { c -> c.isDigit() } }
        val rawId = firstString(o, "id", "imdb_id", "imdbId", "imdb", "_id", "slug", "key")
            .ifBlank { fallbackId }

        if (title.isBlank() && tmdbId == null && rawId.isBlank()) return null
        if (title.isBlank() && tmdbId == null && !looksResolvable(rawId)) return null

        val type = typeOf(rawType)
        val year = yearOf(o)
        val poster = imageUrl(o, 500, "poster", "image", "cover", "thumbnail", "poster_path", "posterPath", "img")
        val backdrop = imageUrl(o, 780, "background", "backdrop", "backdrop_path", "backdropPath", "banner", "fanart")

        val id = tmdbId
            ?: rawId.takeIf { looksResolvable(it) }
            ?: ("nu-" + slug(title) + if (year != null) "-$year" else "")

        return MediaItem(
            providerId = "tmdb",
            id = id,
            title = title.ifBlank { "Untitled" },
            type = type,
            posterUrl = poster,
            year = year,
            overview = firstString(o, "description", "overview", "plot", "synopsis", "summary")
                .takeIf { it.isNotBlank() },
            genres = genresOf(o),
            backdropUrl = backdrop,
            rawType = if (type == MediaType.SERIES) "tv" else if (type == MediaType.MOVIE) "movie" else "",
            rating = ratingOf(o),
            // An export often carries both a localized `name` and the
            // `original_name` the sites index — keep the latter for lookups.
            originalTitle = firstString(o, "original_name", "original_title")
                .takeIf { it.isNotBlank() && it != title }
                .orEmpty(),
        )
    }

    private fun looksResolvable(id: String): Boolean {
        if (id.isBlank()) return false
        if (id.all { it.isDigit() }) return true
        return id.lowercase().startsWith("tt") && id.length >= 8
    }

    private fun firstString(o: JSONObject, vararg keys: String): String {
        for (k in keys) {
            val v = o.opt(k) ?: continue
            if (v is JSONObject || v is JSONArray) continue
            val s = v.toString().trim()
            if (s.isNotEmpty() && s != "null") return s
        }
        return ""
    }

    private fun imageUrl(o: JSONObject, width: Int, vararg keys: String): String? {
        for (k in keys) {
            val v = o.opt(k) ?: continue
            if (v is JSONObject) {
                // Nuvio sometimes nests one of these as {url|path|full|small}.
                val nested = firstString(v, "url", "full", "path", "small", "medium")
                if (nested.isNotBlank()) return absolutise(nested, width)
                continue
            }
            val s = v.toString().trim()
            if (s.isEmpty() || s == "null") continue
            if (s.startsWith("http")) return s
            // A bare TMDB path (very common in exports built from TMDB data).
            if (s.startsWith("/")) return absolutise(s, width)
        }
        return null
    }

    private fun absolutise(s: String, width: Int): String =
        if (s.startsWith("/")) "https://image.tmdb.org/t/p/w$width$s" else s

    private fun genresOf(o: JSONObject): List<String> {
        for (k in listOf("genres", "genre", "categories")) {
            val v = o.opt(k) ?: continue
            if (v is JSONArray) {
                val list = (0 until v.length()).mapNotNull { i ->
                    val e = v.opt(i)
                    when (e) {
                        is String -> e.takeIf { it.isNotBlank() }
                        is JSONObject -> firstString(e, "name").takeIf { it.isNotBlank() }
                        else -> null
                    }
                }
                if (list.isNotEmpty()) return list
            } else {
                val s = v.toString().trim()
                if (s.isNotEmpty() && s != "null") {
                    return s.split(",", "|", "/").map { it.trim() }.filter { it.isNotEmpty() }
                }
            }
        }
        return emptyList()
    }

    private fun ratingOf(o: JSONObject): Double? {
        for (k in listOf("imdbRating", "rating", "vote_average", "voteAverage", "score")) {
            val v = o.opt(k) ?: continue
            val d = when (v) {
                is Number -> v.toDouble()
                else -> v.toString().trim().toDoubleOrNull()
            } ?: continue
            if (d > 0.0 && d <= 10.0) return d
        }
        return null
    }

    /** The year, from an explicit field or a release/air-date string. */
    private fun yearOf(o: JSONObject): Int? {
        for (k in listOf("year", "release_year", "releaseYear", "first_air_date", "release_date")) {
            val v = o.opt(k) ?: continue
            val y = when (v) {
                is Number -> v.toInt()
                else -> Regex("""(18|19|20)\d{2}""").find(v.toString())?.value?.toIntOrNull()
            }
            if (y != null && y in 1870..2200) return y
        }
        return Regex("""(18|19|20)\d{2}""")
            .find(firstString(o, "releaseInfo", "release_info", "released"))
            ?.value?.toIntOrNull()
    }

    private fun typeOf(raw: String): MediaType = when (raw.trim().lowercase()) {
        "movie", "movies", "film", "films" -> MediaType.MOVIE
        "series", "serie", "tv", "show", "shows", "anime", "animes", "ova", "tvshow" -> MediaType.SERIES
        else -> MediaType.UNKNOWN
    }

    private fun slug(s: String): String {
        val out = StringBuilder()
        for (c in s.lowercase()) {
            if (c.isLetterOrDigit()) out.append(c) else if (out.isNotEmpty() && out.last() != '-') out.append('-')
        }
        return out.toString().trim('-').take(48).ifBlank { "title" }
    }


    fun encode(items: List<MediaItem>): String {
        val arr = JSONArray()
        for (m in items) {
            arr.put(
                JSONObject()
                    .put("i", m.id)
                    .put("t", m.title)
                    .put("ty", m.rawType)
                    .put("p", m.posterUrl ?: "")
                    .put("b", m.backdropUrl ?: "")
                    .put("n", m.year ?: 0)
                    .put("o", m.overview ?: "")
                    .put("g", JSONArray(m.genres))
                    .put("r", m.rating ?: 0.0)
                    // The name the extensions index the title under, when the
                    // app's TMDB language renamed it (see MediaItem.originalTitle).
                    .put("x", m.originalTitle)
            )
        }
        return arr.toString()
    }

    fun decode(json: String): List<MediaItem> {
        if (json.isBlank()) return emptyList()
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val out = ArrayList<MediaItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("i")
            val title = o.optString("t")
            if (id.isBlank() || title.isBlank()) continue
            val rawType = o.optString("ty")
            val type = typeOf(rawType)
            val year = o.optInt("n", 0).takeIf { it > 0 }
            val rating = o.optDouble("r", 0.0).takeIf { it > 0.0 }
            out += MediaItem(
                providerId = "tmdb",
                id = id,
                title = title,
                type = type,
                posterUrl = o.optString("p").takeIf { it.isNotBlank() },
                year = year,
                overview = o.optString("o").takeIf { it.isNotBlank() },
                genres = o.optJSONArray("g")?.let { g ->
                    (0 until g.length()).map { g.optString(it) }.filter { it.isNotBlank() }
                } ?: emptyList(),
                backdropUrl = o.optString("b").takeIf { it.isNotBlank() },
                rawType = rawType,
                rating = rating,
                originalTitle = o.optString("x").takeIf { it.isNotBlank() }.orEmpty(),
            )
        }
        return out.distinctBy { it.uniqueId }
    }
}
