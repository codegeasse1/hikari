package com.hikari.app.data

import com.hikari.app.providers.ContentProvider
import com.hikari.app.providers.ProviderManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Turns a saved [Collection] into the same [CatalogRow] shape the Home feed
 * uses, so a collection needs no bespoke rendering: Home's rows, the "Show All"
 * grid, the detail page and the player are all the existing ones.
 *
 * Two shapes are produced:
 *  - [folderRows] — one row per SOURCE of one folder ("HBO", "Marvel Studios",
 *    "Trending Now" side by side). This is what a folder page shows.
 *  - [collectionRows] — one row per FOLDER, each holding the merged items of
 *    that folder's sources. This is what Home shows when a collection is
 *    picked: the folder names become the shelf titles, and everything under
 *    them is already the content the user asked for.
 *
 * Rows are emitted as each source lands (not after every one finished), gated
 * by a small semaphore and tight timeouts so a collection full of slow
 * extensions can neither stall the screen nor flood the network pool.
 */
class CollectionsRepository(private val manager: ProviderManager) {

    private val gate = Semaphore(6)
    private val perCatalogTimeoutMs = 15_000L

    /** A collection-title probe a search runs: one page from one source, and it
     *  must never hold the search up (see [searchTitles]). */
    private val searchTimeoutMs = 8_000L

    /** How many network-backed collection sources one search will probe. The
     *  local ones (imported lists) are always all read. */
    private val maxRemoteProbes = 8

    /** One row per source of [folder], in the folder's own source order. */
    fun folderRows(collection: Collection, folder: CollectionFolder): Flow<List<CatalogRow>> =
        channelFlow flow@{
            // Deduped by key: two sources with the same key ARE the same
            // catalog, and two rows with the same key crash any lazy list that
            // draws them (`Key "prov|cs3|…" was already used`). AppStore drops
            // the copies when it reads a collection, but a collection built in
            // memory (the editor, a just-imported list) can still carry a pair,
            // so the row builder refuses them too.
            val sources = folder.sources.distinctBy { it.key }
            if (sources.isEmpty()) {
                send(emptyList())
                return@flow
            }
            val placed = HashMap<Int, CatalogRow>()
            val work = com.hikari.app.work.BackgroundWork.begin("Loading " + folder.name)
            try {
                sources.forEachIndexed { i, source ->
                    launch {
                        val row = withContext(Dispatchers.IO) {
                            gate.withPermit { runCatching { sourceRow(collection, folder, source) }.getOrNull() }
                        }
                        synchronized(placed) {
                            if (row != null && row.items.isNotEmpty()) placed[i] = row
                        }
                        publish(this@flow, placed, sources.size)
                    }
                }
            } finally {
                com.hikari.app.work.BackgroundWork.end(work)
            }
        }

    /** One row per FOLDER of [collection], each merging that folder's sources. */
    fun collectionRows(collection: Collection): Flow<List<CatalogRow>> = channelFlow flow@{
        if (collection.folders.isEmpty()) {
            send(emptyList())
            return@flow
        }
        val placed = HashMap<Int, CatalogRow>()
        val work = com.hikari.app.work.BackgroundWork.begin("Loading " + collection.name)
        try {
            collection.folders.forEachIndexed { i, folder ->
                launch {
                    val row = withContext(Dispatchers.IO) {
                        runCatching { folderRow(collection, folder) }.getOrNull()
                    }
                    synchronized(placed) {
                        if (row != null && row.items.isNotEmpty()) placed[i] = row
                    }
                    publish(this@flow, placed, collection.folders.size)
                }
            }
        } finally {
            com.hikari.app.work.BackgroundWork.end(work)
        }
    }

    /** The folder's rows in one go (a folder page paints them all together). */
    suspend fun folderRowsOnce(collection: Collection, folder: CollectionFolder): List<CatalogRow> =
        folderRows(collection, folder).lastOrNull().orEmpty()

    /**
     * The rows Home shows for a collection pick.
     *
     * One folder => that folder's own CATALOGS, one shelf each ("Netflix",
     * "Hulu", "Pixar" side by side), because a collection with a single folder
     * is just a grouping of sources the user picked — collapsing them into one
     * mixed shelf hides exactly what they chose. Two or more folders => one
     * shelf per folder, so the folder names are the shelves and the content
     * stays where the user filed it.
     */
    fun pickRows(collection: Collection): Flow<List<CatalogRow>> {
        val only = collection.folders.singleOrNull()
        return if (only != null) folderRows(collection, only) else collectionRows(collection)
    }

