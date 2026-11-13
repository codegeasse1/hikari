package com.hikari.app.music

import android.content.Context

/** Full VxMusic-style settings, login-free. Backed by SharedPrefs so no DataStore/Hilt needed. */
object VxSettings {
    private const val PREFS = "hikari_music_vx"
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // 15 ISO bands like VxMusic
    val EQ_FREQS = intArrayOf(25, 40, 63, 100, 160, 250, 400, 630, 1000, 1600, 2500, 4000, 6300, 10000, 16000)
    fun eqLabel(hz: Int): String = if (hz >= 1000) (hz / 1000).toString() + "K" else hz.toString()
    val STUDIO_MASTER = listOf(2.6f, 2.8f, 2.2f, 0.6f, -1.8f, -2.6f, -1.2f, 0.0f, 1.2f, 2.4f, 3.6f, 4.0f, 4.2f, 4.5f, 4.8f)

    fun eqEnabled(ctx: Context) = runCatching { p(ctx).getBoolean("eq_enabled", false) }.getOrDefault(false)
    fun setEqEnabled(ctx: Context, v: Boolean) { runCatching { p(ctx).edit().putBoolean("eq_enabled", v).apply() } }
    fun eqGains(ctx: Context): List<Float> {
        val s = runCatching { p(ctx).getString("eq_gains", null) }.getOrNull() ?: return List(15) { 0f }
        val parts = s.split(",").mapNotNull { it.toFloatOrNull() }
        if (parts.size != 15) return List(15) { 0f }
        return parts.map { it.coerceIn(-8f, 8f) }
    }
    fun setEqGains(ctx: Context, gains: List<Float>) {
        runCatching { p(ctx).edit().putString("eq_gains", gains.joinToString(",")) }.let {}
        VxAudioFx.apply(ctx)
    }
    fun setEqPreset(ctx: Context, gains: List<Float>) {
        setEqGains(ctx, gains)
        runCatching { p(ctx).edit().putBoolean("eq_enabled", true).apply() }
        VxAudioFx.apply(ctx)
    }

    fun wavy(ctx: Context) = runCatching { p(ctx).getBoolean("wavy", true) }.getOrDefault(true)
    fun setWavy(ctx: Context, v: Boolean) { runCatching { p(ctx).edit().putBoolean("wavy", v).apply() } }

    fun crossfade(ctx: Context) = runCatching { p(ctx).getBoolean("xfade", false) }.getOrDefault(false)
    fun setCrossfade(ctx: Context, v: Boolean) { runCatching { p(ctx).edit().putBoolean("xfade", v).apply() } }
    fun xfadeSecs(ctx: Context) = runCatching { p(ctx).getInt("xfade_secs", 5) }.getOrDefault(5).coerceIn(1, 12)
    fun setXfadeSecs(ctx: Context, v: Int) { runCatching { p(ctx).edit().putInt("xfade_secs", v.coerceIn(1, 12)).apply() } }

    fun lyricsAnim(ctx: Context) = runCatching { p(ctx).getString("lyrics_anim", "Modern") }.getOrDefault("Modern")!!
    fun setLyricsAnim(ctx: Context, v: String) { runCatching { p(ctx).edit().putString("lyrics_anim", v).apply() } }
    val LYRIC_ANIMS = listOf("Apple Fluid", "Karaoke Pulse", "Kinetic Slide", "Cinematic Focus", "Lossless Glow", "Glass Elevation", "Dynamic Zoom", "Minimal Clean", "Classic")

    fun liquidGlass(ctx: Context) = runCatching { p(ctx).getBoolean("liquid_glass", false) }.getOrDefault(false)
    fun setLiquidGlass(ctx: Context, v: Boolean) { runCatching { p(ctx).edit().putBoolean("liquid_glass", v).apply() } }
    fun amoled(ctx: Context) = runCatching { p(ctx).getBoolean("amoled", false) }.getOrDefault(false)
    fun setAmoled(ctx: Context, v: Boolean) { runCatching { p(ctx).edit().putBoolean("amoled", v).apply() } }
    fun dynamicColor(ctx: Context) = runCatching { p(ctx).getBoolean("dyn_color", false) }.getOrDefault(false)
    fun setDynamicColor(ctx: Context, v: Boolean) { runCatching { p(ctx).edit().putBoolean("dyn_color", v).apply() } }
    fun dynNowPlaying(ctx: Context) = runCatching { p(ctx).getBoolean("dyn_np", false) }.getOrDefault(false)
    fun setDynNowPlaying(ctx: Context, v: Boolean) { runCatching { p(ctx).edit().putBoolean("dyn_np", v).apply() } }
    fun appFont(ctx: Context) = runCatching { p(ctx).getBoolean("app_font", true) }.getOrDefault(true)
    fun setAppFont(ctx: Context, v: Boolean) { runCatching { p(ctx).edit().putBoolean("app_font", v).apply() } }
    fun accent(ctx: Context) = runCatching { p(ctx).getString("accent", "Crimson") }.getOrDefault("Crimson")!!
    fun setAccent(ctx: Context, v: String) { runCatching { p(ctx).edit().putString("accent", v).apply() } }
    val ACCENTS = listOf("Crimson", "Violet", "Ocean", "Sage", "Amber", "Rose", "Mono", "Custom")
}
