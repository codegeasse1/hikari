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
    AppIconVariant("toriimoon", "Torii Moon", "com.hikari.app.icon.ToriiMoon", R.mipmap.ic_launcher_v12, R.drawable.ic_launcher_v12_fg),
    AppIconVariant("sakuramoon", "Sakura Moon", "com.hikari.app.icon.SakuraMoon", R.mipmap.ic_launcher_v13, R.drawable.ic_launcher_v13_fg),
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

    /** True when this build actually ships [alias]: a downgrade can leave an
     *  enabled record for an alias the older manifest no longer declares. */
    fun aliasExists(context: Context, alias: String): Boolean = runCatching {
        context.packageManager.getActivityInfo(ComponentName(context, alias), 0)
        true
    }.getOrDefault(false)

    /** Key to use instead of [key] when this build doesn't ship it: the stored
     *  Sakura icon first, then the original gold, then whatever survives. */
    fun resolveAvailable(context: Context, key: String): String {
        if (AppIconVariants.firstOrNull { it.key == key }?.let { aliasExists(context, it.alias) } == true) return key
        for (fallback in listOf("sakura", "classic")) {
            if (AppIconVariants.firstOrNull { it.key == fallback }?.let { aliasExists(context, it.alias) } == true) return fallback
        }
        return AppIconVariants.firstOrNull { aliasExists(context, it.alias) }?.key ?: key
    }

    /** Downgrade guard (call at startup before [ensureApplied]): when the
     *  enabled alias is gone from this build — e.g. an icon picked on a newer
     *  version, then downgraded to one without it, which would leave no
     *  launcher entry and a "vanished" app — fall back to Sakura, else gold.
     *  Returns the key that is now active. */
    fun reconcileOnStart(context: Context): String {
        val cur = AppIconVariants.firstOrNull { it.key == currentKey(context) }
        if (cur != null && aliasExists(context, cur.alias)) return cur.key
        val fixed = resolveAvailable(context, cur?.key ?: DEFAULT_KEY)
        runCatching { apply(context, fixed) }
        return fixed
    }
}
