package com.hikari.app.manga

import com.hikari.app.HikariApp
import com.hikari.app.data.Episode
import com.hikari.app.data.Logs
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.net.Http
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject

/** One page of a downloaded manga chapter: the local file plus the source
 *  URLs it came from (the reader keeps showing the source page identity). */
data class OfflinePage(
    val file: String,
    val pageUrl: String,
    val url: String,
)

enum class DlStatus { IDLE, QUEUED, RUNNING, DONE, FAILED }

/** The download state of one chapter (missing = IDLE). */
data class DlState(
    val status: DlStatus = DlStatus.IDLE,
    val done: Int = 0,
    val total: Int = 0,
    val error: String? = null,
)

/**
 * Offline manga/novel chapters: download one chapter or every chapter of a
 * title, then read them with no network (the readers check here first).
 *
 * Layout under `filesDir/offline/m/<manga>/<chapter>/`: a `meta.json`
 * manifest (page file names + their source urls, or the novel text parts)
 * plus the page image files. State is a process-wide map + a revision
 * counter the screens collect; completion is re-derived from the manifests
 * on every launch, so downloads survive restarts.
 */
object MangaOffline {

    private fun root(): File = File(HikariApp.instance.filesDir, "offline/m").apply { mkdirs() }

    private fun mangaDir(mangaKey: String): File =
        File(root(), mangaKey.hashCode().toUInt().toString(16))

    private fun chapterDir(mangaKey: String, chapterUrl: String): File =
        File(mangaDir(mangaKey), chapterUrl.hashCode().toUInt().toString(16))

    private fun metaFile(mangaKey: String, chapterUrl: String): File =
        File(chapterDir(mangaKey, chapterUrl), "meta.json")

    private val states = ConcurrentHashMap<String, DlState>()

    private val _rev = MutableStateFlow(0L)
    fun rev(): StateFlow<Long> = _rev.asStateFlow()

    private fun ping() {
        _rev.value += 1
    }

    private fun stateKey(mangaKey: String, chapterUrl: String) = "$mangaKey\n$chapterUrl"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val chapterJobs = ConcurrentHashMap<String, Job>()
    private val mangaJobs = ConcurrentHashMap<String, Job>()

    /** Downloaded chapter urls per manga, scanned once from disk then kept
     *  coherent by the write/delete paths below — so row state never does
     *  file IO during composition, no matter how long the chapter list is. */
    private val offlineSets = ConcurrentHashMap<String, MutableSet<String>>()

    private fun offlineSet(mangaKey: String): MutableSet<String> =
        offlineSets.getOrPut(mangaKey) {
            val set = ConcurrentHashMap.newKeySet<String>()
            runCatching {
                mangaDir(mangaKey).listFiles()?.forEach { dir ->
                    if (!dir.isDirectory) return@forEach
                    val cu = runCatching {
                        JSONObject(File(dir, "meta.json").readText()).optString("chapterUrl")
                    }.getOrNull().orEmpty()
                    if (cu.isNotBlank()) set.add(cu)
                }
            }
            set
        }

    /** Same novel rule as the reader router: text chapters travel as text. */
    fun isNovel(providerId: String): Boolean =
        MangaExtensionManager.isNovelId(providerId) ||
            com.hikari.app.sora.SoraMangaProvider.isSoraNovelId(providerId) ||
            com.hikari.app.lnreader.LnReaderProvider.isLnReaderId(providerId) ||
            com.hikari.app.kotatsu.fork.ForkRegistry.isForkNovelId(providerId)

    // ---- Queries ----

    /** The stored manifest for a downloaded chapter, null when not on disk. */
    private fun readMeta(mangaKey: String, chapterUrl: String): JSONObject? {
        val f = metaFile(mangaKey, chapterUrl)
        if (!f.isFile) return null
        return runCatching { JSONObject(f.readText()) }.getOrNull()
    }

    fun isDownloaded(mangaKey: String, chapterUrl: String): Boolean {
        states[stateKey(mangaKey, chapterUrl)]?.let { return it.status == DlStatus.DONE }
        val ok = offlineSet(mangaKey).contains(chapterUrl)
        if (ok) states[stateKey(mangaKey, chapterUrl)] = DlState(DlStatus.DONE)
        return ok
    }

