package org.hany18h.parsers.model

/**
 * A directly playable video stream returned by an anime parser.
 *
 * @property name User-facing server/stream label.
 * @property url Direct HLS or MP4 URL.
 * @property headers HTTP headers required by the media server.
 * @property quality Optional source-provided quality label.
 */
public data class AnimeStream(
	@JvmField public val name: String,
	@JvmField public val url: String,
	@JvmField public val headers: Map<String, String> = emptyMap(),
	@JvmField public val quality: String? = null,
)
