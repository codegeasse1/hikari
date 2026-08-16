package com.hikari.app

import android.app.Application
import android.content.Context
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

class HikariApp : Application() {

    companion object {
        lateinit var instance: HikariApp
            private set
    }

    lateinit var store: AppStore
        private set
    lateinit var providers: ProviderManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        initCloudStream(this)
        store = AppStore(this)
        providers = ProviderManager(store)
        Http.init()
        CoroutineScope(Dispatchers.IO).launch { providers.refresh() }
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
            // Wire up the real okhttp client (redirects, 50MiB cache, optional
            // SSL-ignore) exactly like CloudStream's buildDefaultClient.
            fun build(ignoreSSL: Boolean) = OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
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
