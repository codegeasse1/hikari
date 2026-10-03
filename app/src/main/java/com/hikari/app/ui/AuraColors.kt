package com.hikari.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.hikari.app.ui.theme.HikariAccent

/**
 * The colour an "Aura ring" is drawn in — Settings → App Layout → Poster
 * styling / Loading screen → Aura ring colour.
 *
 * The ring used to be whatever the app accent happened to be, which meant a
 * user who wanted the ring to be red had to repaint the whole app. The two are
 * separate choices now: the ring gets its own colour, chosen from the same
 * palette the accent uses ([HikariAccent]) plus [THEME] — "follow the accent",
 * which is what every ring drew before and stays the default, so nobody's
 * existing look changes on update.
 *
 * Shared by BOTH screens the aura ring appears on (a poster card and the
 * loading card) and by both toolkits the app is built from: the Compose artwork
 * resolves it through [color], the View-based player through [argb]. One
 * preference, drawn in one colour everywhere.
 */
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
