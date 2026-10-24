package org.hany18h.parsers.site.mangareader.ar

import org.hany18h.parsers.MangaLoaderContext
import org.hany18h.parsers.MangaSourceParser
import org.hany18h.parsers.model.MangaListFilterCapabilities
import org.hany18h.parsers.model.MangaParserSource
import org.hany18h.parsers.site.mangareader.MangaReaderParser

@MangaSourceParser("UMIMANGA", "UmiManga", "ar")
internal class UmiManga(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.UMIMANGA, "www.umimanga.com", pageSize = 20, searchPageSize = 10) {
	override val filterCapabilities: MangaListFilterCapabilities
		get() = super.filterCapabilities.copy(
			isTagsExclusionSupported = false,
		)
}
