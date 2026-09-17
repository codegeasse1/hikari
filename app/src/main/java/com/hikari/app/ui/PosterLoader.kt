package com.hikari.app.ui

import android.util.Base64
import androidx.compose.runtime.mutableStateOf
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.hikari.app.HikariApp
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Coil model helper. MRDS and 51CG encrypt their poster images (pic.xustgq.cn)
 * with a fixed AES key, so their plugins download+decrypt them into base64
 * `data:` URIs. Coil renders `ByteArray`/`File` models natively but a raw
 * data-URI string is opaque to it (and a ~1MB blob in the nav route crashes the
 * NavController). Decode once per URL and persist the bytes on disk (keyed by a
 * hash of the URI — the decryption is deterministic, so the same URI always
 * yields the same bytes), so the home catalog's posters are instant on the next
 * app open. Http(s) posters pass through untouched — Coil's own disk cache (see
 * HikariApp) covers those.
 *
 * Two hard-won invariants, both from posters coming back BLANK:
 *
 * 1. A poster is never thrown away. The item's URL is the only copy of the
 *    artwork — once [tokenize] replaces it with a token, a failed decode or a
 *    deleted file means that cell is blank *forever* (the feed would have to be
 *    re-fetched). So a payload that can't be decoded right now keeps its
 *    original URI, and [model] retries the decode off the main thread.
 * 2. Nothing heavy happens on the main thread. Decoding a full-size poster is a
 *    multi-MB allocation; doing that for a hundred cells inside composition is
 *    what made whole rows flicker out under memory pressure. [model] hands back
 *    null (the cell shows its placeholder icon) and queues the work; the
 *    per-poster state in [pending] then recomposes exactly the cell that asked
 *    for that poster.
 */
object PosterLoader {

    private const val DATA_IMAGE = "data:image/"

    /** Prefix of the tiny token [tokenize] returns for a base64 `data:` poster —
     *  the real bytes live in the on-disk store under the token's hash, so a
     *  catalog can hold thousands of posters without blowing the heap while the
     *  grid still renders them ([model] resolves the token back to bytes). */
    private const val CACHE_TOKEN = "data:cache/"

    /** Total size the on-disk poster store may occupy. Big enough for a full
     *  multi-provider home feed (hundreds of covers) and for the same covers to
     *  still be there days later — a poster whose bytes were pruned is one we
     *  cannot re-create (the provider's data URI is gone), so this is generous
     *  on purpose. Pruned oldest-first. */
    private const val DISK_BUDGET_BYTES = 400L * 1024 * 1024

    /**
     * One observable counter per poster whose bytes are still being decoded, so
     * a cell recomposes when *its* poster lands and not when any other one does.
     * A single shared revision (the previous design) meant that every finished
     * decode — and a home feed decodes hundreds — recomposed every cell on
     * screen: a recomposition storm that ran exactly while the user scrolled.
     */
    private val pending = ConcurrentHashMap<String, MutableState<Long>>()

    /** Upper bound on [pending] entries, so a catalog of undecodable payloads
     *  can't grow the map without limit. */
    private const val PENDING_MAX = 4096

