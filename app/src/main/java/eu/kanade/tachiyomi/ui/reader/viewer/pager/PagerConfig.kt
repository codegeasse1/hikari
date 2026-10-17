package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.Color
import androidx.annotation.ColorInt
import com.hikari.app.reader.TappingInvertMode
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation

/**
 * Paged-mode reader settings (ported from chimahon's PagerConfig). Nekoread's preferences live in
 * the ViewModel, so this is a plain snapshot of the values that affect the native pager viewer —
 * the Compose layer rebuilds it and assigns it to the viewer whenever a setting changes. Like
 * WebtoonConfig, navigation-only toggles (tap zones, page transitions) are kept separate from
 * render-affecting ones so a settings tweak doesn't force a full page re-decode.
 */
class PagerConfig {

    /** Scale type for page fit: SubsamplingScaleImageView SCALE_TYPE_* (CENTER_INSIDE, FIT_WIDTH...). */
    var imageScaleType: Int = SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE

    /** Where double-tap zoom focuses (LEFT / CENTER / RIGHT — auto by reading direction). */
    var imageZoomType: ReaderPageImageView.Config.ZoomStartPosition = ReaderPageImageView.Config.ZoomStartPosition.LEFT

    /** Crop the page's blank borders. */
    var imageCropBorders: Boolean = false

    /** Whether double-tap zoom is enabled. */
    var doubleTapZoom: Boolean = true

    /** Whether zooming IN beyond the fit scale is allowed (false = zooming out only). */
    var disableZoomIn: Boolean = false

    /** Whether pinch-to-zoom is enabled. */
    var enablePinchToZoom: Boolean = true

    /** Whether page turns animate (page transitions). */
    var usePageTransitions: Boolean = false

    /** Double-tap zoom animation duration (ms). */
    var doubleTapAnimDuration: Int = 500

    /** Tap-zone scheme index: 0=default, 1=L, 2=Kindlish, 3=Edge, 4=RightAndLeft, 5=Disabled. */
    var navigationMode: Int = 4

    /** How the tap-zone regions are flipped. */
    var tappingInverted: TappingInvertMode = TappingInvertMode.NONE

    /** Use smaller tap zones (0.25 instead of 0.33). */
    var smallerTapZone: Boolean = false

    /** True when this viewer pages vertically instead of horizontally. */
    var isVertical: Boolean = false

    /** Canvas color behind the page (the reader background). */
    @ColorInt
    var pageCanvasColor: Int = Color.BLACK

    /** Builds the [ViewerNavigation] for the current [navigationMode]/[tappingInverted]/[smallerTapZone]. */
    fun buildNavigator(): ViewerNavigation =
        ViewerNavigation.build(navigationMode, tappingInverted, smallerTapZone)

    /** The per-page render config (zoom, crop, scale...) for the [ReaderPageImageView]. */
    fun readerPageConfig(): ReaderPageImageView.Config = ReaderPageImageView.Config(
        zoomDuration = doubleTapAnimDuration,
        minimumScaleType = imageScaleType,
        cropBorders = imageCropBorders,
        enablePinchToZoom = enablePinchToZoom,
        doubleTapZoom = doubleTapZoom,
        disableZoomIn = disableZoomIn,
        zoomStartPosition = imageZoomType,
    )

    /** Render-affecting settings only — combined into a hash for change detection. */
    fun renderKey(): Int {
        var h = imageScaleType.hashCode()
        h = h * 31 + imageZoomType.hashCode()
        h = h * 31 + imageCropBorders.hashCode()
        h = h * 31 + doubleTapZoom.hashCode()
        h = h * 31 + disableZoomIn.hashCode()
        h = h * 31 + enablePinchToZoom.hashCode()
        h = h * 31 + doubleTapAnimDuration.hashCode()
        h = h * 31 + pageCanvasColor.hashCode()
        return h
    }
}
