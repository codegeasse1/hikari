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

    /**
     * [repos] pre-indexed three ways, so ONE provider lookup is O(1) instead of
     * a walk over the repo list.
     *
     * This is what makes [nameMap] usable on the draw path. It used to answer
     * each provider by SCANNING every repo (and computing each repo's identity
     * inside the scan, which meant re-parsing every repo URL once per provider)
     * — O(providers × repos) URL parses. With five hundred installed providers
     * and a couple of hundred added repos that was hundreds of thousands of
     * regexes, run the moment Home's provider picker opened, which is the
     * second of delay a tap on the provider pill used to have.
     */
    class Index(private val repos: List<Cs3Repo>) {

        private val byKey = HashMap<String, Cs3Repo>()
        private val byRootDir = HashMap<String, Cs3Repo>()
        private val byRoot = HashMap<String, Cs3Repo>()

        init {
            for (repo in repos) {
                SourceUrls.repoKey(repo.url)?.let { byKey.putIfAbsent(it, repo) }
                val root = SourceUrls.githubRoot(repo.url) ?: continue
                byRoot.putIfAbsent(root, repo)
                val dir = SourceUrls.repoPath(repo.url)?.substringBeforeLast('/').orEmpty()
                if (dir.isNotBlank()) byRootDir.putIfAbsent("$root|$dir", repo)
            }
        }

        /** The added repository [c] came from, or null when it cannot be told. */
        fun repoFor(c: ProviderConfig): Cs3Repo? {
            val origin = RepoProvenance.originOf(c) ?: return null
            SourceUrls.repoKey(origin)?.let { byKey[it]?.let { r -> return r } }
            val root = SourceUrls.githubRoot(origin) ?: return null
            val dir = SourceUrls.repoPath(origin)?.substringBeforeLast('/')
            if (!dir.isNullOrBlank()) byRootDir["$root|$dir"]?.let { return it }
            return byRoot[root]
        }
    }

    /** The added repository [c] came from, or null when it cannot be told. */
    fun repoFor(c: ProviderConfig, repos: List<Cs3Repo>): Cs3Repo? =
        if (repos.isEmpty()) null else Index(repos).repoFor(c)

    /** The NAME of the repository [c] came from, or null. */
    fun nameOf(c: ProviderConfig, repos: List<Cs3Repo>): String? =
        repoFor(c, repos)?.name?.takeIf { it.isNotBlank() }

    /**
     * One entry per provider id, for the rows that only have the config: the map
     * the Extensions lists, Home's picker and the collection picker all draw
     * from. The repo list is indexed ONCE here (see [Index]), so the whole map
     * costs one pass over the repos plus one pass over the providers.
     */
    fun nameMap(providers: List<ProviderConfig>, repos: List<Cs3Repo>): Map<String, String> {
        if (providers.isEmpty() || repos.isEmpty()) return emptyMap()
        val index = Index(repos)
        val out = HashMap<String, String>(providers.size)
        for (c in providers) {
            val name = index.repoFor(c)?.name?.takeIf { it.isNotBlank() } ?: continue
            out[c.id] = name
        }
        return out
    }
}
