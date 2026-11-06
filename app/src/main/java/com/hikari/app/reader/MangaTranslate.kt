package com.hikari.app.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
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
import com.hikari.app.data.Logs
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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
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
    UR("ur", "Urdu"),
    IT("it", "Italian"),
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

/** One translated text block, in full-image pixels. [bl,bt,br,bb] carries the
 *  measured white room around the text (for centering in any language);
 *  [art] marks art lettering/SFX (drawn stroked, no white cover). */
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
    val art: Boolean = false,
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
    private val inflight = ConcurrentHashMap<String, Deferred<TLPage>>()

    /** Point at the app's files dir once (the cache lives under it). */
    fun configure(ctx: Context) {
        if (dir == null) {
            dir = File(ctx.applicationContext.filesDir, "manga_translate").apply { mkdirs() }
            scope.launchBlock { prune() }
            // The OCR/translation models download on first use: without this
            // the first toggle fails every tile (the download is still
            // running), the page comes back empty and the open page never
            // fills in. Warming here — while the user is still opening the
            // manga — means the engines are usually ready before the toggle.
            scope.launchBlock { warmupEngines() }
        }
    }

    /**
     * Force the on-device models to download long before the first real
     * page needs them. A tiny blank bitmap through each recognizer (plus
     * one language-ID probe) is enough to start the download; failed passes
     * just mean "still downloading", so wait and try again (bounded, a few
     * minutes, fire-and-forget — the reader never waits on this).
     */
    private suspend fun warmupEngines() {
        val blank = try {
            Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(AndroidColor.WHITE) }
        } catch (e: Exception) {
            null
        } ?: return
        try {
            val img = InputImage.fromBitmap(blank, 0)
            val recs = listOf(latinRec, jaRec, koRec, zhRec, hiRec)
            val waits = longArrayOf(0, 10_000, 20_000, 30_000, 60_000, 120_000)
            for (w in waits) {
                if (w > 0) delay(w)
                var ok = 0
                for (r in recs) {
                    try {
                        r.process(img).awaitTask()
                        ok++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Module still downloading — a later pass retries.
                    }
                }
                try {
                    langIdentifier.identifyLanguage("hello").awaitTask()
                    ok++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                }
                if (ok >= recs.size + 1) return
            }
        } finally {
            try {
                blank.recycle()
            } catch (e: Exception) {
            }
        }
    }

    private fun CoroutineScope.launchBlock(block: suspend () -> Unit) {
        val job = this.async { block() }
        job.invokeOnCompletion { /* fire and forget */ }
    }

    /**
     * What a page translation produced — and whether trying again could
     * help. The holders retry only when a retry could succeed; a page that
     * is already in the target language (or holds no text) clears its badge
     * at once instead of spinning "Translating..." through the retry budget.
     */
    enum class TLStatus { OK, BLANK_PAGE, ALREADY_TARGET, ENGINE_FAILED, OCR_PENDING }

    /** One page's translation result (see [TLStatus]). */
    data class TLPage(val boxes: List<TLBox>, val status: TLStatus, val note: String)

    /**
     * Translated blocks for one page image, in full-image pixels. [onStatus]
     * reports each stage ("Reading…", "Translating 12…", "No text found")
     * for the holder badge, so a stuck page says where it is stuck.
     */
    suspend fun translatePage(
        pageKey: String,
        file: File,
        imgW: Int,
        imgH: Int,
        st: MangaTranslateSettings = settings,
        onStatus: (String) -> Unit = {},
    ): TLPage {
        if (!st.enabled || imgW <= 0 || imgH <= 0) {
            // Enabled but dimension-less is a caller bug, not a blank page:
            // say so instead of stamping "No text found" on it.
            if (st.enabled) Logs.log("Manga", "tl-page skip img=" + imgW + "x" + imgH)
            return TLPage(emptyList(), TLStatus.BLANK_PAGE, "")
        }
        if (st.orig != MangaOrigLang.AUTO && st.orig.code == st.target.code) {
            // Same-language pair (English page, English target) can never
            // produce a box: every echo is dropped below. Say so at once
            // instead of burning the retry budget on a stuck badge.
            val note = "Already " + st.target.label
            Logs.log("Manga", "tl-page already-target")
            onStatus(note)
            return TLPage(emptyList(), TLStatus.ALREADY_TARGET, note)
        }
        val d = dir ?: run {
            Logs.log("Manga", "tl-page no-dir")
            return TLPage(emptyList(), TLStatus.ENGINE_FAILED, "")
        }
        Logs.log("Manga", "tl-page start img=" + imgW + "x" + imgH + " orig=" + st.orig.name + " target=" + st.target.name + " engine=" + st.engine.name + " file=" + file.exists() + "/" + runCatching { file.length() }.getOrDefault(-1L))
        // v10: multi-tile script probe + runner-up second chance + full-res
        // re-pass change what a page yields, so older partial caches rescan.
        val hk = sha1("$pageKey|${st.orig.name}|${st.target.name}|${st.engine.name}|${st.geminiKey}|v10")
        val cacheFile = File(d, "$hk.json")
        readCache(cacheFile)?.let {
            Logs.log("Manga", "tl-page cache-hit boxes=" + it.size)
            return TLPage(it, TLStatus.OK, "")
        }
        val def = inflight.getOrPut(hk) { scope.async { runTranslate(file, imgW, imgH, st, onStatus) } }
        return try {
            val result = def.await()
            if (result.boxes.isNotEmpty()) writeCache(cacheFile, result.boxes)
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logs.log("Manga", "tl-page error=" + e.javaClass.simpleName)
            TLPage(emptyList(), TLStatus.ENGINE_FAILED, "")
        } finally {
            if (def.isCompleted) inflight.remove(hk, def)
        }
    }

    private suspend fun runTranslate(
        file: File,
        imgW: Int,
        imgH: Int,
        st: MangaTranslateSettings,
        onStatus: (String) -> Unit,
    ): TLPage = withContext(Dispatchers.IO) {
        onStatus("Reading…")
        val (raw, downloading) = try {
            ocrPage(file, imgW, imgH, st.orig, onStatus)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logs.log("Manga", "tl-run error=" + e.javaClass.simpleName)
            return@withContext TLPage(emptyList(), TLStatus.ENGINE_FAILED, "")
        }
        if (raw.isEmpty()) {
            Logs.log("Manga", "tl-page raw=0 downloading=" + downloading)
            return@withContext if (downloading) TLPage(emptyList(), TLStatus.OCR_PENDING, "")
            else {
                onStatus("No text found")
                TLPage(emptyList(), TLStatus.BLANK_PAGE, "No text found")
            }
        }
        // Stacked lines of one bubble become ONE block: translating them
        // together keeps the sentence whole and renders one clean bubble.
        val grouped = groupBubbles(raw)
        if (grouped.isEmpty()) return@withContext TLPage(emptyList(), TLStatus.BLANK_PAGE, "No text found")
        // Tiny-text gate: site-credit strips and speck noise (a few px tall)
        // never become garbled transliterations. Real dialogue ("?!" included)
        // is far taller, so only physical specks are dropped.
        val kept = if (imgW > 0) {
            grouped.filter { (it.b - it.t) >= imgW * 0.012f }
        } else grouped
        if (kept.isEmpty()) {
            Logs.log("Manga", "tl-page raw=" + raw.size + " grouped=" + grouped.size + " kept=0 (tiny-text gate)")
            onStatus("No text found")
            return@withContext TLPage(emptyList(), TLStatus.BLANK_PAGE, "No text found")
        }
        onStatus("Translating " + kept.size + "…")
        val texts = kept.map { cleanOcr(it.text) }
        Logs.log("Manga", "tl-tr run engine=" + st.engine.name + " orig=" + st.orig.name + " target=" + st.target.code + " boxes=" + texts.size)
        // ALL-CAPS shouting ("LET GO! LET ME GO!") translates badly as-is:
        // Google keeps words like GO in Latin. Sentence-case it for the
        // engine; the equality check below still uses the original.
        val sendTexts = texts.map { normalizeCaps(it) }
        var translated = try {
            translateAll(sendTexts, st)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext TLPage(emptyList(), TLStatus.ENGINE_FAILED, "")
        }
        // Second chance for boxes whose translation still leaks source-script
        // words (e.g. Hindi "मैं GO वापस..."): retry those boxes alone in
        // sentence case, several at once. Whatever still leaks after the
        // retry is dropped.
        if (st.engine == MangaTranslateEngine.GOOGLE) {
            val sl = if (st.orig == MangaOrigLang.AUTO) "auto" else st.orig.code
            val gtxSem = Semaphore(4)
            translated = coroutineScope {
                translated.mapIndexed { i, tr ->
                    async {
                        gtxSem.withPermit {
                            val t = tr?.trim().orEmpty()
                            if (t.isNotBlank() && hasLeftoverSource(t, texts.getOrNull(i).orEmpty(), st.target)) {
                                gtxOne(normalizeCaps(texts.getOrNull(i).orEmpty()), sl, st.target.code)?.trim()
                                    ?.takeIf { it.isNotEmpty() } ?: tr
                            } else tr
                        }
                    }
                }.awaitAll()
            }
        }
        // White room + art/SFX class per box (one shared decoder each): the
        // two scans are independent, so run them together — each decodes the
        // page again, and sequential scans were a third of the wait.
        val roomScan = coroutineScope {
            val roomsD = async(Dispatchers.IO) { measureRooms(file, imgW, imgH, kept) }
            val bubblesD = async(Dispatchers.IO) { detectBubbles(file, imgW, imgH, kept) }
            roomsD.await() to bubblesD.await()
        }
        // Dialogue gets centered in its real white room (any language stays
        // centered), art lettering skips the white cover and draws stroked
        // instead.
        val rooms = roomScan.first
        // Bubble outlines (the ink ovals themselves): the only anchor that is
        // the bubble on white pages too, where the directional room scan can
        // leak through thin outlines. A found outline centers + sizes the box.
        val bubbles = roomScan.second
        val done = kept.mapIndexedNotNull { i, b ->
            val raw = translated.getOrNull(i)?.trim().orEmpty()
            var t = cleanTrans(raw)
            if (t.isBlank() || t.equals(texts.getOrNull(i), ignoreCase = true)) null
            else if (!hasTargetScript(t, st.target)) null
            else {
                // Mixed-script leak (Hindi + leftover Latin like "HER NAME IS"):
                // strip the Latin words but KEEP the Hindi + the white cover, so
                // no half-English garbage is ever drawn huge across the bubble.
                if (hasLeftoverSource(t, texts.getOrNull(i).orEmpty(), st.target)) {
                    val stripped = cleanTrans(stripLatinWords(t))
                    if (stripped.isBlank() || !hasTargetScript(stripped, st.target) ||
                        hasLeftoverSource(stripped, texts.getOrNull(i).orEmpty(), st.target)
                    ) null
                    else {
                        t = stripped
                        t
                    }
                } else t
            }?.let { tt ->
                val rm = rooms.getOrNull(i)
                val bub = bubbles.getOrNull(i)
                // Outline wins over room; art lettering ONLY when the glyphs are
                // truly large SFX on dark art (tall boxes, dark crop border).
                // Small/medium text with no room is still dialogue (white cover,
                // centered on the source) — never huge stroked spill.
                val isArt = (rm?.art == true) && (b.b - b.t) >= imgW * 0.045f
                if (bub != null) TLBox(b.l, b.t, b.r, b.b, b.text, tt, bub.left, bub.top, bub.right, bub.bottom, false)
                else if (rm?.room != null) {
                    val o = rm.room!!
                    TLBox(b.l, b.t, b.r, b.b, b.text, tt, o.left, o.top, o.right, o.bottom, false)
                } else if (isArt) TLBox(b.l, b.t, b.r, b.b, b.text, tt, art = true)
                else TLBox(b.l, b.t, b.r, b.b, b.text, tt)
            }
        }
        if (done.isEmpty()) {
            // Text was found but nothing survived: either every engine
            // reply failed (network/rate-limit — a retry may help) or every
            // reply echoed the source (page already in the target language —
            // retrying would spin the badge forever, so the holder clears).
            val anyReply = translated.any { !it.isNullOrBlank() }
            Logs.log("Manga", "tl-page raw=" + raw.size + " kept=" + kept.size + " replies=" + translated.count { !it.isNullOrBlank() } + " final=0")
            return@withContext if (anyReply) {
                val note = "Already " + st.target.label
                onStatus(note)
                TLPage(emptyList(), TLStatus.ALREADY_TARGET, note)
            } else {
                TLPage(emptyList(), TLStatus.ENGINE_FAILED, "")
            }
        }
        Logs.log("Manga", "tl-page raw=" + raw.size + " grouped=" + grouped.size + " kept=" + kept.size + " translated=" + translated.count { !it.isNullOrBlank() } + " final=" + done.size)
        return@withContext TLPage(done, TLStatus.OK, "")
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

    /**
     * A freshly-built client for [script] (same options as the shared one).
     * A recognizer answers one image at a time, so parallel tile OCR gives
     * each lane its own client; the on-device model underneath is shared, so
     * this costs no extra download.
     */
    private fun newRec(script: Script): TextRecognizer = when (script) {
        Script.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        Script.JA -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        Script.KO -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        Script.ZH -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        Script.HI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
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
     *
     * First-use model download is the slow part: calls made while the
     * module downloads FAIL per tile. Those all-failed passes wait and
     * retry (bounded, a few minutes) instead of reporting "no text" — a
     * pass where the engine ANSWERED (zero failures) but saw nothing is a
     * genuinely blank page and returns at once.
     */
    /**
     * OCR over vertical tiles (see [ocrPage] docs for the pass structure).
     * Returns the boxes plus whether the models may still be downloading: a
     * retry can only help while the engines are still answering with
     * failures — a clean empty pass means the page holds no (readable) text.
     */
    private suspend fun ocrPage(file: File, imgW: Int, imgH: Int, orig: MangaOrigLang, onStatus: (String) -> Unit): Pair<List<RawBox>, Boolean> {
        val dec: BitmapRegionDecoder? = try {
            BitmapRegionDecoder.newInstance(file.absolutePath, false)
        } catch (e: Exception) {
            null
        }
        if (dec == null) {
            // The region decoder rejects the file (a mid-download partial, or
            // a format it cannot handle while the viewer itself shows it
            // fine). This is never a blank page: log the facts, try a plain
            // full decode, and otherwise mark it retryable so holder backoff
            // re-tries instead of stamping "No text found".
            Logs.log("Manga", "tl-decode region-fail exists=" + file.exists() + " len=" + runCatching { file.length() }.getOrDefault(-1L) + " ext=" + file.name.substringAfterLast('.', ""))
            fullDecodeFallback(file, orig)?.let { return it to false }
            return emptyList<RawBox>() to true
        }
        try {
            val w = if (dec.width > 0) dec.width else imgW
            val h = if (dec.height > 0) dec.height else imgH
            if (w <= 0 || h <= 0) {
                Logs.log("Manga", "tl-decode dims dec=" + runCatching { dec.width }.getOrDefault(-1) + "x" + runCatching { dec.height }.getOrDefault(-1) + " arg=" + imgW + "x" + imgH)
                return emptyList<RawBox>() to true
            }
            val sample = max(1, ceil(w / 1600.0).toInt())
            val tileH = 1800
            val overlap = 80
            val fixed = fixedScriptFor(orig)
            // 0s, 5s, 15s, 30s, 60s, 120s between passes (~4 min cover).
            val waits = longArrayOf(0, 5_000, 15_000, 30_000, 60_000, 120_000)
            var scripts: Pair<Script, Script?>? = null
            for (attempt in waits.indices) {
                if (attempt > 0) delay(waits[attempt])
                val tiles = decodeTiles(dec, w, h, sample, tileH, overlap, 24)
                if (tiles.isEmpty()) {
                    Logs.log("Manga", "tl-decode no-tiles w=" + w + " h=" + h + " sample=" + sample)
                    return emptyList<RawBox>() to true
                }
                if (scripts == null) {
                    scripts = if (fixed != null) Pair<Script, Script?>(fixed, null) else detectScripts(tiles)
                }
                val scr = scripts ?: return emptyList<RawBox>() to false
                val (primary, runner) = scr
                val (out, failed) = ocrTiles(tiles, primary, sample)
                if (out.isNotEmpty()) {
                    recycleTiles(tiles)
                    Logs.log("Manga", "tl-ocr pass=" + attempt + " script=" + primary + " tiles=" + tiles.size + " boxes=" + out.size + " failed=" + failed)
                    return mergeRows(out) to false
                }
                // Primary answered fine but saw nothing: a sparse or mixed
                // page (English art with vertical Japanese SFX, tiny
                // hand-lettering the tile scale smears). Second chance with
                // the runner-up script on the same tiles before blanking.
                if (failed == 0 && runner != null) {
                    val (out2, failed2) = ocrTiles(tiles, runner, sample)
                    if (out2.isNotEmpty()) {
                        recycleTiles(tiles)
                        Logs.log("Manga", "tl-ocr pass=" + attempt + " script=" + primary + "+" + runner + " tiles=" + tiles.size + " boxes=" + out2.size)
                        return mergeRows(out2) to false
                    }
                    if (failed2 > 0) {
                        recycleTiles(tiles)
                        continue
                    }
                }
                if (failed == 0) {
                    // Engine answered fine but saw no text. A truly blank
                    // page returns here — after one full-resolution re-pass,
                    // since tiny text the tile scale smeared is often crisp
                    // at full res.
                    recycleTiles(tiles)
                    onStatus("Reading… (hi-res pass)")
                    val (hiOut, hiFailed) = fullResOcr(dec, w, h, primary, runner)
                    if (hiOut.isNotEmpty()) return hiOut to false
                    if (hiFailed > 0) continue
                    Logs.log("Manga", "tl-ocr script=" + primary + " blank page")
                    return emptyList<RawBox>() to false
                }
                recycleTiles(tiles)
                // Otherwise the model is still downloading — next pass waits.
            }
            return emptyList<RawBox>() to true
        } finally {
            try {
                dec.recycle()
            } catch (e: Exception) {
            }
        }
    }

    /** Fixed recognizer for an explicit source language, null when the
     *  probe must decide (see [detectScripts]). */
    private fun fixedScriptFor(orig: MangaOrigLang): Script? = when (orig) {
        MangaOrigLang.JA -> Script.JA
        MangaOrigLang.KO -> Script.KO
        MangaOrigLang.ZH -> Script.ZH
        MangaOrigLang.HI -> Script.HI
        MangaOrigLang.EN -> Script.LATIN
        MangaOrigLang.AUTO -> null
    }

    /**
     * Plain full-bitmap OCR for files the region decoder rejects (see the
     * `dec == null` branch in [ocrPage]): a mid-download partial or a format
     * it cannot handle while the viewer itself shows it fine. The bitmap is
     * downsampled to the same ~1600px width the tiled path uses, then goes
     * through the same probe + primary + runner-up path — boxes come back
     * in full-image pixels exactly like the tiled path. Null when even this
     * cannot read the file (the caller marks the page retryable).
     */
    private suspend fun fullDecodeFallback(file: File, orig: MangaOrigLang): List<RawBox>? {
        val path = file.absolutePath
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeFile(path, bounds)
        } catch (e: Exception) {
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sw = max(1, ceil(bounds.outWidth / 1600.0).toInt())
        val bmp = try {
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sw })
        } catch (e: Exception) {
            null
        } ?: return null
        val tiles = arrayListOf(0 to bmp)
        try {
            if (bmp.width <= 0 || bmp.height <= 0) return null
            val fixed = fixedScriptFor(orig)
            val scripts = if (fixed != null) Pair<Script, Script?>(fixed, null) else detectScripts(tiles)
            val (primary, runner) = scripts
            val (out, failed) = ocrTiles(tiles, primary, sw)
            if (out.isNotEmpty()) {
                Logs.log("Manga", "tl-ocr fullbmp script=" + primary + " boxes=" + out.size + " failed=" + failed)
                return mergeRows(out)
            }
            if (failed == 0 && runner != null) {
                val (out2, _) = ocrTiles(tiles, runner, sw)
                if (out2.isNotEmpty()) {
                    Logs.log("Manga", "tl-ocr fullbmp script=" + primary + "+" + runner + " boxes=" + out2.size)
                    return mergeRows(out2)
                }
            }
            return null
        } finally {
            recycleTiles(tiles)
        }
    }

    /** One vertical-tile decode of [dec] (see [ocrPage]). */
    private fun decodeTiles(
        dec: BitmapRegionDecoder,
        w: Int,
        h: Int,
        sample: Int,
        tileH: Int,
        overlap: Int,
        cap: Int,
    ): ArrayList<Pair<Int, Bitmap>> {
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
            if (tiles.size > cap) break
        }
        return tiles
    }

    /**
     * OCR of decoded tiles, 3 at a time, each lane on its own client (a
     * client answers one image at a time). Order is restored by index so
     * the overlap fold is stable. Returns the boxes plus how many tiles
     * threw (a still-downloading model fails every tile it touches).
     */
    private suspend fun ocrTiles(
        tiles: List<Pair<Int, Bitmap>>,
        script: Script,
        sample: Int,
    ): Pair<List<RawBox>, Int> {
        val sem = Semaphore(3)
        val pool = List(3) { newRec(script) }
        val parts = coroutineScope {
            tiles.mapIndexed { i, (y0s, bmp) ->
                async {
                    sem.withPermit {
                        val rec = pool[i % pool.size]
                        try {
                            Triple(i, ocrTile(rec, bmp, y0s, sample), false)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Triple(i, emptyList<RawBox>(), true)
                        }
                    }
                }
            }.awaitAll()
        }.sortedBy { it.first }
        val out = ArrayList<RawBox>()
        var failed = 0
        for ((_, boxes, bad) in parts) {
            if (bad) failed++ else out.addAll(boxes)
        }
        return out to failed
    }

    private fun recycleTiles(tiles: List<Pair<Int, Bitmap>>) {
        for ((_, bmp) in tiles) {
            try {
                if (!bmp.isRecycled) bmp.recycle()
            } catch (e: Exception) {
            }
        }
    }

    /**
     * One full-resolution pass (no downsampling) for pages the fast tile
     * pass blanked: tiny hand-lettering the 1600px scale smears is often
     * crisp at full res. Boxes come back merged; the failure count tells
     * the caller whether a model is still downloading (wait) or the page
     * is truly blank (stop).
     */
    private suspend fun fullResOcr(
        dec: BitmapRegionDecoder,
        w: Int,
        h: Int,
        primary: Script,
        runner: Script?,
    ): Pair<List<RawBox>, Int> {
        val tiles = decodeTiles(dec, w, h, 1, 1200, 80, 40)
        if (tiles.isEmpty()) return emptyList<RawBox>() to 1
        val (out, failed) = ocrTiles(tiles, primary, 1)
        if (out.isNotEmpty()) {
            recycleTiles(tiles)
            Logs.log("Manga", "tl-ocr fullres script=" + primary + " tiles=" + tiles.size + " boxes=" + out.size)
            return mergeRows(out) to 0
        }
        if (failed == 0 && runner != null) {
            val (out2, failed2) = ocrTiles(tiles, runner, 1)
            recycleTiles(tiles)
            if (out2.isNotEmpty()) {
                Logs.log("Manga", "tl-ocr fullres script=" + primary + "+" + runner + " tiles=" + tiles.size + " boxes=" + out2.size)
                return mergeRows(out2) to 0
            }
            return emptyList<RawBox>() to failed2
        }
        recycleTiles(tiles)
        if (failed == 0) Logs.log("Manga", "tl-ocr fullres script=" + primary + " blank page")
        return emptyList<RawBox>() to failed
    }

    /**
     * Up to three tiles (top/middle/bottom) decide the script — a credits
     * strip at the top must not pick the recognizer for the whole page.
     * Latin is tried first (fast path: most scanlations are Latin); when it
     * sees almost nothing — vertical Japanese, clean Korean/Chinese pages,
     * the old "manga never translates" class — every recognizer is asked
     * on the same tiles at once and the one that actually reads something
     * wins. The runner-up comes back too, so a page the winner still
     * blanks gets a second chance with it instead of staying empty.
     * Bounded to this probe, so the common path costs nothing extra.
     */
    private suspend fun detectScripts(tiles: List<Pair<Int, Bitmap>>): Pair<Script, Script?> {
        val first = tiles.firstOrNull()
        val mid = tiles.getOrNull(tiles.size / 2)
        val last = tiles.lastOrNull()
        val probeTiles = listOfNotNull(
            first,
            mid?.takeIf { it.first != first?.first },
            last?.takeIf { it.first != first?.first && it.first != mid?.first },
        ).take(3)
        var latinTotal = 0
        val latinText = StringBuilder()
        for ((_, bmp) in probeTiles) {
            try {
                val t = latinRec.process(InputImage.fromBitmap(bmp, 0)).awaitTask()
                    .textBlocks.joinToString("\n") { it.text ?: "" }.take(2000)
                latinTotal += t.trim().length
                if (latinText.length < 2000) latinText.append(t, 0, min(2000 - latinText.length, t.length))
            } catch (e: Exception) {
            }
        }
        if (latinTotal >= 4) {
            val primary = scriptOf(latinText.toString())
            Logs.log("Manga", "tl-probe latin=" + latinTotal + " script=" + primary)
            return primary to null
        }
        val ranked = coroutineScope {
            listOf(
                async { Script.JA to probeLen(jaRec, probeTiles) },
                async { Script.KO to probeLen(koRec, probeTiles) },
                async { Script.ZH to probeLen(zhRec, probeTiles) },
                async { Script.HI to probeLen(hiRec, probeTiles) },
            ).awaitAll()
        }.sortedByDescending { it.second }
        Logs.log("Manga", "tl-probe multi " + ranked.joinToString(" ") { it.first.name + "=" + it.second })
        val best = ranked.firstOrNull()
        if (best == null || best.second < 2) return Script.LATIN to null
        val second = ranked.getOrNull(1)?.takeIf { it.second >= 2 && it.first != best.first }?.first
        return best.first to second
    }

    /** How much text [rec] reads across the probe tiles (0 when the model is still down). */
    private suspend fun probeLen(rec: TextRecognizer, tiles: List<Pair<Int, Bitmap>>): Int {
        var total = 0
        for ((_, bmp) in tiles) {
            total += readLen(rec, InputImage.fromBitmap(bmp, 0))
        }
        return total
    }

    /** How much text [rec] reads on [img] (0 when the model is still down). */
    private suspend fun readLen(rec: TextRecognizer, img: InputImage): Int {
        return try {
            rec.process(img).awaitTask().textBlocks.sumOf { it.text?.trim()?.length ?: 0 }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            0
        }
    }

    /** OCR of one vertical tile, in full-image pixels (see [ocrPage]). */
    private suspend fun ocrTile(rec: TextRecognizer, bmp: Bitmap, y0s: Int, sample: Int): List<RawBox> {
        val out = ArrayList<RawBox>()
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
        return out
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

    /** White room around one text box + whether it sits on art (SFX). */
    private data class Room(val room: RectF?, val art: Boolean)

    /**
     * Per-box white room, measured with directional scans that stop at ink —
     * never a flood fill, so nothing can leak. The room is the white space
     * holding the text (bubble interior or dialogue panel), used to CENTER
     * the translation in any language. Boxes whose crop border is mostly
     * non-white sit on art (SFX lettering): room null, art true.
     */
    private fun measureRooms(file: File, imgW: Int, imgH: Int, boxes: List<RawBox>): List<Room> {
        if (boxes.isEmpty()) return emptyList()
        val dec = try {
            BitmapRegionDecoder.newInstance(file.absolutePath, false)
        } catch (e: Exception) {
            null
        } ?: return boxes.map { Room(null, false) }
        return try {
            val w = if (dec.width > 0) dec.width else imgW
            val h = if (dec.height > 0) dec.height else imgH
            if (w <= 0 || h <= 0) boxes.map { Room(null, false) }
            else boxes.map { measureOne(dec, w, h, it) }
        } catch (e: Exception) {
            boxes.map { Room(null, false) }
        } finally {
            try {
                dec.recycle()
            } catch (e: Exception) {
            }
        }
    }

    private fun measureOne(dec: BitmapRegionDecoder, w: Int, h: Int, b: RawBox): Room {
        return try {
            val bw = (b.r - b.l).coerceAtLeast(1f)
            val bh = (b.b - b.t).coerceAtLeast(1f)
            val ex = bw * 1.0f
            val ey = bh * 1.0f
            val cl = max(0f, b.l - ex).toInt()
            val ct = max(0f, b.t - ey).toInt()
            val cr = min(w.toFloat(), b.r + ex).toInt()
            val cb = min(h.toFloat(), b.b + ey).toInt()
            val cw = cr - cl
            val ch = cb - ct
            if (cw < 16 || ch < 16) return Room(null, false)
            val scale = min(1f, 160f / max(cw, ch))
            val sw = max(10, (cw * scale).toInt())
            val sh = max(10, (ch * scale).toInt())
            val bmp = dec.decodeRegion(
                Rect(cl, ct, cr, cb),
                BitmapFactory.Options().apply { inSampleSize = max(1, min(cw / sw, ch / sw)) },
            ) ?: return Room(null, false)
            try {
                val dw = bmp.width
                val dh = bmp.height
                if (dw < 10 || dh < 10) return Room(null, false)
                val px = IntArray(dw * dh)
                bmp.getPixels(px, 0, dw, 0, 0, dw, dh)
                val white = BooleanArray(dw * dh)
                for (i in px.indices) {
                    val p = px[i]
                    white[i] = ((p shr 16) and 0xff) > 195 &&
                        ((p shr 8) and 0xff) > 195 && (p and 0xff) > 195
                }
                // Art test: how much of the crop BORDER is white? Bubbles
                // and dialogue panels sit in white; SFX sits on art.
                var border = 0
                var borderWhite = 0
                for (x in 0 until dw) {
                    for (y in intArrayOf(0, dh - 1)) {
                        border++
                        if (white[y * dw + x]) borderWhite++
                    }
                }
                for (y in 1 until dh - 1) {
                    for (x in intArrayOf(0, dw - 1)) {
                        border++
                        if (white[y * dw + x]) borderWhite++
                    }
                }
                // Bubble or SFX? Decided AFTER the room scan below: a found
                // white room is dialogue even on a dark panel (the crop border
                // catches panel art); only a missing room + a clearly dark
                // border (white < 35%, not 50%) + tall glyphs is SFX.
                val borderDark = borderWhite.toFloat() / max(1, border) < 0.35f
                // Text box in crop space.
                val tl = (((b.l - cl).toFloat() / cw * dw).toInt()).coerceIn(0, dw - 1)
                val tr = (((b.r - cl).toFloat() / cw * dw).toInt()).coerceIn(0, dw - 1)
                val tt = (((b.t - ct).toFloat() / ch * dh).toInt()).coerceIn(0, dh - 1)
                val tb = (((b.b - ct).toFloat() / ch * dh).toInt()).coerceIn(0, dh - 1)
                // Directional max reach: 1.5x the text dim each side (stops a
                // gap-leak from running to the page margin).
                val maxX = (1.5f * (tr - tl + 1)).toInt().coerceAtLeast(4)
                val maxY = (1.5f * (tb - tt + 1)).toInt().coerceAtLeast(4)
                // A row/col counts as white when 98%+ white (anti-aliased
                // edges and specks must not stop the scan; real ink does).
                fun colWhite(x: Int, y0: Int, y1: Int): Boolean {
                    var n = 0
                    var wn = 0
                    for (y in y0..y1) {
                        n++
                        if (white[y * dw + x]) wn++
                    }
                    return wn * 100 >= n * 98
                }
                fun rowWhite(y: Int, x0: Int, x1: Int): Boolean {
                    var n = 0
                    var wn = 0
                    for (x in x0..x1) {
                        n++
                        if (white[y * dw + x]) wn++
                    }
                    return wn * 100 >= n * 98
                }
                var lx = tl
                var stepsL = 0
                while (lx > 0 && stepsL < maxX && colWhite(lx - 1, tt, tb)) {
                    lx--
                    stepsL++
                }
                var rx = tr
                var stepsR = 0
                while (rx < dw - 1 && stepsR < maxX && colWhite(rx + 1, tt, tb)) {
                    rx++
                    stepsR++
                }
                var ty = tt
                var stepsT = 0
                while (ty > 0 && stepsT < maxY && rowWhite(ty - 1, lx, rx)) {
                    ty--
                    stepsT++
                }
                var by = tb
                var stepsB = 0
                while (by < dh - 1 && stepsB < maxY && rowWhite(by + 1, lx, rx)) {
                    by++
                    stepsB++
                }
                // A side that runs to its max reach without touching ink never
                // found the bubble wall — it leaked (thin outlines on white
                // pages). A leaked room drags the box off-center, so drop it
                // and let the outline (or the centered source box) decide.
                if (stepsL >= maxX || stepsR >= maxX || stepsT >= maxY || stepsB >= maxY) return Room(null, borderDark)
                // Back to full-image pixels, inset slightly so ink stays out.
                val fl = cl + lx.toFloat() / dw * cw + 1f
                val ft = ct + ty.toFloat() / dh * ch + 1f
                val fr = cl + (rx + 1).toFloat() / dw * cw - 1f
                val fb = ct + (by + 1).toFloat() / dh * ch - 1f
                if (fr - fl < 12 || fb - ft < 12) return Room(null, borderDark)
                Room(RectF(fl, ft, fr, fb), false)
            } finally {
                if (!bmp.isRecycled) bmp.recycle()
            }
        } catch (e: Exception) {
            Room(null, false)
        }
    }

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
            // Crop wide enough to hold the WHOLE bubble on white pages (the
            // old 1.1x crop never saw the outline, so no outline was ever
            // found where it matters). A merged neighbour is rejected below
            // by the width guard instead.
            val ex = bw * 2.0f
            val ey = bh * 2.0f
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
                // Crop sitting fully INSIDE a big bubble (or on blank page)
                // fills ~everything: ambiguous, not a usable outline.
                if (count > dw * dh * 0.85f) return null
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
                // Merged two neighbouring bubbles through the gutter: not a
                // bubble, and centering in it would sit between bubbles.
                if (out.right - out.left > bw * 6f) return null
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
        if (b.t > a.b + lineH * 1.6f) return false
        val inter = min(a.r, b.r) - max(a.l, b.l)
        if (inter <= 0) return false
        val narrow = min(a.r - a.l, b.r - b.l).coerceAtLeast(1f)
        if (inter < narrow * 0.4f) return false
        // Joint bubbles sit side-by-side / diagonally: their centers drift
        // apart even when the x-ranges touch. Lines of one bubble share a center.
        val ca = (a.l + a.r) / 2f
        val cb = (b.l + b.r) / 2f
        val wide = max(a.r - a.l, b.r - b.l).coerceAtLeast(1f)
        return kotlin.math.abs(cb - ca) <= wide * 0.8f
    }

    /** OCR joins lines with odd spacing — normalize before translating. */
    /**
     * OCR joins lines with odd spacing — normalize before translating. Also
     * repairs lettering stutters ("O-OBVIOUSLY!", "S-S-SO SCARY", OCR-misread
     * "0-0- ..."): the echoed head letter used to ride into the engine and
     * come back as Latin junk ("0-0- साथ..."), so strip it and send the real
     * word instead. Digit heads (0-/1-) are the same stutter with O/I misread
     * by OCR. Legit dashes ("X-MEN", "20-30") never match: a strip needs the
     * same letter repeated, or a 0/1 head in front of a real word.
     */
    private fun cleanOcr(t: String): String {
        var s = t.replace(Regex("\\s+"), " ")
            .replace(Regex("\\s+([,.!?;:%)\\]])"), "$1")
            .replace(Regex("([(\\[])\\s+"), "$1")
            .trim()
        s = s.replace(Regex("""^([A-Za-z])\s*-\s*(?:\1\s*-\s*)+""", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("""^([01])\s*-\s*(?:[01]\s*-\s*)+"""), "")
        s = s.replace(Regex("""^([A-Za-z])\s*-\s*(?=\1[A-Za-z])""", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("""^[01]\s*-\s*(?=[A-Za-z]{2})"""), "")
        return s.trim()
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
        // Strict: for Latin-free targets ANY surviving Latin word is a leak
        // (transliterated leftovers like "RA" inside Hindi). The caller strips
        // the Latin words but keeps the rest + the white cover, instead of
        // drawing mixed garbage huge across the bubble.
        if (Regex("[A-Za-z]{2,}").containsMatchIn(trans)) return true
        return false
    }

    /** Remove Latin words (2+ letters) for Latin-free targets, keep the rest. */
    private fun stripLatinWords(t: String): String {
        return t.replace(Regex("[A-Za-z]{2,}"), " ").replace(Regex("\\s+"), " ").trim()
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
        "ur" -> TranslateLanguage.URDU
        "it" -> TranslateLanguage.ITALIAN
        "ja" -> TranslateLanguage.JAPANESE
        "ko" -> TranslateLanguage.KOREAN
        "zh" -> TranslateLanguage.CHINESE
        else -> TranslateLanguage.ENGLISH
    }

    private val translators = ConcurrentHashMap<String, Translator>()

    /** First-seen translation-call failure signatures, logged once per
     *  process: names every failure mode (http code, timeout, parse, key)
     *  without per-box log spam. Per-page totals ride the tl-tr lines in
     *  [translateGoogle]/[runTranslate] instead. */
    private val trSeen = ConcurrentHashMap.newKeySet<String>()
    private fun trNote(sig: String) {
        if (trSeen.add(sig)) Logs.log("Manga", "tl-tr " + sig)
    }

    /**
     * App-wide Google gate: every gtx request on every page (and the novel
     * reader) serializes here with >=250ms between calls. The old code fired
     * 4 concurrent calls per page on every open page at once and Google
     * 429'd the burst — the stuck "Translating N..." badge.
     */
    private val gtxGate = Mutex()
    private var gtxLastAt = 0L

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
                trNote("mlkit-download-" + e.javaClass.simpleName)
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
            trNote("mlkit-identify-" + e.javaClass.simpleName)
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
            // Several gtx calls at once (bounded): a page holds dozens of
            // lines and sequential calls were most of the network wait.
            val gtxSem = Semaphore(4)
            if (mapped != null) {
                val result: MutableList<String?> = texts.map { null }.toMutableList()
                for ((i, _) in nonBlank) result[i] = mapped[i]
                // Lines the batch dropped get one call each.
                val missing = nonBlank.filter { (i, _) -> result[i] == null }
                if (missing.isNotEmpty()) {
                    val filled = coroutineScope {
                        missing.map { (i, t) ->
                            async {
                                gtxSem.withPermit { i to gtxChunked(t, sl, st.target.code) }
                            }
                        }.awaitAll()
                    }
                    for ((i, tr) in filled) result[i] = tr
                }
                Logs.log("Manga", "tl-tr google batch boxes=" + texts.size + " mapped=" + mapped.size + " refetch=" + missing.size + " ok=" + result.count { !it.isNullOrBlank() })
                return@withContext result
            }
            // Fallback: one call per line.
            if (texts.none { it.isNotBlank() }) return@withContext texts.map { null }
            val done = coroutineScope {
                texts.mapIndexed { i, t ->
                    async {
                        if (t.isBlank()) i to null
                        else gtxSem.withPermit { i to gtxChunked(send[i], sl, st.target.code) }
                    }
                }.awaitAll()
            }
            val out = texts.map { null as String? }.toMutableList()
            for ((i, tr) in done) out[i] = tr
            Logs.log("Manga", "tl-tr google lines boxes=" + texts.size + " ok=" + out.count { !it.isNullOrBlank() })
            out
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

    /**
     * One gtx call through the app-wide gate (see [gtxGate]): serialized,
     * paced, and retried with backoff on HTTP 429 (3 retries: 1s/4s/9s plus
     * jitter, each attempt re-entering the gate so the backoff sleep never
     * blocks other pages). 429s are burst-throttles as often as dead IPs, so
     * a few spaced retries turn most of them into translations — and when the
     * IP is truly burned the log still names it (tl-tr gtx-http-429).
     */
    private suspend fun gtxOne(text: String, sl: String, tl: String): String? {
        val q = URLEncoder.encode(text, "UTF-8")
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=" + sl + "&tl=" + tl + "&dt=t&q=" + q
        var attempt = 0
        while (true) {
            var code = -1
            var raw: String? = null
            var broken = false
            try {
                gtxGate.withLock {
                    val wait = 250 - (System.currentTimeMillis() - gtxLastAt)
                    if (wait > 0) delay(wait)
                    com.hikari.app.net.Http.getQuiet(url).use { resp ->
                        code = resp.code
                        raw = try {
                            resp.body?.string()
                        } catch (e: Exception) {
                            trNote("gtx-body-" + e.javaClass.simpleName)
                            broken = true
                            null
                        }
                    }
                    gtxLastAt = System.currentTimeMillis()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                trNote("gtx-err-" + e.javaClass.simpleName)
                return null
            }
            if (broken) return null
            if (code == 429 && attempt < 3) {
                attempt++
                trNote("gtx-retry429")
                delay(1000L * attempt * attempt + (Math.random() * 500).toLong())
                continue
            }
            if (code !in 200..299) {
                trNote("gtx-http-" + code)
                return null
            }
            val body = raw ?: run {
                trNote("gtx-empty-body")
                return null
            }
            try {
                val root = JSONArray(body)
                val segs = root.optJSONArray(0) ?: run {
                    trNote("gtx-no-segs")
                    return null
                }
                val sb = StringBuilder()
                for (i in 0 until segs.length()) {
                    sb.append(segs.optJSONArray(i)?.optString(0).orEmpty())
                }
                return sb.toString().trim().ifEmpty { null }
            } catch (e: Exception) {
                trNote("gtx-parse")
                return null
            }
        }
    }    /**
     * Long text (novel paragraphs, big batches) blows past the GET URL
     * limit and Google answers 414 — the "Google does nothing" bug. Split
     * on sentence boundaries into ~1200-char pieces, translate each, join.
     */
    // suspend: every piece paces through the app-wide gtx gate (see gtxOne).
    private suspend fun gtxChunked(text: String, sl: String, tl: String): String? {
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
            if (key.isEmpty()) {
                trNote("gemini-no-key")
                return@withContext texts.map { null }
            }
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
            val raw = com.hikari.app.net.Http.postStringQuiet(url, body)
            if (raw == null) {
                trNote("gemini-http-fail")
                return@withContext texts.map { null }
            }
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
                            b.optBoolean("art", false),
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
                        .put("art", b.art)
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
        // MUST stay LEFT: line centering comes from StaticLayout's own
        // ALIGN_CENTER. A CENTER textAlign here double-shifts every line
        // (lines end up right-edge-aligned in the layout's left half), which
        // is exactly the "Hindi hugs the left / spills left" bug.
        textAlign = Paint.Align.LEFT
    }

    private val sfxFillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        // Same as textPaint: StaticLayout centers, paint must not.
        textAlign = Paint.Align.LEFT
    }

    private val sfxStrokePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.BLACK
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        // Same as textPaint: StaticLayout centers, paint must not.
        textAlign = Paint.Align.LEFT
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private data class Fitted(val x: Float, val y: Float, val layout: StaticLayout)

    /** Fill + outline layouts for one art-lettering translation. */
    private data class Stroked(val fill: Fitted, val stroke: Fitted)
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
        // A drawing bug must NEVER take the app down (the 0.12.44
        // coerceIn crash): each box draws defensively, so one bad box is
        // skipped and the rest still paint.
        for ((index, b) in boxes.withIndex()) {
            try {
                drawOne(canvas, loc, index, b)
            } catch (e: Exception) {
            }
        }
    }

    private fun drawOne(canvas: Canvas, loc: (Float, Float) -> PointF?, index: Int, b: TLBox) {
            val p1 = try {
                loc(b.l, b.t)
            } catch (e: Exception) {
                null
            } ?: return
            val p2 = try {
                loc(b.r, b.b)
            } catch (e: Exception) {
                null
            } ?: return
            val l = min(p1.x, p2.x); val t = min(p1.y, p2.y)
            val r = max(p1.x, p2.x); val btm = max(p1.y, p2.y)
            val w = r - l; val h = btm - t
            if (w < 8 || h < 8) return
            if (r < 0 || l > width || btm < 0 || t > height) return
            // Hide the source first, then the translation: a white RECT over
            // the source glyphs (proportional pad — no old lettering shows
            // through), then the translation in a softly rounded box that
            // never leaves the source neighbourhood (width fixed, 1.35x
            // height cap, font shrinks to fit).
            val pad = max(10f, min(w, h) * 0.10f)
            val el = l - pad; val et = t - pad
            val er = r + pad; val eb = btm + pad
            // Hide pass: covers every source pixel plus margin.
            // Hide pass: covers every source pixel plus margin (dialogue
            // only - art lettering below skips the cover on purpose).
            // Page bounds in this view's px: nothing (cover or translation) may
            // ever be drawn outside the page itself (the old "text runs off the
            // left edge" bug) - every rect below is clamped into these.
            val pg0 = try { loc(0f, 0f) } catch (e: Exception) { null }
            val pg1 = try { loc(imgW, imgH) } catch (e: Exception) { null }
            val pgL = if (pg0 != null && pg1 != null) max(0f, min(pg0.x, pg1.x)) else 0f
            val pgT = if (pg0 != null && pg1 != null) max(0f, min(pg0.y, pg1.y)) else 0f
            val pgR = if (pg0 != null && pg1 != null) min(width.toFloat(), max(pg0.x, pg1.x)) else width.toFloat()
            val pgB = if (pg0 != null && pg1 != null) min(height.toFloat(), max(pg0.y, pg1.y)) else height.toFloat()
            if (!b.art) canvas.drawRect(max(el, pgL), max(et, pgT), min(er, pgR), min(eb, pgB), boxPaint)
            val srcW = er - el
            val srcH = eb - et
            if (b.art) {
                // SFX / art lettering: NO white box (a box stickers the art
                // and half-hides big glyphs - the BANG double-show).
                // Translated words draw straight on the art, white with a
                // black outline, sized to the original lettering.
                // Capped to the source neighbourhood so true SFX stays inside
                // it instead of spilling huge across the panel.
                val st = fitStroked(b.trans, srcW * 1.25f, srcH * 1.5f, min(srcW * 0.35f, srcH * 0.60f).coerceAtLeast(10f))
                    ?: return
                val acx = (el + er) / 2f
                val acy = (et + eb) / 2f
                val akey = "sfx" + (srcW / 6).toInt().toString() + "x" + (srcH / 6).toInt().toString() + "|" + b.trans.hashCode().toString()
                fitted[index] = akey to st.fill
                canvas.save()
                canvas.translate(acx - st.fill.layout.width / 2f, acy - st.fill.layout.height / 2f)
                st.stroke.layout.draw(canvas)
                st.fill.layout.draw(canvas)
                canvas.restore()
                return
            }
            // Dialogue geometry (spill-proof by construction): the ANCHOR is
            // the BUBBLE/room center — the source was typeset centered in its
            // bubble, but a PARTIAL OCR box is skewed to one side, so anchoring
            // on the source center dragged Hindi left of the bubble. A found
            // room/outline IS the bubble: any language centers in it.
            var boxCx = (el + er) / 2f
            var boxCy = (et + eb) / 2f
            var roomW = 0f
            var roomH = 0f
            var roomL = 0f
            var roomT = 0f
            var roomR = 0f
            var roomB = 0f
            var hasRoom = false
            if (b.br > b.bl && b.bb > b.bt) {
                try {
                    val q1 = loc(b.bl, b.bt)
                    val q2 = loc(b.br, b.bb)
                    if (q1 != null && q2 != null) {
                        val nl = Math.min(q1.x, q2.x)
                        val nt = Math.min(q1.y, q2.y)
                        val nr = Math.max(q1.x, q2.x)
                        val nb = Math.max(q1.y, q2.y)
                        if (nr - nl >= 12 && nb - nt >= 12 && nr - nl <= srcW * 4f && nb - nt <= srcH * 4f && nl <= el && nt <= et && nr >= er && nb >= eb) {
                            roomW = nr - nl
                            roomH = nb - nt
                            roomL = nl
                            roomT = nt
                            roomR = nr
                            roomB = nb
                            hasRoom = true
                        }
                    }
                } catch (e: Exception) {
                }
            }
            // Room sizes the box (capped: Hindi runs longer than English, but
            // a leaked oversized room must not blow the words past the ink).
            val boxW = if (hasRoom) min(roomW, srcW * 2.2f).coerceAtLeast(16f) else srcW
            var boxH = if (hasRoom) min(roomH, srcH * 2.5f).coerceAtLeast(16f) else srcH
            var f = fit(b.trans, boxW, boxH) ?: return
            val needH = f.layout.height + 8f
            if (needH > boxH) {
                boxH = if (hasRoom) min(roomH, srcH * 2.5f).coerceAtLeast(16f) else min(needH, srcH * 1.35f)
                fit(b.trans, boxW, boxH)?.let { f = it }
            }
            // Floor-size words can still stand taller than the capped box:
            // shrink below the floor (down to 8px) rather than spill up/down.
            if (f.layout.height > boxH * 0.92f) {
                f = shrinkToFit(b.trans, boxW, boxH * 0.92f) ?: return
            }
            // Placement: the bubble/room CENTER (never the skewed source box),
            // then clamped inside the page. A partially-OCR'd source used to
            // drag the translation out of the bubble and off the left edge.
            var cx = if (hasRoom) (roomL + roomR) / 2f else (el + er) / 2f
            var cy = if (hasRoom) (roomT + roomB) / 2f else (et + eb) / 2f
            if (hasRoom) {
                val minCx = roomL + boxW / 2f
                val maxCx = roomR - boxW / 2f
                if (minCx <= maxCx) cx = cx.coerceIn(minCx, maxCx) else cx = (roomL + roomR) / 2f
                val minCy = roomT + boxH / 2f
                val maxCy = roomB - boxH / 2f
                if (minCy <= maxCy) cy = cy.coerceIn(minCy, maxCy) else cy = (roomT + roomB) / 2f
            }
            val pageW = pgR - pgL
            val pageH = pgB - pgT
            if (pageW > 8f && pageH > 8f) {
                val hw = min(boxW, pageW) / 2f
                val hh = min(boxH, pageH) / 2f
                // Float dust can invert these by 1 ulp when the box spans
                // the page (pgL + hw a hair above pgR - hw): coerceIn then
                // throws and takes the whole app down (see the 0.12.44
                // crash: "maximum 540.0 is less than minimum 540.00006").
                // An inverted range just means "center it".
                val loX = pgL + hw
                val hiX = pgR - hw
                cx = if (loX <= hiX) cx.coerceIn(loX, hiX) else (pgL + pgR) / 2f
                val loY = pgT + hh
                val hiY = pgB - hh
                cy = if (loY <= hiY) cy.coerceIn(loY, hiY) else (pgT + pgB) / 2f
            }
            val rad = min(boxW, boxH) * 0.30f
            // The words draw CLIPPED to the cover: even an extreme translation
            // can no longer spill outside the bubble and run down the page.
            val cover = RectF(cx - boxW / 2f, cy - boxH / 2f, cx + boxW / 2f, cy + boxH / 2f)
            canvas.drawRoundRect(cover, rad, rad, boxPaint)
            val key = "${(boxW / 6).toInt()}x${(boxH / 6).toInt()}|${b.trans.hashCode()}"
            fitted[index] = key to f
            canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(cover, rad, rad, Path.Direction.CW) })
            canvas.translate(cx - f.layout.width / 2f, cy - f.layout.height / 2f)
            f.layout.draw(canvas)
            canvas.restore()
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
        // Centered (ALIGN_CENTER below + one shared center in onDraw) and
        // sized to the bubble: short labels keep a MODEST floor (the old
        // 0.24/min-44px floor drew one-word Hindi huge), Devanagari starts
        // smaller with wider side bearing (taller glyphs + matras spill at
        // Latin metrics and looked "left side" when clipped).
        val dev = isDevanagari(text)
        val short = text.length <= 26
        val floor = when {
            short -> max(11f, min(w, h) * 0.16f).coerceAtMost(26f)
            dev -> 10.5f
            else -> 10f
        }
        val startRaw = min(h * 0.55f, w * 0.30f)
        var size = (if (dev) startRaw * 0.80f else startRaw).coerceIn(floor, 140f)
        val availW = (w * if (dev) 0.86f else 0.94f).coerceAtLeast(8f)
        val availH = (h * if (dev) 0.88f else 0.92f).coerceAtLeast(8f)
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
        // The floor itself overflows (Hindi runs long): shrink on down to a
        // 6px hard floor so the words stay INSIDE the bubble. The cover never
        // grows past the room (see drawOne) and the draw is clipped to it, so
        // shrinking — not spilling — is the only way out.
        var small: StaticLayout? = last
        var tiny = floor
        while (small != null && small.height > availH && tiny > 6.5f) {
            tiny = max(6f, tiny - 1f)
            small = buildCentered(text, tiny, availW)
            if (small.height <= availH) {
                return Fitted(-small.width / 2f, 0f, small)
            }
        }
        last = small
        // Even 6px overflows: hand back the smallest layout — drawOne clips it
        // to the cover, so nothing spills down the page.
        return last?.let { Fitted(-it.width / 2f, 0f, it) }
    }

    /** One centered layout at [size]px: the shared builder for the hard-floor
     *  shrink pass (see [fit]). Fresh paint per layout — StaticLayout draws
     *  with its paint live, so a shared paint resized for the next box would
     *  corrupt this one. */
    private fun buildCentered(text: String, size: Float, availW: Float): StaticLayout {
        val paint = TextPaint(textPaint)
        paint.textSize = size
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, availW.toInt())
            .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            builder.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY)
            builder.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE)
        }
        return builder.build()
    }

    /**
     * Art-lettering fit (SFX on the art): white fill + black outline,
     * sized to the original glyphs. Starts at the source height and only
     * shrinks (down to 8px) - never a white box, never a spill.
     */
    private fun fitStroked(text: String, w: Float, h: Float, startSize: Float): Stroked? {
        if (text.isBlank() || w < 16 || h < 16) return null
        var size = startSize.coerceIn(8f, 160f)
        val availW = (w * 0.96f).coerceAtLeast(8f)
        val availH = h.coerceAtLeast(8f)
        val fill = TextPaint(sfxFillPaint)
        val stroke = TextPaint(sfxStrokePaint)
        var lastFill: StaticLayout? = null
        var lastStroke: StaticLayout? = null
        while (true) {
            fill.textSize = size
            stroke.textSize = size
            stroke.strokeWidth = Math.max(1.5f, size * 0.14f)
            val fb = StaticLayout.Builder.obtain(text, 0, text.length, fill, availW.toInt())
                .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                fb.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY)
                fb.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE)
            }
            val fl = fb.build()
            val sb = StaticLayout.Builder.obtain(text, 0, text.length, stroke, availW.toInt())
                .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                sb.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY)
                sb.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE)
            }
            val sl = sb.build()
            lastFill = fl
            lastStroke = sl
            if (fl.height <= availH) {
                return Stroked(Fitted(-fl.width / 2f, 0f, fl), Fitted(-sl.width / 2f, 0f, sl))
            }
            if (size <= 8.5f) break
            size = Math.max(8f, size - 1f)
        }
        return if (lastFill != null && lastStroke != null) {
            Stroked(Fitted(-lastFill.width / 2f, 0f, lastFill), Fitted(-lastStroke.width / 2f, 0f, lastStroke))
        } else null
    }

    /**
     * Last-resort shrink below the readable floor (down to 6px): guarantees
     * the words fit a capped box vertically so nothing spills up/down. Only
     * reached when even the floor layout stands taller than the 1.35x cap.
     */
    private fun shrinkToFit(text: String, w: Float, h: Float): Fitted? {
        if (text.isBlank() || w < 16 || h < 16) return null
        var size = 11f
        val availW = (w * 0.94f).coerceAtLeast(8f)
        val availH = h.coerceAtLeast(8f)
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
            if (size <= 6.5f) break
            size = max(6f, size - 1f)
        }
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
