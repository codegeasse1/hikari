package com.hikari.app.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import com.google.android.gms.tasks.Task
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Manga page translation ("Translate / Dub"): detect the text on pages and
 * replace it with a translation, in place over the art.
 *
 * Three engines, best-first: ML Kit (on-device, basic quality, first use
 * downloads the language models), Google Translate (online, good quality,
 * keyless) and Gemini (online, best quality — reads the whole bubble list
 * with context — needs the user's own API key).
 */
enum class MangaTranslateEngine(val title: String, val subtitle: String) {
    MLKIT("ML Kit", "Offline, basic quality"),
    GOOGLE("Google Translate", "Online, good quality"),
    GEMINI("Gemini", "Best quality, needs an API key"),
}

/** Source-text language of the page. */
enum class MangaOrigLang(val code: String, val label: String) {
    AUTO("auto", "Auto detect"),
    JA("ja", "Japanese"),
    ZH("zh", "Chinese"),
    KO("ko", "Korean"),
    HI("hi", "Hindi"),
    EN("en", "English"),
}

/** Language the page text is translated into. */
enum class MangaTargetLang(val code: String, val label: String) {
    EN("en", "English"),
    JA("ja", "Japanese"),
    KO("ko", "Korean"),
    ZH("zh", "Chinese"),
    BN("bn", "Bengali"),
    HI("hi", "Hindi"),
    ID("id", "Indonesian"),
    ES("es", "Spanish"),
    PT("pt", "Portuguese"),
    RU("ru", "Russian"),
    AR("ar", "Arabic"),
    FR("fr", "French"),
    DE("de", "German"),
    TR("tr", "Turkish"),
}

/** The whole translate surface as one JSON blob (global or per-series). */
data class MangaTranslateSettings(
    val enabled: Boolean = false,
    val orig: MangaOrigLang = MangaOrigLang.AUTO,
    val target: MangaTargetLang = MangaTargetLang.EN,
    val engine: MangaTranslateEngine = MangaTranslateEngine.GOOGLE,
    val geminiKey: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("orig", orig.name)
        .put("target", target.name)
        .put("engine", engine.name)
        .put("geminiKey", geminiKey)

    companion object {
        val DEFAULTS = MangaTranslateSettings()

        fun fromJson(raw: String?): MangaTranslateSettings {
            if (raw.isNullOrBlank()) return DEFAULTS
            return try {
                val o = JSONObject(raw)
                DEFAULTS.copy(
                    enabled = o.optBoolean("enabled", false),
                    orig = o.optString("orig").let { n ->
                        MangaOrigLang.entries.firstOrNull { it.name == n } ?: MangaOrigLang.AUTO
                    },
                    target = o.optString("target").let { n ->
                        MangaTargetLang.entries.firstOrNull { it.name == n } ?: MangaTargetLang.EN
                    },
                    engine = o.optString("engine").let { n ->
                        MangaTranslateEngine.entries.firstOrNull { it.name == n }
                            ?: MangaTranslateEngine.GOOGLE
                    },
                    geminiKey = o.optString("geminiKey", ""),
                )
            } catch (e: Exception) {
                DEFAULTS
            }
        }
    }
}

/**
 * What the reader settings sheet edits: values plus the writes, so the
 * chrome (which only forwards one object) stays a dumb pass-through. With
 * the sheet's "apply to this manga only" switch on, the screen routes every
 * write to that manga's own blob instead of the global one.
 */
data class MangaTranslateUi(
    val enabled: Boolean,
    val onToggle: () -> Unit,
    val orig: MangaOrigLang,
    val onOrig: (MangaOrigLang) -> Unit,
    val target: MangaTargetLang,
    val onTarget: (MangaTargetLang) -> Unit,
    val engine: MangaTranslateEngine,
    val onEngine: (MangaTranslateEngine) -> Unit,
    val geminiKey: String,
    val onSaveKey: (String) -> Unit,
)

/** One translated text block, in full-image pixels. [bl,bt,br,bb] is the
 *  detected white bubble outline around the text — the overlay never draws
 *  past it (falls back to the text box when detection finds nothing). */
data class TLBox(
    val l: Float,
    val t: Float,
    val r: Float,
    val b: Float,
    val text: String,
    val trans: String,
    val bl: Float = 0f,
    val bt: Float = 0f,
    val br: Float = 0f,
    val bb: Float = 0f,
)

private data class RawBox(val l: Float, val t: Float, val r: Float, val b: Float, val text: String)

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { r -> if (!c.isCompleted) c.resume(r) }
    addOnFailureListener { e -> if (!c.isCompleted) c.resumeWithException(e ?: Exception("mlkit task failed")) }
}

/**
 * OCR + translation pipeline with a per-page disk cache. Single-flighted per
 * page+settings so the visible holders asking together decode once; every
 * result (including "no text") is cached, so scrolling back costs nothing.
 */
object MangaTranslateRuntime {
    /**
     * Flipping this (the reader settings sheet writes it on every toggle)
     * pings every visible page holder so the open page translates instantly
     * when turned on — and drops its overlay instantly when turned off —
     * with no scrolling needed.
     */
    @Volatile
    var settings: MangaTranslateSettings = MangaTranslateSettings.DEFAULTS
        set(v) {
            field = v
            notifyChanged()
        }

    private val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    /** Visible page holders register here; fired on every settings write. */
    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    private fun notifyChanged() {
        for (l in listeners) runCatching { l() }
    }

    private var dir: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inflight = ConcurrentHashMap<String, Deferred<List<TLBox>>>()

    /** Point at the app's files dir once (the cache lives under it). */
    fun configure(ctx: Context) {
        if (dir == null) {
            dir = File(ctx.applicationContext.filesDir, "manga_translate").apply { mkdirs() }
            scope.launchBlock { prune() }
        }
    }

    private fun CoroutineScope.launchBlock(block: suspend () -> Unit) {
        val job = this.async { block() }
        job.invokeOnCompletion { /* fire and forget */ }
    }

