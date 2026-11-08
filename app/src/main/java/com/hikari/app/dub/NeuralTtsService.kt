package com.hikari.app.dub

import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The few words the player and the voice helper say to each other. */
internal object NeuralIpc {
    const val MSG_SYNTH = 1
    const val MSG_RESULT = 2
    /** Listen to the original sound of a video: who speaks each subtitle line, man or woman. */
    const val MSG_SCAN = 3
    const val MSG_SCAN_PROGRESS = 4
    const val MSG_SCAN_RESULT = 5
    const val MSG_SCAN_CANCEL = 6
    /** Listen to the voice of a video and write it down as a subtitle (speech to text). */
    const val MSG_GEN = 7
    const val MSG_GEN_PROGRESS = 8
    const val MSG_GEN_RESULT = 9
    const val MSG_GEN_CANCEL = 10
    const val K_URL = "url"
    const val K_HEADERS = "headers"
    const val K_HLS = "hls"
    const val K_STARTS = "starts"
    const val K_ENDS = "ends"
    const val K_OFFSET = "offset"
    const val K_MODEL = "model"
    const val K_PCT = "pct"
    const val K_NOTE = "note"
    const val K_ID = "id"
    /** Folder of the voice to use (model.onnx + tokens.txt). */
    const val K_DIR = "dir"
    /** Folder with the espeak-ng data (shared by all voices). */
    const val K_DATA = "data"
    const val K_TEXT = "text"
    const val K_SID = "sid"
    const val K_SPEED = "speed"
    const val K_OUT = "out"
    const val K_VARIANT = "variant"
    /** Subtitle writing: folder of the speech model (encoder.onnx, decoder.onnx, tokens.txt). */
    const val K_WDIR = "wdir"
    /** Subtitle writing: the speech-detection model file (silero_vad.onnx). */
    const val K_VAD = "vad"
    /** Subtitle writing: spoken language code, empty = decide by listening. */
    const val K_LANG = "lang"
    const val K_DURATION = "duration"
    const val K_THREADS = "threads"
    const val K_COUNT = "count"
    const val K_PARTIAL = "partial"
    /**
     * "kokoro" = the English Kokoro pack (several speakers in one model);
     * "pocket" = the Pocket TTS cloning model (the voice is copied from [K_REF]);
     * anything else = a Piper voice.
     */
    const val K_KIND = "kind"
    /** Pocket only: the recording to copy the voice from (an asset path, or an absolute file path). */
    const val K_REF = "ref"
    /** Pocket only: how many refinement steps the voice generator runs (fewer = faster). */
    const val K_STEPS = "steps"
    /** Where the helper writes what it is doing, so that if it dies we know where. */
    const val TRACE_FILE = "helper_trace.txt"
    const val K_OK = "ok"
    const val K_ERR = "err"
    /** Answer of a voice request: how many ms the helper needed to make the line. */
    const val K_MS = "ms"
}

/**
 * The neural voice lives in its OWN process (see the manifest: process
 * ":neuraltts"). The voice model is big and uses native code; if it ever runs
 * out of memory or crashes, only this helper process dies - the player and the
 * video keep playing, and the dub simply falls back to the normal voice.
 */
class NeuralTtsService : Service() {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var messenger: Messenger? = null
    /** Voices that are loaded right now, least recently used first. */
    private val engines = LinkedHashMap<String, OfflineTts>(4, 0.75f, true)

    /** Voice scans that are running (id -> "stop" flag). */
    private val scans = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.atomic.AtomicBoolean>()

    /** Subtitle writings that are running (id -> "stop" flag). */
    private val gens = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.atomic.AtomicBoolean>()

    /** A reference recording, decoded once (voice cloning). */
    private class Reference(val samples: FloatArray, val rate: Int)
    private val references = HashMap<String, Reference>()

    /**
     * Two lanes make voices at the same time: the slow cloning model on one, the fast Piper /
     * Kokoro voices on the other. A cloned line therefore never keeps the men's lines waiting
     * (before, every line stood in ONE queue behind the slow cloned ones, so the dub could not
     * keep up with the video and lines were skipped).
     */
    private var pocketLane: java.util.concurrent.ExecutorService? = null
    private var voiceLane: java.util.concurrent.ExecutorService? = null

    /** Keys of the engines that are making a line right now (never unloaded). Guarded by [engines]. */
    private val busy = HashSet<String>()
    /** Only one voice is loaded at a time (loading is the memory peak). */
    private val loadLock = Any()

