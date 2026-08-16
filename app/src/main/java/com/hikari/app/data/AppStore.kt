package com.hikari.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
