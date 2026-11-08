package com.hikari.app.dub

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * How good the speech-to-text should be when a subtitle is written from the
 * voice of a video. [key] is used in file names and cache keys, [pack] is the
 * Whisper pack of the sherpa-onnx "asr-models" release.
 */
enum class WhisperSize(val key: String, val pack: String) {
    TINY("tiny", "sherpa-onnx-whisper-tiny.tar.bz2"),
    BASE("base", "sherpa-onnx-whisper-base.tar.bz2"),
}

/**
 * The speech models used by "Make subtitle from voice": one Whisper pack per
 * [WhisperSize] and the small Silero voice-detection file. They are downloaded
 * once from the official sherpa-onnx release and kept in the app's own storage.
 */
object SubtitleGenModels {

    private const val BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
    private const val VAD_URL = BASE + "silero_vad.onnx"

    private fun root(c: Context): File = File(c.filesDir, "dub_models")

    /** Folder of one speech model (encoder.onnx, decoder.onnx, tokens.txt). */
    fun dirOf(c: Context, size: WhisperSize): File = File(root(c), "whisper-" + size.key)

    /** The speech-detection model file (silero_vad.onnx). */
    fun vadFile(c: Context): File = File(root(c), "silero_vad.onnx")

    private fun modelReady(c: Context, size: WhisperSize): Boolean {
        val d = dirOf(c, size)
        return File(d, ".ok").exists() &&
            File(d, "encoder.onnx").length() > 0L &&
            File(d, "decoder.onnx").length() > 0L &&
            File(d, "tokens.txt").length() > 0L
    }

    fun isReady(c: Context, size: WhisperSize): Boolean =
        modelReady(c, size) && vadFile(c).length() > 0L

    /**
     * Downloads and unpacks what is missing. [onProgress] gets (percent 0..100,
     * unpacking?) from a background thread. Returns null on success, otherwise
     * a short message for the person.
     */
    suspend fun install(c: Context, size: WhisperSize, onProgress: (Int, Boolean) -> Unit): String? =
        withContext(Dispatchers.IO) {
            try {
                val base = root(c)
                base.mkdirs()
                if (base.usableSpace < 600_000_000L) {
                    return@withContext "Not enough free space (about 500 MB is needed)."
                }
                if (vadFile(c).length() <= 0L) {
                    val err = fetchSmall(VAD_URL, vadFile(c))
                    if (err != null) return@withContext err
                }
                if (!modelReady(c, size)) {
                    val err = installPack(c, size, onProgress)
                    if (err != null) return@withContext err
                }
                null
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                "Speech model download failed: " + (t.message ?: t.javaClass.simpleName)
            }
        }

