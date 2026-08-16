package com.hikari.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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

@Composable
fun HikariTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = Typography,
        content = content,
    )
}
