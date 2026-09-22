package eu.kanade.tachiyomi.network

import android.content.Context
import com.hikari.app.net.ExtensionCloudflareInterceptor
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
 * The Cloudflare gap is CLOSED here now. An extension whose site sits behind
 * Cloudflare has no way through on its own — AnimeOnline.Ninja's own source
 * says it plainly ("let CloudflareInterceptor solve it"), because Aniyomi,
 * Tadami and Nekoread all hand extensions a client that clears the challenge in
 * a hidden WebView. Hikari's client did not, so those extensions failed with
 * "Home failed: HTTP error 403" while everything unprotected kept working.
 * [ExtensionCloudflareInterceptor] is that missing piece: it reuses a clearance
 * the user's globe-button verification already earned, and otherwise solves the
 * challenge once per host in an offscreen WebView ([CloudflareSolver]) and
 * retries — silently, with nothing opening on screen, and with the host
 * recorded in [CloudflareVerifier] if even that fails so the globe button
 * remains the fallback.
 *
 * The interceptor order follows Aniyomi's, and two of those positions are
 * required BY NAME by extension-lib: `UncaughtExceptionInterceptor` must be
 * first, `UserAgentInterceptor` must be present (it is what puts the default UA
 * on a request that has none — and the UA the clearance gets minted for).
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
        .addInterceptor(ExtensionCloudflareInterceptor(::defaultUserAgentProvider))

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
         * A CURRENT Chrome-on-Android UA. Two things depend on it: sources that
         * simply reject anything not browser-shaped, and Cloudflare, whose
         * `cf_clearance` is bound to the UA it was minted for — so this is also
         * the UA [CloudflareSolver] makes its WebView advertise. A stale
         * browser number is itself a bot signal to the managed-challenge rules,
         * which is why this is kept a recent Chrome rather than any old one.
         *
         * Extensions that need something else put their own "User-Agent" on the
         * request (or override `headersBuilder`); [UserAgentInterceptor] only
         * fills in a default when the request has none — and when they do that,
         * the solver uses THEIR UA for the solve, keeping the pair consistent.
         */
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/141.0.0.0 Mobile Safari/537.36"
    }
}
