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
 *  * The comparison is constant-time ([MessageDigest.isEqual]), so a wrong
 *    password cannot be narrowed down by how long the check took.
 *
 * There is deliberately NO recovery: nothing in the app can read the password
 * back, which is the whole point of storing only a derivation. The settings
 * card says so before the password is set.
 */
object AppLock {

    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16

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

    /** True when [stored] looks like a secret this app wrote. */
    fun isSet(stored: String): Boolean {
        val parts = stored.split(":")
        return parts.size == 3 && parts[0].isNotBlank() && parts[1].isNotBlank() &&
            parts[2].isNotBlank()
    }

    /** Does [password] produce [stored]? False for a blank or unreadable blob. */
    fun verify(password: String, stored: String): Boolean {
        if (password.isEmpty() || !isSet(stored)) return false
        val parts = stored.split(":")
        val algo = parts[0]
        val salt = parts[1]
        val expected = runCatching { parts[2].chunked(2).map { it.toInt(16).toByte() }.toByteArray() }
            .getOrNull() ?: return false
        val actual = runCatching { derive(password.trim(), salt, algo) }.getOrNull() ?: return false
        return MessageDigest.isEqual(expected, actual)
    }

    private fun derive(password: String, saltHex: String, algo: String): ByteArray {
        val salt = saltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
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