    /** A small file in one go (the voice-detection model, about 2 MB). */
    private fun fetchSmall(url: String, out: File): String? {
        val part = File(out.path + ".part")
        part.delete()
        val conn = openFollowing(url)
        try {
            val code = conn.responseCode
            if (code != 200) return "Download failed (server answered $code)."
            conn.inputStream.use { input ->
                FileOutputStream(part).use { fos ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        fos.write(buf, 0, n)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (part.length() <= 0L) {
            part.delete()
            return "Download failed (empty file)."
        }
        out.delete()
        if (!part.renameTo(out)) {
            part.delete()
            return "Could not save the speech model."
        }
        return null
    }

    private fun CoroutineScope.installPack(
        c: Context,
        size: WhisperSize,
        onProgress: (Int, Boolean) -> Unit,
    ): String? {
        val part = File(c.cacheDir, "dubw-" + size.key + ".tar.bz2.part")
        val finalDir = dirOf(c, size)
        val staging = File(finalDir.parentFile, finalDir.name + ".tmp")
        try {
            part.delete()
            val err = download(BASE + size.pack, part, onProgress)
            if (err != null) {
                part.delete()
                return err
            }
            staging.deleteRecursively()
            staging.mkdirs()
            unpack(part, onProgress) { name ->
                when {
                    name.endsWith("encoder.int8.onnx") -> File(staging, "encoder.onnx")
                    name.endsWith("decoder.int8.onnx") -> File(staging, "decoder.onnx")
                    name.endsWith("encoder.onnx") -> File(staging, "encoder.fp32.onnx")
                    name.endsWith("decoder.onnx") -> File(staging, "decoder.fp32.onnx")
                    name.endsWith("tokens.txt") -> File(staging, "tokens.txt")
                    else -> null
                }
            }
            // The small (int8) files are preferred; the big ones only fill a gap.
            val encFull = File(staging, "encoder.fp32.onnx")
            val decFull = File(staging, "decoder.fp32.onnx")
            if (File(staging, "encoder.onnx").length() <= 0L && encFull.length() > 0L) {
                encFull.renameTo(File(staging, "encoder.onnx"))
            }
            if (File(staging, "decoder.onnx").length() <= 0L && decFull.length() > 0L) {
                decFull.renameTo(File(staging, "decoder.onnx"))
            }
            encFull.delete()
            decFull.delete()
            if (File(staging, "encoder.onnx").length() <= 0L ||
                File(staging, "decoder.onnx").length() <= 0L ||
                File(staging, "tokens.txt").length() <= 0L
            ) {
                staging.deleteRecursively()
                part.delete()
                return "The speech model was incomplete. Tap again to retry."
            }
            File(staging, ".ok").writeText("1")
            finalDir.deleteRecursively()
            if (!staging.renameTo(finalDir)) {
                staging.deleteRecursively()
                part.delete()
                return "Could not save the speech model."
            }
            part.delete()
            return null
        } catch (ce: CancellationException) {
            staging.deleteRecursively()
            part.delete()
            throw ce
        } catch (t: Throwable) {
            staging.deleteRecursively()
            part.delete()
            return "Speech model download failed: " + (t.message ?: t.javaClass.simpleName)
        }
    }

    private fun CoroutineScope.download(url: String, part: File, onProgress: (Int, Boolean) -> Unit): String? {
        val conn = openFollowing(url)
        try {
            val code = conn.responseCode
            if (code != 200) return "Download failed (server answered $code)."
            val total = conn.contentLengthLong
            var received = 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        received += n
                        if (total > 0) {
                            val p = (received * 100 / total).toInt().coerceIn(0, 100)
                            if (p != lastPct) {
                                lastPct = p
                                onProgress(p, false)
                            }
                        }
                    }
                }
            }
            if (total > 0 && part.length() < total) {
                return "Download was cut off. Tap again to retry."
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Unpacks a .tar.bz2. [target] gets the file name of each entry and returns
     * where to write it, or null to skip the entry.
     */
    private fun CoroutineScope.unpack(
        part: File,
        onProgress: (Int, Boolean) -> Unit,
        target: (String) -> File?,
    ) {
        val size = part.length().coerceAtLeast(1L)
        var lastPct = -1
        val buf = ByteArray(128 * 1024)
        FileInputStream(part).use { fis ->
            val channel = fis.channel
            BZip2CompressorInputStream(BufferedInputStream(fis, 256 * 1024)).use { bz ->
                TarArchiveInputStream(bz).use { tar ->
                    while (true) {
                        ensureActive()
                        val e = tar.nextEntry ?: break
                        if (e.isDirectory) continue
                        val name = e.name.replace('\\', '/').substringAfterLast('/')
                        if (name.isEmpty()) continue
                        val dest = target(name) ?: continue
                        dest.parentFile?.mkdirs()
                        FileOutputStream(dest).use { out ->
                            while (true) {
                                ensureActive()
                                val n = tar.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                val p = (channel.position() * 100 / size).toInt().coerceIn(0, 100)
                                if (p != lastPct) {
                                    lastPct = p
                                    onProgress(p, true)
                                }
                            }
                        }
                    }
                }
            }
        }
        onProgress(100, true)
    }

    /** Opens [url], following redirects by hand (GitHub hands out a signed link). */
    private fun openFollowing(url: String): HttpURLConnection {
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
                if (next.isNullOrBlank()) return conn
                u = URL(URL(u), next).toString()
                hops++
                continue
            }
            return conn
        }
    }
}