    override fun onCreate() {
        super.onCreate()
        trace("service started")
        val t = HandlerThread("neural-tts", Process.THREAD_PRIORITY_BACKGROUND)
        t.start()
        thread = t
        val h = Handler(t.looper) { msg ->
            handle(msg)
            true
        }
        handler = h
        messenger = Messenger(h)
        pocketLane = newLane("neural-pocket")
        voiceLane = newLane("neural-voice")
    }

    /**
     * One thread that makes voices. NOT the background priority: at that priority Android keeps
     * the thread on the slow cores with a small share of the CPU, which is what made the
     * neural voice crawl while the video was playing.
     */
    private fun newLane(laneName: String): java.util.concurrent.ExecutorService {
        val factory = java.util.concurrent.ThreadFactory { r ->
            val t = Thread({
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
                } catch (e: Throwable) {
                }
                r.run()
            }, laneName)
            t.isDaemon = true
            t
        }
        return java.util.concurrent.Executors.newSingleThreadExecutor(factory)
    }

    override fun onBind(intent: Intent?): IBinder? = messenger?.binder

    override fun onDestroy() {
        for (flag in scans.values) flag.set(true)
        for (flag in gens.values) flag.set(true)
        try { pocketLane?.shutdownNow() } catch (e: Throwable) {}
        try { voiceLane?.shutdownNow() } catch (e: Throwable) {}
        val t = thread
        val h = handler
        if (t != null && h != null) {
            h.post {
                trimEngines(0, force = true)
                t.quitSafely()
            }
        }
        super.onDestroy()
    }

    /** The phone is short of memory: keep only the voice that spoke last. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            handler?.post { trimEngines(1) }
        }
    }

    /** Unloads the least recently used voices until [keep] are left; never one that is speaking now. */
    private fun trimEngines(keep: Int, force: Boolean = false) {
        synchronized(engines) {
            val it = engines.entries.iterator()
            while (engines.size > keep && it.hasNext()) {
                val e = it.next()
                if (!force && busy.contains(e.key)) continue
                it.remove()
                try { e.value.release() } catch (t: Throwable) {}
            }
        }
    }

    private fun handle(msg: Message) {
        when (msg.what) {
            NeuralIpc.MSG_SYNTH -> dispatchSynth(msg)
            NeuralIpc.MSG_SCAN -> startScan(msg)
            NeuralIpc.MSG_SCAN_CANCEL -> {
                val flag = scans[msg.data.getInt(NeuralIpc.K_ID)]
                if (flag != null) flag.set(true)
            }
            NeuralIpc.MSG_GEN -> startGenerate(msg)
            NeuralIpc.MSG_GEN_CANCEL -> {
                val flag = gens[msg.data.getInt(NeuralIpc.K_ID)]
                if (flag != null) flag.set(true)
            }
        }
    }

    /**
     * Listens to the original sound of a video (decode, pitch, voice
     * fingerprints) on a thread of its own, so a line being voiced is never
     * kept waiting. Replies with progress messages and one final result; the
     * answer itself is written to the file named in K_OUT.
     */
    private fun startScan(msg: Message) {
        val d = Bundle(msg.data)
        val id = d.getInt(NeuralIpc.K_ID)
        val reply = msg.replyTo
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        scans[id] = cancelled
        val worker = Thread {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
            } catch (t: Throwable) {
            }
            val answer = Bundle()
            answer.putInt(NeuralIpc.K_ID, id)
            var embedder: SpeakerEmbedder? = null
            try {
                val flat: Array<String> = d.getStringArray(NeuralIpc.K_HEADERS) ?: arrayOf<String>()
                val headers = LinkedHashMap<String, String>()
                var i = 0
                while (i + 1 < flat.size) {
                    headers[flat[i]] = flat[i + 1]
                    i += 2
                }
                val url = d.getString(NeuralIpc.K_URL) ?: error("no video address")
                val starts = d.getLongArray(NeuralIpc.K_STARTS) ?: error("no subtitle lines")
                val ends = d.getLongArray(NeuralIpc.K_ENDS) ?: error("no subtitle lines")
                val out = File(d.getString(NeuralIpc.K_OUT) ?: error("no output file"))
                val model = d.getString(NeuralIpc.K_MODEL).orEmpty()
                if (model.isNotEmpty()) {
                    trace("scan: loading the voice-fingerprint model")
                    embedder = SpeakerEmbedder.create(model, 2)
                    trace(if (embedder != null) "scan: model loaded" else "scan: model could not be loaded, pitch only")
                }
                val progress: (Int, String) -> Unit = { pct, note ->
                    val m = Message.obtain(null, NeuralIpc.MSG_SCAN_PROGRESS)
                    val b = Bundle()
                    b.putInt(NeuralIpc.K_ID, id)
                    b.putInt(NeuralIpc.K_PCT, pct)
                    b.putString(NeuralIpc.K_NOTE, note)
                    m.data = b
                    try {
                        reply?.send(m)
                    } catch (e: Throwable) {
                    }
                }
                trace("scan: start, " + starts.size + " lines")
                val outcome = VoiceScanner.run(
                    ctx = applicationContext,
                    url = url,
                    headers = headers,
                    hlsHint = d.getBoolean(NeuralIpc.K_HLS),
                    startsMs = starts,
                    endsMs = ends,
                    offsetMs = d.getLong(NeuralIpc.K_OFFSET),
                    embedder = embedder,
                    cancelled = { cancelled.get() },
                    progress = progress,
                )
                val result = outcome.result
                if (result != null) {
                    out.parentFile?.mkdirs()
                    val tmp = File(out.path + ".part")
                    tmp.writeText(result.serialize())
                    if (out.exists()) out.delete()
                    if (!tmp.renameTo(out)) error("could not save the answer")
                    answer.putBoolean(NeuralIpc.K_OK, true)
                    answer.putString(NeuralIpc.K_NOTE, result.summary())
                    trace("scan: done, " + result.summary())
                } else {
                    answer.putBoolean(NeuralIpc.K_OK, false)
                    answer.putString(NeuralIpc.K_ERR, outcome.note.ifEmpty { "no answer" })
                    trace("scan: no result, " + outcome.note)
                }
            } catch (t: Throwable) {
                trace("scan error: " + t.javaClass.simpleName + " " + (t.message ?: ""))
                answer.putBoolean(NeuralIpc.K_OK, false)
                answer.putString(NeuralIpc.K_ERR, t.javaClass.simpleName + ": " + (t.message ?: ""))
            } finally {
                scans.remove(id)
                try {
                    embedder?.close()
                } catch (t: Throwable) {
                }
            }
            val done = Message.obtain(null, NeuralIpc.MSG_SCAN_RESULT)
            done.data = answer
            try {
                reply?.send(done)
            } catch (e: Throwable) {
            }
        }
        worker.name = "voice-scan"
        worker.isDaemon = true
        worker.start()
    }

    /**
     * Listens to the voice of a video and writes it down as an SRT subtitle
     * (voice detection + Whisper, see [VoiceTranscriber]) on a thread of its
     * own. Replies with progress messages and one final result; the subtitle
     * text itself is written to the file named in K_OUT.
     */
    private fun startGenerate(msg: Message) {
        val d = Bundle(msg.data)
        val id = d.getInt(NeuralIpc.K_ID)
        val reply = msg.replyTo
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        gens[id] = cancelled
        val worker = Thread {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
            } catch (t: Throwable) {
            }
            val answer = Bundle()
            answer.putInt(NeuralIpc.K_ID, id)
            try {
                val flat: Array<String> = d.getStringArray(NeuralIpc.K_HEADERS) ?: arrayOf<String>()
                val headers = LinkedHashMap<String, String>()
                var i = 0
                while (i + 1 < flat.size) {
                    headers[flat[i]] = flat[i + 1]
                    i += 2
                }
                val url = d.getString(NeuralIpc.K_URL) ?: error("no video address")
                val out = File(d.getString(NeuralIpc.K_OUT) ?: error("no output file"))
                val modelDir = File(d.getString(NeuralIpc.K_WDIR) ?: error("no speech model folder"))
                val vadModel = File(d.getString(NeuralIpc.K_VAD) ?: error("no speech-detection model"))
                val progress: (Int, String) -> Unit = { pct, note ->
                    val m = Message.obtain(null, NeuralIpc.MSG_GEN_PROGRESS)
                    val b = Bundle()
                    b.putInt(NeuralIpc.K_ID, id)
                    b.putInt(NeuralIpc.K_PCT, pct)
                    b.putString(NeuralIpc.K_NOTE, note)
                    m.data = b
                    try {
                        reply?.send(m)
                    } catch (e: Throwable) {
                    }
                }
                trace("subtitle: start")
                val outcome = VoiceTranscriber.run(
                    ctx = applicationContext,
                    url = url,
                    headers = headers,
                    hlsHint = d.getBoolean(NeuralIpc.K_HLS),
                    durationMs = d.getLong(NeuralIpc.K_DURATION),
                    language = d.getString(NeuralIpc.K_LANG).orEmpty(),
                    modelDir = modelDir,
                    vadModel = vadModel,
                    threads = d.getInt(NeuralIpc.K_THREADS, 2).coerceIn(1, 4),
                    cancelled = { cancelled.get() },
                    progress = progress,
                )
                val srt = outcome.srt
                if (srt != null) {
                    out.parentFile?.mkdirs()
                    val tmp = File(out.path + ".part")
                    tmp.writeText(srt, Charsets.UTF_8)
                    if (out.exists()) out.delete()
                    if (!tmp.renameTo(out)) error("could not save the subtitle")
                    answer.putBoolean(NeuralIpc.K_OK, true)
                    answer.putString(NeuralIpc.K_LANG, outcome.lang)
                    answer.putInt(NeuralIpc.K_COUNT, outcome.lines)
                    answer.putBoolean(NeuralIpc.K_PARTIAL, outcome.partial)
                    trace("subtitle: done, " + outcome.lines + " lines, language " + outcome.lang)
                } else {
                    answer.putBoolean(NeuralIpc.K_OK, false)
                    answer.putString(NeuralIpc.K_ERR, outcome.note.ifEmpty { "no answer" })
                    trace("subtitle: no result, " + outcome.note)
                }
            } catch (t: Throwable) {
                trace("subtitle error: " + t.javaClass.simpleName + " " + (t.message ?: ""))
                answer.putBoolean(NeuralIpc.K_OK, false)
                answer.putString(NeuralIpc.K_ERR, t.javaClass.simpleName + ": " + (t.message ?: ""))
            } finally {
                gens.remove(id)
            }
            val done = Message.obtain(null, NeuralIpc.MSG_GEN_RESULT)
            done.data = answer
            try {
                reply?.send(done)
            } catch (e: Throwable) {
            }
        }
        worker.name = "subtitle-gen"
        worker.isDaemon = true
        worker.start()
    }

    /** Hands a voice request to its lane (the message is recycled when this returns, so copy it first). */
    private fun dispatchSynth(msg: Message) {
        val data = Bundle(msg.data)
        val replyTo = msg.replyTo
        val lane = if (data.getString(NeuralIpc.K_KIND) == "pocket") pocketLane else voiceLane
        if (lane == null) {
            handleSynth(data, replyTo)
            return
        }
        try {
            lane.execute { handleSynth(data, replyTo) }
        } catch (t: Throwable) {
            val answer = Bundle()
            answer.putInt(NeuralIpc.K_ID, data.getInt(NeuralIpc.K_ID))
            answer.putBoolean(NeuralIpc.K_OK, false)
            answer.putString(NeuralIpc.K_ERR, "the voice helper is shutting down")
            val r = Message.obtain(null, NeuralIpc.MSG_RESULT)
            r.data = answer
            try { replyTo?.send(r) } catch (e: Throwable) {}
        }
    }

    private fun handleSynth(data: Bundle, replyTo: Messenger?) {
        val id = data.getInt(NeuralIpc.K_ID)
        val answer = Bundle()
        answer.putInt(NeuralIpc.K_ID, id)
        try {
            val tookMs = synth(data)
            answer.putBoolean(NeuralIpc.K_OK, true)
            answer.putLong(NeuralIpc.K_MS, tookMs)
        } catch (t: Throwable) {
            trace("error: " + t.javaClass.simpleName + " " + (t.message ?: ""))
            answer.putBoolean(NeuralIpc.K_OK, false)
            answer.putString(NeuralIpc.K_ERR, t.javaClass.simpleName + ": " + (t.message ?: ""))
        }
        val reply = Message.obtain(null, NeuralIpc.MSG_RESULT)
        reply.data = answer
        try { replyTo?.send(reply) } catch (e: Throwable) {}
    }

    /** Makes one line. Returns how many ms the voice itself needed (without loading it). */
    private fun synth(b: Bundle): Long {
        val dir = File(b.getString(NeuralIpc.K_DIR) ?: error("no voice folder"))
        // the espeak data folder (Piper / Kokoro only; the cloning model does not use it)
        val data = File(b.getString(NeuralIpc.K_DATA).orEmpty())
        val text = b.getString(NeuralIpc.K_TEXT) ?: error("no text")
        val sid = b.getInt(NeuralIpc.K_SID)
        val speed = b.getFloat(NeuralIpc.K_SPEED, 1.0f).coerceIn(0.6f, 1.6f)
        val out = File(b.getString(NeuralIpc.K_OUT) ?: error("no output file"))
        val variant = b.getInt(NeuralIpc.K_VARIANT)
        val kind = b.getString(NeuralIpc.K_KIND) ?: "piper"
        val pocket = kind == "pocket"

        val lease = acquireEngine(dir, data, variant, kind)
        val engine = lease.engine
        val started = System.nanoTime()
        trace("speaking: " + dir.name + ", " + text.length + " characters")
        val audio = try {
            if (pocket) {
                val refPath = b.getString(NeuralIpc.K_REF) ?: error("no reference voice")
                val ref = loadReference(refPath)
                trace("cloning the voice of: " + refPath + " (" + (ref.samples.size * 1000L / ref.rate.coerceAtLeast(1)) + " ms)")
                val cfg = GenerationConfig(
                    speed = 1.0f,
                    sid = 0,
                    referenceAudio = ref.samples,
                    referenceSampleRate = ref.rate,
                    numSteps = b.getInt(NeuralIpc.K_STEPS, 2).coerceIn(1, 8),
                )
                engine.generateWithConfig(text, cfg)
            } else {
                engine.generate(text = text, sid = sid, speed = speed)
            }
        } finally {
            releaseEngine(lease)
        }
        val samples = audio.samples
        if (samples.isEmpty()) error("the voice returned no sound")
        val tookMs = (System.nanoTime() - started) / 1_000_000L
        val audioMs = samples.size * 1000L / audio.sampleRate.coerceAtLeast(1)
        trace("done: " + audioMs + " ms of sound made in " + tookMs + " ms")

        val tmp = File(out.path + ".part")
        writeWav(tmp, samples, audio.sampleRate)
        if (out.exists()) out.delete()
        if (!tmp.renameTo(out)) error("could not save the audio")
        return tookMs
    }

    /**
     * The recording a cloned voice is copied from: a WAV (16-bit) inside the APK's assets
     * ("dub/voices/myira.wav") or, when the path starts with "/", a file on the phone.
     */
    private fun loadReference(path: String): Reference {
        references[path]?.let { return it }
        val bytes = if (path.startsWith("/")) File(path).readBytes()
        else assets.open(path).use { it.readBytes() }
        val ref = parseWav(bytes)
        references[path] = ref
        return ref
    }

    private fun parseWav(b: ByteArray): Reference {
        if (b.size < 44 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(b, 8, 4, Charsets.US_ASCII) != "WAVE"
        ) error("the reference voice is not a WAV file")
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var channels = 1
        var rate = 0
        var bits = 16
        var dataStart = -1
        var dataLen = 0
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.US_ASCII)
            val size = bb.getInt(pos + 4)
            val body = pos + 8
            if (id == "fmt " && body + 16 <= b.size) {
                channels = bb.getShort(body + 2).toInt() and 0xFFFF
                rate = bb.getInt(body + 4)
                bits = bb.getShort(body + 14).toInt() and 0xFFFF
            } else if (id == "data") {
                dataStart = body
                dataLen = if (size < 0 || body + size > b.size) b.size - body else size
                break
            }
            if (size < 0) break
            pos = body + size + (size and 1)
        }
        if (dataStart < 0 || rate <= 0 || channels <= 0 || bits != 16) {
            error("the reference voice must be a 16-bit WAV file")
        }
        val frames = dataLen / (2 * channels)
        if (frames <= 0) error("the reference voice is empty")
        val out = FloatArray(frames)
        var p = dataStart
        for (i in 0 until frames) {
            var acc = 0f
            for (c in 0 until channels) {
                acc += bb.getShort(p).toFloat() / 32768f
                p += 2
            }
            out[i] = acc / channels
        }
        return Reference(out, rate)
    }

    /**
     * [variant] is a safety net: if one way of starting the voice makes the
     * helper die, the player tries the next way on the following attempt.
     *   0 = 3 voices kept in memory, up to 3 threads
     *   1 = 2 voices kept in memory, 2 threads
     *   2 = 1 voice kept in memory, 1 thread
     *
     * [kind] is "piper", "kokoro" or "pocket" (see [NeuralIpc.K_KIND]). Both cloned women
     * share ONE loaded cloning model (the voice comes from the recording, not the model).
     */
    private class Lease(val key: String, val engine: OfflineTts)

    /**
     * Gives the loaded voice for [dir] (loading it first if needed) and marks it as in use, so
     * the other lane can never unload it while it speaks. Give it back with [releaseEngine].
     */
    private fun acquireEngine(dir: File, data: File, variant: Int, kind: String): Lease {
        val key = dir.path + "|" + variant
        synchronized(engines) {
            val e = engines[key]
            if (e != null) {
                busy.add(key)
                return Lease(key, e)
            }
        }
        synchronized(loadLock) {
            synchronized(engines) {
                val e = engines[key]
                if (e != null) {               // the other lane loaded it while we waited
                    busy.add(key)
                    return Lease(key, e)
                }
                // make room (a loaded voice costs about 100-150 MB)
                val cap = when (variant) { 0 -> 3; 1 -> 2; else -> 1 }
                trimEngines(cap - 1)
            }
            val created = createEngine(dir, data, variant, kind)
            synchronized(engines) {
                engines[key] = created
                busy.add(key)
            }
            return Lease(key, created)
        }
    }

    private fun releaseEngine(lease: Lease) {
        synchronized(engines) { busy.remove(lease.key) }
    }

    private fun createEngine(dir: File, data: File, variant: Int, kind: String): OfflineTts {
        val kokoro = kind == "kokoro"
        val pocket = kind == "pocket"
        val model = when {
            pocket -> File(dir, "lm_main.int8.onnx")
            kokoro -> {
                val int8 = File(dir, "model.int8.onnx")
                if (int8.length() > 0) int8 else File(dir, "model.onnx")
            }
            else -> File(dir, "model.onnx")
        }
        val threads = when (variant) {
            0 -> (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 3)
            1 -> 2
            else -> 1
        }
        trace("loading voice: " + dir.name + " " + (model.length() / 1_000_000L) + " MB, way " + variant + ", threads " + threads)
        val modelCfg = when {
            pocket -> OfflineTtsModelConfig(
                pocket = OfflineTtsPocketModelConfig(
                    lmFlow = File(dir, "lm_flow.int8.onnx").path,
                    lmMain = File(dir, "lm_main.int8.onnx").path,
                    encoder = File(dir, "encoder.onnx").path,
                    decoder = File(dir, "decoder.int8.onnx").path,
                    textConditioner = File(dir, "text_conditioner.onnx").path,
                    vocabJson = File(dir, "vocab.json").path,
                    tokenScoresJson = File(dir, "token_scores.json").path,
                ),
                numThreads = threads,
                debug = false,
                provider = "cpu",
            )
            kokoro -> {
                // English lexicon first, Chinese one after it (the pack is multi-language).
                val lexicon = listOf("lexicon-us-en.txt", "lexicon-zh.txt")
                    .map { File(dir, it) }
                    .filter { it.length() > 0 }
                    .joinToString(",") { it.path }
                val dict = File(dir, "dict")
                OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = model.path,
                        voices = File(dir, "voices.bin").path,
                        tokens = File(dir, "tokens.txt").path,
                        dataDir = data.path,
                        lexicon = lexicon,
                        dictDir = if (dict.isDirectory) dict.path else "",
                    ),
                    numThreads = threads,
                    debug = false,
                    provider = "cpu",
                )
            }
            else -> OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = model.path,
                    tokens = File(dir, "tokens.txt").path,
                    dataDir = data.path,
                ),
                numThreads = threads,
                debug = false,
                provider = "cpu",
            )
        }
        val cfg = OfflineTtsConfig(model = modelCfg)
        val created = OfflineTts(config = cfg)
        trace("voice loaded: " + dir.name)
        return created
    }

    private fun trace(line: String) {
        try {
            val f = File(File(cacheDir, "dub").apply { mkdirs() }, NeuralIpc.TRACE_FILE)
            f.appendText(line + "\n")
        } catch (t: Throwable) {}
    }

    /** 16-bit mono WAV. */
    private fun writeWav(file: File, x: FloatArray, rate: Int) {
        val data = ByteBuffer.allocate(x.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in x) {
            val s = (v.coerceIn(-1f, 1f) * 32767f).toInt()
            data.putShort(s.toShort())
        }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        val bytes = x.size * 2
        header.put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(bytes)
        RandomAccessFile(file, "rw").use { f ->
            f.setLength(0)
            f.write(header.array())
            f.write(data.array())
        }
    }
}
