package org.hany18h.parsers.site.mangareader.ar

import org.hany18h.parsers.MangaLoaderContext
import org.hany18h.parsers.MangaSourceParser
import org.hany18h.parsers.model.MangaParserSource
import org.hany18h.parsers.site.mangareader.MangaReaderParser

@MangaSourceParser("DESPAIRMANGA", "Despair Manga", "ar")
internal class DespairManga(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.DESPAIRMANGA, "despair-manga.net", pageSize = 20, searchPageSize = 10)
