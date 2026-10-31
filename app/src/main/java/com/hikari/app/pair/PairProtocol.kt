package com.hikari.app.pair

import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Locale

/**
 * The wire format of Hikari's device pairing, in ONE place.
 *
 * The whole feature is deliberately tiny and deliberately ours: two Hikari
 * installs on the same Wi-Fi find each other with a six-character code, and the
 * device that was set up first sends the other one its complete setup (settings,
 * installed extensions, repos, history — everything `BackupManager` puts in a
 * backup file). There is no account, no server, no cloud and no upload: the
 * bytes go straight from one phone to the other over the local network, which is
 * why the user is asked to be on the same Wi-Fi and why nothing here needs a
 * privacy note.
 *
 * What a "code" is:
 *
 *  * six characters from an alphabet with no `0`/`O` and no `1`/`I`/`L`, so it
 *    can be read off a television screen and typed on a phone without a mistake
 *    (the two devices are rarely both phones — one is usually a TV, and a TV has
 *    no camera, so the typed code is the path that has to be pleasant);
 *  * generated with [SecureRandom], and it is the ONLY thing standing between a
 *    neighbour's phone and the user's whole setup: the HTTP server that carries
 *    the data refuses every request whose token is not this code, and the UDP
 *    beacon answers only a probe that already carries it. A 6-character code
 *    from a 32-letter alphabet is 2^30 combinations, the pairing window is
 *    minutes long and one guess costs a round trip, so the code is the
 *    authentication — obfuscating it in the QR would add nothing;
 *  * valid only while the pairing screen is open. Nothing is listening on the
 *    LAN after that (see [PairHost.stop]).
 */
object PairProtocol {

    /** Protocol marker in a QR payload: `hikari://pair?...`. */
    const val URI_SCHEME = "hikari"
    const val URI_HOST = "pair"

    /**
     * The UDP port the beacon listens on, fixed so a guest can find a host
     * WITHOUT knowing its address (which is the point: the guest may be a TV with
     * no keyboard, and its user certainly does not want to read an IP off one
     * screen and type it on another).
     */
    const val UDP_PORT = 47821

    /** Probe/reply markers. The probe carries the code, so only the device that
     *  is showing that code answers — two Hikari installs hosting at once on the
     *  same Wi-Fi are otherwise indistinguishable. */
    const val PROBE_PREFIX = "HIKARI-PAIR?"
    const val REPLY_PREFIX = "HIKARI-PAIR!"

    const val CODE_LENGTH = 6

    /** The port the host's own HTTP server would like. It is only a preference:
     *  [PairHost] walks up from here when something else holds it, and the real
     *  one travels in the QR payload and in the beacon's reply. */
    const val DEFAULT_PORT = 8787

    /** No `0`, `O`, `1`, `I` or `L`: the pairs that make a typed code wrong. */
    private const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"

    private val random = SecureRandom()

    /** A fresh code. See the class note for what it is and is not protecting. */
    fun newCode(): String {
        val sb = StringBuilder(CODE_LENGTH)
        repeat(CODE_LENGTH) { sb.append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        return sb.toString()
    }

    /**
     * What the user typed, as the code it must have meant: upper case, letters
     * and digits only, and folded to the alphabet's own conventions (`O`/`0`
     * become nothing — they are not in the alphabet, so a code containing one was
     * never ours — while the letters that ARE in it are kept as typed).
     */
    fun normalizeCode(raw: String): String =
        raw.uppercase(Locale.US).filter { ALPHABET.indexOf(it) >= 0 }.take(CODE_LENGTH)

    /** True when [code] is a complete, plausible code (see [CODE_LENGTH]). */
    fun looksComplete(code: String): Boolean = code.length == CODE_LENGTH

    /**
     * The QR payload. It carries the host's address AND its port so a scan needs
     * no discovery at all, plus the device name and the host's app version so the
     * guest can say WHAT it found before it touches anything ("Pixel 7 · Hikari
     * 0.10.20" rather than "192.168.1.5"), and the code itself.
     *
     * Short keys on purpose: a QR this small scans from further away and in worse
     * light, and every character in it is one more module on a screen the other
     * device has to photograph.
     */
    fun uri(host: String, port: Int, code: String, deviceName: String, version: String): String {
        val params = StringBuilder()
        params.append("h=").append(host)
        params.append("&p=").append(port)
        params.append("&c=").append(code)
        if (deviceName.isNotBlank()) params.append("&n=").append(urlEncode(deviceName))
        if (version.isNotBlank()) params.append("&v=").append(urlEncode(version))
        return "$URI_SCHEME://$URI_HOST?$params"
    }

    /**
     * Everything the guest can be handed, turned into an address+code: the QR
     * payload itself, the same query string without the scheme, a
     * `http://…?t=CODE` URL, `host[:port][:code]`, `host:port code`, or a bare
     * code (which means "find the host for me", see [PairClient.discover]).
     *
     * Forgiving by design — this is the landing place for a pasted string, a
     * scanned QR and a typo, and the only thing worth refusing is a string with
     * no complete code in it. An address that is missing is not a failure: it
     * just means discovery has to run.
     */
    fun parse(text: String): PairTarget? {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return null
        val lower = trimmed.lowercase(Locale.US)

        // 1. The QR payload, with or without the scheme.
        if (lower.startsWith(URI_SCHEME) || lower.startsWith("h=") || lower.startsWith("?h=")) {
            val params = parseQuery(trimmed.substringAfter('?', trimmed))
            val code = normalizeCode(params["c"].orEmpty())
            if (code.isNotBlank()) {
                val host = params["h"]?.trim().orEmpty().takeIf { isPlausibleHost(it) }.orEmpty()
                val port = params["p"]?.trim()?.toIntOrNull() ?: DEFAULT_PORT
                return PairTarget(host, port, code, params["n"].orEmpty())
            }
        }

        // 2. The host's own page URL (`http://192.168.1.5:8787/?t=CODE`), which
        //    is what a camera app that does not read QR codes as links would
        //    still offer to open.
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            val noScheme = trimmed.substringAfter("://")
            val host = noScheme.substringBefore('/').substringBefore(':')
            val port = noScheme.substringBefore('/').substringAfter(':', "").toIntOrNull()
                ?: DEFAULT_PORT
            val token = normalizeCode(parseQuery(noScheme.substringAfter('?', ""))["t"].orEmpty())
            if (host.isNotBlank() && token.isNotBlank()) return PairTarget(host, port, token, "")
        }

        // 3. `host[:port][:code]`, optionally followed by `@`/space + the code.
        val splitAt = trimmed.indexOfFirst { it == '@' || it == ' ' || it == '\t' }
        val head = (if (splitAt >= 0) trimmed.substring(0, splitAt) else trimmed).trim()
        val tail = (if (splitAt >= 0) trimmed.substring(splitAt + 1) else "").trim()
        if (isPlausibleHost(head.substringBefore(':'))) {
            val parts = head.split(':').map { it.trim() }.filter { it.isNotEmpty() }
            val host = parts.first()
            var port = DEFAULT_PORT
            var portTaken = false
            var code = ""
            for (part in parts.drop(1)) {
                val number = part.toIntOrNull()
                if (number != null && !portTaken) {
                    port = number
                    portTaken = true
                } else {
                    code = normalizeCode(part)
                }
            }
            if (tail.isNotBlank()) code = normalizeCode(tail)
            if (looksComplete(code)) return PairTarget(host, port, code, "")
        }

        // 4. Just the code — the case the guest's own field holds. The address
        //    comes from discovery.
        val justCode = normalizeCode(trimmed)
        if (looksComplete(justCode)) return PairTarget("", 0, justCode, "")
        return null
    }

