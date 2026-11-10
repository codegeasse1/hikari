package com.hikari.app.player

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hikari.app.R
import kotlin.math.roundToInt

/**
 * The "Streams list" look of the server page (Settings -> Customize -> Server
 * screen style): a title banner, a refresh button + one chip per extension, a
 * "Finding streams…" skeleton while nothing has landed, then the servers
 * grouped per extension with a live "Fetching…" tag.
 *
 * This class only DRAWS. It owns no player state: [PlayerActivity] feeds it a
 * [State] through [update] and handles every tap through the callbacks, so
 * picking, downloading and backing out behave exactly like the classic page.
 */
internal class ServerStreamsPage(
    private val activity: Activity,
    private val episodeLabel: String,
    private val mainTitle: String,
    private val subTitle: String,
    private val artModel: Any?,
    private val isTv: Boolean,
    private val loadImage: (ImageView, Any) -> Unit,
    private val focusRing: () -> Drawable,
    private val onBack: () -> Unit,
    private val onRefresh: () -> Unit,
    private val onPick: (Int) -> Unit,
    private val onLongPick: (Int) -> Unit,
) {

    /** One server card. [index] is the position in the player's own source list. */
    data class Item(
        val index: Int,
        val title: String,
        val sub: String,
        val addon: String,
        val selected: Boolean,
    )

    /** One extension: a chip, a section, and whether it is still being asked. */
    data class Addon(val name: String, val fetching: Boolean)

    data class State(
        val items: List<Item>,
        val addons: List<Addon>,
        val searching: Boolean,
        val emptyNote: String?,
    )

    private companion object {
        const val ALL = "All"
        const val BG = 0xFF0C0C0C.toInt()
        const val CARD = 0xFF181818.toInt()
        const val CARD_EDGE = 0xFF111111.toInt()
        const val CARD_ON = 0xFF262626.toInt()
        const val CHIP = 0xFF1B1B1B.toInt()
        const val CHIP_ON = 0xFFF4F4F4.toInt()
        const val BAR = 0xFF2A2A2A.toInt()
        const val TEXT = 0xFFFFFFFF.toInt()
        const val TEXT_DIM = 0xFF9C9C9C.toInt()
    }

    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Float): Int = (v * density).roundToInt()

    private var state: State? = null
    private var chip = ALL
    private var chipSig = ""
    private var listSig = ""
    private var insetTop = 0
    private var insetBottom = 0

    private val skeletons = ArrayList<View>()
    private var shimmer: ValueAnimator? = null
    private var spinner: ObjectAnimator? = null

    // ── banner height: tall on a portrait phone, shorter in landscape / on TV ──
    private val heroBaseDp: Float = run {
        val dm = activity.resources.displayMetrics
        val landscape = dm.widthPixels > dm.heightPixels
        if (landscape || isTv) minOf(150f, dm.heightPixels / density * 0.30f) else 200f
    }

    val root = FrameLayout(activity).apply { setBackgroundColor(BG) }

    private val heroFrame = FrameLayout(activity)
    private val heroImage = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(0xFF161616.toInt())
    }
    private val heroFade = View(activity).apply {
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x000C0C0C, BG)
        )
    }
    private val heroText = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val backBtn = FrameLayout(activity)

    private val refreshIcon = ImageView(activity)
    private val refreshBtn = FrameLayout(activity)
    private val chipRow = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val chipScroll = HorizontalScrollView(activity).apply {
        isHorizontalScrollBarEnabled = false
        clipToPadding = false
    }
    private val scroll = ScrollView(activity).apply {
        isVerticalScrollBarEnabled = false
    }
    private val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    init {
        // Banner: art, fade into the page colour, then the text block.
        heroFrame.addView(heroImage, FrameLayout.LayoutParams(-1, -1))
        heroFrame.addView(
            heroFade,
            FrameLayout.LayoutParams(-1, dp(150f)).apply { gravity = Gravity.BOTTOM }
        )

        fun line(text: String, sp: Float, color: Int, bold: Boolean, maxLines: Int): TextView =
            TextView(activity).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
                setTextColor(color)
                includeFontPadding = false
                this.maxLines = maxLines
                ellipsize = TextUtils.TruncateAt.END
                if (bold) setTypeface(typeface, Typeface.BOLD)
                setShadowLayer(8f, 0f, 2f, 0x99000000.toInt())
                visibility = if (text.isBlank()) View.GONE else View.VISIBLE
            }
        heroText.addView(line(episodeLabel, 14f, 0xFFE8E8E8.toInt(), true, 1))
        heroText.addView(
            line(mainTitle, 24f, TEXT, true, 2),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3f) }
        )
        heroText.addView(
            line(subTitle, 14f, 0xFFB4B4B4.toInt(), false, 1),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3f) }
        )
        heroFrame.addView(
            heroText,
            FrameLayout.LayoutParams(-1, -2).apply { gravity = Gravity.BOTTOM or Gravity.START }
        )

        // Back button: dark translucent circle with the arrow.
        backBtn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x66000000)
        }
        backBtn.addView(
            ImageView(activity).apply {
                setImageResource(R.drawable.ic_back)
                scaleType = ImageView.ScaleType.CENTER
            },
            FrameLayout.LayoutParams(-1, -1)
        )
        backBtn.isClickable = true
        backBtn.contentDescription = "Back"
        backBtn.setOnClickListener { onBack() }
        if (isTv) tvFocusable(backBtn)
        heroFrame.addView(
            backBtn,
            FrameLayout.LayoutParams(dp(40f), dp(40f)).apply { gravity = Gravity.TOP or Gravity.START }
        )

        if (artModel != null) runCatching { loadImage(heroImage, artModel) }

        // Refresh button (first thing in the chip row, never rebuilt).
        refreshBtn.background = pill(CHIP)
        refreshIcon.setImageResource(R.drawable.ic_refresh)
        refreshIcon.scaleType = ImageView.ScaleType.CENTER
        refreshBtn.addView(
            refreshIcon,
            FrameLayout.LayoutParams(dp(24f), dp(24f)).apply { gravity = Gravity.CENTER }
        )
        refreshBtn.isClickable = true
        refreshBtn.contentDescription = "Refresh"
        refreshBtn.setOnClickListener { onRefresh() }
        if (isTv) tvFocusable(refreshBtn)
        chipRow.addView(
            refreshBtn,
            LinearLayout.LayoutParams(dp(49f), dp(37f)).apply { marginEnd = dp(8f) }
        )
        chipScroll.addView(chipRow, LinearLayout.LayoutParams(-2, -2))
        chipScroll.setPadding(dp(12f), dp(12f), dp(12f), dp(10f))

        list.setPadding(dp(12f), dp(14f), dp(12f), dp(28f))
        scroll.addView(list, ViewGroup.LayoutParams(-1, -2))

        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        column.addView(heroFrame, LinearLayout.LayoutParams(-1, dp(heroBaseDp)))
        column.addView(chipScroll, LinearLayout.LayoutParams(-1, -2))
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(-1, -1))

        applyInsets()
        rebuildChips(emptyList())
        update(State(emptyList(), emptyList(), true, null))
    }

    /** Safe-area insets (display cutout / bars), pushed in by the host window. */
    fun setInsets(top: Int, bottom: Int, left: Int, right: Int) {
        insetTop = top
        insetBottom = bottom
        root.setPadding(left, 0, right, 0)
        applyInsets()
    }

    private fun applyInsets() {
        (heroFrame.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.height = dp(heroBaseDp) + insetTop
            heroFrame.layoutParams = it
        }
        heroText.setPadding(dp(18f), 0, dp(18f), dp(10f))
        (backBtn.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.topMargin = insetTop + dp(12f)
            it.marginStart = dp(13f)
            backBtn.layoutParams = it
        }
        list.setPadding(dp(12f), dp(14f), dp(12f), dp(28f) + insetBottom)
    }

    // ── public drawing entry points ──

    fun update(st: State) {
        state = st
        // A chip whose extension has gone away falls back to All.
        if (chip != ALL && st.addons.none { sameName(it.name, chip) } &&
            st.items.none { sameName(it.addon, chip) }
        ) chip = ALL
        rebuildChips(st.addons.map { it.name })
        renderList(false)
        spin(st.searching)
    }

    fun requestInitialFocus() {
        if (!isTv) return
        (chipRow.getChildAt(1) ?: refreshBtn).requestFocus()
    }

    fun stop() {
        shimmer?.cancel()
        shimmer = null
        spinner?.cancel()
        spinner = null
        refreshIcon.rotation = 0f
    }

    // ── chips ──

    private fun rebuildChips(names: List<String>) {
        val sig = names.joinToString("|") + "#" + chip
        if (sig == chipSig) return
        chipSig = sig
        while (chipRow.childCount > 1) chipRow.removeViewAt(1)
        val labels = ArrayList<String>(names.size + 1)
        labels.add(ALL)
        labels.addAll(names)
        for (label in labels) {
            chipRow.addView(
                makeChip(label, label == chip || (label != ALL && sameName(label, chip))),
                LinearLayout.LayoutParams(-2, dp(37f)).apply { marginEnd = dp(8f) }
            )
        }
    }

    private fun makeChip(label: String, selected: Boolean): TextView =
        TextView(activity).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            includeFontPadding = false
            isSingleLine = true
            gravity = Gravity.CENTER
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setTextColor(if (selected) BG else TEXT)
            setPadding(dp(16f), 0, dp(16f), 0)
            background = pill(if (selected) CHIP_ON else CHIP)
            isClickable = true
            if (isTv) tvFocusable(this)
            setOnClickListener {
                if (chip != label) {
                    chip = label
                    chipSig = ""
                    state?.let { st -> rebuildChips(st.addons.map { it.name }) }
                    renderList(true)
                }
            }
        }

    // ── list ──

    private fun sameName(a: String, b: String): Boolean = a.trim().equals(b.trim(), ignoreCase = true)

    private fun renderList(resetScroll: Boolean) {
        val st = state ?: return
        val sig = buildString {
            append(chip).append('|').append(st.searching).append('|').append(st.emptyNote)
            for (a in st.addons) append(a.name).append(if (a.fetching) 'f' else 'd').append(';')
            for (i in st.items) {
                append(i.index).append(if (i.selected) '*' else '.')
                append(i.title.hashCode()).append(i.sub.hashCode()).append(';')
            }
        }
        if (sig == listSig && !resetScroll) return
        listSig = sig

        val keep = scroll.scrollY
        list.removeAllViews()
        skeletons.clear()

        val all = chip == ALL
        val shown = if (all) st.items else st.items.filter { sameName(it.addon, chip) }
        val chipFetching = if (all) st.searching
        else st.addons.any { it.fetching && sameName(it.name, chip) }

        if (shown.isEmpty()) {
            if (chipFetching || (all && st.searching)) {
                addInfo("Finding streams\u2026", Gravity.START)
                repeat(4) { addSkeleton() }
                startShimmer()
            } else {
                stopShimmer()
                addInfo(st.emptyNote ?: "No streams found.", Gravity.START)
            }
        } else {
            stopShimmer()
            if (all) {
                val names = ArrayList<String>()
                for (a in st.addons) if (names.none { sameName(it, a.name) }) names.add(a.name)
                for (i in st.items) if (names.none { sameName(it, i.addon) }) names.add(i.addon)
                for (name in names) {
                    val group = st.items.filter { sameName(it.addon, name) }
                    val fetching = st.addons.any { it.fetching && sameName(it.name, name) }
                    if (group.isEmpty() && !fetching) continue
                    addHeader(name, fetching)
                    for (item in group) addCard(item)
                }
            } else {
                for (item in shown) addCard(item)
                if (chipFetching) addInfo("Fetching\u2026", Gravity.CENTER_HORIZONTAL)
            }
            if (st.searching) addInfo("Checking more addons\u2026", Gravity.CENTER_HORIZONTAL, 20f)
        }

        if (resetScroll) scroll.scrollTo(0, 0)
        else if (keep > 0) scroll.post { scroll.scrollTo(0, keep) }
    }

    private fun addInfo(text: String, gravity: Int, topDp: Float = 0f) {
        list.addView(
            TextView(activity).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(TEXT_DIM)
                includeFontPadding = false
                this.gravity = gravity
                setPadding(dp(14f), 0, dp(14f), 0)
            },
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(topDp)
                bottomMargin = dp(14f)
            }
        )
    }

    private fun addHeader(name: String, fetching: Boolean) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), 0, dp(14f), 0)
        }
        row.addView(
            TextView(activity).apply {
                text = name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(TEXT)
                includeFontPadding = false
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setTypeface(typeface, Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        if (fetching) {
            row.addView(
                TextView(activity).apply {
                    text = "Fetching\u2026"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(TEXT_DIM)
                    includeFontPadding = false
                },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12f) }
            )
        }
        list.addView(
            row,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(2f)
                bottomMargin = dp(10f)
            }
        )
    }

    private fun cardBg(selected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14f).toFloat()
            setColor(if (selected) CARD_ON else CARD)
            setStroke(
                if (selected) dp(1.5f).coerceAtLeast(1) else dp(1f).coerceAtLeast(1),
                if (selected) 0x99FFFFFF.toInt() else CARD_EDGE
            )
        }

    private fun addCard(item: Item) {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64f)
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            val bg = cardBg(item.selected)
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, bg)
            isClickable = true
            if (isTv) tvFocusable(this)
            setOnClickListener { onPick(item.index) }
            setOnLongClickListener { onLongPick(item.index); true }
        }
        card.addView(
            TextView(activity).apply {
                text = item.title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(TEXT)
                includeFontPadding = false
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setTypeface(typeface, Typeface.BOLD)
            },
            LinearLayout.LayoutParams(-1, -2)
        )
        if (item.sub.isNotBlank()) {
            card.addView(
                TextView(activity).apply {
                    text = item.sub
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(TEXT_DIM)
                    includeFontPadding = false
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5f) }
            )
        }
        list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12f) })
    }

    private fun addSkeleton() {
        val dm = activity.resources.displayMetrics
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64f)
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            background = cardBg(false)
        }
        card.addView(
            View(activity).apply { background = bar() },
            LinearLayout.LayoutParams(dp(110f), dp(9f))
        )
        card.addView(
            View(activity).apply { background = bar() },
            LinearLayout.LayoutParams((dm.widthPixels * 0.62f).toInt(), dp(8f)).apply {
                topMargin = dp(10f)
            }
        )
        list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12f) })
        skeletons.add(card)
    }

    private fun bar(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(5f).toFloat()
            setColor(BAR)
        }

    private fun pill(color: Int): Drawable {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f
            setColor(color)
        }
        return RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, bg)
    }

    // ── animations ──

    private fun startShimmer() {
        if (shimmer != null) return
        shimmer = ValueAnimator.ofFloat(0.45f, 1f).apply {
            duration = 900L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                val v = a.animatedValue as Float
                for (s in skeletons) s.alpha = v
            }
            start()
        }
    }

    private fun stopShimmer() {
        shimmer?.cancel()
        shimmer = null
    }

    private fun spin(on: Boolean) {
        if (on) {
            if (spinner != null) return
            spinner = ObjectAnimator.ofFloat(refreshIcon, View.ROTATION, 0f, 360f).apply {
                duration = 1000L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        } else {
            spinner?.cancel()
            spinner = null
            refreshIcon.rotation = 0f
        }
    }

    // ── television remote ──

    private fun tvFocusable(v: View) {
        v.isFocusable = true
        v.isFocusableInTouchMode = true
        v.foreground = focusRing()
    }
}
