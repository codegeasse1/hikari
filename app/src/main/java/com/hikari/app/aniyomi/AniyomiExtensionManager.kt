package com.hikari.app.aniyomi

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import androidx.core.content.pm.PackageInfoCompat
import com.hikari.app.HikariApp
import com.hikari.app.core.LoadGate
import com.hikari.app.data.AppStore
import com.hikari.app.data.Cs3Repo
import com.hikari.app.data.Cs3RepoPlugin
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import com.hikari.app.data.RepoKind
import dalvik.system.PathClassLoader
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.util.system.ChildFirstPathClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * One display name per source: the source's own name, with a ` (n)` suffix on
 * every name that more than one source of the same extension carries.
 *
 * An Aniyomi extension is not one site — it can bundle a whole family of them
 * (`Jellyfin (1)…(3)` in the official index), and nothing stops two of them
 * from declaring the same `name`. Listed raw, those rows are indistinguishable:
 * nine sources called "AnimeWorld India" looked like the same extension
 * installed nine times. Suffixing only the colliding names (never a name that
 * is already unique) keeps every row honest and changes nothing for the
 * single-source extensions that make up most of the ecosystem.
 *
 * The suffix is the source's LANGUAGE whenever that tells the colliding sources
 * apart ("AnimeWorld India · Hindi" / "· English"), because that is what those
 * packs actually are — the same site published several times, once per audio
 * track — and a bare number says nothing about which one the user wants. A
 * language it already names in its own title is not repeated; when language does
 * not separate them, the site's host is used instead, and only then ` (n)`.
 */
private fun disambiguateSources(sources: List<AnimeSource>, fallback: String): List<String> {
    val base = sources.map { s ->
        runCatching { s.name }.getOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: fallback
    }
    val collisions = base.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    if (collisions.isEmpty()) return base
    val out = base.toMutableList()
    for (name in collisions) {
        val idxs = base.indices.filter { base[it] == name }
        // The first KIND of detail that is present on every row of this group
        // and differs between them all is the one used for the whole group.
        val kinds = listOf<(AnimeSource) -> String?>(
            { languageLabel(it) },
            { siteLabel(it) },
        )
        val picked = kinds.firstOrNull { kind ->
            val details = idxs.map { kind(sources[it]) }
            details.none { it.isNullOrBlank() } && details.distinct().size == details.size
        }
        idxs.forEachIndexed { k, i ->
            val detail = picked?.invoke(sources[i])
            out[i] = when {
                detail.isNullOrBlank() -> "$name (${k + 1})"
                // Don't repeat what the name already says: "AnimeWorld India
                // (Hindi) · Hindi" reads like a bug.
                squashName(name).contains(squashName(detail)) -> name
                else -> "$name · $detail"
            }
        }
    }
    return out
}

/** A readable name for a source's declared language, or null when it declares
 *  none (or one we have no name for). Aniyomi's multi-audio packs publish the
 *  same site once per language under one name — this is the detail that tells
 *  those rows apart. */
private fun languageLabel(s: AnimeSource): String? {
    val code = runCatching { s.lang }.getOrNull()?.trim()?.lowercase().orEmpty()
    return when (code) {
        "en", "eng", "english" -> "English"
        "hi", "hin", "hindi" -> "Hindi"
        "ta", "tam", "tamil" -> "Tamil"
        "te", "tel", "telugu" -> "Telugu"
        "ml", "mal", "malayalam" -> "Malayalam"
        "bn", "ben", "bengali" -> "Bengali"
        "ur", "urd", "urdu" -> "Urdu"
        "ja", "jp", "jpn", "japanese" -> "Japanese"
        "ko", "kor", "korean" -> "Korean"
        "zh", "chi", "chinese" -> "Chinese"
        "es", "spa", "spanish" -> "Spanish"
        "pt", "por", "portuguese" -> "Portuguese"
        "fr", "fre", "french" -> "French"
        "de", "ger", "german" -> "German"
        "it", "ita", "italian" -> "Italian"
        "ru", "rus", "russian" -> "Russian"
        "ar", "ara", "arabic" -> "Arabic"
        "id", "ind", "indonesian" -> "Indonesian"
        "th", "tha", "thai" -> "Thai"
        "vi", "vie", "vietnamese" -> "Vietnamese"
        "fil", "tl", "tgl", "tagalog", "filipino" -> "Filipino"
        // "all"/"multi" means the source itself carries several audio tracks, so
        // it is a real answer but a poor tiebreaker — better to fall through to
        // the site, and only then to a number.
        else -> null
    }
}

/** The site a source points at, without the scheme or `www.` — the second
 *  detail used to tell two same-named sources apart. */
private fun siteLabel(s: AnimeSource): String? {
    val raw = (s as? AnimeHttpSource)?.let { runCatching { it.baseUrl }.getOrNull() }
        ?.trim().orEmpty()
    if (raw.isBlank()) return null
    val withScheme = if (raw.startsWith("http")) raw else "https://$raw"
    return runCatching { java.net.URI(withScheme).host?.lowercase() }.getOrNull()
        ?.removePrefix("www.")?.takeIf { it.isNotBlank() }
}

