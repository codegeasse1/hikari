package com.hikari.app.reader

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Text-chapter translation for the novel reader: the same engines, source
 * languages and target languages as manga translation, applied to plain
 * paragraphs instead of page images. Results are cached per chapter+settings
 * on disk (like manga), so reopening costs nothing.
 */
object NovelTranslateRuntime {
    private var dir: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inflight = ConcurrentHashMap<String, Deferred<List<String>?>>()

    /** Point at the app's files dir once (the cache lives under it). */
    fun configure(ctx: Context) {
        if (dir == null) {
            dir = File(ctx.applicationContext.filesDir, "novel_translate").apply { mkdirs() }
            scope.launchBlock { prune() }
        }
    }

    private fun CoroutineScope.launchBlock(block: suspend () -> Unit) {
        val job = this.async { block() }
        job.invokeOnCompletion { /* fire and forget */ }
    }

    /**
     * Translated paragraphs, 1:1 with [paras] (untranslated entries keep the
     * original text). Null when translation is off or every engine failed —
     * the caller then keeps showing the original chapter.
     */
    suspend fun translateChapter(
        pageKey: String,
        paras: List<String>,
        st: MangaTranslateSettings,
    ): List<String>? {
        if (!st.enabled || paras.isEmpty()) return null
        val d = dir ?: return null
        val hk = sha1("$pageKey|${st.orig.name}|${st.target.name}|${st.engine.name}|${st.geminiKey}|v3")
        val cacheFile = File(d, "$hk.json")
        readCache(cacheFile, paras.size)?.let { return it }
        val def = inflight.getOrPut(hk) { scope.async { runTranslate(paras, st) } }
        return try {
            val result = def.await()
            if (result != null) writeCache(cacheFile, result)
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            if (def.isCompleted) inflight.remove(hk, def)
        }
    }

    private suspend fun runTranslate(
        paras: List<String>,
        st: MangaTranslateSettings,
    ): List<String>? = withContext(Dispatchers.IO) {
        val out = ArrayList<String>(paras.size)
        var anyTranslated = false
        // Google rides one GET URL per batch: long novel paras blew past the
        // URL limit, so the whole chapter failed and the reader kept the
        // original ("Google does nothing"). Small chunks for Google, big for
        // the rest. Cache key v3 above drops the old all-original results.
        val chunkSize = if (st.engine == MangaTranslateEngine.GOOGLE) 6 else 40
        for (chunk in paras.chunked(chunkSize)) {
            val tr = try {
                MangaTranslateRuntime.translateTexts(chunk, st)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (tr == null) {
                out.addAll(chunk)
                continue
            }
            for (i in chunk.indices) {
                val t = tr.getOrNull(i)?.trim().orEmpty()
                if (t.isBlank()) out.add(chunk[i])
                else {
                    out.add(t)
                    anyTranslated = true
                }
            }
        }
        if (!anyTranslated) null else out
    }

    private fun readCache(f: File, size: Int): List<String>? {
        return try {
            if (!f.exists()) return null
            val arr = JSONObject(f.readText()).optJSONArray("paras") ?: return null
            if (arr.length() != size) return null
            ArrayList<String>(arr.length()).also { out ->
                for (i in 0 until arr.length()) out.add(arr.optString(i))
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun writeCache(f: File, paras: List<String>) {
        try {
            val arr = JSONArray()
            for (p in paras) arr.put(p)
            f.writeText(JSONObject().put("paras", arr).toString())
        } catch (e: Exception) {
        }
    }

    private fun prune() {
        try {
            val d = dir ?: return
            val cutoff = System.currentTimeMillis() - 14L * 24 * 60 * 60 * 1000
            d.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        } catch (e: Exception) {
        }
    }

    private fun sha1(s: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
