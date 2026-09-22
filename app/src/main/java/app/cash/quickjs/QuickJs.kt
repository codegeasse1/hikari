package app.cash.quickjs

import com.dokar.quickjs.QuickJs as Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.Closeable

/**
 * The JavaScript engine extensions are compiled against, under the name they
 * link to.
 *
 * Aniyomi bundles `app.cash.quickjs` (a fork of Cash App's QuickJS binding), and
 * extensions call it DIRECTLY — AnimeOnline.Ninja's `VrfInterceptor` runs
 * `QuickJs.create().use { it.evaluate("$west + $east") }` to solve that site's
 * `wsidchk` interstitial, and it is the standard tool for the "compute a token
 * the page's own script would have computed" class of anti-bot wall. Hikari
 * ships the same ENGINE (the `com.dokar.quickjs` runtime the Nuvio and SkyStream
 * providers use, see `eu.kanade.tachiyomi.network.JavaScriptEngine`) but not
 * under that package, so an extension reaching for it died with
 * `NoClassDefFoundError: app.cash.quickjs.QuickJs` — and on a site that needs
 * that computation the failure showed up as an HTTP 403 from the challenge page.
 *
 * This class is the thin bridge: same public shape (`create`, `evaluate`,
 * `get`, `set`, `close`), same synchronous behaviour, backed by the engine
 * Hikari already ships — no second native library, no second QuickJS in the APK.
 *
 * The blocking calls are safe here by construction: extensions call this from
 * their own network threads (an OkHttp interceptor or a source's parse method),
 * never from the main thread, and each call runs on the engine's own dispatcher.
 */
class QuickJs private constructor(private val engine: Engine) : Closeable {

    /** Evaluate [script] and return its value (`null` for a void script). */
    fun evaluate(script: String): Any? = runBlocking {
        engine.evaluate<Any?>(script, "quickjs.js", false)
    }

    /** Evaluate [script] with a file name, for engine-side error messages. */
    fun evaluate(name: String, script: String): Any? = runBlocking {
        engine.evaluate<Any?>(script, name, false)
    }

    /** Read a global. */
    fun <T> get(globalName: String): T = runBlocking {
        engine.evaluate<T>(globalName, "quickjs-get.js", false)
    }

    /**
     * Assign a global. Primitives (and Strings) are written as JavaScript
     * literals — that is all the extensions using this need (a cookie string, a
     * numeric token), and it keeps the bridge free of a JS-object marshalling
     * layer none of them report using.
     */
    fun set(globalName: String, value: Any?) {
        val literal = when (value) {
            null -> "null"
            is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            is Boolean, is Number -> value.toString()
            else -> null
        } ?: return
        evaluate("$globalName = $literal;")
    }

    override fun close() {
        runCatching { engine.close() }
    }

    companion object {
        @JvmStatic
        fun create(): QuickJs = QuickJs(Engine.create(jobDispatcher = Dispatchers.Default))

        /**
         * Cash App's engine accepts VM arguments here; the engine Hikari ships
         * takes its memory/stack limits through properties instead, so the
         * arguments are accepted and ignored.
         *
         * Deliberately ONE vararg overload: a second `create(Array<String>)` is
         * the same JVM signature as this one (`create([Ljava/lang/String;)`) and
         * would not compile.
         */
        @JvmStatic
        fun create(vararg args: String): QuickJs = create()
    }
}
