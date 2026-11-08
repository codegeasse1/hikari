package com.hikari.app.aniyomi

import android.content.Context
import com.hikari.app.data.AppStore
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import java.io.File

object AniyomiProviderSync {

    suspend fun reconcile(context: Context, store: AppStore): Boolean {
        val current = store.providers()
        val mine = current.filter { it.type == ProviderType.ANIYOMI }
        if (mine.isEmpty()) return false

        val merged = LinkedHashMap<String, ProviderConfig>()
        var changed = false

        for ((path, configs) in mine.groupBy { it.url }) {
            val file = File(path)
            val ext = if (file.exists()) {
                runCatching { AniyomiExtensionManager.extensionOf(context, file) }.getOrNull()
            } else {
                null
            }
            // No sources parsed → leave this extension's configs alone.
            if (ext == null || ext.sources.isEmpty()) {
                configs.forEach { merged[it.id] = it }
                continue
            }

            val previousById = configs.associateBy { it.id }
            val template = configs.first()
            val kept = LinkedHashMap<String, ProviderConfig>()
            ext.sources.indices.forEach { i ->
                val id = "aniyomi|${ext.pkgName}|$i"
                val name = ext.labels.getOrNull(i) ?: ext.name
                val prev = previousById[id]
                val freshLang = runCatching { ext.sources[i].lang }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
                val next = prev?.copy(name = name, lang = prev.lang?.takeIf { it.isNotBlank() } ?: freshLang) ?: ProviderConfig(
                    id = id,
                    name = name,
                    type = ProviderType.ANIYOMI,
                    url = path,
                    iconUrl = template.iconUrl,
                    extra = template.extra,
                )
                if (next != prev) changed = true
                kept[id] = next
            }
            if (kept.keys != configs.map { it.id }.toSet()) changed = true
            kept.values.forEach { merged[it.id] = it }
        }

        if (!changed) return false
        val ids = mine.map { it.id }.toSet()
        store.updateProviders { all ->
            all.filterNot { it.id in ids } + merged.values
        }
        return true
    }
}
