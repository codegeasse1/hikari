package com.hikari.app.net

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.hikari.app.data.Logs
import com.lagradost.cloudstream3.CloudStreamApp
import java.io.File

object ExtensionVerifyGuard {

    @Volatile
    private var allowed = false

    private val KNOWN_KEYS = listOf("CINEMACITY_CF_WEBVIEW_ENABLED")

    private val CLOUDFLARE_WORD =
        Regex("CF|CLOUDFLARE|TURNSTILE|CAPTCHA|SECURITY|\\bBOT\\b", RegexOption.IGNORE_CASE)
    private val WEBVIEW_WORD =
        Regex("WEBVIEW|BYPASS|VERIFY|CHALLENGE|CAPTCHA|TURNSTILE|SOLVE", RegexOption.IGNORE_CASE)

    /** The user's current choice, readable by the popup guard: [true] when an
     *  extension's own verification pages are allowed through (Settings →
     *  Sources & Extensions → "Open their verification pages"). */
    val pagesAllowed: Boolean get() = allowed

    private val POPUP_BLOCK_WORDS = Regex(
        "TURNSTILE|CLOUDFLARE|CAPTCHA|CHALLENGE|VERIF|SOLVER|SECURITY" +
            "|DONATION|DONATE|PROMO|FUNDING|SUPPORT_US|MEMBERSHIP",
        RegexOption.IGNORE_CASE,
    )

    fun blocksPopup(className: String?, tag: String?): Boolean {
        if (allowed) return false
        return POPUP_BLOCK_WORDS.containsMatchIn(className.orEmpty()) ||
            POPUP_BLOCK_WORDS.containsMatchIn(tag.orEmpty())
    }

    /** True for a key [apply] would force off (the same rule [discoveredToggles]
     *  uses), so a key that doesn't exist in any file yet is covered too. */
    private fun looksLikeVerifyToggle(key: String): Boolean =
        KNOWN_KEYS.contains(key) ||
            (CLOUDFLARE_WORD.containsMatchIn(key) && WEBVIEW_WORD.containsMatchIn(key))

    fun forcesOff(key: String): Boolean = !allowed && looksLikeVerifyToggle(key)

    fun apply(context: Context, allow: Boolean): List<String> {
        allowed = allow
        val changed = ArrayList<String>()
        val targets = if (allow) emptyList()
        else (KNOWN_KEYS.map { null to it } + discoveredToggles(context)).distinct()
        for ((file, key) in targets) {
            val current = readToggle(context, key)
            if (current != false) changed += "$key=false"
            writeToggle(context, key, false, file)
        }
        if (changed.isNotEmpty()) {
            Logs.log(
                "Extensions",
                "extension verification pages blocked: " + changed.joinToString(", "),
            )
        }
        return changed
    }

    fun wrapContext(base: Context): Context = if (allowed) base else GuardedContext(base)

    /** A Context whose SharedPreferences hand out `false` (and never store
     *  `true`) for verification-shaped keys. */
    private class GuardedContext(base: Context) : ContextWrapper(base) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
            GuardedPrefs(super.getSharedPreferences(name, mode))
    }

    private class GuardedPrefs(private val real: SharedPreferences) : SharedPreferences {
        private fun guarded(key: String?): Boolean = key != null && forcesOff(key)

        override fun getAll(): MutableMap<String, *> {
            val out = HashMap<String, Any?>(real.all)
            for (key in out.keys.toList()) if (forcesOff(key)) out[key] = false
            return out
        }

        override fun getString(key: String?, defValue: String?): String? =
            if (guarded(key)) "false" else real.getString(key, defValue)

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            if (guarded(key)) mutableSetOf() else real.getStringSet(key, defValues)

        override fun getInt(key: String?, defValue: Int): Int =
            if (guarded(key)) 0 else real.getInt(key, defValue)

        override fun getLong(key: String?, defValue: Long): Long =
            if (guarded(key)) 0L else real.getLong(key, defValue)

        override fun getFloat(key: String?, defValue: Float): Float =
            if (guarded(key)) 0f else real.getFloat(key, defValue)

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            if (guarded(key)) false else real.getBoolean(key, defValue)

        override fun contains(key: String?): Boolean =
            if (guarded(key)) true else real.contains(key)

        override fun edit(): SharedPreferences.Editor = GuardedEditor(real.edit())

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = real.registerOnSharedPreferenceChangeListener(listener)

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = real.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private class GuardedEditor(private val real: SharedPreferences.Editor) : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            val turningOn = value != null && value.trim().lowercase() == "true"
            real.putString(key, if (key != null && turningOn && forcesOff(key)) "false" else value)
            return this
        }

        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor {
            real.putStringSet(key, values)
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            real.putInt(key, value)
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            real.putLong(key, value)
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            real.putFloat(key, value)
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            real.putBoolean(key, if (key != null && forcesOff(key)) false else value)
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            real.remove(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            real.clear()
            return this
        }

        override fun commit(): Boolean = real.commit()

        override fun apply() {
            real.apply()
        }
    }

    private fun prefFiles(context: Context): List<SharedPreferences> {
        val out = LinkedHashSet<SharedPreferences>()
        runCatching {
            out += context.getSharedPreferences(CloudStreamApp.CS_PREFS_NAME, Context.MODE_PRIVATE)
        }
        runCatching {
            out += context.getSharedPreferences(CloudStreamApp.HK_PREFS_NAME, Context.MODE_PRIVATE)
        }
        runCatching { out += PreferenceManager.getDefaultSharedPreferences(context) }
        runCatching {
            val dir = File(context.dataDir, "shared_prefs")
            val xmls = dir.listFiles { f -> f.isFile && f.name.endsWith(".xml") } ?: return@runCatching
            for (f in xmls) {
                val name = f.name.removeSuffix(".xml")
                if (name.isBlank()) continue
                runCatching { out += context.getSharedPreferences(name, Context.MODE_PRIVATE) }
            }
        }
        return out.toList()
    }

    private fun discoveredToggles(context: Context): List<Pair<SharedPreferences?, String>> {
        val out = LinkedHashSet<Pair<SharedPreferences?, String>>()
        for (file in prefFiles(context)) {
            val keys = runCatching { file.all.keys }.getOrNull() ?: continue
            for (key in keys) {
                if (key.isBlank() || !looksLikeVerifyToggle(key)) continue
                out += (file to key)
            }
        }
        return out.toList()
    }

    private fun readToggle(context: Context, key: String): Boolean? {
        for (file in prefFiles(context)) {
            val raw = runCatching { file.all[key] }.getOrNull() ?: continue
            val parsed = when (raw) {
                is Boolean -> raw
                is Number -> raw.toInt() != 0
                is String -> when (raw.trim().trim('"').lowercase()) {
                    "true" -> true
                    "false" -> false
                    else -> null
                }
                else -> null
            }
            if (parsed != null) return parsed
        }
        return null
    }

    private fun writeToggle(
        context: Context,
        key: String,
        value: Boolean,
        file: SharedPreferences? = null,
    ) {
        runCatching { CloudStreamApp.setKey(key, value) }
        if (file != null) {
            runCatching { file.edit().putString(key, value.toString()).apply() }
        }
        runCatching {
            PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putString(key, value.toString())
                .apply()
        }
    }
}
