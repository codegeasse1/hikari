package com.hikari.app.player

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.ui.CaptionStyleCompat
import java.io.File

/**
 * The player's caption appearance, persisted in the same `player_subs`
 * preferences as the size / sync / position settings.
 *
 * Media3 renders captions through [androidx.media3.ui.SubtitleView], whose
 * style is a [CaptionStyleCompat]: a foreground colour, a background colour
 * (alpha 0 = no background at all, i.e. "removed"), an edge type + edge colour
 * and a typeface. Everything the settings sheet offers maps onto exactly one of
 * those fields, so what the user previews in the colour picker is what the
 * renderer draws — there is no second styling path that could disagree.
 *
 * The one thing [CaptionStyleCompat] cannot express is an outline WIDTH (the
 * caption renderer strokes the outline at a fixed width), so the sheet offers
 * outline on/off + colour rather than a width slider that would do nothing.
 *
 * [enabled] is the whole feature's switch: while it is off the player hands
 * media3 its default (null) style and re-enables the styles embedded in the
 * subtitle file, so a subtitle that carries its own fonts/colours still looks
 * the way its author intended until the user opts into overriding it.
 */
data class SubtitleStyle(
    val enabled: Boolean = false,
    val textColor: Int = 0xFFFFFFFF.toInt(),
    /** [EDGE_NONE] / [EDGE_OUTLINE] / [EDGE_SHADOW]. */
    val edge: Int = EDGE_OUTLINE,
    val edgeColor: Int = 0xFF000000.toInt(),
    /** Alpha 0 removes the caption background entirely. */
    val background: Int = 0x00000000,
    val bold: Boolean = false,
    val italic: Boolean = false,
    /** A [SubtitleFonts] key: "" for the system default, a family name, or
     *  "file:<path>" for a .ttf/.otf the user brought from this device. */
    val font: String = "",
) {
    val outline: Boolean get() = edge != EDGE_NONE

    /** A short line for the settings row ("Custom · Outline · Sans"). */
    fun summary(): String {
        if (!enabled) return "Off"
        val bits = mutableListOf<String>()
        bits.add(if (edge == EDGE_SHADOW) "Shadow" else if (edge == EDGE_OUTLINE) "Outline" else "Plain")
        bits.add(SubtitleFonts.label(this.font))
        if (bold) bits.add("Bold")
        if (italic) bits.add("Italic")
        return bits.joinToString(" \u00b7 ")
    }

    fun copySaved(prefs: SharedPreferences): SubtitleStyle {
        prefs.edit()
            .putBoolean(KEY_ON, enabled)
            .putInt(KEY_TEXT, textColor)
            .putInt(KEY_EDGE, edge)
            .putInt(KEY_EDGE_COLOR, edgeColor)
            .putInt(KEY_BG, background)
            .putBoolean(KEY_BOLD, bold)
            .putBoolean(KEY_ITALIC, italic)
            .putString(KEY_FONT, font)
            .apply()
        return this
    }

    companion object {
        const val EDGE_NONE = 0
        const val EDGE_OUTLINE = 1
        const val EDGE_SHADOW = 2

        private const val KEY_ON = "sub_style_on"
        private const val KEY_TEXT = "sub_text_color"
        private const val KEY_EDGE = "sub_edge_type"
        private const val KEY_EDGE_COLOR = "sub_edge_color"
        private const val KEY_BG = "sub_bg_color"
        private const val KEY_BOLD = "sub_bold"
        private const val KEY_ITALIC = "sub_italic"
        private const val KEY_FONT = "sub_font"

        fun load(prefs: SharedPreferences): SubtitleStyle {
            val defaults = SubtitleStyle()
            return SubtitleStyle(
                enabled = prefs.getBoolean(KEY_ON, defaults.enabled),
                textColor = prefs.getInt(KEY_TEXT, defaults.textColor),
                edge = prefs.getInt(KEY_EDGE, defaults.edge),
                edgeColor = prefs.getInt(KEY_EDGE_COLOR, defaults.edgeColor),
                background = prefs.getInt(KEY_BG, defaults.background),
                bold = prefs.getBoolean(KEY_BOLD, defaults.bold),
                italic = prefs.getBoolean(KEY_ITALIC, defaults.italic),
                font = prefs.getString(KEY_FONT, defaults.font) ?: defaults.font,
            )
        }
    }
}

