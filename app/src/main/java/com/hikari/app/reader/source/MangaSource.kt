package com.hikari.app.reader.source

import java.io.File

interface MangaSource {

    /** The installed extension's id — used only to tell two sources apart. */
    val id: String

    /** The extension's display name, for log lines and error rows. */
    val name: String

    val userAgent: String get() = ""

    data class PageDescriptor(
        val pageUrl: String,
        val imageUrl: String,
    )

    suspend fun downloadPageImage(page: PageDescriptor, target: File): File

    suspend fun getPageImageModels(rawChapterId: String): List<Any> = emptyList()

    fun coverImageModel(coverUrl: String): Any = coverUrl
}
