package com.hikari.app.kotatsu.fork

import com.hikari.app.data.CatalogRef
import com.hikari.app.data.Episode
import com.hikari.app.data.Logs
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.StreamSource
import com.hikari.app.providers.ContentProvider
import com.hikari.app.providers.ProviderGate
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Video rows from Kotatsu forks — skepsun VIDEO/HENTAI_VIDEO sources and hany18h
 * ANIME sources — exposed as an ordinary [ContentProvider] so the whole video
 * pipeline (Home rows, search, detail, episodes, player) works unchanged.
 *
 * Row ids are `forkvideo|<owner>|<ENUM>` (`skepsun` = kototoro parsers,
 * `hany18h` = hany18h parsers). Catalogue/search/detail/episodes ride the fork
 * bridges ([SkepsunBridge]/[Hany18hBridge]); streams are the parsers' own
 * playback: skepsun video pages ARE the video urls (M3U8/MP4 per page), hany18h
 * anime chapters resolve via `getVideoStreams` to direct HLS/MP4 [AnimeStream]s.
 */
class ForkVideoProvider(override val config: ProviderConfig) : ContentProvider {

	companion object {
		const val PREFIX = "forkvideo|"
		const val OWNER_SKEPSUN = "skepsun"
		const val OWNER_HANY18H = "hany18h"

		const val CATALOG_POPULAR = "popular"
		const val CATALOG_LATEST = "latest"
		const val EXT_CALL_TIMEOUT_MS = 30_000L

		val lastOutcome = ConcurrentHashMap<String, String>()
		val streamErrors = ConcurrentHashMap<String, String>()

		fun isForkVideoId(id: String): Boolean = id.startsWith(PREFIX)

		fun ownerOf(id: String): String = id.removePrefix(PREFIX).substringBefore('|')

		fun enumNameOf(id: String): String = id.substringAfterLast('|')

		fun idOf(owner: String, enumName: String): String = PREFIX + owner + "|" + enumName

		fun pluginUrlOf(owner: String, enumName: String): String = "forkvideo://" + owner + "/" + enumName

		fun isForkVideoPluginUrl(url: String): Boolean = url.startsWith("forkvideo://")

		fun ownerEnumOfPluginUrl(url: String): Pair<String, String>? {
			if (!url.startsWith("forkvideo://")) return null
			val tail = url.removePrefix("forkvideo://")
			val owner = tail.substringBefore('/').trim()
			val enum = tail.substringAfter('/').trim()
			if (owner.isBlank() || enum.isBlank()) return null
			return owner to enum
		}
	}

	private val owner: String get() = ownerOf(config.id)
	private val enumName: String get() = enumNameOf(config.id)
	private val isSkepsun: Boolean get() = owner == OWNER_SKEPSUN

	private fun <T> fail(msg: String, fallback: T): T {
		val short = "✗ " + msg.take(200)
		lastOutcome[config.id] = short
		streamErrors[config.id] = short
		Logs.log("ForkVideo", "${config.name}: $msg")
		return fallback
	}

	private suspend fun <T> gate(
		lane: ProviderGate.Lane = ProviderGate.Lane.INTERACTIVE,
		block: suspend () -> T,
	): T = ProviderGate.withProvider(config.id, lane) {
		withContext(Dispatchers.IO) { block() }
	}

	private fun skepsunBridge(): SkepsunBridge? = SkepsunBridge.forSource(enumName)

	private fun hany18hBridge(): Hany18hBridge? = Hany18hBridge.forSource(enumName)

	// ---- Catalogues ----

	override suspend fun catalogs(): List<CatalogRef> = gate {
		val supportsLatest = when {
			isSkepsun -> skepsunBridge()?.supportsLatest ?: true
			else -> hany18hBridge()?.supportsLatest ?: true
		}
		val out = mutableListOf(
			CatalogRef(config.id, MediaType.SERIES, CATALOG_POPULAR, "Popular", "video"),
		)
		if (supportsLatest) out += CatalogRef(config.id, MediaType.SERIES, CATALOG_LATEST, "Latest", "video")
		out
	}

