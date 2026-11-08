package com.hikari.app.kotatsu

import com.hikari.app.data.AppStore
import com.hikari.app.data.Cs3Repo
import com.hikari.app.data.Cs3RepoPlugin
import com.hikari.app.data.Logs
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import com.hikari.app.data.RepoKind
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import java.util.concurrent.ConcurrentHashMap

object KotatsuRegistry {

    const val PREFIX = "manga|kotatsu|"

    /** The upstream repository the bundled parsers are built from. Stored as
     *  the default Kotatsu repo so the folder reads exactly like every other
     *  engine's: a repo card (tap = its sources), an Add button for more. */
    const val OFFICIAL_REPO_URL = "https://github.com/KotatsuApp/kotatsu-parsers"
    const val OFFICIAL_REPO_NAME = "Kotatsu Parsers"
    const val OFFICIAL_REPO_DESC = "Official Kotatsu manga sources (bundled parsers)"

    /** Scheme every repo entry installs from — no download, the parser ships
     *  inside the app (`kotatsu://MANGADEX`, …). */
    const val PLUGIN_SCHEME = "kotatsu://"

    /** Prefix for third-party repo entries whose parser code is NOT bundled
     *  in this app (`kotatsu://site/<slug>`). They are listed so every repo
     *  shows its own sources, but installing one fails honestly instead of
     *  creating a row that could never run. */
    const val UNBUNDLED_PREFIX = "kotatsu://site/"

    fun pluginUrlOf(enumName: String): String = PLUGIN_SCHEME + enumName

    fun enumNameOfPluginUrl(url: String): String? =
        url.removePrefix(PLUGIN_SCHEME).trim().takeIf { it.isNotBlank() && url.startsWith(PLUGIN_SCHEME) }

    fun isKotatsuId(id: String): Boolean = id.startsWith(PREFIX)

    fun enumNameOf(id: String): String = id.removePrefix(PREFIX)

    /** True for a repo entry no bundled parser can run (see [UNBUNDLED_PREFIX]). */
    fun isUnbundledPluginUrl(url: String): Boolean = url.startsWith(UNBUNDLED_PREFIX)

    /** One entry of a third-party Kotatsu parser list (`{"sites": [...]}` —
     *  the shape community repos like the kototoro / hany18h lists publish:
     *  a site descriptor, not code — the extraction code lives in the fork
     *  that published it). */
    data class KotatsuSiteEntry(
        val name: String,
        val lang: String = "",
        val url: String = "",
        val iconUrl: String? = null,
        val contentType: String = "",
        val isBroken: Boolean = false,
    )

