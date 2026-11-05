package org.hany18h.parsers.site.zeistmanga.ar

import org.hany18h.parsers.MangaLoaderContext
import org.hany18h.parsers.MangaSourceParser
import org.hany18h.parsers.model.MangaParserSource
import org.hany18h.parsers.site.zeistmanga.ZeistMangaParser

@MangaSourceParser("XSANOMANGA", "XsanoManga", "ar")
internal class XsanoManga(context: MangaLoaderContext) :
	ZeistMangaParser(context, MangaParserSource.XSANOMANGA, "www.xsano-manga.com")
