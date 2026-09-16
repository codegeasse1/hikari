package com.hikari.app.i18n

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
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
    fun mapFor(context: Context, tag: String): Map<String, String> {
        if (tag.isBlank()) return emptyMap()
        loadTag(context, tag).let { if (it.isNotEmpty()) return it }
        val base = tag.substringBefore('-')
        if (base != tag) loadTag(context, base).let { if (it.isNotEmpty()) return it }
        return emptyMap()
    }

    /** Pushes the active map for non-composable lookups ([t]). */
    fun setCurrent(map: Map<String, String>) {
        currentMap = map
    }

    /** Non-composable lookup: returns the translation for [en], or [en] itself
     *  when the active language has none. */
    fun t(en: String): String {
        if (en.isBlank()) return en
        return currentMap[en] ?: en
    }
}

/** Translates an English UI literal to the active language (no-op in English). */
@Composable
fun tr(en: String): String {
    if (en.isBlank()) return en
    return I18n.LocalMap.current[en] ?: en
}