	override suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem> = gate {
		try {
			val items = if (isSkepsun) {
				val b = skepsunBridge() ?: return@gate fail("source missing — reinstall it", emptyList())
				val mangas = withTimeoutOrNull(EXT_CALL_TIMEOUT_MS) {
					when (ref.id) {
						CATALOG_LATEST -> b.getLatestUpdates(page)
						else -> b.getPopularManga(page)
					}.mangas
				} ?: return@gate fail("the site took too long to answer", emptyList())
				mangas.map { toItem(it) }
			} else {
				val b = hany18hBridge() ?: return@gate fail("source missing — reinstall it", emptyList())
				val mangas = withTimeoutOrNull(EXT_CALL_TIMEOUT_MS) {
					when (ref.id) {
						CATALOG_LATEST -> b.getLatestUpdates(page)
						else -> b.getPopularManga(page)
					}.mangas
				} ?: return@gate fail("the site took too long to answer", emptyList())
				mangas.map { toItem(it) }
			}
			if (items.isEmpty()) {
				lastOutcome[config.id] = "✗ the site answered, but ${ref.name.lowercase()} came back with no titles (page $page)"
			} else {
				lastOutcome[config.id] = "✓ ${items.size} title(s)"
			}
			items
		} catch (t: Throwable) {
			if (t is kotlinx.coroutines.CancellationException) throw t
			fail("catalog failed: ${reason(t)}", emptyList())
		}
	}

	override suspend fun search(query: String, page: Int): List<MediaItem> = gate {
		try {
			val items = if (isSkepsun) {
				val b = skepsunBridge() ?: return@gate fail("source missing — reinstall it", emptyList())
				val found = withTimeoutOrNull(EXT_CALL_TIMEOUT_MS) {
					b.getSearchManga(page, query, b.getFilterList()).mangas
				} ?: return@gate fail("the site took too long to answer", emptyList())
				found.map { toItem(it) }
			} else {
				val b = hany18hBridge() ?: return@gate fail("source missing — reinstall it", emptyList())
				val found = withTimeoutOrNull(EXT_CALL_TIMEOUT_MS) {
					b.getSearchManga(page, query, b.getFilterList()).mangas
				} ?: return@gate fail("the site took too long to answer", emptyList())
				found.map { toItem(it) }
			}
			lastOutcome[config.id] = "✓ ${items.size} result(s)"
			items
		} catch (t: Throwable) {
			if (t is kotlinx.coroutines.CancellationException) throw t
			fail("search failed: ${reason(t)}", emptyList())
		}
	}

	// ---- Details / episodes ----

	override suspend fun getMeta(item: MediaItem): MediaItem = gate {
		try {
			if (isSkepsun) {
				val b = skepsunBridge() ?: return@gate item
				val full = b.getMangaDetails(sm(item))
				lastOutcome[config.id] = "✓ details"
				item.copy(
					title = full.title.ifBlank { item.title },
					posterUrl = full.thumbnail_url?.takeIf { it.isNotBlank() } ?: item.posterUrl,
					overview = full.description?.takeIf { it.isNotBlank() } ?: item.overview,
					genres = full.getGenres().orEmpty().ifEmpty { item.genres },
				)
			} else {
				val b = hany18hBridge() ?: return@gate item
				val full = b.getMangaDetails(sm(item))
				lastOutcome[config.id] = "✓ details"
				item.copy(
					title = full.title.ifBlank { item.title },
					posterUrl = full.thumbnail_url?.takeIf { it.isNotBlank() } ?: item.posterUrl,
					overview = full.description?.takeIf { it.isNotBlank() } ?: item.overview,
					genres = full.getGenres().orEmpty().ifEmpty { item.genres },
				)
			}
		} catch (t: Throwable) {
			if (t is kotlinx.coroutines.CancellationException) throw t
			Logs.log("ForkVideo", "${config.name}: details failed: ${reason(t)}")
			item
		}
	}

	override suspend fun getEpisodes(item: MediaItem): List<Episode>? = gate {
		try {
			val chapters = if (isSkepsun) {
				val b = skepsunBridge() ?: return@gate failEpisodes("source missing — reinstall it")
				withTimeoutOrNull(EXT_CALL_TIMEOUT_MS) { b.getChapterList(sm(item)) }
					?: return@gate failEpisodes("the site took too long to answer")
			} else {
				val b = hany18hBridge() ?: return@gate failEpisodes("source missing — reinstall it")
				withTimeoutOrNull(EXT_CALL_TIMEOUT_MS) { b.getChapterList(sm(item)) }
					?: return@gate failEpisodes("the site took too long to answer")
			}
			if (chapters.isEmpty()) return@gate failEpisodes("no episodes in this answer")
			lastOutcome[config.id] = "✓ ${chapters.size} episode(s)"
			chapters.mapIndexed { i, c ->
				Episode(number = i + 1, id = c.url, name = c.name.ifBlank { null }, image = null, season = 1)
			}
		} catch (t: Throwable) {
			if (t is kotlinx.coroutines.CancellationException) throw t
			failEpisodes("episodes failed: ${reason(t)}")
		}
	}

	private fun failEpisodes(msg: String): List<Episode>? {
		lastOutcome[config.id] = "✗ " + msg.take(200)
		Logs.log("ForkVideo", "${config.name}: $msg")
		return null
	}

