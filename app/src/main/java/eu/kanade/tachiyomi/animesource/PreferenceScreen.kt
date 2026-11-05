package eu.kanade.tachiyomi.animesource

/**
 * Aniyomi declares this as an `expect class` in commonMain and as a
 * `typealias` to `androidx.preference.PreferenceScreen` in androidMain.
 * Hikari vendors the android side only, so the typealias is the definition.
 */
typealias PreferenceScreen = androidx.preference.PreferenceScreen
