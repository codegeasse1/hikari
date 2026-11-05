package org.hany18h.parsers.site.madara.ar

import okhttp3.Headers
import org.hany18h.parsers.MangaLoaderContext
import org.hany18h.parsers.MangaSourceParser
import org.hany18h.parsers.config.ConfigKey
import org.hany18h.parsers.model.MangaParserSource
import org.hany18h.parsers.network.UserAgents
import org.hany18h.parsers.site.madara.MadaraParser

@MangaSourceParser("MANGALEK", "LekManga", "ar")
internal class LekManga(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANGALEK, "lekmanga.site", pageSize = 20) {
	override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_MOBILE)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(ConfigKey.InterceptCloudflare(defaultValue = true))
	}

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("User-Agent", config[userAgentKey])
		.add("Referer", "https://$domain/")
		.add("Accept-Language", "ar,en-US;q=0.8,en;q=0.7")
		.build()

	// The site's admin-ajax endpoint returns a stale ten-item window. Its public
	// paged search is current and includes newly published works.
	override val withoutAjax = true
}