/** Name comparison for the "don't repeat the language twice" check. */
private fun squashName(s: String): String =
    s.lowercase().replace(Regex("[^a-z0-9]+"), "")
/**
 * What makes two entries of one extension's source list the SAME source:
 * everything Aniyomi itself would use to tell them apart (its id, name, lang
 * and site). A list that repeats a source — a factory appending to a shared
 * list, a class named twice in the extension's metadata — collapses to the one
 * source it really is, while genuinely different sources always differ on at
 * least one of these and all survive.
 */
private fun sourceKey(s: AnimeSource): String {
    val id = runCatching { s.id }.getOrNull()
    val name = runCatching { s.name }.getOrNull()
    val lang = runCatching { s.lang }.getOrNull()
    val base = (s as? AnimeHttpSource)?.let { runCatching { it.baseUrl }.getOrNull() }
    return "$id|$name|$lang|$base"
}

/**
 * Installs and loads **Aniyomi** anime extensions (`aniyomix` `*.apk` files).
 *
 * An Aniyomi extension is a signed APK that declares the feature
 * `tachiyomi.animeextension` and names its `AnimeSource` subclasses in
 * `tachiyomi.animeextension.class` (`;`-separated, a leading `.` meaning
 * "relative to my package"). Loading one is NOT an Android package install: the
 * APK is copied to `filesDir/aniyomi/exts/<pkg>.ext`, made read-only (Android
 * 14+ refuses to load a writable dex), inspected with
 * `PackageManager.getPackageArchiveInfo`, and finally its classes are
 * instantiated through a [ChildFirstPathClassLoader] whose parent is Hikari's
 * own class loader — so the extension links against the vendored
 * `eu.kanade.tachiyomi.*` API layer that ships in this app
 * (`app/src/main/java/eu/kanade/tachiyomi/`), exactly the way the real Aniyomi
 * app does it.
 *
 * Each source of an extension becomes its own [ProviderType.ANIYOMI] provider
 * row (`aniyomi|<packageName>|<index>`), so one APK that bundles twelve sites
 * shows up as twelve sources, like it does in Aniyomi.
 *
 * Extensions are third-party code: every call into them can throw anything
 * (including `LinkageError`/`AbstractMethodError` from a source built against a
 * different extensions-lib), so loading is serialised per APK through
 * [LoadGate] and every failure becomes a short, human-readable [lastError] the
 * Extensions UI can show instead of an unexplained empty list.
 */
object AniyomiExtensionManager {

    /** Aniyomi extensions are a few hundred KB; this is a sanity bound only. */
    const val MAX_BYTES = 96L * 1024 * 1024

    private const val TAG = "AniyomiExt"

    /** File extension Aniyomi uses for the private copy of an extension. */
    private const val EXT = "ext"

    private const val EXTENSION_FEATURE = "tachiyomi.animeextension"
    private const val META_SOURCE_CLASS = "tachiyomi.animeextension.class"
    private const val META_NSFW = "tachiyomi.animeextension.nsfw"
    private const val META_TORRENT = "tachiyomi.animeextension.torrent"

    private const val META_NAME = "aniyomix.name"
    private const val META_EXT_LIB = "aniyomix.extensionLib"
    private const val META_CONTENT_WARNING = "aniyomix.contentWarning"
    private const val META_IS_TORRENT = "aniyomix.torrent"

    /**
     * The extensions-lib MAJOR versions Hikari can host. 14 and 16 are the older
     * APK layouts (still the bulk of the ecosystem), 17 is current, and 18 is
     * accepted ahead of time.
     *
     * Compared by MAJOR, never as a whole number. The metadata
     * (`aniyomix.extensionLib`) is an integer major, but the fallback is the
     * APK's `versionName`, which is a full version — "16.1", "14.4" — so an
     * exact compare rejected every extension built against a point release,
     * which is most of them. A 16.1 extension speaks the same lib as a 16.0 one.
     */
    private val MIN_LIB_MAJOR = 14
    private val MAX_LIB_MAJOR = 18

    /** A failed load is negative-cached for this long (see [extensionOf]). */
    private const val FAIL_RETRY_MS = 60_000L

    /**
     * The built-in Aniyomi extension repositories (Aniyomi's `index.min.json`
     * format). Only the official one is seeded — community Aniyomi repos are
     * add-it-yourself territory (Extensions → Add repo), and silently
     * bulk-installing a whole repo would be hostile.
     */
    val DEFAULT_REPOS = listOf(
        Triple(
            "https://raw.githubusercontent.com/aniyomiorg/aniyomi-extensions/repo/index.min.json",
            "Aniyomi",
            "The official Aniyomi extension repo",
        ),
    )

    // ---- Paths ----

    /** Where private extension copies live (`filesDir/aniyomi/exts`). */
    fun extensionDir(context: Context): File =
        File(context.filesDir, "aniyomi/exts").apply { mkdirs() }

    /** The private copy of an extension: `<pkgName>.ext`. */
    fun extensionFile(context: Context, pkgName: String): File =
        File(extensionDir(context), "$pkgName.$EXT")

