package com.hikari.app.i18n

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import org.json.JSONObject

/**
 * Runtime UI localisation for Hikari.
 *
 * The app's screens are handwritten Compose/Kotlin and their copy is a plain
 * English string literal at every call site. Android's per-app language only
 * swaps *resources* (it never touches literals in code), which is why picking a
 * language used to change exactly one card and nothing else. This object closes
 * that gap: `assets/i18n/<tag>.json` holds `{ "<english>": "<translated>" }`
 * for every supported language, and UI code wraps its literals with [tr] —
 * `Text(tr("Home"))` — so the whole app re-renders in the chosen language.
 *
 * The per-language split matters: a full dump of every language is ~450 KB, so
 * loading it at startup was wasteful. Only the selected tag's file (20-35 KB) is
 * read, and it is read lazily and cached, so switching language costs one small
 * asset parse.
 *
 * [tr] is a composable that reads [LocalMap], so a language change recomposes
 * exactly the composables that use it (no activity restart). Non-composable
 * call sites (toasts, notifications, View-based screens) use [I18n.t], which
 * reads the map pushed by [setCurrent].
 */
object I18n {

    private const val ASSET_DIR = "i18n"

    /** Loaded per-tag maps, populated on first use of each language. */
    private val byTag = HashMap<String, Map<String, String>>()

    @Volatile
    private var currentMap: Map<String, String> = emptyMap()

    /** Provided at the app root with the map for the selected language. */
    val LocalMap = compositionLocalOf<Map<String, String>> { emptyMap() }

    /** Reads and caches `assets/i18n/<tag>.json`. Never throws: a missing or
     *  malformed file just yields an empty map, i.e. untranslated English. */
    private fun loadTag(context: Context, tag: String): Map<String, String> {
        if (tag.isBlank()) return emptyMap()
        byTag[tag]?.let { return it }
        val map = runCatching {
            val text = context.assets.open("$ASSET_DIR/$tag.json").bufferedReader().use { it.readText() }
            val obj = JSONObject(text)
            val m = HashMap<String, String>(obj.length())
            for (key in obj.keys()) {
                val v = obj.optString(key)
                if (v.isNotBlank()) m[key] = v
            }
            m
        }.getOrElse { HashMap<String, String>() }
        byTag[tag] = map
        return map
    }

    /** The translation map for [tag]; blank means "follow the device", which is
     *  the built-in English, so an empty map. Regional tags fall back to their
     *  base language (`pt-BR` -> `pt`) and then to English. */
    /** The tag whose map is currently provided ([setCurrent]'s argument), so
     *  code that needs to know WHICH language is active — the tag translator,
     *  for one — does not have to guess. */
    @Volatile
    var currentTag: String = ""
        private set

    /** The translation map for the language in use, for the non-composable
     *  lookups ([t], [tTerm]). */
    fun currentMap(): Map<String, String> = currentMap

    /**
     * A lowercase index of [map], built once per map. Genre/tag labels arrive
     * from extensions in every casing ("Action", "action", "ACTION"), and an
     * exact-match lookup misses two of the three — so the term lookups take a
     * second pass through this.
     */
    private var lowerCache: Pair<Map<String, String>, Map<String, String>>? = null
    fun lowerIndex(map: Map<String, String>): Map<String, String> {
        val cached = lowerCache
        if (cached != null && cached.first === map) return cached.second
        val idx = HashMap<String, String>(map.size * 2)
        for ((k, v) in map) idx.putIfAbsent(k.trim().lowercase(), v)
        lowerCache = map to idx
        return idx
    }

    fun mapFor(context: Context, tag: String): Map<String, String> {
        if (tag.isBlank()) return emptyMap()
        loadTag(context, tag).let { if (it.isNotEmpty()) return it }
        val base = tag.substringBefore('-')
        if (base != tag) loadTag(context, base).let { if (it.isNotEmpty()) return it }
        return emptyMap()
    }

    /** Pushes the active map for non-composable lookups ([t]). */
    fun setCurrent(map: Map<String, String>, tag: String = "") {
        currentMap = map
        currentTag = tag
    }

