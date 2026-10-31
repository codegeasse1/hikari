package org.hany18h.parsers.core

import org.hany18h.parsers.InternalParsersApi
import org.hany18h.parsers.MangaLoaderContext
import org.hany18h.parsers.model.Manga
import org.hany18h.parsers.model.MangaListFilter
import org.hany18h.parsers.model.MangaParserSource
import org.hany18h.parsers.model.SortOrder

@InternalParsersApi
public abstract class SinglePageMangaParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
) : AbstractMangaParser(context, source) {

	final override suspend fun getList(offset: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		if (offset > 0) {
			return emptyList()
		}
		return getList(order, filter)
	}

	public abstract suspend fun getList(order: SortOrder, filter: MangaListFilter): List<Manga>
}
