package com.hikari.app.providers

import com.hikari.app.data.AppStore
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ProviderManager(private val store: AppStore) {

    private val _providers = MutableStateFlow<List<ContentProvider>>(emptyList())
    val providers: StateFlow<List<ContentProvider>> = _providers.asStateFlow()

    suspend fun refresh() {
        val configs = store.providers()
        _providers.value = configs.mapNotNull { instantiate(it) }
    }

    fun instantiate(c: ProviderConfig): ContentProvider? = when (c.type) {
        ProviderType.STREMIO -> StremioAddon(c)
        ProviderType.UNIVERSAL -> UniversalScraper(c)
        ProviderType.CS3 -> null // .cs3 plugin loading lands in Stage 2
    }

    fun byId(id: String): ContentProvider? =
        _providers.value.firstOrNull { it.config.id == id }
}
