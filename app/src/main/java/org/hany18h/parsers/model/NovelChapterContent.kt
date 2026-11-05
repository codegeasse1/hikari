package org.hany18h.parsers.model

/**
 * Represents the content of a novel chapter.
 * Used by novel parsers to deliver HTML-formatted text content.
 *
 * @property html Complete rendered HTML content of the chapter.
 *   Images should be referenced as original URLs (the caller can replace them if needed).
 * @property images List of images referenced in the HTML, with their URLs and required headers.
 */
public data class NovelChapterContent(
	@JvmField public val html: String,
	@JvmField public val images: List<NovelImage> = emptyList(),
)

/**
 * Represents an image embedded in novel content.
 *
 * @property url The URL of the image.
 * @property headers HTTP headers required to fetch the image (e.g., Referer, Cookie).
 */
public data class NovelImage(
	@JvmField public val url: String,
	@JvmField public val headers: Map<String, String> = emptyMap(),
)
