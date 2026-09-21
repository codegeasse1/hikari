package com.hikari.app.data

import com.hikari.app.i18n.I18n
import org.json.JSONArray
import org.json.JSONObject

/**
 * Imports a Nuvio "collections" export — the file the personal catalog creator
 * produces — as Hikari collections.
 *
 * WHAT THE FILE IS. A JSON array of collections, each with a `folders` array,
 * each folder with a `sources` array. A source is one catalog *descriptor*, not
 * a list of titles:
 *
 * ```json
 * [ { "title": "Networks",
 *     "folders": [ { "title": "Netflix", "tileShape": "LANDSCAPE",
 *                    "coverImageUrl": "https://…/Latest.png",
 *                    "sources": [
 *                      { "provider": "tmdb", "tmdbSourceType": "COMPANY",
 *                        "tmdbId": 213, "mediaType": "TV", "title": "Netflix" },
 *                      { "provider": "addon", "addonId": "com.linvo.cinemeta",
 *                        "catalogId": "top", "type": "movie" } ] } ] } ]
 * ```
 *
 * That maps onto Hikari's own model one-to-one — a collection holds folders, a
 * folder holds catalog sources — which is why this is a *structure* import and
 * not a title-list import: the user's file already describes the shape they
 * want, and rebuilding it by hand (folder by folder, catalog by catalog) is the
 * tedium this removes. It is the same thing the reference app's "Import
 * Collections" does.
 *
 * WHAT IT CANNOT CARRY. Trakt lists (`provider: "trakt"`) have no engine in
 * Hikari, so those sources are dropped and counted; and an `addon` source whose
 * catalog is not in any installed extension is dropped and counted too, rather
 * than being saved as a row that could never load anything. Both counts are
 * reported to the user — an import that quietly loses half a folder is worse
 * than one that says so.
 */
object NuvioCollectionsImport {

    /** One catalog descriptor found in the file, before it is matched against
     *  what is actually installed. */
    class DraftSource(
        val provider: String,
        val title: String,
        val addonId: String,
        val catalogId: String,
        val type: String,
        val tmdbId: String,
        val tmdbType: String,
        val media: String,
        val sort: String,
        val genre: Int,
        val year: Int,
    )

    /** One folder in the file. */
    class DraftFolder(
        val title: String,
        val coverUrl: String,
        val emoji: String,
        val shape: String,
        val sources: List<DraftSource>,
        /** Titles the folder carries ITSELF, rather than naming a catalog to
         *  fetch. A Nuvio export can do either: a folder holding a catalog
         *  descriptor has `sources`, and a folder holding actual titles has
         *  `items`. Ignoring the second shape is why an imported file that
         *  plainly contained titles produced folders that loaded nothing. */
        val items: List<MediaItem> = emptyList(),
    )

    /** One collection in the file. */
    class DraftCollection(
        val title: String,
        val coverUrl: String,
        val shape: String,
        val folders: List<DraftFolder>,
    )

    /** Everything one file offered. */
    class Plan(
        val collections: List<DraftCollection>,
        /** A file that is a plain list of titles rather than collections —
         *  handled by [NuvioCatalogImport]; the sheet offers those too. */
        val titleLists: List<NuvioCatalogImport.ImportedList>,
    ) {
        val folderCount: Int get() = collections.sumOf { it.folders.size }
        val sourceCount: Int get() = collections.sumOf { c -> c.folders.sumOf { it.sources.size } }
        val isCollections: Boolean get() = collections.isNotEmpty()
    }

