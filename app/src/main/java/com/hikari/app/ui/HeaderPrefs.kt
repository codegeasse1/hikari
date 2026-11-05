package com.hikari.app.ui

import android.content.Context
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import com.hikari.app.HikariApp

/**
 * The Home header's HEIGHT setting (Settings → App Layout → Featured banner →
 * Header height). Kept in its own small preferences file so it needs nothing
 * from the rest of the settings store. The value is a Compose state, so every
 * banner on screen redraws the moment the slider moves.
 */
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

    /**
     * The multiplier to apply when the size slider is already at [scale]: the
     * two together never make the banner shorter than 40% of its natural
     * height, because the title and buttons need that much room.
     */
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
