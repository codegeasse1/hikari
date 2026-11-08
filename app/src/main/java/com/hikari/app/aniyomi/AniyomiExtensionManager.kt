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
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

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
            details.distinct().size == details.size &&
                details.count { it.isNullOrBlank() } <= 1
        }
        idxs.forEachIndexed { k, i ->
            val detail = picked?.invoke(sources[i])
            out[i] = when {
                picked == null -> "$name (${k + 1})"
                detail.isNullOrBlank() -> name
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
 *  none that can tell two rows apart. Aniyomi's multi-audio packs publish the
 *  same site once per language under one name — this is the detail that tells
 *  those rows apart. */
private fun languageLabel(s: AnimeSource): String? {
    val raw = runCatching { s.lang }.getOrNull()?.trim()?.lowercase().orEmpty()
    if (raw.isBlank()) return null
    // Language tags arrive as "hi", "hi-IN" and "hi_IN" — the primary subtag is
    // the language; the region is not what separates two feeds of one site.
    val code = raw.substringBefore('-').substringBefore('_')
    LANGUAGE_NAMES[code]?.let { return it }
    if (code == "all" || code == "multi") return null
    return code.uppercase()
}

private val LANGUAGE_NAMES: Map<String, String> = mapOf(
    "en" to "English", "eng" to "English", "english" to "English",
    "hi" to "Hindi", "hin" to "Hindi", "hindi" to "Hindi",
    "bn" to "Bengali", "ben" to "Bengali", "bengali" to "Bengali",
    "ta" to "Tamil", "tam" to "Tamil", "tamil" to "Tamil",
    "te" to "Telugu", "tel" to "Telugu", "telugu" to "Telugu",
    "ml" to "Malayalam", "mal" to "Malayalam", "malayalam" to "Malayalam",
    "mr" to "Marathi", "mar" to "Marathi", "marathi" to "Marathi",
    "kn" to "Kannada", "kan" to "Kannada", "kannada" to "Kannada",
    "gu" to "Gujarati", "guj" to "Gujarati", "gujarati" to "Gujarati",
    "pa" to "Punjabi", "pan" to "Punjabi", "punjabi" to "Punjabi",
    "or" to "Odia", "ori" to "Odia", "odia" to "Odia", "oriya" to "Odia",
    "as" to "Assamese", "asm" to "Assamese", "assamese" to "Assamese",
    "sa" to "Sanskrit", "san" to "Sanskrit",
    "ne" to "Nepali", "nep" to "Nepali", "nepali" to "Nepali",
    "si" to "Sinhala", "sin" to "Sinhala", "sinhala" to "Sinhala",
    "ur" to "Urdu", "urd" to "Urdu", "urdu" to "Urdu",
    "ja" to "Japanese", "jp" to "Japanese", "jpn" to "Japanese",
    "japanese" to "Japanese",
    "ko" to "Korean", "kor" to "Korean", "korean" to "Korean",
    "zh" to "Chinese", "chi" to "Chinese", "zho" to "Chinese",
    "chinese" to "Chinese",
    "es" to "Spanish", "spa" to "Spanish", "spanish" to "Spanish",
    "pt" to "Portuguese", "por" to "Portuguese", "portuguese" to "Portuguese",
    "fr" to "French", "fre" to "French", "fra" to "French", "french" to "French",
    "de" to "German", "ger" to "German", "deu" to "German", "german" to "German",
    "it" to "Italian", "ita" to "Italian", "italian" to "Italian",
    "ru" to "Russian", "rus" to "Russian", "russian" to "Russian",
    "ar" to "Arabic", "ara" to "Arabic", "arabic" to "Arabic",
    "fa" to "Persian", "fas" to "Persian", "per" to "Persian",
    "he" to "Hebrew", "iw" to "Hebrew", "heb" to "Hebrew",
    "tr" to "Turkish", "tur" to "Turkish", "turkish" to "Turkish",
    "id" to "Indonesian", "ind" to "Indonesian", "indonesian" to "Indonesian",
    "ms" to "Malay", "msa" to "Malay", "may" to "Malay",
    "th" to "Thai", "tha" to "Thai", "thai" to "Thai",
    "vi" to "Vietnamese", "vie" to "Vietnamese", "vietnamese" to "Vietnamese",
    "fil" to "Filipino", "tl" to "Filipino", "tgl" to "Filipino",
    "tagalog" to "Filipino", "filipino" to "Filipino",
    "ceb" to "Cebuano", "my" to "Burmese", "mya" to "Burmese",
    "km" to "Khmer", "khm" to "Khmer", "lo" to "Lao", "lao" to "Lao",
    "sw" to "Swahili", "swa" to "Swahili", "ha" to "Hausa", "hau" to "Hausa",
    "am" to "Amharic", "amh" to "Amharic", "zu" to "Zulu", "zul" to "Zulu",
    "af" to "Afrikaans", "afr" to "Afrikaans",
    "pl" to "Polish", "pol" to "Polish", "nl" to "Dutch", "dut" to "Dutch",
    "nld" to "Dutch", "sv" to "Swedish", "swe" to "Swedish",
    "no" to "Norwegian", "nor" to "Norwegian", "nb" to "Norwegian",
    "da" to "Danish", "dan" to "Danish", "fi" to "Finnish", "fin" to "Finnish",
    "cs" to "Czech", "ces" to "Czech", "cze" to "Czech",
    "sk" to "Slovak", "slk" to "Slovak", "hu" to "Hungarian", "hun" to "Hungarian",
    "ro" to "Romanian", "ron" to "Romanian", "rum" to "Romanian",
    "bg" to "Bulgarian", "bul" to "Bulgarian",
    "el" to "Greek", "ell" to "Greek", "gre" to "Greek",
    "uk" to "Ukrainian", "ukr" to "Ukrainian",
    "sr" to "Serbian", "srp" to "Serbian",
    "hr" to "Croatian", "hrv" to "Croatian",
    "sl" to "Slovenian", "slv" to "Slovenian",
    "sq" to "Albanian", "sqi" to "Albanian",
    "mk" to "Macedonian", "mkd" to "Macedonian",
    "lt" to "Lithuanian", "lav" to "Latvian", "lv" to "Latvian",
    "et" to "Estonian", "est" to "Estonian",
    "az" to "Azerbaijani", "aze" to "Azerbaijani",
    "kk" to "Kazakh", "kaz" to "Kazakh", "uz" to "Uzbek", "uzb" to "Uzbek",
    "ca" to "Catalan", "cat" to "Catalan", "gl" to "Galician", "gle" to "Irish",
    "eu" to "Basque", "ka" to "Georgian", "kat" to "Georgian",
    "hy" to "Armenian", "hye" to "Armenian",
)

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
private fun sourceKey(s: AnimeSource): String {
    val id = runCatching { s.id }.getOrNull()
    val name = runCatching { s.name }.getOrNull()
    val lang = runCatching { s.lang }.getOrNull()
    val base = (s as? AnimeHttpSource)?.let { runCatching { it.baseUrl }.getOrNull() }
    return "$id|$name|$lang|$base"
}

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

    private val MIN_LIB_MAJOR = 14
    private val MAX_LIB_MAJOR = 18

    /** A failed load is negative-cached for this long (see [extensionOf]). */
    private const val FAIL_RETRY_MS = 60_000L

    val DEFAULT_REPOS = listOf(
        Triple(
            "https://raw.githubusercontent.com/aniyomiorg/aniyomi-extensions/repo/index.min.json",
            "Aniyomi",
            "The official Aniyomi extension repo",
        ),
    )

    // ---- Index files (which file a repo's extension list actually lives in) ----

    val INDEX_FILE_NAMES = listOf(
        "index.json",
        "index.min.json",
        "repo.json",
        "plugins.json",
        "plugins.min.json",
    )

    val PB_INDEX_FILE_NAMES = listOf("index.pb", "index.min.pb", "repo.pb")

    val ALL_INDEX_FILE_NAMES = INDEX_FILE_NAMES + PB_INDEX_FILE_NAMES

    /** True when [url] points at a repo index FILE rather than at a repo folder. */
    fun isIndexUrl(url: String): Boolean {
        val trimmed = url.trim().trimEnd('/')
        if (ALL_INDEX_FILE_NAMES.any { trimmed.endsWith("/$it", ignoreCase = true) }) return true
        // A repo can publish its list under ANY file name - anything ending
        // in '.json'/'.pb' is already a file, never a folder to append to.
        val last = trimmed.substringAfterLast('/')
        return last.endsWith(".json", ignoreCase = true) ||
            last.endsWith(".pb", ignoreCase = true)
    }

    fun indexDirFor(indexUrl: String): String {
        var url = indexUrl.trim().trimEnd('/')
        for (name in ALL_INDEX_FILE_NAMES) {
            if (url.endsWith("/$name", ignoreCase = true)) {
                url = url.dropLast(name.length + 1).trimEnd('/')
                break
            }
        }
        // Same for a custom-named index file: its apk/ + icon/ folders hang
        // off the parent dir, so the file name is stripped for installs.
        val last = url.substringAfterLast('/')
        if (url.contains("/") &&
            !last.contains("?") && !last.contains("#") &&
            (last.endsWith(".json", ignoreCase = true) ||
                last.endsWith(".pb", ignoreCase = true))
        ) {
            url = url.substringBeforeLast('/').trimEnd('/')
        }
        return url
    }

    fun indexCandidatesFor(url: String): List<String> {
        val clean = url.trim().trimEnd('/')
        if (clean.isEmpty()) return emptyList()
        if (clean.endsWith(".pb", ignoreCase = true)) {
            val stem = clean.dropLast(3)
            val min = if (stem.endsWith(".min", ignoreCase = true)) null else "$stem.min.json"
            return listOfNotNull("$stem.json", min, clean)
        }
        if (clean.endsWith(".json", ignoreCase = true)) {
            // A direct index FILE is fetched as-is first - never treated as
            // a folder. Custom names fall back to the standard files after.
            val fileDir = indexDirFor(clean)
            val fileName = clean.substringAfterLast('/')
            val sibs = ALL_INDEX_FILE_NAMES.filterNot { it.equals(fileName, ignoreCase = true) }
            val head = when {
                fileName.equals("index.min.json", ignoreCase = true) -> listOf("$fileDir/index.json")
                fileName.endsWith(".min.json", ignoreCase = true) ->
                    listOf("$fileDir/" + fileName.dropLast(".min.json".length) + ".json", clean)
                else -> listOf(clean)
            }
            return (head + sibs.map { "$fileDir/$it" }).distinct()
        }
        if (!isIndexUrl(clean)) return ALL_INDEX_FILE_NAMES.map { "$clean/$it" }
        val dir = indexDirFor(clean)
        val given = clean.substringAfterLast('/')
        val others = ALL_INDEX_FILE_NAMES.filterNot { it.equals(given, ignoreCase = true) }
        val order = if (given.equals("index.min.json", ignoreCase = true)) {
            listOf("index.json") + others
        } else {
            listOf(given) + others
        }
        return order.map { "$dir/$it" }
    }

    fun looksLikeStub(entries: JSONArray): Boolean {
        if (entries.length() == 0) return true
        for (i in 0 until entries.length()) {
            val o = entries.optJSONObject(i) ?: return false
            val pkg = indexPkgOf(o)
            val name = o.optString("name").trim()
            val stubPkg = pkg.equals("eu.kanade.tachiyomi.extension.all.keiyoushi", true) ||
                pkg.equals("eu.kanade.tachiyomi.extension.all.mihon", true)
            val stubName = name.equals("Outdated App", true) ||
                name.startsWith("Update to Mihon", true) ||
                name.startsWith("Update to Tachiyomi", true)
            if (!stubPkg && !stubName) return false
        }
        return true
    }

    /** The package name of an index entry, whichever of the two formats it is in. */
    private fun indexPkgOf(o: JSONObject): String =
        o.optString("pkg").ifBlank { o.optString("packageName") }.trim()

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
                    lang = runCatching { ext.sources[index].lang }.getOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: prevById[id]?.lang,
                    extra = sourceUrl ?: pkgName,
                )
            }
            store.updateProviders { list ->
                list.filterNot { it.type == ProviderType.ANIYOMI && it.id.startsWith(prefix) } +
                    fresh
            }
            HikariApp.instance.providers.refresh()
            // A new extension build may change provider-opaque episode payloads — retire cached ones (see MetaCache).
            runCatching { com.hikari.app.data.MetaCache.bumpEpisodesEpoch() }
            Result.success(fresh.size)
        } finally {
            if (temp.exists()) runCatching { temp.delete() }
        }
    }

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
            runCatching { store.seedCs3Repo(Cs3Repo(url, name, desc, RepoKind.ANIYOMI)) }
        }
    }

    /**
     * One entry of a Mihon/Aniyomi extension index, in EITHER of the two shapes
     * the ecosystem publishes.
     *
     * **Legacy** (Aniyomi's `index.min.json`, and every mirror of it) — a BARE
     * JSON ARRAY (not the object with a `plugins` key the other repo kinds use,
     * which is why this kind needs its own parser):
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
     * [baseUrl] is the repo root ([indexDirFor] of the index URL); the first
     * source's `baseUrl` also gives the row a favicon without any network call.
     *
     * **Modern** (keiyoushi's `index.json`, the shape Mihon 0.20.1+ reads) —
     * the entries live under `extensionList.extensions` (see [indexEntries])
     * and carry ABSOLUTE URLs plus a string `versionCode`:
     *
     * ```json
     * {"packageName":"…all.ahottie","resources":{"apkUrl":"https://github.com/…/x.apk",
     *   "iconUrl":"https://cdn.jsdelivr.net/…/ic_launcher.png","jarUrl":"…"},
     *   "extensionLib":"1.6","versionCode":"106004","versionName":"1.6.4",
     *   "contentWarning":"CONTENT_WARNING_NSFW",
     *   "sources":[{"id":"…","name":"AHottie","language":"all","homeUrl":"https://ahottie.top"}]}
     * ```
     *
     * Both are read here so a keiyoushi-style repo lists and installs exactly
     * like an Aniyomi one: the row text, the install URL and the icon URL are
     * built the same way from either shape.
     */
    fun repoPlugin(o: JSONObject, baseUrl: String = ""): Cs3RepoPlugin? {
        val resources = o.optJSONObject("resources")
        val pkg = indexPkgOf(o)
        val name = o.optString("name").ifBlank { pkg }
            .removePrefix("Aniyomi: ").trim()
        // `apk` (legacy — a file name, or a full URL in repos that publish
        // absolute ones) or `resources.apkUrl` (modern — always a full URL).
        val apk = o.optString("apk").ifBlank { resources?.optString("apkUrl").orEmpty() }.trim()
        if (name.isBlank() || apk.isBlank()) return null
        val root = baseUrl.trimEnd('/')
        val url = when {
            apk.startsWith("http://") || apk.startsWith("https://") -> apk
            root.isBlank() -> return null
            else -> "$root/apk/${apk.trimStart('/')}"
        }
        val icon = o.optString("icon")
            .ifBlank { o.optString("iconUrl") }
            .ifBlank { resources?.optString("iconUrl").orEmpty() }
            .trim()
        val iconUrl = when {
            icon.startsWith("http://") || icon.startsWith("https://") -> icon
            icon.isNotBlank() && root.isNotBlank() -> "$root/${icon.trimStart('/')}"
            pkg.isNotBlank() && root.isNotBlank() -> "$root/icon/$pkg.png"
            else -> null
        }
        // The modern format carries no top-level `lang` — the sources each name
        // their own (`language`), which is also what the row should show.
        val lang = o.optString("lang").ifBlank { firstSourceLang(o) }
        val versionName = o.optString("version").ifBlank { o.optString("versionName") }
        val versionCode = o.optInt("code", 0).takeIf { it > 0 }
            ?: o.optString("versionCode").toIntOrNull()
            ?: 1
        return Cs3RepoPlugin(
            name = name,
            description = listOfNotNull(
                lang.ifBlank { null }?.uppercase(),
                versionName.ifBlank { null }?.let { "v$it" },
                pkg.ifBlank { null },
            ).joinToString(" · "),
            url = url,
            iconUrl = iconUrl,
            version = versionCode,
            iconHost = firstSourceHost(o),
            contentKind = contentKindOf(o),
            pkg = pkg,
            // The repo's own 18+ tag (see ExtensionNsfw.repoEntryNsfw) — what
            // the adult-content switch hides from the installable list.
            nsfw = com.hikari.app.data.ExtensionNsfw.repoEntryNsfw(o),
        )
    }

    fun contentKindOf(o: JSONObject): String {
        val modern = o.optJSONObject("resources") != null ||
            o.optString("extensionLib").isNotBlank() ||
            o.optString("versionCode").isNotBlank()
        return if (modern) "manga" else "anime"
    }

    fun indexEntries(text: String): JSONArray? {
        val trimmed = text.trim().removePrefix("\uFEFF").trim()
        if (trimmed.isEmpty()) return null
        runCatching { JSONArray(trimmed) }.getOrNull()?.let { return it }
        val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
        root.optJSONArray("extensionList")?.let { if (it.length() > 0) return it }
        root.optJSONObject("extensionList")?.let { list ->
            for (inner in listOf("extensions", "plugins", "items", "list")) {
                list.optJSONArray(inner)?.let { if (it.length() > 0) return it }
            }
        }
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
                (value.has("apk") || value.has("sources") || value.has("pkg") ||
                    value.has("packageName") || value.has("resources"))
            ) {
                keyed.put(value)
            }
        }
        if (keyed.length() > 0) return keyed
        // One more level: `{"data":{"extensions":[…]}}` /
        // `{"extensionList":{"extensions":[…]}}` behind another object.
        for (key in listOf("data", "repo", "index", "extensionList")) {
            root.optJSONObject(key)?.let { nested ->
                for (inner in listOf("extensions", "plugins", "items", "list")) {
                    nested.optJSONArray(inner)?.let { if (it.length() > 0) return it }
                }
            }
        }
        return null
    }

    class PbIndex(val name: String, val entries: JSONArray)

    fun pbIndex(bytes: ByteArray): PbIndex? {
        val raw = gunzipIfNeeded(bytes) ?: return null
        val top = pbFields(raw) ?: return null
        val storeName = pbString(top[1]?.firstOrNull())
        val list = top[101]?.firstOrNull()?.bytes?.let { pbFields(it) } ?: return null
        val entries = list[1].orEmpty()
        if (entries.isEmpty()) return null
        val arr = JSONArray()
        for (entry in entries) {
            val f = entry.bytes?.let { pbFields(it) } ?: continue
            val name = pbString(f[1]?.firstOrNull())
            val pkg = pbString(f[2]?.firstOrNull())
            if (name.isBlank() || pkg.isBlank()) continue
            val resources = JSONObject()
            f[3]?.firstOrNull()?.bytes?.let { r ->
                val rf = pbFields(r)
                val apk = pbString(rf?.get(1)?.firstOrNull())
                val icon = pbString(rf?.get(2)?.firstOrNull())
                if (apk.isNotBlank()) resources.put("apkUrl", apk)
                if (icon.isNotBlank()) resources.put("iconUrl", icon)
            }
            val sources = JSONArray()
            for (s in f[8].orEmpty()) {
                val sf = s.bytes?.let { pbFields(it) } ?: continue
                val lang = pbString(sf[3]?.firstOrNull())
                val home = pbString(sf[4]?.firstOrNull())
                sources.put(
                    JSONObject()
                        .put("id", (sf[1]?.firstOrNull()?.varint ?: 0L).toString())
                        .put("name", pbString(sf[2]?.firstOrNull()))
                        .put("lang", lang)
                        .put("language", lang)
                        .put("baseUrl", home)
                        .put("homeUrl", home)
                )
            }
            arr.put(
                JSONObject()
                    .put("name", name)
                    .put("packageName", pkg)
                    // extensionLib (field 4) is the library version the extension
                    // was built against ("1.6"); versionName (field 6) is the
                    // extension's own version ("1.6.4"), and versionCode (field 5)
                    // pins it.
                    .put("extensionLib", pbString(f[4]?.firstOrNull()))
                    .put("versionCode", (f[5]?.firstOrNull()?.varint ?: 0L).toString())
                    .put("versionName", pbString(f[6]?.firstOrNull()))
                    // contentWarning (field 7) is Mihon's enum: 0 unspecified,
                    // 1 safe, 2 mixed, 3 nsfw.
                    .put(
                        "contentWarning",
                        contentWarningName((f[7]?.firstOrNull()?.varint ?: 0L).toInt()),
                    )
                    .put("resources", resources)
                    .put("sources", sources)
            )
        }
        return if (arr.length() == 0) null else PbIndex(storeName.trim(), arr)
    }

    /** Mihon's `ContentWarning` enum value, spelt the way a JSON index spells it
     *  (`CONTENT_WARNING_NSFW`) — which is what
     *  [com.hikari.app.data.ExtensionNsfw] reads. */
    private fun contentWarningName(level: Int): String = when (level) {
        1 -> "CONTENT_WARNING_SAFE"
        2 -> "CONTENT_WARNING_MIXED"
        3 -> "CONTENT_WARNING_NSFW"
        else -> ""
    }

    /** One protobuf field: its wire type and payload (a varint, or the raw bytes
     *  of a length-delimited field — a string or a nested message). */
    private class PbField(val wireType: Int, val varint: Long, val bytes: ByteArray?)

    private fun pbFields(msg: ByteArray): Map<Int, List<PbField>>? {
        val out = HashMap<Int, MutableList<PbField>>()
        var i = 0
        while (i < msg.size) {
            val key = pbVarint(msg, i) ?: return null
            i = key.second
            val field = (key.first ushr 3).toInt()
            when ((key.first and 7L).toInt()) {
                0 -> {
                    val v = pbVarint(msg, i) ?: return null
                    i = v.second
                    out.getOrPut(field) { mutableListOf() }.add(PbField(0, v.first, null))
                }
                2 -> {
                    val len = pbVarint(msg, i) ?: return null
                    val from = len.second
                    val to = from + len.first.toInt()
                    if (len.first < 0L || to > msg.size || to < from) return null
                    out.getOrPut(field) { mutableListOf() }
                        .add(PbField(2, 0L, msg.copyOfRange(from, to)))
                    i = to
                }
                // 64-bit and 32-bit fixed fields are legal protobuf but unused by
                // this schema; skipping them keeps a future field from breaking
                // the whole index.
                1 -> {
                    if (i + 8 > msg.size) return null
                    i += 8
                }
                5 -> {
                    if (i + 4 > msg.size) return null
                    i += 4
                }
                else -> return null
            }
        }
        return out
    }

    /** A base-128 varint at [from], with the offset just past it; null when the
     *  bytes run out mid-value. */
    private fun pbVarint(buf: ByteArray, from: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var i = from
        while (i < buf.size && shift <= 63) {
            val b = buf[i++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result to i
            shift += 7
        }
        return null
    }

    /** The UTF-8 text of a length-delimited field, or "" for anything else. */
    private fun pbString(f: PbField?): String =
        f?.bytes?.let { runCatching { String(it, Charsets.UTF_8) }.getOrDefault("") }.orEmpty()

    /** The bytes as they are, or gunzipped when they are a gzip stream (which is
     *  how every published `.pb` index arrives). Null when decompression fails. */
    private fun gunzipIfNeeded(bytes: ByteArray): ByteArray? = runCatching {
        if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        } else {
            bytes
        }
    }.getOrNull()

    /** The host of the first source's URL of an index entry, if any — the key
     *  for the favicon fallback icon. `baseUrl` is the legacy field name,
     *  `homeUrl` the modern one. */
    private fun firstSourceHost(o: JSONObject): String? {
        val sources = runCatching { o.getJSONArray("sources") }.getOrNull() ?: return null
        for (i in 0 until sources.length()) {
            val s = sources.optJSONObject(i) ?: continue
            val base = s.optString("baseUrl").ifBlank { s.optString("homeUrl") }
            hostOf(base)?.let { return it }
        }
        return null
    }

    /** The language of the first source that declares one — the modern index
     *  format has no top-level `lang`, only per-source `language`. */
    private fun firstSourceLang(o: JSONObject): String {
        val sources = runCatching { o.getJSONArray("sources") }.getOrNull() ?: return ""
        for (i in 0 until sources.length()) {
            val s = sources.optJSONObject(i) ?: continue
            val lang = s.optString("lang").ifBlank { s.optString("language") }
            if (lang.isNotBlank()) return lang
        }
        return ""
    }

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
        if (trimmed.endsWith(".json", ignoreCase = true) ||
            trimmed.endsWith(".pb", ignoreCase = true)
        ) return trimmed
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
        val labels: List<String> = disambiguateSources(sources, name)
    }

    private val cache = ConcurrentHashMap<String, Extension>()
    private val lastFail = ConcurrentHashMap<String, Long>()

    fun evict(path: String) {
        cache.remove(path)
        lastFail.remove(path)
        loadFailures.remove(path)
    }

    /** Site URLs already resolved per provider id (see [siteUrlOf]). */
    private val siteCache = ConcurrentHashMap<String, String>()

    @Volatile
    var lastError: String? = null
        private set

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

    fun recordError(what: String, e: Throwable? = null) {
        record(what, e)
        lastError = what
    }

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

    fun iconFallback(config: ProviderConfig): String? {
        iconCache[config.id]?.let { return it }
        // ConcurrentHashMap forbids null values — only cache hits.
        val icon = runCatching { faviconFor(siteUrlOf(config)) }.getOrNull() ?: return null
        iconCache[config.id] = icon
        return icon
    }

    // ---- The actual load ----

    private fun load(context: Context, file: File): Extension? {
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

    private fun ApplicationInfo.fixBasePaths(apkPath: String) {
        if (sourceDir == null) sourceDir = apkPath
        if (publicSourceDir == null) publicSourceDir = apkPath
    }

    /** The extensions-lib the APK was built against: the `aniyomix.extensionLib`
     *  metadata, else the MAJOR of its `versionName` (Aniyomi's own fallback). */
    private fun libVersionOf(info: PackageInfo): Double? {
        metaInt(info, META_EXT_LIB).takeIf { it != 0 }?.let { return it.toDouble() }
        val name = info.versionName ?: return null
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
