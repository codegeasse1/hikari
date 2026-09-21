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
        /** The rest of the reference app's discover filter set, as the raw
         *  strings the file carries (`filters.withGenres`, `…releaseDateGte`,
         *  `…withWatchProviders` …). Empty string = the file did not set it, so
         *  the parameter is simply not sent — which is what keeps an imported
         *  row behaving exactly like the one that produced the file. */
        val genresText: String = "",
        val genresExclude: String = "",
        val dateFrom: String = "",
        val dateTo: String = "",
        val ratingMin: String = "",
        val ratingMax: String = "",
        val votesMin: String = "",
        val language: String = "",
        val country: String = "",
        val keywords: String = "",
        val keywordsExclude: String = "",
        val companies: String = "",
        val companiesExclude: String = "",
        val networks: String = "",
        val providers: String = "",
        val providersExclude: String = "",
        val region: String = "",
        /** True for `provider: "trakt"` — still counted as dropped, since Hikari
         *  has no Trakt engine to run it. */
        val traktListId: String = "",
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
        /** The folder's own toggles and hero, carried over so an imported file
         *  looks the way it looked in the app it came from. */
        val hideTitle: Boolean = false,
        val gifAlways: Boolean = false,
        val gifUrl: String = "",
        val heroBackdropUrl: String = "",
        val titleLogoUrl: String = "",
    )

    /** One collection in the file. */
    class DraftCollection(
        val title: String,
        val coverUrl: String,
        val shape: String,
        val folders: List<DraftFolder>,
        val pinToTop: Boolean = false,
        val viewMode: String = "",
        val showAllTab: Boolean = true,
        val backdropUrl: String = "",
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
                        hideTitle = f.optBoolean("hideTitle", false),
                        // "Show GIF when configured" is the reference app's own
                        // switch; when it is off the gif URL is still the tile's
                        // artwork, it just does not animate (both flags off on a
                        // phone means the folder's gif is a still frame).
                        gifAlways = f.optBoolean("gifAlways", false) ||
                            f.optBoolean("alwaysAnimate", false),
                        gifUrl = firstString(f, "focusGifUrl", "gifUrl"),
                        heroBackdropUrl = firstString(f, "heroBackdropUrl"),
                        titleLogoUrl = firstString(f, "titleLogoUrl"),
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
                pinToTop = o.optBoolean("pinToTop", false),
                viewMode = o.optString("viewMode").trim(),
                showAllTab = o.optBoolean("showAllTab", true),
                backdropUrl = firstString(o, "backdropImageUrl", "backdropUrl"),
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
            // The whole discover filter set travels with the source. It is read
            // as text and passed straight through to TMDB: a filter the file set
            // must survive the import, or "the same JSON" would produce a row
            // that lists different titles than it did in the app it came from.
            val f = s.optJSONObject("filters")
            fun fx(key: String): String =
                f?.opt(key)?.toString()?.takeIf { it != "null" && it != "0" && it.isNotBlank() }
                    .orEmpty()
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
                genresText = fx("withGenres"),
                genresExclude = fx("withoutGenres"),
                dateFrom = fx("releaseDateGte"),
                dateTo = fx("releaseDateLte"),
                ratingMin = fx("voteAverageGte"),
                ratingMax = fx("voteAverageLte"),
                votesMin = fx("voteCountGte"),
                language = fx("withOriginalLanguage"),
                country = fx("withOriginCountry"),
                keywords = fx("withKeywords"),
                keywordsExclude = fx("withoutKeywords"),
                companies = fx("withCompanies"),
                companiesExclude = fx("withoutCompanies"),
                networks = fx("withNetworks"),
                providers = fx("withWatchProviders"),
                providersExclude = fx("withoutWatchProviders"),
                region = fx("watchRegion"),
                traktListId = s.opt("traktListId")?.toString()?.takeIf { it != "null" }.orEmpty(),
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
                // A folder that carries BOTH a still cover and a focus GIF keeps
                // its still: Hikari's tile has one artwork field, and the still
                // is the one that is right whether or not the tile is focused.
                // Only a gif-only folder is stored as [CoverKinds.GIF].
                val still = fd.coverUrl.takeIf { it.isNotBlank() }
                val gif = fd.gifUrl.takeIf { it.isNotBlank() }
                val inheritedCover = still ?: gif
                    ?: fd.items.firstOrNull { !it.posterUrl.isNullOrBlank() }?.posterUrl
                folders += CollectionFolder(
                    id = newId("fld"),
                    name = fd.title.ifBlank { I18n.t("Folder") },
                    sources = sources.distinctBy { it.key },
                    coverKind = when {
                        inheritedCover != null -> CoverKinds.URL
                        fd.emoji.isNotBlank() -> CoverKinds.EMOJI
                        else -> CoverKinds.NONE
                    }.let { kind ->
                        if (kind == CoverKinds.URL && still == null && gif != null) {
                            CoverKinds.GIF
                        } else {
                            kind
                        }
                    },
                    coverValue = inheritedCover ?: fd.emoji,
                    tileShape = shapeOf(fd.shape),
                    hideTitle = fd.hideTitle,
                    gifAlways = fd.gifAlways,
                    heroBackdropUrl = fd.heroBackdropUrl,
                    titleLogoUrl = fd.titleLogoUrl,
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
                pinToTop = draft.pinToTop,
                viewMode = viewModeOf(draft.viewMode),
                showAllTab = draft.showAllTab,
                backdropUrl = draft.backdropUrl,
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

    /** The reference app's collection view mode: `TABBED_GRID` is its tab strip,
     *  `ROWS` its stacked shelves (and an older or hand-written file may say
     *  "tabs"/"rows"). An unknown value keeps Hikari's own default. */
    private fun viewModeOf(raw: String): String = when (raw.trim().lowercase()) {
        "tabbed_grid", "tabs", "tabbed" -> CollectionViewModes.TABS
        "rows", "row" -> CollectionViewModes.ROWS
        else -> CollectionViewModes.ROWS
    }

    private fun mediaOf(raw: String): String = when {
        raw.trim().equals("all", true) || raw.trim().equals("both", true) -> "all"
        raw.trim().equals("TV", true) || raw.trim().equals("series", true) -> "tv"
        else -> "movie"
    }

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
            // LIST and DIRECTOR used to return null, so a file whose folders
            // held a public list or a director imported as folders with a hole
            // in them (and the drop was silently counted as "no extension has
            // this catalog"). Both engines already exist — /list/{id} and the
            // crew-credit walk of /person/{id} — so they map straight across.
            "LIST" -> TmdbSourceType.LIST
            "DIRECTOR" -> TmdbSourceType.DIRECTOR
            "TITLE" -> TmdbSourceType.TITLE
            else -> return null
        }
        // A company/network/collection is a TMDB id; a discover query is its
        // filters. Both are stored as the same JSON spec the editor writes.
        if (type.isEntity && sd.tmdbId.isBlank()) return null
        if (type == TmdbSourceType.TITLE && sd.tmdbId.isBlank()) return null
        if (type.isPerson && sd.tmdbId.isBlank()) return null
        // A list or a director that did not say which kind it is carries BOTH
        // films and series in the reference app, so "all" is the faithful
        // mapping — [mediaOf]'s "movie" default would silently drop every series
        // of a director whose file left `mediaType` empty.
        val media = if (sd.media.isBlank() &&
            (type == TmdbSourceType.LIST || type == TmdbSourceType.DIRECTOR)
        ) {
            "all"
        } else {
            mediaOf(sd.media)
        }
        val spec = TmdbSpec(
            type = type,
            id = if (type.isEntity || type.isPerson || type == TmdbSourceType.TITLE) {
                sd.tmdbId
            } else {
                ""
            },
            media = media,
            sort = sortFor(sd.sort, media),
            genre = sd.genre,
            year = sd.year,
            title = sd.title,
            genresText = sd.genresText,
            genresExclude = sd.genresExclude,
            dateFrom = sd.dateFrom,
            dateTo = sd.dateTo,
            ratingMin = sd.ratingMin,
            ratingMax = sd.ratingMax,
            votesMin = sd.votesMin,
            language = sd.language,
            country = sd.country,
            keywords = sd.keywords,
            keywordsExclude = sd.keywordsExclude,
            companies = sd.companies,
            companiesExclude = sd.companiesExclude,
            networks = sd.networks,
            providers = sd.providers,
            providersExclude = sd.providersExclude,
            region = sd.region,
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

/**
 * The other half of [NuvioCollectionsImport]: the user's collections written
 * back out as the JSON the reference app produces.
 *
 * This is the "Copy JSON" the personal catalog creator needs — the way to move
 * a hand-built catalog to another install, to send it to a friend, or simply to
 * keep a copy before a reinstall. It is deliberately the SAME shape this file's
 * importer reads (collection → folders → sources, with the reference app's own
 * field names), so an export from Hikari is a file Hikari can import again and
 * that the other app understands too: folder covers, tile shapes, hide-title,
 * pin-to-top, the view mode and every discover filter are all carried.
 *
 * Imported TITLE lists (`kind = ITEMS`) are written as a folder's own `items`
 * array, which is the shape the importer already understands for a folder that
 * holds titles rather than a catalog descriptor.
 */
object NuvioCollectionsExport {

    fun encode(collections: List<Collection>): String {
        val arr = JSONArray()
        for (c in collections) {
            val folders = JSONArray()
            for (f in c.folders) {
                val sources = JSONArray()
                for (s in f.sources) sourceJson(s)?.let { sources.put(it) }
                folders.put(
                    JSONObject()
                        .put("id", f.id)
                        .put("title", f.name)
                        .put("tileShape", nuvioShape(f.tileShape))
                        .put(
                            "coverImageUrl",
                            f.coverValue.takeIf { CoverKinds.normalize(f.coverKind) == CoverKinds.URL }
                                .orEmpty(),
                        )
                        .put(
                            "focusGifUrl",
                            f.coverValue.takeIf { CoverKinds.normalize(f.coverKind) == CoverKinds.GIF }
                                .orEmpty(),
                        )
                        .put("coverEmoji", f.coverValue.takeIf {
                            CoverKinds.normalize(f.coverKind) == CoverKinds.EMOJI
                        }.orEmpty())
                        .put("hideTitle", f.hideTitle)
                        .put("gifAlways", f.gifAlways)
                        .put("heroBackdropUrl", f.heroBackdropUrl)
                        .put("titleLogoUrl", f.titleLogoUrl)
                        .put("sources", sources)
                )
            }
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("title", c.name)
                    .put(
                        "backdropImageUrl",
                        c.backdropUrl.ifBlank {
                            c.coverValue.takeIf {
                                CoverKinds.normalize(c.coverKind) == CoverKinds.URL
                            }.orEmpty()
                        },
                    )
                    .put("tileShape", nuvioShape(c.tileShape))
                    .put("pinToTop", c.pinToTop)
                    .put(
                        "viewMode",
                        if (c.viewMode == CollectionViewModes.TABS) "TABBED_GRID" else "ROWS",
                    )
                    .put("showAllTab", c.showAllTab)
                    .put("folders", folders)
            )
        }
        return arr.toString()
    }

    private fun nuvioShape(shape: String): String = when (TileShapes.normalize(shape)) {
        TileShapes.WIDE -> "LANDSCAPE"
        TileShapes.SQUARE -> "SQUARE"
        else -> "poster"
    }

    private fun nuvioMedia(media: String): String = when (media) {
        "tv" -> "TV"
        "all" -> "All"
        else -> "Movie"
    }

    private fun sourceJson(s: CatalogSource): JSONObject? = when (s.kind) {
        CatalogSourceKind.TMDB -> {
            val spec = s.spec ?: TmdbSpec.decode(s.tmdbSpec) ?: return null
            val o = JSONObject()
                .put("provider", "tmdb")
                .put("tmdbSourceType", spec.type.key.uppercase())
                .put("mediaType", nuvioMedia(spec.media))
                .put("sortBy", spec.sort)
                .put("title", s.title)
            if (spec.id.isNotBlank()) {
                o.put("tmdbId", spec.id.toIntOrNull() ?: spec.id)
            }
            val filters = JSONObject()
            if (spec.genresText.isNotBlank() || spec.genre > 0) {
                filters.put(
                    "withGenres",
                    spec.genresText.ifBlank { spec.genre.toString() },
                )
            }
            if (spec.genresExclude.isNotBlank()) filters.put("withoutGenres", spec.genresExclude)
            if (spec.dateFrom.isNotBlank()) filters.put("releaseDateGte", spec.dateFrom)
            if (spec.dateTo.isNotBlank()) filters.put("releaseDateLte", spec.dateTo)
            if (spec.ratingMin.isNotBlank()) filters.put("voteAverageGte", spec.ratingMin)
            if (spec.ratingMax.isNotBlank()) filters.put("voteAverageLte", spec.ratingMax)
            if (spec.votesMin.isNotBlank()) filters.put("voteCountGte", spec.votesMin)
            if (spec.language.isNotBlank()) filters.put("withOriginalLanguage", spec.language)
            if (spec.country.isNotBlank()) filters.put("withOriginCountry", spec.country)
            if (spec.keywords.isNotBlank()) filters.put("withKeywords", spec.keywords)
            if (spec.keywordsExclude.isNotBlank()) filters.put("withoutKeywords", spec.keywordsExclude)
            if (spec.companies.isNotBlank()) filters.put("withCompanies", spec.companies)
            if (spec.companiesExclude.isNotBlank()) {
                filters.put("withoutCompanies", spec.companiesExclude)
            }
            if (spec.networks.isNotBlank()) filters.put("withNetworks", spec.networks)
            if (spec.year > 0) filters.put("year", spec.year)
            if (spec.region.isNotBlank()) filters.put("watchRegion", spec.region)
            if (spec.providers.isNotBlank()) filters.put("withWatchProviders", spec.providers)
            if (spec.providersExclude.isNotBlank()) {
                filters.put("withoutWatchProviders", spec.providersExclude)
            }
            if (filters.length() > 0) o.put("filters", filters)
            o
        }

        CatalogSourceKind.PROVIDER -> JSONObject()
            .put("provider", "addon")
            .put("addonId", s.providerId)
            .put("catalogId", s.catalogId)
            .put("type", s.rawType.ifBlank { s.type.name.lowercase() })
            .put("title", s.title)

        CatalogSourceKind.ITEMS -> {
            // An imported title list travels as the folder's own titles, one
            // entry per line the file's own reader understands.
            val items = runCatching { JSONArray(s.itemsJson) }.getOrNull() ?: return null
            JSONObject()
                .put("provider", "hikari")
                .put("title", s.title)
                .put("items", items)
        }
    }
}
