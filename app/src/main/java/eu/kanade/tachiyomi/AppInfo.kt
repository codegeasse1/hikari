package eu.kanade.tachiyomi

import com.hikari.app.BuildConfig

/**
 * Info about the installed host app (NOT THE EXTENSION!). Some extensions put
 * this in a User-Agent or gate a work-around on the version, so it reports
 * Hikari's own version rather than a hard-coded Aniyomi one.
 */
object AppInfo {
    fun getVersionCode(): Int = BuildConfig.VERSION_CODE

    fun getVersionName(): String = BuildConfig.VERSION_NAME

    fun getSupportedImageMimeTypes(): List<String> = listOf(
        "image/jpeg",
        "image/png",
        "image/webp",
        "image/gif",
        "image/avif",
    )
}
