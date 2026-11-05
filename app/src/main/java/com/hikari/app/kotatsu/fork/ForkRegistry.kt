package com.hikari.app.kotatsu.fork

import com.hikari.app.data.Cs3RepoPlugin
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import org.hany18h.parsers.model.MangaParserSource as Hany18hSource

/**
 * Fork parser rows — third-party Kotatsu-fork sources whose extraction code was
 * not in the app until batch 105 (skepsun/kototoro-parsers via JitPack,
 * hany18h/kotatsu-parsers vendored under `org.hany18h.parsers`).
 *
 * Row shapes (all installable, all runnable — no honest-refusal tier remains):
 *  * `manga|forksk|<ENUM>` (type MANGA, url `kotatsu://forksk/<ENUM>`) — skepsun
 *    manga/novel/image-set sources, driven by [SkepsunBridge].
 *  * `manga|forkhany|<ENUM>` (type MANGA, url `kotatsu://forkhany/<ENUM>`) —
 *    hany18h manga/novel sources, driven by [Hany18hBridge].
 *  * `forkvideo|<owner>|<ENUM>` (type FORK_VIDEO, url `forkvideo://<owner>/<ENUM>`)
 *    — skepsun VIDEO/HENTAI_VIDEO and hany18h ANIME sources, driven by
 *    [ForkVideoProvider] (chapters = episodes, pages/AnimeStreams = servers).
 */
object ForkRegistry {

	const val PREFIX_SK = "manga|forksk|"
	const val PREFIX_HANY = "manga|forkhany|"
	const val PLUGIN_SK_PREFIX = "kotatsu://forksk/"
	const val PLUGIN_HANY_PREFIX = "kotatsu://forkhany/"

	fun isForkSkId(id: String): Boolean = id.startsWith(PREFIX_SK)
	fun isForkHanyId(id: String): Boolean = id.startsWith(PREFIX_HANY)
	fun isForkMangaId(id: String): Boolean = isForkSkId(id) || isForkHanyId(id)

	fun enumNameOfForkId(id: String): String = id.substringAfterLast('|')

	fun isForkSkPluginUrl(url: String): Boolean = url.startsWith(PLUGIN_SK_PREFIX)
	fun isForkHanyPluginUrl(url: String): Boolean = url.startsWith(PLUGIN_HANY_PREFIX)
	fun isForkMangaPluginUrl(url: String): Boolean = isForkSkPluginUrl(url) || isForkHanyPluginUrl(url)

	fun enumNameOfForkPluginUrl(url: String): String? {
		val tail = when {
			url.startsWith(PLUGIN_SK_PREFIX) -> url.removePrefix(PLUGIN_SK_PREFIX)
			url.startsWith(PLUGIN_HANY_PREFIX) -> url.removePrefix(PLUGIN_HANY_PREFIX)
			else -> return null
		}.trim()
		return tail.takeIf { it.isNotBlank() }
	}

	fun pluginUrlOfSk(enumName: String): String = PLUGIN_SK_PREFIX + enumName
	fun pluginUrlOfHany(enumName: String): String = PLUGIN_HANY_PREFIX + enumName

	// ---- Matching ----

	private fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

	/**
	 * The skepsun source behind a repo entry title, or null. Exact enum-name
	 * first, then punctuation-blind title with language preference — same rule
	 * as the official matcher.
	 */
	fun matchSkepsun(name: String, lang: String): org.skepsun.kototoro.parsers.model.ContentParserSource? {
		val all = runCatching {
			org.skepsun.kototoro.parsers.model.ContentParserSource.entries.toList()
		}.getOrDefault(emptyList())
		if (name.isNotBlank()) {
			runCatching { org.skepsun.kototoro.parsers.model.ContentParserSource.valueOf(name.trim()) }
				.getOrNull()?.let { return it }
		}
		val want = norm(name)
		if (want.isBlank()) return null
		val cands = all.filter { norm(it.title) == want }
		if (cands.isEmpty()) return null
		val l = lang.trim().lowercase()
		if (l.isBlank() || l == "all") return cands.first()
		return cands.firstOrNull { s ->
			val sl = s.locale.trim().lowercase()
			sl == l || sl.startsWith(l) || l.startsWith(sl)
		} ?: cands.first()
	}

