package org.hany18h.parsers.site.zeistmanga.ar

import org.hany18h.parsers.MangaLoaderContext
import org.hany18h.parsers.MangaSourceParser
import org.hany18h.parsers.model.MangaParserSource
import org.hany18h.parsers.model.MangaTag
import org.hany18h.parsers.site.zeistmanga.ZeistMangaParser
import org.hany18h.parsers.util.mapToSet
import org.hany18h.parsers.util.parseHtml
import org.hany18h.parsers.util.requireElementById

@MangaSourceParser("MANGAAILAND", "MangaAiLand", "ar")
internal class MangaAiLand(context: MangaLoaderContext) :
	ZeistMangaParser(context, MangaParserSource.MANGAAILAND, "manga-ai-land.blogspot.com") {
	override val sateOngoing: String = "مستمر"
	override val sateFinished: String = "مكتملة"
	override val sateAbandoned: String = "متوقفة"

	override suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain").parseHtml()
		return doc.requireElementById("LinkList1").select("ul li a").mapToSet {
			MangaTag(
				key = it.attr("href").substringBefore("?").substringAfterLast('/'),
				title = it.text(),
				source = source,
			)
		}
	}
}
