package org.hany18h.parsers.model

/**
 * The hany18h fork's parser index — hand-written replacement for the KSP-generated
 * `MangaParserSource` the upstream build produces (see `kotatsu-parsers-ksp`).
 *
 * The fork's site classes carry `@MangaSourceParser(name, title, locale, type)` and
 * KSP turns those into this enum + a `newParser` factory. This app does not run KSP,
 * so the 39 entries below are generated from those annotations (see
 * `scratch/hany18h-sites-pkg.json`, private build record) — same names, titles,
 * locales and content types the fork publishes.
 */
public enum class MangaParserSource(
	public val title: String,
	public val locale: String,
	public val contentType: ContentType,
	public val isBroken: Boolean,
) : MangaSource {
	ANIME3RB("Anime3rb", "ar", ContentType.ANIME, false),
	ANIME_PHOENIX("Anime Phoenix", "ar", ContentType.ANIME, false),
	ANIME_SLAYER("Anime Slayer", "ar", ContentType.ANIME, false),
	ANIME_WITCHER("AnimeWitcher", "ar", ContentType.ANIME, false),
	ANIME_RISTO("RistoAnime", "ar", ContentType.ANIME, false),
	ALHILALLIBRARY("مكتبة الهلال", "ar", ContentType.NOVEL, false),
	MANGASWAT("Manga Swat", "ar", ContentType.MANGA, false),
	AZORAMOON("Azorafly", "ar", ContentType.MANGA, false),
	CENELE("فضاء الروايات", "ar", ContentType.NOVEL, false),
	DILARTUBE("Dilar Tube", "ar", ContentType.MANGA, false),
	GALAXYNOVELS("مجرة الروايات", "ar", ContentType.NOVEL, false),
	KAWAIIMANGA("كواي مانجا", "ar", ContentType.MANGA, false),
	KOLNOVEL("KolNovel", "ar", ContentType.NOVEL, false),
	MANGADAR("MangaDar", "ar", ContentType.MANGA, false),
	MANGAMELLO_PLUS("MangaMello Plus", "ar", ContentType.MANGA, false),
	MANGATEK("MangaTek", "ar", ContentType.MANGA, false),
	MARKAZ_RIWAYAT("مركز الروايات", "ar", ContentType.NOVEL, false),
	PROCHAN("ProChan", "ar", ContentType.MANGA, false),
	REWAYAT("نادي الروايات", "ar", ContentType.NOVEL, false),
	SEANOVEL("بحر الروايات", "ar", ContentType.NOVEL, false),
	SUNOVELS("شمس الروايات", "ar", ContentType.NOVEL, false),
	TEAMXNOVEL("TeamXNovel", "ar", ContentType.MANGA, false),
	ASURASCANS("AsuraComic", "en", ContentType.MANGA, false),
	ANYONEMANGA("AnyoneManga", "ar", ContentType.MANGA, false),
	ASQORG("3Asq", "ar", ContentType.MANGA, false),
	HIZOMANGA("Hizo Manga", "ar", ContentType.NOVEL, false),
	MANGALEK("LekManga", "ar", ContentType.MANGA, false),
	MANGASTARZ("Manga-Starz", "ar", ContentType.MANGA, false),
	ROCKSMANGA("RocksManga", "ar", ContentType.MANGA, false),
	MANHUAUS("ManhuaUs", "en", ContentType.MANGA, false),
	MANHWACLAN("ManhwaClan", "en", ContentType.MANGA, false),
	DESPAIRMANGA("Despair Manga", "ar", ContentType.MANGA, false),
	HIJALACOM("Hijalacom", "ar", ContentType.MANGA, false),
	LAVATOONS("Lavatoons", "ar", ContentType.MANGA, false),
	UMIMANGA("UmiManga", "ar", ContentType.MANGA, false),
	ONMA("Onma", "ar", ContentType.MANGA, false),
	MANGAAILAND("MangaAiLand", "ar", ContentType.MANGA, false),
	MANHATOK("ManhaTok", "ar", ContentType.MANGA, false),
	XSANOMANGA("XsanoManga", "ar", ContentType.MANGA, false),
	;
}