    /**
     * Translated blocks for one page image, in full-image pixels. Empty when
     * translation is off, the page has no text, or every engine failed — the
     * caller then simply draws no overlay.
     */
    suspend fun translatePage(
        pageKey: String,
        file: File,
        imgW: Int,
        imgH: Int,
        st: MangaTranslateSettings = settings,
    ): List<TLBox> {
        if (!st.enabled || imgW <= 0 || imgH <= 0) return emptyList()
        val d = dir ?: return emptyList()
        val hk = sha1("$pageKey|${st.orig.name}|${st.target.name}|${st.engine.name}|${st.geminiKey}|v4")
        val cacheFile = File(d, "$hk.json")
        readCache(cacheFile)?.let { return it }
        val def = inflight.getOrPut(hk) { scope.async { runTranslate(file, imgW, imgH, st) } }
        return try {
            val result = def.await()
            if (result.isNotEmpty()) writeCache(cacheFile, result)
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        } finally {
            if (def.isCompleted) inflight.remove(hk, def)
        }
    }

    private suspend fun runTranslate(
        file: File,
        imgW: Int,
        imgH: Int,
        st: MangaTranslateSettings,
    ): List<TLBox> = withContext(Dispatchers.IO) {
        val raw = try {
            ocrPage(file, imgW, imgH, st.orig)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext emptyList()
        }
        if (raw.isEmpty()) return@withContext emptyList()
        // Stacked lines of one bubble become ONE block: translating them
        // together keeps the sentence whole and renders one clean bubble.
        val grouped = groupBubbles(raw)
        if (grouped.isEmpty()) return@withContext emptyList()
        val texts = grouped.map { cleanOcr(it.text) }
        // ALL-CAPS shouting ("LET GO! LET ME GO!") translates badly as-is:
        // Google keeps words like GO in Latin. Sentence-case it for the
        // engine; the equality check below still uses the original.
        val sendTexts = texts.map { normalizeCaps(it) }
        var translated = try {
            translateAll(sendTexts, st)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext emptyList()
        }
        // Second chance for boxes whose translation still leaks source-script
        // words (e.g. Hindi "मैं GO वापस..."): retry that one box alone in
        // sentence case. Whatever still leaks after the retry is dropped.
        if (st.engine == MangaTranslateEngine.GOOGLE) {
            val sl = if (st.orig == MangaOrigLang.AUTO) "auto" else st.orig.code
            translated = translated.mapIndexed { i, tr ->
                val t = tr?.trim().orEmpty()
                if (t.isNotBlank() && hasLeftoverSource(t, texts.getOrNull(i).orEmpty(), st.target)) {
                    gtxOne(normalizeCaps(texts.getOrNull(i).orEmpty()), sl, st.target.code)?.trim()
                        ?.takeIf { it.isNotEmpty() } ?: tr
                } else tr
            }
        }
        // White bubble outline around each box: the overlay fits the words
        // INSIDE this outline and shrinks the font instead of growing past
        // it, so text can never spill onto the art (the 0.12.35 overflow).
        val bubbles = detectBubbles(file, imgW, imgH, grouped)
        grouped.mapIndexedNotNull { i, b ->
            val raw = translated.getOrNull(i)?.trim().orEmpty()
            val t = cleanTrans(raw)
            if (t.isBlank() || t.equals(texts.getOrNull(i), ignoreCase = true)) null
            else if (!hasTargetScript(t, st.target)) null
            else if (hasLeftoverSource(t, texts.getOrNull(i).orEmpty(), st.target)) null
            else {
                val o = bubbles.getOrNull(i)
                if (o != null) TLBox(b.l, b.t, b.r, b.b, b.text, t, o.l, o.t, o.r, o.b)
                else TLBox(b.l, b.t, b.r, b.b, b.text, t)
            }
        }
    }

    /**
     * Batch text translation with the runtime's settings (also used by the
     * novel reader for plain paragraphs).
     */
    suspend fun translateTexts(texts: List<String>, st: MangaTranslateSettings): List<String?> =
        translateAll(texts, st)

    /** Strip batch-numbering and quote junk the engines echo. */
    private fun cleanTrans(t: String): String {
        var s = t.trim()
        s = s.replace(Regex("""^\s*(?:\d{1,3}[.)]\s*)+"""), "")
        s = s.replace(Regex("""^['"`:;,.\s\-]+"""), "")
        s = s.replace(Regex("""['"`:;,.\s\-]+$"""), "")
        return s.replace(Regex("""\s+"""), " ").trim()
    }

    /** Non-Latin targets must contain the target script, else the box is an untranslated echo. */
    private fun hasTargetScript(t: String, target: MangaTargetLang): Boolean {
        val range = when (target) {
            MangaTargetLang.HI -> '\u0900'..'\u097F'
            MangaTargetLang.BN -> '\u0980'..'\u09FF'
            MangaTargetLang.AR -> '\u0600'..'\u06FF'
            MangaTargetLang.RU -> '\u0400'..'\u04FF'
            else -> return true
        }
        return t.any { it in range }
    }


    // ---- OCR ----

