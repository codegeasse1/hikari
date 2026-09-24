package eu.kanade.tachiyomi.network.interceptor

import android.content.Context
import com.hikari.app.net.ExtensionCloudflareInterceptor
import eu.kanade.tachiyomi.network.NetworkHelper

/**
 * The interceptor extension sources REQUIRE, by name, to be in the client they
 * are handed — and the reason this class exists at all.
 *
 * Newer extensions (every source built on the keiyoushi multisrc families, and
 * anything built on extension-lib 1.6) build their own client out of the one
 * the app gives them, and before using it they ASSERT what is in it. From the
 * extension side, verbatim:
 *
 * ```
 * check(interceptors().any { it.javaClass.simpleName == "UncaughtExceptionInterceptor" }) {
 *     "UncaughtExceptionInterceptor must be present in default client"
 * }
 * check(interceptors().any { it.javaClass.simpleName == "UserAgentInterceptor" }) {
 *     "UserAgentInterceptor must be present in default client"
 * }
 * check(interceptors().any { it.javaClass.simpleName == "CloudflareInterceptor" }) {
 *     "CloudflareInterceptor must be present in default client"
 * }
 * ```
 *
 * The check is on the class's SIMPLE NAME, not on a type from the library —
 * extension-lib does not contain these interceptor classes at all, so an
 * extension cannot reference them; it can only ask whether an object it was
 * handed calls itself one of those. That is also why this class can live in
 * Hikari's own tree and still satisfy the check: the name is what matters.
 *
 * Hikari's client satisfied two of the three. Its Cloudflare handling is
 * [ExtensionCloudflareInterceptor] — the same behaviour under a different name —
 * so the third assertion threw `IllegalStateException("CloudflareInterceptor
 * must be present in default client")` the first time such an extension touched
 * its `client`, i.e. on the very first request of its first catalog. Hikari then
 * showed an empty catalog: no titles, no Cloudflare page, and nothing on screen
 * to explain it — for a whole family of extensions at once (the "many manhwa
 * extensions show no catalog while the same extension works in
 * Nekoread/Mihon" report; Nekoread's own NetworkHelper documents this same
 * assertion and the UncaughtExceptionInterceptor half of it).
 *
 * So this is a real interceptor doing exactly what Hikari's own does, under the
 * one name extensions insist on. [NetworkHelper] installs it.
 */
class CloudflareInterceptor(
    @Suppress("UNUSED_PARAMETER") context: Context,
    defaultUserAgentProvider: () -> String = { NetworkHelper.DEFAULT_USER_AGENT },
) : ExtensionCloudflareInterceptor(defaultUserAgentProvider)
