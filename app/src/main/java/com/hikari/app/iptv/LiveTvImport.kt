package com.hikari.app.iptv

import com.hikari.app.HikariApp
import com.hikari.app.data.Logs
import com.hikari.app.data.NetworkStream
import com.hikari.app.data.ProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One-time move of the playlists the user already added (the IPTV tab's own
 * Add dialog) into the Live TV engine, so everything keeps working in one
 * place: the engine treats them as M3U sources and shows them in the guide,
 * while the provider rows the engine adds keep Home, search and detail
 * working. Runs once per provider (remembered in the engine prefs); the old
 * provider rows stay untouched, so nothing is ever lost.
 */
object LiveTvImport {

    private const val KEY_IMPORTED = "importedProviders";

    fun importedIds(): Set<String> =
        runCatching {
            LiveTvEngine.store.settings()
            val prefs = LiveTvEngine.appContext()
                ?.getSharedPreferences(LiveTvStore.PREFS, android.content.Context.MODE_PRIVATE)
                ?: return emptySet()
            prefs.getStringSet(KEY_IMPORTED, emptySet()).orEmpty().toSet()
        }.getOrDefault(emptySet())

    private fun markImported(ids: Set<String>) {
        runCatching {
            LiveTvEngine.appContext()
                ?.getSharedPreferences(LiveTvStore.PREFS, android.content.Context.MODE_PRIVATE)
                ?.edit()?.putStringSet(KEY_IMPORTED, ids)?.apply()
        }
    }

    /** Imports every plain playlist provider once. Returns how many were added. */
    suspend fun importExistingProviders(app: HikariApp): Int = withContext(Dispatchers.IO) {
        runCatching { LiveTvEngine.init(app) }
        val done = importedIds().toMutableSet()
        var added = 0
        val providers = runCatching { app.store.providers() }.getOrDefault(emptyList())
        providers.forEach { p ->
            if (p.type != ProviderType.IPTV) return@forEach
            if (p.id in done) return@forEach
            if (p.url.startsWith(LiveTvEngine.BRIDGE_SCHEME)) {
                done += p.id
                return@forEach
            }
            // Torrents, bare streams and JSON playlists stay where they are —
            // the engine only takes real M3U-style playlists.
            if (NetworkStream.isStream(p)) {
                done += p.id
                return@forEach
            }
            if (NetworkStream.isTorrentLink(p.url)) {
                done += p.id
                return@forEach
            }
            val url = p.url.trim()
            val isRemote = url.startsWith("http://") || url.startsWith("https://")
            val isFile = !isRemote && java.io.File(url).isFile
            if (!isRemote && !isFile) {
                done += p.id
                return@forEach
            }
            // JSON playlist configs ("{"name",…}) are not M3U — skip those too.
            if (isRemote) {
                val head = runCatching {
                    com.hikari.app.net.Http.fetchStringRobust(url).getOrNull()?.take(4000)
                }.getOrNull().orEmpty()
                val t = head.trimStart()
                if (t.startsWith("{") && ("tmdb" in t.lowercase() || "\"channels\"" in t)) {
                    done += p.id
                    return@forEach
                }
            }
            val name = p.name.ifBlank { "Playlist" }
            val result = runCatching { LiveTvEngine.addM3u(name, url) }
            if (result.isSuccess) {
                added++
                Logs.log("LiveTv", "imported existing playlist: $name")
            } else {
                Logs.log("LiveTv", "import skipped ${p.name}: ${result.exceptionOrNull()?.message}")
            }
            done += p.id
        }
        markImported(done)
        added
    }
}
