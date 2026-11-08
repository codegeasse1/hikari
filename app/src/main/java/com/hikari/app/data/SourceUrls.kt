package com.hikari.app.data

object SourceUrls {

    private val JSDELIVR = Regex(
        "^https?://cdn\\.jsdelivr\\.net/gh/([^/@]+)/([^/@]+)@([^/]+)/(.+)$",
        RegexOption.IGNORE_CASE,
    )
    private val RAW_GH = Regex(
        "^https?://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/(.+)$",
        RegexOption.IGNORE_CASE,
    )
    private val GH_FILE = Regex(
        "^https?://(?:www\\.)?github\\.com/([^/]+)/([^/]+)/(?:blob|raw|resolve)/(.+)$",
        RegexOption.IGNORE_CASE,
    )
    private val GH_RELEASE = Regex(
        "^https?://(?:www\\.)?github\\.com/([^/]+)/([^/]+)/releases/" +
            "(?:download/([^/]+)/(.+)|latest/download/(.+))$",
        RegexOption.IGNORE_CASE,
    )
    private val GH_WEB = Regex(
        "^https?://(?:www\\.)?github\\.com/([^/]+)/([^/]+)/?.*$",
        RegexOption.IGNORE_CASE,
    )
    private val SCHEME_HOST = Regex("^([a-z][a-z0-9+.-]*)://([^/]+)(.*)$", RegexOption.IGNORE_CASE)

    private const val CACHE_MAX = 4096

