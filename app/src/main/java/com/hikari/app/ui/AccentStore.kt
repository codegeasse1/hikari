package com.hikari.app.ui

import android.content.Context
import com.hikari.app.ui.theme.HikariAccent
import com.hikari.app.ui.theme.HikariThemeMode

object AccentStore {

    private const val PREFS = "hikari_accent"
    private const val KEY_APP = "app"
    private const val KEY_PLAYER = "player"
    private const val KEY_LINKED = "linked"
    private const val KEY_THEME = "theme"

    @Volatile private var loaded = false
    @Volatile private var appKey = HikariAccent.DEFAULT_APP.key
    @Volatile private var playerKey = HikariAccent.DEFAULT_PLAYER.key
    @Volatile private var linked = false
    @Volatile private var themeKey = HikariThemeMode.DEFAULT_KEY

    /** Persist + cache the current preferences. Called from the store setters
     *  and from MainActivity whenever the DataStore flows emit, so the mirror
     *  self-heals even if a write was missed. */
    fun sync(context: Context, app: String, player: String, linked: Boolean, theme: String) {
        this.appKey = app
        this.playerKey = player
        this.linked = linked
        this.themeKey = theme
        loaded = true
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_APP, app)
                .putString(KEY_PLAYER, player)
                .putBoolean(KEY_LINKED, linked)
                .putString(KEY_THEME, theme)
                .apply()
        }
    }

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        runCatching {
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            appKey = p.getString(KEY_APP, null) ?: HikariAccent.DEFAULT_APP.key
            playerKey = p.getString(KEY_PLAYER, null) ?: HikariAccent.DEFAULT_PLAYER.key
            linked = p.getBoolean(KEY_LINKED, false)
            themeKey = p.getString(KEY_THEME, null) ?: HikariThemeMode.DEFAULT_KEY
        }
        loaded = true
    }

    /** The accent the app UI uses. */
    fun app(context: Context): HikariAccent {
        ensureLoaded(context)
        return HikariAccent.fromKey(appKey)
    }

    /** True while "Match app & player theme" is on. */
    fun isLinked(context: Context): Boolean {
        ensureLoaded(context)
        return linked
    }

    /** The accent the PLAYER should draw itself with right now: the app accent
     *  while the two are linked, otherwise the player's own accent. */
    fun player(context: Context): HikariAccent {
        ensureLoaded(context)
        return if (linked) HikariAccent.fromKey(appKey) else HikariAccent.fromKey(playerKey)
    }

    /** The player's own accent, ignoring the link (used by Settings). */
    fun playerOwn(context: Context): HikariAccent {
        ensureLoaded(context)
        return HikariAccent.fromKey(playerKey)
    }

    fun theme(context: Context): String {
        ensureLoaded(context)
        return themeKey
    }
}
