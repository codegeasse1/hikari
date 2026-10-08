package com.hikari.app.net

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.TextView
import com.hikari.app.data.Logs
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * Keeps EXTENSIONS from opening things on their own — the enforcement behind
 * "nothing opens unless the user taps it".
 *
 * [ExtensionVerifyGuard] already kills the two mechanisms extensions were seen
 * using (their pref-gated Cloudflare WebViews, and their verification/funding
 * `DialogFragment`s — see `MainActivity.dismissExtensionPopup`). What is left
 * is everything that does NOT go through those paths:
 *
 *  * a plain `AlertDialog`/`Dialog`/`PopupWindow` shown straight on the
 *    activity (never a fragment, so the fragment guard never sees it) — the
 *    shape developer "support us / donate / join our Telegram" promos take,
 *    and the shape an obfuscated verification screen takes;
 *  * an `ACTION_VIEW` browser intent (or a plugin's own Activity) fired from a
 *    background thread in the middle of resolving links — the "a Cloudflare
 *    page / promo page opened by itself" report.
 *
 * Both need either the activity (`CommonActivity.activity` is the REAL
 * activity) or a context — so both flow through exactly two choke points,
 * guarded here:
 *
 *  * [wrapWindowManager] — every floating window (`Dialog`, `PopupWindow`,
 *    custom toast) added through an activity/application context passes a
 *    proxy installed by our own `getSystemService` overrides;
 *  * [checkStartActivity] — consulted by our own `startActivity` overrides
 *    before anything leaves the app.
 *
 * The rule is gesture-based, not content-based: code running WITHOUT any user
 * gesture on the stack (a background resolve, a timer, a pref change) may not
 * open a floating window that looks like a verification or funding screen and
 * may not fire an external intent at all; the moment a tap/click/menu gesture
 * is on the stack it is the user driving and everything passes. That keeps
 * every legitimate flow working — the globe button (our own code in any
 * case), a plugin settings sheet the user opened (see the allow-window
 * below), an OAuth "log in via browser" button inside it — while background
 * auto-opens die with a log line.
 *
 * The allow-window ([allowFor]) covers the one case gestures cannot: our code
 * deliberately invoking plugin UI whose follow-on work lands later on a bare
 * handler (a plugin settings sheet opening a sub-dialog a moment later). It is
 * set only from our own call sites (`Cs3PluginManager.openSettings`), and the
 * master switch (Settings → "Open their verification pages") lifts the whole
 * guard via [ExtensionVerifyGuard.pagesAllowed] for the extension that only
 * works through its own bypass screen.
 */
object ExtensionUiGuard {

    /** How long a deliberate plugin-UI entry keeps follow-on windows allowed. */
    const val SETTINGS_ALLOW_MS = 120_000L

    @Volatile
    private var allowUntil = 0L

    /** Opens the allow-window for [ms] (only ever extends, never shortens). */
    fun allowFor(ms: Long) {
        if (ms <= 0) return
        val until = SystemClock.uptimeMillis() + ms
        if (until > allowUntil) allowUntil = until
    }

    fun isAllowed(): Boolean =
        SystemClock.uptimeMillis() < allowUntil || ExtensionVerifyGuard.pagesAllowed

    // ------------------------------------------------------------ intents ---

    /**
     * True when [intent] may leave the app. Ours and framework flows always
     * pass; plugin code passes only on a user gesture (or inside the
     * allow-window / master switch).
     */
    fun checkStartActivity(intent: Intent?): Boolean {
        if (intent == null) return false
        if (isAllowed()) return true
        val frames = Thread.currentThread().stackTrace
        if (!hasPluginFrame(frames)) return true
        if (hasGestureFrame(frames)) return true
        Logs.log(
            "Extensions",
            "blocked an external open an extension fired on its own: " +
                intent.toUri(Intent.URI_INTENT_SCHEME).take(220),
        )
        return false
    }

    // ------------------------------------------------------------ windows ---

    /**
     * Wraps [base] so floating windows added through it can be vetted.
     * Activity CONTENT windows pass untouched (that is how every screen,
     * including the globe-button WebView, draws); only floating windows —
     * dialogs, popups — are ever inspected, and our own code always passes
     * before any inspection runs.
     */
    fun wrapWindowManager(base: WindowManager?): WindowManager? {
        if (base == null) return null
        if (Proxy.isProxyClass(base.javaClass)) return base
        val handler = InvocationHandler { _, method, args ->
            if (method.name == "addView" && args != null && args.size == 2) {
                val view = args[0] as? View
                val params = args[1] as? ViewGroup.LayoutParams
                if (shouldBlockAdd(view, params)) return@InvocationHandler null
            }
            try {
                method.invoke(base, *(args ?: emptyArray()))
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.cause ?: e
            }
        }
        return Proxy.newProxyInstance(
            base.javaClass.classLoader ?: WindowManager::class.java.classLoader,
            arrayOf(WindowManager::class.java),
            handler,
        ) as WindowManager
    }

    private fun shouldBlockAdd(view: View?, params: ViewGroup.LayoutParams?): Boolean {
        if (view == null) return false
        val type = (params as? WindowManager.LayoutParams)?.type
            ?: WindowManager.LayoutParams.TYPE_APPLICATION
        // Activity content is how screens draw — never a popup.
        if (type == WindowManager.LayoutParams.TYPE_BASE_APPLICATION) return false
        if (isAllowed()) return false
        val frames = Thread.currentThread().stackTrace
        // Our code (or pure framework work like a fragment transaction our code
        // committed) always passes — only extension-driven windows are vetted.
        if (!hasPluginFrame(frames)) return false
        // A tap driving plugin UI is the user, not an auto-open.
        if (hasGestureFrame(frames)) return false
        val reason = popupReason(view) ?: return false
        Logs.log(
            "Extensions",
            "closed a popup an extension opened on its own ($reason)",
        )
        return true
    }

    // --------------------------------------------------------- frame walk ---

    /** Class prefixes that are never extension code. */
    private fun isNeutral(className: String): Boolean =
        className.startsWith("java.") ||
            className.startsWith("kotlin.") ||
            className.startsWith("kotlinx.") ||
            className.startsWith("android.") ||
            className.startsWith("androidx.") ||
            className.startsWith("com.android.") ||
            className.startsWith("dalvik.") ||
            className.startsWith("jdk.") ||
            className.startsWith("sun.") ||
            // Our own guard + the Activity overrides that call into it.
            className.startsWith("com.hikari.app.net.ExtensionUiGuard") ||
            // The jar bridge is ours as much as com.hikari is: plugin code
            // calling through it still shows its own frame below, which is
            // what [hasPluginFrame] convicts on.
            className.startsWith("com.lagradost.cloudstream3.")

    /** True when extension code sits anywhere on this stack. */
    private fun hasPluginFrame(frames: Array<StackTraceElement>): Boolean {
        var i = 0
        // Stack-request machinery.
        while (i < frames.size && frames[i].className == "java.lang.Thread") i++
        // This guard's own frames (checkStartActivity / the proxy handler).
        while (i < frames.size && frames[i].className.startsWith("com.hikari.app.net.ExtensionUiGuard")) i++
        // The override that called in (our activity's startActivity /
        // getSystemService): it is com.hikari code by construction and must
        // not decide the scan — its CALLER does.
        while (i < frames.size && frames[i].className.startsWith("com.hikari.") &&
            (frames[i].methodName == "startActivity" || frames[i].methodName == "getSystemService")
        ) i++
        // The first frame that is neither framework nor ours/jar decides:
        // our code above plugin code is still plugin-driven.
        while (i < frames.size) {
            val name = frames[i].className
            if (name.startsWith("com.hikari.")) return false
            if (!isNeutral(name)) return true
            i++
        }
        return false
    }

    /**
     * True when a tap/click/menu gesture is driving this stack — i.e. the user
     * did this, as opposed to a background resolve firing on its own.
     */
    private fun hasGestureFrame(frames: Array<StackTraceElement>): Boolean {
        for (f in frames) {
            when (f.methodName) {
                "performClick", "performLongClick", "performContextClick",
                "onTouchEvent", "onMenuItemClick", "onItemClick",
                "onClick", "onLongClick",
                -> {
                    val c = f.className
                    // Only framework-delivered gestures count — a plugin calling
                    // its own onClick() method changes nothing.
                    if (c.startsWith("android.view.") || c.startsWith("android.widget.") ||
                        c.startsWith("androidx.") || c.startsWith("com.android.internal.view.menu") ||
                        c.startsWith("com.google.android.material.")
                    ) return true
                }
            }
        }
        return false
    }

    // ------------------------------------------------------ content scan ---

    /** Words that make a floating window an extension's verification screen. */
    private val VERIFY_WORDS = Regex(
        "TURNSTILE|CLOUDFLARE|CAPTCHA|CHALLENGE|VERIF|SOLVER|SECURITY CHECK|ARE YOU HUMAN|JUST A MOMENT|ONE MOMENT",
        RegexOption.IGNORE_CASE,
    )

    /** Words that make one a funding/promo screen. Matches are deliberately
     *  narrow: a bare "support" appears in innocent UI ("subtitle support"),
     *  so it only counts beside a money/community marker. */
    private val FUND_WORDS = Regex(
        "DONAT|PATREON|KO[-_ ]?FI|BUY[-_ ]?ME|PAYPAL|PREMIUM|MEMBERSHIP|SUPPORT_US",
        RegexOption.IGNORE_CASE,
    )
    private val FUND_COMPANION = Regex(
        "SUPPORT|DEVELOPER|TELEGRAM|DISCORD|T\\.ME|JOIN US|JOIN OUR|FOLLOW US",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Why this floating window must not open, or null when it may. A WebView
     * in an extension-driven popup is a verification page until proven
     * otherwise (extensions have no legitimate floating WebView); otherwise
     * the visible text decides, with the same narrow promo rule as above.
     */
    private fun popupReason(root: View): String? {
        // Bounded walk: popups are small, and a hostile view tree must not
        // hang the UI thread.
        val queue = ArrayDeque<View>()
        queue.add(root)
        var seen = 0
        val texts = StringBuilder()
        while (queue.isNotEmpty() && seen < 200) {
            val v = queue.removeFirst()
            seen++
            if (v is WebView) {
                val url = runCatching { v.url }.getOrNull().orEmpty()
                return "floating WebView" + (if (url.isNotBlank()) " (" + url.take(120) + ")" else "")
            }
            if (v is TextView) {
                val t = runCatching { v.text }.getOrNull()?.toString()
                if (!t.isNullOrBlank()) {
                    if (texts.length < 2000) texts.append(t).append('\n')
                }
                val h = runCatching { v.hint }.getOrNull()?.toString()
                if (!h.isNullOrBlank()) {
                    if (texts.length < 2000) texts.append(h).append('\n')
                }
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    runCatching { v.getChildAt(i) }?.getOrNull()?.let { queue.add(it) }
                }
            }
        }
        if (texts.isEmpty()) return null
        val body = texts.toString()
        if (VERIFY_WORDS.containsMatchIn(body)) return "verification wording"
        val fund = FUND_WORDS.containsMatchIn(body)
        if (fund && FUND_COMPANION.containsMatchIn(body)) return "funding wording"
        // The bare word alone ("Donate" with nothing else) is still a funding
        // screen — the companion rule only narrows "support".
        if (Regex("DONAT|PATREON|KO[-_ ]?FI|BUY[-_ ]?ME A COFFEE|PAYPAL\\.ME", RegexOption.IGNORE_CASE)
            .containsMatchIn(body)
        ) return "funding wording"
        return null
    }
}

/**
 * Mixin for our activities + application: routes floating-window adds and
 * external intents through [ExtensionUiGuard]. Call from the overrides:
 *
 *     override fun getSystemService(name: String): Any? {
 *         val svc = super.getSystemService(name)
 *         return if (name == Context.WINDOW_SERVICE) ExtensionUiGuard.wrapWindowManager(svc as? WindowManager)
 *         else svc
 *     }
 *     override fun startActivity(intent: Intent) {
 *         if (ExtensionUiGuard.checkStartActivity(intent)) super.startActivity(intent)
 *     }
 *     override fun startActivity(intent: Intent, options: Bundle?) {
 *         if (ExtensionUiGuard.checkStartActivity(intent)) super.startActivity(intent, options)
 *     }
 */
object ExtensionUiGuardMixins {
    fun windowService(name: String, svc: Any?): Any? =
        if (name == Context.WINDOW_SERVICE) {
            ExtensionUiGuard.wrapWindowManager(svc as? WindowManager) ?: svc
        } else {
            svc
        }

    fun startAllowed(intent: Intent?): Boolean = ExtensionUiGuard.checkStartActivity(intent)

    fun startAllowed(intent: Intent?, @Suppress("UNUSED_PARAMETER") options: Bundle?): Boolean =
        ExtensionUiGuard.checkStartActivity(intent)
}