	fun matchHany18h(name: String, lang: String): Hany18hSource? {
		val all = Hany18hSource.entries.toList()
		if (name.isNotBlank()) {
			runCatching { Hany18hSource.valueOf(name.trim()) }.getOrNull()?.let { return it }
		}
		val want = norm(name)
		if (want.isBlank()) return null
		val cands = all.filter { norm(it.title) == want }
		if (cands.isEmpty()) return null
		val l = lang.trim().lowercase()
		if (l.isBlank() || l == "all") return cands.first()
		return cands.firstOrNull { s ->
			val sl = s.locale.trim().lowercase()
			sl == l || sl.startsWith(l) || l.startsWith(sl)
		} ?: cands.first()
	}

	/** True for skepsun content types that read as manga/novel (not video). */
	fun isSkepsunMangaType(typeName: String): Boolean = when (typeName.uppercase()) {
		"VIDEO", "HENTAI_VIDEO" -> false
		else -> true
	}

	/** True for hany18h content types that read as manga/novel (ANIME rides video). */
	fun isHany18hMangaType(typeName: String): Boolean = typeName.uppercase() != "ANIME"

	fun isSkepsunNovelType(typeName: String): Boolean = when (typeName.uppercase()) {
		"NOVEL", "HENTAI_NOVEL" -> true
		else -> false
	}

	fun isHany18hNovelType(typeName: String): Boolean = typeName.uppercase() == "NOVEL"

	/** True when a fork manga row id points at a novel (text) source. */
	fun isForkNovelId(id: String): Boolean {
		val enum = enumNameOfForkId(id)
		return when {
			isForkSkId(id) -> runCatching {
				val src = org.skepsun.kototoro.parsers.model.ContentParserSource.valueOf(enum)
				isSkepsunNovelType(src.contentType.name)
			}.getOrDefault(false)
			isForkHanyId(id) -> runCatching {
				isHany18hNovelType(Hany18hSource.valueOf(enum).contentType.name)
			}.getOrDefault(false)
			else -> false
		}
	}

	// ---- Configs ----

	fun configForSkepsun(src: org.skepsun.kototoro.parsers.model.ContentParserSource, iconUrl: String? = null): ProviderConfig {
		val hentai = src.contentType.name.startsWith("HENTAI", ignoreCase = true)
		return ProviderConfig(
			id = PREFIX_SK + src.name,
			name = src.title + " (Kototoro)",
			type = ProviderType.MANGA,
			url = pluginUrlOfSk(src.name),
			iconUrl = iconUrl,
			enabled = true,
			extra = "kotatsu-forksk",
			nsfw = hentai.takeIf { it },
		)
	}

	fun configForHany18h(src: Hany18hSource, iconUrl: String? = null): ProviderConfig {
		val hentai = src.contentType.name.equals("HENTAI", ignoreCase = true)
		return ProviderConfig(
			id = PREFIX_HANY + src.name,
			name = src.title + " (Hany18h)",
			type = ProviderType.MANGA,
			url = pluginUrlOfHany(src.name),
			iconUrl = iconUrl,
			enabled = true,
			extra = "kotatsu-forkhany",
			nsfw = hentai.takeIf { it },
		)
	}

	fun configForVideo(owner: String, enumName: String, title: String, nsfw: Boolean, iconUrl: String? = null): ProviderConfig {
		val label = when (owner) {
			ForkVideoProvider.OWNER_SKEPSUN -> " (Kototoro)"
			else -> " (Hany18h)"
		}
		return ProviderConfig(
			id = ForkVideoProvider.idOf(owner, enumName),
			name = title + label,
			type = ProviderType.FORK_VIDEO,
			url = ForkVideoProvider.pluginUrlOf(owner, enumName),
			iconUrl = iconUrl,
			enabled = true,
			extra = "kotatsu-forkvideo",
			nsfw = nsfw.takeIf { it },
		)
	}

