package com.hikari.app.providers

import com.hikari.app.cs3.Cs3MainApiProvider
import com.hikari.app.data.AppStore
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class ProviderManager(private val store: AppStore) {

    private val _providers = MutableStateFlow<List<ContentProvider>>(emptyList())
    val providers: StateFlow<List<ContentProvider>> = _providers.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val configs = store.providers()
        _providers.value = configs.mapNotNull { instantiate(it) }
    }

    fun instantiate(c: ProviderConfig): ContentProvider? = when (c.type) {
        ProviderType.STREMIO -> StremioAddon(c)
        ProviderType.UNIVERSAL -> UniversalScraper(c)
        ProviderType.CS3 -> Cs3MainApiProvider(c)
        ProviderType.HIKARI -> HikariProviderAdapter(c)
    }

    fun byId(id: String): ContentProvider? =
        _providers.value.firstOrNull { it.config.id == id }
}
