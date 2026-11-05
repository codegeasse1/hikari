package org.hany18h.parsers

import org.hany18h.parsers.model.MangaParserSource
import org.hany18h.parsers.site.anime.ar.Anime3rb
import org.hany18h.parsers.site.anime.ar.AnimePhoenix
import org.hany18h.parsers.site.anime.ar.AnimeSlayer
import org.hany18h.parsers.site.anime.ar.AnimeWitcher
import org.hany18h.parsers.site.anime.ar.RistoAnime
import org.hany18h.parsers.site.ar.AlHilalLibrary
import org.hany18h.parsers.site.ar.Azoramoon
import org.hany18h.parsers.site.ar.Cenele
import org.hany18h.parsers.site.ar.DilarTube
import org.hany18h.parsers.site.ar.GalaxyNovels
import org.hany18h.parsers.site.ar.KawaiiManga
import org.hany18h.parsers.site.ar.KolNovel
import org.hany18h.parsers.site.ar.MangaDar
import org.hany18h.parsers.site.ar.MangaMelloPlus
import org.hany18h.parsers.site.ar.MangaTek
import org.hany18h.parsers.site.ar.MarkazRiwayat
import org.hany18h.parsers.site.ar.ProChan
import org.hany18h.parsers.site.ar.Rewayat
import org.hany18h.parsers.site.ar.Seanovel
import org.hany18h.parsers.site.ar.Sunovels
import org.hany18h.parsers.site.ar.TeamXNovel
import org.hany18h.parsers.site.ar.MangaSwat
import org.hany18h.parsers.site.en.AsuraScansParser
import org.hany18h.parsers.site.madara.ar.AnyoneManga
import org.hany18h.parsers.site.madara.ar.Asq
import org.hany18h.parsers.site.madara.ar.HizoManga
import org.hany18h.parsers.site.madara.ar.LekManga
import org.hany18h.parsers.site.madara.ar.MangaStarz
import org.hany18h.parsers.site.madara.ar.RocksManga
import org.hany18h.parsers.site.madara.en.Manhuaus
import org.hany18h.parsers.site.madara.en.ManhwaClan
import org.hany18h.parsers.site.mangareader.ar.DespairManga
import org.hany18h.parsers.site.mangareader.ar.Hijalacom
import org.hany18h.parsers.site.mangareader.ar.Lavatoons
import org.hany18h.parsers.site.mangareader.ar.UmiManga
import org.hany18h.parsers.site.mmrcms.ar.Onma
import org.hany18h.parsers.site.zeistmanga.ar.MangaAiLand
import org.hany18h.parsers.site.zeistmanga.ar.ManhaTok
import org.hany18h.parsers.site.zeistmanga.ar.XsanoManga

/**
 * Hand-written replacement for the KSP-generated `MangaParserSource.newParser`
 * (see `MangaParserSource.kt`): one branch per fork source, same mapping the
 * fork's own build produces.
 */
public fun MangaParserSource.newParser(context: MangaLoaderContext): MangaParser = when (this) {
	MangaParserSource.ANIME3RB -> Anime3rb(context)
	MangaParserSource.ANIME_PHOENIX -> AnimePhoenix(context)
	MangaParserSource.ANIME_SLAYER -> AnimeSlayer(context)
	MangaParserSource.ANIME_WITCHER -> AnimeWitcher(context)
	MangaParserSource.ANIME_RISTO -> RistoAnime(context)
	MangaParserSource.ALHILALLIBRARY -> AlHilalLibrary(context)
	MangaParserSource.MANGASWAT -> MangaSwat(context)
	MangaParserSource.AZORAMOON -> Azoramoon(context)
	MangaParserSource.CENELE -> Cenele(context)
	MangaParserSource.DILARTUBE -> DilarTube(context)
	MangaParserSource.GALAXYNOVELS -> GalaxyNovels(context)
	MangaParserSource.KAWAIIMANGA -> KawaiiManga(context)
	MangaParserSource.KOLNOVEL -> KolNovel(context)
	MangaParserSource.MANGADAR -> MangaDar(context)
	MangaParserSource.MANGAMELLO_PLUS -> MangaMelloPlus(context)
	MangaParserSource.MANGATEK -> MangaTek(context)
	MangaParserSource.MARKAZ_RIWAYAT -> MarkazRiwayat(context)
	MangaParserSource.PROCHAN -> ProChan(context)
	MangaParserSource.REWAYAT -> Rewayat(context)
	MangaParserSource.SEANOVEL -> Seanovel(context)
	MangaParserSource.SUNOVELS -> Sunovels(context)
	MangaParserSource.TEAMXNOVEL -> TeamXNovel(context)
	MangaParserSource.ASURASCANS -> AsuraScansParser(context)
	MangaParserSource.ANYONEMANGA -> AnyoneManga(context)
	MangaParserSource.ASQORG -> Asq(context)
	MangaParserSource.HIZOMANGA -> HizoManga(context)
	MangaParserSource.MANGALEK -> LekManga(context)
	MangaParserSource.MANGASTARZ -> MangaStarz(context)
	MangaParserSource.ROCKSMANGA -> RocksManga(context)
	MangaParserSource.MANHUAUS -> Manhuaus(context)
	MangaParserSource.MANHWACLAN -> ManhwaClan(context)
	MangaParserSource.DESPAIRMANGA -> DespairManga(context)
	MangaParserSource.HIJALACOM -> Hijalacom(context)
	MangaParserSource.LAVATOONS -> Lavatoons(context)
	MangaParserSource.UMIMANGA -> UmiManga(context)
	MangaParserSource.ONMA -> Onma(context)
	MangaParserSource.MANGAAILAND -> MangaAiLand(context)
	MangaParserSource.MANHATOK -> ManhaTok(context)
	MangaParserSource.XSANOMANGA -> XsanoManga(context)
}
