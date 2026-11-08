package com.hikari.app.dub

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** A subtitle that was written by listening to the voice of a video. */
internal class GeneratedSubtitle(
    /** The subtitle as SRT text. */
    val srt: String,
    /** Spoken language code ("en", "ja" ...), empty when it is not known. */
    val lang: String,
    val lines: Int,
    /** True when the time budget ran out, so only the first part of the video is in it. */
    val partial: Boolean,
)

/**
 * Asks the voice helper process ([NeuralTtsService]) to listen to the sound of
 * the video and write it down as a subtitle ([VoiceTranscriber]). A finished
 * subtitle is cached, so asking again for the same video is instant. Any
 * problem gives null and a short message through [onStatus] - never an error.
 */
internal class SubtitleGenClient(context: Context) {

    private val app: Context = context.applicationContext
    private val dir = File(app.cacheDir, "dub").apply { mkdirs() }
    private val main = Handler(Looper.getMainLooper())

    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<String>>()
    private val progressOf = ConcurrentHashMap<Int, (Int, String) -> Unit>()
    private val answers = ConcurrentHashMap<Int, Bundle>()

    private val replies = Messenger(
        Handler(Looper.getMainLooper()) { msg ->
            val d = msg.data
            val id = d.getInt(NeuralIpc.K_ID)
            if (msg.what == NeuralIpc.MSG_GEN_PROGRESS) {
                progressOf[id]?.invoke(d.getInt(NeuralIpc.K_PCT), d.getString(NeuralIpc.K_NOTE).orEmpty())
            } else if (msg.what == NeuralIpc.MSG_GEN_RESULT) {
                val ok = d.getBoolean(NeuralIpc.K_OK)
                answers[id] = Bundle(d)
                val text = if (ok) "" else (d.getString(NeuralIpc.K_ERR) ?: "failed").ifEmpty { "failed" }
                pending.remove(id)?.complete(text)
            }
            true
        }
    )

    private fun say(onStatus: (String) -> Unit, text: String) {
        main.post { onStatus(text) }
    }

    private fun cacheKey(url: String, size: WhisperSize, language: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(("g1|" + url + "|" + size.key + "|" + language).toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }.take(20)
    }

