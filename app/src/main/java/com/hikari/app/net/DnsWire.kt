package com.hikari.app.net

import android.util.Base64
import java.net.InetAddress

/**
 * Just enough of RFC 1035 to speak DNS-over-HTTPS in its native wire format
 * (`application/dns-message`), which is what every public DoH resolver serves.
 *
 * The shared JSON API (`application/dns-json`) exists only on Cloudflare and
 * Google, so a "DNS mode" that let the user pick AdGuard or Quad9 could not be
 * built on it. The wire format is the common denominator, it is a couple of
 * hundred bytes of hand-rolled encoding instead of a DNS library, and the
 * queries this app sends are always one question of type A or AAAA with a zero
 * transaction id, which keeps it tiny.
 */
object DnsWire {

    /** Base64url, unpadded, of a `?name&type` query — the `dns=` parameter. */
    fun queryUrlSafe(host: String, type: Int): String =
        Base64.encodeToString(query(host, type), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    /** A single-question A (1) or AAAA (28) query message. */
    fun query(host: String, type: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(64)
        val labels = host.trim().trimEnd('.').split('.').filter { it.isNotEmpty() }

        fun u16(value: Int) {
            out.write((value ushr 8) and 0xFF)
            out.write(value and 0xFF)
        }

        u16(0)              // transaction id — a DoH query needs none
        u16(0x0100)         // opcode QUERY, recursion desired
        u16(1)              // one question
        u16(0)              // no answers
        u16(0)              // no authority records
        u16(0)              // no additional records
        for (label in labels) {
            val bytes = label.toByteArray(Charsets.UTF_8)
            if (bytes.isEmpty() || bytes.size > 63) continue
            out.write(bytes.size)
            out.write(bytes, 0, bytes.size)
        }
        out.write(0)        // root label
        u16(type)
        u16(1)              // class IN
        return out.toByteArray()
    }

    /** True when the reply's TC bit is set — the server had more to say than
     *  would fit, and the answer section is not to be trusted on its own. */
    fun isTruncated(response: ByteArray): Boolean =
        response.size >= 4 && (response[2].toInt() and 0x02) != 0

    /**
     * The A/AAAA addresses in a response, or an empty list. A response that
     * carries no answer (NXDOMAIN, a blocked name, a CNAME chain that ends
     * nowhere) simply yields nothing — the caller decides what to try next.
     */
    fun parseAnswers(response: ByteArray): List<InetAddress> {        if (response.size < 12) return emptyList()
        val out = ArrayList<InetAddress>(4)

        fun u16(offset: Int): Int =
            ((response[offset].toInt() and 0xFF) shl 8) or (response[offset + 1].toInt() and 0xFF)

        val questions = u16(4)
        val answers = u16(6)
        var offset = 12
        repeat(questions) {
            offset = skipName(response, offset)
            if (offset < 0) return out
            offset += 4               // QTYPE + QCLASS
            if (offset > response.size) return out
        }
        repeat(answers) {
            offset = skipName(response, offset)
            if (offset < 0 || offset + 10 > response.size) return out
            val type = u16(offset)
            val length = u16(offset + 8)
            val rdata = offset + 10
            if (rdata + length > response.size) return out
            if ((type == 1 && length == 4) || (type == 28 && length == 16)) {
                val bytes = ByteArray(length)
                System.arraycopy(response, rdata, bytes, 0, length)
                runCatching { InetAddress.getByAddress(bytes) }.getOrNull()?.let { out.add(it) }
            }
            offset = rdata + length
        }
        return out
    }

    /** Length-prefixed labels, with the 0xC0 compression pointers answered. */
    private fun skipName(data: ByteArray, start: Int): Int {
        var offset = start
        while (offset < data.size) {
            val length = data[offset].toInt() and 0xFF
            when {
                length == 0 -> return offset + 1
                length and 0xC0 == 0xC0 -> return offset + 2   // pointer ends the name
                else -> offset += 1 + length
            }
        }
        return -1
    }
}
