package com.hikari.app.ui

import com.hikari.app.cs3.Cs3MainApiProvider
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import com.hikari.app.net.Http
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Fallback extension icons. Stremio addons store their manifest `icon` at
 * install time now, but addons installed before that have none — fetch it
 * lazily from the manifest (cached). CS3 plugins without a repo icon fall back
 * to the provider's own mainUrl favicon (the same Google-favicon trick the
 * plugin repos themselves use).
 */
object ExtensionIcons {

    private val stremioCache = ConcurrentHashMap<String, String?>()

    suspend fun forConfig(config: ProviderConfig): String? {
        if (!config.iconUrl.isNullOrBlank()) return config.iconUrl
        return when (config.type) {
            ProviderType.STREMIO -> {
                stremioCache[config.id]?.let { return it }
                val icon = runCatching {
                    val base = config.url.trimEnd('/')
                    val text = Http.getString("$base/manifest.json") ?: return@runCatching null
                    JSONObject(text).optString("icon").ifBlank { null }
                }.getOrNull()
                // ConcurrentHashMap forbids null values — only cache hits.
                if (icon != null) stremioCache[config.id] = icon
                icon
            }
            ProviderType.CS3 -> Cs3MainApiProvider.iconFallback(config)
            else -> null
        }
    }
}
