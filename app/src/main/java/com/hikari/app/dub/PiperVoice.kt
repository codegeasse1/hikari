package com.hikari.app.dub

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/**
 * (Named AiDubPiperPack so it never clashes with the Dub engine's PiperVoice
 * data class in SherpaModels.kt.)
 *
 * Piper voice packs: per-language int8 VITS voices from the official
 * sherpa-onnx tts-models release (~20MB each). Downloaded once into the
 * app's own files, extracted, verified (model + tokens), deletable.
 */
object AiDubPiperPack {
    private const val BASE =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"

    data class Found(val id: String, val dir: File, val onnx: String)

    private fun voiceId(L: AiDubLang): String =
        (L.piperTarball ?: "").removeSuffix(".tar.bz2")

    fun dirFor(ctx: Context, L: AiDubLang): File =
        File(ctx.filesDir, "aidub/voices/" + voiceId(L))

    fun locate(ctx: Context, L: AiDubLang): Found? {
        if (L.piperTarball == null) return null
        val dir = dirFor(ctx, L)
        if (!dir.isDirectory) return null
        val onnx = dir.walkTopDown()
            .firstOrNull { it.isFile && it.extension == "onnx" }
            ?: return null
        val tokens = dir.walkTopDown()
            .firstOrNull { it.isFile && it.name == "tokens.txt" }
            ?: return null
        if (!tokens.isFile) return null
        return Found(voiceId(L), onnx.parentFile ?: dir, onnx.name)
    }

    fun isReady(ctx: Context, L: AiDubLang): Boolean = locate(ctx, L) != null

    fun voiceMB(ctx: Context, L: AiDubLang): Long {
        val dir = dirFor(ctx, L)
        if (!dir.isDirectory) return 0
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1048576L
    }

    fun downloadedMB(ctx: Context): Long {
        val root = File(ctx.filesDir, "aidub/voices")
        if (!root.isDirectory) return 0
        return root.walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1048576L
    }

    fun delete(ctx: Context, L: AiDubLang) {
        try {
            dirFor(ctx, L).deleteRecursively()
        } catch (e: Exception) {
        }
    }

    fun clearAll(ctx: Context) {
        try {
            File(ctx.filesDir, "aidub/voices").deleteRecursively()
        } catch (e: Exception) {
        }
    }

    /** Download + extract + verify. True only when model + tokens exist. */
    suspend fun download(
        ctx: Context,
        L: AiDubLang,
        onProgress: ((done: Long, total: Long) -> Unit)?,
    ): Boolean = withContext(Dispatchers.IO) {
        val tarball = L.piperTarball ?: return@withContext false
        val dir = dirFor(ctx, L)
        try {
            dir.mkdirs()
            val archive = File(dir, "pack.tar.bz2")
            if (archive.exists()) archive.delete()
            val url = URL(BASE + tarball)
            var conn = openConn(url)
            var code = conn.responseCode
            var hops = 0
            while ((code == 301 || code == 302 || code == 303 || code == 307 || code == 308) && hops < 6) {
                val loc = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                conn = openConn(URL(loc))
                code = conn.responseCode
                hops++
            }
            if (code !in 200..299) {
                conn.disconnect()
                return@withContext false
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                archive.outputStream().use { output ->
                    val buf = ByteArray(65536)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        onProgress?.invoke(done, total)
                    }
                }
            }
            conn.disconnect()
            extract(archive, dir)
            try {
                archive.delete()
            } catch (e: Exception) {
            }
            locate(ctx, L) != null
        } catch (e: Exception) {
            try {
                dir.deleteRecursively()
            } catch (e2: Exception) {
            }
            false
        }
    }

    private fun openConn(url: URL): HttpURLConnection {
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 30000
        c.readTimeout = 60000
        c.instanceFollowRedirects = false
        c.setRequestProperty("User-Agent", "Hikari-AiDub")
        c.connect()
        return c
    }

    private fun extract(archive: File, dest: File) {
        FileInputStream(archive).use { fis ->
            BZip2CompressorInputStream(fis).use { bz ->
                TarArchiveInputStream(bz).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        val out = File(dest, entry.name)
                        // Zip-slip guard: nothing escapes the voice dir.
                        if (!out.canonicalPath.startsWith(dest.canonicalPath)) {
                            entry = tar.nextEntry
                            continue
                        }
                        if (entry.isDirectory) out.mkdirs()
                        else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { tar.copyTo(it) }
                        }
                        entry = tar.nextEntry
                    }
                }
            }
        }
    }
}
