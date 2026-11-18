package com.hikari.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Full-field reader for Stremio `/meta` documents (Nuvio's [MetaDetailsParser]
 * ported to org.json): cast, director, writer, trailers, production companies,
 * networks, runtime, IMDb id/rating — every field [MetaSources] used to drop
 * on the floor.
 *
 * AioMetadata's backend already aggregates TMDB server-side, so its meta docs
 * carry these fields inline — which is why Nuvio shows cast/production from
 * "Aio" with no extra fetch. This parser reads the same doc instead of
 * requiring TMDB to be on.
 *
 * Shapes are lenient on purpose (unknown shapes are a miss, never a crash):
 * - `cast`: `[{name, character, photo}]`, `["Name", …]` or `"A, B"`; plus
 *   `links[]` with category cast/actor, plus `app_extras.cast`.
 * - `director`/`writer`: string, array or CSV; plus `links[]` categories and
 *   `app_extras.directors`/`writers`.
 * - `trailers`: `[{key|source|ytId, name, site, type, official}]`.
 * - production: `production_companies` / `productionCompanies` / `production`
 *   / `studios` entries `{id, name, logo|logo_path}`; networks under `networks`.
 * - similar: `recommendations` / `similar` / `moreLikeThis` entries
 *   `{id, type, name|title, poster, background, overview, releaseInfo}`.
 */
object AddonMetaDetails {

    data class ParsedMeta(
        val cast: List<CastMember> = emptyList(),
        val director: String? = null,
        val writers: List<String> = emptyList(),
        val trailers: List<Trailer> = emptyList(),
        val companies: List<CompanyRef> = emptyList(),
        val runtimeMinutes: Int? = null,
        val imdbId: String? = null,
        val imdbRating: Double? = null,
        val logo: String? = null,
        val description: String? = null,
        val year: Int? = null,
    )

    fun parse(doc: JSONObject): ParsedMeta {
        val links = linksOf(doc)
        val directors = directorsOf(doc, links)
        val writers = writersOf(doc, links)
        return ParsedMeta(
            cast = castOf(doc, links),
            director = directors.takeIf { it.isNotEmpty() }?.joinToString(", "),
            writers = writers,
            trailers = trailersOf(doc),
            companies = companiesOf(doc),
            runtimeMinutes = runtimeOf(doc),
            imdbId = doc.optString("imdb_id").trim()
                .takeIf { it.startsWith("tt") && it.length >= 8 },
            imdbRating = doc.optString("imdbRating").trim()
                .toDoubleOrNull()?.takeIf { it > 0.0 },
            logo = doc.optString("logo").trim()
                .takeIf { it.isNotBlank() && it != "null" },
            description = doc.optString("description").trim()
                .takeIf { it.isNotBlank() && it != "null" },
            year = doc.optString("releaseInfo").take(4).toIntOrNull()
                ?: doc.optString("released").take(4).toIntOrNull(),
        )
    }

    fun toExtras(parsed: ParsedMeta): TitleExtras? {
        if (parsed.cast.isEmpty() && parsed.trailers.isEmpty() &&
            parsed.companies.isEmpty() && parsed.director == null &&
            parsed.writers.isEmpty() && parsed.runtimeMinutes == null &&
            parsed.imdbId == null
        ) return null
        return TitleExtras(
            details = TitleDetails(
                runtimeMinutes = parsed.runtimeMinutes,
                director = parsed.director,
                writers = parsed.writers,
                imdbId = parsed.imdbId,
                rating = parsed.imdbRating,
                year = parsed.year,
            ),
            cast = parsed.cast,
            trailers = parsed.trailers,
            companies = parsed.companies,
            overview = parsed.description,
        )
    }

