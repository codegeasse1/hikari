package com.hikari.desktop.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val HikariBackground = Color(0xFF0B0E1A)
private val HikariSurface = Color(0xFF151A2E)
private val HikariSurfaceVariant = Color(0xFF1F2740)
private val HikariAmber = Color(0xFFF5C569)
private val HikariOnAmber = Color(0xFF2A1E00)
private val HikariPeriwinkle = Color(0xFF9DB4E8)
private val HikariOnPeriwinkle = Color(0xFF141A2E)
private val HikariText = Color(0xFFEDF0F9)
private val HikariMuted = Color(0xFF9AA3C0)
private val HikariError = Color(0xFFE57373)
private val HikariOutline = Color(0xFF2E3A55)
private val HikariOutlineVariant = Color(0xFF232D47)

private val HikariShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(9.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(22.dp),
)

@Composable
fun HikariDesktopTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = HikariAmber,
            onPrimary = HikariOnAmber,
            primaryContainer = HikariSurfaceVariant,
            onPrimaryContainer = HikariText,
            secondary = HikariPeriwinkle,
            onSecondary = HikariOnPeriwinkle,
            background = HikariBackground,
            onBackground = HikariText,
            surface = HikariSurface,
            onSurface = HikariText,
            surfaceVariant = HikariSurfaceVariant,
            onSurfaceVariant = HikariMuted,
            outline = HikariOutline,
            outlineVariant = HikariOutlineVariant,
            error = HikariError,
        ),
        shapes = HikariShapes,
        content = content,
    )
}
