package com.hikari.app.cs3

import android.content.Context
import com.hikari.app.data.AppStore
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import java.io.File

/**
 * Keeps Hikari's stored CS3 provider configs in sync with the providers a
 * CloudStream plugin actually registers.
 *
 * Why this exists: CloudStream plugins can expose a settings screen that changes
 * WHICH sub-providers get registered — SKTech, for example, shows a checkbox
 * list (BIG BOSS / SONY LIVE / JTV+ / JIOSTAR …) and only registers the ticked
 * ones. Hikari stores one [ProviderConfig] per registered MainAPI, keyed by
 * index ("cs3|<fileHash>|<i>"), so after such a change the stored configs no
 * longer line up: Home would show the wrong names, or entries whose index no
 * longer exists. This rebuilds them against the freshly loaded plugin.
 *
 * A plugin that registers NOTHING is left untouched: that is usually a
 * transient load failure (no network, still loading), and wiping the user's
 * providers over a flaky load would be far worse than a stale name.
 */
object Cs3ProviderSync {

    /**
     * Reconciles every installed CS3 plugin's configs. Returns true when the
     * stored provider list changed (the caller should then call
     * [com.hikari.app.providers.ProviderManager.refresh]).
     *
     * Must be called from IO — it loads each plugin.
     */
    suspend fun reconcile(context: Context, store: AppStore): Boolean {
        val current = store.providers()
        val cs3 = current.filter { it.type == ProviderType.CS3 }
        if (cs3.isEmpty()) return false

        val merged = LinkedHashMap<String, ProviderConfig>()
        var changed = false

        for ((path, configs) in cs3.groupBy { it.url }) {
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
                val id = "cs3|$hash|$i"
                val name = api.name.ifBlank { file.name.removeSuffix(".cs3") }
                val prev = previousById[id]
                val next = prev?.copy(name = name) ?: ProviderConfig(
                    id = id,
                    name = name,
                    type = ProviderType.CS3,
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
        val cs3Ids = cs3.map { it.id }.toSet()
        val out = ArrayList<ProviderConfig>(current.size)
        for (c in current) if (c.id !in cs3Ids) out.add(c)
        out.addAll(merged.values)
        store.saveProviders(out)
        return true
    }
}
