package com.hikari.app.ui

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.hikari.app.HikariApp
import kotlinx.coroutines.launch

/** One selectable app language: an emoji flag, the language's own name, and the
 *  BCP-47 tag handed to the platform ("" = follow the device). */
data class AppLanguage(val tag: String, val flag: String, val name: String)

object LanguageManager {

    /** Follow the device locale. */
    val SYSTEM = AppLanguage("", "🌐", "System default")

    val LANGUAGES: List<AppLanguage> = listOf(
        AppLanguage("en", "🇬🇧", "English"),
        AppLanguage("es", "🇪🇸", "Español"),
        AppLanguage("pt-BR", "🇧🇷", "Português (BR)"),
        AppLanguage("fr", "🇫🇷", "Français"),
        AppLanguage("de", "🇩🇪", "Deutsch"),
        AppLanguage("it", "🇮🇹", "Italiano"),
        AppLanguage("ru", "🇷🇺", "Русский"),
        AppLanguage("uk", "🇺🇦", "Українська"),
        AppLanguage("tr", "🇹🇷", "Türkçe"),
        AppLanguage("ar", "🇸🇦", "العربية"),
        AppLanguage("hi", "🇮🇳", "हिन्दी"),
        AppLanguage("id", "🇮🇩", "Indonesia"),
        AppLanguage("vi", "🇻🇳", "Tiếng Việt"),
        AppLanguage("th", "🇹🇭", "ไทย"),
        AppLanguage("ko", "🇰🇷", "한국어"),
        AppLanguage("ja", "🇯🇵", "日本語"),
        AppLanguage("zh-CN", "🇨🇳", "简体中文"),
        AppLanguage("pl", "🇵🇱", "Polski"),
        AppLanguage("nl", "🇳🇱", "Nederlands"),
        AppLanguage("el", "🇬🇷", "Ελληνικά"),
        AppLanguage("he", "🇮🇱", "עברית"),
        AppLanguage("qaa", "🙈", "mmmm... monke"),
    )

    /** All entries with [SYSTEM] first, for the settings list. */
    val ALL: List<AppLanguage> = listOf(SYSTEM) + LANGUAGES

    /** True when [tag] is one of the known languages (or the system default). */
    fun isKnown(tag: String): Boolean = tag.isBlank() || LANGUAGES.any { it.tag == tag }

    @Volatile
    private var pendingTag: String? = null

    // ---- Synchronous mirror ------------------------------------------------
    //
    // The STORED tag is mirrored into plain SharedPreferences so an Activity can
    // read it without waiting on DataStore. Without it a screen had to seed its
    // language state with "" (i.e. "follow the platform") and then correct
    // itself when the store answered — the whole interface visibly repainted a
    // moment after every return from the player. [pending] still wins over the
    // mirror; the mirror only fills the gap between a store read and the store.
    private const val PREFS = "hikari_language"
    private const val KEY_TAG = "tag"

    /** Persist the stored tag into the mirror (cheap; call it whenever the
     *  store's value is known). */
    fun rememberStored(context: Context, tag: String) {
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TAG, tag)
                .apply()
        }
    }

    /** The stored tag as of the last write, readable synchronously. */
    fun stored(context: Context): String =
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_TAG, "") ?: ""
        }.getOrDefault("")

    /** The in-memory override, or null when the stored value is authoritative. */
    fun pending(): String? = pendingTag

    fun effectiveTag(stored: String): String {
        if (stored.isNotBlank()) return stored
        return platformTag()
    }

    /** What the platform says the app's language is: the per-app locale when one
     *  is set, else the device locale. "" when it cannot be read. */
    fun platformTag(): String {
        val perApp = runCatching {
            AppCompatDelegate.getApplicationLocales().toLanguageTags()
        }.getOrNull().orEmpty()
        if (perApp.isNotBlank()) return perApp.substringBefore(',')
        return runCatching { java.util.Locale.getDefault().toLanguageTag() }.getOrDefault("")
    }

    /** The language's own name for [tag], or null when we do not carry it. Used
     *  for the "System default (…)" row so it names the language actually in
     *  effect instead of always saying English. */
    fun nameOf(tag: String): String? {
        val t = tag.trim()
        if (t.isBlank()) return null
        LANGUAGES.firstOrNull { it.tag.equals(t, ignoreCase = true) }?.let { return it.name }
        val base = t.substringBefore('-')
        return LANGUAGES.firstOrNull { it.tag.substringBefore('-').equals(base, ignoreCase = true) }?.name
    }

    /** Forgets the override once the store reports the same value, so a language
     *  changed anywhere else (Android's own per-app language screen) is not
     *  shadowed by a stale pick. */
    fun reconcile(storeValue: String) {
        if (pendingTag == storeValue) pendingTag = null
    }

    fun choose(app: HikariApp, tag: String) {
        pendingTag = tag
        rememberStored(app, tag)
        app.appScope.launch { runCatching { app.store.setLanguage(tag) } }
        apply(tag)
    }

    /** Applies [tag] app-wide. A blank tag clears the override so the app
     *  follows the device again. Safe to call from Application.onCreate. */
    fun apply(tag: String) {
        val locales = if (tag.isBlank()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        runCatching { AppCompatDelegate.setApplicationLocales(locales) }
    }
}
