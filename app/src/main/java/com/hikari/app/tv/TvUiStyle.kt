package com.hikari.app.tv

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import com.hikari.app.HikariApp

/**
 * Which TV interface the user picked (Settings → Customize → TV interface).
 *
 *  - [DEFAULT] — the original television layout: the navigation rail down the
 *    left edge ([TvNavRail]).
 *  - [NUVIO]   — the Nuvio-style layout: no side rail, a slim navigation bar
 *    along the top ([TvTopNav]) and the page using the full width of the
 *    screen.
 *
 * This only changes anything while the TV layout is on (see [TvMode]); a
 * phone keeps its own bottom taskbar whatever is chosen here. Kept in its own
 * small preferences file (like HeaderPrefs) so it needs nothing from the rest
 * of the settings store. The value is a Compose state, so the whole app
 * re-lays-out the moment the choice changes — no restart.
 */
object TvUiStyle {
    const val DEFAULT = "default"
    const val NUVIO = "nuvio"

    private const val FILE = "hikari_tv_ui_prefs"
    private const val KEY_STYLE = "style"

    private val styleState = mutableStateOf(DEFAULT)
    @Volatile
    private var loaded = false

    private fun normalize(value: String?): String = if (value == NUVIO) NUVIO else DEFAULT

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        styleState.value = runCatching {
            normalize(
                HikariApp.instance
                    .getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .getString(KEY_STYLE, DEFAULT)
            )
        }.getOrDefault(DEFAULT)
    }

    /** The stored choice: [DEFAULT] or [NUVIO]. Reads a Compose state. */
    val style: String
        get() {
            ensureLoaded()
            return styleState.value
        }

    /** True when the Nuvio-style TV interface is chosen. */
    val isNuvio: Boolean get() = style == NUVIO

    fun setStyle(value: String) {
        ensureLoaded()
        val v = normalize(value)
        styleState.value = v
        runCatching {
            HikariApp.instance
                .getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_STYLE, v)
                .apply()
        }
    }
}
