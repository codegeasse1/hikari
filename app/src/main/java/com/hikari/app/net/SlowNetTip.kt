package com.hikari.app.net

import android.content.Context
import android.net.ConnectivityManager
import com.hikari.app.HikariApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

object SlowNetTip {

    /** Below this measured speed the connection genuinely looks slow. */
    private const val SLOW_KBPS = 400

    /** Android's own estimate at/above this vetoes a slow probe reading (the
     *  probe host, not the connection, was the bottleneck). */
    private const val HEALTHY_KBPS = 2_000

    private const val PROBE_BYTES = 128 * 1024
    private const val PROBE_TIMEOUT_MS = 3_000
    private const val MIN_PROBE_BYTES = 8 * 1024

    /** No first frame this long after the tap = struggling. */
    private const val STRUGGLE_MS = 12_000L

    /** Servers failing back-to-back before the tip counts as proven. */
    private const val STRUGGLE_FAILS = 2

    /** How long "Not now" silences the tip. */
    private const val COOLDOWN_MS = 48L * 60 * 60 * 1000

    /** Two unrelated hosts: whichever answers fastest decides, so one host
     *  being throttled/blocked in a region can't produce a false "slow". */
    private val PROBES = listOf(
        "https://speed.cloudflare.com/__down?bytes=$PROBE_BYTES",
        "https://cachefly.cachefly.net/100kb.test",
    )

    private val _suggestion = MutableStateFlow<String?>(null)

    /** Non-null while the player should be showing the tip (the value is the
     *  evidence that produced it, for logging/debugging). */
    val suggestion: StateFlow<String?> = _suggestion

    private var app: HikariApp? = null
    private var probeJob: Job? = null
    private var struggleJob: Job? = null

    @Volatile
    private var askedThisSession = false

    @Volatile
    private var sawFrame = false

    private var fails = 0

    fun init(app: HikariApp) {
        this.app = app
    }

    /**
     * Called once per play, the moment the player opens (before any server is
     * known). Resets the per-session state and starts both lines of evidence.
     */
    fun onPlaybackStart() {
        val a = app ?: return
        sawFrame = false
        fails = 0
        askedThisSession = false
        probeJob?.cancel()
        struggleJob?.cancel()
        _suggestion.value = null

        val scope = a.appScope

        // 1. Measured: only ever runs when the tip is even allowed to fire, so
        //    it costs nothing for users who have it off / have it on already.
        probeJob = scope.launch {
            if (!eligible(a)) return@launch
            if (linkKbps(a) >= HEALTHY_KBPS) return@launch
            val kbps = measureBest() ?: return@launch
            if (kbps >= SLOW_KBPS) return@launch
            suggest("probe $kbps kbps")
        }

        struggleJob = scope.launch {
            delay(STRUGGLE_MS)
            if (sawFrame) return@launch
            suggest("no frame after ${STRUGGLE_MS / 1000}s")
        }
    }

    /** Real video is on screen — retract any pending/visible "slow" verdict. */
    fun onFirstFrame() {
        sawFrame = true
        struggleJob?.cancel()
        struggleJob = null
        _suggestion.value = null
    }

    /** A server died on the current play (server N of the list). */
    fun onServerFailed() {
        fails++
        if (fails >= STRUGGLE_FAILS) suggest("$fails servers failed")
    }

    /** The player dismissed the dialog; keep the flow in sync. */
    fun clear() {
        _suggestion.value = null
    }

    /** Player is gone — stop measuring and drop any pending verdict. */
    fun onPlaybackEnd() {
        probeJob?.cancel()
        probeJob = null
        struggleJob?.cancel()
        struggleJob = null
        _suggestion.value = null
    }

    private fun suggest(reason: String) {
        val a = app ?: return
        val scope: CoroutineScope = a.appScope
        scope.launch {
            if (sawFrame || !eligible(a)) return@launch
            askedThisSession = true
            _suggestion.value = reason
            android.util.Log.i("HikariSlowTip", "suggesting slow mode: $reason")
        }
    }

    /** Everything the user has told us about whether they want to be asked. */
    private suspend fun eligible(a: HikariApp): Boolean {
        if (askedThisSession) return false
        // Live flag first: catches the toggle being flipped before the store
        // write lands, and skips a DataStore read on the common path.
        if (NetTuning.slowConnection) return false
        val store = a.store
        if (runCatching { store.slowConnection() }.getOrDefault(false)) return false
        if (!runCatching { store.slowTipEnabled() }.getOrDefault(true)) return false
        if (runCatching { store.slowTipDontAsk() }.getOrDefault(false)) return false
        val last = runCatching { store.slowTipLastDismiss() }.getOrDefault(0L)
        if (last > 0L && System.currentTimeMillis() - last < COOLDOWN_MS) return false
        return true
    }

    /** Best kbps seen across the probe hosts, or null when none could measure. */
    private suspend fun measureBest(): Int? = withContext(Dispatchers.IO) {
        var best = -1
        for (probe in PROBES) {
            val kbps = runCatching { measure(probe) }.getOrNull() ?: continue
            if (kbps >= SLOW_KBPS) return@withContext kbps
            if (kbps > best) best = kbps
        }
        if (best < 0) null else best
    }

    /** Downloads a slice and converts it to kbps; null = no usable reading. */
    private fun measure(url: String): Int? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = PROBE_TIMEOUT_MS
            readTimeout = PROBE_TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", Http.UA)
        }
        try {
            val t0 = System.currentTimeMillis()
            if (conn.responseCode !in 200..299) return null
            var total = 0L
            val buf = ByteArray(16 * 1024)
            conn.inputStream.use { ins: InputStream ->
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total >= PROBE_BYTES) break
                    // Read past the probe window with enough data in hand: the
                    // connection is answering, we already know the rate.
                    if (System.currentTimeMillis() - t0 > PROBE_TIMEOUT_MS &&
                        total >= MIN_PROBE_BYTES
                    ) break
                }
            }
            if (total < MIN_PROBE_BYTES) return null
            val elapsed = (System.currentTimeMillis() - t0).coerceAtLeast(200L)
            return ((total / 1024L) * 1000L / elapsed).toInt()
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** Android's own downstream estimate in kbps; -1 when it doesn't know. */
    private fun linkKbps(context: Context): Int = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching -1
        val net = cm.activeNetwork ?: return@runCatching -1
        val caps = cm.getNetworkCapabilities(net) ?: return@runCatching -1
        caps.linkDownstreamBandwidthKbps
    }.getOrDefault(-1)
}
