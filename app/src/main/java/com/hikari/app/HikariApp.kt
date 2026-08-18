package com.hikari.app

import android.app.Application
import android.content.Context
import coil.Coil
import coil.ImageLoader
import com.hikari.app.data.AppStore
import com.hikari.app.net.Http
import com.hikari.app.providers.ProviderManager
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SettingsJson
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ignoreAllSSLErrors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.conscrypt.Conscrypt
import java.io.File
import java.security.Security
import java.util.concurrent.TimeUnit

class HikariApp : Application() {

    companion object {
        lateinit var instance: HikariApp
            private set

        /** Stack trace of the last uncaught crash (shown as a Home banner). */
        @Volatile
        var lastCrash: String? = null
            private set
    }

    lateinit var store: AppStore
        private set
    lateinit var providers: ProviderManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashHandler()
        initCloudStream(this)
        store = AppStore(this)
        providers = ProviderManager(store)
        Http.init()
        setupImageLoader()
        CoroutineScope(Dispatchers.IO).launch {
            // Registering extractor aliases initializes the jar's full extractor
            // registry — do it off the main thread.
            com.hikari.app.cs3.HikariExtractorRegistry.register()
            providers.refresh()
            providers.providers.value
                .filterIsInstance<com.hikari.app.cs3.Cs3MainApiProvider>()
                .forEach { it.warm() }
        }
    }

    /**
     * Never let an uncaught exception (main or background thread) die silently:
     * write the stack to a file, and surface it on the next launch as a banner
     * (see HomeScreen) so crashes get reported instead of guessed at.
     */
    private fun installCrashHandler() {
        runCatching {
            val file = File(cacheDir, "crash.log")
            if (file.exists()) lastCrash = file.readText().take(1600)
        }
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            runCatching {
                val trace = "${t.javaClass.simpleName}: ${t.message}\n" +
                    t.stackTrace.take(12).joinToString("\n") { "    at $it" }
                File(cacheDir, "crash.log").writeText(trace)
                lastCrash = trace
            }
            android.util.Log.e("HikariCrash", "Uncaught on ${thread.name}", t)
        }
    }

    /** Clear the persisted crash banner after the user dismisses it. */
    fun clearCrash() {
        lastCrash = null
        runCatching { File(cacheDir, "crash.log").delete() }
    }

    /**
     * Most provider CDNs refuse to serve posters to a bare okhttp client: they
     * require a browser User-Agent and a same-site Referer (hotlink protection).
     * Coil's default loader sends neither, so every poster 403s into a blank
     * placeholder. Wire a global loader that sends a browser UA plus a Referer
     * derived from the image's own origin.
     */
    private fun setupImageLoader() {
        runCatching {
            val client = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val req = chain.request()
                    val builder = req.newBuilder()
                        .header("User-Agent", Http.UA)
                    // Plugins declare per-poster headers (e.g. LeakPorner's
                    // 58img.top needs `Referer: https://leakporner.org/`). Use
                    // the exact headers when known, else fall back to a
                    // same-origin Referer (hotlink protection).
                    val cs3 = com.hikari.app.cs3.Cs3MainApiProvider
                    val exact = cs3.imageHeaders[req.url.toString()]
                    if (exact != null) {
                        exact.forEach { (k, v) -> builder.header(k, v) }
                    } else {
                        // URL may differ from the recorded one (scheme/query/
                        // params) — apply the Referer the provider declared
                        // for this image host.
                        val hostRef = req.url.host?.let { cs3.imageHostReferers[it.lowercase()] }
                        if (hostRef != null) {
                            builder.header("Referer", hostRef)
                        } else {
                            val host = req.url.host
                            if (host.isNotBlank()) {
                                builder.header("Referer", "${req.url.scheme}://$host/")
                            }
                        }
                    }
                    chain.proceed(builder.build())
                }
                .build()
            val loader = ImageLoader.Builder(this)
                .okHttpClient(client)
                .crossfade(true)
                .diskCache {
                    directory(File(cacheDir, "coil_image_cache"))
                    maxSizeBytes(250L * 1024 * 1024)
                }
                .build()
            Coil.setImageLoader(loader)
        }
    }

    private fun initCloudStream(context: Context) {
        try {
            // CloudStream's buildDefaultClient inserts Conscrypt as the JSSE
            // provider before building okhttp — mirror it so TLS handshakes to
            // the streaming CDNs behave identically.
            try {
                Security.insertProviderAt(Conscrypt.newProvider(), 1)
            } catch (_: Throwable) {
            }

            // Accessing the jar's MainActivityKt initializes its own default
            // nicehttp Requests (jackson responseParser + CloudStream user-agent).
            // Wire up the real okhttp client (redirects, generous timeouts +
            // connection retry exactly like CloudStream's buildDefaultClient,
            // 50MiB cache, optional SSL-ignore) so slow anime sites don't throw
            // on the 10s okhttp defaults.
            fun build(ignoreSSL: Boolean) = OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .apply { if (ignoreSSL) ignoreAllSSLErrors() }
                .cache(Cache(File(context.cacheDir, "http_cache"), 50L * 1024 * 1024))
                .build()

            val kt = Class.forName("com.lagradost.cloudstream3.MainActivityKt")
            fun wire(getter: String, ignoreSSL: Boolean) {
                val req = kt.getMethod(getter).invoke(null) as Requests
                req.baseClient = build(ignoreSSL)
            }
            wire("getApp", ignoreSSL = false)
            wire("getInsecureApp", ignoreSSL = true)
            MainAPI.settingsForProvider = SettingsJson()

            // Warm the 810-extractor registry (constructs every built-in
            // extractor, loading newpipe/cryptography/ksoup classes) on a
            // background thread so the first "load sources" click is instant
            // and any initialization failure surfaces as a caught error
            // instead of a silent hang on first play.
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    Class.forName("com.lagradost.cloudstream3.utils.ExtractorApiKt")
                } catch (t: Throwable) {
                    android.util.Log.e("HikariApp", "extractor registry init failed", t)
                }
            }
        } catch (t: Throwable) {
            android.util.Log.e("HikariApp", "CloudStream runtime init failed", t)
        }
    }
}
