package com.hikari.app.player

import org.json.JSONObject

/**
 * Shape of a player button (Settings → Customize → Player UI style).
 * [DEFAULT] leaves the skin's own shape alone.
 *
 *  - [CIRCLE]  fully round. On an icon-only button that is a circle; on a
 *              labelled pill it is the usual capsule.
 *  - [ROUNDED] a square with soft corners.
 *  - [SQUARE]  a square with four (almost) sharp corners.
 */
enum class PlayerButtonShape(val key: String) {
    DEFAULT("default"),
    CIRCLE("circle"),
    ROUNDED("rounded"),
    SQUARE("square");

    companion object {
        fun fromKey(key: String?): PlayerButtonShape =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** What kind of view an element is, which decides what can be changed on it. */
enum class PlayerElementKind {
    /** A labelled pill with an icon (favourite, speed, subtitles …). */
    PILL,
    /** A round icon button (back, play, previous, next). */
    ICON_BUTTON,
    /** The -10 / +10 seek buttons. */
    SEEK_BUTTON,
    /** Plain text (title, elapsed time, total time). */
    TEXT,
    /** The seek bar. */
    PROGRESS,
}

/** One editable thing in the player. */
class PlayerElementInfo(
    val key: String,
    val label: String,
    val kind: PlayerElementKind,
    /** The movable control this pill is, if it is one of the sixteen. */
    val control: PlayerControl? = null,
)

/**
 * What the viewer changed on one element. Every field has a "not set" value:
 *
 *  - [sizeDp]   0 = default. Icon size for pills, button size for icon buttons,
 *               text size for text.
 *  - [color]    0 = default. Icon colour (pills, buttons), text colour (text),
 *               played-bar colour (progress).
 *  - [bgColor]  0 = default. Button fill colour.
 *  - [shape]    [PlayerButtonShape.DEFAULT] = default.
 *  - [alphaPct] 0 = default (fully opaque). Otherwise 20..100 percent.
 *  - [dx], [dy] nudge in dp from where the element normally sits.
 */
data class ElementStyle(
    val sizeDp: Int = 0,
    val color: Int = 0,
    val bgColor: Int = 0,
    val shape: PlayerButtonShape = PlayerButtonShape.DEFAULT,
    val dx: Int = 0,
    val dy: Int = 0,
    val alphaPct: Int = 0,
) {
    val isDefault: Boolean get() = this == ElementStyle()
}

object PlayerElements {
    /** Applies to every pill and every round button at once. */
    const val ALL = "all"
    const val BACK = "back"
    const val PLAY = "play"
    const val PREV = "prev"
    const val NEXT = "next"
    const val REWIND = "rewind"
    const val FORWARD = "forward"
    const val TITLE = "title"
    const val POSITION = "position"
    const val DURATION = "duration"
    const val PROGRESS = "progress"

    /** Largest nudge in either direction, in dp. A fine adjustment: for a big
     *  move use "Where it sits" (top bar / bottom left / bottom right). */
    const val NUDGE_MAX = 40

    /** Opacity range in percent (below ALPHA_MIN means "default"). */
    const val ALPHA_MIN = 20
    const val ALPHA_MAX = 100

    fun pillKey(c: PlayerControl): String = "pill:" + c.key

    val list: List<PlayerElementInfo> = buildList {
        add(PlayerElementInfo(ALL, "All buttons", PlayerElementKind.PILL))
        add(PlayerElementInfo(BACK, "Back button", PlayerElementKind.ICON_BUTTON))
        add(PlayerElementInfo(TITLE, "Title", PlayerElementKind.TEXT))
        add(PlayerElementInfo(PLAY, "Play / pause", PlayerElementKind.ICON_BUTTON))
        add(PlayerElementInfo(PREV, "Previous", PlayerElementKind.ICON_BUTTON))
        add(PlayerElementInfo(NEXT, "Next", PlayerElementKind.ICON_BUTTON))
        add(PlayerElementInfo(REWIND, "Rewind 10s", PlayerElementKind.SEEK_BUTTON))
        add(PlayerElementInfo(FORWARD, "Forward 10s", PlayerElementKind.SEEK_BUTTON))
        add(PlayerElementInfo(PROGRESS, "Seek bar", PlayerElementKind.PROGRESS))
        add(PlayerElementInfo(POSITION, "Elapsed time", PlayerElementKind.TEXT))
        add(PlayerElementInfo(DURATION, "Total time", PlayerElementKind.TEXT))
        for (c in PlayerControl.entries) {
            add(PlayerElementInfo(pillKey(c), c.label, PlayerElementKind.PILL, c))
        }
    }

    fun info(key: String): PlayerElementInfo? = list.firstOrNull { it.key == key }

    fun sizeMin(kind: PlayerElementKind): Int = when (kind) {
        PlayerElementKind.PILL -> 16
        PlayerElementKind.ICON_BUTTON -> 24
        PlayerElementKind.SEEK_BUTTON -> 24
        PlayerElementKind.TEXT -> 8
        PlayerElementKind.PROGRESS -> 0
    }

    fun sizeMax(kind: PlayerElementKind): Int = when (kind) {
        PlayerElementKind.PILL -> 64
        PlayerElementKind.ICON_BUTTON -> 96
        PlayerElementKind.SEEK_BUTTON -> 80
        PlayerElementKind.TEXT -> 32
        PlayerElementKind.PROGRESS -> 0
    }

    fun sizeLabel(kind: PlayerElementKind): String = when (kind) {
        PlayerElementKind.PILL -> "Icon size"
        PlayerElementKind.ICON_BUTTON, PlayerElementKind.SEEK_BUTTON -> "Button size"
        PlayerElementKind.TEXT -> "Text size"
        PlayerElementKind.PROGRESS -> ""
    }

    fun hasSize(kind: PlayerElementKind): Boolean = sizeMax(kind) > 0

    fun hasShape(kind: PlayerElementKind): Boolean =
        kind == PlayerElementKind.PILL || kind == PlayerElementKind.ICON_BUTTON ||
            kind == PlayerElementKind.SEEK_BUTTON

    fun colorLabel(kind: PlayerElementKind): String = when (kind) {
        PlayerElementKind.TEXT -> "Text colour"
        PlayerElementKind.PROGRESS -> "Bar colour"
        PlayerElementKind.PILL -> "Icon & text colour"
        else -> "Icon colour"
    }
}

/**
 * The viewer's whole custom look for the player: one [ElementStyle] per edited
 * element (only edited ones are stored), plus the gap between buttons.
 * An empty style means "not customised" and the player is untouched.
 *
 * Stored as JSON under AppStore.PLAYER_UI_STYLE.
 */
data class PlayerUiStyle(
    val items: Map<String, ElementStyle> = emptyMap(),
    /** -1 = default, else 0..16 dp between neighbouring buttons. */
    val gapDp: Int = -1,
) {
    val isDefault: Boolean get() = items.isEmpty() && gapDp < 0

    fun get(key: String): ElementStyle = items[key] ?: ElementStyle()

    fun withElement(key: String, st: ElementStyle): PlayerUiStyle =
        copy(items = if (st.isDefault) items - key else items + (key to st))

    /**
     * What really applies to [key]: its own values, and for buttons the
     * "All buttons" values wherever the button has none of its own.
     */
    fun resolved(key: String): ElementStyle {
        val own = get(key)
        if (key == PlayerElements.ALL) return own
        val kind = PlayerElements.info(key)?.kind ?: return own
        if (!PlayerElements.hasShape(kind)) return own
        val base = get(PlayerElements.ALL)
        return own.copy(
            sizeDp = if (own.sizeDp > 0) own.sizeDp
            else if (kind == PlayerElementKind.PILL) base.sizeDp else 0,
            color = if (own.color != 0) own.color else base.color,
            bgColor = if (own.bgColor != 0) own.bgColor else base.bgColor,
            shape = if (own.shape != PlayerButtonShape.DEFAULT) own.shape else base.shape,
            alphaPct = if (own.alphaPct > 0) own.alphaPct else base.alphaPct,
        )
    }

    companion object {
        const val GAP_MIN = 0
        const val GAP_MAX = 16

        fun decode(json: String?): PlayerUiStyle {
            if (json.isNullOrBlank()) return PlayerUiStyle()
            return try {
                val root = JSONObject(json)
                val gap = root.optInt("gap", -1)
                val out = LinkedHashMap<String, ElementStyle>()
                val obj = root.optJSONObject("items")
                if (obj != null) {
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val info = PlayerElements.info(key) ?: continue
                        val o = obj.optJSONObject(key) ?: continue
                        val min = PlayerElements.sizeMin(info.kind)
                        val max = PlayerElements.sizeMax(info.kind)
                        val size = o.optInt("s", 0)
                        val alpha = o.optInt("a", 0)
                        val st = ElementStyle(
                            sizeDp = if (size <= 0 || max <= 0) 0 else size.coerceIn(min, max),
                            color = o.optInt("c", 0),
                            bgColor = o.optInt("b", 0),
                            shape = PlayerButtonShape.fromKey(o.optString("sh", "default")),
                            dx = o.optInt("x", 0).coerceIn(-PlayerElements.NUDGE_MAX, PlayerElements.NUDGE_MAX),
                            dy = o.optInt("y", 0).coerceIn(-PlayerElements.NUDGE_MAX, PlayerElements.NUDGE_MAX),
                            alphaPct = if (alpha <= 0) 0
                            else alpha.coerceIn(PlayerElements.ALPHA_MIN, PlayerElements.ALPHA_MAX),
                        )
                        if (!st.isDefault) out[key] = st
                    }
                }
                PlayerUiStyle(
                    items = out,
                    gapDp = if (gap < 0) -1 else gap.coerceIn(GAP_MIN, GAP_MAX),
                )
            } catch (_: Throwable) {
                PlayerUiStyle()
            }
        }

        fun encode(s: PlayerUiStyle): String {
            val root = JSONObject()
            root.put("gap", s.gapDp)
            val obj = JSONObject()
            for ((key, st) in s.items) {
                val o = JSONObject()
                o.put("s", st.sizeDp)
                o.put("c", st.color)
                o.put("b", st.bgColor)
                o.put("sh", st.shape.key)
                o.put("x", st.dx)
                o.put("y", st.dy)
                o.put("a", st.alphaPct)
                obj.put(key, o)
            }
            root.put("items", obj)
            return root.toString()
        }
    }
}

/** A ready-made look the viewer can apply with one tap. */
class PlayerUiPreset(
    val key: String,
    val label: String,
    val style: PlayerUiStyle,
)

/**
 * One-tap starting points. Applying one replaces the whole custom look; every
 * value can still be changed afterwards, element by element.
 */
object PlayerUiPresets {

