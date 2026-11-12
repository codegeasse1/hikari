package com.hikari.app.ui

import android.util.Base64
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.hikari.app.HikariApp
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

object PosterLoader {

    private const val DATA_IMAGE = "data:image/"

    /** Prefix of the tiny token [tokenize] returns for a base64 `data:` poster —
     *  the real bytes live in the on-disk store under the token's hash, so a
     *  catalog can hold thousands of posters without blowing the heap while the
     *  grid still renders them ([model] resolves the token back to bytes). */
    private const val CACHE_TOKEN = "data:cache/"

    private const val DISK_BUDGET_BYTES = 400L * 1024 * 1024

    // Keep Coil request identity stable for persisted poster models across recompositions.
    private val fileRequests = ConcurrentHashMap<String, ImageRequest>()
    private const val FILE_REQUESTS_MAX = 1024

    private val pending = ConcurrentHashMap<String, MutableState<Long>>()

    /** Upper bound on [pending] entries, so a catalog of undecodable payloads
     *  can't grow the map without limit. */
    private const val PENDING_MAX = 4096

    /** Bounded worker pool: decodes are heavy (multi-MB buffers) and running
     *  them two-at-a-time keeps the memory churn low and predictable instead of
     *  a hundred parallel allocations. */
    private val prepExecutor = Executors.newFixedThreadPool(8) { r ->
        Thread(r, "hikari-poster-prep").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    /** Cooldown per poster HASH (never the full URI): [schedulePrep] runs at
     *  most once per cooldown per poster, and a catalog of undecodable
     *  payloads can no longer pin hundreds of multi-hundred-KB strings here —
     *  the strings that used to live in this map were themselves a leak. */
    private val lastAttempt = ConcurrentHashMap<String, Long>()
    private const val RETRY_COOLDOWN_MS = 5_000L
    private const val LAST_ATTEMPT_MAX = 2048

    private val writesSincePrune = AtomicLong(0L)

    private val storeDir: File? by lazy {
        runCatching { File(HikariApp.instance.filesDir, "poster_store").apply { mkdirs() } }
            .getOrNull()
    }

    /** The store the previous build used. Read as a fallback so a poster already
     *  decoded by an older version isn't a blank cell right after the update. */
    private val legacyDir: File? by lazy {
        runCatching { File(HikariApp.instance.cacheDir, "hikari_poster_cache") }.getOrNull()
    }

    fun model(url: String?): Any? {
        // A provider that left no art is a blank cell by construction — count
        // it, so the Logs screen can tell "empty-handed" apart from "failed".
        if (url.isNullOrBlank()) {
            com.hikari.app.data.Logs.traceSampledEvery(
                "Poster", "empty-poster", 50L,
            ) { "blank posterUrl from provider (no art to fetch)" }
            return null
        }
        val u = normalize(url) ?: return null
        if (!u.startsWith(DATA_IMAGE) && !u.startsWith(CACHE_TOKEN)) {
            // Plain remote poster: the fetch census. If these lines exist but
            // no Image/refused lines follow, Coil either never fired or got a
            // 200 with undecodable bytes (see the coil-error lines).
            com.hikari.app.data.Logs.traceSampledEvery(
                "Poster", "http-poster", 200L,
            ) { "fetch host=${com.hikari.app.data.Logs.safeHost(u)}" }
            return u
        }

        val name = hashOf(u)
        existingFile(name)?.let { return request(it, name) }

        if (u.startsWith(DATA_IMAGE)) {
            awaitingRevision(name)
            schedulePrep(u, name)
        }
        return null
    }

    /** This poster's revision counter, read during composition so the caller is
     *  recomposed when its decode finishes (and not before). */
    private fun awaitingRevision(name: String): Long {
        pending[name]?.let { return it.value }
        if (pending.size >= PENDING_MAX) return 0L
        return pending.getOrPut(name) { mutableStateOf(0L) }.value
    }

    fun coverModel(url: String?, providerId: String?): Any? {
        val single = model(url)
        val pid = providerId?.takeIf { it.isNotBlank() } ?: return single
        if (!com.hikari.app.manga.MangaExtensionManager.isMangaProviderId(pid)) return single
        val u = normalize(url) ?: return single
        if (u.startsWith(DATA_IMAGE) || u.startsWith(CACHE_TOKEN)) return single
        return com.hikari.app.reader.source.ExtensionCoverRef(u, pid)
    }

    /** Poster for a grid/row cell: the item's own poster, or its backdrop when
     *  the provider left the poster empty (some catalogs only fill the
     *  landscape `image`, and an empty model is a blank cell). */
    fun model(poster: String?, backdrop: String?): Any? =
        model(poster?.takeIf { it.isNotBlank() } ?: backdrop)


    /** Drop decoded still frames (see relieveHeapPressure in HikariApp): the
     *  cells that drew them re-fetch on next scroll. */
    fun clearMemory() {
        stills.clear()
        stillPending.clear()
    }

    /** First frames already fetched this session, keyed by URL. */
    private val stills = ConcurrentHashMap<String, android.graphics.Bitmap>()

    /** Waiting cells per still URL, so a frame that lands repaints exactly the
     *  tile that asked for it (same idea as [pending]). */
    private val stillPending = ConcurrentHashMap<String, MutableState<Long>>()

    /** Bound on the still cache and on the waiting map: covers are a handful per
     *  screen, and this must not grow with a catalog of thousands. */
    private const val STILLS_MAX = 64

    /** Longest edge a decoded still is allowed to have. A still is drawn in the
     *  same cell as any other cover, so anything past a couple of hundred pixels
     *  is memory that buys nothing (see [decodeStill]). */
    private const val STILL_MAX_PX = 512

    private val stillInFlight = ConcurrentHashMap.newKeySet<String>()
    private val stillLastAttempt = ConcurrentHashMap<String, Long>()

    fun stillModel(url: String?): Any? {
        val u = normalize(url) ?: return null
        stills[u]?.let { return it }
        if (stillPending.size >= STILLS_MAX) return null
        val state = stillPending.getOrPut(u) { mutableStateOf(0L) }
        // Read during composition: this cell repaints when ITS frame lands.
        state.value
        scheduleStill(u)
        return null
    }

    private fun scheduleStill(u: String) {
        val now = System.currentTimeMillis()
        stillLastAttempt[u]?.let { if (now - it < RETRY_COOLDOWN_MS) return }
        if (!stillInFlight.add(u)) return
        stillLastAttempt[u] = now
        prepExecutor.execute {
            try {
                val bytes = com.hikari.app.net.Http.getBytes(u)
                val bmp = bytes?.let { decodeStill(it) }
                if (bmp != null) {
                    if (stills.size >= STILLS_MAX) {
                        stills.keys.firstOrNull()?.let { stills.remove(it) }
                    }
                    stills[u] = bmp
                    stillPending.remove(u)?.let { st -> st.value = st.value + 1L }
                }
            } finally {
                stillInFlight.remove(u)
            }
        }
    }

    private fun decodeStill(bytes: ByteArray): android.graphics.Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= STILL_MAX_PX ||
            bounds.outHeight / (sample * 2) >= STILL_MAX_PX
        ) {
            sample *= 2
        }
        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        }
        return runCatching {
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }.getOrNull()
    }

    fun retryModel(model: Any?, attempt: Int): Any? {
        if (model == null || attempt <= 0) return model
        val builder = if (model is ImageRequest) model.newBuilder()
        else ImageRequest.Builder(HikariApp.instance).data(model)
        return builder.setParameter("hikariRetry", attempt).build()
    }

    /** Global "try dead posters once more" signal, read during composition by
     *  [PosterImage][com.hikari.app.ui.components.PosterImage]. A tile that
     *  exhausted its retries stays blank for its model — bumping this gives
     *  every dead tile one more round without touching tiles that loaded.
     *  Bumped once per finished search scan (see SearchSessionScope): the
     *  first attempts fail while DNS/TLS is still warming up, and by the time
     *  the pipes are hot the tiles have already given up. */
    val retryEpoch = mutableStateOf(0L)

    fun bumpRetryEpoch() {
        retryEpoch.value = retryEpoch.value + 1L
    }

    /** Same as [retryModel], but also stamps the epoch: the request object
     *  changes when the epoch does, so Coil actually re-runs dead requests
     *  instead of handing back the same failed state. Loaded tiles resolve
     *  from the memory cache, so the bump costs them nothing visible. */
    fun retryModel(model: Any?, attempt: Int, epoch: Long): Any? {
        if (model == null) return null
        val builder = if (model is ImageRequest) model.newBuilder()
        else ImageRequest.Builder(HikariApp.instance).data(model)
        builder.setParameter("hikariEpoch", epoch)
        if (attempt > 0) builder.setParameter("hikariRetry", attempt)
        return builder.build()
    }

    fun haloModel(model: Any?, blurDp: Int): Any? {
        if (model == null || blurDp <= 0) return null
        val px = (34 - blurDp).coerceIn(6, 34)
        val builder = if (model is ImageRequest) model.newBuilder()
        else ImageRequest.Builder(HikariApp.instance).data(model)
        val source = (model as? ImageRequest)?.memoryCacheKey ?: model.toString()
        builder.memoryCacheKey("hikari-halo:$px:$source")
        builder.size(px, px)
        builder.setParameter("hikariHalo", px)
        return builder.build()
    }

    fun tokenize(url: String?): String? {
        val u = normalize(url) ?: return null
        if (!u.startsWith(DATA_IMAGE)) return u

        val name = fnv1a(u)
        if (existingFile(name) != null) return CACHE_TOKEN + name

        val bytes = decodeDataUri(u)
        if (bytes == null) {
            // Undecodable payload (encrypted-but-not-decrypted provider art,
            // truncated base64, unknown image magic): the grid shows a blank
            // tile for this poster, so record WHY, not just that.
            com.hikari.app.data.Logs.traceSampledEvery(
                "Poster", "tokenize-undecodable", 50L,
            ) { "undecodable data-poster len=${u.length} hash=$name sig=${sigHead(u)}" }
            return poolGiant(u)
        }
        val file = destFile(name)
        if (file == null) {
            com.hikari.app.data.Logs.trace("Poster", "no store dir for hash=$name")
            return poolGiant(u)
        }
        val wrote = runCatching {
            file.writeBytes(bytes)
            file.length() > 0
        }.getOrDefault(false)
        if (!wrote) {
            com.hikari.app.data.Logs.traceSampledEvery(
                "Poster", "tokenize-write-fail", 50L,
            ) { "could not persist decoded poster hash=$name bytes=${bytes.size}" }
            return poolGiant(u)
        }
        pruneIfNeeded()
        // Success census: without it a screen of clean decodes looks exactly
        // like a screen that never tried (zero lines either way).
        com.hikari.app.data.Logs.traceSampledEvery(
            "Poster", "tokenize-ok", 200L,
        ) { "decoded + cached hash=$name bytes=${bytes.size}" }
        return CACHE_TOKEN + name
    }

    /** Identical giant data-URIs (same poster in 2-3 rows) share one instance
     *  instead of pinning 2-3 copies of the same 1-2MB string. Bounded so an
     *  undecodable catalog can't grow it without limit. */
    private val giantPool = ConcurrentHashMap<String, String>()
    private const val GIANT_POOL_MAX = 512
    private fun poolGiant(u: String): String {
        if (u.length < 8192) return u
        giantPool[u]?.let { return it }
        if (giantPool.size >= GIANT_POOL_MAX) giantPool.clear()
        giantPool[u] = u
        return u
    }

    /** First bytes of a data-URI payload's alphabet for failure lines: the
     *  base64 header is predictable per image kind (never secrets), so a few
     *  chars say a lot (JPEG vs PNG vs encrypted garbage). Never the bytes. */
    private fun sigHead(dataUri: String): String {
        val comma = dataUri.indexOf(',')
        if (comma <= 0) return "<no-comma len=${dataUri.length}>"
        val payload = dataUri.substring(comma + 1).take(16)
        return payload.ifBlank { "<empty-payload>" }
    }
    private fun hashOf(u: String): String =
        if (u.startsWith(CACHE_TOKEN)) u.substring(CACHE_TOKEN.length) else fnv1a(u)

    /** Existing bytes for [name], in the durable store or (for posters decoded
     *  by an older build) the legacy cache directory. */
    private fun existingFile(name: String): File? {
        storeDir?.let { f -> File(f, name).takeIf { it.length() > 0 } }?.let { return it }
        legacyDir?.let { f -> File(f, name).takeIf { it.length() > 0 } }?.let { return it }
        return null
    }

    /** Where new bytes for [name] are written (may not exist yet). */
    private fun destFile(name: String): File? = storeDir?.let { File(it, name) }

    /** Builds the Coil request over an already-persisted poster. The explicit
     *  memory-cache key matters: the model is a File, so Coil would otherwise key
     *  the decoded bitmap by file path — fine — but two different posters that
     *  happen to hash-collide would share one bitmap. */
    private fun request(file: File, name: String): ImageRequest {
        fileRequests[name]?.let { return it }
        val built = ImageRequest.Builder(HikariApp.instance)
            .data(file)
            .memoryCacheKey("hikari-poster:" + name)
            // The bytes are already persisted by this object; Coil keeping a
            // second copy of every poster in its own disk cache is pure waste.
            .diskCachePolicy(CachePolicy.DISABLED)
            .build()
        val stable = fileRequests.putIfAbsent(name, built) ?: built
        if (fileRequests.size > FILE_REQUESTS_MAX) {
            fileRequests.keys.firstOrNull { it != name }?.let { fileRequests.remove(it) }
        }
        return stable
    }

    private fun schedulePrep(u: String, name: String) {
        val now = System.currentTimeMillis()
        // Failure backoff ONLY (keyed by hash, never by the full data URI): a
        // hash lands in [lastAttempt] when its previous decode FAILED. The
        // first attempt of a new poster is never delayed — the old code parked
        // every new poster behind the same cooldown, so a fast scroll showed
        // only the first decodes and blank cells behind them.
        lastAttempt[name]?.let { if (now - it < RETRY_COOLDOWN_MS) return }
        if (!inFlight.add(name)) return
        prepExecutor.execute {
            try {
                val bytes = decodeDataUri(u)
                val file = destFile(name)
                if (bytes != null && bytes.isNotEmpty() && file != null) {
                    runCatching { file.writeBytes(bytes) }
                    if (file.length() > 0) {
                        writesSincePrune.incrementAndGet()
                        // Decoded: no cooldown needed anymore.
                        lastAttempt.remove(name)
                        // Repaint exactly the cells that were waiting on THIS
                        // poster (see [pending]).
                        pending.remove(name)?.let { st -> st.value = st.value + 1L }
                    } else {
                        prepFailed(name)
                        com.hikari.app.data.Logs.traceSampledEvery(
                            "Poster", "prep-write-fail", 50L,
                        ) { "async persist failed hash=$name bytes=${bytes.size}" }
                    }
                } else {
                    val why = when {
                        bytes == null -> "undecodable sig=${sigHead(u)}"
                        bytes.isEmpty() -> "empty-bytes"
                        else -> "no-store-dir"
                    }
                    prepFailed(name)
                    com.hikari.app.data.Logs.traceSampledEvery(
                        "Poster", "prep-undecodable", 50L,
                    ) { "async decode failed ($why) len=${u.length} hash=$name" }
                }
            } finally {
                inFlight.remove(name)
            }
        }
    }

    /** Records a failed decode so the next attempt backs off (see
     *  [RETRY_COOLDOWN_MS]); successes never touch [lastAttempt]. */
    private fun prepFailed(name: String) {
        if (lastAttempt.size >= LAST_ATTEMPT_MAX) lastAttempt.clear()
        lastAttempt[name] = System.currentTimeMillis()
    }

    /** A host-looking path with no scheme ('pic.example.com/x.jpg') — plugins
     *  emit these now and then, and Coil has no fetcher for a scheme-less URI,
     *  so the poster renders as an empty box. Same for protocol-relative '//'. */
    private val HOST_LIKE = Regex("^[A-Za-z0-9][A-Za-z0-9.-]*\\.[A-Za-z]{2,}(/.*)?$")

    /** Repairs the poster URLs plugins hand us: trims, and gives a missing
     *  scheme an https one. Everything else (http(s), data:, cache tokens,
     *  content://) passes through untouched. */
    private fun normalize(url: String?): String? {
        val u = url?.trim() ?: return null
        if (u.isEmpty()) return null
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        if (u.startsWith(DATA_IMAGE) || u.startsWith(CACHE_TOKEN)) return u
        if (u.startsWith("//")) return "https:$u"
        if (HOST_LIKE.matches(u)) return "https://$u"
        return u
    }

    private fun decodeDataUri(url: String): ByteArray? {
        val comma = url.indexOf(',')
        if (comma <= 0) return null
        val payload = url.substring(comma + 1)
            .filterNot { it == '\n' || it == '\r' || it == ' ' || it == '\t' }
        if (payload.isEmpty()) return null
        // Pick the alphabet up front: standard (MRDS/51cg emit NO_WRAP) vs
        // URL-safe. Trying all four variants means up to three wasted
        // full-size decodes per undecodable poster.
        val variants = if (payload.any { it == '-' || it == '_' }) intArrayOf(
            Base64.URL_SAFE,
            Base64.URL_SAFE or Base64.NO_PADDING,
            Base64.DEFAULT,
        ) else intArrayOf(
            Base64.DEFAULT,
            Base64.DEFAULT or Base64.NO_WRAP,
        )
        for (flags in variants) {
            val bytes = runCatching { Base64.decode(payload, flags) }.getOrNull() ?: continue
            if (looksLikeImage(bytes)) return bytes
        }
        return null
    }

    /** True when [b] starts with a signature we can hand to BitmapFactory: JPEG,
     *  PNG, GIF, WEBP, BMP or an ISO-BMFF (AVIF/HEIC) `ftyp` box. */
    private fun looksLikeImage(b: ByteArray): Boolean {
        if (b.size < 16) return false
        fun at(i: Int) = b[i].toInt() and 0xFF
        if (at(0) == 0xFF && at(1) == 0xD8) return true
        if (at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47) return true
        if (at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46) return true
        if (at(0) == 0x42 && at(1) == 0x4D) return true
        if (at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
            at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50
        ) return true
        if (at(4) == 0x66 && at(5) == 0x74 && at(6) == 0x79 && at(7) == 0x70) return true
        return false
    }

    /** 32-bit FNV-1a over the URI bytes → stable store filename. */
    private fun fnv1a(s: String): String {
        var h = 0x811c9dc5.toInt()
        for (b in s.encodeToByteArray()) {
            h = (h xor (b.toInt() and 0xFF))
            h *= 0x01000193
        }
        return (h.toUInt()).toString(16) + "_" + s.length
    }

    private fun pruneIfNeeded() {
        if (writesSincePrune.incrementAndGet() % 64L != 0L) return
        val dir = storeDir ?: return
        val files = runCatching { dir.listFiles() }.getOrNull() ?: return
        var total = 0L
        for (f in files) total += f.length()
        if (total <= DISK_BUDGET_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= DISK_BUDGET_BYTES) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }
}