    /** Recommendation/similar entries carried inside the winning addon doc
     *  (Aio serves these; plain Cinemeta usually does not). Mapped onto the
     *  winning addon's provider so opening one re-resolves through the addon
     *  legs with no TMDB needed. */
    fun similar(
        doc: JSONObject,
        item: MediaItem,
        providerId: String,
        limit: Int = 18,
    ): List<MediaItem> {
        val arr = doc.optJSONArray("recommendations")
            ?: doc.optJSONArray("similar")
            ?: doc.optJSONArray("moreLikeThis")
            ?: return emptyList()
        val out = ArrayList<MediaItem>(limit)
        for (i in 0 until arr.length()) {
            if (out.size >= limit) break
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").trim()
            if (id.isBlank() || id == "null" || id == item.id) continue
            val title = o.optString("name").ifBlank { o.optString("title") }.trim()
            if (title.isBlank()) continue
            val type = when (o.optString("type").trim().lowercase()) {
                "movie", "movies", "film" -> MediaType.MOVIE
                else -> MediaType.SERIES
            }
            val poster = o.optString("poster").trim()
                .takeIf { it.isNotBlank() && it != "null" }
            val backdrop = (o.optString("background").trim()
                .takeIf { it.isNotBlank() && it != "null" }
                ?: o.optString("backdrop").trim()
                    .takeIf { it.isNotBlank() && it != "null" })
            if (poster == null && backdrop == null) continue
            out.add(
                MediaItem(
                    providerId = providerId,
                    id = id,
                    title = title,
                    type = type,
                    posterUrl = poster,
                    year = o.optString("releaseInfo").take(4).toIntOrNull()
                        ?: o.optString("released").take(4).toIntOrNull(),
                    overview = o.optString("description").trim()
                        .takeIf { it.isNotBlank() && it != "null" },
                    backdropUrl = backdrop,
                    rawType = o.optString("type").trim().ifBlank {
                        if (type == MediaType.MOVIE) "movie" else "series"
                    },
                    rating = o.optString("imdbRating").trim()
                        .toDoubleOrNull()?.takeIf { it > 0.0 },
                    originalTitle = "",
                )
            )
        }
        return out
    }

    // ---- internals (mirror Nuvio's MetaDetailsParser precedence) ----

    private data class MetaLink(val name: String, val category: String)

