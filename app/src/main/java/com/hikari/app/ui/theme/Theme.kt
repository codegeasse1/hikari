package com.hikari.app.ui.theme

import android.util.DisplayMetrics
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density

enum class HikariThemeMode(val key: String, val label: String) {
    DARK("dark", "Hikari Dark"),
    GLASS("glass", "Dark Glass UI"),
    AMOLED("amoled", "AMOLED Black"),
    LIGHT("light", "Hikari Light");

    companion object {
        const val DEFAULT_KEY = "amoled"

        /** [DEFAULT_KEY] as the enum value. */
        val DEFAULT: HikariThemeMode = entries.first { it.key == DEFAULT_KEY }

        fun fromKey(key: String?): HikariThemeMode =
            entries.firstOrNull { it.key == key } ?: DEFAULT
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
fun pageBackground(): Color {
    val bg = MaterialTheme.colorScheme.background
    return when {
        bg.alpha >= 0.99f -> bg
        // See-through page colour over a light theme (custom background picture).
        bg.luminance() > 0.5f -> HikariLightBg
        else -> HikariBg
    }
}

@Composable
fun HikariTheme(
    mode: HikariThemeMode = HikariThemeMode.DEFAULT,
    accent: HikariAccent = HikariAccent.DEFAULT_APP,
    uiScaleEnabled: Boolean = false,
    uiScale: Float = 1f,
    /** The app-wide font (Settings → Appearance & Theme → App font); null keeps the stock
     *  Material scale, which is what "System default" means. */
    fontFamily: FontFamily? = null,
    content: @Composable () -> Unit,
) {
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
    val scheme = when (mode) {
        HikariThemeMode.DARK -> darkColors(accent)
        HikariThemeMode.LIGHT -> lightColors(accent)
        HikariThemeMode.GLASS -> glassColors(accent)
        HikariThemeMode.AMOLED -> amoledColors(accent)
    }
    // While the user has a picture set as the app background (Settings ->
    // Customize -> Custom background) every page must let it show through. The
    // scheme's page colour is made see-through for that (the Dark Glass theme
    // already works this way), and the real colour is kept in
    // LocalPageScrim for the darkness layer that sits over the picture.
    val hasPicture = com.hikari.app.ui.CustomBackground.image != null
    val shownScheme = if (hasPicture) {
        scheme.copy(background = scheme.background.copy(alpha = 0f))
    } else {
        scheme
    }
    CompositionLocalProvider(
        LocalDensity provides density,
        LocalContentColor provides scheme.onBackground,
        com.hikari.app.ui.LocalPageScrim provides scheme.background,
    ) {
        MaterialTheme(
            colorScheme = shownScheme,
            typography = typographyWith(fontFamily),
            content = content,
        )
    }
}
