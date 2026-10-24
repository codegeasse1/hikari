package com.hikari.app.hiki

import com.hikari.app.HikariApp
import com.hikari.app.data.ProviderConfig
import com.hikari.ext.HikariProvider
import java.io.File

/**
 * Resolves a [HikariProvider] instance for a HIKARI provider config:
 *  - config.url blank      → built-in provider: extra is the fully-qualified
 *                            class name on the app classpath (the bundled YTS
 *                            demo).
 *  - config.url is a .hiki → external extension jar loaded via
 *                            [HikariPluginManager]; extra encodes the optional
 *                            source URL plus the provider index:
 *                            "<sourceUrl>|<index>" (or just "<index>").
 */
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
