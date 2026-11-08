package com.hikari.app.dub

import android.content.Context
import android.net.Uri
import java.io.File
import java.lang.reflect.Method

/** What listening to a video gave: the subtitle as SRT (null when none) or a short reason. */
internal class TranscribeOutcome(
    val srt: String?,
    /** Spoken language code, empty when it is not known. */
    val lang: String,
    val lines: Int,
    /** True when the time budget ran out, so only the first part of the video is in it. */
    val partial: Boolean,
    val note: String,
)

/**
 * Voice detection (Silero) + speech-to-text (Whisper) from sherpa-onnx, reached
 * by reflection on purpose: if the sherpa-onnx recogniser classes are not in a
 * build, creating this object throws and the caller turns that into a short
 * message - the app still compiles and everything else keeps working.
 */
internal class SherpaWhisper(modelDir: File, vadModel: File, language: String, threads: Int) {

    private val vad: Any
    private val recognizer: Any

    private val vAccept: Method
    private val vEmpty: Method
    private val vFront: Method
    private val vPop: Method
    private val vFlush: Method
    private val vRelease: Method?

    private val rCreateStream: Method
    private val rDecode: Method
    private val rGetResult: Method
    private val sAccept: Method
    private val sRelease: Method?

    init {
        // ---- voice detection ----
        val silero = make("SileroVadModelConfig")
        put(silero, "model", vadModel.path)
        putOpt(silero, "threshold", 0.5f)
        putOpt(silero, "minSilenceDuration", 0.5f)
        putOpt(silero, "minSpeechDuration", 0.25f)
        putOpt(silero, "windowSize", 512)
        putOpt(silero, "maxSpeechDuration", 20.0f)
        val vadCfg = make("VadModelConfig")
        put(vadCfg, "sileroVadModelConfig", silero)
        putOpt(vadCfg, "sampleRate", 16000)
        putOpt(vadCfg, "numThreads", 1)
        putOpt(vadCfg, "provider", "cpu")
        putOpt(vadCfg, "debug", false)
        val vadCls = cls("Vad")
        vad = construct(vadCls, vadCfg)
        vAccept = method(vadCls, "acceptWaveform", 1)
        vEmpty = method(vadCls, "empty", 0)
        vFront = method(vadCls, "front", 0)
        vPop = method(vadCls, "pop", 0)
        vFlush = method(vadCls, "flush", 0)
        vRelease = methodOrNull(vadCls, "release", 0)

        // ---- speech to text ----
        val whisper = make("OfflineWhisperModelConfig")
        put(whisper, "encoder", File(modelDir, "encoder.onnx").path)
        put(whisper, "decoder", File(modelDir, "decoder.onnx").path)
        putOpt(whisper, "language", language)
        putOpt(whisper, "task", "transcribe")
        val model = make("OfflineModelConfig")
        put(model, "whisper", whisper)
        put(model, "tokens", File(modelDir, "tokens.txt").path)
        putOpt(model, "numThreads", threads)
        putOpt(model, "provider", "cpu")
        putOpt(model, "debug", false)
        putOpt(model, "modelType", "whisper")
        val cfg = make("OfflineRecognizerConfig")
        put(cfg, "modelConfig", model)
        try {
            val feat = make("FeatureConfig")
            putOpt(feat, "sampleRate", 16000)
            putOpt(feat, "featureDim", 80)
            putOpt(cfg, "featConfig", feat)
        } catch (t: Throwable) {
        }
        val recCls = cls("OfflineRecognizer")
        recognizer = try {
            construct(recCls, cfg)
        } catch (t: Throwable) {
            releaseQuietly(vad, vRelease)
            throw t
        }
        rCreateStream = method(recCls, "createStream", 0)
        rDecode = method(recCls, "decode", 1)
        rGetResult = method(recCls, "getResult", 1)
        val streamCls = rCreateStream.returnType
        sAccept = method(streamCls, "acceptWaveform", 2)
        sRelease = methodOrNull(streamCls, "release", 0)
    }

    // ---- voice detection side ----

    fun accept(samples: FloatArray) {
        vAccept.invoke(vad, samples)
    }

    fun flush() {
        vFlush.invoke(vad)
    }

    fun hasSegment(): Boolean = (vEmpty.invoke(vad) as? Boolean) == false

