package com.hikari.app.cs3

import android.content.Context
import com.hikari.app.data.AppStore
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import java.io.File

object Cs3ProviderSync {

    suspend fun reconcile(context: Context, store: AppStore): Boolean {
        val current = store.providers()
        val relevant = current.filter {
            it.type == ProviderType.CS3 ||
                (it.type == ProviderType.MANGA && Cs3MangaProvider.isCs3MangaId(it.id))
        }
        if (relevant.isEmpty()) return false

        val merged = LinkedHashMap<String, ProviderConfig>()
        var changed = false

        for ((path, configs) in relevant.groupBy { it.url }) {
            val file = File(path)
            val apis = if (file.exists()) {
                runCatching { Cs3PluginManager.apisFor(context, file) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            // No providers parsed → leave this plugin's configs alone.
            if (apis.isEmpty()) {
                configs.forEach { merged[it.id] = it }
                continue
            }
            val hash = file.name.hashCode()
            val previousById = configs.associateBy { it.id }
            val template = configs.first()
            val kept = LinkedHashMap<String, ProviderConfig>()
            apis.forEachIndexed { i, api ->
                val manga = Cs3MangaProvider.isMangaApi(api)
                val id = (if (manga) Cs3MangaProvider.MANGA_PREFIX else "cs3|") + hash + "|" + i
                val otherId = (if (manga) "cs3|" else Cs3MangaProvider.MANGA_PREFIX) + hash + "|" + i
                val name = api.name.ifBlank { file.name.removeSuffix(".cs3") }
                // The plugin's own declared language (MainAPI.lang — "en"
                // unless the plugin overrides it): the picker flag. Read off
                // the loaded plugin, never guessed from names; a blank load
                // keeps whatever the row already had.
                val pluginLang = runCatching { api.lang }.getOrNull()?.trim()
                    ?.takeIf { it.isNotBlank() }
                // Carry the enabled flag across a kind move: a video row the
                // user kept enabled must not come back disabled as manga.
                val prev = previousById[id] ?: previousById[otherId]
                val next = prev?.copy(
                    id = id,
                    name = name,
                    type = if (manga) ProviderType.MANGA else ProviderType.CS3,
                    lang = pluginLang ?: prev.lang,
                ) ?: ProviderConfig(
                    id = id,
                    name = name,
                    type = if (manga) ProviderType.MANGA else ProviderType.CS3,
                    url = path,
                    iconUrl = template.iconUrl,
                    extra = template.extra,
                    lang = pluginLang,
                )
                if (next != prev) changed = true
                kept[id] = next
            }
            if (kept.keys != configs.map { it.id }.toSet()) changed = true
            kept.values.forEach { merged[it.id] = it }
        }

        if (!changed) return false
        val oldIds = relevant.map { it.id }.toSet()
        store.updateProviders { all ->
            all.filterNot { it.id in oldIds } + merged.values
        }
        return true
    }
}