    /**
     * This device's own IPv4 address on the local network, which is what the QR
     * carries and what the guest dials.
     *
     * Wi-Fi and Ethernet first, then anything else that is up and not a tunnel:
     * a phone usually also has a cellular and a VPN interface, and handing out
     * `10.8.0.2` (a VPN) or a carrier address as "the address to type" is the
     * kind of help that costs an hour.
     */
    fun localIpv4(): String? {
        val candidates = ArrayList<Pair<String, String>>()
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback || nif.isPointToPoint) continue
                for (address in nif.inetAddresses) {
                    if (address !is Inet4Address) continue
                    if (address.isLoopbackAddress || address.isLinkLocalAddress) continue
                    val text = address.hostAddress.orEmpty()
                    if (text.isBlank()) continue
                    candidates.add(nif.name.orEmpty() to text)
                }
            }
        }
        val preferred = listOf("wlan", "eth", "ap", "swlan", "wifi")
        return candidates.firstOrNull { (name, _) ->
            preferred.any { name.startsWith(it, ignoreCase = true) }
        }?.second ?: candidates.firstOrNull()?.second
    }

    /** `%20` for spaces, and only for the two characters a device name can
     *  realistically carry that would break the query. */
    private fun urlEncode(value: String): String =
        value.replace("%", "%25").replace("&", "%26").replace("=", "%3D").replace(" ", "%20")

    private fun urlDecode(value: String): String =
        value.replace("%20", " ").replace("%3D", "=").replace("%26", "&").replace("%25", "%")

    /** `a=1&b=2` (with or without a leading `?`) as a map. */
    private fun parseQuery(query: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (part in query.removePrefix("?").split('&')) {
            if (part.isBlank()) continue
            val key = part.substringBefore('=').trim()
            val value = part.substringAfter('=', "")
            if (key.isNotBlank()) out[key] = urlDecode(value)
        }
        return out
    }

    /** What an address is used for: nothing here needs a DNS name, and a device
     *  could not resolve one for the other anyway. */
    fun isPlausibleHost(host: String): Boolean =
        host.isNotBlank() && (host.count { it == '.' } == 3 || host.endsWith(".local"))

    /** The broadcast address a probe is sent to. Every modern AP forwards it to
     *  the whole subnet; a guest that gets no answer falls back to the typed
     *  address from the QR. */
    fun broadcastAddress(): InetAddress? =
        runCatching { InetAddress.getByName("255.255.255.255") }.getOrNull()

    /** A socket that can send and receive the beacon, or null when the platform
     *  refuses one (no network permission, no network, a firewall). */
    fun udpSocket(): DatagramSocket? = runCatching {
        DatagramSocket().apply {
            soTimeout = 500
            broadcast = true
            reuseAddress = true
        }
    }.getOrNull()
}

/**
 * Where a pairing is aimed: the address to dial, the port, and the code that is
 * both the name of the session and the only thing that authorises it.
 *
 * [host] may be BLANK, which is not an error — it means "this code came from a
 * typed code, find the device yourself" (see [PairClient.discover]). [deviceName]
 * is whatever the QR carried and is used to say what was found.
 */
data class PairTarget(
    val host: String,
    val port: Int,
    val code: String,
    val deviceName: String = "",
) {
    /** `192.168.1.5:8787`, or empty while the address is still unknown. */
    val label: String get() = if (host.isBlank()) "" else "$host:$port"
}