    private val canonicalCache =
        object : LinkedHashMap<String, String>(512, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, String>?,
            ): Boolean = size > CACHE_MAX
        }

    private fun cachedCanonical(cleaned: String): String {
        if (cleaned.isEmpty()) return cleaned
        synchronized(canonicalCache) { canonicalCache[cleaned]?.let { return it } }
        val value = canonicalUncached(cleaned)
        synchronized(canonicalCache) { canonicalCache[cleaned] = value }
        return value
    }

    fun canonical(raw: String): String = cachedCanonical(clean(raw))

    private fun canonicalUncached(t: String): String {
        if (t.isEmpty()) return t
        JSDELIVR.find(t)?.let { m ->
            return "https://raw.githubusercontent.com/${m.groupValues[1].lowercase()}/" +
                "${m.groupValues[2].lowercase()}/${m.groupValues[3]}/${dropRefPrefix(m.groupValues[4])}"
        }
        RAW_GH.find(t)?.let { m ->
            return "https://raw.githubusercontent.com/${m.groupValues[1].lowercase()}/" +
                "${m.groupValues[2].lowercase()}/${dropRefPrefix(m.groupValues[3])}"
        }
        GH_FILE.find(t)?.let { m ->
            return "https://raw.githubusercontent.com/${m.groupValues[1].lowercase()}/" +
                "${m.groupValues[2].lowercase()}/${dropRefPrefix(m.groupValues[3])}"
        }
        GH_RELEASE.find(t)?.let { m ->
            return "https://github.com/${m.groupValues[1].lowercase()}/" +
                "${m.groupValues[2].lowercase()}/releases/download/" +
                "${m.groupValues[3].ifBlank { "latest" }}/" +
                m.groupValues[4].ifBlank { m.groupValues[5] }
        }
        GH_WEB.find(t)?.let { m ->
            return "https://github.com/${m.groupValues[1].lowercase()}/${m.groupValues[2].lowercase()}"
        }
        val base = when {
            t.endsWith("/repo.json") -> t.removeSuffix("/repo.json")
            t.endsWith("/manifest.json") -> t.removeSuffix("/manifest.json")
            else -> t.trimEnd('/')
        }
        SCHEME_HOST.find(base)?.let { m ->
            return "${m.groupValues[1].lowercase()}://${m.groupValues[2].lowercase()}${m.groupValues[3]}"
        }
        return base
    }

    fun repoKey(raw: String): String? {
        val c = canonical(clean(raw))
        if (c.isEmpty()) return null
        RAW_GH.find(c)?.let { m ->
            val rest = m.groupValues[3].split('/').filter { it.isNotBlank() }
            // The segment right after owner/repo is the branch; the rest is the
            // path inside the repo.
            val path = if (rest.size > 1) rest.drop(1).joinToString("/") else rest.joinToString("/")
            return "gh:${m.groupValues[1].lowercase()}/${m.groupValues[2].lowercase()}/${stripIndexFileName(path)}"
        }
        GH_WEB.find(c)?.let { m ->
            return "gh:${m.groupValues[1].lowercase()}/${m.groupValues[2].lowercase()}/"
        }
        SCHEME_HOST.find(c)?.let { m ->
            return (m.groupValues[1].lowercase() + "://" + m.groupValues[2].lowercase() +
                m.groupValues[3]).trimEnd('/')
        }
        return c.trimEnd('/')
    }

    private val INDEX_FILE_NAMES = listOf(
        "index.json",
        "index.min.json",
        "index.pb",
        "index.min.pb",
    )

    private fun stripIndexFileName(path: String): String {
        for (name in INDEX_FILE_NAMES) {
            if (path.equals(name, ignoreCase = true)) return ""
            if (path.endsWith("/$name", ignoreCase = true)) return path.dropLast(name.length + 1)
        }
        return path
    }

    /** True when [raw] and [other] name the same repository file (see [repoKey]). */
    fun sameRepo(raw: String, other: String): Boolean {
        val a = repoKey(raw) ?: return false
        val b = repoKey(other) ?: return false
        return a == b
    }

    fun fileKey(raw: String): String? {
        val c = canonical(clean(raw))
        RAW_GH.find(c)?.let { m ->
            val file = m.groupValues[3].substringAfterLast('/').lowercase()
            if (file.isBlank()) return null
            return "${m.groupValues[1].lowercase()}/${m.groupValues[2].lowercase()}/$file"
        }
        GH_RELEASE.find(c)?.let { m ->
            val file = (m.groupValues[4].ifBlank { m.groupValues[5] })
                .substringAfterLast('/').lowercase()
            if (file.isBlank()) return null
            return "${m.groupValues[1].lowercase()}/${m.groupValues[2].lowercase()}/$file"
        }
        return null
    }

    private val VERSION_SUFFIX = Regex("^(.*?)[-_ ]v\\d[\\w.]*$", RegexOption.IGNORE_CASE)

    fun strippedFileKey(raw: String): String? {
        val k = fileKey(raw) ?: return null
        val i = k.lastIndexOf('/')
        if (i < 0) return null
        val file = k.substring(i + 1)
        val dot = file.lastIndexOf('.')
        val name = if (dot > 0) file.substring(0, dot) else file
        val ext = if (dot > 0) file.substring(dot) else ""
        val stem = VERSION_SUFFIX.find(name)?.groupValues?.get(1) ?: return null
        if (stem.length < 2) return null
        return k.substring(0, i + 1) + stem.lowercase() + ext
    }

    private val matchKeysCache =
        object : LinkedHashMap<String, List<String>>(256, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, List<String>>?,
            ): Boolean = size > CACHE_MAX
        }

    fun matchKeys(raw: String): List<String> {
        val c = clean(raw)
        if (c.isEmpty()) return emptyList()
        synchronized(matchKeysCache) { matchKeysCache[c]?.let { return it } }
        val canon = canonical(c)
        val file = fileKey(c)
        val keys = listOfNotNull(c, canon.takeIf { it != c }, file, strippedFileKey(c).takeIf { it != file })
        synchronized(matchKeysCache) { matchKeysCache[c] = keys }
        return keys
    }

    fun anyKeyIn(raw: String, keys: Set<String>): Boolean {
        if (raw.isBlank() || keys.isEmpty()) return false
        if (raw in keys) return true
        return matchKeys(raw).any { it in keys }
    }

    fun githubRoot(raw: String): String? =
        githubParts(raw)?.let { "${it.first}/${it.second}" }

    /** The path of [raw] inside its GitHub repo (lowercased, no leading slash,
     *  with the branch segment dropped), or null when [raw] is not a GitHub
     *  URL. Used to prefer the repository whose in-repo DIRECTORY a file sits
     *  in when one GitHub repo holds more than one Hikari repository. */
    fun repoPath(raw: String): String? = githubParts(raw)?.third

    private fun githubParts(raw: String): Triple<String, String, String>? {
        val c = canonical(clean(raw))
        RAW_GH.find(c)?.let { m ->
            return Triple(
                m.groupValues[1].lowercase(),
                m.groupValues[2].lowercase(),
                m.groupValues[3].substringAfter('/', "").lowercase(),
            )
        }
        GH_RELEASE.find(c)?.let { m ->
            return Triple(m.groupValues[1].lowercase(), m.groupValues[2].lowercase(), "")
        }
        GH_WEB.find(c)?.let { m ->
            return Triple(m.groupValues[1].lowercase(), m.groupValues[2].lowercase(), "")
        }
        return null
    }

    /** True when [raw] is served through the jsDelivr mirror rather than the
     *  origin host — the mirror is a fallback for a blocked/rate-limited
     *  fetch, so the origin spelling is the one worth persisting. */
    fun isMirror(raw: String): Boolean =
        clean(raw).lowercase().startsWith("https://cdn.jsdelivr.net/") ||
            clean(raw).lowercase().startsWith("http://cdn.jsdelivr.net/")

    private fun clean(raw: String): String = raw
        .trim()
        .substringBefore('#')
        .substringBefore('?')
        .replace("%20", " ")
        .trimEnd('/')

    /** Strips a leading `refs/heads/` or `refs/tags/` so both GitHub spellings
     *  of the same branch compare equal. */
    private fun dropRefPrefix(path: String): String =
        if (path.startsWith("refs/heads/")) path.removePrefix("refs/heads/")
        else if (path.startsWith("refs/tags/")) path.removePrefix("refs/tags/")
        else path
}
