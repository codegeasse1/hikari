@file:JvmName("MangaParsersUtils")

package org.hany18h.parsers.util

import org.hany18h.parsers.model.MangaChapter
import org.hany18h.parsers.model.MangaListFilter
import kotlin.contracts.contract

public fun MangaListFilter?.isNullOrEmpty(): Boolean {
	contract {
		returns(false) implies (this@isNullOrEmpty != null)
	}
	return this == null || this.isEmpty()
}

public fun Collection<MangaChapter>.findById(chapterId: Long): MangaChapter? = find { x ->
	x.id == chapterId
}