    /** Non-composable lookup: returns the translation for [en], or [en] itself
     *  when the active language has none.
     *
     *  Used by the View-based screens (the player and its dialogs), which build
     *  their labels once and cannot re-render when a translation arrives later.
     *  A label the dictionary does not carry is served from [TagTranslator]'s
     *  cache — one it translated on an earlier visit to this screen, or on an
     *  earlier launch, since those are persisted — and if even that has nothing
     *  yet the English is returned and the translation is requested in the
     *  background, so the NEXT time this label is built it is in the chosen
     *  language. */
    fun t(en: String): String {
        if (en.isBlank()) return en
        currentMap[en]?.let { return it }
        val lang = currentTag
        if (lang.isBlank() || lang.startsWith("en")) return en
        TagTranslator.cached(lang, en)?.let { return it }
        TagTranslator.request(lang, en)
        return en
    }

    /**
     * Non-composable term lookup for labels that come from CONTENT rather than
     * from our own copy — an extension's genre tags ("Action", "Martial Arts"),
     * a credit's role ("(voice)"). Tries the exact spelling, then the
     * case-insensitive one, so "action" and "Action" are the same tag.
     */
    fun tTerm(term: String): String {
        val t = term.trim()
        if (t.isBlank() || currentMap.isEmpty()) return term
        currentMap[t]?.let { return it }
        return lowerIndex(currentMap)[t.lowercase()] ?: term
    }
}

/**
 * Translates a content label — a genre/tag chip, a credit's role — to the
 * active language.
 *
 * Two layers, cheapest first:
 *  1. our own dictionary ([I18n.tTerm]) — instant, offline, and where every
 *     TMDB genre lives, so a TMDB title's chips never need a network round
 *     trip;
 *  2. [TagTranslator] — the same free translation endpoint the app already uses
 *     for titles, cached per (language, tag), for whatever a CloudStream or
 *     Aniyomi extension invented ("Martial Arts", "Xianxia", …), which no
 *     fixed list could cover.
 *
 * A tag arrives in its own language the first time it is drawn and settles into
 * the chosen one a moment later: translating it is not worth blocking the page
 * for.
 */
@Composable
fun trTag(term: String): String {
    val t = term.trim()
    if (t.isBlank()) return term
    val map = I18n.LocalMap.current
    if (map.isEmpty()) return term
    map[t]?.let { return it }
    I18n.lowerIndex(map)[t.lowercase()]?.let { return it }
    val lang = I18n.currentTag
    if (lang.isBlank() || lang.startsWith("en")) return term
    val hit = TagTranslator.cached(lang, t)
    // The version is read so a late translation recomposes this label.
    val version by TagTranslator.version.collectAsState()
    LaunchedEffect(lang, t, version) {
        if (TagTranslator.cached(lang, t) == null) TagTranslator.request(lang, t)
    }
    return hit ?: term
}

/** Translates an English UI literal to the active language (no-op in English). */
@Composable
fun tr(en: String): String {
    if (en.isBlank()) return en
    I18n.LocalMap.current[en]?.let { return it }
    // The dictionary is the first and cheapest answer, and the only one that is
    // instant and offline — but it is not the ONLY answer. A literal that the
    // selected language's file does not carry used to fall straight through to
    // English, which is what "I picked Arabic and the settings still say
    // Cinema / App Layout / Wide banner" is: the app's own copy is wrapped for
    // translation, and those particular entries simply had no translation yet.
    // Rather than leaving them English until the next build ships more
    // dictionary entries, the same live translator the content tags already use
    // fills the gaps — one round trip per distinct label, cached in memory and
    // on disk, so it is paid once and then never again.
    return trMissing(en)
}

/** The fallback half of [tr]: the label is not in the dictionary, so ask the
 *  tag translator (and repaint when its answer lands). Kept separate so the
 *  common case — a dictionary hit — costs one map lookup and no state read. */
@Composable
private fun trMissing(en: String): String {
    val lang = I18n.currentTag
    if (lang.isBlank() || lang.startsWith("en")) return en
    TagTranslator.cached(lang, en)?.let { return it }
    val version by TagTranslator.version.collectAsState()
    LaunchedEffect(lang, en, version) {
        if (TagTranslator.cached(lang, en) == null) TagTranslator.request(lang, en)
    }
    return en
}
