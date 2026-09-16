package com.hikari.app.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.hikari.app.HikariApp
import kotlinx.coroutines.launch

/** One selectable app language: an emoji flag, the language's own name, and the
 *  BCP-47 tag handed to the platform ("" = follow the device). */
data class AppLanguage(val tag: String, val flag: String, val name: String)

/**
 * App-language plumbing. The chosen tag is stored in AppStore and applied with
 * [AppCompatDelegate.setApplicationLocales], so every resource-backed string —
 * the player overlay's pills, the "Tap to play" hint, content descriptions,
 * the settings card — is served from values-<lang>/strings.xml and switches
 * live. Hikari's Compose screens still hard-code their copy in English, so the
 * language currently covers the player UI and the resource-backed labels.
 *
 * The tag list must stay in step with res/xml/locales_config.xml (which is what
 * tells Android which locales this app supports, so per-app language selection
 * also appears in the system settings screen).
 */
object LanguageManager {

    /** Follow the device locale. */
    val SYSTEM = AppLanguage("", "🌐", "System default")

    val LANGUAGES: List<AppLanguage> = listOf(
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

    /** The tag chosen most recently, held in MEMORY so the activity recreation
     *  [apply] causes can never race the DataStore write. Without it the newly
     *  recreated activity read the old preference and the app kept the previous
     *  language until the same option was picked a second time (the reported
     *  "it only applies on the second click" bug). */
    @Volatile
    private var pendingTag: String? = null

    /** The in-memory override, or null when the stored value is authoritative. */
    fun pending(): String? = pendingTag

    /** Forgets the override once the store reports the same value, so a language
     *  changed anywhere else (Android's own per-app language screen) is not
     *  shadowed by a stale pick. */
    fun reconcile(storeValue: String) {
        if (pendingTag == storeValue) pendingTag = null
    }

    /** Applies [tag] in the order that actually works: the choice is recorded
     *  FIRST — in memory right away, and persisted on the APPLICATION scope,
     *  which no activity recreation can cancel — and only then handed to
     *  [AppCompatDelegate]. Doing it the other way round (apply, then launch the
     *  write on the composition's own scope) lost the write to the very
     *  recreation the apply had just triggered. */
    fun choose(app: HikariApp, tag: String) {
        pendingTag = tag
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
