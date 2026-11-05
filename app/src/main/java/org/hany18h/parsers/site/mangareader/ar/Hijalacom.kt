package org.hany18h.parsers.site.mangareader.ar
import org.hany18h.parsers.MangaLoaderContext
import org.hany18h.parsers.MangaSourceParser
import org.hany18h.parsers.model.MangaParserSource
import org.hany18h.parsers.site.mangareader.MangaReaderParser
@MangaSourceParser("HIJALACOM", "Hijalacom", "ar")
internal class Hijalacom(context: MangaLoaderContext) :
    MangaReaderParser(context, MangaParserSource.HIJALACOM, "hijala.com", pageSize = 30, searchPageSize = 10)
