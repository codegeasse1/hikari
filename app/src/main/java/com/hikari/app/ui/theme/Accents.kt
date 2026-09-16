package com.hikari.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * The app's accent colours (Settings → Appearance → Accent color).
 *
 * The app was built around a single amber/gold accent ([AMBER], the default)
 * and the player around the cyan→violet glow ([VIOLET], the default). Making
 * the accent a value that flows through the theme means one palette drives the
 * whole UI: every screen already paints itself from
 * `MaterialTheme.colorScheme.primary`, so only the scheme has to change.
 *
 * [start]/[end] are the two ends of the accent gradient, and they are the raw
 * source of truth: [mid] (the solid accent), [soft] (a darker shade for pressed
 * surfaces) and [ripple] are derived from them, and the native player draws its
 * pills/badges/time-bar from the same numbers (see PlayerActivity's
 * applyAccentPalette), so a colour picked here looks identical in both the
 * Compose UI and the View-based player.
 */
enum class HikariAccent(
    val key: String,
    val label: String,
    val start: Color,
    val end: Color,
    /** Light-theme variant of the accent: the [start] colour darkened enough to
     *  stay readable as text/icons on a white surface. */
    val light: Color,
) {
    AMBER("amber", "Amber", Color(0xFFF5C569), Color(0xFFE0A93B), Color(0xFF8A6200)),
    VIOLET("violet", "Violet", Color(0xFF00B4DB), Color(0xFF9D4EDD), Color(0xFF5B2FA8)),
    BLUE("blue", "Blue", Color(0xFF3BB2FF), Color(0xFF2B4BFF), Color(0xFF1B4BC4)),
    CYAN("cyan", "Cyan", Color(0xFF22E1E8), Color(0xFF00A3C4), Color(0xFF07798F)),
    TEAL("teal", "Teal", Color(0xFF2FE0B0), Color(0xFF0E9E88), Color(0xFF0B7A6A)),
    GREEN("green", "Green", Color(0xFF7BE86B), Color(0xFF16A34A), Color(0xFF1F7A2E)),
    RED("red", "Red", Color(0xFFFF6B6B), Color(0xFFD90429), Color(0xFFB3202A)),
    ORANGE("orange", "Orange", Color(0xFFFFB056), Color(0xFFF0590A), Color(0xFFB4530A)),
    PINK("pink", "Pink", Color(0xFFFF7BC5), Color(0xFFE0248A), Color(0xFFB3266E)),
    PURPLE("purple", "Purple", Color(0xFFB98CFF), Color(0xFF7B2CBF), Color(0xFF6A2BB0)),
    MONO("mono", "Mono", Color(0xFFE6E8F0), Color(0xFF8A90A8), Color(0xFF4A4F63));

    /** The solid accent — the colour used for buttons, selected tabs, icons. */
    val mid: Color get() = lerp(start, end, 0.5f)

    /** A darker shade of the accent, for pressed/inset surfaces. */
    val soft: Color get() = lerp(end, Color.Black, 0.45f)

    /** Faint accent wash for chips/containers drawn behind content. */
    val wash: Color get() = mid.copy(alpha = 0.16f)

    /** Ripple for accent-coloured native views (player pills/badges). */
    val ripple: Color get() = mid.copy(alpha = 0.20f)

    companion object {
        /** Default app accent: the amber/gold the app has always used. */
        val DEFAULT_APP = AMBER

        /** Default player accent: the cyan→violet glow the player has always
         *  used (#00B4DB → #9D4EDD, i.e. the old hikari_accent_* resources). */
        val DEFAULT_PLAYER = VIOLET

        fun fromKey(key: String?): HikariAccent =
            entries.firstOrNull { it.key == key }
                ?: entries.firstOrNull { it.key == key?.trim()?.lowercase() }
                ?: DEFAULT_APP
    }
}

/** The best foreground colour (text/icon) to draw on top of [background]: dark
 *  ink on bright accents (amber, cyan, mono), white on saturated ones. */
fun inkOn(background: Color): Color =
    if (background.luminance() > 0.5f) Color(0xFF1E1600) else Color.White
