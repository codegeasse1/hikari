package com.hikari.app.reader.cache

import android.graphics.BitmapFactory
import com.hikari.app.reader.source.MangaSource
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object WebtoonPageCache {

    const val TALL_RATIO = 3f

    fun isTallPage(width: Int, height: Int): Boolean =
        if (width <= 0 || height <= 0) true
        else height.toFloat() > width.toFloat() * TALL_RATIO

    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<File>>()
    private val dims = ConcurrentHashMap<String, Pair<Int, Int>>()
    private val animated = ConcurrentHashMap<String, Boolean>()

    // Downloads run in this shared scope so no single caller's cancellation (e.g. a page item
    // scrolling off-screen mid-download) can kill a fetch that other callers are awaiting.
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val MAX_CACHE_BYTES = 400L * 1024 * 1024
    private const val EVICT_EVERY_N_WRITES = 25
    private val writesSinceEvict = AtomicInteger(0)

    fun keyFor(imageUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(imageUrl.toByteArray())
            .take(8)
            .joinToString("") { String.format("%02x", it) }
        return digest
    }

    fun targetFile(key: String, dir: File): File = File(dir, "$key.img")

    fun cachedSize(imageUrl: String): Pair<Int, Int>? = dims[keyFor(imageUrl)]

    suspend fun fileFor(
        desc: MangaSource.PageDescriptor,
        source: MangaSource,
        dir: File,
    ): File {
        val key = keyFor(desc.imageUrl)
        val target = targetFile(key, dir)
        if (target.exists() && target.length() > 0) return target

        val existing = inFlight[key]
        if (existing != null) return existing.await()

        val deferred = CompletableDeferred<File>()
        val winner = inFlight.putIfAbsent(key, deferred)
        if (winner != null) return winner.await()

        // We own the fetch. Launch it in the shared scope: the caller below only awaits the result,
        // so if the caller is cancelled the download still completes for the other waiters.
        downloadScope.launch {
            try {
                val downloaded = withContext(Dispatchers.IO) {
                    dir.mkdirs()
                    val tmp = File(dir, "$key.tmp")
                    tmp.delete()
                    source.downloadPageImage(desc, tmp)
                    tmp.renameTo(target)
                    target
                }
                maybeEvict(dir)
                deferred.complete(downloaded)
            } catch (e: Throwable) {
                // Never happens in practice (the scope's jobs are never cancelled), but don't
                // mislabel a cancellation as a network failure.
                deferred.completeExceptionally(
                    if (e is CancellationException) IOException("Download cancelled") else e,
                )
            } finally {
                inFlight.remove(key)
            }
        }
        return deferred.await()
    }

    /** Cheap intrinsic pixel size of a page's image file (bounds-only decode, cached). */
    suspend fun dimensions(desc: MangaSource.PageDescriptor, dir: File): Pair<Int, Int>? {
        val key = keyFor(desc.imageUrl)
        dims[key]?.let { return it }
        val f = targetFile(key, dir)
        if (!f.exists() || f.length() == 0L) return null
        return withContext(Dispatchers.IO) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, opts)
            val d = if (opts.outWidth > 0 && opts.outHeight > 0) {
                Pair(opts.outWidth, opts.outHeight)
            } else {
                null
            }
            if (d != null) dims[key] = d
            d
        }
    }

    suspend fun meta(desc: MangaSource.PageDescriptor, dir: File): PageMeta? {
        val key = keyFor(desc.imageUrl)
        dims[key]?.let { d ->
            val anim = animated[key] ?: withContext(Dispatchers.IO) {
                val f = targetFile(key, dir)
                if (f.exists() && f.length() > 0) isAnimatedFile(f).also { animated[key] = it } else false
            }
            return PageMeta(d.first, d.second, anim, isTallPage(d.first, d.second))
        }
        val f = targetFile(key, dir)
        if (!f.exists() || f.length() == 0L) return null
        return withContext(Dispatchers.IO) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, opts)
            val d = if (opts.outWidth > 0 && opts.outHeight > 0) {
                Pair(opts.outWidth, opts.outHeight)
            } else {
                null
            }
            if (d == null) {
                null
            } else {
                dims[key] = d
                val anim = isAnimatedFile(f)
                animated[key] = anim
                PageMeta(d.first, d.second, anim, isTallPage(d.first, d.second))
            }
        }
    }

    suspend fun prime(desc: MangaSource.PageDescriptor, dir: File) {
        meta(desc, dir)
    }

    /** True if [file] looks like an animated GIF or animated WebP (cheap 32-byte header read). */
    private fun isAnimatedFile(file: File): Boolean {
        return try {
            val bytes = ByteArray(32)
            val raf = java.io.RandomAccessFile(file, "r")
            try {
                val n = raf.read(bytes)
                if (n < 12) return false
                val head = String(bytes, 0, n.coerceAtMost(12), Charsets.US_ASCII)
                if (head.startsWith("GIF8")) return true
                if (head.startsWith("RIFF") && n >= 12 && head.substring(8, 12) == "WEBP") {
                    if (n >= 24 && head.substring(12, 16) == "VP8X") {
                        return (bytes[20].toInt() and 0x02) != 0
                    }
                }
                false
            } finally {
                raf.close()
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Deletes the oldest cache files when the directory exceeds the size cap (runs rarely). */
    private fun maybeEvict(dir: File) {
        if (writesSinceEvict.incrementAndGet() < EVICT_EVERY_N_WRITES) return
        writesSinceEvict.set(0)
        val files = dir.listFiles()?.filter { it.name.endsWith(".img") } ?: return
        val total = files.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        // Delete oldest-first until the cache is back under half its cap.
        val sorted = files.sortedBy { it.lastModified() }
        var toFree = total - (MAX_CACHE_BYTES / 2)
        for (f in sorted) {
            if (toFree <= 0) break
            toFree -= f.length()
            f.delete()
        }
    }
}

/** Everything a page holder needs to render a page once its bytes are on disk. */
data class PageMeta(
    val width: Int,
    val height: Int,
    val isAnimated: Boolean,
    val isTall: Boolean,
)
