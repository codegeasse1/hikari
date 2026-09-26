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
import android.view.ViewParent
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
 *    stack sitting inside a curved glass. [bendLoose] registers a container
 *    whose OTHER children — a message line above the list, say — should follow
 *    the bend too, instead of being laid out at the panel's full width and then
 *    sliced by the bowed edge.
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

    /** Containers whose children are bent only when they are NOT part of a
     *  registered host — see [bendLoose]. */
    private val looseHosts = ArrayList<ViewGroup>()

    /** Registers [container] as a row container: its children bend to the curve. */
    fun bendHost(container: ViewGroup) {
        if (container !== this && !hosts.contains(container)) hosts.add(container)
    }

    /**
     * Registers a container whose children are bent UNLESS they are a registered
     * host, or hold one deeper inside.
     *
     * A dialog's content container is the usual one: it may carry rows that were
     * handed over with [bendHost] AND loose children of its own — a message line
     * above the list, say. Those loose children used to be laid out at the
     * panel's full inner width and then sliced by the bowed edge (the download
     * sheet's "Episode 683 · …" line lost its first four letters to the left
     * curve and wrapped the rest of the way round), because only the registered
     * hosts were ever bent. Registering the container here bends everything it
     * holds that [bendHost] does not already cover, without bending a host's
     * rows twice.
     */
    fun bendLoose(container: ViewGroup) {
        if (container === this) return
        // A container that is ALSO a row host is already fully bent, child by
        // child — registering it here would just bend the same children twice.
        if (hosts.contains(container)) return
        if (!looseHosts.contains(container)) looseHosts.add(container)
    }

    /** True when [child] is a registered host, or holds one further down the
     *  tree — i.e. when bending it here would move a host's rows as a block on
     *  top of the per-row bends they already get. */
    private fun holdsHost(child: View): Boolean {
        for (i in hosts.indices) {
            var p: ViewParent? = hosts[i]
            while (p != null) {
                if (p === child) return true
                p = (p as? View)?.parent
            }
        }
        return false
    }

    /** How far the right edge bulges outward — see [GlassShape.BULGE_X]. */
    var bulgeX: Float = GlassShape.BULGE_X

    /** How far the left edge bows inward — see [GlassShape.CONCAVE_X]. */
    var concaveX: Float = GlassShape.CONCAVE_X

    /** Room inside this view's bounds for the glow to fade into, in px. */
    var haloPx: Float = 0f

    // ---- Player UI skin (Settings -> Player -> Player UI) -------------------
    //
    // The curved neon pane is the DEFAULT skin's signature — it is what Hikari
    // shipped with, and it stays exactly as it was. The other three skins wear
    // their OWN flat shape
    // instead — a flat, quiet slab (Minimal), a solid deck with a hairline
    // (Cinema) or a floating rounded card with an accent edge (Neon) — because
    // a dialog that keeps the bowed glass while the control bar under it is
    // square reads as two different apps. The panel is the same view either
    // way: only its outline, fill and edge treatment change, so no dialog has
    // to know which skin is on.

    /** True for every skin but DEFAULT: a plain rounded rectangle instead of
     *  the bowed pane, with no neon along its sides. */
    var flat: Boolean = false

    /** Corner radius of the [flat] silhouette, in px. */
    var flatCornerPx: Float = 16f * resources.displayMetrics.density

    /** Fill and hairline colours of the [flat] silhouette. The border is drawn
     *  in [flatBorder] at [flatBorderPx]; both are set by [applySkin]. */
    var flatFill: Int = 0xF20A0C12.toInt()
    var flatBorder: Int = 0x26FFFFFF
    var flatBorderPx: Float = resources.displayMetrics.density

    /** Air kept between a row and the silhouette's edge, at the widest point.
     *  13dp rather than a hairline: the panel's edge carries a bright rim and
     *  core line, and text sitting a couple of dp off it reads as sliced by the
     *  curve even when it is not — which is exactly how the section headers
     *  ("HIKARI · 5", "ANIME4I · 2") were being reported. */
    var rowGapPx: Float = 13f * resources.displayMetrics.density

    /** Air a FLAT panel keeps between its own left/right edge and the content.
     *
     *  The curved pane has no use for this: its rows are bent to the silhouette
     *  at their own height, so their envelope IS the shape. A flat panel has
     *  straight sides, so its padding is the only inset there is — and with
     *  none, a row (a pill, whose background reaches its own edges) sat flush
     *  against the panel's rounded edge and the corner arc cut into it: the
     *  reported "the server roundy ui is fully attached to the box" plus "the
     *  All chip's box is cut by the main box's round corner". 8dp of air is
     *  enough to read as a deliberate margin without wasting row width — see
     *  [flatTopGapPx] for the extra the corner arc needs. */
    var flatSideGapPx: Float = 8f * resources.displayMetrics.density

    /** How far below its own top edge a FLAT panel starts its content. The
     *  corner arc intrudes furthest exactly at the top, so the content's first
     *  line must start below it by at least as much as the arc has come in at
     *  that depth — [onSizeChanged] derives the horizontal inset from this
     *  figure and the corner radius, so a chip strip at the top of the panel
     *  can never be sliced by the corner. */
    var flatTopGapPx: Float = 8f * resources.displayMetrics.density

    /** Accent the glow is tinted with, top to bottom. */
    var startColor: Int = Color.rgb(120, 220, 255)
    var midColor: Int = Color.rgb(150, 140, 255)
    var endColor: Int = Color.rgb(240, 160, 255)

    /**
     * Wears the Player UI skin [key] (see [PlayerSkins]).
     *
     * Default keeps the curved pane and its neon edge — this view was built for
     * it and nothing here changes it. The other three skins get the flat
     * silhouette plus their own fill and hairline, sized to match the control
     * bar they appear over: Minimal is a barely-there slab with a small radius,
     * Cinema a solid deck with a squarer corner and a plain hairline, Neon a
     * fully rounded floating card edged in the accent colour.
     */
    fun applySkin(key: String) {
        val density = resources.displayMetrics.density
        when (PlayerSkins.normalize(key)) {
            PlayerSkins.MINIMAL -> {
                // Barely there: a flat slab with NO edge line at all, so the
                // rows read as if they were floating on the picture (which is
                // what Minimal's control bar does too).
                flat = true
                flatCornerPx = 10f * density
                flatFill = 0xEE0A0B10.toInt()
                flatBorder = 0x00000000
                flatBorderPx = 0f
            }
            PlayerSkins.CINEMA -> {
                // A deck: squarer corners and a hard, slightly brighter edge —
                // "equipment", like the plates it sits over.
                flat = true
                flatCornerPx = 6f * density
                flatFill = 0xF20A0C12.toInt()
                flatBorder = 0x33FFFFFF
                flatBorderPx = 1.4f * density
            }
            PlayerSkins.NEON -> {
                // The late-night card: fully rounded, edged in the accent.
                flat = true
                flatCornerPx = 26f * density
                flatFill = 0xD90A0D16.toInt()
                flatBorder = withAlpha(midColor, 0.75f)
                flatBorderPx = 1.4f * density
            }
            else -> {
                flat = false
            }
        }
        requestLayout()
        invalidate()
    }

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

    /** How far the content has been scrolled SIDEWAYS inside the panel, in px. */
    private var rowOffsetX = 0

    /**
     * Tells the panel that its content has been dragged sideways by [x] px, and
     * re-bends.
     *
     * A row's position is read in the PANEL's own coordinates, so a sideways drag
     * moves every row's edges out from under the panel — and the bend, which
     * exists to keep a row's visible content clear of the bowed glass, would read
     * that as a row hanging over the left edge and pull it back in by its own
     * cap. Every row of a user-scrolled strip would shrink as they dragged it.
     * Subtracting the offset before the maths puts the row back where it was laid
     * out, so the shape of the stack does not change when the strip is scrolled.
     */
    fun setRowOffsetX(x: Int) {
        if (x == rowOffsetX) return
        rowOffsetX = x
        rebend()
    }

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
        if (flat) {
            // A plain rounded rectangle: the same box the curved pane occupies,
            // with the skin's own corner radius. sidesPath is left empty so the
            // glow loops in onDraw have nothing to stroke (see [applySkin]).
            val radius = flatCornerPx.coerceAtMost((bottom - top) / 2f)
                .coerceAtMost((right - left) / 2f)
            shapePath.reset()
            shapePath.addRoundRect(left, top, right, bottom, radius, radius, Path.Direction.CW)
            sidesPath.reset()
        } else {
            GlassShape.build(shapePath, left, top, right, bottom, bulgeX, concaveX)
            GlassShape.buildSides(sidesPath, left, top, right, bottom, bulgeX, concaveX)
        }
        val density = resources.displayMetrics.density
        // A flat panel is a solid slab in the skin's own colour (see
        // [applySkin]); the curved pane keeps the glass gradient it always had.
        fillPaint.shader = if (flat) null else LinearGradient(
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
        //
        // A FLAT panel is the opposite case: nothing bends, so its padding IS
        // the inset, and it has to cover a rounded CORNER as well as a straight
        // side. The corner arc reaches furthest in at the very top, so the
        // horizontal inset is derived from how far the arc has intruded at the
        // depth the first row starts at (flatTopGapPx): arc = r - sqrt(r^2 -
        // (r - d)^2) for d below the top edge. At 8dp down, an 18dp corner has
        // only come in ~3dp, but a 26dp one has come in ~6dp — so a single
        // constant would look right for one skin and slice the "All" chip on
        // another. Solving it from the actual radius is what makes every flat
        // skin keep exactly flatSideGapPx of visible air around its content.
        val padH: Int
        val padV: Int
        if (flat) {
            val d = flatTopGapPx
            val r = flatCornerPx
            val arc = if (d < r) r - sqrt((r * r) - ((r - d) * (r - d))) else 0f
            padH = (haloPx + flatSideGapPx + arc).toInt()
            padV = (haloPx + d).toInt()
        } else {
            padH = 0
            padV = (haloPx + rowGapPx).toInt()
        }
        if (paddingTop != padV || paddingBottom != padV ||
            paddingLeft != padH || paddingRight != padH
        ) {
            setPadding(padH, padV, padH, padV)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (flat) {
            // The flat skins' slab: a solid fill with a hairline edge, and no
            // neon — the glow belongs to the Glass pane (see [applySkin]).
            fillPaint.color = flatFill
            canvas.drawPath(shapePath, fillPaint)
            // Minimal asks for no edge at all (see applySkin): a stroked path
            // with width 0 is a hairline in Skia, not "nothing", so it is
            // skipped outright instead.
            if (flatBorderPx > 0f) {
                rimPaint.strokeWidth = flatBorderPx
                rimPaint.color = flatBorder
                canvas.drawPath(shapePath, rimPaint)
            }
            return
        }
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
        // A flat panel (every skin but Glass) has straight sides, so there is no
        // curve for a row to follow: rows keep the margins they were built with
        // and the panel's own padding is the only inset. Bending them here would
        // add the row gap on top of that for nothing.
        if (flat) return
        var shifted = false
        if (hosts.isEmpty()) {
            shifted = bendHostChildren(this)
        } else {
            for (i in hosts.indices) shifted = bendHostChildren(hosts[i]) || shifted
        }
        // Loose children of a container (see [bendLoose]) — text that sits in
        // the panel but was never handed over as a row host, e.g. a dialog's
        // message line. Bent AFTER the hosts so a child that holds one is
        // recognised and skipped.
        for (i in looseHosts.indices) {
            shifted = bendHostChildren(looseHosts[i], skipHosts = true) || shifted
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
     *  them had to move. [skipHosts] is set for a "loose" container (see
     *  [bendLoose]): a child that is a registered host — or that holds one
     *  deeper inside — is left alone, because that host's rows are already bent
     *  individually and moving the container as a block would shift them twice.
     */
    private fun bendHostChildren(host: ViewGroup, skipHosts: Boolean = false): Boolean {
        val left = shapeLeft()
        val top = shapeTop()
        val w = shapeRight() - left
        val h = shapeBottom() - top
        if (w <= 0f || h <= 0f || host.childCount == 0) return false
        var shifted = false
        for (i in 0 until host.childCount) {
            val child = host.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            if (skipHosts && holdsHost(child)) continue
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
            // `offsetDescendantRectToMyCoords` also carries the content's own
            // sideways scroll (see [setRowOffsetX]): undoing it here keeps a row
            // on the curve it was laid out against, whatever the strip has been
            // dragged to.
            if (rowOffsetX != 0) rect.offset(rowOffsetX, 0)
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
            // How far the row's BOX may still overhang the silhouette. A pill's
            // empty rounded cap may cross the edge — that is what lets the rows
            // ride the bow instead of floating inside a warped rectangle — but
            // only as far as the row's own content padding reaches, minus half
            // the row gap, so the CONTENT (the label, the sub-line, a section
            // header's first letter) always stays clear of the glass. A child
            // with no padding of its own on a side (a section header, the chip
            // strip, a dialog's message line) therefore gets NO overhang at all:
            // it was exactly that allowance — credited to rows that have no
            // rounded background to spend it on — that left the first letter of
            // every "HIKARI · n" / "ANIME4I · n" header sitting on the bowed
            // edge with the bright rim slicing through the glyph.
            val allowLeft = max(0f, child.paddingLeft.toFloat() - rowGapPx * 0.5f)
            val allowRight = max(0f, child.paddingRight.toFloat() - rowGapPx * 0.5f)
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
                // The silhouette does NOT start at this view's own edge: the
                // halo (0..shapeLeft() on the left, shapeRight()..width on the
                // right) is where the glow lives, and the glass edge is
                // [left]/[top] plus the curve. Measuring the boundary from the
                // view's edge instead is why the rows — and, with a section
                // header's text sitting flush at its own left edge, every
                // "HIKARI · n" / "NUVIO · n" header — ended up ON the glass:
                // the bowed edge cut the rounded cap off every row and sliced
                // the first letter off every header.
                val boundLeft = left + GlassShape.leftEdge(w, h, dy, bulgeX, concaveX)
                val boundRight = left + GlassShape.rightEdge(w, h, dy, bulgeX, concaveX)
                needLeft = max(needLeft, boundLeft - baseLeft - min(inset, allowLeft))
                needRight = max(needRight, baseRight - boundRight - min(inset, allowRight))
            }
            // With the halo counted in, the need is a real distance inside the
            // panel rather than the couple of dp the bow alone is worth, so the
            // cap is what stops a stale rect (a row mid-layout, or one measured
            // during a scroll) from pulling a row out of existence. Coerced at
            // zero too: a child that already sits inside the glass is left
            // where it is instead of being pushed back out over the edge.
            val cap = baseWidth * 0.22f
            needLeft = needLeft.coerceIn(0f, cap)
            needRight = needRight.coerceIn(0f, cap)
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
