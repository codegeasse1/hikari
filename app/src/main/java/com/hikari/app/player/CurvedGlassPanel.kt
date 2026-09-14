package com.hikari.app.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Shader
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/**
 * The player's dialog glass, as a curved surface rather than a rounded
 * rectangle.
 *
 * Two things make the reference panel read the way it does, and this view does
 * both:
 *
 *  - its outline is a superellipse ([GlassShape]) — the sides bow out to their
 *    widest point at mid-height and taper toward the top and bottom, so the
 *    panel itself is curved. The old shell was a `dialog_panel` rounded
 *    rectangle with the curve only implied by the ring behind it, which is
 *    exactly what the user kept pointing at: "the ui is not rectangle".
 *  - its rows follow that bend. [rows] names the container holding the rows
 *    (may be nested, e.g. a list inside a scroll view); each row is pulled in
 *    to the silhouette's edge at its own height, so the pill stack's envelope IS
 *    the barrel curve instead of a uniform stack sitting inside a curved glass.
 *
 * Rows are positioned by adjusting their margins, measured from where they
 * actually landed (`offsetDescendantRectToMyCoords`), not from a guess — that
 * way an ancestor's padding or a scroll view in between cannot throw the maths
 * off, and the adjustment is idempotent (the target depends only on the row's
 * height, which horizontal margins do not move), so the follow-up layout pass
 * settles immediately instead of oscillating.
 *
 * Anything the rows cannot be pulled far enough to miss (a wrapped, centred
 * child; rows deep inside a scrolling list) is clipped to the shape in
 * [dispatchDraw], so nothing ever paints outside the glass.
 */
class CurvedGlassPanel(context: Context) : LinearLayout(context) {

    /**
     * The container whose CHILDREN are the visible rows. Defaults to this panel
     * itself, which is right when the panel holds the rows directly (the
     * progress panel) or holds a single scroll view that should be inset as a
     * whole (a list too long to fit, where per-row insets would be baked in at
     * scroll-0 and then be wrong for every scrolled row).
     */
    var rows: ViewGroup? = null

    /** How barrel-shaped the panel is — see [GlassShape.EXPONENT]. */
    var exponent: Float = GlassShape.EXPONENT

    /** Air kept between a row and the silhouette's edge, at the widest point. */
    var rowGapPx: Float = 10f * resources.displayMetrics.density

    private val shapePath = Path()
    private val rect = Rect()
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hairlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var passes = 0

    init {
        orientation = VERTICAL
        // A ViewGroup skips onDraw entirely unless it is told not to — without
        // this the fill/rim below would never paint.
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        passes = 0
        GlassShape.build(shapePath, 0f, 0f, w.toFloat(), h.toFloat(), exponent)
        val density = resources.displayMetrics.density
        // Same dark glass the old dialog_panel drawable used, so the panel still
        // sits under the cyan -> violet accent wash the rest of the player uses.
        fillPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            0xF4161D2E.toInt(), 0xF00A0C16.toInt(), Shader.TileMode.CLAMP,
        )
        rimPaint.strokeWidth = 3f * density
        rimPaint.color = 0x2E7B5CFF.toInt()
        hairlinePaint.strokeWidth = 1f * density
        hairlinePaint.color = 0x807B5CFF.toInt()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(shapePath, rimPaint)
        canvas.drawPath(shapePath, fillPaint)
        canvas.drawPath(shapePath, hairlinePaint)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val save = canvas.save()
        canvas.clipPath(shapePath)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        bendRows()
    }

    /**
     * Pulls every row's edges in to the silhouette at that row's own height.
     * Rows that sit outside the panel's vertical span (deep inside a scrolling
     * list, which is why [rows] is pointed at the scroll view instead in those
     * panels) are simply left alone.
     */
    private fun bendRows() {
        val host = rows ?: this
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || host.childCount == 0) return
        var shifted = false
        for (i in 0 until host.childCount) {
            val child = host.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            offsetDescendantRectToMyCoords(child, rect)
            if (rect.bottom <= 0 || rect.top >= height) continue
            // A block that spans the panel's full height IS the scrolling
            // viewport: its own extremes sit where the barrel mathematically
            // tapers to nothing, so measuring them there would clamp the whole
            // list to zero width. Evaluate the curve a little inside instead.
            val yTop = if (rect.top <= 0) h * 0.06f else rect.top.toFloat()
            val yBottom = if (rect.bottom >= height) h * 0.94f else rect.bottom.toFloat()
            // The narrower end of the row is what has to fit, so a row that
            // straddles the widest point still clears the curve at both ends.
            val half = minOf(
                GlassShape.halfWidth(w, h, yTop, exponent),
                GlassShape.halfWidth(w, h, yBottom, exponent),
            ) - rowGapPx
            if (half <= 0f) continue
            val targetLeft = (w / 2f - half).toInt()
            val targetRight = (w / 2f + half).toInt()
            val lp = child.layoutParams as? ViewGroup.MarginLayoutParams ?: continue
            var left = lp.leftMargin
            var right = lp.rightMargin
            if (lp.width == ViewGroup.LayoutParams.MATCH_PARENT) {
                // Stretchable: move both edges onto the target.
                left += targetLeft - rect.left
                right += rect.right - targetRight
            } else {
                // Fixed-width (a spinner, a badge): only ever nudge it back
                // inside, never restretch it.
                if (rect.left < targetLeft) left += targetLeft - rect.left
                if (rect.right > targetRight) right += rect.right - targetRight
            }
            left = left.coerceAtLeast(0)
            right = right.coerceAtLeast(0)
            if (left != lp.leftMargin || right != lp.rightMargin) {
                lp.leftMargin = left
                lp.rightMargin = right
                child.layoutParams = lp
                shifted = true
            }
        }
        // One extra pass to adopt the new margins; the pass after that finds
        // nothing left to change. The cap is belt-and-braces against a view
        // whose own layout keeps moving underneath us (a scrolling list).
        if (shifted && passes < 4) {
            passes++
            requestLayout()
        }
    }
}
