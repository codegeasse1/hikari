package com.hikari.app.i18n

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import org.json.JSONObject

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

    fun t(en: String): String {
        if (en.isBlank()) return en
        currentMap[en]?.let { return it }
        val lang = currentTag
        if (lang.isBlank() || lang.startsWith("en")) return en
        TagTranslator.cached(lang, en)?.let { return it }
        TagTranslator.request(lang, en)
        return en
    }

    fun tTerm(term: String): String {
        val t = term.trim()
        if (t.isBlank() || currentMap.isEmpty()) return term
        currentMap[t]?.let { return it }
        return lowerIndex(currentMap)[t.lowercase()] ?: term
    }
}

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
