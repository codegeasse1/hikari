package com.hikari.app.dub

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Keeps finished dub work on the phone, so leaving the video or closing the app never
 * throws it away.
 *
 * What is saved, per video (one folder per video + subtitle text):
 *  - every voiced line (a small audio file per subtitle line),
 *  - every translated line,
 *  - the result of listening to the original voices (who speaks, man or woman),
 *  - a small "resume" note: the dub settings and the subtitle text of the last dub of that
 *    video, so opening the same video again can carry on by itself.
 *
 * Everything lives in the app's own files folder (NOT the cache), so Android does not clean
 * it away on its own. The oldest videos are removed when the saved dubs grow past
 * [MAX_BYTES]. "Clear storage" in the phone's app settings removes all of it.
 */
object DubStore {

    private const val ROOT = "dub_saved"
    private const val MAX_BYTES = 1_500L * 1024L * 1024L
    private const val MIN_CLIP_BYTES = 256L
    private const val RESUME_MAX_AGE_MS = 120L * 24L * 60L * 60L * 1000L

    /** What the last dub of a video was started with. */
    class Resume(
        val lang: String,
        val mode: VoiceMode,
        val duck: Float,
        val multi: Boolean,
        val raw: String,
    )

    // ───────────────────────── names ─────────────────────────

    private fun root(context: Context): File = File(context.filesDir, ROOT).apply { mkdirs() }

    private fun hex(md: MessageDigest, n: Int): String =
        md.digest().joinToString("") { "%02x".format(it) }.take(n)