    /**
     * Every catalog of every folder as its own row — the "Show all" page of a
     * whole collection. Rows land as each source answers, in folder-then-source
     * order, and each row's title carries its folder's name when the collection
     * has more than one (so a flat grid still says where a shelf came from).
     */
    fun allRows(collection: Collection): Flow<List<CatalogRow>> = channelFlow flow@{
        val slots = ArrayList<Pair<CollectionFolder, CatalogSource>>()
        // One slot per DISTINCT source of each folder (see [folderRows]): the
        // "Show all" page draws one row per slot, and two slots with the same
        // catalog key are two rows with the same key — a lazy list crash, and a
        // duplicate shelf for the user.
        collection.folders.forEach { f ->
            f.sources.distinctBy { it.key }.forEach { s -> slots += f to s }
        }
        if (slots.isEmpty()) {
            send(emptyList())
            return@flow
        }
        val placed = HashMap<Int, CatalogRow>()
        val work = com.hikari.app.work.BackgroundWork.begin("Loading " + collection.name)
        try {
            slots.forEachIndexed { i, (folder, source) ->
                launch {
                    val loaded = withContext(Dispatchers.IO) {
                        gate.withPermit { runCatching { sourceRow(collection, folder, source) }.getOrNull() }
                    }
                    val row = loaded?.takeIf { it.items.isNotEmpty() }?.let { r ->
                        if (collection.folders.size > 1) r.copy(title = folder.name + " · " + r.title) else r
                    }
                    synchronized(placed) { if (row != null) placed[i] = row }
                    publish(this@flow, placed, slots.size)
                }
            }
        } finally {
            com.hikari.app.work.BackgroundWork.end(work)
        }
    }

    /** Publish what has arrived so far, in slot order, so late rows slot in
     *  where they belong instead of jumping to the end of the list. */
    private suspend fun publish(
        scope: ProducerScope<List<CatalogRow>>,
        placed: Map<Int, CatalogRow>,
        size: Int,
    ) {
        val ordered = synchronized(placed) { (0 until size).mapNotNull { placed[it] } }
        if (ordered.isNotEmpty()) scope.send(ordered)
    }

    private suspend fun sourceRow(
        collection: Collection,
        folder: CollectionFolder,
        source: CatalogSource,
    ): CatalogRow? {
        val label = if (source.title.isNotBlank()) source.title else source.key
        val breadcrumb = listOf(collection.name, folder.name)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        if (source.kind == CatalogSourceKind.ITEMS) {
            // A list the user imported (a Nuvio/Stremio export, a shared JSON
            // file — see NuvioCatalogImport). Its titles live inside the
            // collection, so this row needs no network at all: the items are
            // already MediaItems, already TMDB-addressed, and open and play
            // exactly like any other Hikari title.
            val items = withContext(Dispatchers.Default) {
                NuvioCatalogImport.decode(source.itemsJson)
            }
            if (items.isEmpty()) return null
            return CatalogRow(
                providerId = "tmdb",
                providerName = breadcrumb,
                title = if (source.title.isNotBlank()) source.title else "Imported list",
                items = items,
                key = "coll|${collection.id}|${folder.id}|${source.key}",
                catalogId = source.key,
                type = if (items.any { it.type == MediaType.SERIES }) MediaType.SERIES
                else MediaType.MOVIE,
                rawType = "import",
            )
        }
        if (source.kind == CatalogSourceKind.TMDB) {
            // A hand-built source (a studio, a network, a person, a custom
            // discover query — see TmdbSources) carries its whole description
            // in `tmdbSpec`; a built-in preset has only a key. Both run through
            // the same pager, so a row behaves identically either way.
            val spec = source.spec ?: TmdbPresets.byKey(source.tmdbPreset)?.let { preset ->
                TmdbSpec(
                    type = TmdbSourceType.PRESET,
                    preset = preset.key,
                    media = if (preset.isMovie) "movie" else "tv",
                )
            } ?: return null
            val items = withTimeoutOrNull(perCatalogTimeoutMs) { TmdbSources.page(spec, 1) }
                .orEmpty().distinctBy { it.uniqueId }
            if (items.isEmpty()) return null
            return CatalogRow(
                providerId = "tmdb",
                providerName = breadcrumb,
                // The user's own name wins; otherwise ask the source (a preset
                // answers from its own list without a request).
                title = if (source.title.isNotBlank()) source.title
                else TmdbSources.displayName(spec),
                items = items,
                key = "coll|${collection.id}|${folder.id}|${source.key}",
                catalogId = source.key,
                type = spec.kind,
                rawType = "tmdb",
            )
        }
        val provider: ContentProvider = manager.byId(source.providerId) ?: return null
        val ref = CatalogRef(
            providerId = source.providerId,
            type = source.type,
            id = source.catalogId,
            name = label,
            rawType = source.rawType,
        )
        val raw = withTimeoutOrNull(perCatalogTimeoutMs) {
            runCatching { provider.getCatalog(ref, 1) }.getOrDefault(emptyList())
        }.orEmpty().distinctBy { it.uniqueId }
        val items = translate(ref.providerId, raw)
        if (items.isEmpty()) return null
        return CatalogRow(
            providerId = source.providerId,
            providerName = listOf(breadcrumb, provider.config.name)
                .filter { it.isNotBlank() }
                .joinToString(" · "),
            title = label,
            items = items,
            key = "coll|${collection.id}|${folder.id}|${source.key}",
            catalogId = source.key,
            type = source.type,
            rawType = source.rawType,
        )
    }

