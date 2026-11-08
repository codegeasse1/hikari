package com.hikari.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.hikari.app.ui.theme.HikariAccent

object AuraColors {

    /** Follow the app accent (the default). */
    const val THEME = "theme"

    /** Every choice, in the order Settings lists them. */
    val ALL: List<String> = listOf(THEME) + HikariAccent.entries.map { it.key }

    fun normalize(key: String?): String {
        val k = key?.trim()?.lowercase().orEmpty()
        return if (k in ALL) k else THEME
    }

    /** The swatch's own name — "Accent" for [THEME], else the palette name. */
    fun label(key: String?): String =
        if (normalize(key) == THEME) "Accent" else HikariAccent.fromKey(key).label

    /**
     * The ring's colour: [fallback] (the live accent) for [THEME], else the
     * chosen palette entry's solid tone.
     */
    fun color(key: String?, fallback: Color): Color =
        if (normalize(key) == THEME) fallback else HikariAccent.fromKey(key).mid

    /** The same colour as an ARGB int, for the View-based player. */
    fun argb(key: String?, fallbackArgb: Int): Int {
        val k = normalize(key)
        if (k == THEME) return fallbackArgb
        return HikariAccent.fromKey(k).mid.toArgb()
    }
}
