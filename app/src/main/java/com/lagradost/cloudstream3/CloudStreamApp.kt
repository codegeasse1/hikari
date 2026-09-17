@file:Suppress("unused")

package com.lagradost.cloudstream3

import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.hikari.app.HikariApp
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * Shadow of CloudStream's CloudStreamApp.
 *
 * The jar ships the DESKTOP artifact of CloudStream, whose CloudStreamApp is
 * compiled against Coil 3 (it implements `coil3.SingletonImageLoader.Factory`).
 * Coil 3 is not in this app (it bundles Coil 2), so any plugin that touches
 * CloudStreamApp dies with `NoClassDefFoundError: Failed resolution of:
 * Lcom/lagradost/cloudstream3/CloudStreamApp;` the moment its class gets
 * loaded — observed with Cinemacity's Cloudflare-bypass interceptor
 * (CinemacityPlugin.getCfUserAgent → NCDFE on an okhttp thread), which then
 * surface as the "crashed on a previous launch" banner.
 *
 * The fix mirrors the WebViewResolver approach: the jar's broken class is
 * dropped in cloudstreamJarClean (`CloudStreamApp*.class`) and replaced by
 * this self-contained host-side implementation, which provides the Companion
 * API plugins and the jar's other classes actually call — `context`,
 * the persisted key/value store (`getKey`/`setKey`/…), activity lookup and
 * `openBrowser`.
 */
class CloudStreamApp : Application() {

