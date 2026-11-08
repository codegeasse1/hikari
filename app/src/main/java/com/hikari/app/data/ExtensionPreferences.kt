package com.hikari.app.data

import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import androidx.preference.SeekBarPreference
import androidx.preference.TwoStatePreference
import com.hikari.app.aniyomi.AniyomiExtensionManager
import com.hikari.app.manga.MangaExtensionManager
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.MangaSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ExtensionPreferences {

    class Screen(
        val fields: JSONArray,
        val strings: Map<String, String>,
        val bools: Map<String, Boolean>,
    )

    fun load(context: Context, config: ProviderConfig): Screen? {
        val built = runCatching { build(context, config) }.getOrNull() ?: return null
        val fields = JSONArray()
        val strings = LinkedHashMap<String, String>()
        val bools = LinkedHashMap<String, Boolean>()
        walk(built.screen, built.prefs, fields, strings, bools)
        if (fields.length() == 0) return null
        return Screen(fields, strings, bools)
    }

    fun save(
        context: Context,
        config: ProviderConfig,
        strings: Map<String, String>,
        bools: Map<String, Boolean>,
    ): Boolean {
        val built = runCatching { build(context, config) }.getOrNull() ?: return false
        val byKey = LinkedHashMap<String, Preference>()
        collect(built.screen, byKey)
        var wrote = false
        for ((key, pref) in byKey) {
            try {
                when (pref) {
                    is MultiSelectListPreference -> {
                        val raw = strings[key] ?: continue
                        val set = raw.split(',')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .toSet()
                        if (pref.callChangeListener(set)) {
                            pref.values = set
                            wrote = true
                        }
                    }
                    is TwoStatePreference -> {
                        val v = bools[key] ?: continue
                        if (pref.callChangeListener(v)) {
                            pref.isChecked = v
                            wrote = true
                        }
                    }
                    is ListPreference -> {
                        val v = strings[key] ?: continue
                        if (pref.callChangeListener(v)) {
                            pref.value = v
                            wrote = true
                        }
                    }
                    is SeekBarPreference -> {
                        val v = strings[key]?.trim()?.toIntOrNull() ?: continue
                        if (pref.callChangeListener(v)) {
                            pref.value = v
                            wrote = true
                        }
                    }
                    is EditTextPreference -> {
                        val v = strings[key] ?: continue
                        if (pref.callChangeListener(v)) {
                            pref.text = v
                            wrote = true
                        }
                    }
                    else -> Unit
                }
            } catch (_: Throwable) {
                // One preference whose own listener throws must not cost the
                // rest of the screen its save.
            }
        }
        return wrote
    }

    // ------------------------------------------------------------------ build --

    private class Built(
        val source: Any,
        val screen: androidx.preference.PreferenceScreen,
        val prefs: SharedPreferences,
    )

    private fun build(context: Context, config: ProviderConfig): Built? {
        val source = sourceOf(context, config) ?: return null
        val prefs = when (source) {
            is ConfigurableAnimeSource -> runCatching { source.getSourcePreferences() }.getOrNull()
            is ConfigurableSource -> runCatching { source.getSourcePreferences() }.getOrNull()
            else -> null
        } ?: return null
        val screen = PreferenceManager(context).createPreferenceScreen(context)
        val sourceId = (source as? MangaSource)?.id ?: (source as? AnimeSource)?.id
        if (sourceId != null) {
            screen.preferenceManager.sharedPreferencesName = "source_$sourceId"
            screen.preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE
        }
        when (source) {
            is ConfigurableAnimeSource -> source.setupPreferenceScreen(screen)
            is ConfigurableSource -> source.setupPreferenceScreen(screen)
            else -> return null
        }
        return Built(source, screen, prefs)
    }

    /** The live source object behind a provider row (anime or manga). */
    private fun sourceOf(context: Context, config: ProviderConfig): Any? = runCatching {
        when (config.type) {
            ProviderType.ANIYOMI -> {
                val file = File(config.url).takeIf { it.exists() }
                    ?: AniyomiExtensionManager.extensionFile(
                        context,
                        AniyomiExtensionManager.packageOf(config),
                    )
                AniyomiExtensionManager.extensionOf(context, file)
                    ?.sources
                    ?.getOrNull(AniyomiExtensionManager.indexOf(config))
            }
            ProviderType.MANGA -> MangaExtensionManager.sourceOf(context, config)
            else -> null
        }
    }.getOrNull()

    // -------------------------------------------------------------------- walk --

    private fun collect(group: PreferenceGroup, out: MutableMap<String, Preference>) {
        for (i in 0 until group.preferenceCount) {
            val pref = group.getPreference(i) ?: continue
            if (pref is PreferenceGroup) {
                collect(pref, out)
                continue
            }
            val key = pref.key.orEmpty()
            if (key.isNotBlank()) out[key] = pref
        }
    }

    private fun walk(
        group: PreferenceGroup,
        prefs: SharedPreferences,
        fields: JSONArray,
        strings: MutableMap<String, String>,
        bools: MutableMap<String, Boolean>,
    ) {
        for (i in 0 until group.preferenceCount) {
            val pref = group.getPreference(i) ?: continue
            if (pref is PreferenceGroup) {
                if (pref is PreferenceCategory) {
                    val title = pref.title?.toString().orEmpty()
                    if (title.isNotBlank()) {
                        fields.put(
                            JSONObject().apply {
                                put("type", "header")
                                put("label", title)
                                put("description", pref.summary?.toString().orEmpty())
                            }
                        )
                    }
                }
                walk(pref, prefs, fields, strings, bools)
                continue
            }
            val key = pref.key.orEmpty()
            if (key.isBlank()) {
                // A preference with no key persists nothing — it is the
                // extension's own note (an "about" line, a link). Shown as
                // text; SettingsElementRow is what drops promo-only rows.
                val label = pref.title?.toString().orEmpty()
                if (label.isNotBlank()) {
                    fields.put(
                        JSONObject().apply {
                            put("type", "info")
                            put("label", label)
                            put("description", pref.summary?.toString().orEmpty())
                        }
                    )
                }
                continue
            }
            val element = runCatching { elementOf(pref, key, prefs) }.getOrNull() ?: continue
            when (element.optString("type")) {
                "toggle" -> bools[key] = element.optBoolean("defaultValue", false)
                // A heading / a note persists nothing, so there is no value to
                // carry into the dialog's map either.
                "header", "info" -> Unit
                else -> strings[key] = element.optString("defaultValue")
            }
            fields.put(element)
        }
    }

    private fun elementOf(pref: Preference, key: String, prefs: SharedPreferences): JSONObject {
        val label = pref.title?.toString().orEmpty()
        return when (pref) {
            is TwoStatePreference -> {
                val value = runCatching { prefs.getBoolean(key, pref.isChecked) }
                    .getOrDefault(pref.isChecked)
                JSONObject().apply {
                    put("type", "toggle")
                    put("key", key)
                    put("label", label)
                    put("description", descOf(pref, value.toString()))
                    put("defaultValue", value)
                }
            }
            is ListPreference -> {
                val value = runCatching { prefs.getString(key, pref.value) }.getOrNull()
                    ?: pref.value.orEmpty()
                JSONObject().apply {
                    put("type", "select")
                    put("key", key)
                    put("label", label)
                    put("description", descOf(pref, value))
                    put("defaultValue", value)
                    put("options", optionsOf(pref.entries, pref.entryValues))
                }
            }
            is MultiSelectListPreference -> {
                val value = runCatching { prefs.getStringSet(key, pref.values) }.getOrNull()
                    ?: pref.values
                val joined = value.joinToString(",")
                JSONObject().apply {
                    put("type", "multi")
                    put("key", key)
                    put("label", label)
                    put("description", descOf(pref, joined))
                    put("defaultValue", joined)
                    put("options", optionsOf(pref.entries, pref.entryValues))
                }
            }
            is SeekBarPreference -> {
                val value = runCatching { prefs.getInt(key, pref.value) }.getOrDefault(pref.value)
                JSONObject().apply {
                    put("type", "text")
                    put("key", key)
                    put("label", label)
                    put("description", descOf(pref, value.toString()))
                    put("defaultValue", value.toString())
                    put("isNumber", true)
                }
            }
            is EditTextPreference -> {
                val value = runCatching { pref.text }.getOrNull().orEmpty()
                JSONObject().apply {
                    put("type", "text")
                    put("key", key)
                    put("label", label)
                    put("description", descOf(pref, value))
                    put("defaultValue", value)
                    put("isPassword", isPassword(pref))
                }
            }
            else -> JSONObject().apply {
                put("type", "info")
                put("label", label.ifBlank { key })
                put("description", descOf(pref, null))
            }
        }
    }

    /** `entries`/`entryValues` as the dialog's `{label, value}` pairs. */
    private fun optionsOf(entries: Array<out CharSequence>?, values: Array<out CharSequence>?): JSONArray {
        val out = JSONArray()
        if (entries == null || values == null) return out
        for (i in 0 until minOf(entries.size, values.size)) {
            out.put(
                JSONObject().apply {
                    put("label", entries[i].toString())
                    put("value", values[i].toString())
                }
            )
        }
        return out
    }

    private fun descOf(pref: Preference, current: String?): String {
        val raw = pref.summary?.toString().orEmpty()
        if (raw.isBlank()) return ""
        if (current == null || !raw.contains("%s")) return raw
        return raw.replace("%s", current)
    }

    private fun isPassword(pref: EditTextPreference): Boolean = runCatching {
        val editText = pref.javaClass.getMethod("getEditText").invoke(pref) as? android.widget.EditText
        val input = editText?.inputType ?: return@runCatching false
        val variant = input and 0xff
        variant == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variant == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
    }.getOrDefault(false)
}