    /** `aniyomi|<packageName>|<sourceIndex>` → its parts. */
    fun packageOf(config: ProviderConfig): String =
        config.id.removePrefix("aniyomi|").substringBefore('|')

    fun indexOf(config: ProviderConfig): Int =
        config.id.substringAfterLast('|').toIntOrNull() ?: 0

    // ---- Install / uninstall ----

    /**
     * Writes [bytes] as a private extension, validates it by loading it, and
     * registers one provider per source it publishes. Returns how many
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
        // The package name is only known AFTER the archive is parsed, so the
        // bytes land in a temp file inside the same directory first (which also
        // keeps the final rename a same-filesystem move).
        val temp = File(dir, "incoming-${System.nanoTime()}.apk")
        try {
            runCatching {
                temp.setWritable(true)
                temp.writeBytes(bytes)
                temp.setReadOnly()
            }.onFailure { return@withContext fail("Could not write the extension to storage", it) }

            val info = inspect(context, temp)
            if (info == null || !isExtension(info)) {
                return@withContext fail(
                    "Not an Aniyomi extension — the .apk doesn't declare the " +
                        "$EXTENSION_FEATURE feature"
                )
            }
            val pkgName = info.packageName
                ?: return@withContext fail("The extension package has no name")
            val newCode = PackageInfoCompat.getLongVersionCode(info)

            val target = extensionFile(context, pkgName)
            val installed = if (target.exists()) inspect(context, target) else null
            if (installed != null &&
                isExtension(installed) &&
                PackageInfoCompat.getLongVersionCode(installed) > newCode
            ) {
                return@withContext fail(
                    "A newer build of this extension is installed — downgrading is not allowed"
                )
            }

            target.delete()
            if (!temp.renameTo(target)) {
                runCatching { temp.copyTo(target, overwrite = true) }
                    .onFailure { return@withContext fail("Could not store the extension file", it) }
                temp.delete()
            }
            target.setReadOnly()

            cache.remove(target.absolutePath)
            val ext = extensionOf(context, target, force = true)
            if (ext == null || ext.sources.isEmpty()) {
                // A half-written or unloadable file must not linger: leaving it
                // behind would make fileMissing lie and the row un-fixable.
                runCatching { target.delete() }
                cache.remove(target.absolutePath)
                return@withContext fail(
                    lastError ?: "The extension could not be loaded",
                    details = takeErrorDetails(),
                )
            }

            val site = siteOf(ext.sources.firstOrNull())
            val icon = iconUrl ?: faviconFor(site)
            val store = HikariApp.instance.store
            val prefix = "aniyomi|$pkgName|"
            val existing = store.providers()
            val prevById = existing.associateBy { it.id }
            val fresh = ext.sources.indices.map { index ->
                val id = "$prefix$index"
                ProviderConfig(
                    id = id,
                    name = ext.labels.getOrNull(index) ?: ext.name,
                    type = ProviderType.ANIYOMI,
                    url = target.absolutePath,
                    iconUrl = icon,
                    // The user's own on/off choice survives a reinstall: the row
                    // is the same row (same id), it just got a fresh name.
                    enabled = prevById[id]?.enabled ?: true,
                    extra = sourceUrl ?: pkgName,
                )
            }
            // ONE write that REPLACES every row this package already had. Adding
            // the new rows one at a time used to leave the old rows behind when a
            // version published fewer sources (or when the same extension was
            // installed again), so the provider list grew a second copy of names
            // the user had already installed — the reported "I installed Anime
            // World again and now it lists six of the same provider".
            // Locked read-modify-write (see [AppStore.updateProviders]): the old
            // rows this replaces are looked up in the store at the moment of the
            // write, so a provider added while the extension was being unpacked
            // survives it.
            store.updateProviders { list ->
                list.filterNot { it.type == ProviderType.ANIYOMI && it.id.startsWith(prefix) } +
                    fresh
            }
            HikariApp.instance.providers.refresh()
            Result.success(fresh.size)
        } finally {
            if (temp.exists()) runCatching { temp.delete() }
        }
    }

    /** Removes every ANIYOMI provider that came from [sourceUrl], plus the
     *  `.ext` file once no provider references it any more. Returns how many
     *  installed providers were actually removed — 0 when the source was not
     *  installed — so the caller never reports a success for an uninstall that
     *  removed nothing. */
    suspend fun uninstall(context: Context, sourceUrl: String): Int {
        val store = HikariApp.instance.store
        val all = store.providers()
        val mine = all.filter { it.type == ProviderType.ANIYOMI && it.extra == sourceUrl }
        if (mine.isEmpty()) return 0
        // Locked read-modify-write: see [AppStore.updateProviders]. Rebuilding
        // from the snapshot read above would drop anything installed meanwhile.
        store.updateProviders { list ->
            list.filterNot { it.type == ProviderType.ANIYOMI && it.extra == sourceUrl }
        }
        HikariApp.instance.providers.refresh()
        withContext(Dispatchers.IO) {
            val keep = store.providers().filter { it.type == ProviderType.ANIYOMI }
                .map { it.url }
                .toSet()
            mine.map { it.url }.distinct().forEach { path ->
                if (path !in keep) {
                    cache.remove(path)
                    runCatching { File(path).delete() }
                }
            }
        }
        return mine.size
    }

