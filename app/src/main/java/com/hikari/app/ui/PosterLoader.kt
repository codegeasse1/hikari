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

    /** Prefix of the tiny token [tokenize] returns for an oversized base64
     *  `data:` poster — the real bytes live in the disk cache under the token's
     *  hash, so a catalog can hold thousands of posters without blowing the
     *  heap while the grid still renders them. */
    private const val CACHE_TOKEN = "data:cache/"

    private val memCache = object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean =
            size > 256
    }

    private val diskDir: File? by lazy {
        runCatching {
            File(HikariApp.instance.cacheDir, "hikari_poster_cache").apply { mkdirs() }
        }.getOrNull()
    }

    fun model(url: String?): Any? {
        if (url.isNullOrBlank()) return null
        if (url.startsWith(CACHE_TOKEN)) return fromToken(url)
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

        val bytes = decodeDataUri(url) ?: return null
        if (bytes.isNotEmpty()) {
            memCache[url] = bytes
            if (file != null) runCatching { file.writeBytes(bytes) }
        }
        return bytes
    }

    /** Decodes the base64 payload of a `data:` URI (null on any failure). */
    private fun decodeDataUri(url: String): ByteArray? {
        val comma = url.indexOf(',')
        if (comma <= 0) return null
        return runCatching {
            Base64.decode(url.substring(comma + 1), Base64.DEFAULT)
        }.getOrNull()
    }

    /**
     * Collapses a huge base64 `data:` poster into a tiny stable disk-cache
     * token so a giant catalog can hold thousands of posters in memory without
     * an OutOfMemoryError, while the grid still shows them ([model] resolves
     * the token back to the persisted bytes). Regular http(s) URLs pass through
     * unchanged. Returns null when the data URI can't be decoded — the poster
     * is simply dropped then (blank cell).
     */
    fun tokenize(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (!url.startsWith(DATA_IMAGE)) return url
        val hash = fnv1a(url)
        val file = diskDir?.let { File(it, hash) }
        val have = file?.let { it.exists() && it.length() > 0 } ?: false
        if (!have) {
            val bytes = decodeDataUri(url) ?: return null
            if (bytes.isEmpty()) return null
            if (file != null) runCatching { file.writeBytes(bytes) }
        }
        return CACHE_TOKEN + hash
    }

    /** Resolves a [CACHE_TOKEN] token back to the persisted poster bytes. */
    private fun fromToken(token: String): ByteArray? {
        memCache[token]?.let { return it }
        val hash = token.removePrefix(CACHE_TOKEN)
        val bytes = diskDir?.let { File(it, hash) }?.takeIf { it.exists() }?.let {
            runCatching { it.readBytes() }.getOrNull()
        }
        if (bytes != null && bytes.isNotEmpty()) {
            memCache[token] = bytes
            return bytes
        }
        return null
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
