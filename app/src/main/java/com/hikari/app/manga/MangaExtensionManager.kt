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

/**
 * Installs and loads **manga** extensions — the Mihon/Tachiyomi-format APKs the
 * whole manga world publishes (keiyoushi and the other mirrors of its repo are
 * the usual source of them).
 *
 * It is a sibling of [com.hikari.app.aniyomi.AniyomiExtensionManager], and for
 * the same reason: an extension is NOT an Android package install. The APK is
 * copied to `filesDir/manga/exts/<pkg>.ext`, made read-only (Android 14+ refuses
 * to load a writable dex), inspected with
 * `PackageManager.getPackageArchiveInfo`, and its classes are instantiated
 * through a [ChildFirstPathClassLoader] whose parent is Hikari's own class
 * loader — so the extension links against the `eu.kanade.tachiyomi.source.*` API
 * that ships in this app (`app/src/main/java/eu/kanade/tachiyomi/source/`),
 * exactly the way Mihon/Aniyomi do it.
 *
 * Two compiler styles are accepted, because both are in the wild:
 *
 *  1. **Mihon / Aniyomi style** — the manifest meta-data key
 *     `tachiyomi.extension.class` lists the entry classes (`;`-separated, a
 *     leading `.` meaning "relative to my package").
 *  2. **keiyoushi / Tadami style** — the extension ships a class literally named
 *     `ExtensionGenerated` in its own package (used when the metadata is absent).
 *
 * Each entry class is either a [MangaSource] itself or a [SourceFactory] whose
 * `createSources()` returns them. Each source becomes its own
 * [ProviderType.MANGA] provider row (`manga|<packageName>|<index>`), so one APK
 * bundling several sites shows up as several sources — the same shape as the
 * anime side.
 *
 * Extensions are third-party code: every call into them can throw anything
 * (including `LinkageError` from a source built against a different
 * extensions-lib), so loading is serialised per APK through [LoadGate] and every
 * failure becomes a short, human-readable message the Extensions UI can show.
 */
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

    /**
     * The built-in manga extension repositories. keiyoushi is the community
     * index the whole Mihon ecosystem reads; its `index.min.json` is a bare JSON
     * array in exactly the shape the Extensions screen already parses for the
     * Aniyomi repo, which is why a manga repo can be added through the same
     * "Aniyomi repo" flow.
     *
     * Only seeded once (see [seedDefaults]) and never bulk-installed: adding the
     * repo just makes the catalogue of manga extensions browsable.
     */
    val DEFAULT_REPOS = listOf(
        Triple(
            "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json",
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

    /** True for a provider id that came from this manager. */
    fun isMangaProviderId(id: String): Boolean = id.startsWith(PREFIX)

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
    )

    private val cache = ConcurrentHashMap<String, Extension>()
    private val failure = ConcurrentHashMap<String, Pair<Long, String>>()

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

    /**
     * The loaded extension for an APK file, or null (with [lastError] set).
     * BLOCKING — it instantiates extension classes, so never call it on the UI
     * thread.
     */
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

    /** The source a provider config points at. */
    fun sourceOf(context: Context, config: ProviderConfig): MangaSource? {
        if (!isMangaProviderId(config.id)) return null
        val file = File(config.url)
        if (!file.exists()) return null
        return sourceOf(context, file, indexOf(config))
    }

    /** Whether the provider's `.ext` file is still on disk. */
    fun fileMissing(config: ProviderConfig): Boolean =
        config.type == ProviderType.MANGA &&
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

        val sources = mutableListOf<MangaSource>()
        for (className in classNames) {
            instantiate(context, className, app.sourceDir, classLoader)?.let { sources += it }
        }
        if (sources.isEmpty()) return failLoad("$name: none of its sources could be loaded")
        val unique = sources.distinctBy { sourceKey(it) }
        val labels = disambiguate(unique, name)
        val langs = unique.mapNotNull { runCatching { it.lang }.getOrNull() }.filter { it.isNotBlank() }.toSet()
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
            sources = unique,
        )
    }

    /**
     * The classes to instantiate, in order: the `;`-separated list in the
     * manifest, then the single `tachiyomi.extension.factory` class, then
     * keiyoushi's `ExtensionGenerated` in the extension's own package. A leading
     * `.` in a metadata entry means "relative to my package".
     */
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

    /**
     * Instantiates one class: a [MangaSource] is used directly, a [SourceFactory]
     * is asked for its sources. A `LinkageError` is retried with a plain
     * parent-first `PathClassLoader`, exactly like the anime loader — a
     * child-first loader can shadow a class the extension itself ships.
     */
    private fun instantiate(
        context: Context,
        className: String,
        apkPath: String,
        child: ClassLoader,
    ): List<MangaSource>? {
        val loaders = listOf(child, dalvik.system.PathClassLoader(apkPath, null, context.classLoader))
        for ((index, loader) in loaders.withIndex()) {
            try {
                val instance = Class.forName(className, false, loader)
                    .getDeclaredConstructor()
                    .newInstance()
                val sources = when (instance) {
                    is MangaSource -> listOf(instance)
                    is SourceFactory -> instance.createSources().filterIsInstance<MangaSource>()
                    is Source -> {
                        // A plain Source with no manga methods is a novel source:
                        // this app has no text reader, so it is reported instead
                        // of being registered as a broken manga extension.
                        record("$className is a novel source (no manga chapters) — skipped")
                        emptyList()
                    }
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

    /** One display name per source: a ` (n)` suffix on colliding names only. */
    private fun disambiguate(sources: List<MangaSource>, fallback: String): List<String> {
        val raw = sources.map { runCatching { it.name }.getOrNull()?.takeIf { n -> n.isNotBlank() } ?: fallback }
        val counts = raw.groupingBy { it }.eachCount()
        val seen = HashMap<String, Int>()
        return raw.map { n ->
            if ((counts[n] ?: 0) <= 1) return@map n
            val i = (seen[n] ?: 0) + 1
            seen[n] = i
            "$n ($i)"
        }
    }


    // ---- Site / icon ----

    private val siteCache = ConcurrentHashMap<String, String>()
    private val iconCache = ConcurrentHashMap<String, String>()

    /**
     * The website an installed extension reads, for the Cloudflare-verification
     * WebView button. A manga provider's `url` is the LOCAL .ext path, so the
     * site has to come out of the extension's own source (`baseUrl`). BLOCKING
     * (deriving it loads the extension), and never on the main thread.
     */
    fun siteUrlOf(config: ProviderConfig): String? {
        siteCache[config.id]?.let { return it }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return null
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

    /**
     * The favicon of the site an installed extension reads, for a provider row
     * with no icon of its own — a manga extension carries no artwork either, and
     * the favicon is the honest answer (the trick the CloudStream repos use).
     * BLOCKING; cached per provider.
     */
    fun iconFallback(config: ProviderConfig): String? {
        iconCache[config.id]?.let { return it }
        val icon = runCatching { faviconFor(siteUrlOf(config)) }.getOrNull() ?: return null
        iconCache[config.id] = icon
        return icon
    }

    // ---- Install / uninstall ----

    /**
     * Writes [bytes] as a private manga extension, validates it by loading it,
     * and registers one provider per source it publishes. Returns how many
     * providers were added, or a failure whose message is shown verbatim.
     */
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
                target.writeBytes(temp.readBytes())
                temp.delete()
            }
            cache.remove(target.absolutePath)
            val ext = extensionOf(context, target, force = true)
            if (ext == null || ext.sources.isEmpty()) {
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
            val fresh = ext.sources.indices.map { index ->
                val id = "$prefix$index"
                ProviderConfig(
                    id = id,
                    name = ext.labels.getOrNull(index) ?: ext.name,
                    type = ProviderType.MANGA,
                    url = target.absolutePath,
                    iconUrl = icon ?: prevById[id]?.iconUrl,
                    enabled = prevById[id]?.enabled ?: true,
                    // The repo URL (when it came from a repo) is what uninstall
                    // matches on; a file install falls back to the package name.
                    extra = sourceUrl ?: pkgName,
                )
            }
            store.updateProviders { list ->
                list.filterNot { it.type == ProviderType.MANGA && it.id.startsWith(prefix) } + fresh
            }
            HikariApp.instance.providers.refresh()
            Result.success(fresh.size)
        } finally {
            if (temp.exists()) runCatching { temp.delete() }
        }
    }

    /** Removes every MANGA provider that came from [sourceUrl] plus its file. */
    suspend fun uninstall(context: Context, sourceUrl: String): Int {
        val store = HikariApp.instance.store
        val mine = store.providers().filter { it.type == ProviderType.MANGA && it.extra == sourceUrl }
        if (mine.isEmpty()) return 0
        store.updateProviders { list ->
            list.filterNot { it.type == ProviderType.MANGA && it.extra == sourceUrl }
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
            runCatching { store.addCs3Repo(Cs3Repo(url, name, desc, RepoKind.ANIYOMI)) }
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
