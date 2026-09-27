package com.hikari.app.player

import android.app.Activity
import android.app.Dialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hikari.app.i18n.I18n
import com.hikari.app.tv.TvMode

/**
 * A full HSV colour picker drawn on a glass panel, for the subtitle style
 * settings (text colour, outline colour, background colour).
 *
 * It offers all three of the controls a subjective "make my subtitles look
 * right" decision actually needs — a saturation/value square, a hue strip and
 * an alpha strip — plus a row of one-tap presets and a live caption preview
 * painted exactly the way the player will paint the real captions (fill +
 * outline), so a colour choice is judged against the video, not against a
 * colour wheel. The platform's own pickers (`ColorPickerDialog` in the
 * framework) do not exist below API 33 and are not themeable, which is why
 * this is drawn in-app.
 *
 * The panel matches the player's other dialogs (dark glass, accent stroke);
 * it is deliberately self-contained — the player's glass helpers are private
 * to [PlayerActivity], so the pieces this needs are local to this file.
 *
 * EVERY CHANGE IS APPLIED AS IT IS MADE. This picker holds no draft: a drag on
 * the square, a new hue, a preset tap all publish their colour to the caller
 * immediately, so the real captions on the video turn green as the finger moves
 * — the same contract the caption panel itself keeps. The ✕ and Apply simply
 * put the picker away (the colour is already applied), and Cancel hands back
 * the colour the picker was opened with. An earlier version kept the pick in a
 * local draft and only applied it on an Apply button, so choosing a colour and
 * closing with the ✕ discarded it — which is the "I move it to green but when I
 * click the cross it stays white" report, true of the text, the edge and the
 * background colour alike.
 */
