package com.hikari.app.ui.theme

import android.content.res.DisplayMetrics
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
                HikariThemeMode.DARK -> DarkColors
                HikariThemeMode.LIGHT -> LightColors
                HikariThemeMode.GLASS -> GlassColors
            },
            typography = Typography,
            content = content,
        )
    }
}