    private fun linksOf(doc: JSONObject): List<MetaLink> {
        val arr = doc.optJSONArray("links") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val l = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = l.optString("name").trim()
            val category = l.optString("category").trim()
            if (name.isBlank() || category.isBlank()) null
            else MetaLink(name, category)
        }
    }

    private fun strList(doc: JSONObject, key: String): List<String> =
        when (val v = doc.opt(key)) {
            is JSONArray -> (0 until v.length())
                .mapNotNull { v.optString(it).trim().takeIf { it.isNotBlank() } }
            is String -> v.split(',').map { it.trim() }.filter { it.isNotBlank() }
            else -> emptyList()
        }

    private fun appExtras(doc: JSONObject): JSONObject =
        doc.optJSONObject("app_extras") ?: JSONObject()

    private fun peopleIn(value: Any?, key: String): List<CastMember> {
        val arr = (value as? JSONObject)?.opt(key) ?: return emptyList()
        return when (arr) {
            is JSONArray -> (0 until arr.length()).mapNotNull { i ->
                when (val e = arr.opt(i)) {
                    is JSONObject -> {
                        val name = e.optString("name").trim()
                        if (name.isBlank()) null else CastMember(
                            name = name,
                            character = e.optString("character").trim()
                                .takeIf { it.isNotBlank() },
                            profileUrl = (e.optString("photo").trim()
                                .takeIf { it.isNotBlank() }
                                ?: e.optString("profile").trim()
                                    .takeIf { it.isNotBlank() }),
                        )
                    }
                    is String -> e.trim().takeIf { it.isNotBlank() }
                        ?.let { CastMember(name = it) }
                    else -> null
                }
            }
            is String -> arr.split(',').map { it.trim() }
                .filter { it.isNotBlank() }.map { CastMember(name = it) }
            else -> emptyList()
        }
    }

    private fun directorsOf(doc: JSONObject, links: List<MetaLink>): List<String> {
        val top = strList(doc, "director")
        val extra = peopleIn(appExtras(doc), "directors").map { it.name }
        val linked = links.filter {
            it.category.equals("director", true) || it.category.equals("directors", true)
        }.map { it.name }
        return (top + extra + linked).map { it.trim() }
            .filter { it.isNotBlank() }.distinct()
    }

    private fun writersOf(doc: JSONObject, links: List<MetaLink>): List<String> {
        val top = strList(doc, "writer")
        val extra = peopleIn(appExtras(doc), "writers").map { it.name }
        val linked = links.filter {
            val c = it.category.lowercase()
            c == "writer" || c == "writers" || c == "screenplay"
        }.map { it.name }
        return (top + extra + linked).map { it.trim() }
            .filter { it.isNotBlank() }.distinct()
    }

    private fun castOf(doc: JSONObject, links: List<MetaLink>): List<CastMember> {
        val merged = linkedMapOf<String, CastMember>()
        fun add(p: CastMember) {
            val key = p.name.trim().lowercase()
            if (key.isBlank()) return
            val cur = merged[key]
            merged[key] = if (cur == null) p.copy(name = p.name.trim())
            else cur.copy(
                character = cur.character ?: p.character,
                profileUrl = cur.profileUrl ?: p.profileUrl,
            )
        }
        peopleIn(appExtras(doc), "cast").forEach(::add)
        when (val v = doc.opt("cast")) {
            is JSONArray -> peopleIn(doc, "cast").forEach(::add)
            is String -> strList(doc, "cast").forEach { add(CastMember(name = it)) }
            else -> {}
        }
        links.filter {
            val c = it.category.lowercase()
            c == "cast" || c == "actor" || c == "actors"
        }.forEach { add(CastMember(name = it.name)) }
        return merged.values.take(20)
    }

    private fun trailersOf(doc: JSONObject): List<Trailer> {
        val arr = doc.optJSONArray("trailers") ?: return emptyList()
        val out = ArrayList<Trailer>(12)
        for (i in 0 until arr.length()) {
            if (out.size >= 12) break
            val t = arr.optJSONObject(i) ?: continue
            val key = (t.optString("key").trim()
                .takeIf { it.isNotBlank() }
                ?: t.optString("source").trim().takeIf { it.isNotBlank() }
                ?: t.optString("ytId").trim().takeIf { it.isNotBlank() }
                ?: t.optString("ytid").trim().takeIf { it.isNotBlank() })
                ?.substringAfter("v=")?.substringBefore("&")
                ?: continue
            if (key.isBlank() || key == "null") continue
            // Only YouTube keys play in-app; a raw URL or other site id
            // would open a dead player, so keep YouTube (or untagged, which
            // Stremio addons use for YouTube) and skip the rest.
            val site = t.optString("site").trim().ifBlank { "YouTube" }
            if (!site.equals("YouTube", true) && t.optString("site").isNotBlank()) continue
            val type = t.optString("type").trim().ifBlank { "Trailer" }
            out.add(
                Trailer(
                    youtubeKey = key,
                    name = t.optString("name").trim()
                        .takeIf { it.isNotBlank() } ?: type,
                    type = type,
                    thumbnailUrl = "https://img.youtube.com/vi/$key/hqdefault.jpg",
                )
            )
        }
        // Trailers before teasers, like the TMDB row ordering.
        return out.sortedWith(
            compareByDescending<Trailer> {
                when {
                    it.type.equals("Trailer", true) -> 2
                    it.type.equals("Teaser", true) -> 1
                    else -> 0
                }
            }.thenBy { it.name }
        )
    }

    private fun companiesOf(doc: JSONObject): List<CompanyRef> {
        val out = ArrayList<CompanyRef>(10)
        fun add(arr: JSONArray?, isNetwork: Boolean) {
            for (i in 0 until (arr?.length() ?: 0)) {
                if (out.size >= 10) return
                val o = arr?.optJSONObject(i) ?: continue
                val name = o.optString("name").trim()
                if (name.isBlank() || name == "null") continue
                val id = o.optString("id").trim().ifBlank { name.lowercase() }
                if (out.any { it.id == id && it.isNetwork == isNetwork }) continue
                val logo = (o.optString("logo").trim()
                    .takeIf { it.isNotBlank() && it != "null" }
                    ?: o.optString("logo_path").trim()
                        .takeIf { it.isNotBlank() && it != "null" })
                    ?.let { fullLogo(it) }
                out.add(CompanyRef(id = id, name = name, logoUrl = logo, isNetwork = isNetwork))
            }
        }
        add(
            doc.optJSONArray("production_companies")
                ?: doc.optJSONArray("productionCompanies")
                ?: doc.optJSONArray("production")
                ?: doc.optJSONArray("studios"),
            isNetwork = false,
        )
        add(doc.optJSONArray("networks"), isNetwork = true)
        return out
    }

    private fun fullLogo(path: String): String = when {
        path.startsWith("http://") || path.startsWith("https://") -> path
        path.startsWith("//") -> "https:$path"
        path.startsWith("/") -> "https://image.tmdb.org/t/p/w500$path"
        else -> path
    }

    private fun runtimeOf(doc: JSONObject): Int? {
        val raw = doc.opt("runtime")?.toString()?.trim().orEmpty()
            .ifBlank { doc.optString("duration").trim() }
        if (raw.isBlank() || raw == "null") return null
        raw.toIntOrNull()?.takeIf { it > 0 }?.let { return it }
        // "2h 53m" / "2H53" / "173 min" shapes.
        var minutes = 0
        Regex("""(\d+)\s*h""", RegexOption.IGNORE_CASE).find(raw)?.let {
            minutes += (it.groupValues[1].toIntOrNull() ?: 0) * 60
        }
        Regex("""(\d+)\s*m(?!s)""", RegexOption.IGNORE_CASE).find(raw)?.let {
            minutes += it.groupValues[1].toIntOrNull() ?: 0
        }
        return minutes.takeIf { it > 0 }
    }
}
