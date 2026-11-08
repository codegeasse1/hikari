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
            val sources = folder.sources.distinctBy { it.key }
            if (sources.isEmpty()) {
                send(emptyList())
                return@flow
            }
            val placed = HashMap<Int, CatalogRow>()
            val work = com.hikari.app.work.BackgroundWork.begin("Loading " + folder.name)
            try {
                coroutineScope {
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
                }
                publish(this@flow, placed, sources.size, force = true)
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
            coroutineScope {
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
            }
            publish(this@flow, placed, collection.folders.size, force = true)
        } finally {
            com.hikari.app.work.BackgroundWork.end(work)
        }
    }

    /** The folder's rows in one go (a folder page paints them all together). */
    suspend fun folderRowsOnce(collection: Collection, folder: CollectionFolder): List<CatalogRow> =
        folderRows(collection, folder).lastOrNull().orEmpty()

    /** Every folder's every catalog in one go — the "All" tab of a collection
     *  whose folders are browsed as a tab strip, which has to paint its rows
     *  together for the same reason a folder page does. */
    suspend fun allRowsOnce(collection: Collection): List<CatalogRow> =
        allRows(collection).lastOrNull().orEmpty()

    fun pickRows(collection: Collection): Flow<List<CatalogRow>> {
        val only = collection.folders.singleOrNull()
        return if (only != null) folderRows(collection, only) else collectionRows(collection)
    }

    fun allRows(collection: Collection): Flow<List<CatalogRow>> = channelFlow flow@{
        val slots = ArrayList<Pair<CollectionFolder, CatalogSource>>()
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
            coroutineScope {
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
            }
            publish(this@flow, placed, slots.size, force = true)
        } finally {
            com.hikari.app.work.BackgroundWork.end(work)
        }
    }

    private suspend fun publish(
        scope: ProducerScope<List<CatalogRow>>,
        placed: Map<Int, CatalogRow>,
        size: Int,
        force: Boolean = false,
    ) {
        val ordered = synchronized(placed) { (0 until size).mapNotNull { placed[it] } }
        if (ordered.isNotEmpty() || force) scope.send(ordered)
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
            runCatching {
                com.hikari.app.data.ContentRepository.loadCatalogPage(provider, ref, 1)
            }.getOrDefault(emptyList())
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

    suspend fun searchTitles(
        query: String,
        limit: Int = 24,
        onPartial: ((List<CollectionHit>) -> Unit)? = null,
    ): List<CollectionHit> =
        searchIn(query, emptySet(), limit, onPartial)

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
