package com.hikari.app.hiki

import com.hikari.app.HikariApp
import com.hikari.app.data.ProviderConfig
import com.hikari.ext.HikariProvider
import java.io.File

object HikariRuntime {

    fun providerFor(config: ProviderConfig): HikariProvider? {
        val path = config.url
        if (path.isBlank()) {
            val className = config.extra?.takeIf { it.isNotBlank() } ?: return null
            return try {
                Class.forName(className).getDeclaredConstructor().newInstance() as? HikariProvider
            } catch (t: Throwable) {
                null
            }
        }
        val file = File(path)
        if (!file.exists()) return null
        val index = config.extra?.substringAfterLast('|')?.toIntOrNull() ?: 0
        return HikariPluginManager.providersFor(HikariApp.instance, file).getOrNull(index)
    }
}