    fun stateFor(mangaKey: String, chapterUrl: String): DlState {
        states[stateKey(mangaKey, chapterUrl)]?.let { return it }
        return if (isDownloaded(mangaKey, chapterUrl)) {
            states[stateKey(mangaKey, chapterUrl)] ?: DlState(DlStatus.DONE)
        } else DlState()
    }

    fun downloadedCount(mangaKey: String, chapterUrls: List<String>): Int =
        chapterUrls.count { isDownloaded(mangaKey, it) }

    fun mangaBytes(mangaKey: String): Long = runCatching {
        mangaDir(mangaKey).walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    /** Downloaded image pages in order, null when the chapter is not offline. */
    fun offlinePages(mangaKey: String, chapterUrl: String): List<OfflinePage>? {
        val m = readMeta(mangaKey, chapterUrl) ?: return null
        if (m.optString("type") != "image") return null
        val arr = m.optJSONArray("pages") ?: return null
        if (arr.length() == 0) return null
        val dir = chapterDir(mangaKey, chapterUrl)
        val out = ArrayList<OfflinePage>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val fn = o.optString("file")
            if (fn.isBlank()) continue
            val f = File(dir, fn)
            if (!f.isFile) return null
            out += OfflinePage(
                file = f.absolutePath,
                pageUrl = o.optString("pageUrl"),
                url = o.optString("url"),
            )
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /** Downloaded novel text parts in order, null when not offline. */
    fun offlineText(mangaKey: String, chapterUrl: String): List<String>? {
        val m = readMeta(mangaKey, chapterUrl) ?: return null
        if (m.optString("type") != "text") return null
        val arr = m.optJSONArray("parts") ?: return null
        if (arr.length() == 0) return null
        return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }
    }

    // ---- Downloads ----

    fun downloadChapter(
        providerId: String,
        mangaUrl: String,
        title: String,
        posterUrl: String?,
        chapter: MangaChapter,
    ) {
        val mangaKey = "$providerId|$mangaUrl"
        val key = stateKey(mangaKey, chapter.url)
        if (isDownloaded(mangaKey, chapter.url)) return
        chapterJobs[key]?.takeIf { it.isActive } ?: run {
            states[key] = DlState(DlStatus.QUEUED)
            ping()
            chapterJobs[key] = scope.launch {
                try {
                    runChapter(providerId, mangaUrl, title, posterUrl, chapter)
                } finally {
                    chapterJobs.remove(key)
                }
            }
        }
    }

    fun downloadAll(
        providerId: String,
        mangaUrl: String,
        title: String,
        posterUrl: String?,
        chapters: List<MangaChapter>,
    ) {
        val mangaKey = "$providerId|$mangaUrl"
        mangaJobs[mangaKey]?.takeIf { it.isActive } ?: run {
            mangaJobs[mangaKey] = scope.launch {
                try {
                    val pending = chapters.filter { !isDownloaded(mangaKey, it.url) }
                    pending.forEachIndexed { i, c ->
                        ensureActive()
                        states[stateKey(mangaKey, c.url)] = DlState(DlStatus.QUEUED, 0, 0)
                        ping()
                        runChapter(providerId, mangaUrl, title, posterUrl, c)
                        ensureActive()
                        Logs.log("Manga", "offline [$providerId] ${title.take(40)} all ${i + 1}/${pending.size}")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Logs.log("Manga", "offline [$providerId] download-all failed: ${t.message?.take(120)}")
                } finally {
                    mangaJobs.remove(mangaKey)
                    ping()
                }
            }
        }
    }

    fun cancelChapter(mangaKey: String, chapterUrl: String) {
        val key = stateKey(mangaKey, chapterUrl)
        chapterJobs.remove(key)?.cancel()
        if (states[key]?.status == DlStatus.RUNNING || states[key]?.status == DlStatus.QUEUED) {
            states[key] = DlState()
            ping()
        }
    }

    fun cancelManga(mangaKey: String) {
        mangaJobs.remove(mangaKey)?.cancel()
        for ((k, j) in chapterJobs) {
            if (k.startsWith(mangaKey + "\n")) {
                j.cancel()
                chapterJobs.remove(k)
                if (states[k]?.status == DlStatus.RUNNING || states[k]?.status == DlStatus.QUEUED) {
                    states[k] = DlState()
                }
            }
        }
        ping()
    }

    fun deleteChapter(mangaKey: String, chapterUrl: String) {
        val key = stateKey(mangaKey, chapterUrl)
        chapterJobs.remove(key)?.cancel()
        runCatching { chapterDir(mangaKey, chapterUrl).deleteRecursively() }
        offlineSets[mangaKey]?.remove(chapterUrl)
        states[key] = DlState()
        ping()
    }

    fun deleteManga(mangaKey: String) {
        cancelManga(mangaKey)
        runCatching { mangaDir(mangaKey).deleteRecursively() }
        offlineSets.remove(mangaKey)
        for (k in states.keys) {
            if (k.startsWith(mangaKey + "\n")) states[k] = DlState()
        }
        ping()
    }

    private suspend fun runChapter(
        providerId: String,
        mangaUrl: String,
        title: String,
        posterUrl: String?,
        chapter: MangaChapter,
    ) {
        val mangaKey = "$providerId|$mangaUrl"
        val key = stateKey(mangaKey, chapter.url)
        val novel = isNovel(providerId)
        val item = MediaItem(
            providerId = providerId,
            id = mangaUrl,
            title = title.ifBlank { mangaUrl },
            type = MediaType.SERIES,
            posterUrl = posterUrl?.takeIf { it.isNotBlank() },
            rawType = if (novel) "novel" else "manga",
        )
        val ep = Episode(number = 1, id = chapter.url, name = chapter.name, image = null, season = 1)
        states[key] = DlState(DlStatus.RUNNING, 0, 0)
        ping()
        try {
            val p = HikariApp.instance.providers.byId(providerId)
                ?: throw Exception("engine not installed")
            val sources = runCatching { p.getStreams(item, ep) }.getOrThrow()
            if (novel) {
                val parts = sources.mapNotNull { it.text.takeIf { t -> t.isNotBlank() } }
                if (parts.isEmpty()) throw Exception("chapter text unavailable")
                writeTextMeta(mangaKey, chapter, title, parts)
                offlineSet(mangaKey).add(chapter.url)
                states[key] = DlState(DlStatus.DONE, parts.size, parts.size)
            } else {
                val pages = sources.filter { it.url.isNotBlank() && it.url.startsWith("http") }
                    .distinctBy { it.url.substringBefore("?").substringBefore("#").trim().lowercase() }
                if (pages.isEmpty()) throw Exception("no readable pages")
                states[key] = DlState(DlStatus.RUNNING, 0, pages.size)
                ping()
                downloadPages(mangaKey, chapter, title, pages, key)
            }
            Logs.log("Manga", "offline [$providerId] ${title.take(40)} saved ${chapter.label}")
        } catch (e: CancellationException) {
            states[key] = DlState()
            ping()
            throw e
        } catch (t: Throwable) {
            states[key] = DlState(DlStatus.FAILED, error = t.message?.take(160))
            Logs.log("Manga", "offline [$providerId] ${chapter.label} failed: ${t.message?.take(160)}")
            ping()
        }
    }

    private suspend fun downloadPages(
        mangaKey: String,
        chapter: MangaChapter,
        title: String,
        pages: List<com.hikari.app.data.StreamSource>,
        key: String,
    ) = coroutineScope {
        val dir = chapterDir(mangaKey, chapter.url)
        val sem = Semaphore(3)
        val failed = java.util.concurrent.atomic.AtomicInteger(0)
        val files = pages.mapIndexed { i, s ->
            async {
                sem.withPermit {
                    ensureActive()
                    val data = downloadImage(s.url, s.headers)
                    if (data == null) {
                        failed.incrementAndGet()
                        val cur = states[key]
                        if (cur != null) {
                            states[key] = cur.copy(done = cur.done + 1)
                            ping()
                        }
                        return@withPermit null
                    }
                    val ext = extForContentType(null, s.url, data)
                    val fn = i.toString().padStart(4, '0') + "." + ext
                    runCatching { File(dir, fn).apply { parentFile?.mkdirs() }.writeBytes(data) }
                        .getOrNull() ?: return@withPermit null
                    val cur = states[key]
                    if (cur != null) {
                        states[key] = cur.copy(done = cur.done + 1)
                        ping()
                    }
                    Triple(fn, s.pageUrl, s.url)
                }
            }
        }.awaitAll().filterNotNull().sortedBy { it.first }
        if (files.isEmpty()) throw Exception("no pages could be saved")
        if (failed.get() > 0) {
            Logs.log("Manga", "offline ${title.take(40)} ${chapter.label}: ${failed.get()}/${pages.size} pages failed")
        }
        val arr = JSONArray()
        files.forEach { (fn, pageUrl, url) ->
            arr.put(JSONObject().apply {
                put("file", fn)
                put("pageUrl", pageUrl)
                put("url", url)
            })
        }
        runCatching {
            File(dir, "meta.json").apply { parentFile?.mkdirs() }.writeText(
                JSONObject().apply {
                    put("v", 1)
                    put("type", "image")
                    put("title", title)
                    put("chapter", chapter.label)
                    put("chapterUrl", chapter.url)
                    put("pages", arr)
                    put("at", System.currentTimeMillis())
                }.toString()
            )
        }.getOrThrow()
        states[key] = DlState(DlStatus.DONE, files.size, pages.size)
        offlineSet(mangaKey).add(chapter.url)
        ping()
    }

    private fun writeTextMeta(
        mangaKey: String,
        chapter: MangaChapter,
        title: String,
        parts: List<String>,
    ) {
        val arr = JSONArray()
        parts.forEach { arr.put(it) }
        File(chapterDir(mangaKey, chapter.url), "meta.json").apply { parentFile?.mkdirs() }.writeText(
            JSONObject().apply {
                put("v", 1)
                put("type", "text")
                put("title", title)
                put("chapter", chapter.label)
                put("chapterUrl", chapter.url)
                put("parts", arr)
                put("at", System.currentTimeMillis())
            }.toString()
        )
    }

    private fun downloadImage(url: String, headers: Map<String, String>): ByteArray? {
        repeat(2) {
            runCatching {
                Http.get(url, headers).use { resp ->
                    if (!resp.isSuccessful) return@runCatching null
                    val body = resp.body?.bytes() ?: return@runCatching null
                    if (isPlausibleImage(body)) return body
                    return@runCatching null
                }
            }.getOrNull()?.let { return it }
        }
        return null
    }

    /** Extension from the reply's content type, else the URL, else the bytes. */
    fun extForContentType(contentType: String?, url: String, bytes: ByteArray? = null): String {
        val c = contentType.orEmpty().lowercase()
        when {
            "png" in c -> return "png"
            "webp" in c -> return "webp"
            "gif" in c -> return "gif"
            "avif" in c -> return "avif"
            "bmp" in c -> return "bmp"
            "jpeg" in c || "jpg" in c -> return "jpg"
        }
        val tail = url.substringBefore("?").substringBefore("#").substringAfterLast('.', "").lowercase()
        if (tail in listOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp")) {
            return if (tail == "jpeg") "jpg" else tail
        }
        if (bytes != null && bytes.size >= 12) {
            if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) return "png"
            if (bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte()) return "gif"
            if (bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte()) return "webp"
        }
        return "jpg"
    }

    private fun isPlausibleImage(b: ByteArray): Boolean {
        if (b.size < 512) return false
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) return true // JPEG
        if (b.size >= 4 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte()) return true // PNG
        if (b.size >= 3 && b[0] == 0x47.toByte() && b[1] == 0x49.toByte()) return true // GIF
        if (b.size >= 12 && b[0] == 'R'.code.toByte() && b[8] == 'W'.code.toByte()) return true // WEBP
        if (b.size >= 2 && b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte()) return true // BMP
        // Unknown magic but a real payload (some CDNs serve raw streams).
        return b.size > 4096
    }
}