    /** Whether the provider's `.ext` file is still on disk. */
    fun fileMissing(config: ProviderConfig): Boolean =
        config.type == ProviderType.ANIYOMI &&
            (config.url.isBlank() || !File(config.url).exists())

    // ---- Repos ----

    /** Adds the official Aniyomi repo once. Non-fatal on any failure. */
    suspend fun seedDefaults(context: Context, store: AppStore) {
        for ((url, name, desc) in DEFAULT_REPOS) {
            runCatching { store.addCs3Repo(Cs3Repo(url, name, desc, RepoKind.ANIYOMI)) }
        }
    }

    /**
     * One entry of an Aniyomi `index.min.json` — a BARE JSON ARRAY (not the
     * object with a `plugins` key the other repo kinds use, which is why this
     * kind needs its own parser):
     *
     * ```json
     * [{"name":"Aniyomi: Jellyfin","pkg":"…jellyfin","apk":"aniyomi-all.jellyfin-v14.17.apk",
     *   "lang":"all","code":17,"version":"14.17","nsfw":0,
     *   "sources":[{"name":"Jellyfin (1)","lang":"all","id":"…","baseUrl":""}]}]
     * ```
     *
     * `apk` is a FILENAME, and the repo serves the files from two subfolders of
     * the index's directory: the APK from `<root>/apk/<apk>` and the icon from
     * `<root>/icon/<pkg>.png` — see Aniyomi's `NetworkLegacyAnimeExtension`.
     * [baseUrl] is the repo root (the index URL minus its `index.min.json`).
     * The first source's `baseUrl` also gives the row a favicon without any
     * network call.
     */
    fun repoPlugin(o: JSONObject, baseUrl: String = ""): Cs3RepoPlugin? {
        val name = o.optString("name").ifBlank { o.optString("pkg") }
            .removePrefix("Aniyomi: ").trim()
        val apk = o.optString("apk")
        if (name.isBlank() || apk.isBlank()) return null
        val root = baseUrl.trimEnd('/')
        val pkg = o.optString("pkg").trim()
        val url = when {
            apk.startsWith("http://") || apk.startsWith("https://") -> apk
            root.isBlank() -> return null
            else -> "$root/apk/${apk.trimStart('/')}"
        }
        return Cs3RepoPlugin(
            name = name,
            description = listOfNotNull(
                o.optString("lang").ifBlank { null }?.uppercase(),
                o.optString("version").ifBlank { null }?.let { "v$it" },
                pkg.ifBlank { null },
            ).joinToString(" · "),
            url = url,
            iconUrl = o.optString("icon")
                .ifBlank { o.optString("iconUrl") }
                .ifBlank {
                    if (pkg.isNotBlank() && root.isNotBlank()) "$root/icon/$pkg.png" else ""
                }
                .ifBlank { null },
            version = o.optInt("code", 1).takeIf { it > 0 } ?: 1,
            iconHost = firstSourceHost(o),
        )
    }

    /**
     * The entries of an Aniyomi `index.min.json`, however the repo serves them.
     *
     * The official repo serves a BARE ARRAY. Community repos (and mirrors)
     * commonly wrap the same entries in an object — `{"extensions":[…]}`,
     * `{"plugins":[…]}` — or key them by package name. Demanding an array threw
     * "Invalid index.min.json: Value {…} of type JSONObject cannot be converted
     * to JSONArray" at the user, which is the error people hit adding an Aniyomi
     * repo whose index is perfectly valid. Returns null when the text really is
     * not an index.
     */
    fun indexEntries(text: String): JSONArray? {
        val trimmed = text.trim().removePrefix("\uFEFF").trim()
        if (trimmed.isEmpty()) return null
        runCatching { JSONArray(trimmed) }.getOrNull()?.let { return it }
        val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
        // An array under one of the names these indexes use.
        for (key in listOf("extensions", "plugins", "items", "data", "list", "apks", "scrapers")) {
            root.optJSONArray(key)?.let { if (it.length() > 0) return it }
        }
        // …or an object keyed by package name whose VALUES are the entries.
        val keyed = JSONArray()
        val names = root.keys()
        while (names.hasNext()) {
            val value = root.opt(names.next())
            if (value is JSONObject &&
                (value.has("apk") || value.has("sources") || value.has("pkg"))
            ) {
                keyed.put(value)
            }
        }
        if (keyed.length() > 0) return keyed
        // One more level: `{"data":{"extensions":[…]}}`.
        for (key in listOf("data", "repo", "index")) {
            root.optJSONObject(key)?.let { nested ->
                for (inner in listOf("extensions", "plugins", "items", "list")) {
                    nested.optJSONArray(inner)?.let { if (it.length() > 0) return it }
                }
            }
        }
        return null
    }

