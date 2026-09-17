package com.hikari.app.net

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.hikari.app.data.Logs
import com.lagradost.cloudstream3.CloudStreamApp

/**
 * Keeps EXTENSIONS from opening their own Cloudflare verification page.
 *
 * Hikari's own rule is that nothing loads a Cloudflare challenge unless the user
 * taps the WebView (globe) button themselves — every launch of Hikari's
 * [com.hikari.app.web.WebViewActivity] is inside a click handler, and the
 * requests are replayed with the clearance that tap earned
 * (see [CloudflareVerifier]). Extensions can't be forced to follow that rule
 * from the outside: several of them ship their own Cloudflare WebView, and a
 * few even open it by themselves in the middle of `loadLinks`.
 *
 * Cinemacity is the one seen doing it. Its dex (verified by disassembly) reads:
 *
 *     if (Cinemacity.isCloudflareBlocked(response)) {
 *         if (CinemacityPlugin.getCfWebviewEnabled())      // pref-gated
 *             showCinemacityCFBypassDialogAndWait(url)     // WebView dialog
 *     }
 *
 * and `showCinemacityCFBypassDialogAndWait` either
 *   * shows `CloudflareWebViewDialog` (tag `cinemacity_cf_bypass_auto`) on the
 *     current AppCompatActivity — the verification page opening on its own, or
 *   * falls back to the Toast
 *     "CinemaCity: Cloudflare blocked. Go to Settings → Bypass Cloudflare."
 *     when there is no usable activity (it runs on a background thread while the
 *     player is up), which is the raw Cloudflare wording that used to surface
 *     over the video.
 *
 * Both symptoms are that one `if`. `getCfWebviewEnabled()` reads the toggle from
 * the `rebuild_preference` SharedPreferences file (the jar's `DataStore`), so
 * forcing that key to `false` removes the dialog AND the toast, and Hikari's own
 * tap-only verification flow stays the single way a challenge can be cleared.
 *
 * The switch in Settings → Privacy & Browsing can let them back through; it is
 * off by default and it turns this guard into a pass-through.
 */
object ExtensionVerifyGuard {

    /**
     * Toggles that gate an extension's own Cloudflare WebView, under the exact
     * key the extension stores them with.
     *
     * CINEMACITY_CF_WEBVIEW_ENABLED — Cinemacity's own switch (its settings
     * sheet shows it as "Bypass Cloudflare" → "cf_webview_toggle", which writes
     * this key through CloudStreamApp.setKey).
     */
    private val KNOWN_KEYS = listOf("CINEMACITY_CF_WEBVIEW_ENABLED")

    /**
     * Fallback for extensions this build has never seen: any other stored
     * toggle whose NAME pairs a Cloudflare word with a webview/bypass word is
     * treated the same way, so a new extension doing the same thing is covered
     * without waiting for a Hikari update. Only keys that already exist in a
     * preferences file are touched, and only ever set to `false` — a key is
     * never invented.
     */
    private val CLOUDFLARE_WORD = Regex("CF|CLOUDFLARE", RegexOption.IGNORE_CASE)
    private val WEBVIEW_WORD = Regex("WEBVIEW|BYPASS|VERIFY|CHALLENGE", RegexOption.IGNORE_CASE)

    /**
     * Applies the user's choice. Returns a description of every key it changed
     * (empty when nothing needed changing), for the app log.
     */
    fun apply(context: Context, allow: Boolean): List<String> {
        val targets = if (allow) KNOWN_KEYS else (KNOWN_KEYS + discoveredToggleKeys(context))
            .distinct()
        val changed = ArrayList<String>(targets.size)
        for (key in targets) {
            val current = readToggle(context, key)
            if (current == allow) continue
            writeToggle(context, key, allow)
            changed += "$key=${allow}"
        }
        if (changed.isNotEmpty()) {
            Logs.log(
                "Extensions",
                (if (allow) "extension verification pages allowed: " else "extension verification pages blocked: ") +
                    changed.joinToString(", "),
            )
        }
        return changed
    }

    /** Every preference file a plugin's setting could live in. */
    private fun prefFiles(context: Context): List<SharedPreferences> = listOfNotNull(
        runCatching {
            context.getSharedPreferences(CloudStreamApp.CS_PREFS_NAME, Context.MODE_PRIVATE)
        }.getOrNull(),
        runCatching {
            context.getSharedPreferences(CloudStreamApp.HK_PREFS_NAME, Context.MODE_PRIVATE)
        }.getOrNull(),
        runCatching { PreferenceManager.getDefaultSharedPreferences(context) }.getOrNull(),
    )

    /**
     * Existing keys across [prefFiles] that look like an extension's own
     * Cloudflare-WebView toggle. CINEMACITY_CF_WEBVIEW_ENABLED is exactly this
     * shape, which is what makes it a safe rule to generalise.
     */
    private fun discoveredToggleKeys(context: Context): List<String> {
        val out = LinkedHashSet<String>()
        for (file in prefFiles(context)) {
            val keys = runCatching { file.all.keys }.getOrNull() ?: continue
            for (key in keys) {
                if (key.isBlank()) continue
                if (CLOUDFLARE_WORD.containsMatchIn(key) && WEBVIEW_WORD.containsMatchIn(key)) {
                    out += key
                }
            }
        }
        return out.toList()
    }

    /** Reads a stored boolean however it was written (bare literal, JSON string
     *  or Hikari's own envelope) — null when the key isn't set at all. */
    private fun readToggle(context: Context, key: String): Boolean? = when (val v = CloudStreamApp.getKey(key)) {
        null -> null
        is Boolean -> v
        is String -> when (v.trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
        is Number -> v.toInt() != 0
        else -> null
    }

    /**
     * Stores a boolean the way an extension will read it: `setKey` writes both
     * of CloudStreamApp's stores with the CloudStream literal encoding, and the
     * default preferences file gets the same bare literal in case this build of
     * the jar's `DataStore.getSharedPrefs` resolves to it.
     */
    private fun writeToggle(context: Context, key: String, value: Boolean) {
        runCatching { CloudStreamApp.setKey(key, value) }
        runCatching {
            PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putString(key, value.toString())
                .apply()
        }
    }
}
