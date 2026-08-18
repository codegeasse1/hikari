package com.hikari.app.ui

import android.util.Base64

/**
 * Coil model helper. MRDS and 51CG encrypt their poster images (pic.xustgq.cn)
 * with a fixed AES key, so their plugins download+decrypt them into base64
 * `data:` URIs. Coil renders `ByteArray` models natively but a raw data-URI
 * string is opaque to it (and a ~1MB blob in the nav route crashes the
 * NavController). Decode once per URL and cache, so every row just hands Coil
 * the bytes.
 */
object PosterLoader {

    private const val DATA_IMAGE = "data:image/"

    private val cache = object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean =
            size > 128
    }

    fun model(url: String?): Any? {
        if (url.isNullOrBlank()) return null
        if (url.startsWith(DATA_IMAGE)) {
            cache[url]?.let { return it }
            val comma = url.indexOf(',')
            if (comma <= 0) return null
            val bytes = runCatching {
                Base64.decode(url.substring(comma + 1), Base64.DEFAULT)
            }.getOrNull() ?: return null
            cache[url] = bytes
            return bytes
        }
        return url
    }
}
