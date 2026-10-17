package com.lagradost.cloudstream3

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.utils.Event
import java.io.File

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
 *
 * ## The [companion] below is load-bearing for the same reason, one layer deeper
 *
 * Excluding the jar's `MainActivity$Companion` with the rest of the class left
 * plugins that touch it in a worse state than the class their settings screens
 * already got used to: every plugin access to `MainActivity.Companion.<x>`
 * compiles to `getstatic MainActivity.Companion` FIRST, so the very first one
 * died with
 *
 *     java.lang.NoSuchFieldError: No field Companion of type
 *       Lcom/lagradost/cloudstream3/MainActivity$Companion; in class
 *       Lcom/lagradost/cloudstream3/MainActivity;
 *
 * — thrown from a CloudStream extension's own settings dialog (BingeCloud's
 * `BingeCloudPlugin.load$lambda$0$0` via `Settings.showSettingsDialog`), on the
 * MAIN thread, which is a process kill. The members below mirror the jar's
 * `MainActivity$Companion` exactly, member for member (`javap`-verified against
 * app/libs/cloudstream3.jar): same names, same erasures, and a real
 * `activityResultLauncher` registered by Hikari's own MainActivity (see there)
 * so a plugin that launches an intent gets a working launcher rather than a null
 * dereference one line later.
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

    companion object {

        /**
         * The launcher plugins call to open an intent for a result — a
         * document/folder picker for "import my JSON", most often.
         *
         * Registered on Hikari's real MainActivity (it must be created before
         * that activity starts, which onCreate guarantees) and published here so
         * a plugin's settings dialog can use it. It is a plain
         * StartActivityForResult contract: what the plugin does with the result
         * is its own business, and the picker itself works.
         */
        @Volatile
        var activityResultLauncher: ActivityResultLauncher<Intent>? = null

        /** Text of the last error CloudStream would show in its crash screen. */
        @Volatile
        var lastError: String? = null

        /** A search the app is asked to run when it next comes up. */
        @Volatile
        var nextSearchQuery: String? = null

        /** Temp files a plugin wants removed when the app exits. */
        @Volatile
        var filesToDelete: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

        // The lifecycle events plugins subscribe to (`Event<T>` is the jar's own
        // tiny observer list). Nothing in Hikari publishes on them, but a plugin
        // that registers a handler needs the event object to exist and to
        // accept the handler — and one that only registers is harmless.
        val afterPluginsLoadedEvent = Event<Boolean>()
        val mainPluginsLoadedEvent = Event<Boolean>()
        val afterRepositoryLoadedEvent = Event<Boolean>()
        val bookmarksUpdatedEvent = Event<Boolean>()
        val reloadHomeEvent = Event<Boolean>()
        val reloadLibraryEvent = Event<Boolean>()
        val reloadAccountEvent = Event<Boolean>()

        /**
         * Clears the persisted error note CloudStream keeps at
         * `files/last_error` and forgets the in-memory one. The jar declares
         * this as an overload of `setLastError` taking the context (verified
         * from its class file), so the name is kept for bytecode that calls it —
         * the property setter above is the `String` overload.
         */
        fun setLastError(context: Context) {
            runCatching {
                val errorFile = File(context.filesDir, "last_error")
                if (errorFile.exists() && errorFile.isFile) errorFile.delete()
            }
            lastError = null
        }

        /** Removes [file] now (the jar defers to app exit; a plugin only ever
         *  wants the temp file gone, and deferring it to a process death we do
         *  not control would leave it behind). */
        fun deleteFileOnExit(file: File) {
            runCatching { if (file.exists()) file.delete() }
        }

        /** CloudStream uses this to scroll a focused TV view to the middle of
         *  its parent. Nothing in Hikari's own screens asks for it; a plugin
         *  that does only wants the view brought to the user. */
        fun centerView(view: View) {
            runCatching { view.requestFocus() }
        }

        /**
         * Deep links (`cloudstreamapp://…`, `cloudstreamsearch://…`, a repo
         * URL, a share link) are CloudStream's own navigation vocabulary and
         * have no equivalent in Hikari, so this answers "not handled" instead of
         * pretending — which is what the jar's own callers check for.
         *
         * The parameters after the URL carry defaults ON PURPOSE: plugins call
         * the generated `handleAppIntentUrl$default` bridge when they omit them
         * (the jar's companion has that synthetic method, verified from its
         * class file), and a Kotlin declaration without defaults emits none.
         */
        fun handleAppIntentUrl(
            activity: FragmentActivity,
            url: String,
            isWebview: Boolean = false,
            extraArgs: Bundle? = null,
        ): Boolean {
            runCatching {
                android.util.Log.d("HIKARI", "plugin deep link ignored: $url")
            }
            return false
        }
    }
}
