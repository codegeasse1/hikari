package com.hikari.app.net

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import com.hikari.app.data.Logs

/**
 * Keeps EXTENSIONS from opening external pages on their own — the enforcement
 * behind "nothing opens unless the user taps it".
 *
 * [ExtensionVerifyGuard] already kills the two mechanisms extensions were seen
 * using (their pref-gated Cloudflare WebViews, and their verification/funding
 * `DialogFragment`s — see `MainActivity.dismissExtensionPopup`). What this
 * adds is the third: an `ACTION_VIEW` browser intent (or a plugin's own
 * Activity) fired from a background thread in the middle of resolving links —
 * the "a Cloudflare page / promo page opened by itself" report. It is checked
 * by our own `startActivity` overrides before anything leaves the app.
 *
 * The rule is gesture-based: code running WITHOUT any user gesture on the
 * stack (a background resolve, a timer, a pref change) may not fire an
 * external intent at all; the moment a tap/click/menu gesture is on the stack
 * it is the user driving and everything passes. That keeps every legitimate
 * flow working — the globe button (our own code in any case), a plugin
 * settings sheet the user opened, an OAuth "log in via browser" button
 * inside it — while background auto-opens die with a log line.
 *
 * The allow-window ([allowFor]) covers the one case gestures cannot: our code
 * deliberately invoking plugin UI whose follow-on work lands later on a bare
 * handler. It is set only from our own call sites
 * (`Cs3PluginManager.openSettings`), and the master switch (Settings →
 * "Open their verification pages") lifts the whole guard via
 * [ExtensionVerifyGuard.pagesAllowed] for the extension that only works
 * through its own bypass screen.
 *
 * Deliberately NOT a WindowManager proxy: Android's `Dialog` construction
 * casts the context's WindowManager back to the framework implementation, so
 * a proxy there crashes EVERY dialog in the app (the launch-with-app-lock
 * `ClassCastException`). Floating-window popups stay covered by the
 * DialogFragment dismissal plus the preference forcing in
 * [ExtensionVerifyGuard].
 */
object ExtensionUiGuard {

    /** How long a deliberate plugin-UI entry keeps follow-on opens allowed. */
    const val SETTINGS_ALLOW_MS = 120_000L

    /**
     * Marks the current thread's next [checkStartActivity] as ours.
     *
     * Our own taps (Compose screens and dialogs) were convicted by the
     * stack scan below: a library frame sits between the Activity override
     * and our code on the real device (log-proven:
     * `convict=com.google.common.base.Ascii.checkStartActivity`), so the
     * "first frame decides" rule blamed a library instead of our caller and
     * every browser tap died silently. Stamping our own launches is
     * deterministic — no inference. Our helpers ([launch] in ExternalLinks
     * and the rating-page fallback) wrap their `startActivity` in this; a
     * background plugin fire never carries the stamp and is still scanned.
     * Thread-local and synchronous: the launch happens inside [block] on the
     * same thread, so no flag ever leaks to another tap.
     */
    private val trusted = ThreadLocal.withInitial { false }

    /** Runs [block] with this thread's launches stamped as ours (see above). */
    fun <T> runTrusted(block: () -> T): T {
        trusted.set(true)
        try {
            return block()
        } finally {
            trusted.set(false)
        }
    }

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

