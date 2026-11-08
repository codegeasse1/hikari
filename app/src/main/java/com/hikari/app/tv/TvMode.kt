package com.hikari.app.tv

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp

object TvMode {
    /** Follow whatever the device reports (the shipped default). */
    const val AUTO = "auto"
    /** Always draw the television interface. */
    const val TV = "tv"
    /** Never draw it — always the phone/tablet interface. */
    const val PHONE = "phone"

    private const val FEATURE_FIRE_TV = "amazon.hardware.fire_tv"
    private const val FEATURE_TYPE_TELEVISION = "android.hardware.type.television"
    private const val FEATURE_TYPE_PC = "android.hardware.type.pc"

    /** What the DEVICE said, ignoring the user's override. */
    @Volatile
    private var deviceIsTv = false

    /** True once [detect] has run — i.e. [deviceIsTv] means something. */
    @Volatile
    private var detected = false

    private val overrideState = mutableStateOf(AUTO)

    val override: String get() = overrideState.value

    /** Whether the device itself looks like a television. */
    val deviceIsTelevision: Boolean get() = deviceIsTv

    /** True once [detect] has run. */
    val isDetected: Boolean get() = detected

    /** The answer every branch should use: the override if there is one, else
     *  the device. Readable from any thread (the View-based player reads it). */
    val isTv: Boolean get() = resolve(overrideState.value)

    fun normalize(mode: String?): String = when (mode) {
        TV -> TV
        PHONE -> PHONE
        else -> AUTO
    }

    private fun resolve(mode: String): Boolean = when (mode) {
        TV -> true
        PHONE -> false
        else -> deviceIsTv
    }

    /** Applies the stored preference. Called at startup and whenever it changes. */
    fun setOverride(mode: String?) {
        overrideState.value = normalize(mode)
    }

    fun detect(context: Context): Boolean {
        val pm = context.packageManager
        val touchscreen: Boolean? = try {
            pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        } catch (t: Throwable) {
            null
        }
        deviceIsTv = !hasFeature(pm, FEATURE_TYPE_PC) && (
            tvUiMode(context) ||
                hasFeature(pm, PackageManager.FEATURE_LEANBACK) ||
                hasFeature(pm, FEATURE_FIRE_TV) ||
                hasFeature(pm, FEATURE_TYPE_TELEVISION) ||
                touchscreen == false
            )
        detected = true
        return deviceIsTv
    }

    /** Every signal, spelled out — one log line that says why a device was
     *  taken for a television (or not). */
    fun describe(context: Context): String {
        val pm = context.packageManager
        return "uiModeTv=" + tvUiMode(context) +
            " leanback=" + hasFeature(pm, PackageManager.FEATURE_LEANBACK) +
            " fireTv=" + hasFeature(pm, FEATURE_FIRE_TV) +
            " typeTv=" + hasFeature(pm, FEATURE_TYPE_TELEVISION) +
            " touch=" + hasFeature(pm, PackageManager.FEATURE_TOUCHSCREEN) +
            " pc=" + hasFeature(pm, FEATURE_TYPE_PC)
    }

    /** A feature query that can never throw: on some boxes a PackageManager call
     *  fails outright, and "we could not ask" must not become "the app
     *  crashed". */
    private fun hasFeature(pm: PackageManager, feature: String): Boolean =
        try {
            pm.hasSystemFeature(feature)
        } catch (t: Throwable) {
            false
        }

    private fun tvUiMode(context: Context): Boolean =
        try {
            (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)
                ?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        } catch (t: Throwable) {
            false
        }

    /** Compose-facing read: subscribes to the override, so flipping it in
     *  Settings re-draws the app as the other kind of device immediately. */
    @Composable
    fun current(): Boolean {
        val mode = overrideState.value
        return resolve(mode)
    }
}

object TvUi {
    const val DEFAULT_OVERSCAN_DP = 0
    const val MAX_OVERSCAN_DP = 96

