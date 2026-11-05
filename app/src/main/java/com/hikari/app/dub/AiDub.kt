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

/**
 * AI Dub: speaks subtitle lines (and translated hardcoded on-video text) in
 * the dub language while the player ducks its own audio underneath.
 *
 * Two engines. System TTS (any TTS engine on the phone — Google, Samsung,
 * eSpeak, RHVoice) is instant and free. Piper (sherpa-onnx + per-language
 * int8 voice packs downloaded on demand) is the natural offline voice.
 * Anything Piper cannot do falls back to System TTS, never silence.
 */
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

    fun onProviderCues(line: String) {
        val clean = line.trim()
        if (!enabled || clean.isEmpty()) return
        providerCueAt = System.currentTimeMillis()
        speakTranslated(clean)
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

    private fun speakTranslated(raw: String) {
        val norm = raw.trim().replace(Regex("\\s+"), " ")
        if (!enabled || norm.isEmpty() || norm == lastInput) return
        lastInput = norm
        scope.launch(Dispatchers.IO) {
            val L = currentLang()
            val out = translateForDub(norm, L) ?: return@launch
            withContext(Dispatchers.Main) {
                if (!enabled) return@withContext
                speak(out, L)
            }
        }
    }

    /** Speak text that is already translated (hardsub path). */
    fun speakDirect(translated: String, L: AiDubLang) {
        val text = translated.trim()
        if (!enabled || text.isEmpty()) return
        scope.launch(Dispatchers.Main) { speak(text, L) }
    }

    private fun speak(text: String, L: AiDubLang) {
        if (text.length > 600) return
        if (engine == ENGINE_PIPER && piperOk) speakPiper(text, L)
        else speakSystem(text, L)
    }

    fun stopSpeech(clearInput: Boolean = true) {
        if (clearInput) lastInput = ""
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
                                onSpeakingChanged?.invoke(false)
                            }
                            override fun onError(utteranceId: String) {
                                onSpeakingChanged?.invoke(false)
                            }
                            override fun onStop(utteranceId: String, interrupted: Boolean) {
                                onSpeakingChanged?.invoke(false)
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

    private fun speakSystem(text: String, L: AiDubLang) {
        ensureTts { ok ->
            if (!ok) return@ensureTts
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
                t.voices?.firstOrNull { it.locale.language == L.locale.language }?.let { t.voice = it }
            } catch (e: Exception) {
            }
            onSpeakingChanged?.invoke(true)
            try {
                t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "aidub-" + System.nanoTime())
            } catch (e: Exception) {
                onSpeakingChanged?.invoke(false)
            }
        }
    }

    // ---- Piper (sherpa-onnx, offline neural voices) ----

    fun piperVoiceReady(L: AiDubLang): Boolean =
        L.piperTarball != null && PiperVoice.isReady(appCtx, L)

    fun piperSummary(): String {
        if (!piperOk) return "Needs a 64-bit phone"
        val L = currentLang()
        if (L.piperTarball == null) return "No Piper voice for ${L.label} — System TTS only"
        return if (PiperVoice.isReady(appCtx, L)) "Voice ready (${PiperVoice.voiceMB(appCtx, L)} MB)"
        else "Voice not downloaded (~20 MB)"
    }

    fun piperHint(): String {
        val L = currentLang()
        return if (L.piperTarball == null || !piperOk) piperSummary()
        else if (PiperVoice.isReady(appCtx, L)) "Piper ${L.label} voice ready"
        else "Piper ${L.label} voice still downloading"
    }

    fun piperMB(): Long = PiperVoice.downloadedMB(appCtx)

    fun clearPiper() {
        PiperVoice.clearAll(appCtx)
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
        if (PiperVoice.isReady(appCtx, L)) {
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
            val ok = PiperVoice.download(appCtx, L, null)
            withContext(Dispatchers.Main) { done(ok) }
        }
    }

    private fun speakPiper(text: String, L: AiDubLang) {
        if (L.piperTarball == null || !PiperVoice.isReady(appCtx, L)) {
            speakSystem(text, L)
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val found = PiperVoice.locate(appCtx, L)
                    ?: run {
                        withContext(Dispatchers.Main) { speakSystem(text, L) }
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
                        withContext(Dispatchers.Main) { speakSystem(text, L) }
                        return@launch
                    }
                val audio = try {
                    engine.generate(text, 0, 1.0f)
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { speakSystem(text, L) }
                    return@launch
                }
                if (audio.samples.isEmpty()) {
                    withContext(Dispatchers.Main) { speakSystem(text, L) }
                    return@launch
                }
                withContext(Dispatchers.Main) { playPcm(audio.samples, engine.sampleRate()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnsatisfiedLinkError) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(appCtx, "Piper engine failed to load — using System TTS", Toast.LENGTH_SHORT).show()
                    speakSystem(text, L)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { speakSystem(text, L) }
            }
        }
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
                    onSpeakingChanged?.invoke(false)
                }
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
