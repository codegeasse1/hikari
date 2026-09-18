package com.hikari.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import com.hikari.app.R

/**
 * The launcher icons the user can pick between in Settings → Appearance & Theme.
 *
 * Every entry has its own `activity-alias` in AndroidManifest.xml, all of them
 * pointing at [com.hikari.app.MainActivity]. Exactly one alias is enabled at a
 * time, and the enabled one is the launcher entry (and the icon the launcher
 * shows) — MainActivity itself is never disabled, so the PendingIntents in
 * DownloadService/WorkService that name `MainActivity::class.java` keep
 * resolving. Switching is `setComponentEnabledSetting(..., DONT_KILL_APP)`, so
 * the app isn't restarted; some launchers cache icons and only repaint after
 * their next refresh (the chooser tells the user that).
 */
data class AppIconVariant(
    val key: String,
    /** Shown in the chooser under the tile. */
    val label: String,
    /** Component name of this variant's `activity-alias`. */
    val alias: String,
    /** What the chooser draws — for every variant this is the launcher mipmap
     *  itself, so the tile is exactly what the home screen will show. */
    @DrawableRes val drawable: Int,
    /**
     * What the chooser *previews*. Drawing the adaptive `mipmap-anydpi-v26`
     * XML in Compose means rasterising it and then guessing at the launcher's
     * mask inset, which used to crop the artwork in the picker. Instead the
     * picker shows the composed icon itself ([R.drawable.ic_launcher_vN_fg] is
     * the same square the launcher builds, with a margin around the artwork),
     * clipped to a rounded square — so nothing the user sees is cut.
     */
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
    const val DEFAULT_KEY = "classic"

    fun byKey(key: String): AppIconVariant =
        AppIconVariants.firstOrNull { it.key == key } ?: AppIconVariants.first()

    /**
     * Which variant the system currently has enabled. Falls back to the stored
     * choice when the answer is `DEFAULT` (component never touched), i.e. the
     * manifest's own state, which is [DEFAULT_KEY].
     */
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

    /**
     * Enables [key]'s alias first, then disables the other ten, so there is no
     * window with the app having no launcher entry at all. The enabled alias is
     * remembered by the system across reboots and updates, so this only has to
     * run when the user actually changes their mind.
     */
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

    /**
     * Re-asserts the stored choice when the manifest disagrees with it — the
     * component states travel with the *installed app*, not with the restored
     * preferences, so after a backup restore (or a copy to a new device) the
     * alias would still be whatever the manifest default is. Cheap: it reads the
     * component states and only writes when they're already wrong.
     */
    fun ensureApplied(context: Context, key: String) {
        runCatching { if (currentKey(context) != key) apply(context, key) }
    }
}
