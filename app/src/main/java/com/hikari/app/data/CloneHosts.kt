package com.hikari.app.data

/** Clone-site helpers: id checks, host extraction + URL rewriting.
 *
 *  A clone is a normal [ProviderConfig] row whose id starts with "clone|"
 *  (same [ProviderType] + file url as the original). Domain overrides live
 *  in [AppStore.cloneHostsFlow], keyed by clone id. The main-site override
 *  ([CloneEntry.mainNew]) replaces the extension's base URL; [CloneEntry.hosts]
 *  rewrites any other host (image CDN, video hosts) wherever a URL is used.
 */
object CloneHosts {
    const val PREFIX = "clone|"

    fun isCloneId(id: String): Boolean = id.startsWith(PREFIX)

    /** Parsed clone record from a clone [ProviderConfig.extra]. Null when the
     *  config is not a clone. */
    data class CloneInfo(
        val origId: String,
        val mainOld: String?,
        val mainNew: String?,
        val hosts: Map<String, String>,
        val origExtra: String?,
    )

    fun infoOf(config: ProviderConfig): CloneInfo? {
        val raw = config.extra?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val o = org.json.JSONObject(raw)
            val orig = o.optString("cloneOf").takeIf { it.isNotBlank() } ?: return null
            val hosts = LinkedHashMap<String, String>()
            val ho = o.optJSONObject("hosts")
            if (ho != null) {
                val k = ho.keys()
                while (k.hasNext()) {
                    val key = k.next()
                    hosts[key] = ho.optString(key)
                }
            }
            CloneInfo(
                origId = orig,
                mainOld = o.optString("mainOld").takeIf { it.isNotBlank() }?.let { hostOf(it) },
                mainNew = o.optString("main").takeIf { it.isNotBlank() }
                    ?: o.optString("mainNew").takeIf { it.isNotBlank() },
                hosts = hosts,
                origExtra = o.optString("origExtra").takeIf { it.isNotBlank() },
            )
        } catch (e: Exception) {
            null
        }
    }

    fun isClone(config: ProviderConfig): Boolean = infoOf(config) != null

    /** The extra a clone stores on its own [ProviderConfig] row. Both hosts
     *  are stored so the old->new pair survives without re-detection. */
    fun makeExtra(
        origId: String,
        mainOld: String?,
        mainNew: String?,
        hosts: Map<String, String> = emptyMap(),
        origExtra: String? = null,
    ): String {
        val ho = org.json.JSONObject()
        for ((k, v) in hosts) ho.put(k, v)
        return org.json.JSONObject()
            .put("cloneOf", origId)
            .put("mainOld", hostOf(mainOld).orEmpty())
            .put("main", mainNew.orEmpty())
            .put("hosts", ho)
            .put("origExtra", origExtra.orEmpty())
            .toString()
    }

    /** The extra the engine should read: the original's, for clones. */
    fun effectiveExtra(config: ProviderConfig): String? =
        infoOf(config)?.origExtra ?: config.extra

    /** Full main-site URL override for a clone, or null. */
    fun mainOverride(config: ProviderConfig): String? {
        val m = infoOf(config)?.mainNew?.trim().orEmpty()
        if (m.isBlank()) return null
        return if (m.startsWith("http")) m.trimEnd('/') + "/" else "https://$m".trimEnd('/') + "/"
    }

    /** Stored old main host for a clone, or null. */
    fun mainOldOf(config: ProviderConfig): String? = infoOf(config)?.mainOld

    /** Old->new host map for a clone (main pair included when known). */
    fun hostsOf(config: ProviderConfig, mainOld: String? = null): Map<String, String> {
        val info = infoOf(config) ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        val oldH = hostOf(mainOld) ?: info.mainOld
        val newH = hostOf(info.mainNew)
        if (!oldH.isNullOrBlank() && !newH.isNullOrBlank() && oldH != newH) out[oldH] = newH
        for ((k, v) in info.hosts) {
            val o = hostOf(k) ?: k.trim().lowercase()
            val n = hostOf(v) ?: v.trim().lowercase()
            if (o.isNotBlank() && n.isNotBlank() && o != n) out[o] = n
        }
        return out
    }

    // ---- Global host rewrite (images + Http scraping) ----

    /** Merged old->new hosts of every installed clone. Refreshed whenever the
     *  provider list resolves (see ProviderManager) and after every clone
     *  save/delete, so image + Http layers stay correct without a restart. */
    @Volatile
    var globalHosts: Map<String, String> = emptyMap()
        private set

    fun refreshGlobal(configs: List<ProviderConfig>) {
        val out = LinkedHashMap<String, String>()
        for (c in configs) {
            val info = infoOf(c) ?: continue
            val oldH = info.mainOld
            val newH = hostOf(info.mainNew)
            if (!oldH.isNullOrBlank() && !newH.isNullOrBlank() && oldH != newH) out[oldH] = newH
            for ((k, v) in info.hosts) {
                val o = hostOf(k) ?: k.trim().lowercase()
                val n = hostOf(v) ?: v.trim().lowercase()
                if (o.isNotBlank() && n.isNotBlank() && o != n) out[o] = n
            }
        }
        globalHosts = out
    }

    fun rewriteGlobal(url: String?): String? = rewrite(url, globalHosts)

    /** Set a CloudStream MainAPI's mainUrl to [url] (same reflective dance as
     *  the Castle-TV normalizer — the field may live on a base class). */
    fun applyCs3MainUrl(api: Any, url: String) {
        runCatching {
            api.javaClass.methods.firstOrNull { it.name == "setMainUrl" }
                ?.invoke(api, url)
                ?: throw NoSuchMethodException()
        }.onFailure {
            var c: Class<*>? = api.javaClass
            while (c != null) {
                try {
                    c.getDeclaredField("mainUrl").also { f ->
                        f.isAccessible = true
                        f.set(api, url)
                    }
                    break
                } catch (e: NoSuchFieldException) {
                    c = c.superclass
                }
            }
        }
    }

    /** Clone id for an original id + user name: keeps the original's
     *  trailing engine index ("|0") so CS3 api resolution keeps working. */
    fun makeId(origId: String, name: String): String {
        val safe = name.trim().lowercase()
            .map { if (it.isLetterOrDigit()) it else '_' }
            .joinToString("").trim('_').take(24).ifBlank { "clone" }
        val tail = origId.substringAfterLast("|", "")
        val suffix = if (tail.all { it.isDigit() } && tail.isNotEmpty()) "|$tail" else ""
        return "$PREFIX${System.currentTimeMillis() % 1000000}_$safe$suffix"
    }

    fun hostOf(raw: String?): String? {
        val t = raw?.trim().orEmpty()
        if (t.isBlank()) return null
        val withScheme = if (t.startsWith("http")) t else "https://$t"
        val host = runCatching { java.net.URI(withScheme).host?.lowercase() }.getOrNull()
            ?: return null
        if (host.isBlank() || !host.contains(".")) return null
        return host
    }

    /** Rewrite [url]'s host through [hosts] (old->new, case-insensitive).
     *  Returns the url unchanged when nothing matches. */
    fun rewrite(url: String?, hosts: Map<String, String>): String? {
        if (url.isNullOrBlank() || hosts.isEmpty()) return url
        return try {
            val uri = java.net.URI(url)
            val host = uri.host?.lowercase() ?: return url
            for ((old, neww) in hosts) {
                val o = old.trim().lowercase()
                val n = hostOf(neww) ?: neww.trim().lowercase()
                if (o.isBlank() || n.isBlank()) continue
                if (host == o || host.endsWith(".$o")) {
                    val newHost = if (host == o) n else host.substring(0, host.length - o.length) + n
                    val rebuilt = java.net.URI(
                        uri.scheme, uri.userInfo, newHost, uri.port,
                        uri.path, uri.query, uri.fragment,
                    )
                    return rebuilt.toString()
                }
            }
            url
        } catch (e: Exception) {
            url
        }
    }

    /** Full rewrite for a clone: main-site host first, then extra hosts. */
    fun rewriteForClone(url: String?, entry: CloneEntry?): String? {
        if (url.isNullOrBlank() || entry == null) return url
        var out = url
        val mainOld = entry.hosts.keys.firstOrNull()?.let { null } // hosts map holds extras only
        // mainNew applies when the url's host matches the ORIGINAL main host.
        // The original host is not stored on the entry (it can be re-derived),
        // so callers pass a combined map; here we just apply [hosts].
        out = rewrite(out, entry.hosts)
        return out
    }

    /** Build the combined old->new map for a clone, including the main site
     *  pair ([mainOld] -> [entry.mainNew]) when both are known. */
    fun combinedHosts(entry: CloneEntry, mainOld: String?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val oldH = hostOf(mainOld)
        val newH = hostOf(entry.mainNew)
        if (!oldH.isNullOrBlank() && !newH.isNullOrBlank() && oldH != newH) {
            out[oldH] = newH
        }
        for ((k, v) in entry.hosts) {
            val o = hostOf(k) ?: k.trim().lowercase()
            val n = hostOf(v) ?: v.trim().lowercase()
            if (o.isNotBlank() && n.isNotBlank() && o != n) out[o] = n
        }
        return out
    }

    /** Scan raw extension bytes/text for http(s) hosts (main + CDN + video).
     *  Best-effort: capped, deduped, shortest-first so the main site (usually
     *  the shortest registrable domain) comes first. */
    fun extractHosts(text: String, limit: Int = 24): List<String> {
        if (text.isBlank()) return emptyList()
        val re = Regex("""https?://([A-Za-z0-9](?:[A-Za-z0-9\-]{0,61}[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9\-]{0,61}[A-Za-z0-9])?)+)(?::\d+)?""")
        val seen = LinkedHashSet<String>()
        for (m in re.findAll(text)) {
            val h = m.groupValues[1].lowercase()
            if (h.contains(".") && !h.endsWith(".js") && !h.endsWith(".css")) seen += h
            if (seen.size >= limit * 2) break
        }
        return seen.sortedWith(compareBy({ it.length }, { it })).take(limit)
    }

    fun sanitizeDomain(input: String): String =
        hostOf(input) ?: input.trim().lowercase().trim('.', '/')
}
