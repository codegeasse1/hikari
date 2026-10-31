package org.hany18h.parsers.core

import androidx.annotation.VisibleForTesting
import org.hany18h.parsers.MangaLoaderContext
import org.hany18h.parsers.model.Manga
import org.hany18h.parsers.model.MangaParserSource
import org.hany18h.parsers.model.search.MangaSearchQuery
import org.hany18h.parsers.model.search.SearchableField
import org.hany18h.parsers.util.Paginator

@Deprecated("Too complex. Use PagedMangaParser instead")
internal abstract class FlexiblePagedMangaParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	@VisibleForTesting(otherwise = VisibleForTesting.PROTECTED) @JvmField public val pageSize: Int,
	searchPageSize: Int = pageSize,
) : FlexibleMangaParser(context, source) {

	@JvmField
	protected val paginator: Paginator = Paginator(pageSize)

	@JvmField
	protected val searchPaginator: Paginator = Paginator(searchPageSize)

	final override suspend fun getList(query: MangaSearchQuery): List<Manga> {
		var containTitleNameCriteria = false
		query.criteria.forEach {
			if (it.field == SearchableField.TITLE_NAME) {
				containTitleNameCriteria = true
			}
		}

		return searchManga(
			paginator = if (containTitleNameCriteria) {
				paginator
			} else {
				searchPaginator
			},
			query = query,
		)
	}

	public abstract suspend fun getListPage(query: MangaSearchQuery, page: Int): List<Manga>

	protected fun setFirstPage(firstPage: Int, firstPageForSearch: Int = firstPage) {
		paginator.firstPage = firstPage
		searchPaginator.firstPage = firstPageForSearch
	}

	private suspend fun searchManga(
		paginator: Paginator,
		query: MangaSearchQuery,
	): List<Manga> {
		val offset: Int = query.offset
		val page = paginator.getPage(offset)
		val list = getListPage(query, page)
		paginator.onListReceived(offset, page, list.size)
		return list
	}
}