	// ---- Streams ----

	override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> =
		gate(ProviderGate.Lane.BACKGROUND) {
			val chapter = episode ?: return@gate fail("No episode selected", emptyList())
			try {
				if (isSkepsun) {
					val b = skepsunBridge() ?: return@gate fail("source missing — reinstall it", emptyList())
					val pages = withTimeoutOrNull(EXT_CALL_TIMEOUT_MS) {
						b.videoPages(chapter.id, chapter.name)
					} ?: return@gate fail("the site took too long to answer", emptyList())
					// Parser-level headers fill what a page does not set (User-Agent); the page's own headers win per key.
					val baseHeaders = videoHeadersSkepsun()
					val out = pages.mapIndexedNotNull { i, (url, pageHeaders) ->
						if (!looksPlayable(url)) return@mapIndexedNotNull null
						StreamSource(
							name = "Server ${i + 1}",
							url = url,
							headers = baseHeaders + pageHeaders,
							isM3u8 = url.contains(".m3u8", ignoreCase = true),
							provider = "Fork",
							providerId = config.id,
							providerName = config.name,
						)
					}
					if (out.isEmpty()) return@gate fail("no playable video in this answer", emptyList())
					lastOutcome[config.id] = "✓ ${out.size} server(s)"
					streamErrors.remove(config.id)
					out
				} else {
					val parser = Hany18hBridge.parserOf(enumName)
						?: return@gate fail("source missing — reinstall it", emptyList())
					val src = runCatching {
						org.hany18h.parsers.model.MangaParserSource.valueOf(enumName)
					}.getOrNull() ?: return@gate fail("unknown anime source", emptyList())
					val kc = org.hany18h.parsers.model.MangaChapter(
						id = chapter.id.hashCode().toLong(),
						title = chapter.name?.ifBlank { null },
						number = chapter.number.toFloat(),
						volume = 0,
						url = chapter.id,
						scanlator = null,
						uploadDate = 0L,
						branch = null,
						source = src,
					)
					val streams = withTimeoutOrNull(EXT_CALL_TIMEOUT_MS) {
						parser.getVideoStreams(kc)
					} ?: return@gate fail("the site took too long to answer", emptyList())
					val out = streams.mapNotNull { s ->
						val url = s.url.trim()
						if (url.isBlank() || !looksPlayable(url)) return@mapNotNull null
						val label = listOfNotNull(
							s.name.trim().ifBlank { null },
							s.quality?.trim()?.takeIf { it.isNotBlank() },
						).joinToString(" • ").ifBlank { "Server" }
						StreamSource(
							name = label,
							url = url,
							headers = s.headers,
							isM3u8 = url.contains(".m3u8", ignoreCase = true),
							provider = "Fork",
							providerId = config.id,
							providerName = config.name,
						)
					}
					if (out.isEmpty()) return@gate fail("no playable video in this answer", emptyList())
					lastOutcome[config.id] = "✓ ${out.size} server(s)"
					streamErrors.remove(config.id)
					out
				}
			} catch (t: Throwable) {
				if (t is kotlinx.coroutines.CancellationException) throw t
				fail("streams failed: ${reason(t)}", emptyList())
			}
		}

	private fun looksPlayable(url: String): Boolean {
		val u = url.trim().lowercase()
		if (u.isBlank() || !u.startsWith("http")) return false
		return u.contains(".m3u8") || u.contains(".mp4") || u.contains(".webm") ||
			u.contains(".mkv") || u.contains("mime=video") || u.contains("/video")
	}

	private fun videoHeadersSkepsun(): Map<String, String> {
		val base = runCatching {
			SkepsunBridge.parserOf(enumName)?.getRequestHeaders()
				?.toMultimap()?.mapValues { it.value.firstOrNull().orEmpty() }
		}.getOrNull().orEmpty()
		var h = base
		if (h.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
			h = h + ("User-Agent" to com.hikari.app.net.Http.UA)
		}
		return h
	}

	// ---- Mapping ----

	private fun toItem(sm: SManga): MediaItem = MediaItem(
		providerId = config.id,
		id = sm.url,
		title = sm.title.ifBlank { sm.url },
		type = MediaType.SERIES,
		posterUrl = sm.thumbnail_url?.takeIf { it.isNotBlank() },
		overview = sm.description?.takeIf { it.isNotBlank() },
		genres = sm.getGenres().orEmpty(),
	)

	private fun sm(item: MediaItem): SManga = SManga.create().apply {
		url = item.id
		title = item.title
		thumbnail_url = item.posterUrl
	}


	private fun reason(t: Throwable): String =
		(t.message?.trim()?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName).take(160)
}
