package com.hikari.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import com.hikari.app.R

data class AppIconVariant(
    val key: String,
    /** Shown in the chooser under the tile. */
    val label: String,
    /** Component name of this variant's `activity-alias`. */
    val alias: String,
    /** What the chooser draws — for every variant this is the launcher mipmap
     *  itself, so the tile is exactly what the home screen will show. */
    @DrawableRes val drawable: Int,
    @DrawableRes val preview: Int,
)

/** Order = the order of the tiles in the chooser. */
val AppIconVariants: List<AppIconVariant> = listOf(
    AppIconVariant("classic", "Hikari Gold", "com.hikari.app.icon.Classic", R.mipmap.ic_launcher, R.mipmap.ic_launcher),
    AppIconVariant("cine", "Cine H", "com.hikari.app.icon.Cine", R.mipmap.ic_launcher_v2, R.drawable.ic_launcher_v2_fg),
    AppIconVariant("orbit", "Orbit Play", "com.hikari.app.icon.OrbitPlay", R.mipmap.ic_launcher_v3, R.drawable.ic_launcher_v3_fg),
    AppIconVariant("moon", "Moon Star", "com.hikari.app.icon.MoonStar", R.mipmap.ic_launcher_v4, R.drawable.ic_launcher_v4_fg),
    AppIconVariant("redplay", "Red Play", "com.hikari.app.icon.RedPlay", R.mipmap.ic_launcher_v5, R.drawable.ic_launcher_v5_fg),
    AppIconVariant("sakura", "Sakura", "com.hikari.app.icon.Sakura", R.mipmap.ic_launcher_v6, R.drawable.ic_launcher_v6_fg),
    AppIconVariant("enso", "Enso Play", "com.hikari.app.icon.EnsoPlay", R.mipmap.ic_launcher_v7, R.drawable.ic_launcher_v7_fg),
    AppIconVariant("silver", "Silver H", "com.hikari.app.icon.SilverH", R.mipmap.ic_launcher_v8, R.drawable.ic_launcher_v8_fg),
    AppIconVariant("violet", "Violet Play", "com.hikari.app.icon.VioletPlay", R.mipmap.ic_launcher_v9, R.drawable.ic_launcher_v9_fg),
    AppIconVariant("horizon", "Horizon", "com.hikari.app.icon.Horizon", R.mipmap.ic_launcher_v10, R.drawable.ic_launcher_v10_fg),
    AppIconVariant("neko", "Neko Moon", "com.hikari.app.icon.NekoMoon", R.mipmap.ic_launcher_v11, R.drawable.ic_launcher_v11_fg),
)

object AppIconManager {

    /** The alias that ships enabled in the manifest. */
    const val DEFAULT_KEY = "sakura"

    fun byKey(key: String): AppIconVariant =
        AppIconVariants.firstOrNull { it.key == key } ?: AppIconVariants.first()

    fun currentKey(context: Context): String {
        val pm = context.packageManager
        for (v in AppIconVariants) {
            val enabled = when (pm.getComponentEnabledSetting(ComponentName(context, v.alias))) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
                else -> v.key == DEFAULT_KEY
            }
            if (enabled) return v.key
        }
        return DEFAULT_KEY
    }

    fun apply(context: Context, key: String) {
        val pm = context.packageManager
        val want = byKey(key)
        val enabled = ComponentName(context, want.alias)
        // FAILED for a component the system doesn't know about would mean the
        // alias is missing from the merged manifest — nothing else to do then.
        pm.setComponentEnabledSetting(
            enabled,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        for (v in AppIconVariants) {
            if (v.key == want.key) continue
            pm.setComponentEnabledSetting(
                ComponentName(context, v.alias),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    fun ensureApplied(context: Context, key: String) {
        runCatching { if (currentKey(context) != key) apply(context, key) }
    }
}
