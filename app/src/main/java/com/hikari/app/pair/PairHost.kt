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
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * The sending end of device pairing: this device (already set up) hands its whole
 * install to another one on the same Wi-Fi.
 *
 * Two things listen while it runs, and only while the pairing screen is open:
 *
 *  * an HTTP server that serves the backup bytes on `/bundle`, and a small page
 *    on `/` for anyone who opens the address in a browser (the user checking that
 *    the two devices can see each other, or a support chat);
 *  * a UDP beacon that answers `HIKARI-PAIR?<code>` with its own port, which is
 *    how a guest that was given nothing but a typed code finds this device's
 *    address without anybody reading IP numbers off a screen.
 *
 * Both are gated on the code (see [PairProtocol]): the page is the only thing
 * that answers without it, and the page deliberately does NOT print the code —
 * otherwise anyone on the Wi-Fi could read the secret off the server that is
 * guarding it.
 *
 * The payload is [BackupManager.export] — the same bytes as "Back up Hikari
 * data", built ONCE at [start] and then served from memory. Building it per
 * request would walk the whole extension directory again for a file that cannot
 * have changed (the user is looking at the pairing screen), and a phone that is
 * being dragged through a 30 MB export on every retry is how a pairing times
 * out. [served] counts the handovers so the screen can say that it worked.
 */
class PairHost(private val app: HikariApp) {

    /** What the hosting screen shows: where to connect and the code to type. */
    data class Session(
        val host: String,
        val port: Int,
        val code: String,
        val payloadBytes: Int,
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

    /**
     * Builds the payload, opens a port and starts listening. Throws only when
     * there is nothing to serve or no port to serve it on; every other failure
     * ends as a null/degraded [Session] the screen can describe.
     */
    suspend fun start(): Session = withContext(Dispatchers.IO) {
        stop()
        val payload = BackupManager.export(app)
        val opened = openServer(payload)
        val session = Session(
            host = PairProtocol.localIpv4().orEmpty(),
            port = opened.listenPort,
            code = opened.code,
            payloadBytes = payload.size,
        )
        Logs.log(
            "Pair",
            "hosting on ${session.host}:${session.port} · ${payload.size / 1024} KB · " +
                "code ${session.code}",
        )
        startBeacon(session.port, session.code)
        session
    }

    /**
     * The HTTP server, on the first free port at or above [PairProtocol.DEFAULT_PORT].
     *
     * The walk matters on a real phone: 8787 can be taken by anything (another
     * Hikari install hosting, a dev tool), and the old behaviour of "the port is
     * busy, so pairing is broken" is indistinguishable from "pairing is broken"
     * for the user. The port that actually opened travels in the QR and in the
     * beacon's reply, so the guest never has to know about the walk.
     */
    private fun openServer(payload: ByteArray): Server {
        val code = PairProtocol.newCode()
        var last: IOException? = null
        for (offset in 0 until 20) {
            val candidate = Server(PairProtocol.DEFAULT_PORT + offset, code, payload) { guest ->
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

    /**
     * The UDP beacon. Answers a probe that carries the right code with its own
     * port, so `255.255.255.255` reaches us on any interface without us having to
     * enumerate them.
     *
     * A multicast lock is held for as long as it listens: Wi-Fi power saving
     * filters broadcast packets almost immediately when nothing holds one, and a
     * beacon that misses the probe is exactly the "it did not find the other
     * device" the user cannot diagnose from the outside. It is released on
     * [stop], so the radio's behaviour is untouched once pairing is over.
     */
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
                // No beacon is survivable: the QR still carries the address, and
                // the guest can always type it. Say so in the log rather than
                // failing a pairing that is otherwise fine.
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

    /**
     * The pairing HTTP server. NanoHTTPD is already on the classpath (an Aniyomi
     * extension API names it), so this is a handful of lines rather than a new
     * dependency and a new way to be wrong about sockets.
     */
    private class Server(
        /** The port this server was asked for, which is the one the QR and the
         *  beacon advertise. Kept as our own property rather than read back from
         *  NanoHTTPD: before `start()` its own listener has no port at all. */
        val listenPort: Int,
        /** The pairing code this server accepts. Read by [PairHost.start] to
         *  build the QR payload and the beacon filter, so it is not private. */
        val code: String,
        private val payload: ByteArray,
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
                            ByteArrayInputStream(payload),
                            payload.size.toLong(),
                        )
                    }
                }
                // The page is for a human who typed the address into a browser.
                // It carries NO code: this is the one thing that answers without
                // one, so it must not be the place the code leaks from.
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