    /** The oldest finished piece of speech: (start sample, samples). Removes it from the queue. */
    fun takeSegment(): Pair<Long, FloatArray>? {
        val seg = vFront.invoke(vad)
        if (seg == null) {
            vPop.invoke(vad)
            return null
        }
        val start = (seg.javaClass.methods.firstOrNull { it.name == "getStart" && it.parameterTypes.isEmpty() }
            ?.invoke(seg) as? Number)?.toLong() ?: 0L
        val samples = seg.javaClass.methods.firstOrNull { it.name == "getSamples" && it.parameterTypes.isEmpty() }
            ?.invoke(seg) as? FloatArray
        vPop.invoke(vad)
        if (samples == null) return null
        return Pair(start, samples)
    }

    // ---- speech to text side ----

    /** Returns (text, language) of one piece of speech. */
    fun transcribe(samples: FloatArray): Pair<String, String> {
        val stream = rCreateStream.invoke(recognizer) ?: return Pair("", "")
        try {
            sAccept.invoke(stream, samples, 16000)
            rDecode.invoke(recognizer, stream)
            val r = rGetResult.invoke(recognizer, stream) ?: return Pair("", "")
            val text = (r.javaClass.methods.firstOrNull { it.name == "getText" && it.parameterTypes.isEmpty() }
                ?.invoke(r) as? String).orEmpty()
            val lang = (r.javaClass.methods.firstOrNull { it.name == "getLang" && it.parameterTypes.isEmpty() }
                ?.invoke(r) as? String).orEmpty()
            return Pair(text, lang)
        } finally {
            try {
                sRelease?.invoke(stream)
            } catch (t: Throwable) {
            }
        }
    }

    fun close() {
        releaseQuietly(vad, vRelease)
        try {
            recognizer.javaClass.methods
                .firstOrNull { it.name == "release" && it.parameterTypes.isEmpty() }
                ?.invoke(recognizer)
        } catch (t: Throwable) {
        }
    }

    private companion object {
        const val PKG = "com.k2fsa.sherpa.onnx."

        fun cls(name: String): Class<*> = Class.forName(PKG + name)

        /** A config object with all its defaults (Kotlin gives such classes a no-argument constructor). */
        fun make(name: String): Any = cls(name).getConstructor().newInstance()

        fun setterOf(obj: Any, prop: String): Method? {
            val name = "set" + prop.replaceFirstChar { it.uppercase() }
            return obj.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == 1 }
        }

        fun put(obj: Any, prop: String, value: Any?) {
            val m = setterOf(obj, prop) ?: throw NoSuchMethodException(obj.javaClass.simpleName + "." + prop)
            m.invoke(obj, value)
        }

        /** Same, but a setting that this sherpa-onnx release does not have is simply skipped. */
        fun putOpt(obj: Any, prop: String, value: Any?) {
            try {
                val m = setterOf(obj, prop) ?: return
                m.invoke(obj, value)
            } catch (t: Throwable) {
            }
        }

        /** Builds [c] from its config: the constructor whose last parameter is the config (a leading asset manager stays null). */
        fun construct(c: Class<*>, config: Any): Any {
            val ctor = c.constructors.filter { !it.isSynthetic }.firstOrNull { k ->
                val p = k.parameterTypes
                p.isNotEmpty() && p[p.size - 1].isInstance(config)
            } ?: throw NoSuchMethodException(c.simpleName)
            val args = arrayOfNulls<Any>(ctor.parameterTypes.size)
            args[args.size - 1] = config
            return ctor.newInstance(*args)
        }

        fun methodOrNull(c: Class<*>, name: String, params: Int): Method? =
            c.methods.firstOrNull { it.name == name && it.parameterTypes.size == params && !it.isSynthetic }

        fun method(c: Class<*>, name: String, params: Int): Method =
            methodOrNull(c, name, params) ?: throw NoSuchMethodException(c.simpleName + "." + name)

        fun releaseQuietly(obj: Any, release: Method?) {
            try {
                release?.invoke(obj)
            } catch (t: Throwable) {
            }
        }
    }
}

/**
 * Listens to the sound of a video and writes what is said as an SRT subtitle.
 * Runs in the voice helper process (see [NeuralTtsService]). Time budget:
 * [BUDGET_MS]; what is written by then is kept and the result is marked partial.
 */
internal object VoiceTranscriber {

    private const val BUDGET_MS = 40L * 60_000L

