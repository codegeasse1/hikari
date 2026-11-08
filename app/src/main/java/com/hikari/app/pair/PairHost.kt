package com.hikari.app.pair

import android.net.wifi.WifiManager
import android.os.Build
import com.hikari.app.BuildConfig
import com.hikari.app.HikariApp
import com.hikari.app.data.BackupManager
import com.hikari.app.data.Logs
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

class PairHost(private val app: HikariApp) {

    /** What the hosting screen shows: where to connect and the code to type. */
    data class Session(
        val host: String,
        val port: Int,
        val code: String,
        val payloadBytes: Long,
    ) {
        val reachable: Boolean get() = host.isNotBlank()

        /** `192.168.1.5:8787`, what the guest would type if the QR cannot be
         *  used and the broadcast cannot be heard. */
        val label: String get() = "$host:$port"
    }

    @Volatile
    private var server: Server? = null

    @Volatile
    private var beacon: Thread? = null

    @Volatile
    private var beaconSocket: DatagramSocket? = null

    @Volatile
    private var multicastLock: WifiManager.MulticastLock? = null

    /** How many times the bundle has been handed over, and to whom — the ONLY
     *  feedback a host has that the other device actually connected. */
    @Volatile
    var served: Int = 0
        private set

    @Volatile
    var lastGuest: String = ""
        private set

    val running: Boolean get() = server != null

    @Volatile
    private var payloadFile: java.io.File? = null

    suspend fun start(): Session = withContext(Dispatchers.IO) {
        stop()
        val file = java.io.File(app.cacheDir, "pair/bundle.json")
        val bytes = BackupManager.exportToFile(app, file)
        payloadFile = file
        val opened = openServer(file, bytes)
        val session = Session(
            host = PairProtocol.localIpv4().orEmpty(),
            port = opened.listenPort,
            code = opened.code,
            payloadBytes = bytes,
        )
        Logs.log(
            "Pair",
            "hosting on ${session.host}:${session.port} · ${bytes / 1024} KB · " +
                "code ${session.code}",
        )
        startBeacon(session.port, session.code)
        session
    }

    private fun openServer(payload: java.io.File, payloadBytes: Long): Server {
        val code = PairProtocol.newCode()
        var last: IOException? = null
        for (offset in 0 until 20) {
            val candidate = Server(PairProtocol.DEFAULT_PORT + offset, code, payload, payloadBytes) { guest ->
                served++
                lastGuest = guest
                Logs.log("Pair", "setup sent to $guest")
            }
            try {
                candidate.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                server = candidate
                return candidate
            } catch (e: IOException) {
                last = e
                runCatching { candidate.stop() }
            }
        }
        throw last ?: IOException("no free port")
    }

    private fun startBeacon(port: Int, code: String) {
        runCatching {
            val wifi = app.applicationContext
                .getSystemService(android.content.Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("hikari-pair")?.apply {
                setReferenceCounted(false)
                runCatching { acquire() }
            }
        }
        val thread = Thread {
            val socket = runCatching {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 700
                    bind(InetSocketAddress(PairProtocol.UDP_PORT))
                }
            }.getOrNull()
            if (socket == null) {
                Logs.log("Pair", "beacon could not bind udp/${PairProtocol.UDP_PORT}")
            } else {
                beaconSocket = socket
                Logs.log("Pair", "beacon listening on udp/${PairProtocol.UDP_PORT}")
                val buffer = ByteArray(96)
                while (!Thread.currentThread().isInterrupted && server != null) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    } catch (_: Exception) {
                        break
                    }
                    val text = runCatching {
                        String(packet.data, packet.offset, packet.length, Charsets.UTF_8).trim()
                    }.getOrNull().orEmpty()
                    if (text != PairProtocol.PROBE_PREFIX + code) continue
                    val reply = (PairProtocol.REPLY_PREFIX + port).toByteArray(Charsets.UTF_8)
                    runCatching {
                        socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                    }
                }
                runCatching { socket.close() }
            }
        }
        thread.isDaemon = true
        thread.name = "hikari-pair-beacon"
        thread.start()
        beacon = thread
    }

    /** Stops listening. Idempotent, and safe to call from any thread — the
     *  screen calls it on leave, on rotate and on stop. */
    fun stop() {
        server?.let { runCatching { it.stop() } }
        server = null
        runCatching { payloadFile?.delete() }
        payloadFile = null
        beacon?.interrupt()
        beacon = null
        runCatching { beaconSocket?.close() }
        beaconSocket = null
        multicastLock?.let { runCatching { if (it.isHeld) it.release() } }
        multicastLock = null
    }

    /** The device name the QR advertises, so a guest can say WHAT it found. */
    fun deviceName(): String = runCatching {
        val maker = Build.MANUFACTURER.orEmpty()
        val model = Build.MODEL.orEmpty()
        when {
            model.isBlank() -> "Hikari"
            maker.isBlank() || model.lowercase().startsWith(maker.lowercase()) -> model
            else -> "$maker $model"
        }
    }.getOrDefault("Hikari")

    fun appVersion(): String = BuildConfig.VERSION_NAME

    private class Server(
        /** The port this server was asked for, which is the one the QR and the
         *  beacon advertise. Kept as our own property rather than read back from
         *  NanoHTTPD: before `start()` its own listener has no port at all. */
        val listenPort: Int,
        /** The pairing code this server accepts. Read by [PairHost.start] to
         *  build the QR payload and the beacon filter, so it is not private. */
        val code: String,
        private val payload: java.io.File,
        private val payloadBytes: Long,
        private val onServed: (String) -> Unit,
    ) : NanoHTTPD(listenPort) {

        override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
            val guest = session.remoteIpAddress.orEmpty()
            return when (session.uri.trimEnd('/').ifBlank { "/" }) {
                "/bundle" -> {
                    val token = session.parameters["t"]?.firstOrNull().orEmpty()
                    if (token != code) {
                        // No detail for the caller: an unauthorised request learns
                        // that it was refused and nothing else.
                        Logs.log("Pair", "refused $guest (wrong code)")
                        NanoHTTPD.newFixedLengthResponse(
                            NanoHTTPD.Response.Status.FORBIDDEN,
                            "text/plain",
                            "wrong pairing code",
                        )
                    } else {
                        onServed(guest)
                        NanoHTTPD.newFixedLengthResponse(
                            NanoHTTPD.Response.Status.OK,
                            "application/json",
                            payload.inputStream(),
                            payloadBytes,
                        )
                    }
                }
                else -> NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    "text/html",
                    "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>" +
                        "<body style='font:16px system-ui;background:#111;color:#eee;padding:24px'>" +
                        "<h2>Hikari pairing</h2><p>This device is ready to send its setup. " +
                        "Open Hikari on the other device &#8594; Settings &#8594; Backup &amp; Restore " +
                        "&#8594; Pair &amp; sync, and enter this device's code.</p>" +
                        "<p style='color:#888'>Everything travels over your Wi-Fi only. " +
                        "Nothing is uploaded anywhere.</p>",
                )
            }
        }
    }
}
