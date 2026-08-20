package com.hikari.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class HikariThemeMode(val key: String, val label: String) {
    DARK("dark", "Hikari Dark"),
    GLASS("glass", "Dark Glass UI"),
    LIGHT("light", "Hikari Light");

    companion object {
        fun fromKey(key: String?): HikariThemeMode =
            entries.firstOrNull { it.key == key } ?: DARK
    }
}

private val DarkColors = darkColorScheme(
    primary = HikariPrimary,
    onPrimary = HikariOnPrimary,
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

private val LightColors = lightColorScheme(
    primary = HikariLightPrimary,
    onPrimary = HikariLightOnPrimary,
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

private val GlassColors = darkColorScheme(
    primary = HikariPrimary,
    onPrimary = HikariOnPrimary,
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

@Composable
fun HikariTheme(
    mode: HikariThemeMode = HikariThemeMode.DARK,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = when (mode) {
            HikariThemeMode.DARK -> DarkColors
            HikariThemeMode.LIGHT -> LightColors
            HikariThemeMode.GLASS -> GlassColors
        },
        typography = Typography,
        content = content,
    )
}
