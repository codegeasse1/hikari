package com.hikari.desktop.engine

import com.caoccao.javet.annotations.V8Function
import com.caoccao.javet.interfaces.IJavetAnonymous
import com.caoccao.javet.interop.V8Host
import com.caoccao.javet.interop.V8Runtime

/** One JS evaluation unit for a single provider call. */
data class JsScript(val name: String, val source: String)

data class JsRun(
    val scripts: List<JsScript>,
    /** Evaluated to start the async work (registers promises / calls entries). */
    val startEval: String,
    /** Evaluated every pump round (family timer queue); null when the family has none. */
    val pumpEval: String? = null,
    val timeoutMs: Long = 60_000,
    val pollMs: Long = 25,
)

data class JsResult(val ok: Boolean, val payload: String?, val error: String?)

/**
 * Shared V8 host for every JS extension family (Javet). One fresh V8 runtime
 * per call, all bridge traffic as JSON strings, sync-blocking fetch so no
 * family needs its own async plumbing: `await` on an already-available value
 * just continues, and the pump loop drains microtasks + family timers.
 */
class JavetJsHost(
    private val userAgent: String = EngineHttp.NUVIO_DEFAULT_UA,
) {

    inner class Bridge(val pluginId: String) : IJavetAnonymous {
        private val crypto = DesktopCryptoBridge()
        private val prefsFile: java.io.File
            get() = prefsFileFor(pluginId)

        @Volatile
        var done: String? = null
            private set

        private fun mark(payload: String?): String {
            if (done == null) done = payload ?: ""
            return ""
        }

        // ---- fetch (sync-blocking; the harness `await`s an available value) ----

        @V8Function(name = "fetchImpl")
        fun fetchImpl(url: String, method: String, headersJson: String, body: String, followRedirects: Boolean): String =
            runCatching {
                EngineHttp.fetchJson(url, method, headersJson, body, followRedirects, userAgent)
            }.getOrElse { e ->
                "{\"ok\":false,\"status\":0,\"statusText\":\"network error\",\"url\":${jsQuote(url)}," +
                    "\"headers\":{},\"body\":\"\",\"bodyBase64\":\"\",\"error\":${jsQuote((e.message ?: "network error").take(200))}}"
            }

        // ---- completion (every family name wired to the same slot) ----

        @V8Function(name = "streamsDone")
        fun streamsDone(cid: String, payload: String): String = mark(payload)

        @V8Function(name = "callDone")
        fun callDone(payload: String): String = mark(payload)

        // ---- logging ----

        @V8Function(name = "logImpl")
        fun logImpl(msg: String): String = ""

        // ---- per-provider key/value prefs (SkyStream-style; file-backed) ----

        @V8Function(name = "prefGet")
        fun prefGet(key: String): String {
            return runCatching {
                val f = prefsFile
                if (!f.exists()) return ""
                val text = f.readText()
                val obj = com.google.gson.JsonParser.parseString(text).asJsonObject
                obj.get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            }.getOrDefault("")
        }

        @V8Function(name = "prefSet")
        fun prefSet(key: String, value: String): String {
            runCatching {
                val f = prefsFile
                f.parentFile?.mkdirs()
                val obj = runCatching {
                    com.google.gson.JsonParser.parseString(f.takeIf { it.exists() }?.readText() ?: "{}").asJsonObject
                }.getOrDefault(com.google.gson.JsonObject())
                obj.addProperty(key, value)
                f.writeText(obj.toString())
            }
            return ""
        }

        // ---- WebCrypto (delegated 1:1) ----

        @V8Function(name = "randHex")
        fun randHex(n: Int): String = crypto.__crypto_get_random_values_hex(n)

        @V8Function(name = "digestHex")
        fun digestHex(alg: String, dataHex: String): String = crypto.__crypto_digest_hex_raw(alg, dataHex)

        @V8Function(name = "hmacHex")
        fun hmacHex(alg: String, keyHex: String, dataHex: String): String =
            crypto.__crypto_hmac_hex_raw(alg, keyHex, dataHex)

        @V8Function(name = "pbkdf2Hex")
        fun pbkdf2Hex(passHex: String, saltHex: String, iterations: Int, keyBits: Int, hash: String): String =
            crypto.__crypto_pbkdf2_hex(passHex, saltHex, iterations, keyBits, hash)

        @V8Function(name = "aesEncryptHex")
        fun aesEncryptHex(mode: String, keyHex: String, ivHex: String, dataHex: String): String =
            crypto.__crypto_aes_encrypt_hex(mode, keyHex, ivHex, dataHex)

        @V8Function(name = "aesDecryptHex")
        fun aesDecryptHex(mode: String, keyHex: String, ivHex: String, dataHex: String): String =
            crypto.__crypto_aes_decrypt_hex(mode, keyHex, ivHex, dataHex)
    }

    fun execute(run: JsRun, pluginId: String): JsResult {
        val bridge = Bridge(pluginId)
        var runtime: V8Runtime? = null
        try {
            runtime = V8Host.getV8I18nInstance().createV8Runtime()
            val rt = runtime
            rt.globalObject.set("HikariBridge", bridge)
            // Alias every historic host name to the one bridge object, so all
            // families (and their unknown future renames) land in one place.
            rt.getExecutor(
                "var __B = globalThis.HikariBridge;" +
                    "globalThis.__hikariFetch = function(u,m,h,b,f){ return __B.fetchImpl(String(u), String(m||'GET'), String(h==null?'{}':h), String(b==null?'':b), f!==false); };" +
                    "globalThis.__hikariOnStreamsDone = function(c,p){ return __B.streamsDone(String(c), String(p)); };" +
                    "globalThis.__hikariDone = function(p){ return __B.callDone(String(p)); };" +
                    "globalThis.__soraDone = function(p){ return __B.callDone(String(p)); };" +
                    "globalThis.__anymexDone = function(p){ return __B.callDone(String(p)); };" +
                    "globalThis.__zDone = function(p){ return __B.callDone(String(p)); };" +
                    "globalThis.__vegaDone = function(p){ return __B.callDone(String(p)); };" +
                    "globalThis.__lnDone = function(p){ return __B.callDone(String(p)); };" +
                    "globalThis.__hikariLog = function(m){ return __B.logImpl(String(m)); };" +
                    "globalThis.__soraLog = function(m){ return __B.logImpl(String(m)); };" +
                    "globalThis.__hikariPrefGet = function(k){ return __B.prefGet(String(k)); };" +
                    "globalThis.__hikariPrefSet = function(k,v){ return __B.prefSet(String(k), String(v)); };" +
                    "globalThis.__crypto_get_random_values_hex = function(n){ return __B.randHex(Number(n)||0); };" +
                    "globalThis.__crypto_digest_hex_raw = function(a,d){ return __B.digestHex(String(a), String(d)); };" +
                    "globalThis.__crypto_hmac_hex_raw = function(a,k,d){ return __B.hmacHex(String(a), String(k), String(d)); };" +
                    "globalThis.__crypto_pbkdf2_hex = function(p,s,i,b,h){ return __B.pbkdf2Hex(String(p), String(s), Number(i)||1000, Number(b)||256, String(h||'SHA1')); };" +
                    "globalThis.__crypto_aes_encrypt_hex = function(m,k,iv,d){ return __B.aesEncryptHex(String(m), String(k), String(iv), String(d)); };" +
                    "globalThis.__crypto_aes_decrypt_hex = function(m,k,iv,d){ return __B.aesDecryptHex(String(m), String(k), String(iv), String(d)); };"
            ).executeVoid()
            for (s in run.scripts) {
                if (s.source.isBlank()) return JsResult(false, null, "missing engine asset: ${s.name}")
                rt.getExecutor(s.source).executeVoid()
            }
            rt.getExecutor(run.startEval).executeVoid()
            val deadline = System.currentTimeMillis() + run.timeoutMs
            while (bridge.done == null && System.currentTimeMillis() < deadline) {
                run.pumpEval?.let { pump ->
                    runCatching { rt.getExecutor(pump).executeString() }
                }
                runCatching { rt.await() }
                try {
                    Thread.sleep(run.pollMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            val payload = bridge.done
            return if (payload != null) JsResult(true, payload, null)
            else JsResult(false, null, "timed out after ${run.timeoutMs}ms")
        } catch (e: Exception) {
            return JsResult(false, null, (e.message ?: "engine failed").take(300))
        } finally {
            runCatching { runtime?.close() }
        }
    }

    companion object {
        fun prefsFileFor(pluginId: String): java.io.File {
            val safe = pluginId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            val base = System.getProperty("user.home") ?: "."
            return java.io.File(base, ".hikari-desktop/engines/$safe.json")
        }

        /** JSON string quoting without extra deps. */
        fun jsQuote(s: String): String {
            val sb = StringBuilder(s.length + 2)
            sb.append('"')
            for (c in s) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\b' -> sb.append("\\b")
                    else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
                }
            }
            sb.append('"')
            return sb.toString()
        }
    }
}