    /** True when [text] looks like a collections export (something in it lists
     *  `folders`), as opposed to a list of titles. */
    fun looksLikeCollections(text: String): Boolean {
        val t = text.trim()
        if (!t.startsWith("[")) return false
        val arr = runCatching { JSONArray(t) }.getOrNull() ?: return false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optJSONArray("folders") != null) return true
        }
        return false
    }

    /** Parses [text]. Never throws: anything unreadable comes back as an empty
     *  plan, and the caller says so. */
    fun parse(text: String): Plan {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Plan(emptyList(), emptyList())
        // A bare array is what the reference app exports; an object with a
        // `collections` array is what a wrapped/older export looks like.
        val arr = if (trimmed.startsWith("[")) {
            runCatching { JSONArray(trimmed) }.getOrNull()
        } else {
            runCatching { JSONObject(trimmed) }.getOrNull()?.optJSONArray("collections")
        }
        if (arr == null) return Plan(emptyList(), NuvioCatalogImport.parse(trimmed))
        val out = draftsFrom(arr)
        // A file of collections with no folders at all is still worth importing
        // as empty collections; a file that had no collections is a title list.
        return if (out.isEmpty()) Plan(emptyList(), NuvioCatalogImport.parse(trimmed))
        else Plan(out, emptyList())
    }

    private fun draftsFrom(arr: JSONArray): List<DraftCollection> {
        val out = ArrayList<DraftCollection>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val folders = ArrayList<DraftFolder>()
            val folderArr = o.optJSONArray("folders")
            if (folderArr != null) {
                for (j in 0 until folderArr.length()) {
                    val f = folderArr.optJSONObject(j) ?: continue
                    folders += DraftFolder(
                        title = firstString(f, "title", "name"),
                        coverUrl = firstString(f, "coverImageUrl", "coverUrl", "cover"),
                        emoji = firstString(f, "coverEmoji", "emoji"),
                        shape = firstString(f, "tileShape"),
                        sources = sourcesOf(f),
                        items = itemsOf(f),
                    )
                }
            }
            val title = firstString(o, "title", "name")
            if (title.isBlank() && folders.isEmpty()) continue
            out += DraftCollection(
                title = title.ifBlank { "Imported" },
                coverUrl = firstString(o, "backdropImageUrl", "coverImageUrl", "cover"),
                shape = firstString(o, "tileShape"),
                folders = folders,
            )
        }
        return out
    }

    /** The titles a folder carries inline (a Nuvio export that puts actual titles
     *  in a folder instead of naming a catalog). Empty for the catalog-descriptor
     *  shape, which is what the reference export uses for its own rows. */
    private fun itemsOf(folder: JSONObject): List<MediaItem> {
        for (key in listOf("items", "titles", "metas", "results", "entries", "list")) {
            val arr = folder.optJSONArray(key) ?: continue
            val items = NuvioCatalogImport.itemsOf(arr)
            if (items.isNotEmpty()) return items
        }
        return emptyList()
    }

    private fun sourcesOf(folder: JSONObject): List<DraftSource> {
        val arr = folder.optJSONArray("sources") ?: folder.optJSONArray("catalogSources")
            ?: return emptyList()
        val out = ArrayList<DraftSource>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            out += DraftSource(
                provider = s.optString("provider").trim().lowercase(),
                title = firstString(s, "title", "name"),
                addonId = s.optString("addonId").trim(),
                catalogId = s.optString("catalogId").trim(),
                type = s.optString("type").trim(),
                tmdbId = s.opt("tmdbId")?.toString()?.takeIf { it != "null" }.orEmpty(),
                tmdbType = s.optString("tmdbSourceType").trim().uppercase(),
                media = s.optString("mediaType").trim(),
                sort = s.optString("sortBy").trim(),
                genre = s.optJSONObject("filters")?.let { f ->
                    f.optString("withGenres").split(',').firstOrNull()
                        ?.trim()?.toIntOrNull() ?: 0
                } ?: 0,
                year = s.optJSONObject("filters")?.optInt("year", 0) ?: 0,
            )
        }
        return out
    }

    private fun firstString(o: JSONObject, vararg keys: String): String {
        for (k in keys) {
            val v = o.optString(k).trim()
            if (v.isNotBlank() && v != "null") return v
        }
        return ""
    }

    /** What an import actually produced. */
    class Result(
        val collections: List<Collection>,
        val catalogs: Int,
        val droppedTrakt: Int,
        val droppedAddons: Int,
    )

    /** Turns a [Plan] into saved-ready [Collection]s.
     *
     *  [resolveCatalog] answers "which installed extension exposes this catalog
     *  id of this type?" for one `addon` source, or null when nothing does — see
     *  the call site, which asks the provider manager once per import. */
    suspend fun materialise(
        plan: Plan,
        newId: (String) -> String,
        resolveCatalog: suspend (addonId: String, type: String, catalogId: String) -> CatalogSource?,
    ): Result {
        val collections = ArrayList<Collection>(plan.collections.size)
        var catalogs = 0
        var trakt = 0
        var addons = 0
        for (draft in plan.collections) {
            val folders = ArrayList<CollectionFolder>(draft.folders.size)
            for (fd in draft.folders) {
                val sources = ArrayList<CatalogSource>(fd.sources.size + 1)
                // Titles the folder carries itself come FIRST: they are the
                // folder's own content, and (unlike a catalog descriptor) they
                // need nothing installed to load. This is the shape a Nuvio
                // export uses when a folder holds titles directly, and dropping
                // it is why such a file imported "successfully" into folders
                // that showed nothing at all.
                if (fd.items.isNotEmpty()) {
                    val json = NuvioCatalogImport.encode(fd.items)
                    if (json.isNotEmpty()) {
                        sources += CatalogSource(
                            kind = CatalogSourceKind.ITEMS,
                            title = fd.title.ifBlank { I18n.t("Imported list") },
                            itemsJson = json,
                            type = if (fd.items.any { it.type == MediaType.SERIES }) {
                                MediaType.SERIES
                            } else {
                                MediaType.MOVIE
                            },
                            rawType = "import",
                            uid = newId("items"),
                        )
                        catalogs++
                    }
                }
                for (sd in fd.sources) {
                    val mapped = when (sd.provider) {
                        "tmdb" -> tmdbSource(sd)
                        "addon", "stremio" -> resolveCatalog(sd.addonId, sd.type, sd.catalogId)
                        else -> null
                    }
                    when {
                        mapped != null -> {
                            sources += mapped
                            catalogs++
                        }
                        sd.provider == "trakt" -> trakt++
                        else -> addons++
                    }
                }
                // No cover in the file: the first title's poster stands in, so
                // the imported folder is recognisable instead of a grey tile.
                val inheritedCover = fd.coverUrl.takeIf { it.isNotBlank() }
                    ?: fd.items.firstOrNull { !it.posterUrl.isNullOrBlank() }?.posterUrl
                folders += CollectionFolder(
                    id = newId("fld"),
                    name = fd.title.ifBlank { I18n.t("Folder") },
                    sources = sources.distinctBy { it.key },
                    coverKind = when {
                        inheritedCover != null -> CoverKinds.URL
                        fd.emoji.isNotBlank() -> CoverKinds.EMOJI
                        else -> CoverKinds.NONE
                    },
                    coverValue = inheritedCover ?: fd.emoji,
                    tileShape = shapeOf(fd.shape),
                )
            }
            val inheritedCollectionCover = draft.coverUrl.takeIf { it.isNotBlank() }
                ?: folders.firstOrNull { it.coverKind == CoverKinds.URL }?.coverValue
            collections += Collection(
                id = newId("col"),
                name = draft.title.ifBlank { I18n.t("Imported") },
                folders = folders,
                coverKind = if (inheritedCollectionCover != null) CoverKinds.URL
                else CoverKinds.NONE,
                coverValue = inheritedCollectionCover.orEmpty(),
                tileShape = shapeOf(draft.shape),
            )
        }
        return Result(collections, catalogs, trakt, addons)
    }

    /** Nuvio's tile shapes are `LANDSCAPE` / `poster` (and a lowercase
     *  `landscape` in older files); Hikari's are wide / poster / square. */
    private fun shapeOf(raw: String): String = when (raw.trim().lowercase()) {
        "landscape", "wide", "16:9" -> TileShapes.WIDE
        "square" -> TileShapes.SQUARE
        else -> TileShapes.POSTER
    }

    private fun mediaOf(raw: String): String =
        if (raw.trim().equals("TV", true) || raw.trim().equals("series", true)) "tv" else "movie"

    /**
     * The ordering TMDB will actually accept for [media].
     *
     * A file can pair a movie's date field with a TELEVISION discovery (the
     * example export asks for `/discover/tv` sorted by
     * `primary_release_date.desc`, which is a movie-only field): TMDB answers
     * that with an error and the row would come back empty with nothing to
     * explain why. The date field is swapped for the one the endpoint has —
     * `first_air_date` for series, `primary_release_date` for films — and every
     * other ordering (`popularity.desc`, `vote_average.desc`, …) is passed
     * through untouched.
     */
    private fun sortFor(raw: String, media: String): String {
        val sort = raw.trim().ifBlank { "popularity.desc" }
        return when {
            media == "tv" && sort.startsWith("primary_release_date") ->
                "first_air_date" + sort.removePrefix("primary_release_date")
            media != "tv" && sort.startsWith("first_air_date") ->
                "primary_release_date" + sort.removePrefix("first_air_date")
            else -> sort
        }
    }

    /** A TMDB source, in Hikari's own spec form. */
    private fun tmdbSource(sd: DraftSource): CatalogSource? {
        val type = when (sd.tmdbType) {
            "DISCOVER" -> TmdbSourceType.DISCOVER
            "COMPANY" -> TmdbSourceType.COMPANY
            "NETWORK" -> TmdbSourceType.NETWORK
            "COLLECTION" -> TmdbSourceType.COLLECTION
            "PERSON" -> TmdbSourceType.PERSON
            else -> return null
        }
        // A company/network/collection is a TMDB id; a discover query is its
        // filters. Both are stored as the same JSON spec the editor writes.
        if (type.isEntity && sd.tmdbId.isBlank()) return null
        val spec = TmdbSpec(
            type = type,
            id = if (type.isEntity) sd.tmdbId else "",
            media = mediaOf(sd.media),
            sort = sortFor(sd.sort, mediaOf(sd.media)),
            genre = sd.genre,
            year = sd.year,
            title = sd.title,
        )
        return CatalogSource(
            kind = CatalogSourceKind.TMDB,
            title = sd.title.ifBlank { fallbackTitle(type) },
            type = spec.kind,
            rawType = "tmdb",
            tmdbSpec = spec.encode(),
        )
    }

    private fun fallbackTitle(type: TmdbSourceType): String = when (type) {
        TmdbSourceType.COMPANY -> I18n.t("Studio")
        TmdbSourceType.NETWORK -> I18n.t("Network")
        TmdbSourceType.COLLECTION -> I18n.t("Collection")
        TmdbSourceType.DISCOVER -> I18n.t("Discover")
        else -> "TMDB"
    }
}
