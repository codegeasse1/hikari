package org.hany18h.parsers.site.ar

import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** RFC 5297 AES-SIV, using standard Android/JVM AES primitives. */
internal object DilarAesSiv {
    fun decrypt(key: ByteArray, tag: ByteArray, ciphertext: ByteArray, associatedData: List<ByteArray>): ByteArray {
        require(key.size in setOf(32, 48, 64) && tag.size == 16) { "Invalid Dilar AES-SIV key or tag" }
        val half = key.size / 2
        val counter = tag.copyOf().apply {
            this[8] = (this[8].toInt() and 0x7f).toByte()
            this[12] = (this[12].toInt() and 0x7f).toByte()
        }
        val plaintext = Cipher.getInstance("AES/CTR/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key.copyOfRange(half, key.size), "AES"), IvParameterSpec(counter))
            doFinal(ciphertext)
        }
        val macKey = key.copyOfRange(0, half)
        var d = cmac(macKey, ByteArray(16))
        for (data in associatedData) d = xor(doubleBlock(d), cmac(macKey, data))
        val input = if (plaintext.size >= 16) {
            plaintext.copyOf().apply {
                val offset = size - 16
                for (i in d.indices) this[offset + i] = (this[offset + i].toInt() xor d[i].toInt()).toByte()
            }
        } else {
            xor(doubleBlock(d), plaintext.copyOf(16).apply { this[plaintext.size] = 0x80.toByte() })
        }
        if (!MessageDigest.isEqual(tag, cmac(macKey, input))) {
            plaintext.fill(0)
            throw AEADBadTagException("Dilar AES-SIV authentication failed")
        }
        return plaintext
    }

    private fun cmac(key: ByteArray, message: ByteArray): ByteArray {
        val aes = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        }
        val k1 = doubleBlock(aes.doFinal(ByteArray(16)))
        val k2 = doubleBlock(k1)
        val blocks = ((message.size + 15) / 16).coerceAtLeast(1)
        var state = ByteArray(16)
        repeat(blocks - 1) { index ->
            state = aes.doFinal(xor(state, message.copyOfRange(index * 16, index * 16 + 16)))
        }
        val offset = (blocks - 1) * 16
        val complete = message.isNotEmpty() && message.size % 16 == 0
        val last = if (complete) {
            xor(message.copyOfRange(offset, message.size), k1)
        } else {
            val tail = message.copyOfRange(offset, message.size)
            xor(tail.copyOf(16).apply { this[tail.size] = 0x80.toByte() }, k2)
        }
        return aes.doFinal(xor(state, last))
    }

    private fun doubleBlock(block: ByteArray): ByteArray {
        var carry = 0
        val result = ByteArray(16)
        for (i in block.indices.reversed()) {
            val byte = block[i].toInt() and 0xff
            result[i] = ((byte shl 1) or carry).toByte()
            carry = byte ushr 7
        }
        if (carry != 0) result[15] = (result[15].toInt() xor 0x87).toByte()
        return result
    }

    private fun xor(left: ByteArray, right: ByteArray): ByteArray = ByteArray(left.size) { i ->
        (left[i].toInt() xor right[i].toInt()).toByte()
    }
}
