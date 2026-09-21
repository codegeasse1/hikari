package com.hikari.app.tv

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp

/**
 * "Is this device a television?", asked once per launch and never guessed from
 * the model name.
 *
 * ONE APK serves phones, tablets, Fire TV, Android TV and Google TV. What
 * differs is not the app (the engines, the player, the downloads, the history
 * and the settings file are the same code) but the *chrome*: a phone has a
 * touchscreen and a taskbar, a television box has a remote and a 10-foot
 * viewing distance. This object answers which one we are running on, and every
 * TV-specific branch in the app reads its answer from here — see
 * [com.hikari.app.ui.navigation.AppRoot] for the rail, [TvFocusIndication] for
 * the focus ring every clickable gets on a TV, and the "TV & Remote" settings
 * folder for the user's own override.
 *
 * The signals, in order of trust:
 *
 *  - `UI_MODE_TYPE_TELEVISION` — the system saying so outright. Android TV and
 *    Google TV set it.
 *  - `android.software.leanback` — the Leanback (d-pad / 10-foot) feature.
 *    Android TV and Google TV declare it.
 *  - `amazon.hardware.fire_tv` — Amazon's own marker. Fire OS is not Google's
 *    TV build, so this is the one that catches a Fire TV Stick that reports a
 *    plain handheld UI mode (the older Sticks do).
 *  - `android.hardware.type.television` — the pre-Android-TV marker, kept for
 *    very old boxes.
 *  - NO touchscreen at all. Every television in existence lacks one, and a
 *    handful of cheap boxes declare none of the four features above. This is
 *    the signal that makes an unknown no-name Android box behave like a TV
 *    instead of like a phone with a taskbar you cannot tap.
 *
 * `android.hardware.type.pc` (ChromeOS and the desktop builds) is a hard NO:
 * those report no touchscreen either, and a Chromebook must not be handed the
 * living-room layout.
 *
 * Everything found here can be overridden by the user — some boxes genuinely
 * misreport themselves, and being stuck with an un-navigable layout because of
 * one wrong flag is not acceptable (Settings → TV & Remote → "This device is a
 * TV"). [override] is that choice, kept in the settings store and mirrored
 * here.
 */
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

    /**
     * The user's override, as a Compose state: switching it in Settings
     * re-lays-out the whole app live, on both kinds of device (a phone user can
     * turn the TV layout on to see it; a television user whose box reports
     * itself as a phone can turn the television layout on).
     */
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

    /**
     * Runs the feature checks. Cheap (a few PackageManager lookups), idempotent
     * and safe to call from `Application.onCreate` and again from an Activity —
     * which is what happens, so that the flag is set before any UI (including
     * the View-based player) can ask for it, and again in case a device's UI
     * mode only settles after the application object exists.
     */
    fun detect(context: Context): Boolean {
        val pm = context.packageManager
        deviceIsTv = !hasFeature(pm, FEATURE_TYPE_PC) && (
            tvUiMode(context) ||
                hasFeature(pm, PackageManager.FEATURE_LEANBACK) ||
                hasFeature(pm, FEATURE_FIRE_TV) ||
                hasFeature(pm, FEATURE_TYPE_TELEVISION) ||
                !hasFeature(pm, PackageManager.FEATURE_TOUCHSCREEN)
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

/**
 * The numbers that make a phone layout readable from a sofa: the size of the
 * navigation rail, the gap the safe area leaves at every screen edge, and how
 * big a poster (and therefore how many of them fit across) should be.
 *
 * All in one object because they have to agree with each other — the rail's
 * width is also the room every screen leaves on its left, and the poster size
 * decides both the row cells and the grid's column count.
 */
object TvUi {
    /**
     * Screen-edge padding ("overscan").
     *
     * Televisions have cut roughly 5% of the picture off since the CRT era, and
     * plenty of modern sets and HDMI switches still do — content drawn hard
     * against the edge simply is not there on those screens (the classic "the
     * back button is half off the left side" on a Fire TV, and the reported
     * "the navigation rail is cut off at the left edge of my TV"). The default
     * keeps every control well inside the safe area; the slider exists because
     * the right number depends on the television, and 0 is offered for the sets
     * that show the whole frame.
     *
     * 48dp is not a guess: a 1080p television reports a 960x540dp viewport, so
     * 5% of the width is exactly 48dp. Anything smaller left the leftmost
     * column of the navigation rail clipped on the sets that crop — which is
     * what the screenshots showed.
     */
    const val DEFAULT_OVERSCAN_DP = 48
    const val MAX_OVERSCAN_DP = 96

    /** The navigation rail's width, which is also the left inset every page
     *  keeps clear of it (see [com.hikari.app.ui.navigation.AppRoot]). */
    val RAIL_WIDTH = 112.dp

    /** One tab's touch/D-pad target on the rail. */
    val RAIL_ITEM_WIDTH = 96.dp

    /** How wide a poster is drawn on a television, in a row or in a grid. A
     *  phone's 120dp cell is a thumbnail at four metres; this is the same card
     *  at a size that reads across a living room. */
    const val POSTER_WIDTH_DP = 168

    /**
     * The minimum cell size a poster grid should use on this device, given the
     * size the phone layout asks for. Grids are `GridCells.Adaptive`, so on a
     * 1920dp television screen a phone-sized 84dp minimum would lay out twenty
     *  two columns of thumbnails; raising it is what turns the same grid into
     *  six or seven proper cells.
     */
    fun gridMin(phoneDp: Int): androidx.compose.ui.unit.Dp =
        if (TvMode.isTv) maxOf(phoneDp, POSTER_WIDTH_DP).dp else phoneDp.dp

    /**
     * Poster cell width for THIS screen — the number that actually has to move.
     *
     * A flat [POSTER_WIDTH_DP] is right on a 1080p television (960dp wide, 540dp
     * tall) and wrong on anything shorter: a 2:3 poster 168dp wide is 252dp
     * tall, so on a 360-540dp-tall landscape display four of them cover the
     * whole screen and the rows underneath are never reached (the "the posters
     * are far too big, three or four of them fill the screen" report). Sizing
     * the cell from the screen's HEIGHT instead — roughly three rows to a
     * screen, clamped to a range that still reads from a sofa — gives about
     * seven cells across a landscape phone and five across a television, with
     * every row's title visible under it.
     */
    @Composable
    fun posterWidth(): androidx.compose.ui.unit.Dp {
        if (!TvMode.current()) return 120.dp
        val h = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
        return (h * 0.30f).coerceIn(96f, POSTER_WIDTH_DP.toFloat()).dp
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
}