    private fun sha(text: String, n: Int): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(text.toByteArray(Charsets.UTF_8))
        return hex(md, n)
    }

    /** Identity of a subtitle: the number of lines, their timing and their text. */
    private fun cuesSig(cues: List<DubCue>): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(("v1|" + cues.size + "|").toByteArray(Charsets.UTF_8))
        for (c in cues) {
            md.update((c.startMs.toString() + ":" + c.endMs + ":" + c.text + ";").toByteArray(Charsets.UTF_8))
        }
        return hex(md, 24)
    }

    private fun videoDir(context: Context, videoKey: String, cues: List<DubCue>): File =
        File(root(context), "v_" + sha(videoKey, 16) + "_" + cuesSig(cues))

    private fun safe(text: String): String = text.replace(Regex("[^A-Za-z0-9]"), "_")

    // ───────────────────────── text escaping ─────────────────────────

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun unesc(s: String): String {
        if (s.indexOf('\\') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                val next = s[i + 1]
                when (next) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    '\\' -> sb.append('\\')
                    else -> {
                        sb.append(ch)
                        sb.append(next)
                    }
                }
                i += 2
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }

    // ───────────────────────── one dub session ─────────────────────────

    /** The saved work of one video + language + voice setting. */
    class Session internal constructor(
        private val clipsDir: File,
        private val trFile: File,
    ) {
        private val lock = Any()

        /** Saved translations: original line -> translated line. */
        fun translations(): Map<String, String> {
            val out = HashMap<String, String>()
            if (!trFile.exists()) return out
            synchronized(lock) {
                try {
                    trFile.forEachLine(Charsets.UTF_8) { line ->
                        val t = line.indexOf('\t')
                        if (t > 0) {
                            out[DubStore.unesc(line.substring(0, t))] = DubStore.unesc(line.substring(t + 1))
                        }
                    }
                } catch (e: Throwable) {
                    // a half-written last line is simply ignored
                }
            }
            return out
        }

        fun addTranslation(src: String, translated: String) {
            if (src.isBlank() || translated.isBlank()) return
            synchronized(lock) {
                try {
                    trFile.appendText(DubStore.esc(src) + "\t" + DubStore.esc(translated) + "\n", Charsets.UTF_8)
                } catch (e: Throwable) {
                    // not saving is never a reason to stop the dub
                }
            }
        }

        /** Saved voiced lines: line number -> audio file. */
        fun readyClips(): Map<Int, File> {
            val out = HashMap<Int, File>()
            val files = clipsDir.listFiles() ?: return out
            for (f in files) {
                val n = f.name
                if (!n.startsWith("c") || !n.endsWith(".wav")) continue
                val idx = n.substring(1, n.length - 4).toIntOrNull() ?: continue
                if (f.length() >= DubStore.MIN_CLIP_BYTES) out[idx] = f
            }
            return out
        }

        /**
         * Copies a finished line into the saved folder (through a temp name, so a line is
         * never half-saved). Returns the saved file, or null when it could not be saved
         * (then the caller keeps using the original file).
         */
        fun saveClip(idx: Int, from: File): File? {
            try {
                if (from.parentFile == clipsDir) return from
                clipsDir.mkdirs()
                val target = File(clipsDir, "c$idx.wav")
                if (target.exists() && target.length() >= DubStore.MIN_CLIP_BYTES) return target
                val tmp = File(clipsDir, "c$idx.tmp")
                from.copyTo(tmp, overwrite = true)
                if (tmp.length() < DubStore.MIN_CLIP_BYTES) {
                    tmp.delete()
                    return null
                }
                if (target.exists()) target.delete()
                if (tmp.renameTo(target)) return target
                tmp.delete()
                return null
            } catch (e: Throwable) {
                return null
            }
        }
    }

    /**
     * Opens (and creates) the saved folder for this video + subtitle + language + voice
     * setting. Lines made with a different voice setting, or with a different result of the
     * voice scan, live in their own folder, so two kinds of voice are never mixed.
     * Returns null when the phone's storage cannot be used.
     */
    fun openSession(
        context: Context,
        videoKey: String,
        cues: List<DubCue>,
        lang: String,
        mode: VoiceMode,
        smart: Boolean,
        scan: VoiceScanResult?,
    ): Session? {
        try {
            val top = videoDir(context, videoKey, cues)
            val scanTag = if (scan == null) "n" else sha(scan.serialize(), 8)
            val clips = File(
                top,
                "clips_" + safe(lang) + "_" + mode.name + "_" + (if (smart) "s" else "p") +
                    (if (DubPrefs.fast(context)) "f" else "") + "_" + scanTag,
            )
            clips.mkdirs()
            if (!clips.isDirectory) return null
            trim(context, top)
            top.setLastModified(System.currentTimeMillis())
            return Session(clips, File(top, "tr_" + safe(lang) + ".txt"))
        } catch (e: Throwable) {
            return null
        }
    }

    // ───────────────────────── voice scan ─────────────────────────

    fun loadScan(context: Context, videoKey: String, cues: List<DubCue>, offsetMs: Long): VoiceScanResult? {
        try {
            val f = File(videoDir(context, videoKey, cues), "scan_$offsetMs.txt")
            if (!f.exists()) return null
            return VoiceScanResult.parse(f.readText(Charsets.UTF_8), cues.size)
        } catch (e: Throwable) {
            return null
        }
    }

    fun saveScan(context: Context, videoKey: String, cues: List<DubCue>, offsetMs: Long, scan: VoiceScanResult) {
        try {
            val top = videoDir(context, videoKey, cues)
            top.mkdirs()
            File(top, "scan_$offsetMs.txt").writeText(scan.serialize(), Charsets.UTF_8)
            top.setLastModified(System.currentTimeMillis())
        } catch (e: Throwable) {
            // not saving is never a reason to stop the dub
        }
    }

    // ───────────────────────── resume ─────────────────────────

    private fun resumeFile(context: Context, videoKey: String): File =
        File(root(context), "resume_" + sha(videoKey, 16) + ".txt")

    private fun resumeSubFile(context: Context, videoKey: String): File =
        File(root(context), "resume_" + sha(videoKey, 16) + ".sub")

    /** Remembers that this video is dubbed, with the settings and the subtitle text used. */
    fun saveResume(
        context: Context,
        videoKey: String,
        lang: String,
        mode: VoiceMode,
        duck: Float,
        multi: Boolean,
        raw: String,
    ) {
        try {
            resumeSubFile(context, videoKey).writeText(raw, Charsets.UTF_8)
            resumeFile(context, videoKey)
                .writeText("v1|" + lang + "|" + mode.name + "|" + duck + "|" + multi, Charsets.UTF_8)
        } catch (e: Throwable) {
            // not saving is never a reason to stop the dub
        }
    }

    fun loadResume(context: Context, videoKey: String): Resume? {
        try {
            val f = resumeFile(context, videoKey)
            val s = resumeSubFile(context, videoKey)
            if (!f.exists() || !s.exists()) return null
            val p = f.readText(Charsets.UTF_8).trim().split('|')
            if (p.size < 5 || p[0] != "v1") return null
            val raw = s.readText(Charsets.UTF_8)
            if (raw.isBlank()) return null
            return Resume(p[1], VoiceMode.valueOf(p[2]), p[3].toFloat(), p[4].toBoolean(), raw)
        } catch (e: Throwable) {
            return null
        }
    }

    /** The person stopped this video's dub on purpose: it must not come back by itself. */
    fun clearResume(context: Context, videoKey: String) {
        try {
            resumeFile(context, videoKey).delete()
            resumeSubFile(context, videoKey).delete()
        } catch (e: Throwable) {
            // nothing to do
        }
    }

    /** True when something of this video's dub is saved (lines or the voice scan). */
    fun hasSaved(context: Context, videoKey: String, cues: List<DubCue>): Boolean {
        try {
            val top = videoDir(context, videoKey, cues)
            if (!top.isDirectory) return false
            return top.walkTopDown().any { it.isFile }
        } catch (e: Throwable) {
            return false
        }
    }

    // ───────────────────────── housekeeping ─────────────────────────

    private fun sizeOf(f: File): Long {
        if (f.isFile) return f.length()
        var n = 0L
        val kids = f.listFiles() ?: return 0L
        for (k in kids) n += sizeOf(k)
        return n
    }

    /** Removes the least recently used videos when the saved dubs grow too big. */
    private fun trim(context: Context, keep: File) {
        val all = root(context).listFiles() ?: return
        val now = System.currentTimeMillis()
        for (f in all) {
            // old resume notes of videos nobody opened for months
            if (f.isFile && f.name.startsWith("resume_") && now - f.lastModified() > RESUME_MAX_AGE_MS) {
                f.delete()
            }
        }
        val dirs = all.filter { it.isDirectory }
        var total = 0L
        val sizes = HashMap<File, Long>()
        for (d in dirs) {
            val s = sizeOf(d)
            sizes[d] = s
            total += s
        }
        if (total <= MAX_BYTES) return
        for (d in dirs.sortedBy { it.lastModified() }) {
            if (d == keep) continue
            total -= sizes[d] ?: 0L
            d.deleteRecursively()
            if (total <= MAX_BYTES * 3L / 4L) break
        }
    }
}
