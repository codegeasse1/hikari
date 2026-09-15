package com.hikari.app.ui

import android.content.Context
import com.hikari.app.ui.theme.HikariAccent

/**
 * The accent-colour preferences, mirrored into plain SharedPreferences so the
 * View-based screens (the player, mainly) can read them SYNCHRONOUSLY while an
 * Activity is being created — the same trick [UiScale] uses for the in-app UI
 * scale, and for the same reason: the player must build its pills/badges/time
 * bar with the right colours on the very first frame, not a DataStore round
 * trip later.
 *
 * The Compose side does not need this: it reads the DataStore flows, so a
 * change recolours the app instantly. MainActivity mirrors every emission here
 * (and the store setters do too, so the change is available immediately).
 *
 * [playerKey] resolves the "match app & player theme" switch: while it is ON the
 * player follows the app accent, so the two can never drift apart.
 */
object AccentStore {

    private const val PREFS = "hikari_accent"
    private const val KEY_APP = "app"
    private const val KEY_PLAYER = "player"
    private const val KEY_LINKED = "linked"

    @Volatile private var loaded = false
    @Volatile private var appKey = HikariAccent.DEFAULT_APP.key
    @Volatile private var playerKey = HikariAccent.DEFAULT_PLAYER.key
    @Volatile private var linked = false

    /** Persist + cache the current preferences. Called from the store setters
     *  and from MainActivity whenever the DataStore flows emit, so the mirror
     *  self-heals even if a write was missed. */
    fun sync(context: Context, app: String, player: String, linked: Boolean) {
        this.appKey = app
        this.playerKey = player
        this.linked = linked
        loaded = true
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_APP, app)
                .putString(KEY_PLAYER, player)
                .putBoolean(KEY_LINKED, linked)
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
}
