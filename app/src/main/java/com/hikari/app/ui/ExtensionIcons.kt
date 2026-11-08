package com.hikari.app.ui

import com.hikari.app.cs3.Cs3MainApiProvider
import com.hikari.app.data.Cs3RepoPlugin
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import com.hikari.app.data.RepoKind
import com.hikari.app.net.Http
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object ExtensionIcons {

    private val stremioCache = ConcurrentHashMap<String, String?>()

    /** Resolved icons for repo-listing rows, keyed by repo kind + entry URL. */
    private val repoCache = ConcurrentHashMap<String, String>()

    private val missAt = ConcurrentHashMap<String, Long>()
    private const val MISS_TTL_MS = 5 * 60_000L

    suspend fun forConfig(config: ProviderConfig): String? {
        if (!config.iconUrl.isNullOrBlank()) return config.iconUrl
        missAt[config.id]?.let {
            if (System.currentTimeMillis() - it <= MISS_TTL_MS) return null
        }
        val icon = resolveForConfig(config)
        if (icon == null) missAt[config.id] = System.currentTimeMillis()
        else missAt.remove(config.id)
        return icon
    }

    private suspend fun resolveForConfig(config: ProviderConfig): String? {
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
            ProviderType.SKYSTREAM ->
                com.hikari.app.skystream.SkyStreamPluginManager.iconFallback(config)
            ProviderType.ANIYOMI ->
                com.hikari.app.aniyomi.AniyomiExtensionManager.iconFallback(config)
            // A manga extension carries no artwork either — its only identity is
            // the site it reads, so the favicon of that site is the icon.
            ProviderType.MANGA ->
                com.hikari.app.manga.MangaExtensionManager.iconFallback(config)
            // A fork video row reads a site like a manga row does — its only
            // identity is that site, so the same favicon fallback applies
            // (the repo entry's own icon, when it declared one, is already
            // stored in [ProviderConfig.iconUrl] and returned by [forConfig]
            // above; [siteUrlOf] resolves fork video ids to their parser's
            // live domain).
            ProviderType.FORK_VIDEO ->
                com.hikari.app.manga.MangaExtensionManager.iconFallback(config)
            else -> null
        }
    }

    suspend fun forRepoPlugin(
        p: Cs3RepoPlugin,
        kind: RepoKind,
        repoUrl: String? = null,
    ): String? {
        absolute(p.iconUrl, repoUrl)?.let { return it }
        val key = kind.name + "|" + p.url
        repoCache[key]?.let { return it }
        val resolved = runCatching {
            val manifestUrl = absolute(p.iconManifest, p.url)
            if (manifestUrl != null) {
                val logo = runCatching { JSONObject(Http.getString(manifestUrl) ?: "") }
                    .getOrNull()
                    ?.let { o ->
                        o.optString("logo")
                            .ifBlank { o.optString("icon") }
                            .ifBlank { o.optString("logoUrl") }
                            .ifBlank { null }
                    }
                absolute(logo, manifestUrl)?.let { return@runCatching it }
            }
            p.iconHost?.takeIf { it.isNotBlank() }
                ?.let { "https://www.google.com/s2/favicons?domain=$it&sz=64" }
        }.getOrNull()
        // ConcurrentHashMap forbids null values — only cache hits.
        if (resolved != null) repoCache[key] = resolved
        return resolved
    }

    fun absolute(url: String?, base: String?): String? {
        val u = url?.trim().orEmpty()
        if (u.isBlank()) return null
        if (u.startsWith("//")) return "https:$u"
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        if (u.startsWith("data:")) return u
        if (base.isNullOrBlank()) return null
        return runCatching { java.net.URI(base).resolve(u).toString() }
            .getOrNull()
            ?.takeIf { it.startsWith("http") }
    }
}