    /** The host of the first `sources[].baseUrl` of an index entry, if any —
     *  the key for the favicon fallback icon. */
    private fun firstSourceHost(o: JSONObject): String? {
        val sources = runCatching { o.getJSONArray("sources") }.getOrNull() ?: return null
        for (i in 0 until sources.length()) {
            val base = sources.optJSONObject(i)?.optString("baseUrl").orEmpty()
            hostOf(base)?.let { return it }
        }
        return null
    }

    /**
     * Turns whatever the user typed into "Add Aniyomi repo" into a URL. The
     * index file is `index.min.json`, so a bare repo directory is normalised to
     * `<dir>/index.min.json` (Aniyomi's repos publish it in a `repo` folder,
     * e.g. `…/aniyomi-extensions/repo/index.min.json`). Returns null when
     * nothing sensible could be built.
     */
    fun resolveRepoUrl(raw: String): String? {
        val text = raw.trim()
        if (text.isBlank()) return null
        val withScheme = when {
            text.startsWith("http://") || text.startsWith("https://") -> text
            text.startsWith("//") -> "https:$text"
            else -> {
                val scheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://(.+)$").find(text)
                when {
                    scheme != null -> "https://" + scheme.groupValues[1]
                    text.contains("/") ||
                        Regex("^[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)+").containsMatchIn(text) ->
                        "https://$text"
                    else -> return null
                }
            }
        }
        val trimmed = withScheme.trimEnd('/')
        if (trimmed.endsWith(".json", ignoreCase = true)) return trimmed
        return "$trimmed/index.min.json"
    }

    // ---- Loading ----

    /** A validated, loaded extension (metadata plus its instantiated sources). */
    class Extension(
        val file: File,
        val pkgName: String,
        val name: String,
        val versionName: String,
        val versionCode: Long,
        val libVersion: Double,
        val isNsfw: Boolean,
        val isTorrent: Boolean,
        val lang: String,
        val sources: List<AnimeSource>,
    ) {
        /**
         * The display name of each source, in [sources] order.
         *
         * One Aniyomi extension can bundle several sources (a "multipack"), and
         * some of them publish several sources under the SAME name. Read
         * straight from `source.name` those rows were indistinguishable — nine
         * identical "AnimeWorld India" rows read as the one extension installed
         * nine times, which is exactly what a user reported. A name more than
         * one source carries therefore gets Aniyomi's own ` (n)` suffix
         * (the same shape Aniyomi's index uses for "Jellyfin (1)…(3)"), so every
         * row in the list says which source it actually is.
         */
        val labels: List<String> = disambiguateSources(sources, name)
    }

    private val cache = ConcurrentHashMap<String, Extension>()
    private val lastFail = ConcurrentHashMap<String, Long>()

    /** Site URLs already resolved per provider id (see [siteUrlOf]). */
    private val siteCache = ConcurrentHashMap<String, String>()

    @Volatile
    var lastError: String? = null
        private set

    /**
     * Why THIS extension could not be loaded, keyed by its APK path.
     *
     * [lastError] is one global slot that whichever load finishes last
     * overwrites, and several extensions load concurrently (Home asks every
     * installed row at once). It therefore cannot answer "why is this one
     * blank?", which is exactly the question the Home empty state asks when
     * every Aniyomi row is missing. The per-extension reason is what turned
     * "check the WebView, it may be a Cloudflare check" into a real cause.
     */
    private val loadFailures = ConcurrentHashMap<String, String>()

    /** The load failure recorded for this extension's own APK, or null when it
     *  loaded (or has not been tried yet). */
    fun loadFailure(context: Context, config: ProviderConfig): String? =
        loadFailure(extensionFile(context, packageOf(config)))

    /** As above, for a caller that already resolved the APK itself (the
     *  provider prefers the path recorded at install time). */
    fun loadFailure(file: File): String? {
        val path = file.absolutePath
        if (cache.containsKey(path)) return null
        return loadFailures[path]
    }

    /** The reason [load] is about to fail with, on the loading thread. */
    private val loadError = ThreadLocal.withInitial<String?> { null }

    /** Per-thread detail of the load in progress (same contract as
     *  [com.hikari.app.cs3.Cs3PluginManager]). */
    private val errorDetails = ThreadLocal.withInitial { StringBuilder() }

    fun takeErrorDetails(): String = errorDetails.get().toString().trim()

    /**
     * Records a failed call INTO an extension (a hoster/video fetch that threw)
     * so the reason can be shown where the empty result is — [lastError] is the
     * one-line summary, [takeErrorDetails] the full trace. Used by
     * [AniyomiProvider], which composes many such calls per stream lookup.
     */
    fun recordError(what: String, e: Throwable? = null) {
        record(what, e)
        lastError = what
    }

