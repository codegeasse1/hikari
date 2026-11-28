package com.hikari.desktop.engine

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.prefs.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Installed engine providers (all ecosystems) + fan-out aggregation.
 * Backed by java.util.prefs like the other desktop stores — headless-safe.
 */
class ProviderRegistry(
    private val prefs: Preferences = Preferences.userRoot().node("hikari-desktop/engines"),
) {
    private val gson = Gson()

    private val host = JavetJsHost()
    private val engines: Map<EngineKind, Engine> = mapOf(
        EngineKind.STREMIO to StremioEngineAdapter(),
        EngineKind.NUVIO to NuvioEngine(host) { tmdbKey() },
        EngineKind.ANYMEX to AnymexEngine(host),
        EngineKind.SORA to SoraEngine(host),
        EngineKind.SKYSTREAM to SkyStreamEngine(host),
        EngineKind.ZANGETSU to ZangetsuEngine(host),
        EngineKind.VEGA to VegaEngine(host),
        EngineKind.IPTV to IptvEngine { tmdbKey() },
    )

    fun engineFor(kind: EngineKind): Engine? = engines[kind]

    fun all(): List<EngineProvider> {
        val raw = runCatching { prefs.get("providers.v1", "[]") }.getOrDefault("[]")
        return runCatching {
            gson.fromJson<List<EngineProvider>>(raw, object : TypeToken<List<EngineProvider>>() {}.type)
        }.getOrDefault(emptyList())
    }

    fun enabled(): List<EngineProvider> = all().filter { it.enabled }

    private fun save(list: List<EngineProvider>) {
        runCatching { prefs.put("providers.v1", gson.toJson(list)) }
    }

    fun tmdbKey(): String = runCatching { prefs.get("tmdbKey", "") }.getOrDefault("")

    fun setTmdbKey(value: String) {
        runCatching { prefs.put("tmdbKey", value.trim()) }
    }

    fun upsert(provider: EngineProvider) {
        val list = all().filterNot { it.id == provider.id } + provider
        save(list)
    }

    fun remove(id: String) {
        save(all().filterNot { it.id == id })
    }

    fun setEnabled(id: String, enabled: Boolean) {
        save(all().map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun setNote(id: String, note: String?) {
        save(all().map { if (it.id == id) it.copy(note = note) else it })
    }

    data class Shelf(val provider: EngineProvider, val catalog: EngineCatalog, val items: List<EngineMedia>)

    /** Home shelves across every enabled engine (bounded, failure-isolated). */
    suspend fun homeShelves(maxProviders: Int = 8, maxCatalogs: Int = 3): List<Shelf> =
        withContext(Dispatchers.IO) {
            val providers = enabled().take(maxProviders)
            coroutineScope {
                providers.map { p ->
                    async {
                        val engine = engines[p.kind] ?: return@async emptyList<Shelf>()
                        val catalogs = runCatching {
                            withTimeoutOrNull(30_000) { engine.catalogs(p) }.orEmpty()
                        }.getOrDefault(emptyList()).take(maxCatalogs)
                        catalogs.mapNotNull { c ->
                            val items = runCatching {
                                withTimeoutOrNull(45_000) { engine.catalog(p, c, 1) }.orEmpty()
                            }.getOrDefault(emptyList()).take(20)
                            if (items.isEmpty()) null else Shelf(p, c, items)
                        }
                    }
                }.mapNotNull { runCatching { it.await() }.getOrNull() }.flatten()
            }
        }

    /** Search across every enabled engine (bounded, failure-isolated). */
    suspend fun searchAll(query: String): List<Pair<EngineProvider, List<EngineMedia>>> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val providers = enabled()
            coroutineScope {
                providers.map { p ->
                    async {
                        val engine = engines[p.kind] ?: return@async null
                        val items = runCatching {
                            withTimeoutOrNull(45_000) { engine.search(p, query, 1) }.orEmpty()
                        }.getOrDefault(emptyList()).take(50)
                        if (items.isEmpty()) null else p to items
                    }
                }.mapNotNull { runCatching { it.await() }.getOrNull() }
            }
        }

    /** Streams for one media across every enabled engine (bounded, failure-isolated). */
    suspend fun streamsFor(
        media: EngineMedia,
        episode: EngineEpisode?,
    ): List<Pair<EngineProvider, List<EngineStream>>> = withContext(Dispatchers.IO) {
        val providers = enabled()
        coroutineScope {
            providers.map { p ->
                async {
                    val engine = engines[p.kind] ?: return@async null
                    val streams = runCatching {
                        withTimeoutOrNull(90_000) { engine.streams(p, media, episode) }.orEmpty()
                    }.getOrDefault(emptyList())
                    if (streams.isEmpty()) null else p to streams
                }
            }.mapNotNull { runCatching { it.await() }.getOrNull() }
        }
    }
}
