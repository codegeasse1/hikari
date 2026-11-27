package com.hikari.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val HikariBackground = Color(0xFF0B0E1A)
private val HikariSurface = Color(0xFF151A2E)
private val HikariSurfaceVariant = Color(0xFF1F2740)
private val HikariAmber = Color(0xFFF5C569)
private val HikariOnAmber = Color(0xFF2A1E00)
private val HikariText = Color(0xFFEDF0F9)
private val HikariMuted = Color(0xFF9AA3C0)
private val HikariError = Color(0xFFE57373)

@Composable
fun HikariDesktopTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = HikariAmber,
            onPrimary = HikariOnAmber,
            primaryContainer = HikariSurfaceVariant,
            onPrimaryContainer = HikariText,
            background = HikariBackground,
            onBackground = HikariText,
            surface = HikariSurface,
            onSurface = HikariText,
            surfaceVariant = HikariSurfaceVariant,
            onSurfaceVariant = HikariMuted,
            error = HikariError,
        ),
        content = content,
    )
}