    /** Bounded worker pool: decodes are heavy (multi-MB buffers) and running
     *  them two-at-a-time keeps the memory churn low and predictable instead of
     *  a hundred parallel allocations. */
    private val prepExecutor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "hikari-poster-prep").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val lastAttempt = ConcurrentHashMap<String, Long>()
    private const val RETRY_COOLDOWN_MS = 5_000L

    private val writesSincePrune = AtomicLong(0L)

    /**
     * Where the decoded poster bytes live: filesDir, NOT cacheDir. The OS
     * deletes cacheDir whenever storage runs low, and because the base64 URI is
     * replaced by a token, a deleted cache file means that row goes blank until
     * the feed is re-fetched — exactly the "some images just don't load" report.
     * filesDir is only cleared when the user wipes the app's data.
     */
    private val storeDir: File? by lazy {
        runCatching { File(HikariApp.instance.filesDir, "poster_store").apply { mkdirs() } }
            .getOrNull()
    }

    /** The store the previous build used. Read as a fallback so a poster already
     *  decoded by an older version isn't a blank cell right after the update. */
    private val legacyDir: File? by lazy {
        runCatching { File(HikariApp.instance.cacheDir, "hikari_poster_cache") }.getOrNull()
    }

    /**
     * Coil model for [url]: a plain String for http(s) posters (Coil fetches
     * those itself), or an [ImageRequest] over the stored bytes for a poster we
     * had to decrypt ourselves. Null while those bytes are still being decoded —
     * the caller should render a placeholder and will be recomposed when they
     * land (see [revision]).
     */
    fun model(url: String?): Any? {
        val u = normalize(url) ?: return null
        if (!u.startsWith(DATA_IMAGE) && !u.startsWith(CACHE_TOKEN)) return u

        val name = hashOf(u)
        existingFile(name)?.let { return request(it, name) }

        if (u.startsWith(DATA_IMAGE)) {
            // Deliberately read inside composition: this poster's own state is
            // what repaints this cell the moment its bytes materialise. Reading
            // a state shared by all posters here is what used to make one decode
            // repaint the whole screen.
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

    /** Poster for a grid/row cell: the item's own poster, or its backdrop when
     *  the provider left the poster empty (some catalogs only fill the
     *  landscape `image`, and an empty model is a blank cell). */
    fun model(poster: String?, backdrop: String?): Any? =
        model(poster?.takeIf { it.isNotBlank() } ?: backdrop)

    /**
     * Re-issues a failed request on attempt N (0 = the original model). Coil
     * does not retry on its own, so a transient 5xx / dropped connection / busy
     * decoder left the cell blank until the user scrolled it out of view and
     * back. `setParameter` changes the request's structural equality (which is
     * what [coil.compose.AsyncImage] restarts on) without changing its data.
     */
    fun retryModel(model: Any?, attempt: Int): Any? {
        if (model == null || attempt <= 0) return model
        val builder = if (model is ImageRequest) model.newBuilder()
        else ImageRequest.Builder(HikariApp.instance).data(model)
        return builder.setParameter("hikariRetry", attempt).build()
    }

    /**
     * Replaces a base64 `data:` poster with a tiny stable token whose bytes are
     * persisted in the on-disk store, so a giant catalog can hold thousands of
     * posters in memory without an OutOfMemoryError while the grid still shows
     * them ([model] resolves the token back to the bytes). Regular http(s) URLs
     * pass through unchanged.
     *
     * Never returns null for a data URI: when the payload can't be decoded right
     * now (non-standard base64, or an OutOfMemoryError under the load of a whole
     * catalog) the URI itself is returned so the item keeps its artwork and
     * [model] retries the decode later. Dropping it here is how an entire row
     * used to end up permanently blank.
     */
    fun tokenize(url: String?): String? {
        val u = normalize(url) ?: return null
        if (!u.startsWith(DATA_IMAGE)) return u

        val name = fnv1a(u)
        if (existingFile(name) != null) return CACHE_TOKEN + name

        val bytes = decodeDataUri(u) ?: return u
        val file = destFile(name) ?: return u
        val wrote = runCatching {
            file.writeBytes(bytes)
            file.length() > 0
        }.getOrDefault(false)
        if (!wrote) return u
        pruneIfNeeded()
        return CACHE_TOKEN + name
    }

    /** The filename a poster's bytes are stored under: the URI hash carried in a
     *  [CACHE_TOKEN], or the hash of the URI itself. */
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
    private fun request(file: File, name: String): ImageRequest =
        ImageRequest.Builder(HikariApp.instance)
            .data(file)
            .memoryCacheKey("hikari-poster:" + name)
            // The bytes are already persisted by this object; Coil keeping a
            // second copy of every poster in its own disk cache is pure waste.
            .diskCachePolicy(CachePolicy.DISABLED)
            .build()

    /**
     * Decodes a [DATA_IMAGE] URI on a background thread and persists the bytes,
     * then bumps [revision] so the cells that are waiting for it repaint. A
     * no-op while an identical decode is in flight, and rate-limited after a
     * failure so a broken payload can't spin the pool on every recomposition.
     */
    private fun schedulePrep(u: String, name: String) {
        val now = System.currentTimeMillis()
        lastAttempt[u]?.let { if (now - it < RETRY_COOLDOWN_MS) return }
        if (!inFlight.add(u)) return
        lastAttempt[u] = now
        prepExecutor.execute {
            try {
                val bytes = decodeDataUri(u)
                val file = destFile(name)
                if (bytes != null && bytes.isNotEmpty() && file != null) {
                    runCatching { file.writeBytes(bytes) }
                    if (file.length() > 0) {
                        writesSincePrune.incrementAndGet()
                        // Repaint exactly the cells that were waiting on THIS
                        // poster (see [pending]).
                        pending.remove(name)?.let { st -> st.value = st.value + 1L }
                    }
                }
            } finally {
                inFlight.remove(u)
            }
        }
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

    /**
     * Decodes the base64 payload of a `data:` URI (null when nothing decodable
     * comes out).
     *
     * Deliberately stubborn, because a silently-wrong decode is worse than a
     * failure: Android's decoder with the default flags STOPS at the first
     * character outside the standard alphabet, so a URL-safe payload (`-`/`_`)
     * decodes to truncated garbage that Coil then refuses — a whole provider's
     * rows of blank cells, with no error anywhere. Spaces (which sites wrap
     * base64 with) are rejected outright. So: strip whitespace, try each
     * alphabet, and only accept an answer whose magic bytes say it really is an
     * image.
     */
    private fun decodeDataUri(url: String): ByteArray? {
        val comma = url.indexOf(',')
        if (comma <= 0) return null
        val payload = url.substring(comma + 1)
            .filterNot { it == '\n' || it == '\r' || it == ' ' || it == '\t' }
        if (payload.isEmpty()) return null
        val variants = intArrayOf(
            Base64.DEFAULT,
            Base64.URL_SAFE,
            Base64.URL_SAFE or Base64.NO_PADDING,
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

    /**
     * Keeps the store under [DISK_BUDGET_BYTES] by deleting the least recently
     * written files. Checked every so often rather than on every write (the size
     * is a directory walk), and never touches anything outside [storeDir].
     */
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
