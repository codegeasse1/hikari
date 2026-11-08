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
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The voice-fingerprint model for the voice scan (a speaker-embedding network,
 * about 25-40 MB), downloaded once from the official sherpa-onnx releases page.
 * Several models are tried in order; if none can be fetched the scan simply
 * works from pitch alone.
 */
internal object VoiceScanModels {

    private class Model(val file: String, val minBytes: Long)

    private const val BASE =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/"

    private val LIST = listOf(
        Model("nemo_en_titanet_small.onnx", 5_000_000L),
        Model("wespeaker_en_voxceleb_resnet34.onnx", 5_000_000L),
        Model("3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx", 5_000_000L),
    )

    private fun dir(c: Context): File = File(File(c.filesDir, "dub_models"), "speaker")

    /** Path of a model that is on the phone, or null. */
    fun ready(c: Context): String? {
        for (m in LIST) {
            val f = File(dir(c), m.file)
            if (f.exists() && f.length() >= m.minBytes) return f.path
        }
        return null
    }

    fun delete(c: Context) {
        dir(c).deleteRecursively()
    }

    /** Downloads the first model that works. Returns its path, or null. [onPercent] gets 0..100. */
    fun download(c: Context, onPercent: (Int) -> Unit): String? {
        val d = dir(c)
        d.mkdirs()
        if (d.usableSpace < 150_000_000L) return null
        for (m in LIST) {
            val part = File(d, m.file + ".part")
            val out = File(d, m.file)
            try {
                if (fetch(BASE + m.file, part, m.minBytes, onPercent)) {
                    out.delete()
                    if (part.renameTo(out)) return out.path
                }
            } catch (t: Throwable) {
            }
            part.delete()
        }
        return null
    }

    private fun fetch(url: String, part: File, minBytes: Long, onPercent: (Int) -> Unit): Boolean {
        var u = url
        var hops = 0
        while (true) {
            val conn = URL(u).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "Hikari")
            val code = conn.responseCode
            if (code in 300..399 && hops < 6) {
                val next = conn.getHeaderField("Location")
                conn.disconnect()
                if (next.isNullOrBlank()) return false
                u = URL(URL(u), next).toString()
                hops++
                continue
            }
            if (code != 200) {
                conn.disconnect()
                return false
            }
            val total = conn.contentLengthLong
            var received = 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        received += n
                        if (total > 0) {
                            val p = (received * 100 / total).toInt().coerceIn(0, 100)
                            if (p != lastPct) {
                                lastPct = p
                                onPercent(p)
                            }
                        }
                    }
                }
            }
            conn.disconnect()
            if (total > 0 && received < total) return false
            return received >= minBytes
        }
    }
}

/**
 * Asks the voice helper process ([NeuralTtsService]) to listen to the original
 * sound of the video and tell, for every subtitle line, who speaks it and
 * whether that is a man or a woman. The answer is cached per subtitle, so
 * starting the dub again is instant. Any problem gives null (and the dub then
 * uses the old random guess), never an error.
 */
class VoiceScanClient(context: Context) {

    private val app: Context = context.applicationContext
    private val dir = File(app.cacheDir, "dub").apply { mkdirs() }
    private val main = Handler(Looper.getMainLooper())

    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<String>>()
    private val progressOf = ConcurrentHashMap<Int, (Int) -> Unit>()
    private val notes = ConcurrentHashMap<Int, String>()

    private val replies = Messenger(
        Handler(Looper.getMainLooper()) { msg ->
            val d = msg.data
            val id = d.getInt(NeuralIpc.K_ID)
            if (msg.what == NeuralIpc.MSG_SCAN_PROGRESS) {
                progressOf[id]?.invoke(d.getInt(NeuralIpc.K_PCT))
            } else if (msg.what == NeuralIpc.MSG_SCAN_RESULT) {
                val ok = d.getBoolean(NeuralIpc.K_OK)
                notes[id] = d.getString(NeuralIpc.K_NOTE).orEmpty()
                val text = if (ok) "" else (d.getString(NeuralIpc.K_ERR) ?: "failed").ifEmpty { "failed" }
                pending.remove(id)?.complete(text)
            }
            true
        }
    )

    private fun say(onStatus: (String) -> Unit, text: String) {
        main.post { onStatus(text) }
    }

