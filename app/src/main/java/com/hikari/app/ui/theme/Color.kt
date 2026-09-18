package com.hikari.app.ui.theme

import androidx.compose.ui.graphics.Color

val HikariBg = Color(0xFF0B0E1A)
val HikariSurface = Color(0xFF151A2E)
val HikariSurfaceVariant = Color(0xFF1F2740)
val HikariPrimary = Color(0xFFF5C569)
val HikariOnPrimary = Color(0xFF2A1E00)
val HikariSecondary = Color(0xFF7DD3FC)
val HikariOnSecondary = Color(0xFF00283A)
val HikariTertiary = Color(0xFFF7A1C4)
val HikariError = Color(0xFFE57373)
val HikariText = Color(0xFFEDF0F9)
val HikariMuted = Color(0xFF9AA3C0)

// Hikari Light — warm paper background, dark text, amber accent.
val HikariLightBg = Color(0xFFF6F4EE)
val HikariLightSurface = Color(0xFFFFFFFF)
val HikariLightSurfaceVariant = Color(0xFFEDEAE0)
val HikariLightPrimary = Color(0xFF8A6200)
val HikariLightOnPrimary = Color(0xFFFFFFFF)
val HikariLightSecondary = Color(0xFF0F6A94)
val HikariLightOnSecondary = Color(0xFFFFFFFF)
val HikariLightTertiary = Color(0xFF9D3D6F)
val HikariLightError = Color(0xFFB3261E)
val HikariLightText = Color(0xFF1C1B18)
val HikariLightMuted = Color(0xFF5F5B52)

// Dark Glass UI — translucent surfaces over a colorful backdrop gradient
// (the gradient itself is drawn by AppRoot when this theme is active).
val GlassBackground = Color.Transparent
val GlassSurface = Color(0xCC171E38)
// A translucent navy, not the translucent WHITE this used to be. Its whole job
// is to be the theme's `surfaceVariant`/`primaryContainer` — the slightly
// lighter panel behind a chip, a placeholder or a small button — and every
// caller pairs it with the theme's light `onSurface` ink. As white it only
// worked while it stayed very see-through; anywhere a caller raised its alpha
// to make a solid pill (`.copy(alpha = 0.92f)` on Home's provider pill, 0.7 on
// the extension-catalog button, 0.6 on Library's chips, 0.5 in the player)
// it became a nearly-opaque WHITE slab with near-white text on top — the
// reported "provider button is fully bright and I can't read what it says".
// Dark, and in the same family as the dark theme's surfaceVariant, it reads
// correctly at every alpha, in every one of those places.
val GlassSurfaceVariant = Color(0xB31F2740)
val GlassScrim = Color(0x99000000)

// AMOLED — for OLED panels, where a lit pixel is a pixel that costs battery
// and never goes fully black. Pure #000000 page, near-black surfaces, and the
// same translucent white cards as the other dark themes (see
// com.hikari.app.ui.theme.rememberGlassTokens) so it still reads as Hikari.
val AmoledBg = Color(0xFF000000)
val AmoledSurface = Color(0xFF0A0A0C)
val AmoledSurfaceVariant = Color(0xFF141418)