class ColorPickerDialog(
    private val host: Activity,
    private val accent: Int,
) {
    private val density = host.resources.displayMetrics.density

    private fun dp(v: Float): Int = (v * density + 0.5f).toInt()

    /**
     * Shows the picker. [allowAlpha] adds the transparency strip (the captions'
     * background colour needs it — "remove the background" IS alpha 0 — while a
     * text/outline colour is always wanted at full opacity).
     */
    fun show(title: String, initial: Int, allowAlpha: Boolean, onPick: (Int) -> Unit) {
        val dialog = Dialog(host, android.R.style.Theme_Translucent_NoTitleBar)

        val hsv = FloatArray(3)
        Color.colorToHSV(initial, hsv)
        var hue = hsv[0]
        var sat = hsv[1]
        var value = hsv[2]
        var alpha = if (allowAlpha) Color.alpha(initial) else 255

        fun current(): Int {
            val rgb = Color.HSVToColor(floatArrayOf(hue, sat, value))
            return if (allowAlpha) (rgb and 0x00FFFFFF) or (alpha shl 24) else rgb
        }

        val panel = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20f).toFloat()
                setColor(0xF0141822.toInt())
                setStroke(dp(1f).coerceAtLeast(1), withAlpha(accent, 0.55f))
            }
        }

        // ---- header: title + close -----------------------------------------
        panel.addView(LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(host).apply {
                text = title
                textSize = 13f
                includeFontPadding = false
                setTextColor(0xFFE9EEF7.toInt())
                setTypeface(Typeface.DEFAULT_BOLD)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(host).apply {
                text = "\u2715"
                textSize = 14f
                includeFontPadding = false
                gravity = Gravity.CENTER
                setTextColor(0xE6FFFFFF.toInt())
                setPadding(dp(6f), dp(2f), dp(6f), dp(2f))
                setOnClickListener { dialog.dismiss() }
            })
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10f) })

        // ---- live caption preview ------------------------------------------
        val preview = CaptionPreview(host).apply {
            text = I18n.t("Sample caption")
            previewTypeface = Typeface.DEFAULT
        }
        panel.addView(preview, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(56f)
        ).apply { bottomMargin = dp(10f) })

        val readout = TextView(host).apply {
            textSize = 10.5f
            includeFontPadding = false
            setTextColor(0xFF9AA5B5.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(6f), 0, 0)
        }

        /** The last colour handed to [onPick], so a drag does not republish a
         *  value the caller already holds. */
        var published = initial
        /** Whether the user has changed anything — what tells Cancel's "put it
         *  back" apart from "this was opened and closed again". */
        var touched = false

        /**
         * Repaints the picker's own preview and read-out, AND publishes the
         * colour to the caller.
         *
         * Every change is published the moment it is made — the same contract
         * the caption panel itself keeps ("every change is applied to the
         * player's SubtitleView the moment it is made, so the captions on the
         * video ARE the preview"). Drag the square to green and the real
         * subtitles turn green as the finger moves.
         *
         * That is also what makes the ✕ honest. This picker used to hold the
         * colour in a local draft and hand it over only when the user found the
         * Apply button, so choosing green and closing with the ✕ — the obvious
         * "I'm done", and on a remote the only way out at all — threw the colour
         * away and the swatch behind still read white. Reported exactly that way
         * for the text, the edge and the background colour alike.
         */
        fun publish() {
            val c = current()
            preview.previewColor = c
            readout.text = hexOf(c)
            if (c != published) {
                published = c
                touched = true
                onPick(c)
            }
        }

        // ---- colour controls ------------------------------------------------
        // The views are made first and wired afterwards: the square and the hue
        // strip each need to repaint the other, so neither can be built inside
        // the other's initialiser. Both callbacks only ever run once the user
        // touches them, long after both exist.
        lateinit var sv: SvSquare
        lateinit var huePicker: HueStrip
        var alphaStrip: AlphaStrip? = null

        /** Repaints everything that depends on the current HSV + alpha, and
         *  updates the preview and read-out. */
        fun syncFromHsv() {
            val rgb = Color.HSVToColor(floatArrayOf(hue, sat, value))
            sv.set(hue, sat, value)
            huePicker.markerHue = hue
            alphaStrip?.let {
                it.baseColor = rgb
                it.invalidate()
            }
            publish()
        }

        sv = SvSquare(host).apply {
            set(hue, sat, value)
            onChange = { s, v ->
                sat = s
                value = v
                syncFromHsv()
            }
        }
        panel.addView(sv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(132f)
        ))

        huePicker = HueStrip(host).apply {
            markerHue = hue
            onChange = { h ->
                hue = h
                syncFromHsv()
            }
        }
        panel.addView(huePicker, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(22f)
        ).apply { topMargin = dp(10f) })

        if (allowAlpha) {
            val strip = AlphaStrip(host).apply {
                baseColor = Color.HSVToColor(floatArrayOf(hue, sat, value))
                markerAlpha = alpha
            }
            strip.onChange = { a ->
                alpha = a
                strip.markerAlpha = a
                publish()
            }
            alphaStrip = strip
            panel.addView(strip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(22f)
            ).apply { topMargin = dp(8f) })
        }

        // ---- presets --------------------------------------------------------
        panel.addView(LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10f), 0, 0)
            addView(swatchStrip(current()) { picked ->
                val c = if (allowAlpha) {
                    (picked and 0x00FFFFFF) or (alpha shl 24)
                } else picked
                val h = FloatArray(3)
                Color.colorToHSV(c, h)
                hue = h[0]; sat = h[1]; value = h[2]
                alpha = Color.alpha(c)
                syncFromHsv()
                alphaStrip?.let { it.markerAlpha = alpha; it.invalidate() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        panel.addView(readout, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // ---- actions --------------------------------------------------------
        // The colour is ALREADY applied (see [publish]): the ✕ and Apply just
        // put the picker away, and Cancel is a real undo — it hands back the
        // colour the picker was opened with. A Cancel that dismissed without
        // restoring would leave the video wearing a colour the user just told
        // the app to forget, which is the one thing a live picker must not do.
        panel.addView(LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10f), 0, 0)
            addView(button(I18n.t("Cancel"), filled = false) {
                if (touched) onPick(initial)
                dialog.dismiss()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(button(I18n.t("Apply"), filled = true) {
                dialog.dismiss()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8f)
            })
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        publish()

        // The picker is a TALL dialog — a preview, the saturation/value square,
        // two strips, the preset chips and two buttons come to roughly 390dp —
        // and the landscape player's window is only ~393dp tall (a 1080p phone
        // in landscape). On a window marginally shorter than that, the dialog's
        // bottom (the buttons) sat past the screen edge with no way to reach it:
        // nothing scrolled, and the window cannot be bigger than the screen. The
        // panel is measured first and its window is capped against the window it
        // is in, with a scroller around it, so every part of the picker is
        // reachable however short the screen is.
        val scroller = ScrollView(host).apply {
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(panel, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        dialog.setContentView(scroller, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        val decor = host.window?.decorView
        val winW = (decor?.width ?: 0).takeIf { it > 0 } ?: dp(400f)
        val winH = (decor?.height ?: 0).takeIf { it > 0 } ?: dp(720f)
        val w = minOf((winW * 0.86f).toInt(), dp(360f))
        panel.measure(
            View.MeasureSpec.makeMeasureSpec((w - dp(28f)).coerceAtLeast(dp(120f)), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val h = panel.measuredHeight.coerceAtMost(winH - dp(24f))
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(w, h)
            setGravity(Gravity.CENTER)
            setDimAmount(0.55f)
        }
        if (runCatching { dialog.show() }.isFailure) return
        if (TvMode.isTv) {
            makeTvReachable(panel)
            findFirstFocusable(panel)?.requestFocus()
        }
    }

    /**
     * Makes every clickable-but-not-focusable view under [root] reachable with a
     * D-pad, and gives each of them a focus ring.
     *
     * The same treatment the player's own panels get (see
     * `PlayerActivity.View.tvFocusableTree`): `setOnClickListener` makes a view
     * clickable and leaves it unfocusable, and a D-pad can only land on a
     * focusable view — so without this the picker's preset chips, its ✕ and its
     * two buttons could not be reached by a remote at all. The three custom
     * surfaces (the square, the hue strip, the alpha strip) are not clickable —
     * a tap on them is a drag — so they are focused explicitly instead.
     */
    private fun makeTvReachable(root: View) {
        val custom = root is SvSquare || root is HueStrip || root is AlphaStrip
        if ((custom || root.isClickable) && !root.isFocusable && root.visibility == View.VISIBLE) {
            root.isFocusable = true
            root.isFocusableInTouchMode = true
            if (root.foreground == null) root.foreground = focusRing()
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) makeTvReachable(root.getChildAt(i))
        }
    }

    /** The ring a focused control wears on a television, as a focus-state-only
     *  foreground (see [makeTvReachable]). */
    private fun focusRing(): Drawable {
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(9f).toFloat()
            setColor(Color.TRANSPARENT)
            setStroke(dp(2f).coerceAtLeast(1), accent)
        }
        // Inset by the stroke's own outer half so all of it lands inside the
        // control: an un-inset stroke is centred on the edge and its outer half
        // is painted away by whatever clips the view.
        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_focused),
                InsetDrawable(ring, dp(1f).coerceAtLeast(1)),
            )
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }
    }

    /** The first control a D-pad would land on inside [root], so a picker that
     *  opens on a television is already usable (and already shows where the
     *  cursor is) before the first key press. */
    private fun findFirstFocusable(root: View?): View? {
        if (root == null) return null
        if (root.isShown && root.visibility == View.VISIBLE && root.isFocusable) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findFirstFocusable(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    /** "#RRGGBB" / "#AARRGGBB" — the picker's honest readout of what was picked. */
    private fun hexOf(color: Int): String =
        if (Color.alpha(color) == 255) String.format("#%06X", color and 0xFFFFFF)
        else String.format("#%02X%06X", Color.alpha(color), color and 0xFFFFFF)

    private fun withAlpha(color: Int, fraction: Float): Int =
        (color and 0x00FFFFFF) or ((fraction.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255) shl 24)

    private fun button(label: String, filled: Boolean, onClick: () -> Unit): TextView =
        TextView(host).apply {
            text = label
            textSize = 12f
            includeFontPadding = false
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(6f), dp(9f), dp(6f), dp(9f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999f
                if (filled) {
                    setColors(intArrayOf(withAlpha(accent, 0.95f), withAlpha(accent, 0.72f)))
                } else {
                    setColor(0x1AFFFFFF.toInt())
                    setStroke(dp(1f).coerceAtLeast(1), 0x33FFFFFF)
                }
            }
            setOnClickListener { onClick() }
        }

    /** A horizontally scrolling row of one-tap presets. */
    private fun swatchStrip(selected: Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        PRESETS.forEach { preset ->
            row.addView(Swatch(host, preset, selected, accent).apply {
                setOnClickListener { onPick(preset) }
            }, LinearLayout.LayoutParams(dp(28f), dp(28f)).apply { marginEnd = dp(6f) })
        }
        return HorizontalScrollView(host).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            addView(row)
        }
    }

    private companion object {
        /** White first (the readable default), then the high-contrast colours a
         *  viewer actually reaches for over bright video, then a couple of
         *  accents. */
        val PRESETS = intArrayOf(
            0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFFEB3B.toInt(), 0xFFFFC107.toInt(),
            0xFF00E5FF.toInt(), 0xFF69F0AE.toInt(), 0xFFFF80AB.toInt(), 0xFFFF5252.toInt(),
            0xFFFF9800.toInt(), 0xFF448AFF.toInt(), 0xFFB0BEC5.toInt(), 0xFFE040FB.toInt(),
        )
    }
}

/** One preset colour chip: the colour, a thin light border, a ring when it is
 *  the colour currently picked. */
private class Swatch(
    context: android.content.Context,
    private val color: Int,
    private val selected: Int,
    private val accent: Int,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    init {
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        val inset = if (color == selected) 4f * density else 1.5f * density
        val r = RectF(inset, inset, width - inset, height - inset)
        val radius = r.height() / 2f
        fill.color = color
        canvas.drawRoundRect(r, radius, radius, fill)
        ring.color = if (color == selected) accent else 0x40FFFFFF
        ring.strokeWidth = if (color == selected) 2f * density else 1f * density
        canvas.drawRoundRect(r, radius, radius, ring)
    }
}

/** The saturation (x) / value (y) square for the current hue. */
private class SvSquare(context: android.content.Context) : View(context) {
    var onChange: ((Float, Float) -> Unit)? = null
    var onChanged: (() -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shader: Shader? = null
    var sat = 1f
        private set
    var value = 1f
        private set
    private var hue = 0f

    init {
        // ComposeShader with a MULTIPLY blend is the classic way to build this
        // square, but on a hardware canvas its result has never been reliably
        // identical across devices — and this view is small, so a software
        // layer costs nothing and guarantees the square looks the same
        // everywhere.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun set(hue: Float, sat: Float, value: Float) {
        this.hue = hue
        this.sat = sat.coerceIn(0f, 1f)
        this.value = value.coerceIn(0f, 1f)
        rebuild()
        invalidate()
    }

    private fun rebuild() {
        if (width <= 0 || height <= 0) {
            shader = null
            return
        }
        val hueColor = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        val horizontal = LinearGradient(
            0f, 0f, width.toFloat(), 0f,
            Color.WHITE, hueColor, Shader.TileMode.CLAMP
        )
        val vertical = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP
        )
        shader = ComposeShader(horizontal, vertical, PorterDuff.Mode.MULTIPLY)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rebuild()
    }

    override fun onDraw(canvas: Canvas) {
        val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val radius = 12f * resources.displayMetrics.density
        paint.shader = shader
        canvas.drawRoundRect(r, radius, radius, paint)
        paint.shader = null
        // The cursor: a white ring with a dark inner dot, so it stays visible
        // over both the white and the black corner.
        val cx = sat * width
        val cy = (1f - value) * height
        val d = resources.displayMetrics.density
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * d
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(cx, cy, 7f * d, paint)
        paint.color = 0x66000000
        paint.strokeWidth = 1f * d
        canvas.drawCircle(cx, cy, 8.5f * d, paint)
        paint.style = Paint.Style.FILL
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                sat = (event.x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
                value = (1f - event.y / height.coerceAtLeast(1)).coerceIn(0f, 1f)
                invalidate()
                onChange?.invoke(sat, value)
                onChanged?.invoke()
            }
            MotionEvent.ACTION_UP -> performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /**
     * The D-pad drives the cursor too: left/right move saturation, up/down move
     * value. Without this the picker's two custom surfaces were touch-only, so
     * on a television the only colour a remote could reach was one of the preset
     * chips — the square, the hue strip and the alpha strip were unreachable
     * (and invisible, since nothing drew a focus).
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val step = 0.04f
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> sat = (sat - step).coerceIn(0f, 1f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> sat = (sat + step).coerceIn(0f, 1f)
            KeyEvent.KEYCODE_DPAD_UP -> value = (value + step).coerceIn(0f, 1f)
            KeyEvent.KEYCODE_DPAD_DOWN -> value = (value - step).coerceIn(0f, 1f)
            else -> return super.onKeyDown(keyCode, event)
        }
        invalidate()
        onChange?.invoke(sat, value)
        onChanged?.invoke()
        return true
    }
}

/** The hue strip. */
private class HueStrip(context: android.content.Context) : View(context) {
    var onChange: ((Float) -> Unit)? = null
    var onChanged: (() -> Unit)? = null
    var markerHue: Float = 0f
        set(v) {
            field = v
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shader: Shader? = null

    private val hues = intArrayOf(
        0xFFFF0000.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(),
        0xFF00FFFF.toInt(), 0xFF0000FF.toInt(), 0xFFFF00FF.toInt(), 0xFFFF0000.toInt(),
    )

    private fun rebuild() {
        if (width <= 0) {
            shader = null
            return
        }
        shader = LinearGradient(0f, 0f, width.toFloat(), 0f, hues, null, Shader.TileMode.CLAMP)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rebuild()
    }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val r = RectF(0f, 4f * d, width.toFloat(), height - 4f * d)
        val radius = r.height() / 2f
        paint.shader = shader
        canvas.drawRoundRect(r, radius, radius, paint)
        paint.shader = null
        val cx = (markerHue / 360f) * width
        paint.color = 0xFFFFFFFF.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f * d
        canvas.drawLine(cx, 0f, cx, height.toFloat(), paint)
        paint.style = Paint.Style.FILL
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val f = (event.x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
                markerHue = f * 360f
                onChange?.invoke(markerHue)
                onChanged?.invoke()
            }
            MotionEvent.ACTION_UP -> performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** The D-pad walks the hue (see [SvSquare.onKeyDown]). */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> markerHue = (markerHue - 3f + 360f) % 360f
            KeyEvent.KEYCODE_DPAD_RIGHT -> markerHue = (markerHue + 3f) % 360f
            else -> return super.onKeyDown(keyCode, event)
        }
        onChange?.invoke(markerHue)
        onChanged?.invoke()
        return true
    }
}

/** The transparency strip, drawn over a checkerboard so "invisible" reads as
 *  invisible rather than as black. */
private class AlphaStrip(context: android.content.Context) : View(context) {
    var onChange: ((Int) -> Unit)? = null
    var markerAlpha: Int = 255
    var baseColor: Int = 0xFFFFFFFF.toInt()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val check = Paint()

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val r = RectF(0f, 4f * d, width.toFloat(), height - 4f * d)
        val radius = r.height() / 2f
        canvas.save()
        val clip = android.graphics.Path().apply {
            addRoundRect(r, radius, radius, android.graphics.Path.Direction.CW)
        }
        canvas.clipPath(clip)
        val square = 6f * d
        var y = r.top
        var row = 0
        while (y < r.bottom) {
            var x = r.left
            var col = 0
            while (x < r.right) {
                check.color = if ((row + col) % 2 == 0) 0xFF3A4152.toInt() else 0xFF20242E.toInt()
                canvas.drawRect(x, y, x + square, y + square, check)
                x += square
                col++
            }
            y += square
            row++
        }
        canvas.restore()
        paint.shader = LinearGradient(
            0f, 0f, width.toFloat(), 0f,
            baseColor and 0x00FFFFFF, baseColor, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(r, radius, radius, paint)
        paint.shader = null
        val cx = (markerAlpha / 255f) * width
        paint.color = 0xFFFFFFFF.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f * d
        canvas.drawLine(cx, 0f, cx, height.toFloat(), paint)
        paint.style = Paint.Style.FILL
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val f = (event.x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
                markerAlpha = (f * 255f).toInt().coerceIn(0, 255)
                invalidate()
                onChange?.invoke(markerAlpha)
            }
            MotionEvent.ACTION_UP -> performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** The D-pad walks the transparency (see [SvSquare.onKeyDown]). */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> markerAlpha = (markerAlpha - 8).coerceIn(0, 255)
            KeyEvent.KEYCODE_DPAD_RIGHT -> markerAlpha = (markerAlpha + 8).coerceIn(0, 255)
            else -> return super.onKeyDown(keyCode, event)
        }
        invalidate()
        onChange?.invoke(markerAlpha)
        return true
    }
}

/**
 * The preview caption: the picked text colour with the picked outline colour
 * drawn as a real outline (stROKE then FILL, the same recipe the caption
 * renderer uses), on the picked background, so the choice is judged the way it
 * will actually look over the video.
 */
private class CaptionPreview(context: android.content.Context) : View(context) {
    var text: String = ""
    var previewColor: Int = 0xFFFFFFFF.toInt()
        set(v) {
            field = v
            invalidate()
        }
    var previewTypeface: Typeface = Typeface.DEFAULT
    var outlineColor: Int = 0xFF000000.toInt()
    var outline: Boolean = true
    var captionBackground: Int = 0x00000000

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val backdrop = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        // A stand-in for the video: a soft dark gradient, so the caption is
        // read against something video-like rather than against the panel.
        backdrop.shader = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            0xFF2A2F3C.toInt(), 0xFF11141B.toInt(), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backdrop)
        backdrop.shader = null

        val size = 15f * d
        fill.textSize = size
        stroke.textSize = size
        fill.typeface = previewTypeface
        stroke.typeface = previewTypeface
        val textWidth = fill.measureText(text)
        val cx = (width - textWidth) / 2f
        val cy = height / 2f - (fill.descent() + fill.ascent()) / 2f
        if (captionBackground != 0) {
            backdrop.color = captionBackground
            canvas.drawRoundRect(
                RectF(cx - 8f * d, cy + fill.ascent() - 4f * d, cx + textWidth + 8f * d, cy + fill.descent() + 4f * d),
                4f * d, 4f * d, backdrop
            )
        }
        if (outline) {
            stroke.color = outlineColor
            stroke.strokeWidth = 3.6f * d
            canvas.drawText(text, cx, cy, stroke)
        }
        fill.color = previewColor
        canvas.drawText(text, cx, cy, fill)
    }
}
