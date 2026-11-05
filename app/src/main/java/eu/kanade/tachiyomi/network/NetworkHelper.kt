package eu.kanade.tachiyomi.network

import android.content.Context
import com.hikari.app.HikariApp
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
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
        // Named `CloudflareInterceptor` ON PURPOSE, and it must stay that name:
        // extension-lib 1.6 sources assert, by class simple name, that the client
        // they are handed contains one — see [CloudflareInterceptor]. Hikari's own
        // [com.hikari.app.net.ExtensionCloudflareInterceptor] is the same
        // behaviour, but the assertion looks for the shorter name, so installing
        // that one instead left EVERY extension in that family throwing
        // "CloudflareInterceptor must be present in default client" on its first
        // request — an empty catalog with no visible reason.
        .addInterceptor(CloudflareInterceptor(context, ::defaultUserAgentProvider))

    val client: OkHttpClient = clientBuilder
        .addNetworkInterceptor(
            HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC },
        )
        .build()

    @Deprecated("The regular client handles Cloudflare by default")
    @Suppress("UNUSED")
    val cloudflareClient: OkHttpClient = client

    /**
     * The UA every extension request presents when it sets none.
     *
     * This is Hikari's EFFECTIVE WEBVIEW UA, not a hard-coded browser string, and
     * that is the point: a Cloudflare `cf_clearance` is minted for the exact UA
     * the WebView presented, and it is rejected the moment a request advertises a
     * different one. The clearance the user earns with the globe button is earned
     * by a WebView, so the requests that follow must look like that same browser
     * or the site simply challenges them again — the failure the user reported as
     * "I verified the site and it still says I need to verify". A device's WebView
     * UA is also an honest one (it is what the engine on this phone really is),
     * and it is still a Chrome-shaped mobile UA, which is all the sites that
     * sniff for a browser need.
     *
     * It is read live rather than captured, because the user can change it in
     * Settings (and Android updates the WebView's own version over time) — a new
     * UA means new clearances, which is unavoidable, but at least every part of
     * the app agrees on which one is in force.
     */
    fun defaultUserAgentProvider(): String = runCatching {
        HikariApp.instance.effectiveWebViewUa()
    }.getOrNull().orEmpty().ifBlank { DEFAULT_USER_AGENT }

    companion object {
        /**
         * The fallback UA, used only when the app's WebView UA cannot be read
         * (before [HikariApp] exists). A CURRENT Chrome-on-Android UA: sources
         * that simply reject anything not browser-shaped need one, and a stale
         * browser number is itself a bot signal to the managed-challenge rules.
         *
         * See [defaultUserAgentProvider] for why the live WebView UA — not this —
         * is what requests normally carry: a `cf_clearance` is bound to the UA it
         * was minted for.
         *
         * Extensions that need something else put their own "User-Agent" on the
         * request (or override `headersBuilder`); [UserAgentInterceptor] only
         * fills in a default when the request has none — and when they do that,
         * the solver uses the WebView UA for the solve and the request that
         * follows carries the clearance under that same UA.
         */
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/141.0.0.0 Mobile Safari/537.36"
    }
}