    fun parseSiteIndex(text: String): List<KotatsuSiteEntry>? {
        val t = text.trim()
        if (t.isEmpty()) return null
        fun entryOf(o: org.json.JSONObject): KotatsuSiteEntry? {
            val name = o.optString("name").trim()
            if (name.isBlank()) return null
            return KotatsuSiteEntry(
                name = name,
                lang = o.optString("lang").trim(),
                url = o.optString("url").trim(),
                iconUrl = o.optString("icon").trim().ifBlank { null },
                contentType = o.optString("contentType").trim(),
                isBroken = o.optBoolean("isBroken", false),
            )
        }
        if (t.startsWith("{")) {
            val o = runCatching { org.json.JSONObject(t) }.getOrNull() ?: return null
            val arr = o.optJSONArray("sites") ?: return null
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::entryOf) }
        }
        if (t.startsWith("[")) {
            val arr = runCatching { org.json.JSONArray(t) }.getOrNull() ?: return null
            val objs = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            if (objs.isEmpty()) return null
            val apkish = objs.any {
                it.optString("apk").isNotBlank() || it.optString("packageName").isNotBlank()
            }
            if (apkish) return null
            val kotatsuish = objs.any {
                it.optString("contentType").isNotBlank() ||
                    (it.optString("icon").isNotBlank())
            }
            if (!kotatsuish) return null
            return objs.mapNotNull(::entryOf)
        }
        return null
    }

    private val parserCache = ConcurrentHashMap<String, MangaParser>()

    fun allSources(): List<MangaParserSource> =
        runCatching { MangaParserSource.entries.toList() }.getOrDefault(emptyList())

    fun parserOf(enumName: String): MangaParser? {
        val src = runCatching { MangaParserSource.valueOf(enumName) }.getOrNull() ?: return null
        return parserCache.getOrPut(enumName) {
            KotatsuContext.newParserInstance(src)
        }
    }

    fun configFor(src: MangaParserSource): ProviderConfig {
        return ProviderConfig(
            id = PREFIX + src.name,
            name = src.title + " (Kotatsu)",
            type = ProviderType.MANGA,
            url = pluginUrlOf(src.name),
            enabled = true,
            extra = "kotatsu",
            nsfw = (src.contentType == ContentType.HENTAI).takeIf { it },
        )
    }

    fun repoPlugins(): List<Cs3RepoPlugin> =
        allSources().filter { !it.isBroken }.map { src ->
            Cs3RepoPlugin(
                name = src.title,
                description = src.locale.trim().uppercase().ifBlank { "??" } +
                    " · " + src.contentType.name.lowercase(),
                url = pluginUrlOf(src.name),
                iconUrl = null,
                version = 1,
                contentKind = "manga",
                pluginLang = src.locale.trim().ifBlank { null },
                pkg = "kotatsu." + src.name.lowercase(),
                nsfw = src.contentType == ContentType.HENTAI,
            )
        }.sortedBy { it.name.lowercase() }

    fun matchBundled(name: String, lang: String): MangaParserSource? {
        val all = allSources()
        if (name.isNotBlank()) {
            runCatching { MangaParserSource.valueOf(name.trim()) }.getOrNull()?.let { return it }
        }
        val want = normSiteName(name)
        if (want.isBlank()) return null
        val cands = all.filter { normSiteName(it.title) == want }
        if (cands.isEmpty()) return null
        val l = lang.trim().lowercase()
        if (l.isBlank() || l == "all") return cands.first()
        return cands.firstOrNull { s ->
            val sl = s.locale.trim().lowercase()
            sl == l || sl.startsWith(l) || l.startsWith(sl)
        } ?: cands.first()
    }

    private fun normSiteName(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() }

    private fun siteSlug(e: KotatsuSiteEntry): String =
        (normSiteName(e.name).ifBlank { "source" } + "-" +
            e.lang.trim().lowercase().ifBlank { "all" }).take(80)

    fun pluginsForRepo(indexText: String): List<Cs3RepoPlugin> {
        val sites = parseSiteIndex(indexText) ?: return emptyList()
        return sites.filter { !it.isBroken && it.name.isNotBlank() }.map { e ->
            val src = matchBundled(e.name, e.lang)
            val ct = e.contentType.trim()
            val hentai = ct.startsWith("HENTAI", ignoreCase = true)
            if (src != null) {
                val desc = listOfNotNull(
                    e.lang.trim().uppercase().ifBlank { null },
                    ct.lowercase().ifBlank { null },
                ).joinToString(" · ").ifBlank { "Kotatsu source" }
                Cs3RepoPlugin(
                    name = e.name,
                    description = desc,
                    url = pluginUrlOf(src.name),
                    iconUrl = e.iconUrl,
                    version = 1,
                    contentKind = "manga",
                    pkg = "kotatsu." + src.name.lowercase(),
                    nsfw = hentai || src.contentType == ContentType.HENTAI,
                )
            } else {
                com.hikari.app.kotatsu.fork.ForkRegistry.pluginForEntry(
                    entryName = e.name,
                    entryLang = e.lang,
                    entryIcon = e.iconUrl,
                    entryContentType = ct,
                    entryNsfw = hentai,
                ) ?: run {
                    val desc = listOfNotNull(
                        e.lang.trim().uppercase().ifBlank { null },
                        ct.lowercase().ifBlank { null },
                    ).joinToString(" · ").ifBlank { "Kotatsu source" } + " · not bundled"
                    Cs3RepoPlugin(
                        name = e.name,
                        description = desc,
                        url = UNBUNDLED_PREFIX + siteSlug(e),
                        iconUrl = e.iconUrl,
                        version = 1,
                        contentKind = "manga",
                        pkg = "kotatsu.site." + siteSlug(e),
                        nsfw = hentai,
                    )
                }
            }
        }
    }

    /** Drops the cached parser + bridge for [enumName] (see
     *  [com.hikari.app.providers.ExtensionRecovery]): the next call builds
     *  them fresh instead of reusing a wedged instance. */
    fun evict(enumName: String) {
        parserCache.remove(enumName)
        KotatsuBridge.evict(enumName)
    }

    /** Drops a wedged fork parser/bridge (see [evict]): fork manga rows cache
     *  in [com.hikari.app.kotatsu.fork.ForkRegistry]. */
    fun evictFork(id: String) {
        com.hikari.app.kotatsu.fork.ForkRegistry.evictForkManga(id)
    }

    /** Registers the official repo once (seed path: a user who deletes it
     *  keeps it deleted — see [AppStore.seedCs3Repo]). */
    suspend fun seedDefaults(store: AppStore) {
        runCatching {
            store.seedCs3Repo(
                Cs3Repo(
                    OFFICIAL_REPO_URL,
                    OFFICIAL_REPO_NAME,
                    OFFICIAL_REPO_DESC,
                    RepoKind.KOTATSU,
                )
            )
        }
    }

    suspend fun pruneSeeded(store: AppStore) {
        if (runCatching { store.kotatsuPruned() }.getOrDefault(false)) return
        val have = runCatching { store.providers() }.getOrDefault(emptyList())
        val seeded = have.filter { it.id.startsWith(PREFIX) }
        if (seeded.isNotEmpty()) {
            runCatching {
                store.updateProviders { before -> before.filterNot { it.id.startsWith(PREFIX) } }
            }
            Logs.log("Kotatsu", "pruned ${seeded.size} auto-seeded sources (install what you want from Kotatsu repos)")
        }
        runCatching { store.markKotatsuPruned() }
    }
}
