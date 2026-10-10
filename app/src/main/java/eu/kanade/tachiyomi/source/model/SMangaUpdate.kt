package eu.kanade.tachiyomi.source.model

/**
 * Result of a combined manga update request: refreshed details and/or chapters.
 *
 * @since extensions-lib 1.6
 */
@Suppress("UNUSED")
class SMangaUpdate(val manga: SManga, val chapters: List<SChapter>)
