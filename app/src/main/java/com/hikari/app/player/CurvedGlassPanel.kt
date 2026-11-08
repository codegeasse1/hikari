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

class CurvedGlassPanel(context: Context) : LinearLayout(context) {

    private val hosts = ArrayList<ViewGroup>()

    /** Containers whose children are bent only when they are NOT part of a
     *  registered host — see [bendLoose]. */
    private val looseHosts = ArrayList<ViewGroup>()

    /** Registers [container] as a row container: its children bend to the curve. */
    fun bendHost(container: ViewGroup) {
        if (container !== this && !hosts.contains(container)) hosts.add(container)
    }

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

    var rowGapPx: Float = 13f * resources.displayMetrics.density

    var flatSideGapPx: Float = 8f * resources.displayMetrics.density

    var flatTopGapPx: Float = 8f * resources.displayMetrics.density

    /** Accent the glow is tinted with, top to bottom. */
    var startColor: Int = Color.rgb(120, 220, 255)
    var midColor: Int = Color.rgb(150, 140, 255)
    var endColor: Int = Color.rgb(240, 160, 255)

    fun applySkin(key: String) {
        val density = resources.displayMetrics.density
        when (PlayerSkins.normalize(key)) {
            PlayerSkins.MINIMAL -> {
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
            PlayerSkins.TV -> {
                // TV Player is a calm elevated sheet: enough contrast to read
                // from the sofa, but no border/curve/glow fighting the focus state.
                flat = true
                flatCornerPx = 18f * density
                flatFill = 0xEB15171D.toInt()
                flatBorder = 0x18FFFFFF
                flatBorderPx = 0.8f * density
                flatSideGapPx = 18f * density
                flatTopGapPx = 14f * density
            }
            else -> {
                flat = false
            }
        }
        requestLayout()
        invalidate()
        // Same size, new outline: rebuild it now instead of waiting for a
        // resize that may never come (see [rebuildShape]).
        if (width > 0 && height > 0) rebuildShape(width, height)
    }

    private val shapePath = Path()
    private val sidesPath = Path()
    /** The size [shapePath]/[sidesPath] were last built for (see [rebuildShape]). */
    private var shapeW = -1
    private var shapeH = -1
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
        rebuildShape(w, h)
    }

    private fun rebuildShape(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        shapeW = w
        shapeH = h
        passes = 0
        val left = shapeLeft()
        val top = shapeTop()
        val right = shapeRight()
        val bottom = shapeBottom()
        if (flat) {
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
            if (flatBorderPx > 0f) {
                rimPaint.strokeWidth = flatBorderPx
                rimPaint.color = flatBorder
                canvas.drawPath(shapePath, rimPaint)
            }
            return
        }
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
        // The clip must match the size on screen, not the size of the last
        // size change (see [rebuildShape]).
        if ((width != shapeW || height != shapeH) && width > 0 && height > 0) {
            rebuildShape(width, height)
        }
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

    fun rebend() {
        if (rebendPosted || !isAttachedToWindow) return
        rebendPosted = true
        postOnAnimation(rebendRunnable)
    }

    private fun bendRows() {
        if (flat) return
        var shifted = false
        if (hosts.isEmpty()) {
            shifted = bendHostChildren(this)
        } else {
            for (i in hosts.indices) shifted = bendHostChildren(hosts[i]) || shifted
        }
        for (i in looseHosts.indices) {
            shifted = bendHostChildren(looseHosts[i], skipHosts = true) || shifted
        }
        // One extra pass to adopt the new margins; the pass after that finds
        // nothing left to change. The cap is belt-and-braces against a view
        // whose own layout keeps moving underneath us (a scrolling list).
        if (!shifted || passes >= PASS_LIMIT) return
        if (inLayout) return
        passes++
        if (isAttachedToWindow) postOnAnimation { bendRows() } else requestLayout()
    }

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
            rect.set(0, 0, child.width, child.height)
            offsetDescendantRectToMyCoords(child, rect)
            if (rowOffsetX != 0) rect.offset(rowOffsetX, 0)
            if (rect.bottom <= top || rect.top >= top + h) continue
            val lp = child.layoutParams as? ViewGroup.MarginLayoutParams ?: continue
            val base = baseMargins.getOrPut(child) { intArrayOf(lp.leftMargin, lp.rightMargin) }
            val baseLeft = rect.left - (lp.leftMargin - base[0])
            val baseRight = rect.right + (lp.rightMargin - base[1])
            val baseWidth = baseRight - baseLeft
            if (baseWidth <= 0) continue
            val yTop = if (rect.top <= top) top + h * 0.06f else rect.top.toFloat()
            val yBottom = if (rect.bottom >= top + h) top + h * 0.94f else rect.bottom.toFloat()
            val radius = min(child.height.toFloat(), baseWidth.toFloat()) / 2f
            val centerY = (rect.top + rect.bottom) / 2f
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
                val boundLeft = left + GlassShape.leftEdge(w, h, dy, bulgeX, concaveX)
                val boundRight = left + GlassShape.rightEdge(w, h, dy, bulgeX, concaveX)
                needLeft = max(needLeft, boundLeft - baseLeft - min(inset, allowLeft))
                needRight = max(needRight, baseRight - boundRight - min(inset, allowRight))
            }
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
