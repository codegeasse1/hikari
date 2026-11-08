package com.hikari.app.net

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import com.hikari.app.data.Logs

object ExtensionUiGuard {

    /** How long a deliberate plugin-UI entry keeps follow-on opens allowed. */
    const val SETTINGS_ALLOW_MS = 120_000L

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

    fun checkStartActivity(intent: Intent?): Boolean {
        if (intent == null) return false
        // Stamped by our own launch helpers (see [runTrusted]): a tap from
        // our own UI, always allowed — no stack inference involved.
        if (trusted.get() == true) return true
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
            className.startsWith("com.lagradost.cloudstream3.")

    /** True when extension code sits anywhere on this stack. */
    private fun hasPluginFrame(frames: Array<StackTraceElement>): Boolean {
        var i = 0
        // Stack-request machinery.
        while (i < frames.size && frames[i].className == "java.lang.Thread") i++
        // This guard's own frames.
        while (i < frames.size && frames[i].className.startsWith("com.hikari.app.net.ExtensionUiGuard")) i++
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

object ExtensionUiGuardMixins {
    fun startAllowed(intent: Intent?): Boolean = ExtensionUiGuard.checkStartActivity(intent)

    fun startAllowed(intent: Intent?, @Suppress("UNUSED_PARAMETER") options: Bundle?): Boolean =
        ExtensionUiGuard.checkStartActivity(intent)
}
