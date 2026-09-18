package com.hikari.app.providers

import com.hikari.app.cs3.Cs3MainApiProvider
import com.hikari.app.data.AppStore
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
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
        ProviderType.NUVIO -> com.hikari.app.nuvio.NuvioScraper(c)
        ProviderType.SKYSTREAM -> com.hikari.app.skystream.SkyStreamProvider(c)
        ProviderType.ANIYOMI -> com.hikari.app.aniyomi.AniyomiProvider(c)
    }

    fun byId(id: String): ContentProvider? =
        _providers.value.firstOrNull { it.config.id == id }
}

/**
 * Serialises the calls Hikari makes into ONE extension instance.
 *
 * Every extension — a CloudStream `.cs3` plugin, a Hikari `.hiki` bundle, a
 * Nuvio script — is an object we do not control, and most of them keep mutable
 * state while a search runs. Two calls in flight at once (the origin pass and
 * the cross-repo pass, or a meta/episode fetch landing while a stream lookup is
 * still walking the same object) on the same instance is how an extension's own
 * unsynchronised `ArrayList` gets corrupted: the recorded crash
 * `ArrayIndexOutOfBoundsException: length=49; index=49 at java.util.ArrayList.add`
 * inside a bridge provider, thrown from an extension's own coroutine, is exactly
 * that — two `add`s racing on one list.
 *
 * Holding a per-provider lock costs nothing when the calls are already
 * sequential, and it removes the app's contribution to that race: Hikari never
 * has two calls inside the same extension at the same time. A wedged extension
 * cannot block the others (a lock each), and a call that throws simply releases
 * it.
 */
object ProviderGate {
    private val locks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    private fun lockFor(id: String) = locks.computeIfAbsent(id) { kotlinx.coroutines.sync.Mutex() }

    suspend fun <T> withProvider(id: String, block: suspend () -> T): T =
        lockFor(id).withLock { block() }
}
