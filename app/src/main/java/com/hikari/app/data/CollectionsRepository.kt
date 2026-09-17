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

    /** One row per source of [folder], in the folder's own source order. */
    fun folderRows(collection: Collection, folder: CollectionFolder): Flow<List<CatalogRow>> =
        channelFlow flow@{
            if (folder.sources.isEmpty()) {
                send(emptyList())
                return@flow
            }
            val placed = HashMap<Int, CatalogRow>()
            val work = com.hikari.app.work.BackgroundWork.begin("Loading " + folder.name)
            try {
                folder.sources.forEachIndexed { i, source ->
                    launch {
                        val row = withContext(Dispatchers.IO) {
                            gate.withPermit { runCatching { sourceRow(collection, folder, source) }.getOrNull() }
                        }
                        synchronized(placed) {
                            if (row != null && row.items.isNotEmpty()) placed[i] = row
                        }
                        publish(this@flow, placed, folder.sources.size)
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
        if (source.kind == CatalogSourceKind.TMDB) {
            val preset = TmdbPresets.byKey(source.tmdbPreset) ?: return null
            val items = withTimeoutOrNull(perCatalogTimeoutMs) { TmdbPresets.page(preset, 1) }
                .orEmpty().distinctBy { it.uniqueId }
            if (items.isEmpty()) return null
            return CatalogRow(
                providerId = "tmdb",
                providerName = breadcrumb,
                title = preset.name,
                items = items,
                key = "coll|${collection.id}|${folder.id}|${source.key}",
                catalogId = source.key,
                type = preset.kind,
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