    /** One title found in the user's own collections. */
    data class CollectionHit(
        val item: MediaItem,
        /** "Collection · Folder" — what the Search tab prints under the poster,
         *  so a hit says which catalog it came from. */
        val label: String,
    )

    /**
     * The LOCAL half of Search: the titles the user's own collections carry,
     * matched by name and labelled with the collection they came from.
     *
     * Imported lists ([CatalogSourceKind.ITEMS]) are already [MediaItem]s inside
     * the collection, so they are matched with no network at all — and that is
     * the case that matters most, because a personal catalog is usually exactly
     * that: a list someone imported. A hand-built TMDB source has no local copy
     * of its titles, so its first page is fetched (under the same small gate
     * and a tight timeout the rows use). PROVIDER sources are skipped on
     * purpose: their extension is what the search itself searches, and asking
     * it here as well would double every result.
     */
    suspend fun searchTitles(
        query: String,
        limit: Int = 24,
        /** Called with the hits gathered SO FAR, as each source answers.
         *  Imported lists are instant but an extension catalog is a network
         *  fetch, so without this the row stayed empty until the slowest source
         *  of the folder had answered; the caller can then show the local
         *  matches the moment they exist. Always called at least once (with the
         *  final list). */
        onPartial: ((List<CollectionHit>) -> Unit)? = null,
    ): List<CollectionHit> =
        searchIn(query, emptySet(), limit, onPartial)

