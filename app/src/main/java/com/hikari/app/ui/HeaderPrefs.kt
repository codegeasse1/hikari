package com.hikari.app.ui

import android.content.Context
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import com.hikari.app.HikariApp

object HeaderPrefs {
    const val MIN_PERCENT = 50
    const val MAX_PERCENT = 150
    const val DEFAULT_PERCENT = 100

    private const val FILE = "hikari_header_prefs"
    private const val KEY_HEIGHT = "heightPercent"

    private val heightState: MutableIntState by lazy {
        mutableIntStateOf(readStored())
    }

    private fun readStored(): Int = runCatching {
        HikariApp.instance
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY_HEIGHT, DEFAULT_PERCENT)
            .coerceIn(MIN_PERCENT, MAX_PERCENT)
    }.getOrDefault(DEFAULT_PERCENT)

    /** 50..150, 100 = the height the banner always had. */
    val heightPercent: Int get() = heightState.intValue

    /** [heightPercent] as a multiplier (1f = unchanged). */
    val heightFactor: Float get() = heightState.intValue / 100f

    fun heightFactorFor(scale: Float): Float {
        val s = scale.coerceIn(0.5f, 1.5f)
        val f = heightFactor
        return if (s * f < 0.4f) 0.4f / s else f
    }

    fun setHeightPercent(percent: Int) {
        val p = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)
        heightState.intValue = p
        runCatching {
            HikariApp.instance
                .getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_HEIGHT, p)
                .apply()
        }
    }
}
