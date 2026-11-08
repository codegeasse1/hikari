package com.hikari.app.data

import com.hikari.app.HikariApp
import java.util.concurrent.ConcurrentHashMap

object IptvMark {

    /** How every playlist provider's id begins. */
    const val ID_PREFIX = "iptv|"

    private val known = ConcurrentHashMap<String, Boolean>()

    fun isIptvProvider(providerId: String): Boolean {
        val id = providerId.trim()
        if (id.isEmpty()) return false
        if (id.startsWith(ID_PREFIX)) return true
        if (known.containsKey(id)) return true
        val hit = runCatching {
            HikariApp.instance.providers.byId(id)?.config?.type
        }.getOrNull() == ProviderType.IPTV
        if (hit) known[id] = true
        return hit
    }

    /** True when [item] is a channel of an installed playlist. */
    fun of(item: MediaItem): Boolean = isIptvProvider(item.providerId)

    /** Drops the memoised ids (nothing in the app needs this yet; it exists so a
     *  future provider edit has one obvious thing to call). */
    fun forget() {
        known.clear()
    }
}
