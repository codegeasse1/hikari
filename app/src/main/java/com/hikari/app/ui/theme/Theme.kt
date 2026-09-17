package com.hikari.app.ui.theme

import android.util.DisplayMetrics
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

enum class HikariThemeMode(val key: String, val label: String) {
    DARK("dark", "Hikari Dark"),
    GLASS("glass", "Dark Glass UI"),
    AMOLED("amoled", "AMOLED Black"),
    LIGHT("light", "Hikari Light");

    companion object {
        fun fromKey(key: String?): HikariThemeMode =
            entries.firstOrNull { it.key == key } ?: DARK
    }
}

private fun darkColors(accent: HikariAccent) = darkColorScheme(
    primary = accent.start,
    onPrimary = inkOn(accent.start),
    primaryContainer = HikariSurfaceVariant,
    onPrimaryContainer = HikariText,
    secondary = HikariSecondary,
    onSecondary = HikariOnSecondary,
    tertiary = HikariTertiary,
    background = HikariBg,
    onBackground = HikariText,
    surface = HikariSurface,
    onSurface = HikariText,
    surfaceVariant = HikariSurfaceVariant,
    onSurfaceVariant = HikariMuted,
    error = HikariError,
    outline = HikariMuted,
    scrim = Color.Black,
)

private fun lightColors(accent: HikariAccent) = lightColorScheme(
    primary = accent.light,
    onPrimary = Color.White,
    primaryContainer = HikariLightSurfaceVariant,
    onPrimaryContainer = HikariLightText,
    secondary = HikariLightSecondary,
    onSecondary = HikariLightOnSecondary,
    tertiary = HikariLightTertiary,
    background = HikariLightBg,
    onBackground = HikariLightText,
    surface = HikariLightSurface,
    onSurface = HikariLightText,
    surfaceVariant = HikariLightSurfaceVariant,
    onSurfaceVariant = HikariLightMuted,
    error = HikariLightError,
    outline = HikariLightMuted,
    scrim = Color.Black,
)

private fun glassColors(accent: HikariAccent) = darkColorScheme(
    primary = accent.start,
    onPrimary = inkOn(accent.start),
    primaryContainer = GlassSurfaceVariant,
    onPrimaryContainer = HikariText,
    secondary = HikariSecondary,
    onSecondary = HikariOnSecondary,
    tertiary = HikariTertiary,
    background = GlassBackground,
    onBackground = HikariText,
    surface = GlassSurface,
    onSurface = HikariText,
    surfaceVariant = GlassSurfaceVariant,
    onSurfaceVariant = Color(0xFFC9CEE3),
    error = HikariError,
    outline = HikariMuted,
    scrim = GlassScrim,
)

/**
 * AMOLED: the dark scheme with the page and its surfaces dropped to true black,
 * so an OLED phone lights only the pixels that carry content. Everything else
 * (accent, text, muted, error) is the same as [darkColors] on purpose — the
 * "pure black" theme must not look like a different app, just a darker one.
 */
private fun amoledColors(accent: HikariAccent) = darkColorScheme(
    primary = accent.start,
    onPrimary = inkOn(accent.start),
    primaryContainer = AmoledSurfaceVariant,
    onPrimaryContainer = HikariText,
    secondary = HikariSecondary,
    onSecondary = HikariOnSecondary,
    tertiary = HikariTertiary,
    background = AmoledBg,
    onBackground = HikariText,
    surface = AmoledSurface,
    onSurface = HikariText,
    surfaceVariant = AmoledSurfaceVariant,
    onSurfaceVariant = HikariMuted,
    error = HikariError,
    outline = HikariMuted,
    scrim = Color.Black,
)

@Composable
fun HikariTheme(
    mode: HikariThemeMode = HikariThemeMode.DARK,
    accent: HikariAccent = HikariAccent.DEFAULT_APP,
    uiScaleEnabled: Boolean = false,
    uiScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    // In-app UI scale. When ON, this replaces the phone's Font size AND Display
    // size settings with a single in-app scale: the app ignores `fontScale`
    // (stays 1.0) and rebuilds the density from the device's STABLE physical
    // density times the chosen scale, so every device renders the same layout
    // regardless of accessibility size settings. OFF = inherit the system
    // density untouched (the previous behaviour).
    val base = LocalDensity.current
    val context = LocalContext.current
    val density = remember(base, context, uiScaleEnabled, uiScale) {
        if (!uiScaleEnabled) {
            base
        } else {
            val stable = runCatching { DisplayMetrics.DENSITY_DEVICE_STABLE / 160f }
                .getOrDefault(0f)
            val unit = if (stable > 0f) stable else base.density
            Density(
                density = unit * uiScale.coerceIn(0.7f, 1.3f),
                fontScale = 1f,
            )
        }
    }
    CompositionLocalProvider(LocalDensity provides density) {
        MaterialTheme(
            colorScheme = when (mode) {
                HikariThemeMode.DARK -> darkColors(accent)
                HikariThemeMode.LIGHT -> lightColors(accent)
                HikariThemeMode.GLASS -> glassColors(accent)
                HikariThemeMode.AMOLED -> amoledColors(accent)
            },
            typography = Typography,
            content = content,
        )
    }
}
