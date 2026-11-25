package com.hikari.app.hiki

import com.hikari.app.HikariApp
import com.hikari.app.data.ProviderConfig
import com.hikari.ext.HikariProvider
import java.io.File

object HikariRuntime {

    fun providerFor(config: ProviderConfig): HikariProvider? {
        val path = config.url
        if (path.isBlank()) {
            // Clone-site: read the original's config (see CloneHosts).
            val className = com.hikari.app.data.CloneHosts.effectiveExtra(config)?.takeIf { it.isNotBlank() } ?: return null
            return try {
                Class.forName(className).getDeclaredConstructor().newInstance() as? HikariProvider
            } catch (t: Throwable) {
                null
            }
        }
        val file = File(path)
        if (!file.exists()) return null
        // Clone-site: read the original's config (see CloneHosts).
        val index = com.hikari.app.data.CloneHosts.effectiveExtra(config)?.substringAfterLast('|')?.toIntOrNull() ?: 0
        return HikariPluginManager.providersFor(HikariApp.instance, file).getOrNull(index)
    }
}