    private const val DARK_FILL = 0xB3000000
    private const val YELLOW_FILL = 0xB3FFD600
    private const val GLASS_FILL = 0x33FFFFFF
    private const val CYAN = 0xFF00E5FF
    private const val BLACK = 0xFF000000

    val list: List<PlayerUiPreset> = listOf(
        // Back to how the player shipped.
        PlayerUiPreset("default", "Default", PlayerUiStyle()),

        // Big round touch targets, dark plates.
        PlayerUiPreset(
            "round_big", "Round & big",
            PlayerUiStyle(
                items = mapOf(
                    PlayerElements.ALL to ElementStyle(
                        sizeDp = 30,
                        shape = PlayerButtonShape.CIRCLE,
                        bgColor = DARK_FILL.toInt(),
                    ),
                    PlayerElements.PLAY to ElementStyle(sizeDp = 64),
                    PlayerElements.BACK to ElementStyle(sizeDp = 40),
                ),
                gapDp = 8,
            ),
        ),

        // Hard-edged plates with a cyan glyph.
        PlayerUiPreset(
            "square_neon", "Square neon",
            PlayerUiStyle(
                items = mapOf(
                    PlayerElements.ALL to ElementStyle(
                        color = CYAN.toInt(),
                        bgColor = DARK_FILL.toInt(),
                        shape = PlayerButtonShape.SQUARE,
                    ),
                    PlayerElements.PROGRESS to ElementStyle(color = CYAN.toInt()),
                ),
                gapDp = 6,
            ),
        ),

        // Yellow soft-corner plates with a black glyph.
        PlayerUiPreset(
            "yellow_soft", "Yellow soft",
            PlayerUiStyle(
                items = mapOf(
                    PlayerElements.ALL to ElementStyle(
                        color = BLACK.toInt(),
                        bgColor = YELLOW_FILL.toInt(),
                        shape = PlayerButtonShape.ROUNDED,
                    ),
                ),
                gapDp = 6,
            ),
        ),

        // Faint see-through plates that stay out of the picture.
        PlayerUiPreset(
            "ghost", "Ghost",
            PlayerUiStyle(
                items = mapOf(
                    PlayerElements.ALL to ElementStyle(
                        bgColor = GLASS_FILL.toInt(),
                        shape = PlayerButtonShape.ROUNDED,
                        alphaPct = 80,
                    ),
                ),
                gapDp = 4,
            ),
        ),
    )
}
