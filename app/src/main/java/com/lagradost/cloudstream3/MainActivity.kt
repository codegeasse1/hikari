package com.lagradost.cloudstream3

import android.content.Intent
import android.os.Bundle

/**
 * The `com.lagradost.cloudstream3.MainActivity` shadow.
 *
 * CloudStream plugins name this class when they want to hand the user to "the
 * app" — `startActivity(Intent(context, MainActivity::class.java))`, or just
 * `MainActivity::class.java` in code that builds such an intent. Hikari is not
 * CloudStream and had no such class, so ART failed the resolution and the
 * `NoClassDefFoundError` escaped through the plugin's own callback; the crash
 * report from a user with the CineStream plugin shows exactly that, thrown from
 * a plugin's settings dialog on the **main thread** — which kills the process
 * (see HikariApp.installCrashHandler).
 *
 * This is the same "shadow" treatment the jar's WebViewResolver,
 * CloudflareKiller, CloudStreamApp and ToastBinding already get: the class
 * exists, it links, and it does the harmless thing. Since it is only ever asked
 * for to OPEN the app, it forwards the user to Hikari's own main screen and gets
 * out of the way. It is declared in the manifest (transparent, no title bar) so
 * a real `startActivity` resolves instead of throwing
 * ActivityNotFoundException, which would be the same crash one step later.
 */
class MainActivity : android.app.Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            startActivity(
                Intent(this, com.hikari.app.MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
        }
        finish()
    }
}
