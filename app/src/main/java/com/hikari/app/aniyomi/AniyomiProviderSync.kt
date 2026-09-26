package com.hikari.app.aniyomi

import android.content.Context
import com.hikari.app.data.AppStore
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import java.io.File

/**
 * Keeps Hikari's stored ANIYOMI provider configs in line with the sources an
 * installed extension actually publishes.
 *
 * One `.ext` file is one Aniyomi extension, and one extension can publish a
 * dozen sources (`Jellyfin (1)`, `Jellyfin (2)`, …) — Hikari stores one
 * [ProviderConfig] per source, keyed `aniyomi|<packageName>|<index>`. An
 * extension update can add, remove or RENAME those sources, which leaves the
 * stored rows pointing at the wrong names (or at an index that no longer
 * exists). This rebuilds them against the freshly loaded extension. Same
 * contract as [com.hikari.app.cs3.Cs3ProviderSync].
 *
 * An extension that fails to load is left ALONE: that is usually a transient
 * failure, and wiping the user's sources over a flaky load is far worse than a
 * stale name.
 */
object AniyomiProviderSync {

    /**
     * Reconciles every installed Aniyomi extension's configs. Returns true when
     * the stored provider list changed (the caller should then call
     * [com.hikari.app.providers.ProviderManager.refresh]).
     *
     * Must be called from IO — it loads each extension.
     */
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
                val next = prev?.copy(name = name) ?: ProviderConfig(
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
        // Written as a locked read-modify-write: the list this rebuilds is the
        // one in the store RIGHT NOW, so a provider another thread added while
        // this ran (an install, another engine's reconcile) is kept instead of
        // being overwritten by this snapshot (see [AppStore.updateProviders]).
        store.updateProviders { all ->
            all.filterNot { it.id in ids } + merged.values
        }
        return true
    }
}
