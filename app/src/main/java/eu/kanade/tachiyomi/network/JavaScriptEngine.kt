package eu.kanade.tachiyomi.network

import android.content.Context
import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.Dispatchers
import tachiyomi.core.common.util.lang.withIOContext

/**
 * Util for evaluating JavaScript in sources.
 *
 * Aniyomi runs this on `app.cash.quickjs`; Hikari already ships the same engine
 * under its own package (`com.dokar.quickjs`, the runtime the Nuvio and
 * SkyStream providers use), so the class keeps Aniyomi's exact public shape —
 * that is all an extension's bytecode links against — and delegates to it.
 */
class JavaScriptEngine(context: Context) {

    @Suppress("UNUSED", "UNCHECKED_CAST")
    suspend fun <T> evaluate(script: String): T = withIOContext {
        val qjs = QuickJs.create(jobDispatcher = Dispatchers.IO)
        try {
            qjs.evaluate<Any?>(script, "extension.js", false) as T
        } finally {
            runCatching { qjs.close() }
        }
    }
}
