package com.hikari.app.dub

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import com.hikari.app.reader.MangaOrigLang
import com.hikari.app.reader.MangaTranslateEngine
import com.hikari.app.reader.MangaTranslateRuntime
import com.hikari.app.reader.MangaTranslateSettings
import com.hikari.app.reader.MangaTargetLang
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.getOfflineTtsConfig
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One dub language: device locale, translation target, and the optional
 *  Piper voice pack (int8 tarball from the official sherpa-onnx tts-models
 *  release — ~20MB each). Null tarball = device TTS only. */
data class AiDubLang(
    val code: String,
    val label: String,
    val locale: Locale,
    val target: MangaTargetLang,
    val piperTarball: String?,
    val espeakTag: String,
)

object AiDubLangs {
    val all = listOf(
        AiDubLang("en", "English", Locale.US, MangaTargetLang.EN, "vits-piper-en_US-lessac-medium-int8.tar.bz2", "en-us"),
        AiDubLang("hi", "Hindi", Locale("hi", "IN"), MangaTargetLang.HI, "vits-piper-hi_IN-pratham-medium-int8.tar.bz2", "hi"),
        AiDubLang("es", "Spanish", Locale("es", "ES"), MangaTargetLang.ES, "vits-piper-es_ES-davefx-medium-int8.tar.bz2", "es"),
        AiDubLang("fr", "French", Locale.FRANCE, MangaTargetLang.FR, "vits-piper-fr_FR-siwis-medium-int8.tar.bz2", "fr"),
        AiDubLang("ur", "Urdu", Locale("ur", "PK"), MangaTargetLang.UR, "vits-piper-ur_PK-fasih-medium-int8.tar.bz2", "ur"),
        AiDubLang("ar", "Arabic", Locale("ar"), MangaTargetLang.AR, "vits-piper-ar_JO-kareem-medium-int8.tar.bz2", "ar"),
        AiDubLang("bn", "Bengali", Locale("bn"), MangaTargetLang.BN, null, "bn"),
        AiDubLang("de", "German", Locale.GERMANY, MangaTargetLang.DE, "vits-piper-de_DE-thorsten-medium-int8.tar.bz2", "de"),
        AiDubLang("ru", "Russian", Locale("ru"), MangaTargetLang.RU, "vits-piper-ru_RU-ruslan-medium-int8.tar.bz2", "ru"),
        AiDubLang("pt", "Portuguese", Locale("pt", "BR"), MangaTargetLang.PT, "vits-piper-pt_BR-faber-medium-int8.tar.bz2", "pt"),
        AiDubLang("tr", "Turkish", Locale("tr"), MangaTargetLang.TR, "vits-piper-tr_TR-fettah-medium-int8.tar.bz2", "tr"),
        AiDubLang("id", "Indonesian", Locale("id"), MangaTargetLang.ID, "vits-piper-id_ID-news_tts-medium-int8.tar.bz2", "id"),
        AiDubLang("it", "Italian", Locale.ITALY, MangaTargetLang.IT, "vits-piper-it_IT-paola-medium-int8.tar.bz2", "it"),
        AiDubLang("ja", "Japanese", Locale.JAPAN, MangaTargetLang.JA, null, "ja"),
        AiDubLang("ko", "Korean", Locale.KOREA, MangaTargetLang.KO, null, "ko"),
        AiDubLang("zh", "Chinese", Locale.CHINA, MangaTargetLang.ZH, "vits-piper-zh_CN-xiao_ya-medium-int8.tar.bz2", "zh"),
    )

    fun byCode(code: String): AiDubLang = all.firstOrNull { it.code == code } ?: all[0]
}

class AiDubManager(private val appCtx: Context) {
    companion object {
        const val ENGINE_SYSTEM = "system"
        const val ENGINE_PIPER = "piper"
    }

    var enabled = false
    var engine = ENGINE_SYSTEM
    var lang = "hi"
    var hardsub = false

