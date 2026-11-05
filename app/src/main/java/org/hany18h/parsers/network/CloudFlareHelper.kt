package org.hany18h.parsers.network

import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.Jsoup
import java.net.HttpURLConnection.HTTP_FORBIDDEN
import java.net.HttpURLConnection.HTTP_UNAVAILABLE
import java.util.Locale

public object CloudFlareHelper {

	public const val PROTECTION_NOT_DETECTED: Int = 0
	public const val PROTECTION_CAPTCHA: Int = 1
	public const val PROTECTION_BLOCKED: Int = 2

	private const val CF_CLEARANCE = "cf_clearance"

	public fun checkResponseForProtection(response: Response): Int {
		if (response.code != HTTP_FORBIDDEN && response.code != HTTP_UNAVAILABLE) {
			return PROTECTION_NOT_DETECTED
		}
		val content = try {
			response.peekBody(Long.MAX_VALUE).use {
				Jsoup.parse(it.byteStream(), Charsets.UTF_8.name(), response.request.url.toString())
			}
		} catch (_: IllegalStateException) {
			return PROTECTION_NOT_DETECTED
		}
		val title = content.title().trim().lowercase(Locale.ROOT)
		val bodyText = content.body()?.text().orEmpty().lowercase(Locale.ROOT)
		val html = content.html().lowercase(Locale.ROOT)
		return when {
			content.selectFirst(
				"h2[data-translate=\"blocked_why_headline\"], " +
					"#cf-error-details, .cf-error-details, [data-translate=\"error\"], " +
					".cf-error-code",
			) != null ||
				"sorry, you have been blocked" in bodyText ||
				"error 1020" in bodyText ||
				"access denied" in bodyText && response.header("Server").equals("cloudflare", ignoreCase = true) ->
				PROTECTION_BLOCKED

			content.selectFirst(
				"#challenge-form, #challenge-running, #cf-challenge-running, " +
					"#challenge-error-title, #challenge-error-text, " +
					"script[src*=\"/cdn-cgi/challenge-platform/\"], " +
					"input[name=\"cf-turnstile-response\"]",
			) != null ||
				"/cdn-cgi/challenge-platform/" in html ||
				"window._cf_chl_opt" in html ||
				"cf-turnstile-response" in html ||
				("just a moment" in title && "cloudflare" in html) ->
				PROTECTION_CAPTCHA

			else -> PROTECTION_NOT_DETECTED
		}
	}

	public fun getClearanceCookie(cookieJar: CookieJar, url: String): String? {
		return cookieJar.loadForRequest(url.toHttpUrl()).find { it.name == CF_CLEARANCE }?.value
	}

	public fun isCloudFlareCookie(name: String): Boolean {
		return name.startsWith("cf_")
			|| name.startsWith("_cf")
			|| name.startsWith("__cf")
			|| name == "csrftoken"
	}
}