    /**
     * The loaded extension for [file], or null (with [lastError] set). Loads
     * lazily and caches; a failed load is negative-cached for [FAIL_RETRY_MS]
     * so a broken extension can't stall every Home refresh.
     *
     * BLOCKING (it instantiates extension classes) — never call it on the UI
     * thread; a call there returns null immediately rather than risking an ANR.
     */
    fun extensionOf(context: Context, file: File, force: Boolean = false): Extension? {
        val path = file.absolutePath
        if (!force) cache[path]?.let { return it }
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        if (!file.exists()) {
            lastError = "Extension file missing — reinstall this extension"
            loadFailures[path] = lastError!!
            return null
        }
        if (!force) {
            val failAt = lastFail[path]
            if (failAt != null && System.currentTimeMillis() - failAt < FAIL_RETRY_MS) {
                return null
            }
        }
        // One lock PER APK: several providers of the same extension (and the
        // Extensions screen) can ask for it at once, and each load is a fresh
        // class-loader commit that must not race another.
        val lock = LoadGate.lockFor(path)
        if (!LoadGate.acquire(lock)) {
            lastError = "The extension loader is busy — try again"
            return null
        }
        try {
            if (!force) cache[path]?.let { return it }
            val ext = try {
                LoadGate.withSlot { load(context, file) }
            } catch (e: LoadGate.LoadQueueBusyException) {
                record("the extension loader is busy", e)
                null
            }
            if (ext != null) {
                cache[path] = ext
                lastFail.remove(path)
                loadFailures.remove(path)
            } else {
                lastFail[path] = System.currentTimeMillis()
                // The reason THIS extension failed, captured on the thread that
                // ran the load (see [loadFailures]) — WITH the full recorded
                // detail, so the UI can show the unwrapped cause and its frames
                // ("caused by java.lang.NoClassDefFoundError: …") instead of
                // only the one-line summary. That summary is all the row used to
                // carry, which is why a load failure read as
                // "none of its sources could be loaded" with nothing to act on.
                val why = loadError.get()
                    ?: lastError
                    ?: "The extension could not be loaded"
                val detail = takeErrorDetails()
                loadFailures[path] = if (detail.isBlank()) why else "$why\n$detail"
            }
            return ext
        } finally {
            lock.unlock()
        }
    }

    /** One source of an already-loaded extension (used by [AniyomiProvider]). */
    fun sourceOf(context: Context, file: File, index: Int): AnimeSource? =
        extensionOf(context, file)?.sources?.getOrNull(index)

    /**
     * The website an extension actually reads — its first source's `baseUrl`
     * (the Home screen's globe button needs a target). Deriving it means
     * loading the extension, so this caches aggressively and returns null on
     * the UI thread (loading a dex there would ANR — the same trade-off CS3
     * makes). [AniyomiProvider] warms the cache from its IO paths.
     */
    fun siteUrlOf(config: ProviderConfig): String? {
        siteCache[config.id]?.let { return it }
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val url = runCatching {
            val ext = extensionOf(HikariApp.instance, File(config.url)) ?: return@runCatching null
            siteOf(ext.sources.getOrNull(indexOf(config)))
        }.getOrNull() ?: return null
        siteCache[config.id] = url
        return url
    }

    /** `https://host/` for an "Aniyomi repo" entry (see [repoPlugin]). */
    fun siteUrlForHost(host: String?): String? = host?.takeIf { it.isNotBlank() }?.let { "https://$it/" }

    private fun siteOf(source: AnimeSource?): String? =
        siteUrlForHost((source as? AnimeHttpSource)?.baseUrl?.let { hostOf(it) })

    private fun hostOf(raw: String?): String? {
        val t = raw?.trim().orEmpty()
        if (t.isBlank()) return null
        val withScheme = if (t.startsWith("http")) t else "https://$t"
        val host = runCatching { java.net.URI(withScheme).host?.lowercase() }.getOrNull()
            ?: return null
        if (host.isBlank() || !host.contains(".")) return null
        return host
    }

    private fun faviconFor(siteUrl: String?): String? {
        val host = hostOf(siteUrl) ?: return null
        return "https://www.google.com/s2/favicons?domain=$host&sz=64"
    }

    /** Icons already resolved per provider id (see [iconFallback]). */
    private val iconCache = ConcurrentHashMap<String, String>()

    /**
     * The favicon of the site an installed extension reads, for a provider row
     * that has no icon of its own. An Aniyomi extension carries no artwork at
     * all — its only identity is the site it scrapes — so the favicon is the
     * honest answer, exactly the trick the CloudStream repos themselves use.
     * BLOCKING (deriving the host loads the extension), so call it from IO; the
     * result is cached per provider.
     */
    fun iconFallback(config: ProviderConfig): String? {
        iconCache[config.id]?.let { return it }
        // ConcurrentHashMap forbids null values — only cache hits.
        val icon = runCatching { faviconFor(siteUrlOf(config)) }.getOrNull() ?: return null
        iconCache[config.id] = icon
        return icon
    }

    // ---- The actual load ----

