package com.lagradost.cloudstream3

data class MainPageRequest(
    val name: String?,
    val data: String,
    val horizontalImages: Boolean = false,
)

open class HomePageList(
    open val name: String?,
    open var list: List<SearchResponse>,
    open val isHorizontalImages: Boolean = false,
)

open class HomePageResponse(
    open var items: List<HomePageList>,
    open var hasNext: Boolean = false,
)
