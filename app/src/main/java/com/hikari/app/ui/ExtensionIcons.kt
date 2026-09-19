package com.hikari.app.ui

import com.hikari.app.cs3.Cs3MainApiProvider
import com.hikari.app.data.Cs3RepoPlugin
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import com.hikari.app.data.RepoKind
import com.hikari.app.net.Http
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Fallback extension icons. Stremio addons store their manifest `icon` at
 * install time now, but addons installed before that have none — fetch it
 * lazily from the manifest (cached). CS3 plugins without a repo icon fall back
 * to the provider's own mainUrl favicon (the same Google-favicon trick the
 * plugin repos themselves use).
 *
 * The same idea applies to a REPO LISTING, where nothing is installed yet: see
 * [forRepoPlugin], which walks the entry's own icon fields → its addon
 * manifest's logo → the extension site's favicon. A listing that shows no icon
 * at all (which was the norm for SkyStream repos — only 1 of 25 entries
 * declares an `iconUrl`) renders as a monochrome puzzle-piece glyph otherwise.
 */
object ExtensionIcons {

    private val stremioCache = ConcurrentHashMap<String, String?>()

    /** Resolved icons for repo-listing rows, keyed by repo kind + entry URL. */
    private val repoCache = ConcurrentHashMap<String, String>()

    suspend fun forConfig(config: ProviderConfig): String? {
        if (!config.iconUrl.isNullOrBlank()) return config.iconUrl
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
            else -> null
        }
    }

    /**
     * The icon to show for a repo-listing row — nothing is installed yet, so
     * the only sources are what the repo listing declared:
     *
     *  1. the entry's own image field (`iconUrl`, `icon`, `logo` — repos use
     *     all three spellings; a relative path is resolved against the repo),
     *  2. [Cs3RepoPlugin.iconManifest] — the entry's first Stremio addon
     *     manifest, whose `logo` is the REAL icon for a SkyStream `.sky`
     *     (its plugin.json declares no icon at all),
     *  3. [Cs3RepoPlugin.iconHost] — the extension site's favicon, via the
     *     same Google favicon service the CloudStream repos use.
     *
     * Returns null when none of those exist, and the row keeps its placeholder
     * glyph. BLOCKING network I/O on a miss (only step 2 fetches, and its
     * answer is cached per entry) — call it from Dispatchers.IO, exactly like
     * [forConfig].
     */
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

    /**
     * Makes a repo-declared image reference absolute. Plugin listings in the
     * wild use protocol-relative (`//host/icon.png`), root-relative
     * (`/static/icon.png`) and bare relative paths, and a stored icon that
     * Coil cannot turn into a URL is simply skipped — which is another way an
     * otherwise-fine icon silently degrades to the placeholder glyph. Returns
     * null for blank input, and for a relative path with no usable [base].
     */
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
