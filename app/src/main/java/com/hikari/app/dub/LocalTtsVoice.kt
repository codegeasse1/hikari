package com.hikari.app.dub

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Method 2 — the phone's own text-to-speech engine. Works with no internet once
 * the language's voice data is installed (Settings → System → Languages →
 * Text-to-speech output). Nothing is sent anywhere.
 */
class LocalTtsVoice(private val context: Context) : DubVoice {

    override val label = "Phone voice (offline)"

    private val dir = File(context.cacheDir, "dub").apply { mkdirs() }
    private val lock = Mutex()   // one utterance at a time: the engine is single-threaded
    private var tts: TextToSpeech? = null

    private suspend fun engine(): TextToSpeech? {
        tts?.let { return it }
        // The engine's init callback needs a Looper thread: create it on Main.
        return withContext(Dispatchers.Main) { withTimeoutOrNull(8_000L) {
            suspendCancellableCoroutine { cont ->
                var created: TextToSpeech? = null
                created = TextToSpeech(context.applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        tts = created
                        if (cont.isActive) cont.resume(created)
                    } else {
                        runCatching { created?.shutdown() }
                        if (cont.isActive) cont.resume(null)
                    }
                }
                cont.invokeOnCancellation { runCatching { created?.shutdown() } }
            }
        } }
    }

    override suspend fun isAvailable(lang: String): Boolean {
        val e = engine() ?: return false
        val r = e.isLanguageAvailable(Locale.forLanguageTag(lang))
        return r >= TextToSpeech.LANG_AVAILABLE
    }

    override suspend fun synthesize(text: String, lang: String): File? =
        withContext(Dispatchers.IO) {
            val out = File(dir, GoogleTtsVoice.key("l", lang, text) + ".wav")
            if (out.exists() && out.length() > 0) return@withContext out
            lock.withLock {
                val e = engine() ?: return@withLock null
                if (e.setLanguage(Locale.forLanguageTag(lang)) < TextToSpeech.LANG_AVAILABLE) {
                    return@withLock null
                }
                val id = "dub-" + System.nanoTime()
                val done = withTimeoutOrNull(20_000L) {
                    suspendCancellableCoroutine<Boolean> { cont ->
                        e.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {}
                            override fun onDone(utteranceId: String?) {
                                if (utteranceId == id && cont.isActive) cont.resume(true)
                            }
                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) {
                                if (utteranceId == id && cont.isActive) cont.resume(false)
                            }
                        })
                        val rc = e.synthesizeToFile(text, Bundle(), out, id)
                        if (rc != TextToSpeech.SUCCESS && cont.isActive) cont.resume(false)
                    }
                }
                if (done == true && out.exists() && out.length() > 0) out
                else { out.delete(); null }
            }
        }

    override fun release() {
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
    }
}
