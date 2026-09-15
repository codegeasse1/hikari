package com.hikari.app.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Shader
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.hikari.app.data.Logs
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The player's dialog glass: ONE curved surface that carries its own light.
 *
 * Three things make the reference panel read the way it does, and this view does
 * all three:
 *
 *  - its outline is the curved pane in [GlassShape]: the right edge bulges
 *    outward and the left edge bows inward, so the panel itself is a bent sheet
 *    of glass rather than a rounded rectangle (the old `dialog_panel` drawable)
 *    or a barely-bowed superellipse.
 *  - the neon light runs along the panel's OWN two bowed sides — [sidesPath],
 *    stroked in [onDraw] — not along the flat top and bottom, and it fades to
 *    nothing at the ends. That is what the reference does, and it is why the
 *    panel has no "box" lines: the light never leaves the arcs, so there is
 *    neither a horizontal line across the top or bottom nor a short horizontal
 *    stub where a side meets a corner. The light used to live in a separate
 *    ring view parked behind the panel, which is what the user kept pointing
 *    at: a box with a curve behind it, instead of one shape whose edge IS the
 *    curve.
 *  - its rows follow the bend. [bendHost] names each container whose children
 *    are rows (which may be nested, e.g. a list inside a scroll view); each row
 *    is pulled in to the silhouette's own left and right edges at its own
 *    height, so the pill stack's envelope IS the shape instead of a uniform
 *    stack sitting inside a curved glass.
 *
 * Rows are positioned by adjusting their margins, measured from where they
 * actually landed (`offsetDescendantRectToMyCoords`) rather than from a guess,
 * so an ancestor's padding or a scroll view in between cannot throw the maths
 * off. The target depends only on the row's height — which horizontal margins do
 * not move — so the follow-up layout pass settles immediately instead of
 * oscillating.
 *
 * [haloPx] reserves room inside this view's bounds for the glow to bloom into;
 * the silhouette (and the content) live inset by that much, so the light is
 * never sliced off by the view edge.
 */
class CurvedGlassPanel(context: Context) : LinearLayout(context) {

    /**
     * The containers whose CHILDREN are the visible rows. Each is bent
     * independently, so a dialog whose rows live in two places (a track list
     * plus a stack of control rows) can hand both over and every row follows the
     * curve. When empty, the panel bends its own children, which is right for a
     * panel that holds its content directly (the progress panel) or one whose
     * single child is a scroll view.
     *
     * Never register a container AND one of its ancestors: the ancestor would be
     * bent as one block and could clip the rows the inner container then moves.
     */
    private val hosts = ArrayList<ViewGroup>()

    /** Registers [container] as a row container: its children bend to the curve. */
    fun bendHost(container: ViewGroup) {
        if (container !== this && !hosts.contains(container)) hosts.add(container)
    }

    /** How far the right edge bulges outward — see [GlassShape.BULGE_X]. */
    var bulgeX: Float = GlassShape.BULGE_X

    /** How far the left edge bows inward — see [GlassShape.CONCAVE_X]. */
    var concaveX: Float = GlassShape.CONCAVE_X

    /** Room inside this view's bounds for the glow to fade into, in px. */
    var haloPx: Float = 0f

    /** Air kept between a row and the silhouette's edge, at the widest point. */
    var rowGapPx: Float = 10f * resources.displayMetrics.density

    /** Accent the glow is tinted with, top to bottom. */
    var startColor: Int = Color.rgb(120, 220, 255)
    var midColor: Int = Color.rgb(150, 140, 255)
    var endColor: Int = Color.rgb(240, 160, 255)

    private val shapePath = Path()
    private val sidesPath = Path()
    private val rect = Rect()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var passes = 0

    /** Set while [onLayout] is running. A bend may not ask for another layout
     *  pass from inside the pass that is already in flight — that is what turned
     *  a scroll (or an overscroll bounce) into a visible shudder. */
    private var inLayout = false

    /** Coalesces the many scroll notifications of a single frame into one bend. */
    private var rebendPosted = false

    /** The left/right margins each row was laid out with, keyed weakly so a
     *  rebuilt list cannot pin its old rows in memory. Every bend is computed
     *  from these rather than from the margins the previous bend applied — see
     *  [bendHostChildren]. */
    private val baseMargins = WeakHashMap<View, IntArray>()

    /** Rows that once had a real size, so a row that loses it can be reported
     *  (one line per row, not one per frame) instead of vanishing silently. */
    private val sizedOnce = WeakHashMap<View, Boolean>()
    private val collapsedLogged = WeakHashMap<View, Boolean>()

