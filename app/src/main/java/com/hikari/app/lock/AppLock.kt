package com.hikari.app.lock

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object AppLock {

    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16

    /** The algorithms a blob may have been written with, in preference order. */
    private val ALGOS = listOf("sha256", "sha1")

    /** A fresh random salt, hex. */
    fun newSalt(): String {
        val bytes = ByteArray(SALT_BYTES)
        SecureRandom().nextBytes(bytes)
        return hex(bytes)
    }

    /** The derivation this platform will actually run (see the class comment). */
    fun algorithm(): String = try {
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        "sha256"
    } catch (e: Throwable) {
        "sha1"
    }

    /** `algo:salt:hash` for [password] — what the store keeps. */
    fun encode(password: String, salt: String = newSalt()): String {
        val algo = algorithm()
        return "$algo:$salt:${hex(derive(password.trim(), salt, algo))}"
    }

    private class Parts(val algo: String?, val salt: String, val hash: String)

    private fun parse(stored: String): Parts? {
        if (stored.isBlank()) return null
        val parts = stored.split(":")
        return when {
            parts.size == 3 && parts.none { it.isBlank() } -> Parts(parts[0], parts[1], parts[2])
            parts.size == 2 && parts.none { it.isBlank() } -> Parts(null, parts[0], parts[1])
            else -> null
        }
    }

    /** True when [stored] looks like a secret this app wrote. */
    fun isSet(stored: String): Boolean = parse(stored)?.let { bytes(it.hash) } != null

    /** Does [password] produce [stored]? False for a blank or unreadable blob. */
    fun verify(password: String, stored: String): Boolean {
        if (password.isEmpty()) return false
        val parts = parse(stored) ?: return false
        val expected = bytes(parts.hash) ?: return false
        // The algorithm the blob records is the one to derive with; only a blob
        // that records none (an older build's `salt:hash`) is tried with both,
        // so the ordinary wrong-PIN path still costs exactly ONE derivation and
        // reports as fast as it always did.
        val algos = if (parts.algo in ALGOS) listOf(parts.algo!!) else ALGOS
        val variants =
            if (password == password.trim()) listOf(password)
            else listOf(password, password.trim())
        for (algo in algos) {
            for (value in variants) {
                val actual = runCatching { derive(value, parts.salt, algo) }.getOrNull() ?: continue
                if (MessageDigest.isEqual(expected, actual)) return true
            }
        }
        return false
    }

    private fun derive(password: String, saltHex: String, algo: String): ByteArray {
        val salt = bytes(saltHex) ?: ByteArray(0)
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance(
            if (algo == "sha1") "PBKDF2WithHmacSHA1" else "PBKDF2WithHmacSHA256"
        )
        return try {
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Hex → bytes, or null when it is not an even-length hex string. */
    private fun bytes(hex: String): ByteArray? {
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0f])
        }
        return sb.toString()
    }
}
