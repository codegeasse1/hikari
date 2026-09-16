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
 * that gap: `assets/i18n.json` holds `{ "<tag>": { "<english>": "<translated>" } }`
 * for every supported language, and UI code wraps its literals with [tr] —
 * `Text(tr("Home"))` — so the whole app re-renders in the chosen language.
 *
 * [tr] is a composable that reads [LocalMap], so a language change recomposes
 * exactly the composables that use it (no activity restart). Non-composable
 * call sites (toasts, notifications, View-based screens) use [I18n.t], which
 * reads the map pushed by [setCurrent].
 */
object I18n {

    private const val ASSET = "i18n.json"

    @Volatile
    private var byTag: Map<String, Map<String, String>> = emptyMap()

    @Volatile
    private var currentMap: Map<String, String> = emptyMap()

    /** Provided at the app root with the map for the selected language. */
    val LocalMap = compositionLocalOf<Map<String, String>> { emptyMap() }

    private fun load(context: Context) {
        if (byTag.isNotEmpty()) return
        runCatching {
            val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val out = HashMap<String, Map<String, String>>()
            for (tag in root.keys()) {
                val obj = root.optJSONObject(tag) ?: continue
                val m = HashMap<String, String>(obj.length())
                for (key in obj.keys()) m[key] = obj.optString(key)
                out[tag] = m
            }
            byTag = out
        }
    }

    /** The translation map for [tag]; blank means "follow the device", which is
     *  the built-in English, so an empty map. */
    fun mapFor(context: Context, tag: String): Map<String, String> {
        load(context)
        if (tag.isBlank()) return emptyMap()
        byTag[tag]?.let { return it }
        return byTag[tag.substringBefore('-')] ?: emptyMap()
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