    private fun load(context: Context, file: File): Extension? {
        // The DI container an extension resolves its own dependencies out of
        // (`Injekt.get<Application>()` in its constructor, which is why a
        // failure here shows up as an `InvocationTargetException: null`). Cheap
        // when the scope has not changed — an identity compare — and the only
        // thing that repairs an extension loader whose Injekt scope was
        // replaced after startup.
        runCatching { HikariApp.instance.ensureAniyomiInjekt() }
        errorDetails.get().setLength(0)
        loadError.set(null)
        lastError = null
        // Android 14+ refuses to load a writable dex file; an extension
        // restored from a backup (or one whose copy lost its mode) must still
        // load.
        if (file.canWrite()) runCatching { file.setReadOnly() }

        val info = inspect(context, file)
        if (info == null || !isExtension(info)) {
            return failReason(
                "Not an Aniyomi extension — the file is unreadable, or it doesn't " +
                    "declare the $EXTENSION_FEATURE feature"
            )
        }
        val app = info.applicationInfo
            ?: return failReason("The extension package is malformed (no application info)")
        app.fixBasePaths(file.absolutePath)

        val pkgName = info.packageName
            ?: return failReason("The extension package has no name")
        val versionName = info.versionName.orEmpty()
        val libVersion = libVersionOf(info)
            ?: return failReason(
                "Can't tell which extensions-lib this was built against " +
                    "(its versionName is \"$versionName\") — refusing to load it"
            )
        val libVersionMajor = libVersion.toInt()
        if (libVersionMajor !in MIN_LIB_MAJOR..MAX_LIB_MAJOR) {
            return failReason(
                "Built against extensions-lib ${libVersion.toString().removeSuffix(".0")} — " +
                    "Hikari supports $MIN_LIB_MAJOR to $MAX_LIB_MAJOR"
            )
        }

        val name = displayName(context, info)
        val classLoader = runCatching {
            ChildFirstPathClassLoader(app.sourceDir, null, context.classLoader)
        }.getOrElse {
            return failReason("$name: could not open the extension's class loader", it)
        }

        val classNames = metaString(info, META_SOURCE_CLASS)
            ?.split(";")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        if (classNames.isEmpty()) {
            return failReason("$name declares no source class ($META_SOURCE_CLASS is empty)")
        }

        val sources = mutableListOf<AnimeSource>()
        for (entry in classNames) {
            val className = if (entry.startsWith(".")) pkgName + entry else entry
            instantiate(context, className, app.sourceDir, classLoader)?.let { sources += it }
        }
        if (sources.isEmpty()) {
            return failReason("$name: none of its sources could be loaded")
        }
        // Deduplicate BEFORE anything counts the sources: a list that names the
        // same source twice (a factory appending to a shared list, a class
        // repeated in the extension's metadata) is one source, and keeping the
        // copies is how a single-source extension produced a stack of identical
        // provider rows.
        val unique = sources.distinctBy { sourceKey(it) }

        val langs = unique.map { it.lang }.filter { it.isNotBlank() }.toSet()
        return Extension(
            file = file,
            pkgName = pkgName,
            name = name,
            versionName = versionName,
            versionCode = PackageInfoCompat.getLongVersionCode(info),
            libVersion = libVersion,
            isNsfw = metaInt(info, META_CONTENT_WARNING) > 0 || metaInt(info, META_NSFW) == 1,
            isTorrent = metaBool(info, META_IS_TORRENT) || metaInt(info, META_TORRENT) == 1,
            lang = when (langs.size) {
                0 -> ""
                1 -> langs.first()
                else -> "all"
            },
            sources = unique,
        )
    }

    /**
     * Instantiates one class name, mirroring Aniyomi's loader: an `AnimeSource`
     * is used directly, an `AnimeSourceFactory` is asked for its sources. A
     * `LinkageError` is retried with a plain [PathClassLoader] (parent-first),
     * because a child-first loader can shadow a class the extension itself
     * ships — the retry is exactly what the real app does.
     *
     * NOTE: a class whose superclass declares an abstract method its
     * extensions-lib didn't have (a 14-lib source missing, say,
     * `hosterListSelector`) still instantiates — ART only fails when that
     * method is actually called, which surfaces as an `AbstractMethodError`
     * from [AniyomiProvider] instead of an unintelligible install failure.
     */
    private fun instantiate(
        context: Context,
        className: String,
        apkPath: String,
        child: ClassLoader,
    ): List<AnimeSource>? {
        val loaders = listOf(child, PathClassLoader(apkPath, null, context.classLoader))
        for ((index, loader) in loaders.withIndex()) {
            var result: List<AnimeSource>? = null
            try {
                val instance = Class.forName(className, false, loader)
                    .getDeclaredConstructor()
                    .newInstance()
                result = when (instance) {
                    is AnimeSource -> listOf(instance)
                    is AnimeSourceFactory -> instance.createSources()
                    else -> {
                        record("$className is neither an AnimeSource nor an AnimeSourceFactory")
                        return null
                    }
                }
            } catch (e: LinkageError) {
                if (index == 0) continue
                record("$className could not be linked", e)
                return null
            } catch (e: Throwable) {
                record("$className could not be instantiated", e)
                return null
            }
            if (result.isNullOrEmpty()) {
                record("$className produced no sources")
                return null
            }
            return result
        }
        return null
    }

    // ---- Metadata helpers ----

    private val PACKAGE_FLAGS =
        PackageManager.GET_CONFIGURATIONS or PackageManager.GET_META_DATA

