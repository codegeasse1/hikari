package com.hikari.desktop.engine

import com.caoccao.javet.annotations.V8Function
import com.caoccao.javet.interfaces.IJavetAnonymous
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * WebCrypto primitives for the JS extension engines, exposed to V8 as
 * `__crypto_*_hex` globals. Hex in / hex out, byte-identical semantics to the
 * Android NuvioCryptoBridge (JCE is pure JVM, so this ports 1:1).
 */
class DesktopCryptoBridge : IJavetAnonymous {

    private val random = SecureRandom()

    @V8Function
    fun __crypto_get_random_values_hex(n: Int): String {
        val bytes = ByteArray(n.coerceAtLeast(0))
        random.nextBytes(bytes)
        return toHex(bytes)
    }

    @V8Function
    fun __crypto_digest_hex_raw(alg: String, dataHex: String): String {
        val digest = MessageDigest.getInstance(jcaName(alg))
        return toHex(digest.digest(fromHex(dataHex)))
    }

    @V8Function
    fun __crypto_hmac_hex_raw(alg: String, keyHex: String, dataHex: String): String {
        val mac = Mac.getInstance(jcaMacName(alg))
        mac.init(SecretKeySpec(fromHex(keyHex), jcaMacName(alg)))
        return toHex(mac.doFinal(fromHex(dataHex)))
    }

    @V8Function
    fun __crypto_pbkdf2_hex(passHex: String, saltHex: String, iterations: Int, keyBits: Int, hash: String): String {
        return toHex(pbkdf2(fromHex(passHex), fromHex(saltHex), iterations.coerceAtLeast(1), (keyBits / 8).coerceAtLeast(1), hash.ifBlank { "SHA1" }))
    }

    @V8Function
    fun __crypto_aes_encrypt_hex(mode: String, keyHex: String, ivHex: String, dataHex: String): String {
        return toHex(aesCrypt(mode, encrypt = true, fromHex(keyHex), fromHex(ivHex), fromHex(dataHex)))
    }

    @V8Function
    fun __crypto_aes_decrypt_hex(mode: String, keyHex: String, ivHex: String, dataHex: String): String {
        return toHex(aesCrypt(mode, encrypt = false, fromHex(keyHex), fromHex(ivHex), fromHex(dataHex)))
    }

    private fun jcaName(alg: String): String {
        val upper = alg.uppercase()
        return when {
            upper == "MD5" -> "MD5"
            upper.length >= 4 && upper.startsWith("SHA") -> "SHA-" + upper.substring(3)
            else -> throw IllegalArgumentException("unsupported hash: $alg")
        }
    }

    private fun jcaMacName(alg: String): String {
        val upper = alg.uppercase()
        return when {
            upper == "MD5" -> "HmacMD5"
            upper.length >= 4 && upper.startsWith("SHA") -> "HmacSHA-" + upper.substring(3)
            else -> throw IllegalArgumentException("unsupported hmac hash: $alg")
        }
    }

    private fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int, hash: String): ByteArray {
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
