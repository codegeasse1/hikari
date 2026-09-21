package com.hikari.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.hikari.app.HikariApp
import com.hikari.app.data.MediaItem
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * A tile drawn for a LIVE TV channel that shipped no logo of its own.
 *
 * Plenty of playlists — regional Indian/Bangladeshi and "24-7 movie channel"
 * lists especially — leave `tvg-logo` empty for a good part of their channels,
 * and those rows sat on the generic film-clapper placeholder, which on a wall of
 * channels reads as "these images just don't load" (the reported "some iptv
 * movie channel not loading images").
 *
 * The fix is local and offline: a poster-shaped tile in the channel's own
 * colours, carrying its initials, its name and its group, drawn once and kept in
 * the app's own storage so the second visit is a plain file read. Nothing here
 * touches TMDB — a live channel has no TMDB entry, and the lookup that used to
 * fill these cells matched unrelated FILMS by name (see
 * [com.hikari.app.data.IptvMark]).
 *
 * The tile is written as a real PNG under `filesDir/iptv_art` and handed to Coil
 * through a `file://` URL, so it flows through exactly the same poster pipeline
 * (and the same disk caching) as every other cover in the app.
 */
object IptvArt {

    private const val W = 360
    private const val H = 540

    /** Key -> the `file://` URL already made for it. */
    private val urls = ConcurrentHashMap<String, String>()

    private val lock = Any()

    /**
     * The tile for [item], or null when nothing could be drawn (no name, or no
     * writable storage). Safe to call from any thread — the artwork lookup calls
     * it from its own worker.
     *
     * Keyed by the channel's NAME and group, so the same channel in two playlists
     * (or the same name in a group and in "All channels") reuses one file.
     */
    fun tile(item: MediaItem): String? {
        val name = item.title.trim()
        if (name.isEmpty()) return null
        val group = item.overview.orEmpty().trim()
        val key = fnv1a(name.lowercase() + "|" + group.lowercase())
        urls[key]?.let { return it }
        synchronized(lock) {
            urls[key]?.let { return it }
            val dir = runCatching {
                File(HikariApp.instance.filesDir, "iptv_art").apply { mkdirs() }
            }.getOrNull() ?: return null
            val file = File(dir, "$key.png")
            if (!file.exists() || file.length() == 0L) {
                val bmp = runCatching { draw(name, group) }.getOrNull() ?: return null
                val wrote = runCatching {
                    file.outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 92, out) }
                }.getOrDefault(false)
                bmp.recycle()
                if (!wrote || file.length() == 0L) return null
            }
            val url = "file://" + file.absolutePath
            urls[key] = url
            return url
        }
    }

    // ------------------------------------------------------------------ drawing --

    private fun draw(name: String, group: String): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val hue = ((fnv1a(name.lowercase()) and 0x7FFFFFFF) % 360).toFloat()
        val top = Color.HSVToColor(floatArrayOf(hue, 0.62f, 0.50f))
        val bottom = Color.HSVToColor(floatArrayOf((hue + 26f) % 360f, 0.78f, 0.12f))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.shader = LinearGradient(0f, 0f, 0f, H.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint)
        paint.shader = null

        // A soft light where the "lens" of the tile sits, and a darker foot so
        // the name below always has something to sit on.
        paint.color = Color.WHITE
        paint.alpha = 26
        canvas.drawCircle(W * 0.80f, H * 0.20f, W * 0.46f, paint)
        paint.alpha = 40
        canvas.drawCircle(W * 0.12f, H * 0.88f, W * 0.34f, paint)
        paint.color = Color.BLACK
        paint.alpha = 90
        paint.shader = LinearGradient(
            0f, H * 0.55f, 0f, H.toFloat(),
            Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, H * 0.55f, W.toFloat(), H.toFloat(), paint)
        paint.shader = null
        paint.alpha = 255

        // Initials: two or three letters that read as a station mark.
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = W * 0.30f
        canvas.drawText(initialsOf(name), W / 2f, H * 0.46f, paint)

        // Name, wrapped to at most two lines, under the mark.
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = W * 0.075f
        val nameLines = wrap(paint, name, W * 0.86f, 2)
        var y = H * 0.70f
        for (line in nameLines) {
            canvas.drawText(line, W / 2f, y, paint)
            y += paint.textSize * 1.22f
        }

        if (group.isNotBlank()) {
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            paint.textSize = W * 0.055f
            paint.alpha = 205
            canvas.drawText(
                wrap(paint, group.uppercase(), W * 0.86f, 1).firstOrNull().orEmpty(),
                W / 2f,
                y + paint.textSize * 0.35f,
                paint,
            )
            paint.alpha = 255
        }

        // A LIVE marker, the way an IPTV app labels a channel.
        val label = "LIVE"
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = W * 0.048f
        val padX = W * 0.035f
        val textW = paint.measureText(label)
        val dotR = W * 0.018f
        val pillW = textW + dotR * 2f + padX * 3.4f
        val pillH = paint.textSize * 1.85f
        val pill = RectF(W * 0.07f, H * 0.06f, W * 0.07f + pillW, H * 0.06f + pillH)
        paint.color = 0x33FFFFFF
        canvas.drawRoundRect(pill, pillH / 2f, pillH / 2f, paint)
        paint.color = 0xFFE8455A.toInt()
        canvas.drawCircle(pill.left + padX + dotR, pill.centerY(), dotR, paint)
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(
            label,
            pill.left + padX + dotR * 2f + padX * 0.9f,
            pill.centerY() + paint.textSize * 0.36f,
            paint,
        )
        return bmp
    }

    /** Up to three letters that stand for [name]: the initials of its words
     *  ("Sony TV HD" → "STH"), or the first letters of a single word. */
    private fun initialsOf(name: String): String {
        val words = name.split(' ', '-', '_', '.', '|')
            .map { it.trim() }
            .filter { it.any { c -> c.isLetterOrDigit() } }
        val letters = when {
            words.size >= 3 -> words.take(3).mapNotNull { it.firstOrNull { c -> c.isLetterOrDigit() } }
            words.size == 2 -> words.mapNotNull { it.firstOrNull { c -> c.isLetterOrDigit() } }
            words.size == 1 -> words[0].filter { it.isLetterOrDigit() }.take(3).toList()
            else -> emptyList()
        }
        val out = letters.joinToString("").uppercase()
        return out.ifBlank { name.filter { it.isLetterOrDigit() }.take(2).uppercase().ifBlank { "TV" } }
    }

    /** [text] broken into at most [maxLines] lines that fit [maxWidth], by
     *  words; the last line is ellipsised when the text does not fit. Word-wrap
     *  is done here rather than with a StaticLayout so the tile needs nothing
     *  from the UI toolkit. */
    private fun wrap(paint: Paint, text: String, maxWidth: Float, maxLines: Int): List<String> {
        val words = text.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return listOf(text)
        val lines = ArrayList<String>(maxLines)
        var current = ""
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth || current.isEmpty()) {
                current = candidate
            } else {
                lines += current
                current = word
                if (lines.size == maxLines) break
            }
        }
        if (lines.size < maxLines && current.isNotEmpty()) lines += current
        if (lines.isEmpty()) return listOf(text)
        val last = lines.last()
        if (paint.measureText(last) > maxWidth) {
            var cut = last
            while (cut.length > 1 && paint.measureText(cut + "…") > maxWidth) cut = cut.dropLast(1)
            lines[lines.size - 1] = cut.trimEnd() + "…"
        }
        return lines
    }

    /** 32-bit FNV-1a — a stable number for a name, used both for the tile's file
     *  key and its hue. */
    private fun fnv1a(s: String): Int {
        var h = 0x811c9dc5.toInt()
        for (b in s.encodeToByteArray()) {
            h = (h xor (b.toInt() and 0xFF))
            h *= 0x01000193
        }
        return h
    }
}