    private fun inspect(context: Context, file: File): PackageInfo? = runCatching {
        context.packageManager.getPackageArchiveInfo(file.absolutePath, PACKAGE_FLAGS)
    }.getOrNull()

    private fun isExtension(info: PackageInfo): Boolean =
        info.reqFeatures.orEmpty().any { it.name == EXTENSION_FEATURE }

    /**
     * On Android 13+ the `ApplicationInfo` from `getPackageArchiveInfo` has no
     * `sourceDir`, which breaks everything that reads the APK by path (the
     * class loader, `loadIcon`). Same fix-up as Aniyomi's loader.
     */
    private fun ApplicationInfo.fixBasePaths(apkPath: String) {
        if (sourceDir == null) sourceDir = apkPath
        if (publicSourceDir == null) publicSourceDir = apkPath
    }

    /** The extensions-lib the APK was built against: the `aniyomix.extensionLib`
     *  metadata, else the MAJOR of its `versionName` (Aniyomi's own fallback). */
    private fun libVersionOf(info: PackageInfo): Double? {
        metaInt(info, META_EXT_LIB).takeIf { it != 0 }?.let { return it.toDouble() }
        val name = info.versionName ?: return null
        // The first segment is the major. `substringBeforeLast('.')` read
        // "16.1.2" as 16.1 and "1.4.36" as 1.4 — the second is not a lib
        // version at all, and only the major is ever compared (see
        // [MIN_LIB_MAJOR]).
        return name.substringBefore('.').toDoubleOrNull()
    }

    /** The extension's display name: `aniyomix.name`, else the app label with
     *  Aniyomi's `"Aniyomi: "` prefix removed, else the package name. */
    fun displayName(context: Context, info: PackageInfo): String {
        metaString(info, META_NAME)?.let { return it }
        val label = runCatching {
            info.applicationInfo?.let { context.packageManager.getApplicationLabel(it).toString() }
        }.getOrNull().orEmpty()
        return label.substringAfter("Aniyomi: ").trim()
            .ifBlank { info.packageName ?: "extension" }
    }

    private fun metaString(info: PackageInfo, key: String): String? = runCatching {
        info.applicationInfo?.metaData?.getString(key)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun metaInt(info: PackageInfo, key: String): Int = runCatching {
        info.applicationInfo?.metaData?.getInt(key) ?: 0
    }.getOrDefault(0)

    private fun metaBool(info: PackageInfo, key: String): Boolean = runCatching {
        info.applicationInfo?.metaData?.getBoolean(key) ?: false
    }.getOrDefault(false)

    // ---- Failure reporting ----

    private fun failReason(msg: String, e: Throwable? = null): Extension? {
        lastError = msg
        loadError.set(msg)
        record(msg, e)
        return null
    }

    private fun fail(msg: String, e: Throwable? = null, details: String? = null): Result<Int> {
        lastError = msg
        if (e != null) record(msg, e)
        val text = listOfNotNull(
            msg,
            e?.let { "(${it.javaClass.simpleName}: ${it.message})" },
            details,
        ).filter { it.isNotBlank() }.joinToString(" ")
        android.util.Log.e(TAG, text)
        runCatching { com.hikari.app.data.Logs.logError(TAG, text, e) }
        return Result.failure(Exception(text))
    }

    private fun record(what: String, e: Throwable? = null) {
        // The CAUSE, not the wrapper. An extension's constructor that throws is
        // reported by the JVM as an `InvocationTargetException` whose message is
        // null — the useful line ("NetworkHelper could not be created",
        // "NoClassDefFoundError: …", "InjektionException: …") is one level
        // deeper. Printing only the wrapper is what made "ChineseAnime could not
        // be instantiated: java.lang.reflect.InvocationTargetException: null"
        // the whole story, with nothing to act on. The unwrapped cause is
        // printed with its own frames, so the next report names the real fault.
        val line = buildString {
            append(what)
            if (e != null) {
                append(": ${e.javaClass.name}: ${e.message}")
                val root = rootCause(e)
                if (root !== e) {
                    append("\n    caused by ${root.javaClass.name}: ${root.message}")
                    for (frame in root.stackTrace.take(6)) append("\n      at $frame")
                }
                for (frame in e.stackTrace.take(4)) append("\n    at $frame")
            }
        }
        if (errorDetails.get().length < 4000) errorDetails.get().append(line).append("\n")
        lastError = lastError ?: what
        android.util.Log.e(TAG, line, e)
    }

    /** The real fault behind [t], unwrapping the reflection/execution wrappers
     *  (`InvocationTargetException`, `ExecutionException`, a class-initialiser
     *  failure) that a reflective instantiation puts in front of it. */
    private fun rootCause(t: Throwable): Throwable {
        var cur = t
        var i = 0
        while (i++ < 8) {
            val next: Throwable? = when (cur) {
                is java.lang.reflect.InvocationTargetException -> cur.targetException
                is java.lang.ExceptionInInitializerError -> cur.exception
                else -> cur.cause
            }
            if (next == null || next === cur) break
            cur = next
        }
        return cur
    }
}
