package com.hikari.app.reader

import org.json.JSONObject

/**
 * The reader's modes, backgrounds, fits and orientation lock — Nekoread's own
 * enums (`com.example.ui`), kept as plain Kotlin enums with no string resources
 * so the ported chrome can label them and the ported native viewers can consume
 * their values directly.
 */

/** How the reader advances through a chapter. */
enum class ReaderMode {
    /** One continuous vertical strip, pages butted together with no gap. */
    WEBTOON,

    /** The same strip with a small gap between pages. */
    WEBTOON_GAPS,

    /** One page per screen, swiped and tapped left to right. */
    LEFT_TO_RIGHT,

    /** The same, mirrored: swiping right shows the NEXT page (Japanese manga). */
    RIGHT_TO_LEFT,

    /** One page per screen, advancing downwards (a vertical pager). */
    VERTICAL,
}

/** The page backdrop. */
enum class ReaderBg {
    PURE_BLACK,
    DARK_GRAY,
    CREAM,
    WHITE,
}

/**
 * How a page is fitted to the screen in the PAGED modes.
 *
 * The webtoon modes ignore this by design (as in Nekoread): a long strip is
 * always drawn at the screen's width, because that is what a strip is drawn for —
 * fitting it to the height would leave two thirds of a phone screen empty beside
 * every page.
 */
enum class ReaderFit {
    /** Whole page inside the screen, letterboxed, nothing off-screen. */
    FIT,

    /** Fill the screen on both axes; a page whose ratio differs is cropped. */
    STRETCH,

    /** Fill the width and let the page be taller than the screen (the default). */
    FIT_WIDTH,

    /** Fill the height; a wide page is cropped or scrolled sideways. */
    FIT_HEIGHT,

    /** The page's own pixels, at 1:1. */
    ORIGINAL_SIZE,

    /** [FIT], with the page's blank borders detected and cut. */
    SMART_FIT,
}

/** Whether the reader locks the screen orientation while it is open. */
enum class ReaderOrientation {
    AUTO,
    PORTRAIT,
    LANDSCAPE,
}

/** True for the two continuous-strip modes (they share every strip behaviour). */
val ReaderMode.isWebtoon: Boolean
    get() = this == ReaderMode.WEBTOON || this == ReaderMode.WEBTOON_GAPS

/**
 * One chapter as the reader's chrome needs it — Nekoread's `ChapterEntity`, with
 * only the three fields its chapter list draws: the id the reader jumps by (this
 * app's chapter URL), the label, and whether it has been read.
 */
data class ReaderChapter(
    val id: String,
    val name: String,
    val read: Boolean = false,
)

/**
 * Every reader option, in one object.
 *
 * This is the ported chrome's whole settings surface (Nekoread keeps these in its
 * ViewModel's `StateFlow`s). It is stored as one JSON blob rather than thirty
 * preferences: the settings travel together — a backup, a reset and the reader's
 * own "Reset settings" row all mean this object — and a new option added later
 * needs no new key. The legacy per-key preferences this app used before are read
 * once as the defaults for the fields they cover (see [fromLegacy]), so nobody
 * loses the reading mode or background they had picked.
 *
 * Defaults are the reference reader's own out-of-the-box states (the settings
 * screens the user went through and asked to be the app's defaults): webtoon mode
 * with a pure black backdrop, page transitions and smooth auto-scroll ON, and
 * every gesture that would otherwise change what a plain swipe does OFF — pinch
 * to zoom, double-tap to zoom and tap-to-turn-the-page — because on a long strip
 * those turn a scroll into a jump. Tap zones are `Edge`, the webtoon scale type
 * is `Fit`, the menu hides at the `Normal` threshold, and pages are decoded at
 * the `High (sharp)` quality. A fresh install therefore reads a chapter exactly
 * the way the reference app does, and every one of them is one tap away in the
 * chrome (or one tap on *Reset to defaults*).
 */
