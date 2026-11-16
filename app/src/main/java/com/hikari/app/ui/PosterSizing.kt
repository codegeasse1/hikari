package com.hikari.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.hikari.app.HikariApp
import com.hikari.app.data.AppStore
import com.hikari.app.tv.TvMode
import kotlin.math.roundToInt

/**
 * Settings -> Customize -> Poster cards: the three sizes a card can be drawn
 * at. Portrait and landscape cards each keep their own choice.
 *
 * The SHAPE (portrait / landscape) is the app's existing poster type
 * ([PosterTypes.VERTICAL] / [PosterTypes.CINEMA]); this file only adds the size.
 */
object PosterSizes {
    const val SMALL = "small"
    const val DEFAULT = "default"
    const val LARGE = "large"

    /** Every size, in the order the settings page lists them. */
    val ALL = listOf(SMALL, DEFAULT, LARGE)

    fun normalize(key: String?): String = if (key != null && key in ALL) key else DEFAULT

    fun label(key: String): String = when (normalize(key)) {
        SMALL -> "Small"
        LARGE -> "Large"
        else -> "Default"
    }

    /** How much bigger / smaller than today's card the chosen size draws it
     *  (1f = exactly the size the app always had). */
    fun factor(key: String): Float = when (normalize(key)) {
        SMALL -> 0.8f
        LARGE -> 1.25f
        else -> 1f
    }

    /** Landscape cards in a phone grid: how many columns each size gets. */
    fun landscapeColumns(key: String): Int = when (normalize(key)) {
        SMALL -> 3
        LARGE -> 1
        else -> 2
    }
}

/**
 * Reads the Poster cards settings from anywhere in the UI. Every function is
 * a Composable that follows the saved choice live, so changing a size in
 * Settings repaints rows and grids at once. With the default choices each one
 * returns exactly what the app drew before (factor 1f, same columns).
 */
object PosterSizing {

    @Composable
    private fun rememberStore(): AppStore? =
        remember { runCatching { HikariApp.instance.store }.getOrNull() }

    /** True when the cards on this device are drawn landscape (16:9). */
    @Composable
    fun landscape(): Boolean {
        val store = rememberStore() ?: return false
        val flow = remember(store) { store.posterTypeFlow() }
        val type by flow.collectAsState(initial = store.posterTypeMemory)
        return when (PosterTypes.normalize(type)) {
            PosterTypes.CINEMA -> true
            PosterTypes.VERTICAL -> false
            else -> TvMode.current()
        }
    }

    /** Size choice for portrait cards: small / default / large. */
    @Composable
    fun portraitKey(): String {
        val store = rememberStore() ?: return PosterSizes.DEFAULT
        val flow = remember(store) { store.posterPortraitSizeFlow() }
        val key by flow.collectAsState(initial = store.posterPortraitSizeMemory)
        return key
    }

    /** Size choice for landscape cards: small / default / large. */
    @Composable
    fun landscapeKey(): String {
        val store = rememberStore() ?: return PosterSizes.DEFAULT
        val flow = remember(store) { store.posterLandscapeSizeFlow() }
        val key by flow.collectAsState(initial = store.posterLandscapeSizeMemory)
        return key
    }

    @Composable
    fun portraitFactor(): Float = PosterSizes.factor(portraitKey())

    @Composable
    fun landscapeFactor(): Float = PosterSizes.factor(landscapeKey())

    /** The factor of the shape this device is drawing right now. */
    @Composable
    fun factor(): Float {
        val land = landscape()
        val p = portraitFactor()
        val l = landscapeFactor()
        return if (land) l else p
    }

    /** A fixed column count ([base] columns at the default size) made to follow
     *  the chosen size: bigger cards mean fewer columns, smaller mean more. */
    fun scaledColumns(base: Int, factor: Float): Int {
        if (factor == 1f) return base
        return (base / factor).roundToInt().coerceIn(2, 12)
    }
}
