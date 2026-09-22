package com.hikari.app.data

/**
 * Identity helpers for extension source URLs.
 *
 * An extension's origin is remembered verbatim in [ProviderConfig.extra] — the
 * exact string the repo.json served when it was installed — but repos rewrite
 * the same file's URL over time. The very same plugin can appear as
 * `raw.githubusercontent.com/o/r/<branch>/X.cs3`,
 * `.../o/r/refs/heads/<branch>/X.cs3`, or through the jsDelivr mirror
 * (`cdn.jsdelivr.net/gh/o/r@<branch>/X.cs3`), and the CNC repo for example
 * mixes both raw spellings in one build.
 *
 * A repo built by CI publishes its files as GitHub RELEASE ASSETS instead
 * (`github.com/o/r/releases/download/<tag>/X.cs3`). Those used to collapse
 * onto the repo root, i.e. every extension of such a repo shared one identity.
 *
 * Comparing those strings literally made Hikari treat an already-installed
 * extension as "not installed" (an Install button in the repo it came from,
 * while the extension kept working on Home), and let one repo be added twice
 * under two spellings of the same URL — the second copy showing every
 * extension as uninstalled again. Every such comparison goes through the
 * canonical forms below instead.
 */
object SourceUrls {

    private val JSDELIVR = Regex(
        "^https?://cdn\\.jsdelivr\\.net/gh/([^/@]+)/([^/@]+)@([^/]+)/(.+)$",
        RegexOption.IGNORE_CASE,
    )
    private val RAW_GH = Regex(
        "^https?://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/(.+)$",
        RegexOption.IGNORE_CASE,
    )
    /** A github.com FILE link (`github.com/o/r/blob/<ref>/<path>`, `raw/…`,
     *  `resolve/…`) — the web spelling of a raw file. It must be folded onto
     *  its raw.githubusercontent.com form, NOT onto the repo root: collapsing
     *  these to `github.com/o/r` gave every plugin of such a repo the SAME
     *  identity, so uninstalling one extension removed the whole repo's worth
     *  (and a re-added repo handed out Install buttons again). */
    private val GH_FILE = Regex(
        "^https?://(?:www\\.)?github\\.com/([^/]+)/([^/]+)/(?:blob|raw|resolve)/(.+)$",
        RegexOption.IGNORE_CASE,
    )
    /**
     * A GitHub RELEASE-ASSET link
     * (`github.com/o/r/releases/download/<tag>/<file>`, and the `latest` form).
     *
     * This is the shape extension repos that are built by CI publish, and it
     * must be handled before the repo-page rule below: without it, every asset
     * of a repo collapsed onto the repo ROOT, i.e. every extension in a repo
     * built this way shared ONE identity. Installing one made the whole repo
     * read as installed (each row showing Uninstall with the Install button
     * nowhere), Install-all skipped everything, and uninstalling a row could
     * take a different extension with it — the reported "installing one shows
     * all installed, uninstalling one shows all uninstalled".
     */
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

    /**
     * One string per repo/file, whatever spelling it arrived in: the jsDelivr
     * mirror and the `github.com` web page collapse onto their raw
     * raw.githubusercontent.com form, `/refs/heads/<branch>/` and
     * `/<branch>/` collapse together, and trailing slashes / queries / `%20`
     * vs a literal space no longer distinguish two URLs.
     */
    fun canonical(raw: String): String {
        val t = clean(raw)
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
        // A release asset keeps its tag: two files in the same repo are two
        // files, and the tag only pins WHICH build of that file (a new tag is
        // the same file — see [fileKey], which ignores it).
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

    /**
     * The identity of one *repository file*: which GitHub repo (or host+path) it
     * belongs to, ignoring the branch it was fetched from.
     *
     * `owner/repo/builds/repo.json` and `owner/repo/main/repo.json` are the SAME
     * repo (the same plugin list, published on two branches), and adding one
     * after the other used to grow a second folder in the repo list with the same
     * name — which read as "I added the same repo twice and it made a duplicate".
     * Everything that compares repos as a whole (adding, removing, the stored
     * list's own dedupe) keys on this; [canonical] stays the identity of the
     * exact FILE, which is what extension matching needs.
     */
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

    /**
     * Which FILE a Mihon/Aniyomi repo publishes its extension list in is not
     * part of the repo's identity: `…/repo/index.json` and
     * `…/repo/index.min.json` are the same repository, and Hikari may store one
     * spelling while fetching the other — keiyoushi's minified file is a
     * two-entry "update your app" stub, so the real list always comes from
     * `index.json` (see AniyomiExtensionManager.indexCandidatesFor). Without
     * this, re-adding the same repo with the other spelling grew a second,
     * empty-looking folder.
     *
     * Only the Mihon/Aniyomi index names are folded away — `repo.json`,
     * `manifest.json` and `plugins.json` are the repo FILES of the other kinds
     * and keep their identity.
     */
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

    /**
     * The identity of one *file* inside a GitHub repo: `owner/repo/FileName`.
     * Used as a second chance when a repo moved the file (a new branch, a new
     * path) so an installed extension is still recognised, while keeping
     * different repos' (and different files') identities apart.
     */
    fun fileKey(raw: String): String? {
        val c = canonical(clean(raw))
        RAW_GH.find(c)?.let { m ->
            val file = m.groupValues[3].substringAfterLast('/').lowercase()
            if (file.isBlank()) return null
            return "${m.groupValues[1].lowercase()}/${m.groupValues[2].lowercase()}/$file"
        }
        // The same release asset under another tag (`continuous` → `v1.2`, or
        // GitHub's `latest`) is the same extension, so the tag is not part of
        // the file's identity — exactly as the branch is not part of a raw
        // file's.
        GH_RELEASE.find(c)?.let { m ->
            val file = (m.groupValues[4].ifBlank { m.groupValues[5] })
                .substringAfterLast('/').lowercase()
            if (file.isBlank()) return null
            return "${m.groupValues[1].lowercase()}/${m.groupValues[2].lowercase()}/$file"
        }
        return null
    }

    /**
     * Every string [raw] should be recognised by: itself, its canonical form,
     * and its file key. Anything sourced from [raw] then matches a repo listing
     * that spells the URL differently, in either direction.
     */
    fun matchKeys(raw: String): List<String> {
        val c = clean(raw)
        if (c.isEmpty()) return emptyList()
        val canon = canonical(c)
        return listOfNotNull(c, canon.takeIf { it != c }, fileKey(c))
    }

    /**
     * True when [raw] is one of the file URLs in [keys] — however either side
     * spells it (refs/heads vs a plain branch, the jsDelivr mirror, a
     * github.com blob link, %20 vs a space).
     *
     * The repo listing and the installed set disagree about spelling far more
     * often than they used to: a repo rewrites its file URLs between builds
     * (a new branch name, a different mirror), and a github.com blob link is
     * now recognised as the file it points at. Every "is this installed?"
     * test goes through this instead of a literal `url in installedUrls`,
     * which showed an Install button for an extension that was already there.
     */
    fun anyKeyIn(raw: String, keys: Set<String>): Boolean {
        if (raw.isBlank() || keys.isEmpty()) return false
        if (raw in keys) return true
        return matchKeys(raw).any { it in keys }
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
