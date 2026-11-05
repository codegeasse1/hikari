package org.hany18h.parsers.site.madara.en

import org.hany18h.parsers.MangaLoaderContext
import org.hany18h.parsers.MangaSourceParser
import org.hany18h.parsers.model.MangaParserSource
import org.hany18h.parsers.site.madara.MadaraParser

@MangaSourceParser("MANHUAUS", "ManhuaUs", "en")
internal class Manhuaus(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANHUAUS, "manhuaus.com")