    /**
     * The same match, narrowed to a set of collections — what the Search tab
     * asks for when the user has picked a personal catalog in its provider row
     * ("search inside abc"), and what the magnifier on a catalog page opens.
     *
     * An EMPTY [collectionIds] means every collection, which is what the
     * unscoped lookup ([searchTitles]) has always done; a non-empty set reads
     * only what those collections hold. Imported lists still cost nothing, and
     * the network-backed sources (a hand-built TMDB source, an extension
     * catalog filed inside a personal catalog) still share the one probe budget,
     * so picking a catalog can never turn a search into a stampede.
     */
    suspend fun searchIn(
        query: String,
        collectionIds: Set<String> = emptySet(),
        limit: Int = 24,
        onPartial: ((List<CollectionHit>) -> Unit)? = null,
    ): List<CollectionHit> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.length < 2) return@withContext emptyList()
            val needle = q.lowercase()
            val collections = runCatching {
                com.hikari.app.HikariApp.instance.store.collections()
            }.getOrDefault(emptyList())
                .filter { collectionIds.isEmpty() || it.id in collectionIds }
            val out = ArrayList<CollectionHit>()
            val seen = HashSet<String>()
            // Only a bounded number of SOURCES THAT NEED THE NETWORK are probed
            // per search. Imported lists are free (their titles live in the
            // collection already), but a user with thirty hand-built TMDB
            // sources would otherwise fire thirty page requests on every
            // keystroke pause — a search is not allowed to become a stampede.
            var remoteProbes = 0
            for (collection in collections) {
                for (folder in collection.folders) {
                    for (source in folder.sources) {
                        if (out.size >= limit) break
                        val items: List<MediaItem> = when (source.kind) {
                            CatalogSourceKind.ITEMS -> runCatching {
                                NuvioCatalogImport.decode(source.itemsJson)
                            }.getOrDefault(emptyList())

                            CatalogSourceKind.TMDB -> {
                                if (remoteProbes >= maxRemoteProbes) {
                                    emptyList()
                                } else {
                                    remoteProbes++
                                    val spec = source.spec
                                        ?: TmdbPresets.byKey(source.tmdbPreset)?.let { p ->
                                            TmdbSpec(
                                                type = TmdbSourceType.PRESET,
                                                preset = p.key,
                                                media = if (p.isMovie) "movie" else "tv",
                                            )
                                        }
                                    if (spec == null) emptyList() else {
                                        withTimeoutOrNull(searchTimeoutMs) {
                                            runCatching { TmdbSources.page(spec, 1) }
                                                .getOrDefault(emptyList())
                                        }.orEmpty()
                                    }
                                }
                            }

                            // An extension catalog the user filed in their own
                            // catalog: its first page is fetched through the very
                            // same [sourceRow] the folder's rows use, so a title
                            // that lives in a personal catalog is findable in
                            // Search like everything else — it used to be the ONE
                            // kind skipped here ("my personal catalog doesn't
                            // show up in search at all"). Bounded by the same
                            // remote probe budget as a TMDB source.
                            CatalogSourceKind.PROVIDER -> {
                                if (remoteProbes >= maxRemoteProbes) {
                                    emptyList()
                                } else {
                                    remoteProbes++
                                    runCatching {
                                        gate.withPermit {
                                            withTimeoutOrNull(searchTimeoutMs) {
                                                sourceRow(collection, folder, source)?.items
                                                    ?: emptyList()
                                            }.orEmpty()
                                        }
                                    }.getOrDefault(emptyList())
                                }
                            }
                        }
                        val label = listOf(collection.name, folder.name)
                            .filter { it.isNotBlank() }
                            .distinct()
                            .joinToString(" · ")
                        for (item in items) {
                            if (out.size >= limit) break
                            // Both names count: the display title (what the user
                            // reads, in the app's TMDB language) and the original
                            // one (what the sites and the user's own memories of
                            // the film call it) — otherwise a Spanish-language
                            // install cannot find "Avengers: Endgame" at all.
                            val names = item.allTitles.map { it.lowercase() }
                            if (names.none { it.contains(needle) }) continue
                            if (!seen.add(item.uniqueId + "|" + collection.id)) continue
                            out += CollectionHit(item, label.ifBlank { collection.name })
                        }
                        onPartial?.invoke(out.toList())
                    }
                }
            }
            onPartial?.invoke(out.toList())
            out
        }

    /** A merged row for one folder: its sources' items, deduped, source order. */
    private suspend fun folderRow(collection: Collection, folder: CollectionFolder): CatalogRow? {
        val sources = folder.sources
        if (sources.isEmpty()) return null
        val rows = ArrayList<CatalogRow>(sources.size)
        coroutineScope {
            sources.map { source ->
                async {
                    val row = withContext(Dispatchers.IO) {
                        gate.withPermit { runCatching { sourceRow(collection, folder, source) }.getOrNull() }
                    }
                    if (row != null) synchronized(rows) { rows.add(row) }
                }
            }.forEach { it.await() }
        }
        if (rows.isEmpty()) return null
        val slots = sources.map { it.key }
        rows.sortBy { slots.indexOf(it.catalogId) }
        val seen = HashSet<String>()
        val merged = ArrayList<MediaItem>()
        rows.forEach { row ->
            row.items.forEach { item -> if (seen.add(item.uniqueId)) merged.add(item) }
        }
        if (merged.isEmpty()) return null
        return CatalogRow(
            providerId = "collection",
            providerName = collection.name,
            title = folder.name,
            items = merged,
            key = "coll|${collection.id}|folder|${folder.id}",
            catalogId = folder.id,
            type = rows.firstOrNull { it.type != MediaType.UNKNOWN }?.type ?: MediaType.UNKNOWN,
            rawType = "collection",
        )
    }

    private suspend fun translate(providerId: String, items: List<MediaItem>): List<MediaItem> {
        if (items.isEmpty()) return items
        if (providerId !in Translator.enabledIds()) return items
        val translated = runCatching { Translator.translateAll(items.map { it.title }) }.getOrNull()
            ?: return items
        return items.mapIndexed { i, item ->
            val t = translated.getOrNull(i) ?: item.title
            if (t != item.title) item.copy(title = t) else item
        }
    }
}