    private fun cacheKey(cues: List<DubCue>, offsetMs: Long): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(("v1|" + cues.size + "|" + offsetMs + "|").toByteArray())
        for (c in cues) md.update((c.startMs.toString() + ":" + c.endMs + ";").toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }.take(20)
    }

    /**
     * Listens to the video. [url] is the address that is playing now. Takes a
     * minute or two for a whole episode; [onStatus] gets short messages (from
     * the main thread). Cancelling the calling coroutine stops the helper.
     */
    suspend fun scan(
        cues: List<DubCue>,
        offsetMs: Long,
        url: String,
        headers: Map<String, String>,
        hls: Boolean,
        onStatus: (String) -> Unit,
    ): VoiceScanResult? = withContext(Dispatchers.IO) {
        val cacheFile = File(dir, "scan_" + cacheKey(cues, offsetMs) + ".txt")
        if (cacheFile.exists()) {
            val cached = VoiceScanResult.parse(runCatching { cacheFile.readText() }.getOrDefault(""), cues.size)
            if (cached != null) {
                say(onStatus, "Dub: voices of this video are already known (" + cached.summary() + ").")
                return@withContext cached
            }
            cacheFile.delete()
        }

        // 1) the voice-fingerprint model (once)
        var model = VoiceScanModels.ready(app)
        if (model == null) {
            say(onStatus, "Dub: downloading the voice-detection model once (about 40 MB)...")
            var lastShown = 0
            model = VoiceScanModels.download(app) { p ->
                if (p >= lastShown + 25) {
                    lastShown = p
                    say(onStatus, "Dub: downloading the voice-detection model... $p%")
                }
            }
            if (model == null) {
                say(onStatus, "Dub: the voice-detection model could not be downloaded, so men and women are told by pitch only.")
            }
        }

        // 2) the helper process listens to the video
        val link = connect() ?: run {
            say(onStatus, "Dub: could not start the voice helper, using the normal voices.")
            return@withContext null
        }
        val (messenger, conn) = link
        val id = nextId.getAndIncrement()
        val done = CompletableDeferred<String>()
        pending[id] = done
        var lastPct = -100
        progressOf[id] = { pct ->
            if (pct >= lastPct + 20) {
                lastPct = pct
                say(onStatus, "Dub: listening to the original voices... $pct%")
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
                putLongArray(NeuralIpc.K_STARTS, LongArray(cues.size) { cues[it].startMs })
                putLongArray(NeuralIpc.K_ENDS, LongArray(cues.size) { cues[it].endMs })
                putLong(NeuralIpc.K_OFFSET, offsetMs)
                putString(NeuralIpc.K_MODEL, model ?: "")
                putString(NeuralIpc.K_OUT, cacheFile.path)
            }
            val msg = Message.obtain(null, NeuralIpc.MSG_SCAN)
            msg.data = data
            msg.replyTo = replies
            try {
                messenger.send(msg)
            } catch (t: Throwable) {
                say(onStatus, "Dub: could not talk to the voice helper, using the normal voices.")
                return@withContext null
            }
            val err = withTimeoutOrNull(SCAN_LIMIT_MS) { done.await() }
            if (err == null) {
                say(onStatus, "Dub: listening took too long, using the normal voices.")
                return@withContext null
            }
            if (err.isNotEmpty()) {
                say(onStatus, "Dub: could not listen to this video ($err), using the normal voices.")
                return@withContext null
            }
            val result = VoiceScanResult.parse(runCatching { cacheFile.readText() }.getOrDefault(""), cues.size)
            if (result == null) {
                say(onStatus, "Dub: the voice scan gave no usable answer, using the normal voices.")
                return@withContext null
            }
            val how = if (model == null) " - by pitch only" else ""
            say(onStatus, "Dub: found " + result.summary() + how + ".")
            result
        } finally {
            // also runs when the coroutine is cancelled: tell the helper to stop
            if (pending.remove(id) != null) {
                val c = Message.obtain(null, NeuralIpc.MSG_SCAN_CANCEL)
                c.data = Bundle().apply { putInt(NeuralIpc.K_ID, id) }
                try {
                    messenger.send(c)
                } catch (t: Throwable) {
                }
            }
            progressOf.remove(id)
            notes.remove(id)
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
        /** A whole film: the helper itself stops after 8 minutes, this is the safety net. */
        const val SCAN_LIMIT_MS = 11L * 60_000L
    }
}
