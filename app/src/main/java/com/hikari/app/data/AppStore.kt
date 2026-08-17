package com.hikari.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hikari.app.ui.theme.HikariThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.hkDataStore by preferencesDataStore(name = "hikari")

class AppStore(private val ctx: Context) {

    private val store get() = ctx.hkDataStore

    private object K {
        val PROVIDERS = stringPreferencesKey("providers")
        val FAVORITES = stringPreferencesKey("favorites")
        val CS3_REPOS = stringPreferencesKey("cs3Repos")
        val SITES = stringPreferencesKey("sites")
        val THEME = stringPreferencesKey("theme")
    }

    fun themeFlow(): Flow<String> =
        store.data.map { it[K.THEME] ?: HikariThemeMode.DARK.key }

    suspend fun theme(): String = themeFlow().first()

    suspend fun setTheme(key: String) {
        store.edit { it[K.THEME] = key }
    }

    fun providersFlow(): Flow<List<ProviderConfig>> =
        store.data.map { parseProviders(it[K.PROVIDERS]) }

    suspend fun providers(): List<ProviderConfig> = providersFlow().first()

    suspend fun saveProviders(list: List<ProviderConfig>) {
        store.edit { it[K.PROVIDERS] = encodeProviders(list) }
    }

    suspend fun addProvider(c: ProviderConfig) {
        saveProviders(providers().filter { it.id != c.id } + c)
    }

    suspend fun removeProvider(id: String) =
        saveProviders(providers().filter { it.id != id })

    suspend fun setEnabled(id: String, enabled: Boolean) {
        saveProviders(providers().map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun reposFlow(): Flow<List<Cs3Repo>> =
        store.data.map { parseRepos(it[K.CS3_REPOS]) }

    suspend fun repos(): List<Cs3Repo> = reposFlow().first()

    suspend fun addCs3Repo(r: Cs3Repo) {
        saveRepos(repos().filter { it.url != r.url } + r)
    }

    suspend fun removeCs3Repo(url: String) {
        saveRepos(repos().filter { it.url != url })
    }

    private suspend fun saveRepos(list: List<Cs3Repo>) {
        store.edit { it[K.CS3_REPOS] = encodeRepos(list) }
    }

    fun favoritesFlow(): Flow<List<MediaItem>> =
        store.data.map { parseMedia(it[K.FAVORITES]) }

    suspend fun favorites(): List<MediaItem> = favoritesFlow().first()

    suspend fun addFavorite(m: MediaItem) {
        val list = favorites().filter { it.uniqueId != m.uniqueId } + m
        store.edit { it[K.FAVORITES] = encodeMedia(list) }
    }

    suspend fun removeFavorite(id: String) {
        store.edit { it[K.FAVORITES] = encodeMedia(favorites().filter { f -> f.uniqueId != id }) }
    }

    fun sitesFlow(): Flow<List<Site>> =
        store.data.map { parseSites(it[K.SITES]) }

    suspend fun sites(): List<Site> = sitesFlow().first()

    suspend fun addSite(s: Site) {
        store.edit { it[K.SITES] = encodeSites(sites().filter { it.url != s.url } + s) }
    }

    suspend fun removeSite(url: String) {
        store.edit { it[K.SITES] = encodeSites(sites().filter { it.url != url }) }
    }

    private fun encodeSites(list: List<Site>): String {
        val arr = JSONArray()
        for (s in list) {
            arr.put(JSONObject().put("name", s.name).put("url", s.url))
        }
        return arr.toString()
    }

    private fun parseSites(s: String?): List<Site> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url")
                if (url.isBlank()) null
                else Site(name = o.optString("name").ifBlank { url }, url = url)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun clearAll() {
        store.edit { it.clear() }
    }

    private fun encodeProviders(list: List<ProviderConfig>): String {
        val arr = JSONArray()
        for (c in list) {
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("type", c.type.name)
                    .put("url", c.url)
                    .put("iconUrl", c.iconUrl ?: "")
                    .put("enabled", c.enabled)
                    .put("extra", c.extra ?: "")
            )
        }
        return arr.toString()
    }

    private fun parseProviders(s: String?): List<ProviderConfig> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                ProviderConfig(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    type = runCatching { ProviderType.valueOf(o.optString("type")) }
                        .getOrDefault(ProviderType.STREMIO),
                    url = o.optString("url"),
                    iconUrl = o.optString("iconUrl").ifBlank { null },
                    enabled = o.optBoolean("enabled", true),
                    extra = o.optString("extra").ifBlank { null },
                )
            }.filter { it.id.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun encodeRepos(list: List<Cs3Repo>): String {
        val arr = JSONArray()
        for (r in list) {
            arr.put(
                JSONObject()
                    .put("url", r.url)
                    .put("name", r.name)
                    .put("description", r.description)
            )
        }
        return arr.toString()
    }

    private fun parseRepos(s: String?): List<Cs3Repo> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Cs3Repo(
                    url = o.optString("url"),
                    name = o.optString("name").ifBlank { o.optString("url") },
                    description = o.optString("description"),
                )
            }.filter { it.url.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun encodeMedia(list: List<MediaItem>): String {
        val arr = JSONArray()
        for (m in list) {
            arr.put(
                JSONObject()
                    .put("pid", m.providerId)
                    .put("id", m.id)
                    .put("title", m.title)
                    .put("type", m.type.name)
                    .put("poster", m.posterUrl ?: "")
                    .put("year", m.year ?: 0)
                    .put("overview", m.overview ?: "")
            )
        }
        return arr.toString()
    }

    private fun parseMedia(s: String?): List<MediaItem> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                MediaItem(
                    providerId = o.optString("pid"),
                    id = o.optString("id"),
                    title = o.optString("title"),
                    type = runCatching { MediaType.valueOf(o.optString("type")) }
                        .getOrDefault(MediaType.UNKNOWN),
                    posterUrl = o.optString("poster").ifBlank { null },
                    year = o.optInt("year", 0).takeIf { it > 0 },
                    overview = o.optString("overview").ifBlank { null },
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
