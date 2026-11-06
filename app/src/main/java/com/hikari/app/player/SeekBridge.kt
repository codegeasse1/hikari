package com.hikari.app.player

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import com.hikari.app.data.Logs
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Localhost range bridge for progressive servers that ignore HTTP Range (the
 * "skip restarts from 00:00" hosts): ExoPlayer asks for bytes N- and the host
 * answers from byte 0, so every seek replays from the start.
 *
 * The bridge downloads the file once into a temp file and answers ExoPlayer's
 * range requests from it, so skip/scrub behave normally. Only one session is
 * ever kept and its file is deleted on the next source and on [stop] (called
 * from the player's onDestroy), so app size never grows — at most one temp
 * file, only while that video plays.
 */
object SeekBridge {
    private data class Session(
        val id: String,
        val remote: String,
        val file: File,
        val client: OkHttpClient,
        @Volatile var length: Long = -1L,
        @Volatile var mime: String = "video/mp4",
        @Volatile var done: Boolean = false,
        @Volatile var failed: Boolean = false,
    )

    private val sessions = ConcurrentHashMap<String, Session>()
    private var server: ServerSocket? = null
    private var port: Int = 0
    private val lock = Any()
    // No own HTTP client on purpose: the fetch runs through the caller's playback client
    // (shared cookies, extractor headers, video interceptors), so hosts answer the bridge
    // exactly like the app. Temp files live ONLY in cacheDir/seekbridge — manual downloads
    // live in the downloads folder and are never listed, read or deleted here.

    /**
     * Bridge URL for [remote], starting the download on first call. Same
     * remote returns the same URL (no second download). Null when the bridge
     * cannot start — the caller then plays the direct URL as before.
     */
    fun open(
        remote: String,
        headers: Map<String, String>,
        ua: String,
        cacheDir: File,
        client: OkHttpClient,
    ): String? {
        return try {
            synchronized(lock) {
                sessions.values.firstOrNull { it.remote == remote }?.let {
                    if (!it.failed) return "http://127.0.0.1:$port/play?id=${it.id}"
                    closeSession(it.id)
                }
                // One live session: a new video drops the previous temp file.
                sessions.keys.toList().forEach(::closeSession)
                ensureServer()
                val dir = File(cacheDir, "seekbridge").apply { mkdirs() }
                val id = System.nanoTime().toString(36)
                dir.listFiles()?.forEach { f ->
                    if (f.isFile && f.name.startsWith("sb_") && sessions.values.none { it.file == f }) {
                        runCatching { f.delete() }
                    }
                }
                val s = Session(id, remote, File(dir, "sb_$id.bin"), client)
                Logs.log("Player", "seek-bridge open")
                sessions[id] = s
                thread(name = "seekbridge-fetch", isDaemon = true) { fetch(s, headers, ua) }
                "http://127.0.0.1:$port/play?id=$id"
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Bridge URL for an already-open [remote], or null (play direct). */
    fun current(remote: String): String? {
        val s = sessions.values.firstOrNull { it.remote == remote } ?: return null
        if (s.failed) return null
        return "http://127.0.0.1:$port/play?id=${s.id}"
    }

    /** Drop every session and its temp file; the player calls this on exit. */
    fun stop() {
        synchronized(lock) {
            sessions.keys.toList().forEach(::closeSession)
            try {
                server?.close()
            } catch (e: Exception) {
            }
            server = null
            port = 0
        }
    }

    private fun closeSession(id: String) {
        sessions.remove(id)?.let { s ->
            runCatching { s.file.delete() }
        }
    }

    private fun ensureServer() {
        if (server != null) return
        val srv = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = srv
        port = srv.localPort
        thread(name = "seekbridge-accept", isDaemon = true) {
            while (!srv.isClosed) {
                try {
                    val sock = srv.accept()
                    thread(name = "seekbridge-conn", isDaemon = true) { handle(sock) }
                } catch (e: Exception) {
                    break
                }
            }
        }
    }

    private fun fetch(s: Session, headers: Map<String, String>, ua: String) {
        try {
            val rb = Request.Builder().url(s.remote).get()
            if (ua.isNotBlank()) rb.header("User-Agent", ua)
            for ((k, v) in headers) {
                if (k.equals("Range", ignoreCase = true)) continue
                if (k.equals("User-Agent", ignoreCase = true)) continue
                if (k.equals("Host", ignoreCase = true)) continue
                if (k.equals("Accept-Encoding", ignoreCase = true)) continue
                runCatching { rb.header(k, v) }
            }
            s.client.newCall(rb.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    s.failed = true
                    return
                }
                resp.header("Content-Length")?.toLongOrNull()?.let { if (it > 0) s.length = it }
                resp.header("Content-Type")?.substringBefore(";")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    s.mime = it
                }
                val body = resp.body ?: run {
                    s.failed = true
                    return
                }
                s.file.outputStream().use { out ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = body.byteStream().read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                    out.flush()
                }
                if (s.length <= 0) s.length = s.file.length()
                s.done = true
                Logs.log("Player", "seek-bridge ready")
            }
        } catch (e: Exception) {
            // Died mid-download with bytes on disk: serve what we have.
            if (s.file.length() > 0L) {
                s.length = s.file.length()
                s.done = true
            } else {
                s.failed = true
                Logs.log("Player", "seek-bridge fetch failed")
            }
        }
    }

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 30_000
            val input = sock.getInputStream().bufferedReader(Charsets.US_ASCII)
            val reqLine = runCatching { input.readLine() }.getOrNull() ?: run {
                runCatching { sock.close() }
                return
            }
            val parts = reqLine.split(" ")
            if (parts.size < 2) {
                runCatching { sock.close() }
                return
            }
            val method = parts[0].uppercase()
            val target = parts[1]
            var range: String? = null
            while (true) {
                val h = runCatching { input.readLine() }.getOrNull() ?: break
                if (h.isEmpty()) break
                if (h.startsWith("Range:", ignoreCase = true)) range = h.substringAfter(":").trim()
            }
            val id = target.substringAfter("id=", "").substringBefore("&").substringBefore(" ")
            val s = sessions[id] ?: run {
                replyCode(sock, 404, "Not Found")
                return
            }
            var waited = 0
            while (s.length < 0 && !s.done && !s.failed && waited < 1_500) {
                Thread.sleep(100)
                waited += 100
            }
            if (s.failed) {
                replyCode(sock, 502, "Bad Gateway")
                return
            }
            if (method == "HEAD") {
                replyHeaders(sock, 200, "OK", s.mime, s.length, -1, -1, partial = false)
                runCatching { sock.close() }
                return
            }
            val (rs, re) = parseRange(range)
            if (rs != null && s.length > 0 && s.done && rs >= s.length) {
                replyCode(sock, 416, "Range Not Satisfiable")
                return
            }
            serveBytes(sock, s, rs, re)
        } catch (e: Exception) {
            runCatching { sock.close() }
        }
    }

    private fun parseRange(range: String?): Pair<Long?, Long?> {
        if (range == null) return null to null
        val m = Regex("""bytes=(\d*)-(\d*)""").find(range.trim()) ?: return null to null
        val a = m.groupValues[1].toLongOrNull()
        val b = m.groupValues[2].toLongOrNull()
        if (a == null && b == null) return null to null
        return a to b
    }

    private fun serveBytes(sock: Socket, s: Session, rs: Long?, re: Long?) {
        val start = rs ?: 0L
        val out = sock.getOutputStream().buffered(64 * 1024)
        try {
            val end: Long = when {
                re != null && s.length > 0 -> minOf(re, s.length - 1)
                re != null -> re
                s.length > 0 -> s.length - 1
                else -> Long.MAX_VALUE
            }
            val partial = rs != null
            if (partial && end != Long.MAX_VALUE && end < start) {
                replyCode(sock, 416, "Range Not Satisfiable")
                return
            }
            replyHeaders(sock, if (partial) 206 else 200, if (partial) "Partial Content" else "OK", s.mime, s.length, start, end, partial)
            RandomAccessFile(s.file, "r").use { raf ->
                var pos = start
                if (pos > 0) runCatching { raf.seek(pos) }
                val buf = ByteArray(64 * 1024)
                var idleWaited = 0
                while (pos <= end) {
                    val have = s.file.length()
                    if (pos < have) {
                        raf.seek(pos)
                        val want = minOf(buf.size.toLong(), end - pos + 1).toInt()
                        val n = raf.read(buf, 0, want)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        pos += n
                        idleWaited = 0
                    } else if (s.done || s.failed) {
                        break
                    } else {
                        Thread.sleep(120)
                        idleWaited += 120
                        if (idleWaited > 30_000) break
                    }
                }
                out.flush()
            }
        } catch (e: Exception) {
        } finally {
            runCatching { sock.close() }
        }
    }

    private fun replyCode(sock: Socket, code: Int, msg: String) {
        try {
            val out = sock.getOutputStream()
            out.write("HTTP/1.1 $code $msg\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
        } catch (e: Exception) {
        } finally {
            runCatching { sock.close() }
        }
    }

    private fun replyHeaders(
        sock: Socket,
        code: Int,
        msg: String,
        mime: String,
        totalLen: Long,
        start: Long,
        end: Long,
        partial: Boolean,
    ) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $code $msg\r\n")
        sb.append("Content-Type: $mime\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        sb.append("Connection: close\r\n")
        if (partial) {
            val total = if (totalLen > 0) totalLen.toString() else "*"
            val e = if (end == Long.MAX_VALUE) "" else end.toString()
            sb.append("Content-Range: bytes $start-$e/$total\r\n")
            if (end != Long.MAX_VALUE && end >= start) sb.append("Content-Length: ${end - start + 1}\r\n")
        } else if (totalLen > 0) {
            sb.append("Content-Length: $totalLen\r\n")
        }
        sb.append("\r\n")
        sock.getOutputStream().write(sb.toString().toByteArray(Charsets.US_ASCII))
    }
}