    private val latinRec: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val jaRec: TextRecognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }
    private val koRec: TextRecognizer by lazy {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }
    private val zhRec: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    private val hiRec: TextRecognizer by lazy {
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    }

    private fun recFor(script: Script): TextRecognizer = when (script) {
        Script.LATIN -> latinRec
        Script.JA -> jaRec
        Script.KO -> koRec
        Script.ZH -> zhRec
        Script.HI -> hiRec
    }

    private enum class Script { LATIN, JA, KO, ZH, HI }

    private fun scriptOf(text: String): Script {
        var ja = 0; var ko = 0; var zh = 0; var hi = 0; var latin = 0
        for (ch in text) {
            when (ch) {
                in '\u3040'..'\u30ff', in '\uff00'..'\uffef' -> ja++
                in '\uac00'..'\ud7af', in '\u1100'..'\u11ff' -> ko++
                in '\u0900'..'\u097f' -> hi++
                in '\u4e00'..'\u9fff', in '\u3400'..'\u4dbf' -> zh++
                in 'a'..'z', in 'A'..'Z' -> latin++
            }
        }
        // Kana decides Japanese even with kanji around; otherwise majority wins.
        if (ja > 0) return Script.JA
        if (ko >= zh && ko >= hi && ko >= latin) return if (ko > 0) Script.KO else Script.LATIN
        if (hi >= zh && hi >= latin) return if (hi > 0) Script.HI else Script.LATIN
        if (zh >= latin) return if (zh > 0) Script.ZH else Script.LATIN
        return Script.LATIN
    }

    /**
     * OCR over vertical tiles (a strip can be 15k px tall — never one
     * bitmap). Boxes come back in full-image pixels, one [RawBox] per text
     * block (roughly one bubble).
     */
    private suspend fun ocrPage(file: File, imgW: Int, imgH: Int, orig: MangaOrigLang): List<RawBox> {
        val dec = try {
            BitmapRegionDecoder.newInstance(file.absolutePath, false) ?: return emptyList()
        } catch (e: Exception) {
            return emptyList()
        }
        try {
            val w = if (dec.width > 0) dec.width else imgW
            val h = if (dec.height > 0) dec.height else imgH
            if (w <= 0 || h <= 0) return emptyList()
            val sample = max(1, ceil(w / 1600.0).toInt())
            val tw = w / sample
            val tileH = 1800
            val overlap = 80
            val tiles = ArrayList<Pair<Int, Bitmap>>()
            var y = 0
            while (y * 1L * sample < h) {
                val y0s = y
                val y1s = min(y + tileH, ceil(h.toDouble() / sample).toInt().coerceAtLeast(1))
                val rect = Rect(0, y0s * sample, w, min(h, y1s * sample))
                if (rect.height() <= 0) break
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = try {
                    dec.decodeRegion(rect, opts)
                } catch (e: Exception) {
                    null
                }
                if (bmp != null) tiles.add(y0s to bmp)
                y = y1s - overlap
                if (y1s * 1L * sample >= h) break
                if (tiles.size > 24) break
            }
            if (tiles.isEmpty()) return emptyList()
            val fixed = when (orig) {
                MangaOrigLang.JA -> Script.JA
                MangaOrigLang.KO -> Script.KO
                MangaOrigLang.ZH -> Script.ZH
                MangaOrigLang.HI -> Script.HI
                MangaOrigLang.EN -> Script.LATIN
                MangaOrigLang.AUTO -> null
            }
            val script = fixed ?: detectScript(tiles)
            val rec = recFor(script)
            val out = ArrayList<RawBox>()
            for ((y0s, bmp) in tiles) {
                try {
                    val text = rec.process(InputImage.fromBitmap(bmp, 0)).awaitTask()
                    for (block in text.textBlocks) {
                        val box = block.boundingBox ?: continue
                        val t = block.text?.trim().orEmpty()
                        if (t.length < 2) continue
                        out.add(
                            RawBox(
                                l = (box.left + 0) * sample.toFloat(),
                                t = (y0s + box.top) * sample.toFloat(),
                                r = (box.right) * sample.toFloat(),
                                b = (y0s + box.bottom) * sample.toFloat(),
                                text = t,
                            )
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // One bad tile never kills the page.
                } finally {
                    if (!bmp.isRecycled) bmp.recycle()
                }
            }
            return mergeRows(out)
        } finally {
            try {
                dec.recycle()
            } catch (e: Exception) {
            }
        }
    }

    /** First tile through Latin: CJK text reveals its own script. */
    private suspend fun detectScript(tiles: List<Pair<Int, Bitmap>>): Script {
        val (_, first) = tiles.first()
        return try {
            val text = latinRec.process(InputImage.fromBitmap(first, 0)).awaitTask()
            val probe = text.textBlocks.joinToString("\n") { it.text ?: "" }.take(2000)
            val s = scriptOf(probe)
            if (s != Script.LATIN || probe.trim().length >= 4) s else Script.LATIN
        } catch (e: Exception) {
            Script.LATIN
        }
    }

    /**
     * Tiles overlap, so a bubble on a seam is seen twice: fold boxes that
     * overlap heavily, keeping the taller read.
     */
    private fun mergeRows(boxes: List<RawBox>): List<RawBox> {
        if (boxes.isEmpty()) return boxes
        val sorted = boxes.sortedWith(compareBy({ it.t }, { it.l }))
        val out = ArrayList<RawBox>()
        for (b in sorted) {
            val last = out.lastOrNull()
            if (last != null && overlaps(last, b)) {
                if (area(b) > area(last)) out[out.lastIndex] = b
            } else {
                out.add(b)
            }
        }
        return out
    }

    private fun overlaps(a: RawBox, b: RawBox): Boolean {
        val ix = min(a.r, b.r) - max(a.l, b.l)
        val iy = min(a.b, b.b) - max(a.t, b.t)
        if (ix <= 0 || iy <= 0) return false
        val ia = ix * iy
        return ia > 0.5f * min(area(a), area(b))
    }

    private fun area(b: RawBox): Float = max(0f, b.r - b.l) * max(0f, b.b - b.t)

    /**
     * White bubble outline around each text box, in full-image pixels (null
     * when nothing sane is found). The bubble interior is the white connected
     * region holding the text: flood-fill it on a small downscaled crop and
     * take its bounds, inset slightly so the overlay stays inside the ink.
     * Rejects regions touching 3+ crop edges (that's page background, not a
     * bubble) and tiny slivers (a gap between letters, not the bubble).
     */
    private fun detectBubbles(file: File, imgW: Int, imgH: Int, boxes: List<RawBox>): List<RectF?> {
        if (boxes.isEmpty()) return emptyList()
        val dec = try {
            BitmapRegionDecoder.newInstance(file.absolutePath, false)
        } catch (e: Exception) {
            null
        } ?: return boxes.map { null }
        return try {
            val w = if (dec.width > 0) dec.width else imgW
            val h = if (dec.height > 0) dec.height else imgH
            if (w <= 0 || h <= 0) boxes.map { null }
            else boxes.map { detectOne(dec, w, h, it) }
        } catch (e: Exception) {
            boxes.map { null }
        } finally {
            try {
                dec.recycle()
            } catch (e: Exception) {
            }
        }
    }

    private fun detectOne(dec: BitmapRegionDecoder, w: Int, h: Int, b: RawBox): RectF? {
        return try {
            val bw = (b.r - b.l).coerceAtLeast(1f)
            val bh = (b.b - b.t).coerceAtLeast(1f)
            // Crop generously so the whole bubble is inside, but capped so a
            // neighbouring bubble can't merge in through the crop.
            val ex = bw * 1.1f
            val ey = bh * 1.1f
            val cl = max(0f, b.l - ex).toInt()
            val ct = max(0f, b.t - ey).toInt()
            val cr = min(w.toFloat(), b.r + ex).toInt()
            val cb = min(h.toFloat(), b.b + ey).toInt()
            val cw = cr - cl
            val ch = cb - ct
            if (cw < 16 || ch < 16) return null
            val scale = min(1f, 160f / max(cw, ch))
            val sw = max(10, (cw * scale).toInt())
            val sh = max(10, (ch * scale).toInt())
            val sample = max(1, min(cw / sw, ch / sh))
            val bmp = dec.decodeRegion(
                Rect(cl, ct, cr, cb),
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return null
            try {
                val dw = bmp.width
                val dh = bmp.height
                if (dw < 10 || dh < 10) return null
                val px = IntArray(dw * dh)
                bmp.getPixels(px, 0, dw, 0, 0, dw, dh)
                val white = BooleanArray(dw * dh)
                for (i in px.indices) {
                    val p = px[i]
                    white[i] = ((p shr 16) and 0xff) > 195 &&
                        ((p shr 8) and 0xff) > 195 && (p and 0xff) > 195
                }
                // Seeds: white pixels overlapping the OCR text box (crop space).
                // The text sits inside its bubble, so the fill starts there.
                val tl = (((b.l - cl).toFloat() / cw * dw).toInt()).coerceIn(0, dw - 1)
                val tr = (((b.r - cl).toFloat() / cw * dw).toInt()).coerceIn(0, dw - 1)
                val tt = (((b.t - ct).toFloat() / ch * dh).toInt()).coerceIn(0, dh - 1)
                val tb = (((b.b - ct).toFloat() / ch * dh).toInt()).coerceIn(0, dh - 1)
                val seen = BooleanArray(dw * dh)
                val queue = ArrayDeque<Int>()
                for (y in tt..tb) {
                    for (x in tl..tr) {
                        val i = y * dw + x
                        if (white[i] && !seen[i]) {
                            seen[i] = true
                            queue.add(i)
                        }
                    }
                }
                if (queue.isEmpty()) {
                    // Text pixels aren't white (dark bubble?): try the box
                    // center — a white interior still fills from there.
                    val cx = (tl + tr) / 2
                    val cy = (tt + tb) / 2
                    val i = cy * dw + cx
                    if (!white[i]) return null
                    seen[i] = true
                    queue.add(i)
                }
                var minX = dw
                var minY = dh
                var maxX = -1
                var maxY = -1
                var count = 0
                var touches = 0
                var touchL = false
                var touchR = false
                var touchT = false
                var touchB = false
                while (queue.isNotEmpty()) {
                    val i = queue.removeFirst()
                    val x = i % dw
                    val y = i / dw
                    count++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                    if (x == 0) touchL = true
                    if (x == dw - 1) touchR = true
                    if (y == 0) touchT = true
                    if (y == dh - 1) touchB = true
                    if (x > 0 && white[i - 1] && !seen[i - 1]) {
                        seen[i - 1] = true
                        queue.add(i - 1)
                    }
                    if (x < dw - 1 && white[i + 1] && !seen[i + 1]) {
                        seen[i + 1] = true
                        queue.add(i + 1)
                    }
                    if (y > 0 && white[i - dw] && !seen[i - dw]) {
                        seen[i - dw] = true
                        queue.add(i - dw)
                    }
                    if (y < dh - 1 && white[i + dw] && !seen[i + dw]) {
                        seen[i + dw] = true
                        queue.add(i + dw)
                    }
                }
                touches = (if (touchL) 1 else 0) + (if (touchR) 1 else 0) +
                    (if (touchT) 1 else 0) + (if (touchB) 1 else 0)
                // Page background floods the whole crop (3-4 edges); a real
                // bubble interior touches at most the side it opens through.
                if (touches >= 3) return null
                // Sliver between glyphs, not a bubble.
                if (count < dw * dh * 0.02f) return null
                if (maxX < minX || maxY < minY) return null
                // Back to full-image pixels, inset so the oval stays inside ink.
                val fl = cl + minX.toFloat() / dw * cw
                val ft = ct + minY.toFloat() / dh * ch
                val fr = cl + (maxX + 1).toFloat() / dw * cw
                val fb = ct + (maxY + 1).toFloat() / dh * ch
                val ix = max(2f, (fr - fl) * 0.03f)
                val iy = max(2f, (fb - ft) * 0.03f)
                val out = RectF(fl + ix, ft + iy, fr - ix, fb - iy)
                // Must still contain the text, with sane proportions.
                if (out.right - out.left < bw * 0.5f) return null
                if (out.bottom - out.top < bh * 0.5f) return null
                if (out.left > b.l || out.top > b.t || out.right < b.r || out.bottom < b.b) {
                    // Outline missed the text (outline gap leaked out): clamp
                    // to a padded text box instead of a wrong bubble.
                    return RectF(b.l - bw * 0.15f, b.t - bh * 0.15f, b.r + bw * 0.15f, b.b + bh * 0.15f)
                }
                out
            } finally {
                if (!bmp.isRecycled) bmp.recycle()
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Stacked text lines of one speech bubble become one block (union bounds,
     * words joined). Thresholds stay conservative: neighbouring bubbles keep
     * their own blocks.
     */
    private fun groupBubbles(boxes: List<RawBox>): List<RawBox> {
        if (boxes.size < 2) return boxes
        val sorted = boxes.sortedWith(compareBy({ it.t }, { it.l }))
        val groups = ArrayList<MutableList<RawBox>>()
        for (b in sorted) {
            val g = groups.lastOrNull()
            if (g != null && sameBubble(g.last(), b)) g.add(b)
            else groups.add(mutableListOf(b))
        }
        return groups.map { g ->
            if (g.size == 1) g[0]
            else RawBox(
                l = g.minOf { it.l }, t = g.minOf { it.t },
                r = g.maxOf { it.r }, b = g.maxOf { it.b },
                text = g.joinToString(" ") { it.text },
            )
        }
    }

    /** True when [b] reads as the next line inside [a]'s bubble. */
    private fun sameBubble(a: RawBox, b: RawBox): Boolean {
        val lineH = min(a.b - a.t, b.b - b.t).coerceAtLeast(1f)
        // A side-by-side bubble starts way above a's bottom; a SEPARATE joint
        // bubble starts below the outline gap. A next line inside the SAME
        // bubble starts right around a's bottom, well aligned with it.
        if (b.t < a.b - lineH * 0.6f) return false
        if (b.t > a.b + lineH * 1.0f) return false
        val inter = min(a.r, b.r) - max(a.l, b.l)
        if (inter <= 0) return false
        val narrow = min(a.r - a.l, b.r - b.l).coerceAtLeast(1f)
        if (inter < narrow * 0.5f) return false
        // Joint bubbles sit side-by-side / diagonally: their centers drift
        // apart even when the x-ranges touch. Lines of one bubble share a center.
        val ca = (a.l + a.r) / 2f
        val cb = (b.l + b.r) / 2f
        val wide = max(a.r - a.l, b.r - b.l).coerceAtLeast(1f)
        return kotlin.math.abs(cb - ca) <= wide * 0.6f
    }

    /** OCR joins lines with odd spacing — normalize before translating. */
    private fun cleanOcr(t: String): String {
        return t.replace(Regex("\\s+"), " ")
            .replace(Regex("\\s+([,.!?;:%)\\]])"), "$1")
            .replace(Regex("([(\\[])\\s+"), "$1")
            .trim()
    }

    /**
     * Shout-case ("LET GO! LET ME GO!") makes Google echo words back in
     * Latin ("मैं GO वापस..."). When 70%+ of the letters are uppercase,
     * send sentence case instead — same words, far fuller translation.
     */
    private fun normalizeCaps(t: String): String {
        var letters = 0; var upper = 0
        for (ch in t) {
            if (ch.isLetter()) {
                letters++
                if (ch.isUpperCase()) upper++
            }
        }
        if (letters < 3 || upper * 10 < letters * 7) return t
        // Sentence case without regex: capitalize the first letter and any
        // letter following a sentence-ending mark.
        val lower = t.lowercase()
        val sb = StringBuilder(lower.length)
        var capNext = true
        for (ch in lower) {
            sb.append(if (capNext && ch.isLetter()) ch.uppercaseChar() else ch)
            if (ch == '.' || ch == '!' || ch == '?' || ch == '।' || ch == '。') capNext = true
            else if (ch.isLetter()) capNext = false
        }
        return sb.toString()
    }

    /**
     * True when the translation still carries source-script words (e.g. a
     * Latin "GO" inside a Hindi reply that also matches the source box).
     * Single letters and numbers never count — only words of 2+ letters.
     */
    private fun hasLeftoverSource(trans: String, src: String, target: MangaTargetLang): Boolean {
        val needLatinFree = when (target) {
            MangaTargetLang.HI, MangaTargetLang.BN,
            MangaTargetLang.AR, MangaTargetLang.RU -> true
            else -> false
        }
        if (!needLatinFree) return false
        val srcWords = Regex("[A-Za-z]{2,}").findAll(src).map { it.value.lowercase() }.toSet()
        if (srcWords.isEmpty()) return false
        val transWords = Regex("[A-Za-z]{2,}").findAll(trans).map { it.value.lowercase() }.toSet()
        return transWords.any { it in srcWords }
    }

    // ---- Translation ----

    private fun mlCode(code: String): String = when (code) {
        "en" -> TranslateLanguage.ENGLISH
        "bn" -> TranslateLanguage.BENGALI
        "hi" -> TranslateLanguage.HINDI
        "id" -> TranslateLanguage.INDONESIAN
        "es" -> TranslateLanguage.SPANISH
        "pt" -> TranslateLanguage.PORTUGUESE
        "ru" -> TranslateLanguage.RUSSIAN
        "ar" -> TranslateLanguage.ARABIC
        "fr" -> TranslateLanguage.FRENCH
        "de" -> TranslateLanguage.GERMAN
        "tr" -> TranslateLanguage.TURKISH
        "ja" -> TranslateLanguage.JAPANESE
        "ko" -> TranslateLanguage.KOREAN
        "zh" -> TranslateLanguage.CHINESE
        else -> TranslateLanguage.ENGLISH
    }

    private val translators = ConcurrentHashMap<String, Translator>()

    private suspend fun translateAll(texts: List<String>, st: MangaTranslateSettings): List<String?> {
        return when (st.engine) {
            MangaTranslateEngine.MLKIT -> translateMlKit(texts, st)
            MangaTranslateEngine.GOOGLE -> translateGoogle(texts, st)
            MangaTranslateEngine.GEMINI -> translateGemini(texts, st)
        }
    }

    private suspend fun translateMlKit(texts: List<String>, st: MangaTranslateSettings): List<String?> =
        withContext(Dispatchers.IO) {
            val src = if (st.orig == MangaOrigLang.AUTO) {
                identify(texts.firstOrNull { it.isNotBlank() }.orEmpty()) ?: return@withContext texts.map { null }
            } else {
                mlCode(st.orig.code)
            }
            val tgt = mlCode(st.target.code)
            if (src == tgt) return@withContext texts.map { it }
            val key = "$src|$tgt"
            val client = translators.getOrPut(key) {
                Translation.getClient(
                    TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build()
                )
            }
            try {
                // First use downloads the language models (any network is fine).
                client.downloadModelIfNeeded(
                    com.google.mlkit.common.model.DownloadConditions.Builder().build()
                ).awaitTask()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withContext texts.map { null }
            }
            texts.map { t ->
                if (t.isBlank()) null else try {
                    client.translate(t).awaitTask()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
        }

    private val langIdentifier by lazy { LanguageIdentification.getClient() }

    private suspend fun identify(text: String): String? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext null
        try {
            val tag = langIdentifier.identifyLanguage(text).awaitTask()
            if (tag == "und") null else mlCode(tag.substringBefore('-'))
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun translateGoogle(texts: List<String>, st: MangaTranslateSettings): List<String?> =
        withContext(Dispatchers.IO) {
            val sl = if (st.orig == MangaOrigLang.AUTO) "auto" else st.orig.code
            // Sentence-case shouty lines before sending (see normalizeCaps):
            // novel paras ride this same path and shout too.
            val send = texts.map { if (it.isBlank()) it else normalizeCaps(it) }
            val nonBlank = send.mapIndexedNotNull { i, t -> if (t.isBlank()) null else i to t }
            if (nonBlank.isEmpty()) return@withContext texts.map { null }
            // One batched call so the lines are translated with each other as
            // context, like speech in the same scene — then mapped back by number.
            // Long batches are split so the GET URL never blows past the limit
            // (the novel reader's 40-para chunks did exactly that: batch 414s,
            // every per-para retry too long as well, so Google "did nothing").
            val numbered = nonBlank.joinToString("\n") { (i, t) -> "${i + 1}. ${t.replace('\n', ' ')}" }
            val mapped = parseNumbered(gtxChunked(numbered, sl, st.target.code), nonBlank.size)
            if (mapped != null) {
                val result: MutableList<String?> = texts.map { null }.toMutableList()
                for ((i, _) in nonBlank) result[i] = mapped[i]
                // Lines the batch dropped get one call each.
                for ((i, t) in nonBlank) {
                    if (result[i] == null) result[i] = gtxChunked(t, sl, st.target.code)
                }
                return@withContext result
            }
            // Fallback: one call per line.
            texts.mapIndexed { i, t -> if (t.isBlank()) null else gtxChunked(send[i], sl, st.target.code) }
        }

    /** "1. ..." numbered reply -> index map. Null when too few lines survived. */
    private fun parseNumbered(reply: String?, count: Int): Map<Int, String>? {
        if (reply.isNullOrBlank()) return null
        val lineRe = Regex("""^\s*(\d+)[.)]\s?(.*)$""")
        val mapped = HashMap<Int, String>()
        for (line in reply.lines()) {
            val m = lineRe.find(line.trimEnd()) ?: continue
            val n = m.groupValues[1].toIntOrNull() ?: continue
            val v = m.groupValues[2].trim()
            if (v.isNotEmpty()) mapped[n - 1] = v
        }
        return if (mapped.size >= count / 2) mapped else null
    }

    private fun gtxOne(text: String, sl: String, tl: String): String? {
        return try {
            val q = URLEncoder.encode(text, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sl&tl=$tl&dt=t&q=$q"
            val raw = com.hikari.app.net.Http.getStringQuiet(url) ?: return null
            val root = JSONArray(raw)
            val segs = root.optJSONArray(0) ?: return null
            val sb = StringBuilder()
            for (i in 0 until segs.length()) {
                sb.append(segs.optJSONArray(i)?.optString(0).orEmpty())
            }
            sb.toString().trim().ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Long text (novel paragraphs, big batches) blows past the GET URL
     * limit and Google answers 414 — the "Google does nothing" bug. Split
     * on sentence boundaries into ~1200-char pieces, translate each, join.
     */
    private fun gtxChunked(text: String, sl: String, tl: String): String? {
        if (text.length <= 1500) return gtxOne(text, sl, tl)
        val parts = ArrayList<String>()
        val sb = StringBuilder()
        val sentences = text.split(Regex("(?<=[.!?।。…])\\s+"))
        for (s in sentences) {
            if (sb.length + s.length + 1 > 1200 && sb.isNotEmpty()) {
                parts.add(sb.toString())
                sb.clear()
            }
            if (s.length > 1200) {
                if (sb.isNotEmpty()) {
                    parts.add(sb.toString())
                    sb.clear()
                }
                var k = 0
                while (k < s.length) {
                    parts.add(s.substring(k, min(k + 1200, s.length)))
                    k += 1200
                }
            } else {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(s)
            }
        }
        if (sb.isNotEmpty()) parts.add(sb.toString())
        if (parts.isEmpty()) return gtxOne(text, sl, tl)
        val out = StringBuilder()
        for (p in parts) {
            val tr = gtxOne(p, sl, tl) ?: return null
            if (out.isNotEmpty()) out.append(' ')
            out.append(tr)
        }
        return out.toString().trim().ifEmpty { null }
    }

    private suspend fun translateGemini(texts: List<String>, st: MangaTranslateSettings): List<String?> =
        withContext(Dispatchers.IO) {
            val key = st.geminiKey.trim()
            if (key.isEmpty()) return@withContext texts.map { null }
            val nonBlank = texts.mapIndexedNotNull { i, t -> if (t.isBlank()) null else i to t }
            if (nonBlank.isEmpty()) return@withContext texts.map { null }
            val origLabel = st.orig.label
            val numbered = nonBlank.joinToString("\n") { (i, t) -> "${i + 1}. ${t.replace('\n', ' ')}" }
            val prompt = JSONObject().put(
                "text",
                "Translate these numbered manga/manhwa speech lines from $origLabel to ${st.target.label}. " +
                    "Write like a professional manhwa translator: fluent dialogue that sounds actually spoken, never word-for-word; " +
                    "keep the same meaning, tone and energy (a shout stays a shout, a whisper stays a whisper); " +
                    "names of people, places and titles transliterated, never translated; exclamation and question marks kept with their full force. " +
                    "Reply with ONLY the translations, one per line, keeping the same numbers " +
                    "in the form \"1. ...\". Do not add explanations.\n$numbered",
            ).toString()
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                .toString()
            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$key"
            val raw = com.hikari.app.net.Http.postStringQuiet(url, body) ?: return@withContext texts.map { null }
            try {
                val root = JSONObject(raw)
                val out = root.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    .orEmpty()
                val mapped = parseNumbered(out, nonBlank.size) ?: emptyMap()
                // Numbered reply: map by number; unnumbered fallback: in order.
                val result: MutableList<String?> = texts.map { null }.toMutableList()
                if (mapped.isNotEmpty()) {
                    for ((i, _) in nonBlank) result[i] = mapped[i]?.ifEmpty { null }
                } else {
                    val lines = out.lines().map { it.trim() }.filter { it.isNotEmpty() }.takeLast(nonBlank.size)
                    nonBlank.forEachIndexed { k, (i, _) -> result[i] = lines.getOrNull(k) }
                }
                result
            } catch (e: Exception) {
                texts.map { null }
            }
        }

    // ---- Disk cache ----

    private fun readCache(f: File): List<TLBox>? {
        return try {
            if (!f.exists()) return null
            val o = JSONObject(f.readText())
            val arr = o.optJSONArray("blocks") ?: return null
            ArrayList<TLBox>(arr.length()).also { out ->
                for (i in 0 until arr.length()) {
                    val b = arr.optJSONObject(i) ?: continue
                    val tr = b.optString("trans").ifEmpty { continue }
                    val l = b.optDouble("l").toFloat()
                    val t = b.optDouble("t").toFloat()
                    val r = b.optDouble("r").toFloat()
                    val bb = b.optDouble("b").toFloat()
                    out.add(
                        TLBox(
                            l, t, r, bb,
                            b.optString("text"), tr,
                            b.optDouble("bl", l.toDouble()).toFloat(),
                            b.optDouble("bt", t.toDouble()).toFloat(),
                            b.optDouble("br", r.toDouble()).toFloat(),
                            b.optDouble("bb", bb.toDouble()).toFloat(),
                        )
                    )
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun writeCache(f: File, boxes: List<TLBox>) {
        try {
            val arr = JSONArray()
            for (b in boxes) {
                arr.put(
                    JSONObject().put("l", b.l).put("t", b.t).put("r", b.r).put("b", b.b)
                        .put("text", b.text).put("trans", b.trans)
                        .put("bl", b.bl).put("bt", b.bt).put("br", b.br).put("bb", b.bb)
                )
            }
            f.writeText(JSONObject().put("blocks", arr).toString())
        } catch (e: Exception) {
        }
    }

    private fun prune() {
        try {
            val d = dir ?: return
            val cutoff = System.currentTimeMillis() - 14L * 24 * 60 * 60 * 1000
            d.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        } catch (e: Exception) {
        }
    }

    private fun sha1(s: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

/**
 * In-place overlay: white bubble boxes with the translated text, drawn over
 * the page. Positioned by [locator] (full-image px -> this view's px), so it
 * stays glued under zoom and pan; it never touches input.
 */
class TranslationOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private var imgW = 1f
    private var imgH = 1f
    private var boxes: List<TLBox> = emptyList()

    /** Full-image pixel -> this view's pixel. Null when the image isn't laid out yet. */
    var locator: ((Float, Float) -> PointF?)? = null

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Fully opaque: no trace of the source lettering may show through.
        color = AndroidColor.WHITE
        alpha = 255
        style = Paint.Style.FILL
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.BLACK
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private data class Fitted(val x: Float, val y: Float, val layout: StaticLayout)
    private val fitted = HashMap<Int, Pair<String, Fitted>>()

    private var loopOn = false
    private val loop = object : Runnable {
        override fun run() {
            if (!loopOn || !isAttachedToWindow) return
            invalidate()
            postOnAnimation(this)
        }
    }

    init {
        isClickable = false
        isFocusable = false
    }

    fun setContent(w: Float, h: Float, list: List<TLBox>, loc: (Float, Float) -> PointF?) {
        imgW = if (w > 0) w else 1f
        imgH = if (h > 0) h else 1f
        boxes = list
        locator = loc
        fitted.clear()
        if (!loopOn && list.isNotEmpty()) {
            loopOn = true
            postOnAnimation(loop)
        }
        invalidate()
    }

    fun clearContent() {
        boxes = emptyList()
        fitted.clear()
        loopOn = false
        removeCallbacks(loop)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        loopOn = false
        removeCallbacks(loop)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val loc = locator ?: return
        if (imgW <= 0 || imgH <= 0) return
        for ((index, b) in boxes.withIndex()) {
            val p1 = try {
                loc(b.l, b.t)
            } catch (e: Exception) {
                null
            } ?: continue
            val p2 = try {
                loc(b.r, b.b)
            } catch (e: Exception) {
                null
            } ?: continue
            val l = min(p1.x, p2.x); val t = min(p1.y, p2.y)
            val r = max(p1.x, p2.x); val btm = max(p1.y, p2.y)
            val w = r - l; val h = btm - t
            if (w < 8 || h < 8) continue
            if (r < 0 || l > width || btm < 0 || t > height) continue
            // Same bubble format, kept INSIDE the real outline: first a white
            // RECT over the source glyphs (proportional pad — this hides the
            // old lettering), then the translation in an OVAL fitted inside
            // the detected bubble outline. Long text gets SMALLER words that
            // stay inside the bubble — the oval never grows past the outline
            // onto the art (the 0.12.35 left-spill).
            val pad = max(10f, min(w, h) * 0.10f)
            val el = l - pad; val et = t - pad
            val er = r + pad; val eb = btm + pad
            // Hide pass: covers every source pixel plus margin.
            canvas.drawRect(el, et, er, eb, boxPaint)
            // Bubble outline in view space. Missing/degenerate (old cache,
            // detection miss) falls back to the padded source box.
            var capL = el
            var capT = et
            var capR = er
            var capB = eb
            if (b.br > b.bl && b.bb > b.bt) {
                try {
                    val q1 = loc(b.bl, b.bt)
                    val q2 = loc(b.br, b.bb)
                    if (q1 != null && q2 != null) {
                        val nl = min(q1.x, q2.x); val nt = min(q1.y, q2.y)
                        val nr = max(q1.x, q2.x); val nb = max(q1.y, q2.y)
                        if (nr - nl >= 12 && nb - nt >= 12) {
                            capL = nl; capT = nt; capR = nr; capB = nb
                        }
                    }
                } catch (e: Exception) {
                }
            }
            val capW = capR - capL
            val capH = capB - capT
            if (capW < 14 || capH < 14) continue
            // Words live inside the outline with an ink margin; shrink the
            // font down to 8px before ever touching the edge.
            val availW = capW * 0.84f
            val availH = capH * 0.84f
            val f = fitBubble(b.trans, availW, availH) ?: continue
            val cx = (capL + capR) / 2f
            val cy = (capT + capB) / 2f
            val ow = min(capW * 0.96f, f.layout.width + 14f)
            val oh = min(capH * 0.96f, f.layout.height + 12f)
            canvas.drawOval(RectF(cx - ow / 2f, cy - oh / 2f, cx + ow / 2f, cy + oh / 2f), boxPaint)
            val key = "${(availW / 6).toInt()}x${(availH / 6).toInt()}|${b.trans.hashCode()}"
            fitted[index] = key to f
            canvas.save()
            canvas.translate(cx - f.layout.width / 2f, cy - f.layout.height / 2f)
            f.layout.draw(canvas)
            canvas.restore()
        }
    }

    /** True when most of the text is Devanagari (Hindi): taller glyph metrics. */
    private fun isDevanagari(t: String): Boolean {
        var dev = 0
        var total = 0
        for (ch in t) {
            if (ch.isWhitespace()) continue
            total++
            if (ch in '\u0900'..'\u097F') dev++
        }
        return total > 0 && dev * 2 >= total
    }

    /**
     * Grow-to-fill centered layout for one bubble: start big, shrink until it
     * fits — but short labels ("THIS IS.."-class, one or two Hindi words) keep
     * a high floor so they render BIG like the original lettering instead of a
     * tiny speck, and the final pass always returns a layout (the caller grows
     * the oval cover when even the floor overflows) instead of dropping the box.
     */
    private fun fit(text: String, w: Float, h: Float): Fitted? {
        if (text.isBlank() || w <= 14 || h <= 14) return null
        val short = text.length <= 26
        val floor = when {
            short -> max(13f, min(w, h) * 0.24f).coerceAtMost(44f)
            isDevanagari(text) -> 12f
            else -> 11f
        }
        var size = min(h * 0.55f, w * 0.30f).coerceIn(floor, 140f)
        val availW = (w * 0.94f).coerceAtLeast(8f)
        val availH = (h * 0.92f).coerceAtLeast(8f)
        // Fresh paint per layout: StaticLayout draws with its paint live, so a
        // shared paint resized for the next box would corrupt this one.
        val paint = TextPaint(textPaint)
        var last: StaticLayout? = null
        while (true) {
            paint.textSize = size
            val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, availW.toInt())
                .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                builder.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY)
                builder.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE)
            }
            val layout = builder.build()
            last = layout
            if (layout.height <= availH) {
                return Fitted(-layout.width / 2f, 0f, layout)
            }
            if (size <= floor + 0.5f) break
            size = max(floor, size * 0.90f)
        }
        // Even the floor overflows: hand back the floor layout and let the
        // caller grow the cover (bounded, oval) instead of shrinking to dust.
        return last?.let { Fitted(-it.width / 2f, 0f, it) }
    }

    /**
     * Shrink-to-fit INSIDE a known bubble outline: start big, shrink until
     * the words fit — down to an 8px floor, never past the outline. Long
     * translations get smaller words that stay inside the bubble instead of
     * an oval bursting through the edge onto the art.
     */
    private fun fitBubble(text: String, w: Float, h: Float): Fitted? {
        if (text.isBlank() || w < 16 || h < 16) return null
        var size = min(h * 0.52f, w * 0.30f).coerceIn(8f, 140f)
        val availW = (w * 0.96f).coerceAtLeast(8f)
        val availH = (h * 0.94f).coerceAtLeast(8f)
        // Fresh paint per layout: StaticLayout draws with its paint live, so a
        // shared paint resized for the next box would corrupt this one.
        val paint = TextPaint(textPaint)
        var last: StaticLayout? = null
        while (true) {
            paint.textSize = size
            val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, availW.toInt())
                .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                builder.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY)
                builder.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE)
            }
            val layout = builder.build()
            last = layout
            if (layout.height <= availH) {
                return Fitted(-layout.width / 2f, 0f, layout)
            }
            if (size <= 8.5f) break
            size = max(8f, size * 0.90f)
        }
        // Even 8px overflows (extreme): hand it back anyway — clamped oval,
        // centered, still inside the outline.
        return last?.let { Fitted(-it.width / 2f, 0f, it) }
    }
}
