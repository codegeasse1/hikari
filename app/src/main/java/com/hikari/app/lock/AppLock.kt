package com.hikari.app.lock

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The app lock's password storage (Settings → Privacy & Browsing → App lock).
 *
 * The password is never written anywhere: what is stored is a PBKDF2
 * derivation of it plus a random 16-byte salt, as `algo:salt:hash`, all hex.
 *
 *  * `algo` is recorded so an install that was locked on an older phone still
 *    verifies after the derivation changes: `sha256` is used where the platform
 *    has it (API 26+, i.e. everything current) and `sha1` where it does not —
 *    Android only added PBKDF2WithHmacSHA256 in API 26, and this app runs on
 *    API 24. A lock that silently stops accepting the right password on an old
 *    device would be worse than one built on the older primitive.
 *  * A blob with NO algorithm — `salt:hash`, the shape written before the
 *    algorithm was recorded — is still accepted ([parse] takes both), and with
 *    no algorithm recorded BOTH primitives are tried. That is a lock that was
 *    set by an older build: refusing it would lock the user out of their own
 *    app for a reason no one could see.
 *  * The password is tried trimmed and exactly as typed, for the same reason: a
 *    build that did not trim (or a keyboard that appended a space) would
 *    otherwise produce a blob that no trimmed attempt can ever match.
 *  * The comparison is constant-time ([MessageDigest.isEqual]), so a wrong
 *    password cannot be narrowed down by how long the check took.
 *
 * There is deliberately NO recovery: nothing in the app can read the password
 * back, which is the whole point of storing only a derivation. The settings
 * card says so before the password is set, and the unlock screen offers the one
 * honest way out — turning the lock off (see [com.hikari.app.ui.AppLockScreen]).
 */
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
        // Trimmed on BOTH sides of the trip: a keyboard's autocorrect can leave
        // a trailing space in a text field that a password field would never
        // produce, and a correct password that fails because of an invisible
        // character is the "it says wrong even when I type it right" report.
        return "$algo:$salt:${derive(password.trim(), salt, algo)}"
    }

    /**
     * A stored blob, whichever shape it is: `algo:salt:hash` (what this build
     * writes) or `salt:hash` (what it wrote before the algorithm was recorded).
     * [algo] is null when the blob does not name one.
     */
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
        // Exactly as typed first — that is what a password field hands over —
        // and the trimmed form only when it differs, again to keep the common
        // path to a single derivation.
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
