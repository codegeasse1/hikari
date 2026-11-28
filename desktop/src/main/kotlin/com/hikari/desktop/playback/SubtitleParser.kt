package com.hikari.desktop.playback

/** One caption cue with millisecond bounds. */
data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

/** Pure SRT/WebVTT parsing — no I/O, fully unit-testable. */
object SubtitleParser {

    fun parse(text: String, fallbackLabel: String = ""): List<SubtitleCue> {
        val cleaned = text.trim().replace("\r\n", "\n").replace('\r', '\n')
        if (cleaned.isEmpty()) return emptyList()
        val body = if (cleaned.startsWith("WEBVTT", ignoreCase = true)) {
            cleaned.lineSequence().drop(1).joinToString("\n").trim()
        } else cleaned
        val cues = mutableListOf<SubtitleCue>()
        for (block in body.split(Regex("\n\\s*\n"))) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            val (stampLine, textLines) = if (lines.size >= 2 && lines[0].matches(Regex("^\\d+$")) && lines[1].contains("-->")) {
                lines[1] to lines.drop(2)
            } else if (lines[0].contains("-->")) {
                lines[0] to lines.drop(1)
            } else continue
            val bounds = parseBounds(stampLine) ?: continue
            val caption = textLines
                .filterNot { it.startsWith("<") && it.endsWith(">") && !it.contains(' ') }
                .joinToString("\n") { stripTags(it) }
                .trim()
                .take(500)
            if (caption.isBlank()) continue
            cues.add(SubtitleCue(bounds.first, bounds.second, caption))
        }
        if (cues.isEmpty() && fallbackLabel.isNotBlank()) return emptyList()
        return cues.sortedBy { it.startMs }
    }

    fun activeAt(cues: List<SubtitleCue>, positionMs: Long): SubtitleCue? {
        if (cues.isEmpty()) return null
        // Cues are sorted: binary search the last cue that starts at/before now.
        var lo = 0
        var hi = cues.size - 1
        var found: SubtitleCue? = null
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = cues[mid]
            if (c.startMs <= positionMs) {
                found = c
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found?.takeIf { positionMs < it.endMs }
    }

    private fun parseBounds(line: String): Pair<Long, Long>? {
        val parts = line.split("-->")
        if (parts.size != 2) return null
        val start = parseStamp(parts[0].trim()) ?: return null
        val end = parseStamp(parts[1].trim().split(Regex("\\s+")).firstOrNull() ?: return null) ?: return null
        if (end <= start) return null
        return start to end
    }

    private fun parseStamp(raw: String): Long? {
        // 00:00:00,000 | 00:00.000 | 00:00:00.000
        val s = raw.replace(',', '.')
        return runCatching {
            val hms = s.split(':')
            val (h, m, sec) = when (hms.size) {
                3 -> Triple(hms[0].toLong(), hms[1].toLong(), hms[2].toDouble())
                2 -> Triple(0L, hms[0].toLong(), hms[1].toDouble())
                else -> return null
            }
            ((h * 3600 + m * 60) * 1000 + (sec * 1000)).toLong()
        }.getOrNull()
    }

    private fun stripTags(s: String): String =
        s.replace(Regex("</?[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()
}
