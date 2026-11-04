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

/** One translated text block, in full-image pixels. */
data class TLBox(
    val l: Float,
    val t: Float,
    val r: Float,
    val b: Float,
    val text: String,
    val trans: String,
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
    @Volatile
    var settings: MangaTranslateSettings = MangaTranslateSettings.DEFAULTS

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
        val hk = sha1("$pageKey|${st.orig.name}|${st.target.name}|${st.engine.name}|${st.geminiKey}")
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
        val texts = raw.map { it.text }
        val translated = try {
            translateAll(texts, st)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext emptyList()
        }
        raw.mapIndexedNotNull { i, b ->
            val t = translated.getOrNull(i)?.trim().orEmpty()
            if (t.isBlank() || t == b.text.trim()) null else TLBox(b.l, b.t, b.r, b.b, b.text, t)
        }
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
            texts.map { t ->
                if (t.isBlank()) null else gtxOne(t, sl, st.target.code)
            }
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
                "Translate these numbered manga speech lines from $origLabel to ${st.target.label}. " +
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
                val mapped = HashMap<Int, String>()
                val lineRe = Regex("""^\s*(\d+)[.)]\s?(.*)$""")
                for (line in out.lines()) {
                    val m = lineRe.find(line.trimEnd()) ?: continue
                    val n = m.groupValues[1].toIntOrNull() ?: continue
                    mapped[n - 1] = m.groupValues[2].trim()
                }
                // Numbered reply: map by number; unnumbered fallback: in order.
                val result: MutableList<String?> = texts.map { null }.toMutableList()
                if (mapped.size >= nonBlank.size / 2) {
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
                    out.add(
                        TLBox(
                            b.optDouble("l").toFloat(), b.optDouble("t").toFloat(),
                            b.optDouble("r").toFloat(), b.optDouble("b").toFloat(),
                            b.optString("text"), tr,
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
        color = AndroidColor.WHITE
        alpha = 235
        style = Paint.Style.FILL
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9AA0A6.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1f
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
            val rect = RectF(l, t, r, btm)
            canvas.drawRoundRect(rect, 7f, 7f, boxPaint)
            canvas.drawRoundRect(rect, 7f, 7f, edgePaint)
            val key = "${(w / 6).toInt()}x${(h / 6).toInt()}"
            val cached = fitted[index]
            val f = if (cached != null && cached.first == key) {
                cached.second
            } else {
                fit(b.trans, w, h)?.also { fitted[index] = key to it } ?: continue
            }
            canvas.save()
            canvas.translate(l + w / 2f, t + (h - f.layout.height) / 2f)
            f.layout.draw(canvas)
            canvas.restore()
        }
    }

    /** Shrink-to-fit centered layout for one box. */
    private fun fit(text: String, w: Float, h: Float): Fitted? {
        if (text.isBlank() || w <= 12 || h <= 12) return null
        var size = (h * 0.30f).coerceIn(9f, 26f)
        val availW = (w - 10).coerceAtLeast(4f)
        val availH = h - 8
        // Fresh paint per layout: StaticLayout draws with its paint live, so a
        // shared paint resized for the next box would corrupt this one.
        val paint = TextPaint(textPaint)
        while (size >= 8f) {
            paint.textSize = size
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, availW.toInt())
                .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .build()
            if (layout.height <= availH) {
                return Fitted(-layout.width / 2f, 0f, layout)
            }
            size *= 0.88f
        }
        return null
    }
}
