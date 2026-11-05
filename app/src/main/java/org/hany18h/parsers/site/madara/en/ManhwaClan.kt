package org.hany18h.parsers.site.madara.en

import org.hany18h.parsers.MangaLoaderContext
import org.hany18h.parsers.MangaSourceParser
import org.hany18h.parsers.model.MangaParserSource
import org.hany18h.parsers.site.madara.MadaraParser

@MangaSourceParser("MANHWACLAN", "ManhwaClan", "en")
internal class ManhwaClan(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANHWACLAN, "manhwaclan.com", pageSize = 10) {
	override val datePattern = "MMMM dd, yyyy"
}
