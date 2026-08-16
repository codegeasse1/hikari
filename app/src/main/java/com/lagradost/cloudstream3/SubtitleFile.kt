package com.lagradost.cloudstream3

open class SubtitleFile(
    open val lang: String,
    open val url: String,
    open var headers: Map<String, String> = emptyMap(),
) {
    constructor(lang: String, url: String) : this(lang, url, emptyMap())

    val langTag: String
        get() = lang.substringBefore("-").lowercase()
}