    /**
     * Writes the subtitle. [url] is the address that is playing now, [language]
     * is the spoken language ("" = find out by listening). The speech models of
     * [size] must be installed already ([SubtitleGenModels.install]). A whole
     * episode takes several minutes; [onStatus] gets short messages (main
     * thread). Cancelling the calling coroutine stops the helper.
     */
    suspend fun generate(
        url: String,
        headers: Map<String, String>,
        hls: Boolean,
        durationMs: Long,
        language: String,
        size: WhisperSize,
        onStatus: (String) -> Unit,
    ): GeneratedSubtitle? = withContext(Dispatchers.IO) {
        val key = cacheKey(url, size, language)
        val cacheSrt = File(dir, "gen_$key.srt")
        val cacheMeta = File(dir, "gen_$key.meta")
        if (cacheSrt.exists() && cacheMeta.exists()) {
            val text = runCatching { cacheSrt.readText(Charsets.UTF_8) }.getOrDefault("")
            val meta = runCatching { cacheMeta.readText() }.getOrDefault("").split("|")
            val lines = meta.getOrNull(1)?.toIntOrNull() ?: 0
            if (text.isNotBlank() && lines > 0) {
                say(onStatus, "Subtitle: this video was already written down.")
                return@withContext GeneratedSubtitle(text, meta.getOrNull(0).orEmpty(), lines, false)
            }
            cacheSrt.delete()
            cacheMeta.delete()
        }

        if (!SubtitleGenModels.isReady(app, size)) {
            say(onStatus, "Subtitle: the speech model is not installed yet.")
            return@withContext null
        }

        val link = connect() ?: run {
            say(onStatus, "Subtitle: could not start the voice helper.")
            return@withContext null
        }
        val (messenger, conn) = link
        val id = nextId.getAndIncrement()
        val done = CompletableDeferred<String>()
        pending[id] = done
        val out = File(dir, "gen_run_$id.srt")
        var lastPct = -100
        progressOf[id] = { pct, lines ->
            if (pct >= 0 && pct >= lastPct + 10) {
                lastPct = pct
                say(onStatus, "Subtitle: listening to the voice... $pct% ($lines lines so far)")
            }
        }
        try {
            val flat = ArrayList<String>()
            for ((k, v) in headers) {
                flat.add(k)
                flat.add(v)
            }
            val data = Bundle().apply {
                putInt(NeuralIpc.K_ID, id)
                putString(NeuralIpc.K_URL, url)
                putStringArray(NeuralIpc.K_HEADERS, flat.toTypedArray())
                putBoolean(NeuralIpc.K_HLS, hls)
                putLong(NeuralIpc.K_DURATION, durationMs)
                putString(NeuralIpc.K_LANG, language)
                putString(NeuralIpc.K_WDIR, SubtitleGenModels.dirOf(app, size).path)
                putString(NeuralIpc.K_VAD, SubtitleGenModels.vadFile(app).path)
                putInt(NeuralIpc.K_THREADS, 2)
                putString(NeuralIpc.K_OUT, out.path)
            }
            val msg = Message.obtain(null, NeuralIpc.MSG_GEN)
            msg.data = data
            msg.replyTo = replies
            try {
                messenger.send(msg)
            } catch (t: Throwable) {
                say(onStatus, "Subtitle: could not talk to the voice helper.")
                return@withContext null
            }
            val err = withTimeoutOrNull(GEN_LIMIT_MS) { done.await() }
            if (err == null) {
                say(onStatus, "Subtitle: it took too long, stopped.")
                return@withContext null
            }
            if (err.isNotEmpty()) {
                say(onStatus, "Subtitle: could not write it ($err).")
                return@withContext null
            }
            val text = runCatching { out.readText(Charsets.UTF_8) }.getOrDefault("")
            val a = answers[id]
            val lines = a?.getInt(NeuralIpc.K_COUNT) ?: 0
            val lang = a?.getString(NeuralIpc.K_LANG).orEmpty()
            val partial = a?.getBoolean(NeuralIpc.K_PARTIAL) ?: false
            if (text.isBlank() || lines <= 0) {
                say(onStatus, "Subtitle: no speech was found in this video.")
                return@withContext null
            }
            if (!partial) {
                runCatching {
                    cacheSrt.writeText(text, Charsets.UTF_8)
                    cacheMeta.writeText(lang + "|" + lines)
                }
            }
            GeneratedSubtitle(text, lang, lines, partial)
        } finally {
            // also runs when the coroutine is cancelled: tell the helper to stop
            if (pending.remove(id) != null) {
                val c = Message.obtain(null, NeuralIpc.MSG_GEN_CANCEL)
                c.data = Bundle().apply { putInt(NeuralIpc.K_ID, id) }
                try {
                    messenger.send(c)
                } catch (t: Throwable) {
                }
            }
            progressOf.remove(id)
            answers.remove(id)
            runCatching { out.delete() }
            try {
                app.unbindService(conn)
            } catch (t: Throwable) {
            }
        }
    }

    private suspend fun connect(): Pair<Messenger, ServiceConnection>? {
        val ready = CompletableDeferred<Messenger>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder != null) ready.complete(Messenger(binder))
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                failAll("the voice helper stopped")
            }

            override fun onBindingDied(name: ComponentName?) {
                failAll("the voice helper stopped")
            }
        }
        val bound = try {
            app.bindService(Intent(app, NeuralTtsService::class.java), conn, Context.BIND_AUTO_CREATE)
        } catch (t: Throwable) {
            false
        }
        if (!bound) return null
        val m = withTimeoutOrNull(20_000L) { ready.await() }
        if (m == null) {
            try {
                app.unbindService(conn)
            } catch (t: Throwable) {
            }
            return null
        }
        return Pair(m, conn)
    }

    private fun failAll(why: String) {
        val waiting = pending.values.toList()
        pending.clear()
        waiting.forEach { it.complete(why) }
    }

    private companion object {
        /** The helper stops itself after 40 minutes; this is the safety net. */
        const val GEN_LIMIT_MS = 45L * 60_000L
    }
}
