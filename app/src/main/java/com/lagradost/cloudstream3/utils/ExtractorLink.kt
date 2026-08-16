package com.lagradost.cloudstream3.utils

enum class ExtractorLinkType(val mimeType: String) {
    VIDEO("video/*"),
    M3U8("application/x-mpegURL"),
    DASH("application/dash+xml"),
    TORRENT("application/x-bittorrent"),
    MAGNET("application/x-magnet"),
}

open class ExtractorLink(
    open val source: String,
    open val name: String,
    open val url: String,
    open var referer: String? = null,
    open var quality: Int = Qualities.Unknown.value,
    open var headers: Map<String, String> = emptyMap(),
    open var extractorData: String = "",
    open var type: ExtractorLinkType = ExtractorLinkType.M3U8,
    open var audioTracks: List<Any>? = null,
) {
    val isM3u8: Boolean
        get() = type == ExtractorLinkType.M3U8 || url.contains(".m3u8", true)

    val isDash: Boolean
        get() = type == ExtractorLinkType.DASH
}
