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
        private val prefs by lazy {
            runCatching {
                HikariApp.instance.getSharedPreferences("cloudstream_keys", Context.MODE_PRIVATE)
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

        private fun read(key: String): Any? {
            val p = prefs ?: return null
            val raw = p.getString(key, null) ?: return null
            return try {
                val o = JSONObject(raw)
                when {
                    o.has("s") -> o.optString("s")
                    o.has("n") -> o.opt("n")
                    o.has("a") -> {
                        val arr = o.optJSONArray("a")
                        (0 until arr.length()).map { arr.opt(it) }
                    }
                    else -> o.opt("v")
                }
            } catch (_: Throwable) {
                raw
            }
        }

        private fun write(key: String, value: Any?) {
            val p = prefs ?: return
            if (value == null) {
                p.edit().remove(key).apply()
                return
            }
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

        fun setKey(key: String, value: Any?) = write(key, value)

        fun setKey(key: String, type: String, value: Any?) = write(key, value)

        fun setKeyClass(key: String, value: Any?) = write(key, value)

        fun getKey(key: String): Any? = read(key)

        fun getKey(key: String, default: Any?): Any? = read(key) ?: default

        fun getKey(key: String, default: String?): Any? = read(key) ?: default

        fun getKey(key: String, type: String, default: Any?): Any? = read(key) ?: default

        fun getKeyClass(key: String, clazz: Class<*>): Any? = read(key)

        fun getKeys(key: String): List<Any?> =
            read(key) as? List<*> ?: emptyList()

        fun removeKey(key: String) {
            prefs?.edit()?.remove(key)?.apply()
        }

        fun removeKey(key: String, subKey: String) {
            val p = prefs ?: return
            p.edit().remove(key).apply()
            p.all.keys.filter {
                it.startsWith("$key$subKey") || it.startsWith("$key.") || it.startsWith("$key$")
            }.forEach { p.edit().remove(it).apply() }
        }

        fun removeKeys(key: String): Int {
            val p = prefs ?: return 0
            val toRemove = p.all.keys.filter { it == key || it.startsWith("$key.") || it.startsWith("$key$") }
            toRemove.forEach { p.edit().remove(it).apply() }
            return toRemove.size
        }

        fun openBrowser(url: String, newTab: Boolean, fragment: Fragment) {
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