    companion object {
        /**
         * The SharedPreferences file the jar's OWN
         * `com.lagradost.cloudstream3.utils.DataStore` is hard-wired to
         * (`PREFERENCES_NAME` in DataStoreKt.class — read straight out of the
         * shipped jar's bytecode).
         *
         * This matters because not every plugin reads its settings through this
         * class. Cinemacity, for example, gates its whole Cloudflare flow on
         * `CinemacityPlugin.getCfWebviewEnabled()`, which does:
         *
         *     CloudStreamApp.context
         *       ?.let { DataStore.getSharedPrefs(it).getString(KEY, null) }
         *       ?.let { AppUtils.parseJson<Boolean>(it) }
         *       ?: false
         *
         * i.e. it reads that file DIRECTLY and JSON-decodes the stored literal.
         * Writing only into our own file below made those reads always fall
         * back to their default, so a plugin's stored setting never round-
         * tripped. Every write now lands in both files.
         */
        const val CS_PREFS_NAME = "rebuild_preference"

        /** Hikari's own key file: one JSON envelope per key, which is what the
         *  first version of this shadow wrote. Kept as the primary store so the
         *  meaning of everything an existing install already wrote is
         *  unchanged. */
        const val HK_PREFS_NAME = "cloudstream_keys"

        private val prefs by lazy {
            runCatching {
                HikariApp.instance.getSharedPreferences(HK_PREFS_NAME, Context.MODE_PRIVATE)
            }.getOrNull()
        }

        /** The file the jar's DataStore reads (and plugins that bypass this
         *  class read) — see [CS_PREFS_NAME]. */
        private val csPrefs by lazy {
            runCatching {
                HikariApp.instance.getSharedPreferences(CS_PREFS_NAME, Context.MODE_PRIVATE)
            }.getOrNull()
        }

        @Volatile
        private var _context: WeakReference<Context>? = null

        @Volatile
        private var _exceptionHandler: ExceptionHandler? = null

        /** Current host context (wired by HikariApp at startup). */
        val context: Context?
            get() = _context?.get()

        fun setContext(context: Context) {
            _context = WeakReference(context.applicationContext ?: context)
        }

        fun getExceptionHandler(): ExceptionHandler? = _exceptionHandler

        fun setExceptionHandler(handler: ExceptionHandler?) {
            _exceptionHandler = handler
        }

        fun getActivity(context: Context): Activity? =
            CommonActivity.activity
                ?: (context as? Activity)
                ?: runCatching { HikariApp.mainActivity }.getOrNull()

        // ---- persisted key/value store (mirrors CloudStream's plugin prefs) ----

        /** Decodes our own envelope (`{s|n|a|v}`). Returns null when [raw] is
         *  not one (e.g. a CloudStream literal some other writer left behind),
         *  so the caller can fall through to [parseLiteral]. */
        private fun decodeEnvelope(raw: String): Any? = try {
            val o = JSONObject(raw)
            when {
                o.has("s") -> o.optString("s")
                o.has("n") -> o.opt("n")
                o.has("a") -> {
                    val arr = o.optJSONArray("a")
                    (0 until (arr?.length() ?: 0)).map { arr?.opt(it) }
                }
                o.has("v") -> o.opt("v")
                else -> null
            }
        } catch (_: Throwable) {
            null
        }

        /**
         * Parses a CloudStream JSON literal (`AppUtils.toJsonLiteral`): a
         * double-quoted string, a bare number or boolean, or a JSON array/object.
         * Anything that is not valid JSON is handed back as the raw string, so a
         * value written by some other writer is never lost.
         */
        private fun parseLiteral(raw: String?): Any? {
            val t = raw?.trim() ?: return null
            if (t.isEmpty()) return ""
            if (t == "null") return null
            val v = runCatching { JSONArray("[$t]").opt(0) }.getOrNull()
            return if (v == null || v == JSONObject.NULL) t else v
        }

        /** Encodes [value] the way the jar's `AppUtils.toJsonLiteral` does, so
         *  the value is readable by a plugin that parses the prefs file itself. */
        private fun csLiteral(value: Any?): String? = when (value) {
            null -> null
            is Boolean, is Number -> value.toString()
            is String -> JSONObject.quote(value)
            is List<*> -> {
                val arr = JSONArray()
                value.forEach { arr.put(it as? Any ?: JSONObject.NULL) }
                arr.toString()
            }
            else -> JSONObject.quote(value.toString())
        }

        private fun read(key: String): Any? {
            prefs?.getString(key, null)?.let { raw ->
                decodeEnvelope(raw)?.let { return it }
                parseLiteral(raw)?.let { return it }
            }
            return parseLiteral(csPrefs?.getString(key, null))
        }

        private fun write(key: String, value: Any?) {
            val p = prefs
            if (p != null) {
                if (value == null) {
                    p.edit().remove(key).apply()
                } else {
                    val o = JSONObject()
                    try {
                        when (value) {
                            is String -> o.put("s", value)
                            is Number, is Boolean -> o.put("n", value)
                            is List<*> -> {
                                val arr = JSONArray()
                                value.forEach { arr.put(it as? Any ?: JSONObject.NULL) }
                                o.put("a", arr)
                            }
                            else -> o.put("s", value.toString())
                        }
                        p.edit().putString(key, o.toString()).apply()
                    } catch (_: Throwable) {
                    }
                }
            }
            // Mirror into the file the jar's DataStore reads, in ITS encoding —
            // see CS_PREFS_NAME above for why one file is not enough.
            val cs = csPrefs ?: return
            val literal = csLiteral(value)
            if (literal == null) cs.edit().remove(key).apply()
            else cs.edit().putString(key, literal).apply()
        }

        fun setKey(key: String, value: Any?) = write(key, value)

        fun setKey(key: String, type: String, value: Any?) = write(key, value)

        fun setKeyClass(key: String, value: Any?) = write(key, value)

        fun getKey(key: String): Any? = read(key)

        fun getKey(key: String, default: Any?): Any? = read(key) ?: default

        fun getKey(key: String, default: String?): Any? = read(key) ?: default

        fun getKey(key: String, type: String, default: Any?): Any? = read(key) ?: default

        fun getKeyClass(key: String, clazz: Class<*>): Any? = read(key)

        fun getKeys(key: String): List<Any?> =
            (read(key) as? List<*>) ?: emptyList()

        fun removeKey(key: String) {
            prefs?.edit()?.remove(key)?.apply()
            csPrefs?.edit()?.remove(key)?.apply()
        }

        fun removeKey(key: String, subKey: String) {
            val p = prefs ?: return
            p.edit().remove(key).apply()
            p.all.keys.filter {
                it.startsWith("$key$subKey") || it.startsWith("$key.") || it.startsWith("$key$")
            }.forEach { p.edit().remove(it).apply() }
            csPrefs?.edit()?.remove(key)?.apply()
        }

        /**
         * Boxed return type ON PURPOSE: the jar's `CloudStreamApp$Companion`
         * declares `removeKeys(String): Integer` (verified from the jar's own
         * class file), and plugins are compiled against that descriptor. A
         * Kotlin `Int` return would emit `()I` and any plugin calling it would
         * die with `NoSuchMethodError`. `Int?` emits `Ljava/lang/Integer;`.
         */
        fun removeKeys(key: String): Int? {
            val p = prefs ?: return 0
            val toRemove = p.all.keys.filter { it == key || it.startsWith("$key.") || it.startsWith("$key$") }
            toRemove.forEach { p.edit().remove(it).apply() }
            csPrefs?.edit()?.remove(key)?.apply()
            return toRemove.size
        }

        /**
         * Default value on [newTab] ON PURPOSE: the jar declares
         * `openBrowser(url: String, newTab: Boolean = false, fragment: Fragment)`
         * and therefore has a synthetic `openBrowser$default(...)` that plugins
         * call. Without a default parameter Kotlin emits no `$default` bridge,
         * so those plugin calls failed with `NoSuchMethodError`. The
         * three-argument `openBrowser(String, Fragment)` overload below stays
         * for the jar's own second declaration.
         */
        fun openBrowser(url: String, newTab: Boolean = false, fragment: Fragment) {
            openBrowser(url, fragment.activity)
        }

        fun openBrowser(url: String, activity: FragmentActivity?) {
            val act = activity ?: CommonActivity.activity ?: HikariApp.mainActivity ?: return
            runCatching {
                val intent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url)
                )
                act.startActivity(intent)
            }
        }
    }
}
