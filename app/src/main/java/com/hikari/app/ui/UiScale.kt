package com.hikari.app.ui

import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics
import kotlin.math.roundToInt

/**
 * The "In-app UI scale" preference (Settings → App Layout → In-app UI scale), mirrored into
 * plain SharedPreferences so it can be read SYNCHRONOUSLY while an Activity is
 * being created.
 *
 * The Compose part of the app scales itself in [com.hikari.app.ui.theme.HikariTheme],
 * but this app also has plain View-based screens (the player and the WebView)
 * which live outside that tree — they pick up the scale here, via
 * [wrap] in each Activity's `attachBaseContext`.
 *
 * When the scale is ON the phone's Font size AND Display size settings are
 * ignored app-wide: `fontScale` is pinned to 1.0 and the density is rebuilt
 * from the device's STABLE physical density times the chosen scale, so every
 * device renders the same layout. OFF = the context is returned untouched and
 * the system settings apply as before.
 */
object UiScale {

    private const val PREFS = "hikari_ui_scale"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PERCENT = "percent"

    @Volatile private var loaded = false
    @Volatile private var enabled = false
    @Volatile private var scale = 1f

    /** Persist + cache the current preference. Called from the store setters and
     *  from MainActivity whenever the DataStore flow emits (so it also self-heals
     *  if the mirror is ever out of date). */
    fun sync(context: Context, enabled: Boolean, scale: Float) {
        val pct = (scale * 100f).roundToInt().coerceIn(70, 130)
        this.enabled = enabled
        this.scale = pct / 100f
        loaded = true
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, enabled)
                .putInt(KEY_PERCENT, pct)
                .apply()
        }
    }

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        runCatching {
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            enabled = p.getBoolean(KEY_ENABLED, false)
            scale = p.getInt(KEY_PERCENT, 100).coerceIn(70, 130) / 100f
        }.onFailure { scale = 1f }
        loaded = true
    }

    /** [base] with a Configuration that ignores the phone's font/display size
     *  settings while the in-app scale is on; [base] itself when it is off. */
    fun wrap(base: Context): Context {
        ensureLoaded(base)
        if (!enabled) return base
        return runCatching {
            val config = Configuration(base.resources.configuration)
            config.fontScale = 1f
            val stable = DisplayMetrics.DENSITY_DEVICE_STABLE
            if (stable > 0) config.densityDpi = (stable * scale).roundToInt()
            base.createConfigurationContext(config)
        }.getOrDefault(base)
    }
}
