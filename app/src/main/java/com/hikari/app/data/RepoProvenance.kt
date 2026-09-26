package com.hikari.app.data

/**
 * Which repository an installed extension came from.
 *
 * Every engine records the URL of the FILE it downloaded in
 * [ProviderConfig.extra] (Hikari appends the source index after a `|`), while
 * the repo list records the URL of the repo's own index (`repo.json`,
 * `manifest.json`, `index.min.json`, …). Those two strings agree on nothing —
 * the same extension is a `…/builds/Foo.cs3` inside one and a
 * `…/builds/repo.json` or a `…/releases/download/v1/Foo.cs3` outside it — so
 * comparing them literally can never answer the question. What they DO share is
 * the repository, so the match is made in this order:
 *
 *  1. the exact repo key ([SourceUrls.repoKey]) — the same file;
 *  2. the same GitHub `owner/repo` AND the same in-repo directory — the shape
 *     every repo that publishes more than one repository out of one GitHub
 *     project has (`owner/repo` holding a `repo.json` and an Aniyomi index);
 *  3. the same GitHub `owner/repo` — the shape of a CI-built repo, whose files
 *     are release assets and whose index sits at the repo root.
 *
 * It is deliberately best-effort: a provider installed by hand, or from a repo
 * the user has since removed, simply gets no label, and the row keeps showing
 * only its engine. The alternative (remembering the repo in [ProviderConfig])
 * would only ever work for installs made after the field was added, which is
 * exactly the opposite of the reported problem — four already-installed
 * "AniKoto" rows that nobody can tell apart.
 */
object RepoProvenance {

    /** The URL the extension was installed from, or null for a provider that
     *  did not come from a repository at all (a Stremio addon, an IPTV
     *  playlist, a universal scraper pasted as JSON). */
    fun originOf(c: ProviderConfig): String? {
        val raw = when (c.type) {
            ProviderType.HIKARI -> c.extra?.substringBeforeLast('|')
            ProviderType.CS3, ProviderType.NUVIO, ProviderType.SKYSTREAM,
            ProviderType.ANIYOMI, ProviderType.MANGA, ProviderType.VEGA -> c.extra
            else -> null
        }?.trim()
        return raw?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }

    /** The added repository [c] came from, or null when it cannot be told. */
    fun repoFor(c: ProviderConfig, repos: List<Cs3Repo>): Cs3Repo? {
        val origin = originOf(c) ?: return null
        if (repos.isEmpty()) return null
        val key = SourceUrls.repoKey(origin)
        repos.firstOrNull { SourceUrls.repoKey(it.url) == key }?.let { return it }
        val root = SourceUrls.githubRoot(origin)
        if (root != null) {
            val dir = SourceUrls.repoPath(origin)?.substringBeforeLast('/')
            if (!dir.isNullOrBlank()) {
                repos.firstOrNull { r ->
                    SourceUrls.githubRoot(r.url) == root &&
                        SourceUrls.repoPath(r.url)?.substringBeforeLast('/') == dir
                }?.let { return it }
            }
            repos.firstOrNull { SourceUrls.githubRoot(it.url) == root }?.let { return it }
        }
        return null
    }

    /** The NAME of the repository [c] came from, or null. */
    fun nameOf(c: ProviderConfig, repos: List<Cs3Repo>): String? =
        repoFor(c, repos)?.name?.takeIf { it.isNotBlank() }

    /** One entry per provider id, for the rows that only have the config. */
    fun nameMap(providers: List<ProviderConfig>, repos: List<Cs3Repo>): Map<String, String> {
        if (providers.isEmpty() || repos.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        for (c in providers) {
            val name = nameOf(c, repos) ?: continue
            out[c.id] = name
        }
        return out
    }
}
