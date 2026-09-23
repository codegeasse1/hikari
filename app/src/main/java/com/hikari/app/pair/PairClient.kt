package com.hikari.app.pair

import com.hikari.app.data.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.util.concurrent.TimeUnit

/**
 * The receiving end of device pairing: a device that was just installed (or
 * reset) pulls another Hikari's setup over the Wi-Fi.
 *
 * Two steps, in this order:
 *
 *  1. [discover] — only when the guest was given a code and no address. A UDP
 *     broadcast carries the code, the host that owns it answers with its port,
 *     and the reply's source address IS the host's address (no parsing of
 *     "where am I" out of anything). This is the step that makes a television
 *     usable as a guest: no camera to scan the QR with, no keyboard to type an
 *     IP on.
 *  2. [download] — a plain HTTP GET of `/bundle?t=<code>`, which is the backup
 *     file byte for byte, and the caller hands it to [com.hikari.app.data.BackupManager.restore]
 *     — the same code path a file picked from Downloads takes, with the same path
 *     validation and the same live-state refresh. The network part of this
 *     feature therefore cannot damage anything the file path could not.
 *
 * Nothing here throws a raw network exception at the UI: a failure is a
 * [PairException] with a sentence a user can act on ("No other device answered"),
 * because "SocketTimeoutException" is not a thing to show a person.
 */
object PairClient {

    /** A hard ceiling on what a guest will accept. A real setup is single-digit
     *  MB (extensions plus notes); the cap is there so a device on the LAN that
     *  is NOT a Hikari (or a hostile one that guessed a code) cannot make this
     *  app allocate without limit. */
    private const val MAX_BUNDLE_BYTES = 256L * 1024L * 1024L

    class PairException(message: String) : Exception(message)

    /**
     * Finds the device that owns [code] on this network, or null after
     * [timeoutMs]. Never throws: "no answer" is the normal outcome on a Wi-Fi
     * that filters broadcasts, and the caller's next move is the manual address
     * the QR carries.
     */
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

    /**
     * Fetches the other device's setup. Throws [PairException] with a sentence
     * for every failure the user can cause (wrong code, nothing listening, the
     * two devices on different networks).
     */
    suspend fun download(target: PairTarget): ByteArray = withContext(Dispatchers.IO) {
        if (target.host.isBlank()) throw PairException("No address to connect to")
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
                    // The only refusal the server has: the token did not match the
                    // code it is showing. Worth its own sentence, because the fix
                    // (re-read the code) is different from every other failure.
                    throw PairException("That code was refused — check it on the other device")
                }
                if (!response.isSuccessful) {
                    throw PairException("The other device answered with ${response.code}")
                }
                val body = response.body
                val length = body.contentLength()
                if (length > MAX_BUNDLE_BYTES) throw PairException("That transfer is too large")
                val bytes = body.bytes()
                if (bytes.size.toLong() > MAX_BUNDLE_BYTES) {
                    throw PairException("That transfer is too large")
                }
                Logs.log(
                    "Pair",
                    "received ${bytes.size / 1024} KB from ${target.label} in " +
                        "${System.currentTimeMillis() - started}ms",
                )
                bytes
            }
        } catch (e: PairException) {
            throw e
        } catch (e: Exception) {
            Logs.logError("Pair", "download failed from ${target.label}", e)
            throw PairException(explain(e))
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
