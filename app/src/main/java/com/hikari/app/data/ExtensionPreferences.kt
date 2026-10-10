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
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.source.ConfigurableSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * An Aniyomi/Mihon extension's OWN settings screen, read and written through
 * the extension's own `Preference` objects instead of a list Hikari invented.
 *
 * A Tachiyomi-family extension declares its settings in code:
 *
 *   override fun setupPreferenceScreen(screen: PreferenceScreen) {
 *       EditTextPreference(screen.context).apply { key = "pref_domain"; … }
 *   }
 *
 * and reads them back from `getSourcePreferences()`. The real Aniyomi app shows
 * those by handing the extension an `androidx.preference.PreferenceScreen` and
 * displaying the tree. Hikari used to have nothing here at all: the gear on an
 * Aniyomi/Manga extension pointed at a screen that said the extension had no
 * settings, even for the extensions whose whole point is a domain / login /
 * quality preference. So the same trick is done here — the extension is asked
 * for its preference tree — and the result is mapped onto the JSON shape the
 * app's own settings dialog already draws (see the Extensions screen's
 * `SchemaSettingsDialog`).
 *
 * WRITES go back through the same `Preference` objects, which is the part that
 * matters: an extension very often does not store the raw text. The Usual
 * pattern is `EditTextPreference` + `setOnPreferenceChangeListener` that
 * validates the input and calls `preferences.edit().putInt(key, …)`, and a
 * `SwitchPreference` whose listener rewrites a dependent field. Writing the
 * SharedPreferences directly would bypass all of that and leave the extension
 * reading a value in the wrong type (or a stale dependent), so every value is
 * pushed in exactly the way the preference's own dialog pushes it —
 * `callChangeListener(value)` first, then the setter.
 */
object ExtensionPreferences {

    /**
     * What one extension's settings screen turned out to be: the fields in the
     * JSON shape `SettingsElementRow` draws, plus the values they hold now.
     *
     * A multi-select field is a comma-joined string in [strings] (so the whole
     * dialog can work with two plain maps); [save] splits it back apart.
     */
    class Screen(
        val fields: JSONArray,
        val strings: Map<String, String>,
        val bools: Map<String, Boolean>,
    )

    /**
     * Reads [config]'s settings screen, or null when the extension declares
     * none (it does not implement `ConfigurableSource`/`ConfigurableAnimeSource`)
     * or its file cannot be loaded — the caller then shows what the extension IS
     * rather than an empty screen. Blocking (it instantiates the extension), so
     * call it from IO.
     */
    fun load(context: Context, config: ProviderConfig): Screen? {
        val built = runCatching { build(context, config) }.getOrNull() ?: return null
        val fields = JSONArray()
        val strings = LinkedHashMap<String, String>()
        val bools = LinkedHashMap<String, Boolean>()
        walk(built.screen, built.prefs, fields, strings, bools)
        if (fields.length() == 0) return null
        return Screen(fields, strings, bools)
    }

    /**
     * Writes the chosen values back through the extension's own preferences.
     * Returns false when the extension has no settings screen (or its file
     * cannot be loaded), so the caller reports a real failure instead of a
     * silent no-op.
     */
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

    /**
     * The live pair a load needs: the extension's `PreferenceScreen` and the
     * SharedPreferences it reads those preferences from. [source] is kept only
     * so the object cannot be collected while its screen is walked.
     */
    private class Built(
        val source: Any,
        val screen: androidx.preference.PreferenceScreen,
        val prefs: SharedPreferences,
    )

    /**
     * Asks [config]'s extension for its preference tree.
     *
     * Everything is wrapped by the caller: an extension's `setupPreferenceScreen`
     * is arbitrary third-party code, and one that throws (a `R.string` its own
     * resources cannot resolve, a preference needing a real Activity) has to
     * fall back to the honest info dialog rather than crash the screen that
     * opened the gear.
     */
    private fun build(context: Context, config: ProviderConfig): Built? {
        val source = sourceOf(context, config) ?: return null
        val prefs = when (source) {
            is ConfigurableAnimeSource -> runCatching { source.getSourcePreferences() }.getOrNull()
            is ConfigurableSource -> runCatching { source.getSourcePreferences() }.getOrNull()
            else -> null
        } ?: return null
        val screen = PreferenceManager(context).createPreferenceScreen(context)
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

    /**
     * One preference as a drawable field.
     *
     * The VALUE comes from the preference's own getter (`pref.isChecked`,
     * `pref.value`, `pref.text`) — that is the getter the extension's code paths
     * use, it already has the persisted value merged over the declared default,
     * and it is the only one guaranteed to agree with the SharedPreferences
     * (a `getString` on a key an extension stores as an Int throws).
     */
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
                    // Drawn as a numeric text field: the dialog's field types are
                    // the ones the app can render everywhere (including with a
                    // D-pad on a TV), and a number typed as digits is the same
                    // value a slider would produce.
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

    /**
     * The extension's summary line. The `%s` placeholder is the androidx
     * convention — the preference class substitutes the CURRENT value into it —
     * so it is filled in here the same way, otherwise the row would read "%s".
     */
    private fun descOf(pref: Preference, current: String?): String {
        val raw = pref.summary?.toString().orEmpty()
        if (raw.isBlank()) return ""
        if (current == null || !raw.contains("%s")) return raw
        return raw.replace("%s", current)
    }

    /**
     * True for an `EditTextPreference` the extension marked as a secret.
     *
     * The `EditText` is read reflectively rather than through the synthetic
     * `editText` property: `getEditText()` is only present in some
     * `androidx.preference` artifacts, so the property does not resolve in
     * every build (it failed to compile with `Unresolved reference 'editText'`).
     * Reflection keeps the value visible when the method is there and just
     * treats the preference as a plain text row when it is not.
     */
    private fun isPassword(pref: EditTextPreference): Boolean = runCatching {
        val editText = pref.javaClass.getMethod("getEditText").invoke(pref) as? android.widget.EditText
        val input = editText?.inputType ?: return@runCatching false
        val variant = input and 0xff
        variant == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variant == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
    }.getOrDefault(false)
}