	/**
	 * One repo entry matched against both forks: a runnable manga row, a runnable
	 * video row, or null when neither fork ships that source.
	 */
	fun pluginForEntry(
		entryName: String,
		entryLang: String,
		entryIcon: String?,
		entryContentType: String,
		entryNsfw: Boolean,
	): Cs3RepoPlugin? {
		matchSkepsun(entryName, entryLang)?.let { src ->
			if (src.isBroken) return null
			val video = !isSkepsunMangaType(src.contentType.name)
			val hentai = src.contentType.name.startsWith("HENTAI", ignoreCase = true)
			val desc = listOfNotNull(
				src.locale.trim().uppercase().ifBlank { null },
				src.contentType.name.lowercase(),
			).joinToString(" · ")
			return if (video) {
				Cs3RepoPlugin(
					name = src.title,
					description = desc,
					url = ForkVideoProvider.pluginUrlOf(ForkVideoProvider.OWNER_SKEPSUN, src.name),
					iconUrl = entryIcon,
					version = 1,
					contentKind = "anime",
					pkg = "forkvideo.skepsun." + src.name.lowercase(),
					nsfw = hentai,
				)
			} else {
				Cs3RepoPlugin(
					name = src.title,
					description = desc,
					url = pluginUrlOfSk(src.name),
					iconUrl = entryIcon,
					version = 1,
					contentKind = "manga",
					pkg = "forksk." + src.name.lowercase(),
					nsfw = hentai || entryNsfw,
				)
			}
		}
		matchHany18h(entryName, entryLang)?.let { src ->
			if (src.isBroken) return null
			val video = !isHany18hMangaType(src.contentType.name)
			val hentai = src.contentType.name.equals("HENTAI", ignoreCase = true)
			val desc = listOfNotNull(
				src.locale.trim().uppercase().ifBlank { null },
				src.contentType.name.lowercase(),
			).joinToString(" · ")
			return if (video) {
				Cs3RepoPlugin(
					name = src.title,
					description = desc,
					url = ForkVideoProvider.pluginUrlOf(ForkVideoProvider.OWNER_HANY18H, src.name),
					iconUrl = entryIcon,
					version = 1,
					contentKind = "anime",
					pkg = "forkvideo.hany18h." + src.name.lowercase(),
					nsfw = hentai,
				)
			} else {
				Cs3RepoPlugin(
					name = src.title,
					description = desc,
					url = pluginUrlOfHany(src.name),
					iconUrl = entryIcon,
					version = 1,
					contentKind = "manga",
					pkg = "forkhany." + src.name.lowercase(),
					nsfw = hentai || entryNsfw,
				)
			}
		}
		return null
	}

	fun evictForkManga(id: String) {
		val enum = enumNameOfForkId(id)
		when {
			isForkSkId(id) -> SkepsunBridge.evict(enum)
			isForkHanyId(id) -> Hany18hBridge.evict(enum)
		}
	}

	/** The fork parser's live site root, for verification/warm-up and icons. */
	fun domainOfForkManga(id: String): String? {
		val enum = enumNameOfForkId(id)
		return when {
			isForkSkId(id) -> runCatching { SkepsunBridge.forSource(enum)?.parserDomain() }.getOrNull()
			isForkHanyId(id) -> runCatching { Hany18hBridge.forSource(enum)?.parserDomain() }.getOrNull()
			else -> null
		}?.takeIf { it.isNotBlank() }
	}

	/** The fork VIDEO parser's live site root, for row icons. */
	fun domainOfForkVideo(id: String): String? {
		if (!ForkVideoProvider.isForkVideoId(id)) return null
		val owner = ForkVideoProvider.ownerOf(id)
		val enum = ForkVideoProvider.enumNameOf(id)
		return when (owner) {
			ForkVideoProvider.OWNER_SKEPSUN ->
				runCatching { SkepsunBridge.forSource(enum)?.parserDomain() }.getOrNull()
			else -> runCatching { Hany18hBridge.forSource(enum)?.parserDomain() }.getOrNull()
		}?.takeIf { it.isNotBlank() }
	}
}
