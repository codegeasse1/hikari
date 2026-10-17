package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.PointF
import android.graphics.RectF
import com.hikari.app.reader.TappingInvertMode

/** The action triggered by a tap zone of the reader. */
sealed class NavigationRegion {
    object MENU : NavigationRegion()
    object PREV : NavigationRegion()
    object NEXT : NavigationRegion()
    object LEFT : NavigationRegion()
    object RIGHT : NavigationRegion()
}

/** A rectangular screen region (in normalized 0..1 coordinates) mapped to a [NavigationRegion]. */
data class Region(val rectF: RectF, val type: NavigationRegion) {
    /** Returns a copy of this region with its rect flipped per [invertMode]. */
    fun invert(invertMode: TappingInvertMode): Region {
        if (invertMode == TappingInvertMode.NONE) return this
        return copy(rectF = rectF.invert(invertMode))
    }
}

/** Flipped copy of this rect about the horizontal/vertical center axis per [invertMode]. */
private fun RectF.invert(invertMode: TappingInvertMode): RectF {
    var left = this.left
    var right = this.right
    var top = this.top
    var bottom = this.bottom
    if (invertMode.shouldInvertHorizontal) {
        left = 1f - this.right
        right = 1f - this.left
    }
    if (invertMode.shouldInvertVertical) {
        top = 1f - this.bottom
        bottom = 1f - this.top
    }
    return RectF(left, top, right, bottom)
}

/**
 * Defines the reader's tap zones and maps a tap position to a [NavigationRegion]. Ported from
 * chimahon's ViewerNavigation (region coordinates are normalized 0..1; taps outside every zone but
 * inside the top strip open the menu).
 */
abstract class ViewerNavigation(private val smallerTapZone: Boolean = false) {

    private val constantMenuRegion = RectF(0f, 0f, 1f, 0.05f)

    var invertMode: TappingInvertMode = TappingInvertMode.NONE

    protected abstract var regionList: List<Region>

    fun getRegions(): List<Region> = regionList.map { it.invert(invertMode) }

    fun getAction(pos: PointF): NavigationRegion {
        val x = pos.x
        val y = pos.y
        val region = getRegions().find { it.rectF.contains(x, y) }
        return when {
            region != null -> region.type
            constantMenuRegion.contains(x, y) -> NavigationRegion.MENU
            else -> NavigationRegion.MENU
        }
    }

    protected val regionSize1: Float
        get() = if (smallerTapZone) 0.25f else 0.33f

    protected val regionSize2: Float
        get() = 1f - regionSize1

    companion object {
        /** Builds the [ViewerNavigation] for the given tap-zone [mode] (0=default, 1..5). */
        fun build(mode: Int, invertMode: TappingInvertMode, smallerTapZone: Boolean): ViewerNavigation {
            val navigator = when (mode) {
                0, 1 -> LNavigation(smallerTapZone)
                2 -> KindlishNavigation(smallerTapZone)
                3 -> EdgeNavigation(smallerTapZone)
                4 -> RightAndLeftNavigation(smallerTapZone)
                else -> DisabledNavigation(smallerTapZone)
            }
            navigator.invertMode = invertMode
            return navigator
        }
    }
}

/**
 * +---+---+---+
 * | P | P | P |
 * +---+---+---+
 * | P | M | N |
 * +---+---+---+
 * | N | N | N |
 * +---+---+---+
 */
class LNavigation(smallerTapZone: Boolean = false) : ViewerNavigation(smallerTapZone) {
    override var regionList: List<Region> = listOf(
        Region(RectF(0f, 0f, 1f, regionSize1), NavigationRegion.PREV),
        Region(RectF(0f, regionSize1, regionSize1, regionSize2), NavigationRegion.PREV),
        Region(RectF(regionSize2, regionSize1, 1f, regionSize2), NavigationRegion.NEXT),
        Region(RectF(0f, regionSize2, 1f, 1f), NavigationRegion.NEXT),
    )
}

/**
 * +---+---+---+
 * | M | M | M |
 * +---+---+---+
 * | P | N | N |
 * +---+---+---+
 * | P | N | N |
 * +---+---+---+
 */
class KindlishNavigation(smallerTapZone: Boolean = false) : ViewerNavigation(smallerTapZone) {
    override var regionList: List<Region> = listOf(
        Region(RectF(regionSize1, regionSize1, 1f, 1f), NavigationRegion.NEXT),
        Region(RectF(0f, regionSize1, regionSize1, 1f), NavigationRegion.PREV),
    )
}

/**
 * +---+---+---+
 * | N | N | N |
 * +---+---+---+
 * | N | M | N |
 * +---+---+---+
 * | N | P | N |
 * +---+---+---+
 */
class EdgeNavigation(smallerTapZone: Boolean = false) : ViewerNavigation(smallerTapZone) {
    override var regionList: List<Region> = listOf(
        Region(RectF(0f, 0f, regionSize1, 1f), NavigationRegion.NEXT),
        Region(RectF(regionSize1, regionSize2, regionSize2, 1f), NavigationRegion.PREV),
        Region(RectF(regionSize2, 0f, 1f, 1f), NavigationRegion.NEXT),
    )
}

/**
 * +---+---+---+
 * | N | M | P |
 * +---+---+---+
 * | N | M | P |
 * +---+---+---+
 * | N | M | P |
 * +---+---+---+
 */
class RightAndLeftNavigation(smallerTapZone: Boolean = false) : ViewerNavigation(smallerTapZone) {
    override var regionList: List<Region> = listOf(
        Region(RectF(0f, 0f, regionSize1, 1f), NavigationRegion.LEFT),
        Region(RectF(regionSize2, 0f, 1f, 1f), NavigationRegion.RIGHT),
    )
}

/**
 * +---+---+---+
 * | M | M | M |
 * +---+---+---+
 * | M | M | M |
 * +---+---+---+
 * | M | M | M |
 * +---+---+---+
 */
class DisabledNavigation(smallerTapZone: Boolean = false) : ViewerNavigation(smallerTapZone) {
    override var regionList: List<Region> = emptyList()
}
