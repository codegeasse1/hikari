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
        // Video rows AND already-routed manga rows: a plugin whose MainAPI
        // serves chapters/pages instead of video (see Cs3MangaProvider) is
        // registered as a MANGA row (`manga|cs3|…`), so the manga tab — not
        // Home — lists it. Both spellings group by file below, so a plugin
        // that changed kind (or was installed before routing existed) moves
        // to the right tab on the next sync instead of sticking to the wrong
        // one.
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
                // Manga-capable MainAPI → manga tab (MANGA row), everything
                // else stays a video row. The id carries the kind, so a kind
                // change registers under a new id and drops the old one.
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
        // Written as a locked read-modify-write: the list this rebuilds is the
        // one in the store RIGHT NOW, so a provider another thread added while
        // this ran (an install, another engine's reconcile) is kept instead of
        // being overwritten by this snapshot (see [AppStore.updateProviders]).
        store.updateProviders { all ->
            all.filterNot { it.id in oldIds } + merged.values
        }
        return true
    }
}