/** Typeface + font-file handling for [SubtitleStyle]. */
object SubtitleFonts {
    /** The selectable families, in the order the picker lists them. */
    val FAMILIES: List<String> = listOf("", "sans", "serif", "monospace", "casual", "cursive")

    fun label(key: String): String = when {
        key.isBlank() -> "Default"
        key.startsWith(FILE_PREFIX) -> File(key.removePrefix(FILE_PREFIX)).name
        key == "sans" -> "Sans"
        key == "serif" -> "Serif"
        key == "monospace" -> "Monospace"
        key == "casual" -> "Casual"
        key == "cursive" -> "Cursive"
        else -> key
    }

    private const val FILE_PREFIX = "file:"

    fun isFile(key: String): Boolean = key.startsWith(FILE_PREFIX)

    /**
     * The base typeface for [key], or null when the key is the system default.
     * A file the user picked that has since been moved/deleted yields null too,
     * so a stale preference degrades to the default font instead of crashing
     * the renderer.
     */
    fun base(context: Context, key: String): Typeface? = when {
        key.isBlank() -> null
        isFile(key) -> runCatching {
            Typeface.createFromFile(File(key.removePrefix(FILE_PREFIX)))
        }.getOrNull()
        key == "sans" -> Typeface.SANS_SERIF
        key == "serif" -> Typeface.SERIF
        key == "monospace" -> Typeface.MONOSPACE
        // "casual" / "cursive" are family names the platform resolves by name;
        // a device without them falls back to its default font, which is the
        // right outcome for a purely cosmetic choice.
        else -> Typeface.create(key, Typeface.NORMAL)
    }

    /** The typeface for [style] with bold/italic baked in (CaptionStyleCompat
     *  takes a typeface, not separate weight flags). */
    fun resolve(context: Context, style: SubtitleStyle): Typeface {
        val base = base(context, style.font) ?: Typeface.DEFAULT
        val flags = (if (style.bold) Typeface.BOLD else 0) or
            (if (style.italic) Typeface.ITALIC else 0)
        return Typeface.create(base, flags)
    }

    /** The media3 caption style for [style].
     *
     *  When custom styling is off this is media3's own [CaptionStyleCompat.DEFAULT],
     *  never null: `SubtitleView.setStyle(null)` is accepted by its (unannotated)
     *  Java signature but leaves the view's style field null, and the canvas
     *  caption renderer then dereferences it while drawing — i.e. passing null
     *  crashes the player the moment a caption appears. */
    fun captionStyle(context: Context, style: SubtitleStyle): CaptionStyleCompat {
        if (!style.enabled) return CaptionStyleCompat.DEFAULT
        val edgeType = when (style.edge) {
            SubtitleStyle.EDGE_OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
            SubtitleStyle.EDGE_SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
            else -> CaptionStyleCompat.EDGE_TYPE_NONE
        }
        return CaptionStyleCompat(
            style.textColor,
            style.background,
            android.graphics.Color.TRANSPARENT,
            edgeType,
            style.edgeColor,
            resolve(context, style),
        )
    }

    /**
     * Copies a .ttf/.otf/.ttc the user picked into the app's own files
     * directory — the picked content:// URI's permission is not persisted, so a
     * cache copy is what keeps the font working after a restart — validates
     * that the platform can actually load it, and returns the preference key
     * ("file:<path>") for it. Null when the file is not a usable font.
     */
    fun importFile(context: Context, uri: Uri): String? {
        val name = displayName(context, uri) ?: "font.ttf"
        val lower = name.lowercase()
        if (!lower.endsWith(".ttf") && !lower.endsWith(".otf") && !lower.endsWith(".ttc")) return null
        val dir = File(context.filesDir, "subtitle_fonts").apply { mkdirs() }
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(48)
        val dest = File(dir, safe)
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } != null
        }.getOrDefault(false)
        if (!copied || dest.length() <= 0L) {
            runCatching { dest.delete() }
            return null
        }
        return runCatching {
            Typeface.createFromFile(dest)
            FILE_PREFIX + dest.absolutePath
        }.getOrElse {
            runCatching { dest.delete() }
            null
        }
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
