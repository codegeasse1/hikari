package com.hikari.app.nuvio

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Native crypto bridges for the nuvio JS runtime, mirroring the contract the
 * NuvioMobile app's CryptoBridge exposes. The polyfill (boot.js) calls these
 * synchronous functions with hex-encoded args and expects a hex-encoded return.
 *
 * Exact contract (do not change — boot.js depends on it):
 *   __crypto_digest_hex_raw(alg, hexData)                  -> hex digest
 *   __crypto_hmac_hex_raw(alg, hexKey, hexData)            -> hex mac
 *   __crypto_pbkdf2_hex(hexPass, hexSalt, iters, keyBits, hash) -> hex key
 *   __crypto_aes_encrypt_hex(mode, hexKey, hexIv, hexData) -> hex ciphertext
 *   __crypto_aes_decrypt_hex(mode, hexKey, hexIv, hexData) -> hex plaintext
 *   __crypto_get_random_values_hex(byteLength)             -> hex random
 *
 * alg/hash/mode names are UPPERCASE without dashes (SHA1, SHA256, MD5, ...;
 * AES-CBC, AES-ECB, AES-GCM, optionally suffixed -NoPadding). The polyfill
 * passes RAW unpadded data for CBC/ECB, so PKCS7 padding is applied here
 * (Java's PKCS5Padding is identical to PKCS7 for AES's 16-byte block). GCM
 * uses a 128-bit tag and no padding.
 *
 * Sign/verify bridges are intentionally NOT registered: the polyfill performs
 * HMAC signing itself and reports a clean "Native signature bridge is
 * unavailable" error for RSA/ECDSA — matching nuvio, which registers no
 * signature bridges either.
 */
object NuvioCryptoBridge {

    private val random = SecureRandom()

    fun bindAll(qjs: QuickJs) {
        qjs.function("__crypto_get_random_values_hex") { args ->
            val n = (args.getOrNull(0) as? Number)?.toInt() ?: 0
            val bytes = ByteArray(n.coerceAtLeast(0))
            random.nextBytes(bytes)
            toHex(bytes)
        }

        qjs.function("__crypto_digest_hex_raw") { args ->
            val alg = args.getOrNull(0)?.toString() ?: ""
            val data = fromHex(args.getOrNull(1)?.toString() ?: "")
            val digest = MessageDigest.getInstance(jcaName(alg))
            toHex(digest.digest(data))
        }

        qjs.function("__crypto_hmac_hex_raw") { args ->
            val alg = args.getOrNull(0)?.toString() ?: ""
            val key = fromHex(args.getOrNull(1)?.toString() ?: "")
            val data = fromHex(args.getOrNull(2)?.toString() ?: "")
            val mac = Mac.getInstance(jcaMacName(alg))
            mac.init(SecretKeySpec(key, jcaMacName(alg)))
            toHex(mac.doFinal(data))
        }

        qjs.function("__crypto_pbkdf2_hex") { args ->
            val pass = fromHex(args.getOrNull(0)?.toString() ?: "")
            val salt = fromHex(args.getOrNull(1)?.toString() ?: "")
            val iterations = (args.getOrNull(2) as? Number)?.toInt() ?: 1000
            val keyBits = (args.getOrNull(3) as? Number)?.toInt() ?: 256
            val hash = args.getOrNull(4)?.toString() ?: "SHA1"
            toHex(pbkdf2(pass, salt, iterations, keyBits / 8, hash))
        }

        qjs.function("__crypto_aes_encrypt_hex") { args ->
            val mode = args.getOrNull(0)?.toString() ?: ""
            val key = fromHex(args.getOrNull(1)?.toString() ?: "")
            val iv = fromHex(args.getOrNull(2)?.toString() ?: "")
            val data = fromHex(args.getOrNull(3)?.toString() ?: "")
            toHex(aesCrypt(mode, encrypt = true, key, iv, data))
        }

        qjs.function("__crypto_aes_decrypt_hex") { args ->
            val mode = args.getOrNull(0)?.toString() ?: ""
            val key = fromHex(args.getOrNull(1)?.toString() ?: "")
            val iv = fromHex(args.getOrNull(2)?.toString() ?: "")
            val data = fromHex(args.getOrNull(3)?.toString() ?: "")
            toHex(aesCrypt(mode, encrypt = false, key, iv, data))
        }
    }

    // SHA1 -> SHA-1, SHA256 -> SHA-256, ... (uppercase alg names from JS)
    private fun jcaName(alg: String): String {
        val upper = alg.uppercase()
        return when {
            upper == "MD5" -> "MD5"
            upper.length >= 4 && upper.startsWith("SHA") ->
                "SHA-" + upper.substring(3)
            else -> throw IllegalArgumentException("unsupported hash: $alg")
        }
    }

    // HmacSHA1, HmacSHA256, HmacMD5, ...
    private fun jcaMacName(alg: String): String {
        val upper = alg.uppercase()
        return when {
            upper == "MD5" -> "HmacMD5"
            upper.length >= 4 && upper.startsWith("SHA") ->
                "HmacSHA-" + upper.substring(3)
            else -> throw IllegalArgumentException("unsupported hmac hash: $alg")
        }
    }

    // PBKDF2 done by hand so API 24+ works regardless of
    // PBKDF2WithHmacSHA256 availability (that algorithm needs API 26).
    private fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int, hash: String): ByteArray {
        val digest = MessageDigest.getInstance(jcaName(hash))
        val blockSize = 64
        val outSize = digest.digestLength
        val macName = jcaMacName(hash)
        val mac = Mac.getInstance(macName)
        mac.init(SecretKeySpec(password, macName))
        val out = ByteArray(dkLen)
        var off = 0
        var block = 1
        while (off < dkLen) {
            val u = ByteArray(salt.size + 4)
            salt.copyInto(u)
            u[salt.size] = (block ushr 24).toByte()
            u[salt.size + 1] = (block ushr 16).toByte()
            u[salt.size + 2] = (block ushr 8).toByte()
            u[salt.size + 3] = block.toByte()
            var t = mac.doFinal(u)
            var tmp = t
            for (i in 1 until iterations) {
                tmp = mac.doFinal(tmp)
                for (j in t.indices) t[j] = (t[j].toInt() xor tmp[j].toInt()).toByte()
            }
            val take = minOf(t.size, dkLen - off)
            t.copyInto(out, off, 0, take)
            off += take
            block++
        }
        return out
    }

    private fun aesCrypt(mode: String, encrypt: Boolean, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val normalized = mode.uppercase()
        val gcm = normalized.contains("GCM")
        val noPadding = normalized.contains("NOPADDING")
        val transformer = if (gcm) "AES/GCM/NoPadding" else
            if (noPadding) "AES/" + (if (normalized.contains("ECB")) "ECB" else "CBC") + "/NoPadding"
            else "AES/" + (if (normalized.contains("ECB")) "ECB" else "CBC") + "/PKCS5Padding"
        val cipher = Cipher.getInstance(transformer)
        val spec = when {
            gcm -> GCMParameterSpec(128, iv)
            normalized.contains("ECB") -> null
            else -> IvParameterSpec(iv)
        }
        cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)
        return cipher.doFinal(data)
    }

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            if (v < 16) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }

    private fun fromHex(s: String): ByteArray {
        var hex = s.replace(Regex("[^0-9a-fA-F]"), "")
        if (hex.length % 2 == 1) hex = "0$hex"
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16).toByte()
        }
        return out
    }
}
