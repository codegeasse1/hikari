package com.hikari.app.data

object RepoProvenance {

    /** The URL the extension was installed from, or null for a provider that
     *  did not come from a repository at all (a Stremio addon, an IPTV
     *  playlist, a universal scraper pasted as JSON). */
    fun originOf(c: ProviderConfig): String? {
        val raw = when (c.type) {
            ProviderType.HIKARI -> c.extra?.substringBeforeLast('|')
            ProviderType.CS3, ProviderType.NUVIO, ProviderType.SKYSTREAM,
            ProviderType.ANIYOMI, ProviderType.MANGA, ProviderType.VEGA,
            ProviderType.SORA, ProviderType.ANYMEX, ProviderType.ANYMEX_MANGA,
            ProviderType.ZANGETSU, ProviderType.FORK_VIDEO -> c.extra?.substringBefore('|')
            else -> null
        }?.trim()
        return raw?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }

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
            ?: fallbackLabel(c)

    fun fallbackLabel(c: ProviderConfig): String? {
        val origin = originOf(c) ?: return null
        SourceUrls.githubRoot(origin)?.let { return it }
        return runCatching {
            val host = origin.substringAfter("://").substringBefore('/').lowercase()
            host.removePrefix("www.").removePrefix("raw.")
        }.getOrNull()?.takeIf { it.isNotBlank() && it.contains('.') }
    }

    fun nameMap(providers: List<ProviderConfig>, repos: List<Cs3Repo>): Map<String, String> {
        if (providers.isEmpty()) return emptyMap()
        val index = if (repos.isEmpty()) null else Index(repos)
        val out = HashMap<String, String>(providers.size)
        for (c in providers) {
            val name = (index?.repoFor(c)?.name?.takeIf { it.isNotBlank() }
                ?: fallbackLabel(c)) ?: continue
            out[c.id] = name
        }
        return out
    }
}