    /** Fired on the main thread when speech starts/stops (player ducks). */
    var onSpeakingChanged: ((Boolean) -> Unit)? = null

    /** Last time a provider subtitle cue arrived (hardsubs stand down). */
    var providerCueAt = 0L

    /** Native TTS stack only exists on 64-bit phones — the AAR ships no
     *  32-bit binaries, so the Piper engine hides itself there. */
    val piperOk: Boolean =
        Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }

    private val prefs: SharedPreferences =
        appCtx.getSharedPreferences("aidub", Context.MODE_PRIVATE)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsInitStarted = false

    private var piper: OfflineTts? = null
    private var piperId: String? = null
    private var pipeStop = false
    private var pipeTrack: AudioTrack? = null

    private var lastInput = ""
    private val dubQueue = ArrayDeque<Triple<String, AiDubLang, Gender>>()
    private var dubDraining = false
    private var lastEnqueueAt = 0L
    private val piperQueue = ArrayDeque<Pair<FloatArray, Int>>()
    private var castMap: Map<String, Gender> = emptyMap()
    private var castKey: String = ""

    init {
        load()
    }

    fun load() {
        enabled = prefs.getBoolean("enabled", false)
        engine = prefs.getString("engine", ENGINE_SYSTEM) ?: ENGINE_SYSTEM
        lang = prefs.getString("lang", "hi") ?: "hi"
        hardsub = prefs.getBoolean("hardsub", false)
    }

    fun save() {
        prefs.edit()
            .putBoolean("enabled", enabled)
            .putString("engine", engine)
            .putString("lang", lang)
            .putBoolean("hardsub", hardsub)
            .apply()
    }

    fun currentLang(): AiDubLang = AiDubLangs.byCode(lang)

    // ---- subtitle entry points (called from the player's listener) ----

    fun setTitle(title: String, year: Int? = null) {
        val key = title.trim().lowercase() + "|" + (year ?: 0)
        if (key == castKey) return
        castKey = key
        if (title.isBlank()) { castMap = emptyMap(); return }
        scope.launch(Dispatchers.IO) {
            val m = runCatching { CastGenders.load(title, year) }.getOrDefault(emptyMap())
            withContext(Dispatchers.Main) { if (key == castKey) castMap = m }
        }
    }

    fun onProviderCues(line: String) {
        val clean = line.trim()
        if (!enabled || clean.isEmpty()) return
        providerCueAt = System.currentTimeMillis()
        val g = CastGenders.genderForLine(clean, castMap)
        speakTranslated(clean, g)
    }

    fun onPlayingChanged(playing: Boolean) {
        if (!playing) stopSpeech(clearInput = false)
    }

    fun onSeek() {
        stopSpeech(clearInput = true)
    }

    fun onActivityPause() {
        stopSpeech(clearInput = false)
    }

    // ---- translate + speak ----

    /** Translate once (Google, then ML Kit) for dub/hardsub display. */
    suspend fun translateForDub(text: String, L: AiDubLang): String? {
        val clean = text.trim()
        if (clean.isEmpty()) return null
        for (e in listOf(MangaTranslateEngine.GOOGLE, MangaTranslateEngine.MLKIT)) {
            try {
                val out = MangaTranslateRuntime.translateTexts(
                    listOf(clean),
                    MangaTranslateSettings(engine = e, orig = MangaOrigLang.AUTO, target = L.target),
                ).getOrNull(0)?.trim()
                if (!out.isNullOrEmpty()) return out
            } catch (e2: CancellationException) {
                throw e2
            } catch (e2: Exception) {
                continue
            }
        }
        return null
    }

    private fun speakTranslated(raw: String, gender: Gender = Gender.UNKNOWN) {
        val norm = raw.trim().replace(Regex("\\s+"), " ")
        if (!enabled || norm.isEmpty() || norm == lastInput) return
        lastInput = norm
        scope.launch(Dispatchers.IO) {
            val L = currentLang()
            val out = translateForDub(norm, L) ?: return@launch
            withContext(Dispatchers.Main) {
                if (!enabled) return@withContext
                enqueue(out, L, gender)
            }
        }
    }

    private fun enqueue(text: String, L: AiDubLang, gender: Gender = Gender.UNKNOWN) {
        val clean = text.trim()
        if (!enabled || clean.isEmpty()) return
        if (engine == ENGINE_PIPER && piperOk) { enqueuePiper(clean, L, gender); return }
        synchronized(dubQueue) {
            while (dubQueue.size >= 3) dubQueue.removeFirst()
            val tail = dubQueue.lastOrNull()
            val now = System.currentTimeMillis()
            if (tail != null && tail.second.code == L.code &&
                now - lastEnqueueAt < 800 && tail.first.length + clean.length < 350
            ) {
                dubQueue.removeLast()
                dubQueue.addLast(Triple(tail.first + " " + clean, L, gender))
            } else {
                dubQueue.addLast(Triple(clean, L, gender))
            }
            lastEnqueueAt = now
        }
        drainSystemQueue()
    }

    private fun drainSystemQueue() {
        if (dubDraining) return
        val head = synchronized(dubQueue) { dubQueue.firstOrNull() } ?: return
        dubDraining = true
        speakSystemQueued(head.first, head.second, head.third)
    }

    private fun speakNextInQueue() {
        synchronized(dubQueue) { if (dubQueue.isNotEmpty()) dubQueue.removeFirst() }
        dubDraining = false
        if (dubQueue.isNotEmpty() && enabled) drainSystemQueue()
        else onSpeakingChanged?.invoke(false)
    }

    private fun enqueuePiper(text: String, L: AiDubLang, gender: Gender = Gender.UNKNOWN) {
        scope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { speakPiperQueued(text, L, gender) }
        }
    }

    /** Speak text that is already translated (hardsub path). */
    fun speakDirect(translated: String, L: AiDubLang, gender: Gender = Gender.UNKNOWN) {
        val text = translated.trim()
        if (!enabled || text.isEmpty()) return
        scope.launch(Dispatchers.Main) { enqueue(text, L, gender) }
    }

    private fun speak(text: String, L: AiDubLang, gender: Gender = Gender.UNKNOWN) {
        if (text.length > 600) return
        enqueue(text, L, gender)
    }

    private fun speakPiperQueued(text: String, L: AiDubLang, gender: Gender = Gender.UNKNOWN) {
        if (text.length > 600) return
        if (!AiDubPiperPack.isReady(appCtx, L)) { synchronized(dubQueue) { while (dubQueue.size >= 3) dubQueue.removeFirst(); dubQueue.addLast(Triple(text, L, gender)) }; drainSystemQueue(); return }
        synthesizePiper(text, L, gender)
    }

    fun stopSpeech(clearInput: Boolean = true) {
        if (clearInput) lastInput = ""
        synchronized(dubQueue) { dubQueue.clear() }
        synchronized(piperQueue) { piperQueue.clear() }
        dubDraining = false
        try {
            tts?.stop()
        } catch (e: Exception) {
        }
        pipeStop = true
        stopPipeTrack()
    }

    fun release() {
        stopSpeech(clearInput = true)
        scope.cancel()
        try {
            tts?.shutdown()
        } catch (e: Exception) {
        }
        tts = null
        try {
            piper?.free()
        } catch (e: Exception) {
        }
        piper = null
    }

    // ---- System TTS ----

    private fun ensureTts(done: (Boolean) -> Unit) {
        if (ttsReady && tts != null) {
            done(true)
            return
        }
        if (ttsInitStarted) {
            scope.launch {
                for (i in 0 until 40) {
                    if (ttsReady) break
                    delay(250)
                }
                done(ttsReady && tts != null)
            }
            return
        }
        ttsInitStarted = true
        try {
            tts = TextToSpeech(appCtx) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    try {
                        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String) = Unit
                            override fun onDone(utteranceId: String) {
                                if (utteranceId.startsWith("aidub-q")) runCatching { scope.launch(Dispatchers.Main) { speakNextInQueue() } }
                                else onSpeakingChanged?.invoke(false)
                            }
                            override fun onError(utteranceId: String) {
                                if (utteranceId.startsWith("aidub-q")) runCatching { scope.launch(Dispatchers.Main) { speakNextInQueue() } }
                                else onSpeakingChanged?.invoke(false)
                            }
                            override fun onStop(utteranceId: String, interrupted: Boolean) {
                                runCatching { scope.launch(Dispatchers.Main) {
                                    dubDraining = false
                                    if (!interrupted && utteranceId.startsWith("aidub-q") &&
                                        dubQueue.isNotEmpty() && enabled
                                    ) drainSystemQueue()
                                    else onSpeakingChanged?.invoke(false)
                                } }
                            }
                        })
                    } catch (e: Exception) {
                    }
                }
                done(ttsReady)
            }
        } catch (e: Exception) {
            done(false)
        }
    }

    fun systemVoiceKnown(L: AiDubLang): Boolean? {
        val t = tts
        if (!ttsReady || t == null) return null
        return try {
            val r = t.isLanguageAvailable(L.locale)
            r == TextToSpeech.LANG_AVAILABLE ||
                r == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                r == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
        } catch (e: Exception) {
            null
        }
    }

    fun systemSummary(): String {
        ensureTts { }
        return if (ttsReady) "Device voices" else "Device voices (loading…)"
    }

    private fun speakSystem(text: String, L: AiDubLang, gender: Gender = Gender.UNKNOWN) {
        enqueue(text, L, gender)
    }

    private fun pickVoiceForGender(t: TextToSpeech, L: AiDubLang, gender: Gender): android.speech.tts.Voice? {
        return try {
            val cands = t.voices?.filter { it.locale.language == L.locale.language } ?: return null
            if (cands.isEmpty()) return null
            if (gender == Gender.UNKNOWN) return cands.firstOrNull()
            val femaleKeys = listOf("female", "woman", "girl", "feminine", "zira", "samantha", "veena", "heera")
            val maleKeys = listOf("male", "man", "boy", "masculine", "david", "mark", "alex", "daniel")
            val want = if (gender == Gender.FEMALE) femaleKeys else maleKeys
            val avoid = if (gender == Gender.FEMALE) maleKeys else femaleKeys
            cands.firstOrNull { v -> want.any { v.name.lowercase().contains(it) } }
                ?: cands.firstOrNull { v -> avoid.none { v.name.lowercase().contains(it) } }
                ?: cands.firstOrNull()
        } catch (e: Exception) { null }
    }

    private fun speakSystemQueued(text: String, L: AiDubLang, gender: Gender = Gender.UNKNOWN) {
        ensureTts { ok ->
            if (!ok) { runCatching { scope.launch(Dispatchers.Main) { speakNextInQueue() } }; return@ensureTts }
            val t = tts ?: return@ensureTts
            val res = try {
                t.setLanguage(L.locale)
            } catch (e: Exception) {
                TextToSpeech.LANG_NOT_SUPPORTED
            }
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                Toast.makeText(
                    appCtx,
                    "No ${L.label} voice on this phone — install one in the system TTS settings",
                    Toast.LENGTH_LONG,
                ).show()
                return@ensureTts
            }
            try {
                pickVoiceForGender(t, L, gender)?.let { t.voice = it }
                t.setPitch(when (gender) { Gender.FEMALE -> 1.25f; Gender.MALE -> 0.85f; else -> 1.0f })
                t.setSpeechRate(1.25f)
            } catch (e: Exception) {
            }
            onSpeakingChanged?.invoke(true)
            try {
                val r = t.speak(text, TextToSpeech.QUEUE_ADD, null, "aidub-q-" + System.nanoTime())
                if (r != TextToSpeech.SUCCESS) runCatching { scope.launch(Dispatchers.Main) { speakNextInQueue() } }
            } catch (e: Exception) {
                runCatching { scope.launch(Dispatchers.Main) { speakNextInQueue() } }
            }
        }
    }

    // ---- Piper (sherpa-onnx, offline neural voices) ----

    fun piperVoiceReady(L: AiDubLang): Boolean =
        L.piperTarball != null && AiDubPiperPack.isReady(appCtx, L)

    fun piperSummary(): String {
        if (!piperOk) return "Needs a 64-bit phone"
        val L = currentLang()
        if (L.piperTarball == null) return "No Piper voice for ${L.label} — System TTS only"
        return if (AiDubPiperPack.isReady(appCtx, L)) "Voice ready (${AiDubPiperPack.voiceMB(appCtx, L)} MB)"
        else "Voice not downloaded (~20 MB)"
    }

    fun piperHint(): String {
        val L = currentLang()
        return if (L.piperTarball == null || !piperOk) piperSummary()
        else if (AiDubPiperPack.isReady(appCtx, L)) "Piper ${L.label} voice ready"
        else "Piper ${L.label} voice still downloading"
    }

    fun piperMB(): Long = AiDubPiperPack.downloadedMB(appCtx)

    fun clearPiper() {
        AiDubPiperPack.clearAll(appCtx)
        try {
            piper?.free()
        } catch (e: Exception) {
        }
        piper = null
        piperId = null
    }

    fun ensurePiperForCurrent(done: (Boolean) -> Unit) {
        val L = currentLang()
        if (!piperOk || L.piperTarball == null) {
            done(false)
            return
        }
        if (AiDubPiperPack.isReady(appCtx, L)) {
            done(true)
            return
        }
        downloadPiper(L, done)
    }

    fun downloadPiper(L: AiDubLang, done: (Boolean) -> Unit) {
        if (!piperOk || L.piperTarball == null) {
            done(false)
            return
        }
        scope.launch(Dispatchers.IO) {
            val ok = AiDubPiperPack.download(appCtx, L, null)
            withContext(Dispatchers.Main) { done(ok) }
        }
    }

    private fun speakPiper(text: String, L: AiDubLang, gender: Gender = Gender.UNKNOWN) {
        synthesizePiper(text, L, gender)
    }

    private fun resampleForGender(samples: FloatArray, gender: Gender): FloatArray {
        val factor = when (gender) { Gender.FEMALE -> 1.12f; Gender.MALE -> 0.9f; else -> 1.0f }
        if (factor == 1.0f || samples.size < 8) return samples
        val outSize = (samples.size / factor).toInt().coerceAtLeast(1)
        val out = FloatArray(outSize)
        for (i in out.indices) {
            val pos = i * factor
            val i0 = pos.toInt().coerceIn(0, samples.size - 1)
            val i1 = (i0 + 1).coerceIn(0, samples.size - 1)
            val frac = pos - i0
            out[i] = samples[i0] * (1 - frac) + samples[i1] * frac
        }
        return out
    }

    private fun synthesizePiper(text: String, L: AiDubLang, gender: Gender = Gender.UNKNOWN) {
        if (L.piperTarball == null || !AiDubPiperPack.isReady(appCtx, L)) {
            synchronized(dubQueue) { while (dubQueue.size >= 3) dubQueue.removeFirst(); dubQueue.addLast(Triple(text, L, gender)) }; drainSystemQueue()
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val found = AiDubPiperPack.locate(appCtx, L)
                    ?: run {
                        withContext(Dispatchers.Main) { synchronized(dubQueue) { while (dubQueue.size >= 3) dubQueue.removeFirst(); dubQueue.addLast(Triple(text, L, gender)) }; drainSystemQueue() }
                        return@launch
                    }
                if (piperId != found.id) {
                    try {
                        piper?.free()
                    } catch (e: Exception) {
                    }
                    // sherpa-onnx 1.13.8: espeak-ng is built into the AAR.
                    // dataDir points at the voice pack's espeak-ng-data dir
                    // (shipped inside every tts-models tarball); plain
                    // generate() phonemizes internally. No piper-phonemize.
                    val espeakDir = java.io.File(found.dir, "espeak-ng-data")
                    val dataDir = if (espeakDir.isDirectory) espeakDir.absolutePath
                    else found.dir.absolutePath
                    piper = OfflineTts(
                        config = getOfflineTtsConfig(
                            modelDir = found.dir.absolutePath,
                            modelName = found.onnx,
                            acousticModelName = "",
                            vocoder = "",
                            voices = "",
                            lexicon = "",
                            dataDir = dataDir,
                            dictDir = "",
                            ruleFsts = "",
                            ruleFars = "",
                        ),
                    )
                    piperId = found.id
                }
                val engine = piper
                    ?: run {
                        withContext(Dispatchers.Main) { synchronized(dubQueue) { while (dubQueue.size >= 3) dubQueue.removeFirst(); dubQueue.addLast(Triple(text, L, gender)) }; drainSystemQueue() }
                        return@launch
                    }
                val audio = try {
                    engine.generate(text, 0, 1.0f)
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { synchronized(dubQueue) { while (dubQueue.size >= 3) dubQueue.removeFirst(); dubQueue.addLast(Triple(text, L, gender)) }; drainSystemQueue() }
                    return@launch
                }
                if (audio.samples.isEmpty()) {
                    withContext(Dispatchers.Main) { synchronized(dubQueue) { while (dubQueue.size >= 3) dubQueue.removeFirst(); dubQueue.addLast(Triple(text, L, gender)) }; drainSystemQueue() }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    val pitched = resampleForGender(audio.samples, gender)
                    synchronized(piperQueue) { while (piperQueue.size >= 3) piperQueue.removeFirst(); piperQueue.addLast(Pair(pitched, engine.sampleRate())) }
                    playNextPiperBlock()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnsatisfiedLinkError) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(appCtx, "Piper engine failed to load — using System TTS", Toast.LENGTH_SHORT).show()
                    speakSystem(text, L, gender)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { synchronized(dubQueue) { while (dubQueue.size >= 3) dubQueue.removeFirst(); dubQueue.addLast(Triple(text, L, gender)) }; drainSystemQueue() }
            }
        }
    }


    private fun playNextPiperBlock() {
        if (pipeTrack != null || pipeStop) return
        val head = synchronized(piperQueue) { piperQueue.firstOrNull() } ?: return
        playPcm(head.first, head.second)
    }

    private fun playPcm(samples: FloatArray, sampleRate: Int) {
        stopPipeTrack()
        pipeStop = false
        val track = try {
            val attr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val fmt = AudioFormat.Builder()
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .build()
            AudioTrack(
                attr, fmt, samples.size * 4,
                AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
        } catch (e: Exception) {
            return
        }
        pipeTrack = track
        try {
            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        } catch (e: Exception) {
            stopPipeTrack()
            return
        }
        onSpeakingChanged?.invoke(true)
        track.play()
        scope.launch(Dispatchers.IO) {
            try {
                while (!pipeStop &&
                    track.playState == AudioTrack.PLAYSTATE_PLAYING &&
                    track.playbackHeadPosition < samples.size
                ) {
                    delay(120)
                }
            } catch (e: Exception) {
            }
            withContext(Dispatchers.Main) {
                if (pipeTrack === track) {
                    pipeTrack = null
                }
                synchronized(piperQueue) { if (piperQueue.isNotEmpty()) piperQueue.removeFirst() }
                try {
                    track.stop()
                } catch (e: Exception) {
                }
                try {
                    track.release()
                } catch (e: Exception) {
                }
                if (enabled && !pipeStop && synchronized(piperQueue) { piperQueue.isNotEmpty() }) playNextPiperBlock()
                else onSpeakingChanged?.invoke(false)
            }
        }
    }

    private fun stopPipeTrack() {
        val track = pipeTrack
        pipeTrack = null
        if (track != null) {
            try {
                track.stop()
            } catch (e: Exception) {
            }
            try {
                track.release()
            } catch (e: Exception) {
            }
        }
    }
}