    fun run(
        ctx: Context,
        url: String,
        headers: Map<String, String>,
        hlsHint: Boolean,
        durationMs: Long,
        language: String,
        modelDir: File,
        vadModel: File,
        threads: Int,
        cancelled: () -> Boolean,
        progress: (Int, String) -> Unit,
    ): TranscribeOutcome {
        val lower = url.lowercase()
        val isFile = lower.startsWith("file:") || url.startsWith("/")
        val isHttp = lower.startsWith("http://") || lower.startsWith("https://")
        if (!isFile && !isHttp) return fail("this kind of video link cannot be read")

        val engine = try {
            SherpaWhisper(modelDir, vadModel, language, threads)
        } catch (t: Throwable) {
            return fail("speech-to-text is not available in this build (" + t.javaClass.simpleName + ")")
        }

        try {
            val deadline = System.nanoTime() + BUDGET_MS * 1_000_000L
            var timedOut = false
            val stop: () -> Boolean = {
                if (System.nanoTime() > deadline) timedOut = true
                cancelled() || timedOut
            }
            val feeder = SpeechFeeder(engine, durationMs, stop, progress)

            var gotSound: Boolean
            if (isFile) {
                val path = if (lower.startsWith("file:")) (Uri.parse(url).path ?: "") else url
                gotSound = AudioDecode.run({ ex -> ex.setDataSource(path) }, 0L, feeder, stop)
            } else {
                val tmp = File(ctx.cacheDir, "dub/gen_tmp")

                fun viaHls(): Boolean {
                    val plan = Hls.plan(url, headers) ?: return false
                    return Hls.run(plan, headers, { _, _ -> true }, feeder, tmp, stop)
                }

                fun viaFile(): Boolean =
                    AudioDecode.run({ ex -> ex.setDataSource(url, headers) }, 0L, feeder, stop)

                val looksHls = hlsHint || lower.contains(".m3u8")
                if (looksHls) {
                    gotSound = viaHls()
                    if (!gotSound && !stop()) gotSound = viaFile()
                } else {
                    gotSound = viaFile()
                    if (!gotSound && !stop()) gotSound = viaHls()
                }
            }

            if (cancelled()) return fail("cancelled")
            feeder.finish()

            if (!gotSound && feeder.cues.isEmpty()) return fail("the sound of this video could not be read")
            if (feeder.cues.isEmpty()) return fail("no speech was found in this video")

            val lang = if (language.isNotBlank()) language else feeder.topLanguage()
            return TranscribeOutcome(buildSrt(feeder.cues), lang, feeder.cues.size, timedOut, "")
        } catch (t: Throwable) {
            return fail(t.javaClass.simpleName + ": " + (t.message ?: ""))
        } finally {
            engine.close()
        }
    }

    private fun fail(note: String) = TranscribeOutcome(null, "", 0, false, note)

    private class Cue(val startMs: Long, val endMs: Long, val text: String)

