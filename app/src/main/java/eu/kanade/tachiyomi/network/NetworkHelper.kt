package eu.kanade.tachiyomi.network

import android.content.Context
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The OkHttp stack every Aniyomi extension talks to (`AnimeHttpSource.network`,
 * handed out through `Injekt.get<NetworkHelper>()`).
 *
 * Hikari's copy keeps Aniyomi's public surface — [client], [cloudflareClient],
 * [defaultUserAgentProvider] — with a single shared client, the WebView cookie
 * jar, a 5 MiB HTTP cache and a browser User-Agent (plenty of sources reject
 * anything else, and it is also what `headersBuilder()` puts on every request).
 *
 * The one deliberate omission is Aniyomi's CloudflareInterceptor (its
 * WebView-driven challenge solver): Hikari has its own WebView verification flow
 * for CloudStream plugins, and wiring it into this client is a follow-up rather
 * than something to fake here.
 */
class NetworkHelper(private val context: Context) {

    val cookieJar = AndroidCookieJar()

    private val clientBuilder: OkHttpClient.Builder = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.MINUTES)
        .cache(Cache(File(context.cacheDir, "aniyomi_network_cache"), 5L * 1024 * 1024))
        .addInterceptor(UncaughtExceptionInterceptor())
        .addInterceptor(UserAgentInterceptor(::defaultUserAgentProvider))

    val client: OkHttpClient = clientBuilder
        .addNetworkInterceptor(
            HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC },
        )
        .build()

    @Deprecated("The regular client handles Cloudflare by default")
    @Suppress("UNUSED")
    val cloudflareClient: OkHttpClient = client

    fun defaultUserAgentProvider(): String = DEFAULT_USER_AGENT

    companion object {
        /**
         * A desktop-ish Chrome UA. Extensions that need something else put their
         * own "User-Agent" on the request (or override `headersBuilder`), and
         * [UserAgentInterceptor] only fills in a default when the request has
         * none.
         */
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/122.0.0.0 Mobile Safari/537.36"
    }
}
