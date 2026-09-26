package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import com.hikari.app.reader.ReaderHideThreshold
import com.hikari.app.reader.TappingInvertMode
import com.hikari.app.reader.WebtoonScaleType
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation

/**
 * Webtoon-mode reader settings (ported from chimahon's WebtoonConfig). Nekoread's preferences live
 * in the ViewModel, so this is a plain snapshot of the values that affect the native viewer — the
 * Compose layer rebuilds it and assigns it to the viewer whenever a setting changes.
 */
class WebtoonConfig {

    /** Crop the page's blank borders in continuous (no-gap) long-strip mode. */
    var cropBordersWebtoon: Boolean = false

    /** Crop the page's blank borders in gaps (non-continuous) long-strip mode. */
    var continuousCropBorders: Boolean = false

    /** Side padding as a percentage of the screen width (0..25). */
    var webtoonSidePadding: Int = 0

    /** Tap-zone scheme index: 0=default, 1=L, 2=Kindlish, 3=Edge, 4=RightAndLeft, 5=Disabled. */
    var navigationMode: Int = 5

    /** How the tap-zone regions are flipped. */
    var tappingInverted: TappingInvertMode = TappingInvertMode.NONE

    /** Use smaller tap zones (0.25 instead of 0.33). */
    var smallerTapZone: Boolean = false

    /** Whether double-tap zoom is enabled. */
    var doubleTapZoom: Boolean = true

    /** Whether pinch-to-zoom is enabled. */
    var pinchToZoom: Boolean = true

    /** Target page aspect ratio for gaps-mode smart scaling (FIT = natural height). */
    var webtoonScaleType: WebtoonScaleType = WebtoonScaleType.FIT

    /** Whether the gaps-mode smart page scaling is enabled. */
    var longStripGapSmartScale: Boolean = false

    /** Whether zooming out below 1x is allowed. */
    var webtoonDisableZoomOut: Boolean = false

    /** Whether tap-zones scroll smoothly (animated) instead of jumping instantly. */
    var usePageTransitions: Boolean = true

    /** Scroll distance (px) above which the reader menu auto-hides. */
    var readerHideThreshold: ReaderHideThreshold = ReaderHideThreshold.LOW

    /** Double-tap zoom animation duration (ms). */
    var doubleTapAnimDuration: Int = 500

    /** Force tall pages through the subsampling view instead of the short Coil path. */
    var alwaysDecodeLongStripWithSSIV: Boolean = false

    /** In gaps mode, tapping the next/prev zone moves by one page instead of scrolling. */
    var continuousVerticalTappingByPage: Boolean = false

    /** Whether auto-scroll animates smoothly. */
    var smoothAutoScroll: Boolean = true

    /** Whether pages fade in when they first load (yomi's "fade in" reader option). */
    var fadeIn: Boolean = false

    /** Builds the [ViewerNavigation] for the current [navigationMode]/[tappingInverted]/[smallerTapZone]. */
    fun buildNavigator(): ViewerNavigation =
        ViewerNavigation.build(navigationMode, tappingInverted, smallerTapZone)
}