    /**
     * True when [intent] may leave the app. Ours and framework flows always
     * pass; plugin code passes only on a user gesture (or inside the
     * allow-window / master switch).
     */
    fun checkStartActivity(intent: Intent?): Boolean {
        if (intent == null) return false
        // Stamped by our own launch helpers (see [runTrusted]): a tap from
        // our own UI, always allowed — no stack inference involved.
        if (trusted.get() == true) return true
        // Internal navigation is never an "external open": an explicit intent
        // into one of our own activities (player, web view, settings...) always
        // passes. The abuse this guard targets is extensions firing the OUTSIDE
        // world (browser intents, their own activities) on their own — our own
        // screens opening our own player/view is the app itself working, and
        // blocking it is the whole "telegram videos, webview sites unclickable"
        // class (the tap then dies silently inside runCatching with no toast).
        val componentClass = runCatching { intent.component?.className }.getOrNull()
        if (componentClass != null && componentClass.startsWith("com.hikari.app.")) return true
        if (isAllowed()) return true
        val frames = Thread.currentThread().stackTrace
        if (!hasPluginFrame(frames)) return true
        if (hasGestureFrame(frames)) return true
        Logs.log(
            "Extensions",
            "blocked an external open an extension fired on its own: " +
                intent.toUri(Intent.URI_INTENT_SCHEME).take(220) +
                " convict=" + (convictFrame(frames) ?: "unknown"),
        )
        return false
    }

    /** Class prefixes that are never extension code: the framework, Kotlin, and
     *  every third-party library the app ships (networking, JSON, image
     *  loading, Guava...). A library frame between the Activity override and
     *  our caller must be SKIPPED, never convicted — convicting one is what
     *  turned every tracker browser tap into a silent no-op
     *  (`convict=com.google.common.base.Ascii`). */
    private fun isNeutral(className: String): Boolean =
        className.startsWith("java.") ||
            className.startsWith("javax.") ||
            className.startsWith("kotlin.") ||
            className.startsWith("kotlinx.") ||
            className.startsWith("android.") ||
            className.startsWith("androidx.") ||
            className.startsWith("com.android.") ||
            className.startsWith("com.google.") ||
            className.startsWith("com.squareup.") ||
            className.startsWith("com.bumptech.") ||
            className.startsWith("com.github.") ||
            className.startsWith("coil.") ||
            className.startsWith("okhttp3.") ||
            className.startsWith("okio.") ||
            className.startsWith("retrofit2.") ||
            className.startsWith("org.") ||
            className.startsWith("io.") ||
            className.startsWith("net.") ||
            className.startsWith("com.sun.") ||
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
        // This guard's own frames.
        while (i < frames.size && frames[i].className.startsWith("com.hikari.app.net.ExtensionUiGuard")) i++
        // The override that called in (our activity's startActivity): it is
        // com.hikari code by construction and must not decide the scan — its
        // CALLER does.
        while (i < frames.size && frames[i].className.startsWith("com.hikari.") &&
            frames[i].methodName == "startActivity"
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

    /** The frame [hasPluginFrame] convicted, for the block log — so the next
     *  "why did my tap die" report names the class instead of guessing. */
    private fun convictFrame(frames: Array<StackTraceElement>): String? {
        var i = 0
        while (i < frames.size && frames[i].className == "java.lang.Thread") i++
        while (i < frames.size && frames[i].className.startsWith("com.hikari.app.net.ExtensionUiGuard")) i++
        while (i < frames.size && frames[i].className.startsWith("com.hikari.") &&
            frames[i].methodName == "startActivity"
        ) i++
        while (i < frames.size) {
            val name = frames[i].className
            if (name.startsWith("com.hikari.")) return null
            if (!isNeutral(name)) return name + "." + frames[i].methodName
            i++
        }
        return null
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
}

/**
 * Mixin for our activities + application: routes external intents through
 * [ExtensionUiGuard]. Call from the overrides:
 *
 *     override fun startActivity(intent: Intent) {
 *         if (ExtensionUiGuardMixins.startAllowed(intent)) super.startActivity(intent)
 *     }
 *     override fun startActivity(intent: Intent, options: Bundle?) {
 *         if (ExtensionUiGuardMixins.startAllowed(intent, options)) super.startActivity(intent, options)
 *     }
 */
object ExtensionUiGuardMixins {
    fun startAllowed(intent: Intent?): Boolean = ExtensionUiGuard.checkStartActivity(intent)

    fun startAllowed(intent: Intent?, @Suppress("UNUSED_PARAMETER") options: Bundle?): Boolean =
        ExtensionUiGuard.checkStartActivity(intent)
}
