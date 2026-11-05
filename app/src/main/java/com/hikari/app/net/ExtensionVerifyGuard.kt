package com.hikari.app.net

import android.content.Context
import android.content.ContextWrapper
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
 * That read is why this object works in three places, not one. Writing the
 * stored value is not enough on its own — an extension can hold its own
 * default (`?: true`), cache the value, or read the prefs file directly
 * through `Context.getSharedPreferences` (which is exactly what Cinemacity
 * does, via `DataStore.getSharedPrefs`). So while extension verification
 * pages are blocked:
 *
 *  * [forcesOff] makes every read through the shadow
 *    [com.lagradost.cloudstream3.CloudStreamApp] answer `false` for a
 *    verification-shaped key, even one that was never stored;
 *  * [apply] stores `false` for that key in every preferences file an
 *    extension could be reading (and never stores `true` — turning Hikari's
 *    own switch on only stops the forcing, it never enables an extension's
 *    bypass);
 *  * [wrapContext] wraps the Context extensions are handed, so a plugin that
 *    opens a preferences file itself gets `false` back for those keys, and
 *    cannot write `true` into one either.
 *
 * Together those three make the switch unflippable from inside an extension.
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
    private val CLOUDFLARE_WORD =
        Regex("CF|CLOUDFLARE|TURNSTILE|CAPTCHA|SECURITY|\\bBOT\\b", RegexOption.IGNORE_CASE)
    private val WEBVIEW_WORD =
        Regex("WEBVIEW|BYPASS|VERIFY|CHALLENGE|CAPTCHA|TURNSTILE|SOLVE", RegexOption.IGNORE_CASE)

    /** The user's current choice, readable by the popup guard: [true] when an
     *  extension's own verification pages are allowed through (Settings →
     *  Sources & Extensions → "Open their verification pages"). */
    val pagesAllowed: Boolean get() = allowed

    /**
     * Words that make a popup an EXTENSION puts on one of Hikari's screens its
     * own verification screen or its funding screen — the two kinds of popup a
     * user never asks for and never wants over a loading episode.
     *
     * This exists because forcing pref keys off ([apply], [forcesOff]) is not
     * enough for every extension. Forcing a key can only work when the popup is
     * gated by a preference at all, and several are not: Anichi (Phisher repo,
     * `Anichi.cs3` — verified by reading its dex strings) ships
     * `com.Anichi.AnichiTurnstileDialog`, a DialogFragment it shows by itself in
     * the middle of resolving links, with the heading "Anichi Security Check",
     * the line "Solve the security check if prompted. Dialog closes
     * automatically once the episode loads.", and an injected
     * `window.AnichiApiBridge` that reports `onSecurityCheckDetected` out of the
     * page it loads. There is no key to force off — the dialog IS the flow.
     * Hikari's rule stays what it always was: a challenge page opens only when
     * the user taps the app's own verify (globe) button.
     *
     * Matched against the popup's CLASS NAME *and* its fragment TAG, because a
     * plugin that obfuscates its classes still names the tag it shows them with
     * (Anichi's own tag is `anichi_turnstile`).
     *
     * Nothing here matches a plugin's settings sheet: those are named after the
     * plugin (`com.cncverse.Settings`, SK Tech's sub-provider picker, …), which
     * is exactly why the close-button/scroll fix in MainActivity keeps working.
     */
    private val POPUP_BLOCK_WORDS = Regex(
        "TURNSTILE|CLOUDFLARE|CAPTCHA|CHALLENGE|VERIF|SOLVER|SECURITY" +
            "|DONATION|DONATE|PROMO|FUNDING|SUPPORT_US|MEMBERSHIP",
        RegexOption.IGNORE_CASE,
    )

    /**
     * True when a popup an extension opened on its own has to be closed the
     * moment it appears: a verification screen (Turnstile / Cloudflare / captcha
     * / "security check" dance) or a funding/donation screen. False while the
     * user has allowed extensions' verification pages through — that switch is
     * the one escape hatch for an extension that only works via its own bypass
     * screen.
     */
    fun blocksPopup(className: String?, tag: String?): Boolean {
        if (allowed) return false
        return POPUP_BLOCK_WORDS.containsMatchIn(className.orEmpty()) ||
            POPUP_BLOCK_WORDS.containsMatchIn(tag.orEmpty())
    }

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
     *
     * Turning the switch ON deliberately writes NOTHING: it only stops the
     * forcing. Writing `true` into an extension's own bypass switch — which is
     * what this used to do — would make Hikari *enable* the very page the user
     * is complaining about, and then leave it enabled after they turn the
     * switch back off only on the next launch. The extension's own switch
     * keeps whatever the user of that extension set; Hikari simply gets out of
     * the way.
     */
    fun apply(context: Context, allow: Boolean): List<String> {
        allowed = allow
        val changed = ArrayList<String>()
        val targets = if (allow) emptyList()
        else (KNOWN_KEYS.map { null to it } + discoveredToggles(context)).distinct()
        for ((file, key) in targets) {
            val current = readToggle(context, key)
            if (current != false) changed += "$key=false"
            writeToggle(context, key, false, file)
        }
        if (changed.isNotEmpty()) {
            Logs.log(
                "Extensions",
                "extension verification pages blocked: " + changed.joinToString(", "),
            )
        }
        return changed
    }

    /**
     * The Context extensions are handed (see
     * [com.lagradost.cloudstream3.CloudStreamApp.context]). While verification
     * pages are blocked, every `getSharedPreferences(...)` on it hands back
     * [GuardedPrefs], so an extension that reads its own switch straight from a
     * preferences file — the way Cinemacity's `getCfWebviewEnabled()` does,
     * through the jar's `DataStore` — still reads `false`.
     */
    fun wrapContext(base: Context): Context = if (allowed) base else GuardedContext(base)

    /** A Context whose SharedPreferences hand out `false` (and never store
     *  `true`) for verification-shaped keys. */
    private class GuardedContext(base: Context) : ContextWrapper(base) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
            GuardedPrefs(super.getSharedPreferences(name, mode))
    }

    /**
     * Read/write interception for one preferences file. Reads of a guarded key
     * answer `false` whatever is stored (so a key that was never written, or
     * that an extension wrote as `true` before this guard ran, still reads
     * blocked), and writes of one are coerced to `false` (so an extension can
     * never turn its own bypass back on). Every other key passes straight
     * through, untouched.
     */
    private class GuardedPrefs(private val real: SharedPreferences) : SharedPreferences {
        private fun guarded(key: String?): Boolean = key != null && forcesOff(key)

        override fun getAll(): MutableMap<String, *> {
            val out = HashMap<String, Any?>(real.all)
            for (key in out.keys.toList()) if (forcesOff(key)) out[key] = false
            return out
        }

        override fun getString(key: String?, defValue: String?): String? =
            if (guarded(key)) "false" else real.getString(key, defValue)

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            if (guarded(key)) mutableSetOf() else real.getStringSet(key, defValues)

        override fun getInt(key: String?, defValue: Int): Int =
            if (guarded(key)) 0 else real.getInt(key, defValue)

        override fun getLong(key: String?, defValue: Long): Long =
            if (guarded(key)) 0L else real.getLong(key, defValue)

        override fun getFloat(key: String?, defValue: Float): Float =
            if (guarded(key)) 0f else real.getFloat(key, defValue)

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            if (guarded(key)) false else real.getBoolean(key, defValue)

        override fun contains(key: String?): Boolean =
            if (guarded(key)) true else real.contains(key)

        override fun edit(): SharedPreferences.Editor = GuardedEditor(real.edit())

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = real.registerOnSharedPreferenceChangeListener(listener)

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = real.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private class GuardedEditor(private val real: SharedPreferences.Editor) : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            // Only a value that IS the toggle being switched on is coerced. A
            // key that merely looks like a verification key can hold a URL or a
            // mode name, and rewriting that would corrupt the extension's own
            // setting instead of guarding it.
            val turningOn = value != null && value.trim().lowercase() == "true"
            real.putString(key, if (key != null && turningOn && forcesOff(key)) "false" else value)
            return this
        }

        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor {
            real.putStringSet(key, values)
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            real.putInt(key, value)
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            real.putLong(key, value)
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            real.putFloat(key, value)
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            real.putBoolean(key, if (key != null && forcesOff(key)) false else value)
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            real.remove(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            real.clear()
            return this
        }

        override fun commit(): Boolean = real.commit()

        override fun apply() {
            real.apply()
        }
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

    /**
     * Reads a stored boolean however it was written — bare literal, JSON string
     * or Hikari's own envelope — and null when the key isn't set anywhere.
     *
     * Deliberately reads the files directly instead of going through
     * [CloudStreamApp.getKey]: that read is one of the things this guard
     * forces off, so asking it what is stored would always answer `false` and
     * the log would never report a key that is genuinely stuck on.
     */
    private fun readToggle(context: Context, key: String): Boolean? {
        for (file in prefFiles(context)) {
            val raw = runCatching { file.all[key] }.getOrNull() ?: continue
            val parsed = when (raw) {
                is Boolean -> raw
                is Number -> raw.toInt() != 0
                is String -> when (raw.trim().trim('"').lowercase()) {
                    "true" -> true
                    "false" -> false
                    else -> null
                }
                else -> null
            }
            if (parsed != null) return parsed
        }
        return null
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
