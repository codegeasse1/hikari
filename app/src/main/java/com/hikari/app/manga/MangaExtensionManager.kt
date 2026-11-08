package com.hikari.app.manga

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import com.hikari.app.HikariApp
import com.hikari.app.core.LoadGate
import com.hikari.app.data.AppStore
import com.hikari.app.data.Cs3Repo
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import com.hikari.app.data.RepoKind
import eu.kanade.tachiyomi.source.MangaSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.util.system.ChildFirstPathClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object MangaExtensionManager {

    /** Manga extensions are a few hundred KB; this is a sanity bound only. */
    const val MAX_BYTES = 96L * 1024 * 1024

    private const val EXT = "ext"

    /** Mihon's feature/metadata keys — also what keiyoushi's extensions declare. */
    private const val EXTENSION_FEATURE = "tachiyomi.extension"
    private const val META_SOURCE_CLASS = "tachiyomi.extension.class"
    private const val META_SOURCE_FACTORY = "tachiyomi.extension.factory"
    private const val META_NSFW = "tachiyomi.extension.nsfw"

    /** keiyoushi/Tadami style: the factory class is simply named this. */
    private const val GENERATED_CLASS = "ExtensionGenerated"

    /** A failed load is negative-cached for this long (see [extensionOf]). */
    private const val FAIL_RETRY_MS = 60_000L

    val DEFAULT_REPOS = listOf(
        Triple(
            "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.json",
            "Keiyoushi (manga)",
            "The community manga extension repo (Mihon / Tachiyomi)",
        ),
    )

    /** `manga|<packageName>|<sourceIndex>` → its parts. */
    fun packageOf(config: ProviderConfig): String =
        config.id.removePrefix(PREFIX).substringBefore('|')

    fun indexOf(config: ProviderConfig): Int =
        config.id.substringAfterLast('|').toIntOrNull() ?: 0

    private const val PREFIX = "manga|"

    /** True for a novel row id (`manga|<pkg>|n<idx>`): the `n` keeps novel
     *  rows from ever colliding with a manga row of the same file. */
    fun isNovelId(id: String): Boolean {
        if (!id.startsWith(PREFIX)) return false
        val tail = id.substringAfterLast('|')
        return tail.length > 1 && tail[0] == 'n' && tail.substring(1).all { it.isDigit() }
    }

    /** Which novel source of the extension a novel provider config points at. */
    fun novelIndexOf(config: ProviderConfig): Int =
        config.id.substringAfterLast('|').removePrefix("n").toIntOrNull() ?: 0

    /** True for a provider id that came from this manager. */
    fun isMangaProviderId(id: String): Boolean = id.startsWith(PREFIX)

    /** True when the provider row is a novel source (see [isNovelId]). */
    fun isNovelProvider(config: ProviderConfig): Boolean = isNovelId(config.id)

    // ---- Paths ----

    /** Where private extension copies live (`filesDir/manga/exts`). */
    fun extensionDir(context: Context): File =
        File(context.filesDir, "manga/exts").apply { mkdirs() }

    /** The private copy of an extension: `<pkgName>.ext`. */
    fun extensionFile(context: Context, pkgName: String): File =
        File(extensionDir(context), "$pkgName.$EXT")

    // ---- Loading ----

    /** A loaded extension: metadata plus the sources it publishes. */
    class Extension(
        val file: File,
        val pkgName: String,
        val name: String,
        val versionName: String,
        val versionCode: Long,
        val isNsfw: Boolean,
        val lang: String,
        /** Display label per source, already de-duplicated. */
        val labels: List<String>,
        val sources: List<MangaSource>,
        /** Plain text-chapter (novel) sources: a [Source] with no manga
         *  methods. They read through [com.hikari.app.manga.MangaProvider]'s
         *  novel branch into the novel reader — never the image reader. */
        val novels: List<Source> = emptyList(),
        /** Display label per novel source, already de-duplicated. */
        val novelLabels: List<String> = emptyList(),
    )

    private val cache = ConcurrentHashMap<String, Extension>()
    private val failure = ConcurrentHashMap<String, Pair<Long, String>>()

    fun evict(path: String) {
        cache.remove(path)
        failure.remove(path)
    }

    @Volatile
    var lastError: String? = null
        private set

    private val errorDetails = ThreadLocal.withInitial { StringBuilder() }

    fun takeErrorDetails(): String = errorDetails.get().toString().trim()

    private fun record(what: String, e: Throwable? = null) {
        val sb = errorDetails.get()
        sb.append("• ").append(what)
        if (e != null) sb.append(" — ").append(rootCause(e).let { "${it::class.java.simpleName}: ${it.message}" })
        sb.append('\n')
    }

    private fun rootCause(t: Throwable): Throwable {
        var c: Throwable = t
        while (c.cause != null && c.cause !== c) c = c.cause!!
        return c
    }

    fun extensionOf(context: Context, file: File, force: Boolean = false): Extension? {
        val key = file.absolutePath
        if (!force) {
            cache[key]?.let { if (it.file.exists()) return it }
            failure[key]?.let { (at, msg) ->
                if (System.currentTimeMillis() - at < FAIL_RETRY_MS) {
                    lastError = msg
                    return null
                }
            }
        }
        val loaded = LoadGate.withSlot { load(context, file) }
        if (loaded == null) {
            failure[key] = System.currentTimeMillis() to (lastError ?: "The extension could not be loaded")
            cache.remove(key)
        } else {
            failure.remove(key)
            cache[key] = loaded
        }
        return loaded
    }

    /** The source at [index] of the extension in [file], or null. */
    fun sourceOf(context: Context, file: File, index: Int): MangaSource? =
        extensionOf(context, file)?.sources?.getOrNull(index)

    /** The source a provider config points at. Null for novel rows (see
     *  [novelSourceOf]) — a novel id's `n` index is not a manga index. */
    fun sourceOf(context: Context, config: ProviderConfig): MangaSource? {
        if (!isMangaProviderId(config.id) || isNovelId(config.id)) return null
        val file = File(config.url)
        if (!file.exists()) return null
        return sourceOf(context, file, indexOf(config))
    }

    /** The novel source at [novelIndex] of the extension in [file], or null. */
    fun novelSourceOf(context: Context, file: File, novelIndex: Int): Source? =
        extensionOf(context, file)?.novels?.getOrNull(novelIndex)

    /** The novel source a novel provider config points at. */
    fun novelSourceOf(context: Context, config: ProviderConfig): Source? {
        if (!isNovelId(config.id)) return null
        val file = File(config.url)
        if (!file.exists()) return null
        return novelSourceOf(context, file, novelIndexOf(config))
    }

    /** Whether the provider's `.ext` file is still on disk. Bundled Kotatsu
     *  parsers ship inside the app, so they are never "missing". */
    fun fileMissing(config: ProviderConfig): Boolean =
        config.type == ProviderType.MANGA &&
            !com.hikari.app.kotatsu.KotatsuRegistry.isKotatsuId(config.id) &&
            !com.hikari.app.kotatsu.fork.ForkRegistry.isForkMangaId(config.id) &&
            (config.url.isBlank() || !File(config.url).exists())

    private fun load(context: Context, file: File): Extension? {
        // Extensions resolve their own dependencies out of this DI container
        // (`Injekt.get<Application>()` in a constructor is common), so the scope
        // has to be in place before a single class is instantiated. Cheap when it
        // has not changed; the same call the anime loader makes.
        runCatching { HikariApp.instance.ensureAniyomiInjekt() }
        errorDetails.get().setLength(0)
        lastError = null
        if (file.canWrite()) runCatching { file.setReadOnly() }

        val info = inspect(context, file)
            ?: return failLoad("The extension file is unreadable")
        val app = info.applicationInfo
            ?: return failLoad("The extension package is malformed (no application info)")
        fixBasePaths(app, file.absolutePath)
        val pkgName = info.packageName ?: return failLoad("The extension package has no name")
        val name = displayName(context, info) ?: pkgName

        val classLoader = runCatching {
            ChildFirstPathClassLoader(app.sourceDir, null, context.classLoader)
        }.getOrElse { return failLoad("$name: could not open the extension's class loader", it) }

        val classNames = sourceClassNames(info, pkgName)
        if (classNames.isEmpty()) {
            return failLoad(
                "$name declares no source class (no $META_SOURCE_CLASS, no " +
                    "$META_SOURCE_FACTORY and no $GENERATED_CLASS class)"
            )
        }

        val found = mutableListOf<Source>()
        for (className in classNames) {
            instantiate(context, className, app.sourceDir, classLoader)?.let { found += it }
        }
        if (found.isEmpty()) return failLoad("$name: none of its sources could be loaded")
        val uniqueManga = found.filterIsInstance<MangaSource>().distinctBy { sourceKey(it) }
        val uniqueNovels = found.filter { it !is MangaSource }.distinctBy { novelKey(it) }
        if (uniqueManga.isEmpty() && uniqueNovels.isEmpty()) {
            return failLoad("$name: none of its sources could be loaded")
        }
        val labels = disambiguate(uniqueManga, name)
        val novelLabels = disambiguateNovels(uniqueNovels, name)
        val langs = (uniqueManga + uniqueNovels)
            .mapNotNull { runCatching { it.lang }.getOrNull() }.filter { it.isNotBlank() }.toSet()
        return Extension(
            file = file,
            pkgName = pkgName,
            name = name,
            versionName = info.versionName.orEmpty(),
            versionCode = runCatching { PackageInfoCompat.getLongVersionCode(info) }.getOrDefault(0L),
            isNsfw = metaInt(info, META_NSFW) == 1,
            lang = when (langs.size) {
                0 -> ""
                1 -> langs.first()
                else -> "all"
            },
            labels = labels,
            sources = uniqueManga,
            novels = uniqueNovels,
            novelLabels = novelLabels,
        )
    }

    private fun sourceClassNames(info: PackageInfo, pkgName: String): List<String> {
        val absolute = { raw: String -> if (raw.startsWith(".")) pkgName + raw else raw }
        val fromMeta = metaString(info, META_SOURCE_CLASS)
            ?.split(";", ",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.map(absolute)
            .orEmpty()
        if (fromMeta.isNotEmpty()) return fromMeta
        metaString(info, META_SOURCE_FACTORY)?.trim()?.takeIf { it.isNotBlank() }?.let {
            return listOf(absolute(it))
        }
        return listOf("$pkgName.$GENERATED_CLASS")
    }

    private fun instantiate(
        context: Context,
        className: String,
        apkPath: String,
        child: ClassLoader,
    ): List<Source>? {
        val loaders = listOf(child, dalvik.system.PathClassLoader(apkPath, null, context.classLoader))
        for ((index, loader) in loaders.withIndex()) {
            try {
                val instance = Class.forName(className, false, loader)
                    .getDeclaredConstructor()
                    .newInstance()
                val sources = when (instance) {
                    is MangaSource -> listOf(instance)
                    is SourceFactory -> instance.createSources().filterIsInstance<Source>()
                    is Source -> listOf(instance)
                    else -> {
                        record("$className is not a manga source")
                        emptyList()
                    }
                }
                return sources.ifEmpty { null }
            } catch (e: LinkageError) {
                if (index == 0) continue
                record("$className could not be linked", e)
                return null
            } catch (e: Throwable) {
                if (e is VirtualMachineError) throw e
                record("$className could not be instantiated", e)
                return null
            }
        }
        return null
    }

    private fun sourceKey(s: MangaSource): String {
        val id = runCatching { s.id }.getOrNull()
        val name = runCatching { s.name }.getOrNull()
        val lang = runCatching { s.lang }.getOrNull()
        return "$id|$name|$lang"
    }

    private fun novelKey(s: Source): String {
        val id = runCatching { s.id }.getOrNull()
        val name = runCatching { s.name }.getOrNull()
        val lang = runCatching { s.lang }.getOrNull()
        return "$id|$name|$lang"
    }

    /** De-duplicated display names for novel sources (same rule as manga:
     *  colliding names get a numeric suffix). */
    private fun disambiguateNovels(sources: List<Source>, fallback: String): List<String> {
        val raw = sources.map { runCatching { it.name }.getOrNull()?.takeIf { n -> n.isNotBlank() } ?: fallback }
        val byName = HashMap<String, MutableList<Int>>()
        raw.forEachIndexed { idx, n -> byName.getOrPut(n) { ArrayList() } += idx }
        val out = raw.toMutableList()
        for ((n, idxs) in byName) {
            if (idxs.size <= 1) continue
            idxs.forEachIndexed { k, idx -> out[idx] = "$n (${k + 1})" }
        }
        return out
    }

    /** One display name per source: colliding names get the site host (Mihon parity),
     *  with a numeric suffix when the host is also identical (or absent). */
    private fun disambiguate(sources: List<MangaSource>, fallback: String): List<String> {
        val raw = sources.map { runCatching { it.name }.getOrNull()?.takeIf { n -> n.isNotBlank() } ?: fallback }
        val hosts = sources.map {
            runCatching {
                val http = it as? eu.kanade.tachiyomi.source.online.HttpSource ?: return@runCatching ""
                hostOf(http.baseUrl).orEmpty()
            }.getOrDefault("")
        }
        fun shortOf(h: String): String =
            h.removePrefix("www.").substringBefore('.').takeIf { it.isNotBlank() } ?: h
        // Indices sharing one raw name, so same-host subgroups can be numbered.
        val byName = HashMap<String, MutableList<Int>>()
        raw.forEachIndexed { idx, n -> byName.getOrPut(n) { ArrayList() } += idx }
        val out = raw.toMutableList()
        for ((n, idxs) in byName) {
            if (idxs.size <= 1) continue
            val shorts = idxs.map { shortOf(hosts.getOrNull(it).orEmpty()) }
            if (shorts.toSet().size <= 1) {
                // All blank or all the same host: number them ("MangaDot (1)" …).
                idxs.forEachIndexed { k, idx -> out[idx] = "$n (${k + 1})" }
            } else {
                // Distinct hosts: suffix each; number inside a shared host.
                val seenInHost = HashMap<String, Int>()
                for ((k, idx) in idxs.withIndex()) {
                    val s = shorts[k]
                    if (s.isBlank()) {
                        val i = (seenInHost[""] ?: 0) + 1
                        seenInHost[""] = i
                        out[idx] = "$n ($i)"
                    } else if (shorts.count { it == s } > 1) {
                        val i = (seenInHost[s] ?: 0) + 1
                        seenInHost[s] = i
                        out[idx] = "$n • $s ($i)"
                    } else {
                        out[idx] = "$n • $s"
                    }
                }
            }
        }
        return out
    }


    // ---- Site / icon ----

    private val siteCache = ConcurrentHashMap<String, String>()
    private val iconCache = ConcurrentHashMap<String, String>()

    fun siteUrlOf(config: ProviderConfig): String? {
        siteCache[config.id]?.let { return it }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return null
        // Bundled Kotatsu parsers have no .ext file — their site root is the
        // parser's own live domain.
        if (com.hikari.app.kotatsu.KotatsuRegistry.isKotatsuId(config.id)) {
            val url = runCatching {
                val bridge = com.hikari.app.kotatsu.KotatsuBridge.forSource(
                    com.hikari.app.kotatsu.KotatsuRegistry.enumNameOf(config.id),
                ) ?: return@runCatching null
                siteUrlForHost(hostOf(bridge.parserDomain()))
            }.getOrNull() ?: return null
            siteCache[config.id] = url
            return url
        }
        // Fork parsers (skepsun / vendored hany18h) likewise ship inside the
        // app — their site root is the fork parser's own live domain.
        if (com.hikari.app.kotatsu.fork.ForkRegistry.isForkMangaId(config.id)) {
            val url = runCatching {
                val domain = com.hikari.app.kotatsu.fork.ForkRegistry.domainOfForkManga(config.id)
                siteUrlForHost(hostOf(domain))
            }.getOrNull() ?: return null
            siteCache[config.id] = url
            return url
        }
        // Fork video rows (skepsun anime / vendored hany18h anime) likewise
        // ship inside the app — same live-domain site root.
        if (com.hikari.app.kotatsu.fork.ForkVideoProvider.isForkVideoId(config.id)) {
            val url = runCatching {
                val domain = com.hikari.app.kotatsu.fork.ForkRegistry.domainOfForkVideo(config.id)
                siteUrlForHost(hostOf(domain))
            }.getOrNull() ?: return null
            siteCache[config.id] = url
            return url
        }
        // A novel source exposes no baseUrl — there is no site to warm.
        if (isNovelId(config.id)) return null
        val url = runCatching {
            val file = File(config.url)
            if (!file.exists()) return@runCatching null
            val src = sourceOf(HikariApp.instance, file, indexOf(config))
                as? eu.kanade.tachiyomi.source.online.HttpSource
            siteUrlForHost(hostOf(src?.baseUrl))
        }.getOrNull() ?: return null
        siteCache[config.id] = url
        return url
    }

    /** "https://host/" for a host name. */
    fun siteUrlForHost(host: String?): String? = host?.takeIf { it.isNotBlank() }?.let { "https://$it/" }

    private fun hostOf(raw: String?): String? {
        val t = raw?.trim().orEmpty()
        if (t.isBlank()) return null
        val withScheme = if (t.startsWith("http")) t else "https://$t"
        val host = runCatching { java.net.URI(withScheme).host?.lowercase() }.getOrNull() ?: return null
        if (host.isBlank() || !host.contains(".")) return null
        return host
    }

    private fun faviconFor(siteUrl: String?): String? {
        val host = hostOf(siteUrl) ?: return null
        return "https://www.google.com/s2/favicons?domain=$host&sz=64"
    }

    fun iconFallback(config: ProviderConfig): String? {
        iconCache[config.id]?.let { return it }
        val icon = runCatching { faviconFor(siteUrlOf(config)) }.getOrNull() ?: return null
        iconCache[config.id] = icon
        return icon
    }

    // ---- Install / uninstall ----

    suspend fun install(
        context: Context,
        bytes: ByteArray,
        sourceUrl: String? = null,
        iconUrl: String? = null,
    ): Result<Int> = withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) return@withContext fail("The downloaded file is empty")
        if (bytes.size > MAX_BYTES) {
            return@withContext fail("File too large (max ${MAX_BYTES / 1024 / 1024}MB)")
        }
        val dir = extensionDir(context)
        val pkgName = runCatching {
            val f = File.createTempFile("manga-ext", ".apk", context.cacheDir)
            f.writeBytes(bytes)
            val info = inspect(context, f)
            f.delete()
            info?.packageName
        }.getOrNull() ?: return@withContext fail("The APK could not be read (not a valid extension?)")
        val target = File(dir, "$pkgName.$EXT")
        val temp = File(dir, "$pkgName.tmp")
        try {
            temp.writeBytes(bytes)
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.inputStream().use { input ->
                    target.outputStream().use { out -> input.copyTo(out) }
                }
                temp.delete()
            }
            cache.remove(target.absolutePath)
            val ext = extensionOf(context, target, force = true)
            if (ext == null || (ext.sources.isEmpty() && ext.novels.isEmpty())) {
                runCatching { target.delete() }
                cache.remove(target.absolutePath)
                return@withContext fail(
                    lastError ?: "The extension could not be loaded",
                    details = takeErrorDetails(),
                )
            }
            val icon = iconUrl
            val store = HikariApp.instance.store
            val prefix = "$PREFIX$pkgName|"
            val existing = store.providers()
            val prevById = existing.associateBy { it.id }
            val freshManga = ext.sources.indices.map { index ->
                val id = "$prefix$index"
                ProviderConfig(
                    id = id,
                    name = ext.labels.getOrNull(index) ?: ext.name,
                    type = ProviderType.MANGA,
                    url = target.absolutePath,
                    iconUrl = icon ?: prevById[id]?.iconUrl,
                    enabled = prevById[id]?.enabled ?: true,
                    lang = runCatching { ext.sources[index].lang }.getOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: prevById[id]?.lang,
                    // The repo URL (when it came from a repo) is what uninstall
                    // matches on; a file install falls back to the package name.
                    extra = sourceUrl ?: pkgName,
                )
            }
            // Novel (text-chapter) sources ride as MANGA rows with an `n`
            // index, so the manga tab lists them and the whole provider
            // plumbing (search scope, detail screen, library) works unchanged;
            // the provider's novel branch + the novel reader take it from here.
            val freshNovels = ext.novels.indices.map { index ->
                val id = "$prefix" + "n$index"
                ProviderConfig(
                    id = id,
                    name = ext.novelLabels.getOrNull(index) ?: ext.name,
                    type = ProviderType.MANGA,
                    url = target.absolutePath,
                    iconUrl = icon ?: prevById[id]?.iconUrl,
                    enabled = prevById[id]?.enabled ?: true,
                    lang = runCatching { ext.novels[index].lang }.getOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: prevById[id]?.lang,
                    extra = sourceUrl ?: pkgName,
                )
            }
            val fresh = freshManga + freshNovels
            store.updateProviders { list ->
                list.filterNot {
                    it.type == ProviderType.MANGA &&
                        (it.id.startsWith(prefix) || it.url == target.absolutePath)
                } + fresh
            }
            HikariApp.instance.providers.refresh()
            Result.success(fresh.size)
        } finally {
            if (temp.exists()) runCatching { temp.delete() }
        }
    }


    suspend fun backfillLangs(context: Context, store: com.hikari.app.data.AppStore): Boolean {
        val rows = store.providers().filter {
            it.type == com.hikari.app.data.ProviderType.MANGA && it.lang.isNullOrBlank() && isMangaProviderId(it.id)
        }
        if (rows.isEmpty()) return false
        var changed = false
        val byFile = LinkedHashMap<String, MutableList<com.hikari.app.data.ProviderConfig>>()
        for (r in rows) {
            val f = java.io.File(r.url)
            if (!f.isFile) continue
            byFile.getOrPut(f.absolutePath) { ArrayList() } += r
        }
        val fresh = LinkedHashMap<String, com.hikari.app.data.ProviderConfig>()
        for ((path, configs) in byFile) {
            val ext = runCatching { extensionOf(context, java.io.File(path)) }.getOrNull() ?: continue
            for (c in configs) {
                val lang = if (isNovelId(c.id)) {
                    runCatching { ext.novels.getOrNull(novelIndexOf(c))?.lang }.getOrNull()
                } else {
                    runCatching { ext.sources.getOrNull(indexOf(c))?.lang }.getOrNull()
                }?.trim()?.takeIf { it.isNotBlank() } ?: continue
                fresh[c.id] = c.copy(lang = lang)
                changed = true
            }
        }
        if (!changed) return false
        store.updateProviders { all -> all.map { fresh[it.id] ?: it } }
        return true
    }

    suspend fun pruneDeadFiles(app: HikariApp): Int {
        val root = app.filesDir.absolutePath
        val store = app.store
        val dead = runCatching { store.providers() }.getOrDefault(emptyList())
            .filter {
                (it.type == ProviderType.MANGA || it.type == ProviderType.ANIYOMI) &&
                    it.url.startsWith(root) && !File(it.url).isFile
            }
            .map { it.id }.toSet()
        if (dead.isEmpty()) return 0
        store.updateProviders { list -> list.filterNot { it.id in dead } }
        return dead.size
    }

    /** Removes every MANGA provider that came from [sourceUrl] plus its file. */
    suspend fun uninstall(context: Context, sourceUrl: String): Int {
        val store = HikariApp.instance.store
        val mine = store.providers().filter { it.type == ProviderType.MANGA && !com.hikari.app.sora.SoraMangaProvider.isSoraMangaProviderId(it.id) && it.extra == sourceUrl }
        if (mine.isEmpty()) return 0
        store.updateProviders { list ->
            list.filterNot { it.type == ProviderType.MANGA && !com.hikari.app.sora.SoraMangaProvider.isSoraMangaProviderId(it.id) && it.extra == sourceUrl }
        }
        HikariApp.instance.providers.refresh()
        withContext(Dispatchers.IO) {
            val keep = store.providers().filter { it.type == ProviderType.MANGA }.map { it.url }.toSet()
            mine.map { it.url }.distinct().forEach { path ->
                if (path !in keep) {
                    cache.remove(path)
                    runCatching { File(path).delete() }
                }
            }
        }
        return mine.size
    }

    /** Adds the manga repo once. Non-fatal on any failure. */
    suspend fun seedDefaults(context: Context, store: AppStore) {
        for ((url, name, desc) in DEFAULT_REPOS) {
            runCatching { store.seedCs3Repo(Cs3Repo(url, name, desc, RepoKind.ANIYOMI)) }
        }
    }

    /** True when the APK looks like a manga (Mihon/keiyoushi) extension. */
    fun isMangaApk(context: Context, bytes: ByteArray): Boolean = runCatching {
        val f = File.createTempFile("manga-probe", ".apk", context.cacheDir)
        try {
            f.writeBytes(bytes)
            val info = inspect(context, f) ?: return@runCatching false
            metaString(info, META_SOURCE_CLASS) != null || metaString(info, META_SOURCE_FACTORY) != null
        } finally {
            f.delete()
        }
    }.getOrDefault(false)

    // ---- Package inspection ----

    /** GET_META_DATA is what populates [PackageInfo.applicationInfo].metaData,
     *  which is where every key this loader reads lives. */
    private val PACKAGE_FLAGS = PackageManager.GET_META_DATA

    private fun inspect(context: Context, file: File): PackageInfo? = runCatching {
        context.packageManager.getPackageArchiveInfo(file.absolutePath, PACKAGE_FLAGS)
    }.getOrNull()

    private fun fixBasePaths(app: ApplicationInfo, apkPath: String) {
        if (app.sourceDir == null || app.sourceDir != apkPath) app.sourceDir = apkPath
        app.publicSourceDir = apkPath
        app.dataDir = apkPath
        app.nativeLibraryDir = File(File(apkPath).parentFile, "lib").absolutePath
    }

    private fun displayName(context: Context, info: PackageInfo): String? = runCatching {
        metaString(info, "tachiyomi.extension.name")
            ?: info.applicationInfo?.let { context.packageManager.getApplicationLabel(it).toString() }
    }.getOrNull()

    private fun metaString(info: PackageInfo, key: String): String? =
        runCatching { info.applicationInfo?.metaData?.getString(key) }.getOrNull()

    private fun metaInt(info: PackageInfo, key: String): Int = runCatching {
        val m = info.applicationInfo?.metaData ?: return 0
        when (val v = runCatching { m.getInt(key) }.getOrNull()) {
            null -> runCatching { m.getString(key)?.toIntOrNull() }.getOrNull() ?: 0
            else -> v
        }
    }.getOrDefault(0)

    private fun fail(msg: String, e: Throwable? = null, details: String? = null): Result<Int> {
        lastError = msg
        if (details != null) {
            val sb = errorDetails.get()
            sb.setLength(0)
            sb.append(details)
        }
        if (e != null) record(msg, e)
        return Result.failure(IllegalStateException(msg))
    }

    /** Sets [lastError] and yields null, for the loader's nullable paths. */
    private fun failLoad(msg: String, e: Throwable? = null): Nothing? {
        lastError = msg
        if (e != null) record(msg, e)
        return null
    }
}