    /** Receives the sound in order, finds pieces of speech and writes each one down. */
    private class SpeechFeeder(
        private val engine: SherpaWhisper,
        private val durationMs: Long,
        private val stop: () -> Boolean,
        private val progress: (Int, String) -> Unit,
    ) : PcmSink {

        val cues = ArrayList<Cue>()
        private val langVotes = HashMap<String, Int>()

        /** Samples given to the voice detector so far. */
        private var fed = 0L

        /** Where the video's timeline is ahead of the samples given (after a long hole in the sound). */
        private var delta = 0L

        override fun put(at: Long, pcm: ShortArray, n: Int) {
            if (n <= 0 || stop()) return
            var from = 0
            var count = n
            val expected = delta + fed
            if (at < expected) {
                val skip = expected - at
                if (skip >= count) return
                from = skip.toInt()
                count -= from
            } else if (at > expected) {
                val gap = at - expected
                if (gap <= MAX_FILL) {
                    // a short hole: fill it with silence so the timeline stays right
                    var left = gap.toInt()
                    while (left > 0) {
                        val m = minOf(left, 16000)
                        feed(ShortArray(m), 0, m)
                        left -= m
                    }
                } else {
                    // a long jump: close the speech that is open, then move the timeline on
                    engine.flush()
                    drain()
                    delta += gap
                }
            }
            feed(pcm, from, count)
        }

        private fun feed(pcm: ShortArray, from: Int, count: Int) {
            var i = 0
            while (i < count) {
                val m = minOf(count - i, 16000)
                val f = FloatArray(m)
                for (q in 0 until m) f[q] = pcm[from + i + q] / 32768f
                engine.accept(f)
                fed += m
                i += m
                drain()
                if (stop()) return
            }
        }

        fun finish() {
            engine.flush()
            drain()
        }

        private fun drain() {
            while (engine.hasSegment()) {
                val seg = engine.takeSegment() ?: continue
                if (stop()) return
                write(seg.first + delta, seg.second)
            }
        }

        private fun write(startSample: Long, samples: FloatArray) {
            if (samples.size < 4800) return // less than 0.3 s cannot be a word
            val (raw, lang) = engine.transcribe(samples)
            val text = clean(raw)
            val startMs = startSample * 1000L / 16000L
            val endMs = startMs + samples.size.toLong() * 1000L / 16000L
            if (text.isNotEmpty()) {
                for (c in splitCue(startMs, endMs, text)) cues.add(c)
                val code = lang.removePrefix("<|").removeSuffix("|>").trim()
                if (code.isNotEmpty()) langVotes[code] = (langVotes[code] ?: 0) + 1
            }
            val pct = if (durationMs > 0L) (endMs * 100L / durationMs).toInt().coerceIn(0, 100) else -1
            progress(pct, cues.size.toString())
        }

        fun topLanguage(): String {
            var best = ""
            var bestN = 0
            for ((k, v) in langVotes) {
                if (v > bestN) {
                    best = k
                    bestN = v
                }
            }
            return best
        }

        private companion object {
            /** Holes in the sound up to 30 s are filled with silence. */
            const val MAX_FILL = 30L * 16000L
        }
    }

    /** Trims the text and drops what is not speech ("[BLANK_AUDIO]", "(music)", lone symbols). */
    private fun clean(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        if (t.none { it.isLetterOrDigit() }) return ""
        val first = t.first()
        val last = t.last()
        if ((first == '[' && last == ']') || (first == '(' && last == ')')) return ""
        return t
    }

    /** A long text is cut into readable lines; the time is shared by length. */
    private fun splitCue(startMs: Long, endMs: Long, text: String): List<Cue> {
        val max = 84
        if (text.length <= max) return listOf(Cue(startMs, endMs, text))
        val pieces = ArrayList<String>()
        if (text.contains(' ')) {
            var cur = StringBuilder()
            for (w in text.split(' ').filter { it.isNotEmpty() }) {
                if (cur.isNotEmpty() && cur.length + 1 + w.length > max) {
                    pieces.add(cur.toString())
                    cur = StringBuilder()
                }
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(w)
                val e = w.last()
                if (cur.length >= 40 && (e == '.' || e == '?' || e == '!')) {
                    pieces.add(cur.toString())
                    cur = StringBuilder()
                }
            }
            if (cur.isNotEmpty()) pieces.add(cur.toString())
        } else {
            // no spaces (Japanese, Chinese ...): fixed-size pieces
            var i = 0
            while (i < text.length) {
                val e = minOf(text.length, i + 30)
                pieces.add(text.substring(i, e))
                i = e
            }
        }
        val total = pieces.sumOf { it.length }.coerceAtLeast(1)
        val span = (endMs - startMs).coerceAtLeast(0L)
        val out = ArrayList<Cue>()
        var t = startMs
        var used = 0
        for (idx in pieces.indices) {
            used += pieces[idx].length
            val e = if (idx == pieces.size - 1) endMs else startMs + span * used / total
            out.add(Cue(t, e, pieces[idx]))
            t = e
        }
        return out
    }

    private fun buildSrt(cues: List<Cue>): String {
        val sb = StringBuilder()
        var n = 1
        for (c in cues) {
            val end = if (c.endMs > c.startMs) c.endMs else c.startMs + 500L
            sb.append(n).append('\n')
            sb.append(stamp(c.startMs)).append(" --> ").append(stamp(end)).append('\n')
            sb.append(c.text).append("\n\n")
            n++
        }
        return sb.toString()
    }

    private fun stamp(ms: Long): String {
        val v = if (ms < 0L) 0L else ms
        val h = v / 3_600_000L
        val m = (v / 60_000L) % 60L
        val s = (v / 1000L) % 60L
        val x = v % 1000L
        return String.format(java.util.Locale.US, "%02d:%02d:%02d,%03d", h, m, s, x)
    }
}
