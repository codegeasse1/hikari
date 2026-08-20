package com.hikari.app.ui

import android.util.Base64
import com.hikari.app.HikariApp
import java.io.File

/**
 * Coil model helper. MRDS and 51CG encrypt their poster images (pic.xustgq.cn)
 * with a fixed AES key, so their plugins download+decrypt them into base64
 * `data:` URIs. Coil renders `ByteArray` models natively but a raw data-URI
 * string is opaque to it (and a ~1MB blob in the nav route crashes the
 * NavController). Decode once per URL, cache in memory AND on disk (keyed by
 * a hash of the URI — the decryption is deterministic, so the same URI always
 * yields the same bytes), so the home catalog's posters are instant on the
 * next app open. Http(s) posters pass through untouched — Coil's own disk
 * cache (see HikariApp) covers those.
 */
object PosterLoader {

    private const val DATA_IMAGE = "data:image/"

    private val memCache = object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean =
            size > 128
    }

    private val diskDir: File? by lazy {
        runCatching {
            File(HikariApp.instance.cacheDir, "hikari_poster_cache").apply { mkdirs() }
        }.getOrNull()
    }

    fun model(url: String?): Any? {
        if (url.isNullOrBlank()) return null
        if (!url.startsWith(DATA_IMAGE)) return url

        memCache[url]?.let { return it }

        val file = diskDir?.let { File(it, fnv1a(url)) }
        val onDisk = file?.takeIf { it.exists() }?.let {
            runCatching { it.readBytes() }.getOrNull()
        }
        if (onDisk != null && onDisk.isNotEmpty()) {
            memCache[url] = onDisk
            return onDisk
        }

        val comma = url.indexOf(',')
        if (comma <= 0) return null
        val bytes = runCatching {
            Base64.decode(url.substring(comma + 1), Base64.DEFAULT)
        }.getOrNull() ?: return null
        if (bytes.isNotEmpty()) {
            memCache[url] = bytes
            if (file != null) runCatching { file.writeBytes(bytes) }
        }
        return bytes
    }

    /** 32-bit FNV-1a over the URI bytes → stable cache filename. */
    private fun fnv1a(s: String): String {
        var h = 0x811c9dc5.toInt()
        for (b in s.encodeToByteArray()) {
            h = (h xor (b.toInt() and 0xFF))
            h *= 0x01000193
        }
        return (h.toUInt()).toString(16) + "_" + s.length
    }
}