    private val rebendRunnable = Runnable {
        rebendPosted = false
        passes = 0
        bendRows()
    }

    init {
        orientation = VERTICAL
        // A ViewGroup skips onDraw entirely unless it is told not to — without
        // this the glass and the glow below would never paint.
        setWillNotDraw(false)
    }

    /** The silhouette's box inside this view, i.e. the panel proper. */
    private fun shapeLeft() = haloPx
    private fun shapeTop() = haloPx
    private fun shapeRight() = width - haloPx
    private fun shapeBottom() = height - haloPx

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        passes = 0
        val left = shapeLeft()
        val top = shapeTop()
        val right = shapeRight()
        val bottom = shapeBottom()
        GlassShape.build(shapePath, left, top, right, bottom, bulgeX, concaveX)
        GlassShape.buildSides(sidesPath, left, top, right, bottom, bulgeX, concaveX)
        val density = resources.displayMetrics.density
        // Same dark glass the old dialog_panel drawable used, so the panel still
        // sits under the cyan -> violet accent wash the rest of the player uses.
        // The alpha falls off at the very top and bottom so the panel's flat
        // edges dissolve instead of drawing a border — in the reference the
        // silhouette simply fades out where the curves end.
        fillPaint.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(
                0x00161D2E,
                0xF4161D2E.toInt(),
                0xF00A0C16.toInt(),
                0x000A0C16,
            ),
            floatArrayOf(0f, 0.10f, 0.90f, 1f),
            Shader.TileMode.CLAMP,
        )
        // Light travels down the panel's own edge: bright through the middle,
        // fading out well before the ends, so the arcs taper away instead of
        // running into the top and bottom corners. Sampled along the
        // silhouette's vertical span so the gradient tracks the shape rather
        // than the view.
        glowPaint.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(
                withAlpha(startColor, 0f),
                withAlpha(startColor, 0.5f),
                withAlpha(midColor, 1f),
                withAlpha(endColor, 0.55f),
                withAlpha(endColor, 0f),
            ),
            floatArrayOf(0f, 0.18f, 0.45f, 0.82f, 1f),
            Shader.TileMode.CLAMP,
        )
        corePaint.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(
                withAlpha(CORE_TOP, 0f),
                withAlpha(CORE_TOP, 0.5f),
                withAlpha(CORE_MID, 1f),
                withAlpha(CORE_BOTTOM, 0.55f),
                withAlpha(CORE_BOTTOM, 0f),
            ),
            floatArrayOf(0f, 0.18f, 0.45f, 0.82f, 1f),
            Shader.TileMode.CLAMP,
        )
        rimPaint.strokeWidth = 3f * density
        rimPaint.color = 0x2E7B5CFF.toInt()
        // Content clears the bowed top/bottom; horizontally the rows bend to the
        // silhouette themselves (see bendRows), so no side padding is added here
        // — padding on top of a bent row would double the inset.
        val padV = (haloPx + rowGapPx).toInt()
        if (paddingTop != padV || paddingBottom != padV ||
            paddingLeft != 0 || paddingRight != 0
        ) {
            setPadding(0, padV, 0, padV)
        }
    }

    override fun onDraw(canvas: Canvas) {
        // The bloom, the panel, then the hot line back on the very edge: drawn
        // in this order the fill hides the inner half of each glow stroke, so
        // the light reads as coming OFF the panel rather than ringed around it.
        // Both are stroked on [sidesPath] only, never the closed outline.
        val density = resources.displayMetrics.density
        for (i in STROKE_DP.indices) {
            glowPaint.strokeWidth = STROKE_DP[i] * density
            glowPaint.alpha = (ALPHA[i] * 255f).toInt().coerceIn(0, 255)
            canvas.drawPath(sidesPath, glowPaint)
        }
        canvas.drawPath(shapePath, fillPaint)
        canvas.drawPath(sidesPath, rimPaint)
        corePaint.strokeWidth = CORE_STROKE_DP * density
        corePaint.alpha = (CORE_ALPHA * 255f).toInt().coerceIn(0, 255)
        canvas.drawPath(sidesPath, corePaint)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val save = canvas.save()
        canvas.clipPath(shapePath)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        inLayout = true
        try {
            super.onLayout(changed, l, t, r, b)
        } finally {
            inLayout = false
        }
        passes = 0
        bendRows()
    }

    /**
     * Re-bends the rows after something other than a size change moved them —
     * in practice a scroll, which changes the height inside the panel that each
     * row sits at, and therefore which part of the curve it has to clear. The
     * pass cap is reset so a long scroll can never exhaust it.
     *
     * Calls are coalesced onto the next frame's animation phase: a scroll emits
     * a change event per pixel, and bending on each of them re-laid out the
     * whole panel several times per frame. The animation phase runs before that
     * frame's traversal, so the fresh margins are applied by the same frame.
     */
    fun rebend() {
        if (rebendPosted || !isAttachedToWindow) return
        rebendPosted = true
        postOnAnimation(rebendRunnable)
    }

    /**
     * Pulls every row's edges in to the silhouette at that row's own height.
     * The pane is asymmetric — the left boundary moves with height in the
     * opposite direction to the right one — so each side is measured from its
     * own curve. Rows that sit outside the panel's vertical span (deep inside a
     * scrolling list) are simply left alone.
     */
    private fun bendRows() {
        var shifted = false
        if (hosts.isEmpty()) {
            shifted = bendHostChildren(this)
        } else {
            for (i in hosts.indices) shifted = bendHostChildren(hosts[i]) || shifted
        }
        // One extra pass to adopt the new margins; the pass after that finds
        // nothing left to change. The cap is belt-and-braces against a view
        // whose own layout keeps moving underneath us (a scrolling list).
        if (!shifted || passes >= PASS_LIMIT) return
        // Inside a layout pass the margins are picked up by the traversal that
        // is already scheduled, so scheduling another one from here would fight
        // it (and, mid-scroll, never settle).
        if (inLayout) return
        passes++
        if (isAttachedToWindow) postOnAnimation { bendRows() } else requestLayout()
    }

    /** Bends the visible children of one row container. Returns true if any of
     *  them had to move. */
    private fun bendHostChildren(host: ViewGroup): Boolean {
        val left = shapeLeft()
        val top = shapeTop()
        val w = shapeRight() - left
        val h = shapeBottom() - top
        if (w <= 0f || h <= 0f || host.childCount == 0) return false
        var shifted = false
        for (i in 0 until host.childCount) {
            val child = host.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            if (child.width <= 0 || child.height <= 0) {
                // A row that is VISIBLE but has no size is exactly what an empty
                // band in the panel looks like: it holds its line open but can
                // paint nothing. Put its margins back so the next layout gives
                // it a size again, and say so once — silence here is what let a
                // squeezed row sit in the list looking like a gap.
                if (host.isLaidOut && host.width > 0 && sizedOnce.containsKey(child) &&
                    collapsedLogged.put(child, true) == null
                ) {
                    Logs.log(
                        "Panel",
                        "row $i (${child.javaClass.simpleName}) lost its size " +
                            "(${child.width}x${child.height}) — margins reset"
                    )
                    putBackBaseMargins(child)
                }
                continue
            }
            sizedOnce[child] = true
            // offsetDescendantRectToMyCoords ADDS the descendant's own offset to
            // the rect it is handed, so the rect has to be reseeded with the
            // child's bounds every single time. Reusing it across iterations
            // (as this used to) compounds row 1's offset into row 2, row 2's into
            // row 3 and so on, so only the first row of each container ended up
            // on the curve — every later row kept the container's full width and
            // was sliced off by the bowed left and right edges, which is exactly
            // the "the curve is cutting the text" the panel kept showing.
            rect.set(0, 0, child.width, child.height)
            offsetDescendantRectToMyCoords(child, rect)
            if (rect.bottom <= top || rect.top >= top + h) continue
            val lp = child.layoutParams as? ViewGroup.MarginLayoutParams ?: continue
            val base = baseMargins.getOrPut(child) { intArrayOf(lp.leftMargin, lp.rightMargin) }
            // Where this row sits with the bend taken back off. Margins are
            // always derived from this reference, never from the margins the
            // previous pass applied: a row's width is what its inset is measured
            // against, so counting from the last result let each pass shrink the
            // next one, and a row could be pulled in until nothing was left of
            // it. From the base, the same layout always yields the same margins.
            val baseLeft = rect.left - (lp.leftMargin - base[0])
            val baseRight = rect.right + (lp.rightMargin - base[1])
            val baseWidth = baseRight - baseLeft
            if (baseWidth <= 0) continue
            // A block that spans the panel's full height IS the scrolling
            // viewport: its own extremes sit where the sides sweep back to
            // their corners, so measuring them there would clamp the whole list
            // to the corner width. Evaluate the curve a little inside.
            val yTop = if (rect.top <= top) top + h * 0.06f else rect.top.toFloat()
            val yBottom = if (rect.bottom >= top + h) top + h * 0.94f else rect.bottom.toFloat()
            // The row has to clear the curve at EVERY height it covers, but only
            // where its BACKGROUND actually reaches. A row is a stadium (a
            // 999px corner radius clamps to half its height), so at a distance
            // |off| from the row's own middle its edge is already inset by
            // r - sqrt(r^2 - off^2). Charging that inset against the curve is
            // what lets the rows FOLLOW the bend: each row is pushed in only as
            // far as its visible background needs, so the top and bottom rows
            // sit out wide near the glass's narrow ends while the middle ones
            // ride the bow. Asking instead for the plain worst case — every row
            // clamped to the deepest point of the curve inside its own height,
            // which for a 51dp row is very nearly the panel's mid-height —
            // flattened the whole stack into one uniform column, i.e. a
            // rectangle floating inside a warped panel.
            // The radius is taken from the row's UNBENT width: its bent width is
            // an effect of the bend, so feeding it back in made the inset (and
            // so the next width) a function of itself.
            val radius = min(child.height.toFloat(), baseWidth.toFloat()) / 2f
            val centerY = (rect.top + rect.bottom) / 2f
            var needLeft = 0f
            var needRight = 0f
            for (k in 0..8) {
                val y = yTop + (yBottom - yTop) * (k / 8f)
                val off = abs(y - centerY)
                val inset = if (radius <= 0f) {
                    0f
                } else if (off >= radius) {
                    radius
                } else {
                    radius - sqrt(radius * radius - off * off)
                }
                val dy = y - top
                needLeft = max(needLeft, GlassShape.leftEdge(w, h, dy, bulgeX, concaveX) - inset)
                needRight = max(needRight, w - GlassShape.rightEdge(w, h, dy, bulgeX, concaveX) - inset)
            }
            // The two curves are shallow (a few px at the ends, a couple of dp
            // mid-panel), so a need beyond a fifth of the row means the rect the
            // curve was sampled against is stale — a row mid-layout, or a rect
            // measured during a scroll. Cap it rather than let a bad frame pull
            // a row out of existence.
            val cap = baseWidth * 0.22f
            needLeft = needLeft.coerceAtMost(cap)
            needRight = needRight.coerceAtMost(cap)
            val targetLeft = (baseLeft + needLeft + rowGapPx).toInt()
            val targetRight = (baseRight - needRight - rowGapPx).toInt()
            if (targetRight <= targetLeft) continue
            var leftMargin: Int
            var rightMargin: Int
            if (lp.width == ViewGroup.LayoutParams.MATCH_PARENT) {
                // Stretchable: move both edges onto the target.
                leftMargin = base[0] + (targetLeft - baseLeft)
                rightMargin = base[1] + (baseRight - targetRight)
            } else {
                // Fixed-width (a spinner, a badge): only ever nudge it back
                // inside, never restretch it.
                leftMargin = base[0] + max(0, targetLeft - baseLeft)
                rightMargin = base[1] + max(0, baseRight - targetRight)
            }
            leftMargin = leftMargin.coerceAtLeast(0)
            rightMargin = rightMargin.coerceAtLeast(0)
            if (leftMargin != lp.leftMargin || rightMargin != lp.rightMargin) {
                lp.leftMargin = leftMargin
                lp.rightMargin = rightMargin
                child.layoutParams = lp
                shifted = true
            }
        }
        return shifted
    }

    /** Puts a row's margins back to the ones it was laid out with. */
    private fun putBackBaseMargins(child: View) {
        val base = baseMargins[child] ?: return
        val lp = child.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (lp.leftMargin == base[0] && lp.rightMargin == base[1]) return
        lp.leftMargin = base[0]
        lp.rightMargin = base[1]
        child.layoutParams = lp
    }

    private fun withAlpha(color: Int, fraction: Float): Int {
        val a = (Color.alpha(color) * fraction).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    private companion object {
        // Widest stroke is 44dp: half of it (22dp) has to fit inside haloPx, so
        // callers reserve at least that much of a halo (see glassHaloPx).
        val STROKE_DP = floatArrayOf(44f, 32f, 22f, 15f, 10f, 6f, 3.5f)
        val ALPHA = floatArrayOf(0.09f, 0.14f, 0.22f, 0.35f, 0.53f, 0.75f, 0.93f)
        const val CORE_STROKE_DP = 1.6f
        const val CORE_ALPHA = 0.9f
        /** How many extra layout passes a bend is allowed to ask for before it
         *  gives up (each pass should settle, this only bounds a view whose own
         *  layout keeps moving underneath us). */
        const val PASS_LIMIT = 4
        val CORE_TOP = Color.rgb(215, 248, 255)
        val CORE_MID = Color.rgb(205, 195, 255)
        val CORE_BOTTOM = Color.rgb(245, 205, 255)
    }
}
