package org.hany18h.parsers.model

public enum class ContentType {

	/**
	 * Standard manga, manhua, webtoons, etc
	 */
	MANGA,

	MANHWA,

	MANHUA,

	/**
	 * Use this if the source provides mostly nsfw content.
	 */
	HENTAI,

	/**
	 * Western comics
	 */
	COMICS,

	NOVEL,

	/** Streamed anime series whose chapters represent playable episodes. */
	ANIME,

	/**
	 * Use this type if no other suits your needs. For example, for an indie manga
	 */

	ONE_SHOT,
	DOUJINSHI,
	IMAGE_SET,
	ARTIST_CG,
	GAME_CG,
	OTHER,
}