    /** The navigation rail's width, which is also the left inset every page
     *  keeps clear of it (see [com.hikari.app.ui.navigation.AppRoot]). Slim
     *  like the reference client's icon rail — the focused tab still expands
     *  into its labelled pill over the content. */
    val RAIL_WIDTH = 84.dp

    /** The rail's EXPANDED width: the full labelled panel the rail becomes
     *  while the D-pad sits on it (see TvNavRail). Content keeps the collapsed
     *  inset (see [railWidth]) — the panel overlays it, like the reference. */
    val RAIL_EXPANDED_WIDTH = 232.dp

    /** Adaptive TV type scale, from the viewport width: 1.0 on a 1080p panel
     *  (960dp wide), smaller on short/dense screens, larger on 4K — clamped to
     *  a range that still reads from a sofa without blowing the layout apart.
     *  Rail, hero, detail and episode text sizes all ride this one number. */
    @Composable
    fun scale(): Float {
        if (!TvMode.current()) return 1f
        val w = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
        return (w / 960f).coerceIn(0.8f, 1.3f)
    }

    @Composable
    fun textScale(): Float {
        if (!TvMode.current()) return 1f
        val app = remember { runCatching { com.hikari.app.HikariApp.instance }.getOrNull() }
        val pct by (app?.store?.tvTextScaleFlow()
            ?: kotlinx.coroutines.flow.flowOf(100)).collectAsState(initial = 100)
        return (pct.coerceIn(50, 150)) / 100f
    }

    /** The left inset a page keeps clear of the rail: the collapsed width
     *  always (the expanded panel overlays the content, like the reference). */
    @Composable
    fun railWidth(): androidx.compose.ui.unit.Dp = RAIL_WIDTH

    /** One tab's touch/D-pad target on the rail. */
    val RAIL_ITEM_WIDTH = 96.dp

    /** How wide a poster is drawn on a television, in a row or in a grid. A
     *  phone's 120dp cell is a thumbnail at four metres; this is the same card
     *  at a size that reads across a living room. */
    const val POSTER_WIDTH_DP = 168

    fun gridMin(phoneDp: Int): androidx.compose.ui.unit.Dp =
        if (TvMode.isTv) maxOf(phoneDp, POSTER_WIDTH_DP).dp else phoneDp.dp

    @Composable
    fun posterWidth(): androidx.compose.ui.unit.Dp {
        if (!TvMode.current()) return 120.dp
        val h = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
        val app = remember { runCatching { com.hikari.app.HikariApp.instance }.getOrNull() }
        val scalePct by (app?.store?.tvPosterScaleFlow()
            ?: kotlinx.coroutines.flow.flowOf(100)).collectAsState(initial = 100)
        val scale = (scalePct.coerceIn(70, 150)) / 100f
        return (h * 0.15f * scale).coerceIn(40f, 150f).dp
    }

    /** [gridMin], but sized from the screen rather than a constant — see
     *  [posterWidth]. This is what a poster grid should use. */
    @Composable
    fun gridMinFor(phoneDp: Int): androidx.compose.ui.unit.Dp =
        if (TvMode.current()) maxOf(phoneDp.toFloat(), posterWidth().value).dp else phoneDp.dp

    /** The same idea for a FIXED column count (the search results grid): as many
     *  columns as the screen can hold at [posterWidth], so a television gets the
     *  cells an adaptive grid would have given it. */
    @Composable
    fun gridColumns(phoneColumns: Int): Int {
        if (!TvMode.current()) return phoneColumns
        val w = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
        val avail = (w - RAIL_WIDTH.value).coerceAtLeast(200f)
        return (avail / posterWidth().value).toInt().coerceIn(4, 8)
    }

    @Composable
    fun resultsColumns(): Int =
        if (TvMode.current()) (gridColumns(4) + 2).coerceIn(4, 9) else 4
}
