package org.hany18h.parsers.site.ar

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** XChaCha20's HChaCha20 extension; the provider still authenticates Poly1305. */
internal object DilarXChaCha20 {

    fun decrypt(key: ByteArray, nonce: ByteArray, ciphertextAndTag: ByteArray, aad: ByteArray): ByteArray {
        require(nonce.size == 24) { "Invalid XChaCha20 nonce" }
        val subkey = hChaCha20(key, nonce.copyOfRange(0, 16))
        val shortNonce = ByteArray(4) + nonce.copyOfRange(16, 24)
        return Cipher.getInstance("ChaCha20-Poly1305").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(subkey, "ChaCha20"), IvParameterSpec(shortNonce))
            updateAAD(aad)
            doFinal(ciphertextAndTag)
        }
    }

    // https://datatracker.ietf.org/doc/html/draft-irtf-cfrg-xchacha-03#section-2.2
    internal fun hChaCha20(key: ByteArray, nonce: ByteArray): ByteArray {
        require(key.size == 32 && nonce.size == 16) { "Invalid HChaCha20 key or nonce" }
        val state = IntArray(16)
        state[0] = 0x61707865
        state[1] = 0x3320646e
        state[2] = 0x79622d32
        state[3] = 0x6b206574
        for (i in 0 until 8) state[4 + i] = littleEndian(key, i * 4)
        for (i in 0 until 4) state[12 + i] = littleEndian(nonce, i * 4)
        repeat(10) {
            quarterRound(state, 0, 4, 8, 12)
            quarterRound(state, 1, 5, 9, 13)
            quarterRound(state, 2, 6, 10, 14)
            quarterRound(state, 3, 7, 11, 15)
            quarterRound(state, 0, 5, 10, 15)
            quarterRound(state, 1, 6, 11, 12)
            quarterRound(state, 2, 7, 8, 13)
            quarterRound(state, 3, 4, 9, 14)
        }
        val output = ByteArray(32)
        intArrayOf(0, 1, 2, 3, 12, 13, 14, 15).forEachIndexed { i, index ->
            for (byte in 0 until 4) output[i * 4 + byte] = (state[index] ushr (byte * 8)).toByte()
        }
        return output
    }

    private fun littleEndian(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8) or
            ((bytes[offset + 2].toInt() and 255) shl 16) or ((bytes[offset + 3].toInt() and 255) shl 24)

    private fun quarterRound(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 16)
        s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 12)
        s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 8)
        s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 7)
    }
}