data class ReaderSettings(
    val mode: ReaderMode = ReaderMode.WEBTOON,
    val bg: ReaderBg = ReaderBg.PURE_BLACK,
    val fit: ReaderFit = ReaderFit.FIT_WIDTH,
    val orientation: ReaderOrientation = ReaderOrientation.AUTO,
    val keepScreenOn: Boolean = true,
    val showPageNumber: Boolean = false,
    val cropBorders: Boolean = false,
    val cropBordersPaged: Boolean = false,
    val cropBordersContinuous: Boolean = false,
    val doubleTapZoom: Boolean = false,
    val pinchToZoom: Boolean = false,
    val tapToChangePages: Boolean = false,
    val webtoonSidePadding: Int = 0,
    val webtoonNavigationMode: Int = 3,
    val webtoonNavInverted: TappingInvertMode = TappingInvertMode.NONE,
    val webtoonSmallerTapZone: Boolean = false,
    val webtoonScaleType: WebtoonScaleType = WebtoonScaleType.FIT,
    val longStripGapSmartScale: Boolean = false,
    val webtoonDisableZoomOut: Boolean = false,
    val webtoonPageTransitions: Boolean = true,
    val webtoonSmoothAutoScroll: Boolean = true,
    val alwaysDecodeLongStripWithSSIV: Boolean = false,
    val continuousVerticalTappingByPage: Boolean = false,
    val readerHideThreshold: ReaderHideThreshold = ReaderHideThreshold.LOW,
    val doubleTapAnimDuration: Int = 500,
    val showReadingMode: Boolean = false,
    val customBrightness: Boolean = false,
    val customBrightnessValue: Int = 0,
    val colorFilter: Boolean = false,
    val colorFilterValue: Int = 0,
    val colorFilterMode: Int = 0,
    val grayscale: Boolean = false,
    val invertedColors: Boolean = false,
    val imageEnhance: Boolean = false,
    val readerQuality: Int = 100,
    val webtoonFade: Boolean = false,
    val autoScroll: Boolean = false,
    val autoScrollSpeedDp: Float = 30f,
) {

    /** The reader background as a Compose-visible ARGB int. */
    val bgArgb: Long
        get() = when (bg) {
            ReaderBg.PURE_BLACK -> 0xFF000000L
            ReaderBg.DARK_GRAY -> 0xFF181A24L
            ReaderBg.CREAM -> 0xFFFBF0D9L
            ReaderBg.WHITE -> 0xFFFFFFFFL
        }

    /** True when the backdrop is light, so the chrome's own text has to be dark. */
    val lightBackdrop: Boolean
        get() = bg == ReaderBg.CREAM || bg == ReaderBg.WHITE

    /** The crop setting that applies to the mode in force (Nekoread's rule). */
    val activeCrop: Boolean
        get() = when (mode) {
            ReaderMode.WEBTOON -> cropBorders
            ReaderMode.WEBTOON_GAPS -> cropBordersContinuous
            else -> cropBordersPaged
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("mode", mode.name)
        put("bg", bg.name)
        put("fit", fit.name)
        put("orientation", orientation.name)
        put("keepScreenOn", keepScreenOn)
        put("showPageNumber", showPageNumber)
        put("cropBorders", cropBorders)
        put("cropBordersPaged", cropBordersPaged)
        put("cropBordersContinuous", cropBordersContinuous)
        put("doubleTapZoom", doubleTapZoom)
        put("pinchToZoom", pinchToZoom)
        put("tapToChangePages", tapToChangePages)
        put("webtoonSidePadding", webtoonSidePadding)
        put("webtoonNavigationMode", webtoonNavigationMode)
        put("webtoonNavInverted", webtoonNavInverted.name)
        put("webtoonSmallerTapZone", webtoonSmallerTapZone)
        put("webtoonScaleType", webtoonScaleType.name)
        put("longStripGapSmartScale", longStripGapSmartScale)
        put("webtoonDisableZoomOut", webtoonDisableZoomOut)
        put("webtoonPageTransitions", webtoonPageTransitions)
        put("webtoonSmoothAutoScroll", webtoonSmoothAutoScroll)
        put("alwaysDecodeLongStripWithSSIV", alwaysDecodeLongStripWithSSIV)
        put("continuousVerticalTappingByPage", continuousVerticalTappingByPage)
        put("readerHideThreshold", readerHideThreshold.name)
        put("doubleTapAnimDuration", doubleTapAnimDuration)
        put("showReadingMode", showReadingMode)
        put("customBrightness", customBrightness)
        put("customBrightnessValue", customBrightnessValue)
        put("colorFilter", colorFilter)
        put("colorFilterValue", colorFilterValue)
        put("colorFilterMode", colorFilterMode)
        put("grayscale", grayscale)
        put("invertedColors", invertedColors)
        put("imageEnhance", imageEnhance)
        put("readerQuality", readerQuality)
        put("webtoonFade", webtoonFade)
        put("autoScroll", autoScroll)
        put("autoScrollSpeedDp", autoScrollSpeedDp.toDouble())
    }

    companion object {

        /** The fallback for every field a stored blob does not name. */
        val DEFAULTS = ReaderSettings()

        /**
         * Builds settings from this app's older per-key preferences (reading mode,
         * fit, background, keep-awake, page number), used when there is no stored
         * blob yet so an existing install keeps the reader it had.
         */
        fun fromLegacy(
            mode: ReaderMode,
            fit: ReaderFit,
            bg: ReaderBg,
            keepAwake: Boolean,
            showPageNumber: Boolean,
            enhance: Boolean,
        ): ReaderSettings = DEFAULTS.copy(
            mode = mode,
            fit = fit,
            bg = bg,
            keepScreenOn = keepAwake,
            showPageNumber = showPageNumber,
            imageEnhance = enhance,
        )

        fun fromJson(raw: String?): ReaderSettings {
            if (raw.isNullOrBlank()) return DEFAULTS
            val o = runCatching { JSONObject(raw) }.getOrNull() ?: return DEFAULTS
            val d = DEFAULTS
            fun enumOf(name: String, fallback: ReaderMode) = ReaderMode.entries.firstOrNull { it.name == name } ?: fallback
            fun bgOf(name: String) = ReaderBg.entries.firstOrNull { it.name == name } ?: d.bg
            fun fitOf(name: String) = ReaderFit.entries.firstOrNull { it.name == name } ?: d.fit
            fun orientOf(name: String) = ReaderOrientation.entries.firstOrNull { it.name == name } ?: d.orientation
            fun invertOf(name: String) = TappingInvertMode.entries.firstOrNull { it.name == name } ?: d.webtoonNavInverted
            fun scaleOf(name: String) = WebtoonScaleType.entries.firstOrNull { it.name == name } ?: d.webtoonScaleType
            fun hideOf(name: String) = ReaderHideThreshold.entries.firstOrNull { it.name == name } ?: d.readerHideThreshold
            return ReaderSettings(
                mode = enumOf(o.optString("mode"), d.mode),
                bg = bgOf(o.optString("bg")),
                fit = fitOf(o.optString("fit")),
                orientation = orientOf(o.optString("orientation")),
                keepScreenOn = o.optBoolean("keepScreenOn", d.keepScreenOn),
                showPageNumber = o.optBoolean("showPageNumber", d.showPageNumber),
                cropBorders = o.optBoolean("cropBorders", d.cropBorders),
                cropBordersPaged = o.optBoolean("cropBordersPaged", d.cropBordersPaged),
                cropBordersContinuous = o.optBoolean("cropBordersContinuous", d.cropBordersContinuous),
                doubleTapZoom = o.optBoolean("doubleTapZoom", d.doubleTapZoom),
                pinchToZoom = o.optBoolean("pinchToZoom", d.pinchToZoom),
                tapToChangePages = o.optBoolean("tapToChangePages", d.tapToChangePages),
                webtoonSidePadding = o.optInt("webtoonSidePadding", d.webtoonSidePadding),
                webtoonNavigationMode = o.optInt("webtoonNavigationMode", d.webtoonNavigationMode),
                webtoonNavInverted = invertOf(o.optString("webtoonNavInverted")),
                webtoonSmallerTapZone = o.optBoolean("webtoonSmallerTapZone", d.webtoonSmallerTapZone),
                webtoonScaleType = scaleOf(o.optString("webtoonScaleType")),
                longStripGapSmartScale = o.optBoolean("longStripGapSmartScale", d.longStripGapSmartScale),
                webtoonDisableZoomOut = o.optBoolean("webtoonDisableZoomOut", d.webtoonDisableZoomOut),
                webtoonPageTransitions = o.optBoolean("webtoonPageTransitions", d.webtoonPageTransitions),
                webtoonSmoothAutoScroll = o.optBoolean("webtoonSmoothAutoScroll", d.webtoonSmoothAutoScroll),
                alwaysDecodeLongStripWithSSIV = o.optBoolean("alwaysDecodeLongStripWithSSIV", d.alwaysDecodeLongStripWithSSIV),
                continuousVerticalTappingByPage = o.optBoolean("continuousVerticalTappingByPage", d.continuousVerticalTappingByPage),
                readerHideThreshold = hideOf(o.optString("readerHideThreshold")),
                doubleTapAnimDuration = o.optInt("doubleTapAnimDuration", d.doubleTapAnimDuration),
                showReadingMode = o.optBoolean("showReadingMode", d.showReadingMode),
                customBrightness = o.optBoolean("customBrightness", d.customBrightness),
                customBrightnessValue = o.optInt("customBrightnessValue", d.customBrightnessValue),
                colorFilter = o.optBoolean("colorFilter", d.colorFilter),
                colorFilterValue = o.optInt("colorFilterValue", d.colorFilterValue),
                colorFilterMode = o.optInt("colorFilterMode", d.colorFilterMode),
                grayscale = o.optBoolean("grayscale", d.grayscale),
                invertedColors = o.optBoolean("invertedColors", d.invertedColors),
                imageEnhance = o.optBoolean("imageEnhance", d.imageEnhance),
                readerQuality = o.optInt("readerQuality", d.readerQuality),
                webtoonFade = o.optBoolean("webtoonFade", d.webtoonFade),
                autoScroll = o.optBoolean("autoScroll", d.autoScroll),
                autoScrollSpeedDp = o.optDouble("autoScrollSpeedDp", d.autoScrollSpeedDp.toDouble()).toFloat(),
            )
        }
    }
}
