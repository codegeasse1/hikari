package com.hikari.app.net

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.hikari.app.data.Logs
import com.lagradost.cloudstream3.CloudStreamApp
import java.io.File

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
     * The user's current choice. Starts `false` (blocked) because that is the
     * preference's default, so a read that happens before [apply] runs — a
     * plugin's settings sheet touched during launch — is still guarded.
     */
    @Volatile
    private var allowed = false

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

    /** True for a key [apply] would force off (the same rule [discoveredToggles]
     *  uses), so a key that doesn't exist in any file yet is covered too. */
    private fun looksLikeVerifyToggle(key: String): Boolean =
        KNOWN_KEYS.contains(key) ||
            (CLOUDFLARE_WORD.containsMatchIn(key) && WEBVIEW_WORD.containsMatchIn(key))

    /**
     * Read-time half of the guard: when the user has extensions' own
     * verification pages blocked, every CF/X-WebView-shaped key reads back as
     * `false` **even if it doesn't exist on disk yet**.
     *
     * This is the part that closes the gap [apply] alone cannot: an extension
     * that ships the toggle enabled (`?: true`, or its own default written on
     * first run) would otherwise get `true` back and open its dialog anyway.
     * The keys are only ever turned *off* here, never on — and never invented,
     * just intercepted on the way out of the jar's key store.
     */
    fun forcesOff(key: String): Boolean = !allowed && looksLikeVerifyToggle(key)

    /**
     * Applies the user's choice. Returns a description of every key it changed
     * (empty when nothing needed changing), for the app log.
     */
    fun apply(context: Context, allow: Boolean): List<String> {
        allowed = allow
        val changed = ArrayList<String>()
        val targets = if (allow) KNOWN_KEYS.map { null to it }
        else (KNOWN_KEYS.map { null to it } + discoveredToggles(context)).distinct()
        for ((file, key) in targets) {
            val current = readToggle(context, key)
            if (current != allow) changed += "$key=${allow}"
            writeToggle(context, key, allow, file)
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

    /**
     * Every preference file a plugin's setting could live in: the two files the
     * jar's key store is known to use, the default file, and then EVERY `*.xml`
     * in the app's `shared_prefs` directory — an extension that uses
     * `context.getSharedPreferences("xdmovies_prefs", …)` names its own file,
     * and that file is exactly as visible to us as the jar's.
     */
    private fun prefFiles(context: Context): List<SharedPreferences> {
        val out = LinkedHashSet<SharedPreferences>()
        runCatching {
            out += context.getSharedPreferences(CloudStreamApp.CS_PREFS_NAME, Context.MODE_PRIVATE)
        }
        runCatching {
            out += context.getSharedPreferences(CloudStreamApp.HK_PREFS_NAME, Context.MODE_PRIVATE)
        }
        runCatching { out += PreferenceManager.getDefaultSharedPreferences(context) }
        runCatching {
            val dir = File(context.dataDir, "shared_prefs")
            val xmls = dir.listFiles { f -> f.isFile && f.name.endsWith(".xml") } ?: return@runCatching
            for (f in xmls) {
                val name = f.name.removeSuffix(".xml")
                if (name.isBlank()) continue
                runCatching { out += context.getSharedPreferences(name, Context.MODE_PRIVATE) }
            }
        }
        return out.toList()
    }

    /**
     * Existing keys across [prefFiles] that look like an extension's own
     * Cloudflare-WebView toggle. CINEMACITY_CF_WEBVIEW_ENABLED is exactly this
     * shape, which is what makes it a safe rule to generalise.
     */
    private fun discoveredToggles(context: Context): List<Pair<SharedPreferences?, String>> {
        val out = LinkedHashSet<Pair<SharedPreferences?, String>>()
        for (file in prefFiles(context)) {
            val keys = runCatching { file.all.keys }.getOrNull() ?: continue
            for (key in keys) {
                if (key.isBlank() || !looksLikeVerifyToggle(key)) continue
                out += (file to key)
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
    private fun writeToggle(
        context: Context,
        key: String,
        value: Boolean,
        file: SharedPreferences? = null,
    ) {
        runCatching { CloudStreamApp.setKey(key, value) }
        // A key that lives in some extension's own file has to be written back
        // into THAT file: the extension reads it directly, not through the
        // jar's key store.
        if (file != null) {
            runCatching { file.edit().putString(key, value.toString()).apply() }
        }
        runCatching {
            PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putString(key, value.toString())
                .apply()
        }
    }
}
