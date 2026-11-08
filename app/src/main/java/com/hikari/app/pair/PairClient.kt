package com.hikari.app.pair

import com.hikari.app.data.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.DatagramPacket
import java.util.concurrent.TimeUnit

object PairClient {

    /** A hard ceiling on what a guest will accept. A real setup is single-digit
     *  MB (extensions plus notes); the cap is there so a device on the LAN that
     *  is NOT a Hikari (or a hostile one that guessed a code) cannot make this
     *  app allocate without limit. */
    private const val MAX_BUNDLE_BYTES = 256L * 1024L * 1024L

    class PairException(message: String) : Exception(message)

    suspend fun discover(code: String, timeoutMs: Long = 6000L): PairTarget? =
        withContext(Dispatchers.IO) {
            if (code.isBlank()) return@withContext null
            val socket = PairProtocol.udpSocket() ?: return@withContext null
            try {
                val probe = (PairProtocol.PROBE_PREFIX + code).toByteArray(Charsets.UTF_8)
                val broadcast = PairProtocol.broadcastAddress() ?: return@withContext null
                runCatching {
                    socket.send(
                        DatagramPacket(probe, probe.size, broadcast, PairProtocol.UDP_PORT)
                    )
                }.onFailure { return@withContext null }
                val deadline = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(96)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    val got = runCatching { socket.receive(packet); true }.getOrDefault(false)
                    if (!got) continue
                    val text = runCatching {
                        String(packet.data, packet.offset, packet.length, Charsets.UTF_8).trim()
                    }.getOrNull().orEmpty()
                    if (!text.startsWith(PairProtocol.REPLY_PREFIX)) continue
                    val port = text.removePrefix(PairProtocol.REPLY_PREFIX).trim().toIntOrNull()
                        ?: PairProtocol.DEFAULT_PORT
                    val host = packet.address?.hostAddress?.trim().orEmpty()
                    if (host.isBlank()) continue
                    Logs.log("Pair", "found a host at $host:$port")
                    return@withContext PairTarget(
                        host = host,
                        port = port,
                        code = code,
                        deviceName = "",
                    )
                }
                null
            } finally {
                runCatching { socket.close() }
            }
        }

    suspend fun downloadTo(target: PairTarget, dest: File): Long = withContext(Dispatchers.IO) {
        if (target.host.isBlank()) throw PairException("No address to connect to")
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .callTimeout(300, TimeUnit.SECONDS)
            .build()
        val url = "http://${target.host}:${target.port}/bundle?t=${target.code}"
        val request = Request.Builder().url(url).get().build()
        val started = System.currentTimeMillis()
        try {
            client.newCall(request).execute().use { response ->
                if (response.code == 403) {
                    throw PairException("That code was refused — check it on the other device")
                }
                if (!response.isSuccessful) {
                    throw PairException("The other device answered with ${response.code}")
                }
                val body = response.body
                val length = body.contentLength()
                if (length > MAX_BUNDLE_BYTES) throw PairException("That transfer is too large")
                var total = 0L
                (body.byteStream() ?: throw PairException("The transfer failed")).use { src ->
                    java.io.FileOutputStream(tmp).buffered(64 * 1024).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = src.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_BUNDLE_BYTES) throw PairException("That transfer is too large")
                            out.write(buf, 0, n)
                        }
                    }
                }
                if (dest.exists()) dest.delete()
                tmp.renameTo(dest)
                Logs.log(
                    "Pair",
                    "received ${total / 1024} KB from ${target.label} in " +
                        "${System.currentTimeMillis() - started}ms",
                )
                total
            }
        } catch (e: PairException) {
            runCatching { tmp.delete() }
            throw e
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            throw PairException(explain(e) + (e.message?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()))
        }
    }

    /** A network failure in words. The distinction that matters is "nothing is
     *  listening at that address" (the address is wrong, or the other device's
     *  pairing screen is closed) versus "we could not reach the network at all". */
    private fun explain(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> "The other device did not answer in time"
        is java.net.ConnectException -> "Nothing is listening on the other device"
        is java.net.UnknownHostException -> "That address could not be found"
        is java.io.IOException -> "The connection dropped — stay on the same Wi-Fi"
        else -> "The transfer failed"
    }

    /** True when the address in [target] is worth dialling at all. */
    fun dialable(target: PairTarget): Boolean =
        target.host.isNotBlank() && target.port in 1..65535 && target.code.isNotBlank()

    /** The address a device should advertise, for the "type this instead" line
     *  under the QR when the broadcast path is unavailable. */
    fun localAddressHint(): String = PairProtocol.localIpv4().orEmpty()
}
