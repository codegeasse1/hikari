package com.hikari.app.tracker

import com.hikari.app.data.AppStore
import com.hikari.app.data.HistoryEntry
import com.hikari.app.data.Logs
import com.hikari.app.data.MediaType
import com.hikari.app.manga.MangaStore
import com.hikari.app.data.TrackerAccount
import com.hikari.app.data.TrackerClient
import com.hikari.app.data.TrackerMatch
import com.hikari.app.data.TrackerMedia
import com.hikari.app.data.TrackerStore
import com.hikari.app.data.TRACKER_AUTO_THRESHOLD
import com.hikari.app.data.matchScoreAny
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What actually gets sent to the trackers, and when (Settings → Trackers).
 *
 * The rule the whole feature hangs on: **an automatic push must be right, or it
 * must not happen.** A tracker list is something the user has kept for years, and
 * marking the wrong episode watched is worse than reporting nothing at all — so
 * a title is only ever pushed when its match is confident ([bestMatch]), the
 * answer is remembered per title so the same show is matched once and not on
 * every episode ([TrackerStore.matchKey]), and a match that could not be made
 * confidently is REPORTED to the user rather than guessed at ("AniList: no
 * confident match for \"…\""). Nothing here fails silently: every path returns a
 * sentence, and the card shows them.
 *
 * Progress is reported when an episode has been watched far enough to count
 * ([WATCHED_FRACTION]) — the same "you watched it" line the watch history uses —
 * and never twice ([AppStore.markTrackerDone]).
 */
object TrackerSync {

    /** How much of an episode counts as watched (90%, like the history shelf). */
    private const val WATCHED_FRACTION = 0.9

    /**
     * Reports one watch to every signed-in tracker that can take it, and returns
     * a sentence per tracker that had something to say.
     *
     * [historyKey] is the watch-history identity of this video, which is what
     * stops the same episode being reported on every 5-second flush while it is
     * being watched.
     */
    suspend fun pushWatched(
        store: AppStore,
        media: TrackerMedia,
        historyKey: String,
        force: Boolean = false,
    ): List<String> = withContext(Dispatchers.IO) {
        var notes: List<String> = ArrayList()
        val accounts = runCatching { store.trackers() }.getOrDefault(emptyList())
        if (accounts.isEmpty()) return@withContext notes
        if (!force && !runCatching { store.trackerSync() }.getOrDefault(true)) return@withContext notes
        if (media.title.isBlank()) return@withContext notes
        // An episode number is what a tracker reports; without one (and without a
        // film) there is nothing to report, and guessing "episode 1" would mark
        // the wrong thing watched.
        if (!media.movie && media.episode <= 0) return@withContext notes

        val clients = runCatching { store.trackerClients() }.getOrDefault(emptyList())
        val done = runCatching { store.trackerDone() }.getOrDefault(emptyMap())
        val matches = runCatching { store.trackerMatches() }.getOrDefault(emptyMap())

        for (account in accounts) {
            val kind = account.kind
            val client = clients.firstOrNull { it.kind == kind } ?: TrackerClient(kind)
            if (!client.ready) {
                notes += "${kind.label}: the app id is missing — open Trackers and add it"
                continue
            }
            // Only AniList and MAL keep manga lists: the others can never
            // match a manga title, so say that instead of "no confident
            // match" (which reads as a bug rather than a scope limit).
            if (media.manga && kind != com.hikari.app.data.TrackerKind.ANILIST &&
                kind != com.hikari.app.data.TrackerKind.MAL
            ) {
                notes += "${kind.label}: doesn't track manga — skipped"
                continue
            }
            val doneKey = kind.key + "|" + historyKey + "|" + media.episode
            if (!force && done.containsKey(doneKey)) {
                notes += "${kind.label}: ${media.title} — already reported"
                continue
            }
            val live = freshAccount(store, client, account)

            // The cached match, when there is one, saves a search — and it is
            // only trusted while it still looks like the same title.
            val cached = matches[TrackerStore.matchKey(kind, media.title)]
                ?.takeIf { matchScoreAny(media.title, it.title, media.year, it.year) >= TRACKER_AUTO_THRESHOLD }
            val match = if (cached != null) {
                Logs.log("Tracker", "${kind.label} decide [\"${media.title}\"] CACHED id=${cached.id} title=\"${cached.title}\"")
                cached
            } else {
                resolve(store, client, live, media)
            }
            if (match == null) {
                notes += "${kind.label}: no confident match for \"${media.title}\""
                continue
            }
            TrackerApi.push(client, live, match, media)
                .onSuccess { note ->
                    Logs.log("Tracker", "${kind.label} push OK [\"${media.title}\" ep=${media.episode}] id=${match.id} $note")
                    notes += note
                    runCatching { store.markTrackerDone(doneKey) }
                }
                .onFailure { error ->
                    Logs.log("Tracker", "${kind.label} push FAIL [\"${media.title}\" ep=${media.episode}] id=${match.id}: ${error.message}")
                    // A refusal can mean the stored match is stale (the service
                    // re-numbered something, or the account was reset), so it is
                    // forgotten: the next watch searches again instead of
                    // re-sending the same wrong id forever.
                    runCatching { store.dropTrackerMatch(TrackerStore.matchKey(kind, media.title)) }
                    notes += error.message ?: "${kind.label}: the update failed"
                }
        }
        // Only a run that had something to SAY is written down: the automatic
        // push runs on every history flush while the last minutes of an episode
        // play, and "already reported" twenty times over is not news. The same
        // sentence is not written twice either — a title nothing can match is
        // still unmatched on the next flush, and that must not mean a DataStore
        // write every five seconds for the rest of the episode.
        notes = notes.distinct()
        val summary = notes.joinToString("\n")
        if (notes.any { !it.endsWith("already reported") } &&
            runCatching { store.trackerLast() }.getOrDefault("") != summary
        ) {
            runCatching { store.setTrackerLast(summary) }
        }
        notes
    }

    /** The stored token, refreshed first when the service said it would expire. */
    private suspend fun freshAccount(
        store: AppStore,
        client: TrackerClient,
        account: TrackerAccount,
    ): TrackerAccount {
        if (!account.expired) return account
        val renewed = runCatching { TrackerApi.refresh(client, account) }.getOrNull() ?: return account
        runCatching { store.setTrackerAccount(renewed) }
        return renewed
    }

    /** Searches one service and remembers the answer, or null when unsure. */
    private suspend fun resolve(
        store: AppStore,
        client: TrackerClient,
        account: TrackerAccount,
        media: TrackerMedia,
    ): TrackerMatch? {
        val candidates = TrackerApi.search(client, account.token, media).getOrNull().orEmpty()
        Logs.log(
            "Tracker",
            "${client.kind.label} decide [title=\"${media.title}\" season=${media.season} ep=${media.episode} " +
                "year=${media.year} movie=${media.movie} manga=${media.manga}] candidates=${candidates.size}" +
                if (candidates.isEmpty()) "" else " " + candidates.take(12).joinToString(" ;; ") {
                    "id=${it.id} score=${"%.3f".format(it.score)} cat=${it.category} year=${it.year} total=${it.total} title=\"${it.title}\""
                },
        )
        val direct = bestMatch(media, candidates, client.kind)
        if (direct != null) {
            Logs.log("Tracker", "${client.kind.label} decide [\"${media.title}\"] DIRECT id=${direct.id} title=\"${direct.title}\" score=${"%.3f".format(direct.score)}")
            runCatching { store.putTrackerMatch(TrackerStore.matchKey(client.kind, media.title), direct) }
            return direct
        }
        val alias = aliasBestMatch(media, candidates, client.kind)
        if (alias != null) {
            Logs.log("Tracker", "${client.kind.label} decide [\"${media.title}\"] ALIAS id=${alias.id} title=\"${alias.title}\" score=${"%.3f".format(alias.score)}")
            runCatching { store.putTrackerMatch(TrackerStore.matchKey(client.kind, media.title), alias) }
            return alias
        }
        Logs.log(
            "Tracker",
            "${client.kind.label} decide [\"${media.title}\"] NO-MATCH " +
                "(threshold=$TRACKER_AUTO_THRESHOLD; pool empty, top below threshold, or a full tie — see candidates above)",
        )
        return null
    }

    /**
     * Second chance for a title the fuzzy score rejected: AniList knows every
     * spelling of the show (romaji, English, native + synonyms), and an exact
     * hit on ANY of them is the confident match a slightly-different name
     * could never score. Each alias re-runs the full [bestMatch] rules, so a
     * wrong-season or wrong-kind entry still cannot sneak through.
     */
    private suspend fun aliasBestMatch(
        media: TrackerMedia,
        candidates: List<TrackerMatch>,
        kind: com.hikari.app.data.TrackerKind,
    ): TrackerMatch? {
        if (candidates.isEmpty()) return null
        val aliases = runCatching {
            com.hikari.app.data.AnimeMetadataRepository.aliasesFor(media.title)
        }.getOrNull().orEmpty()
        if (aliases.isEmpty()) return null
        for (alias in aliases) {
            if (alias.equals(media.title, ignoreCase = true)) continue
            bestMatch(media.copy(title = alias), candidates, kind)?.let { return it }
        }
        return null
    }

    /**
     * The one candidate that may be pushed to without asking, or null.
     *
     * Three things have to hold at once:
     *  * the title matches confidently ([TRACKER_AUTO_THRESHOLD]) — this is the
     *    rule that keeps "Naruto" from marking "Naruto Shippuden";
     *  * the KIND agrees: a film is reported to a film entry, an episode to a
     *    series entry. Without this, "Naruto" playing as a film could land on the
     *    TV series (or the reverse), which is the same bug wearing a hat;
     *  * nothing else scores the same. Two different entries at the top of the
     *    list means the search cannot tell them apart, and a guess here is a
     *    wrong episode on the user's list — so it is reported as unmatched
     *    instead.
     */
    suspend fun bestMatch(
        media: TrackerMedia,
        candidates: List<TrackerMatch>,
        kind: com.hikari.app.data.TrackerKind,
    ): TrackerMatch? {
        val usable = candidates.filter { it.id.isNotBlank() && it.score > 0.0 }
            .sortedByDescending { it.score }
        if (usable.isEmpty()) return null
        val agreeing = usable.filter { kindAgrees(media, it.category) }
        val pool = (agreeing.ifEmpty { usable }).filterNot { seasonMismatch(media, it) }
        if (pool.isEmpty()) return null
        val top = pool.first()
        if (top.score < TRACKER_AUTO_THRESHOLD) return null
        val rivals = pool.filter { it.id != top.id && it.score >= top.score - 0.02 }
        if (rivals.isNotEmpty()) {
            // Several seasons of one show all tie here (every sequel tail
            // strips to the same base, e.g. "Jade Dynasty Season 4" against
            // "Jade Dynasty" and "Jade Dynasty Final" — and every donghua
            // season scores 1.0 through the shared alias, e.g. "Battle
            // Through the Heavens Season 5" against every "Dou Po Cangqiong"
            // entry whose own title carries no season number). The old code
            // reported "no confident match" for all of them. Instead the raw
            // title decides, then the season marker, then release-year
            // closeness and episode coverage — and only a tie on EVERY
            // signal is still reported as unmatched.
            val ranked = pool.sortedWith(
                compareByDescending<TrackerMatch> { it.score }
                    .thenByDescending { com.hikari.app.data.exactTitleHit(media.title, it) }
                    .thenByDescending { com.hikari.app.data.matchScore(media.title, it.title, media.year, it.year) }
                    .thenByDescending { seasonMarkerScore(media, it) }
                    .thenByDescending { yearCloseness(media, it) }
                    .thenByDescending { coversEpisode(media, it) },
            )
            val winner = ranked.first()
            // A known season picks its entry first off the tracker's own
            // sequel line (AniList PREQUEL/SEQUEL edges), then by release
            // order. Donghua sequel entries often carry no number at all
            // ("Nian Fan" is season 5), so no marker can separate them — but
            // the franchise graph (and then the release years) say which is
            // which. Either way the pick must ALSO fit the watched episode
            // or carry the season's marker: an interleaved side story at the
            // wanted index is rejected back to an honest no-match instead of
            // marking the wrong entry watched.
            if (media.season >= 2) {
                chainPick(media, pool, kind)?.let { return it }
                orderPick(media, pool)?.let { return it }
            }
            val runnerUp = ranked.firstOrNull { it.id != winner.id }
            if (runnerUp != null && runnerUp.score >= winner.score - 0.02 &&
                com.hikari.app.data.exactTitleHit(media.title, runnerUp) >=
                com.hikari.app.data.exactTitleHit(media.title, winner) &&
                com.hikari.app.data.matchScore(media.title, runnerUp.title, media.year, runnerUp.year) >=
                com.hikari.app.data.matchScore(media.title, winner.title, media.year, winner.year) &&
                seasonMarkerScore(media, runnerUp) >= seasonMarkerScore(media, winner) &&
                yearCloseness(media, runnerUp) >= yearCloseness(media, winner) &&
                coversEpisode(media, runnerUp) == coversEpisode(media, winner)
            ) return null
            return winner
        }
        return top
    }

    /**
     * The wanted season straight off the service's franchise graph: the tied
     * pool ordered root-first along PREQUEL/SEQUEL edges, mainline entries
     * only. Null unless the pick is verifiable — in the pool, confident, AND
     * fitting the watched episode or carrying the season's marker.
     */
    private suspend fun chainPick(
        media: TrackerMedia,
        pool: List<TrackerMatch>,
        kind: com.hikari.app.data.TrackerKind,
    ): TrackerMatch? {
        if (kind != com.hikari.app.data.TrackerKind.ANILIST) return null
        val ids = pool.mapNotNull { it.id.toIntOrNull() }.distinct()
        if (ids.size < media.season) return null
        val chain = TrackerApi.anilistSequelChain(ids)
            .mapNotNull { id -> pool.firstOrNull { it.id == id.toString() } }
            .filter { com.hikari.app.data.isMainlineEntry(it.title, media.movie) }
        val pick = chain.getOrNull(media.season - 1) ?: return null
        if (pick.score < TRACKER_AUTO_THRESHOLD - 0.1) return null
        if (!coversEpisode(media, pick) && seasonMarkerScore(media, pick) == 0) return null
        Logs.log(
            "Tracker",
            "decide [\"${media.title}\"] CHAIN season=${media.season} " +
                "id=${pick.id} title=\"${pick.title}\"",
        )
        return pick
    }

    /**
     * The wanted season by release order: the Nth-earliest same-show entry is
     * season N. Same-show is alias-aware (plain sequel-base equality misses
     * every pinyin-vs-English pair), specials stay out of the line, and a pick
     * from unknown years is refused — without years the order is id order,
     * which is meaningless.
     */
    private fun orderPick(media: TrackerMedia, pool: List<TrackerMatch>): TrackerMatch? {
        val wantedBase = com.hikari.app.data.sequelBase(com.hikari.app.data.normalizeTitle(media.title))
        val sameShow = pool.filter { c ->
            com.hikari.app.data.sequelBase(com.hikari.app.data.normalizeTitle(c.title)) == wantedBase ||
                com.hikari.app.data.matchScoreAny(c.title, media.title, media.year, c.year) >= TRACKER_AUTO_THRESHOLD - 0.1
        }.filter { com.hikari.app.data.isMainlineEntry(it.title, media.movie) }
        if (media.season > sameShow.size) return null
        val ordered = sameShow.sortedWith(
            compareBy({ it.year.takeIf { y -> y > 0 } ?: Int.MAX_VALUE }, { it.id })
        )
        val pick = ordered.getOrNull(media.season - 1) ?: return null
        if (pick.score < TRACKER_AUTO_THRESHOLD - 0.1) return null
        if (pick.year <= 0) return null
        if (!coversEpisode(media, pick) && seasonMarkerScore(media, pick) == 0) return null
        Logs.log(
            "Tracker",
            "decide [\"${media.title}\"] ORDER season=${media.season} " +
                "id=${pick.id} title=\"${pick.title}\" year=${pick.year}",
        )
        return pick
    }

    /** True when the candidate's episode total proves it is a DIFFERENT season:
     *  the watched episode number cannot exist inside it (e.g. episode 697 of
     *  a 272-episode entry). Reporting that as a match marks the wrong show
     *  watched, so it is excluded before any scoring.
     *
     *  Season-aware: extensions often number a later season's episodes
     *  ABSOLUTELY (donghua season 5 reading "episode 130"), which no single
     *  season entry's total can contain — so when the watched season is known
     *  (> 1) the total comparison is skipped instead of excluding every
     *  season entry and reporting "no confident match". */
    private fun seasonMismatch(media: TrackerMedia, c: TrackerMatch): Boolean =
        !media.movie && media.episode > 0 && c.total > 0 && media.episode > c.total &&
        media.season <= 1

    /** 1 when the candidate's own title carries the watched season's marker
     *  ("Season 5", "Part 2", "S3"), else 0 — the tie-break that picks the
     *  right season entry when every season strips to the same base. A bare
     *  number that IS the season ("Jade Dynasty 4", "Douluo Dalu 2") counts
     *  too: sequel entries are often numbered that way. */
    private fun seasonMarkerScore(media: TrackerMedia, c: TrackerMatch): Int {
        val s = media.season
        if (s <= 1) return 0
        if (Regex("""(?i)\b(s|season|part|cour)\s*0?$s\b""").containsMatchIn(c.title)) return 1
        if (Regex("""(?i)(?:^|[\s:_\-])0?$s(?:$|[\s:_\-])""").containsMatchIn(c.title)) return 1
        return 0
    }

    /** Release-year closeness, for the same tie: nearer year wins. Zero when
     *  either side has no year, so it never separates on missing data. */
    private fun yearCloseness(media: TrackerMedia, c: TrackerMatch): Int {
        if (media.year <= 0 || c.year <= 0) return 0
        return -kotlin.math.abs(media.year - c.year)
    }

    /** True when the watched episode fits inside the candidate's total. */
    private fun coversEpisode(media: TrackerMedia, c: TrackerMatch): Boolean =
        media.movie || media.episode <= 0 || c.total <= 0 || media.episode <= c.total

    /** Does the service's kind for this entry match what is being watched? */
    private fun kindAgrees(media: TrackerMedia, category: String): Boolean {
        val movie = media.movie || media.episode <= 0
        return if (movie) isMovieKind(category) else !isMovieKind(category)
    }

    private fun isMovieKind(category: String): Boolean {
        val c = category.lowercase()
        return c.contains("movie") || c.contains("film")
    }

    /** The tracker's view of a watch-history entry. */
    fun mediaOf(entry: HistoryEntry, year: Int = 0): TrackerMedia = TrackerMedia(
        title = entry.title,
        year = year,
        movie = entry.type == MediaType.MOVIE,
        episode = if (entry.episodeNumber > 0) entry.episodeNumber else episodeFromName(entry.episodeName),
        // A stored season is exact and wins; otherwise read the marker out of
        // the title ("…Season 5" → 5) so entries written before seasons were
        // stored still disambiguate. Never invents one: 0 keeps every
        // season-1 rule exactly as it was.
        season = entry.seasonNumber.takeIf { it > 1 }
            ?: com.hikari.app.data.seasonFromTitle(entry.title),
    )

    /**
     * The episode number out of an episode's name, for entries written before the
     * number was stored (and for extensions whose episodes are named rather than
     * numbered). A wrong answer here is worse than none: only a name that
     * actually says "episode 5"/"E5"/"S2 E5" (or is nothing but a number) is
     * read, and anything else answers 0, which makes the push skip the title.
     */
    fun episodeFromName(name: String?): Int {
        val text = name?.trim().orEmpty()
        if (text.isEmpty()) return 0
        if (text.toIntOrNull() != null) return text.toInt()
        val patterns = listOf(
            Regex("""(?i)\bs\d{1,2}\s*[ex](\d{1,4})\b"""),
            Regex("""(?i)\b(?:ep|episode|e)\s*(\d{1,4})\b"""),
            Regex("""(?i)#(\d{1,4})\b"""),
        )
        for (p in patterns) {
            val m = p.find(text) ?: continue
            val n = m.groupValues[1].toIntOrNull() ?: continue
            if (n > 0) return n
        }
        return 0
    }

    /**
     * Brings the trackers up to date from the watch history — what the card's
     * "Sync watch history" button runs.
     *
     * Only videos that were watched far enough to count are considered, newest
     * first, and the per-episode log means a second press reports the same
     * things as already reported instead of uploading them again.
     */
    suspend fun syncHistory(store: AppStore, limit: Int = 12): String {
        val accounts = runCatching { store.trackers() }.getOrDefault(emptyList())
        if (accounts.isEmpty()) return "No tracker is signed in yet."
        val history = runCatching { store.history() }.getOrDefault(emptyList())
        val watched = history
            .filter { it.type != MediaType.UNKNOWN && it.title.isNotBlank() }
            .filter { it.durationMs > 0 && it.positionMs >= (it.durationMs * WATCHED_FRACTION).toLong() }
            .sortedByDescending { it.watchedAt }
        if (watched.isEmpty()) return "Nothing watched far enough to report yet."
        val notes = ArrayList<String>()
        var reported = 0
        for (entry in watched.take(limit)) {
            val before = notes.size
            notes += pushWatched(store, mediaOf(entry), entry.uniqueKey)
            if (notes.size > before && notes.drop(before).any { !it.endsWith("already reported") }) reported++
        }
        val head = if (reported == 0) {
            "Nothing new to report (${minOf(limit, watched.size)} recent titles checked)."
        } else {
            "Reported $reported of the last ${minOf(limit, watched.size)} watched titles:"
        }
        val body = notes.distinct().take(40).joinToString("\n")
        val text = head + if (body.isBlank()) "" else "\n" + body
        runCatching { store.setTrackerLast(text) }
        val mangaText = runCatching { syncMangaHistory(store, limit) }.getOrDefault("")
        return if (mangaText.isBlank()) text else text + "\n" + mangaText
    }

    /**
     * Reports manga reading progress the same way [syncHistory] reports watched
     * videos: the newest titles with a chapter number first, each pushed once
     * (the per-chapter log deduplicates repeats). A title whose chapter number
     * cannot be determined is skipped rather than guessed at.
     */
    suspend fun syncMangaHistory(store: AppStore, limit: Int = 12): String {
        val accounts = runCatching { store.trackers() }.getOrDefault(emptyList())
        if (accounts.isEmpty()) return ""
        val progress = runCatching { MangaStore.progress() }.getOrDefault(emptyList())
        if (progress.isEmpty()) return ""
        val notes = ArrayList<String>()
        var reported = 0
        for (p in progress.take(limit)) {
            if (p.title.isBlank()) continue
            val num = runCatching {
                MangaStore.chaptersFor(p.mangaKey)?.firstOrNull { it.url == p.chapterUrl }?.number?.toInt()
            }.getOrNull()?.takeIf { it > 0 } ?: chapterNumberFromName(p.chapterName)
            if (num <= 0) continue
            val before = notes.size
            notes += pushWatched(store, TrackerMedia(title = p.title, movie = false, episode = num, manga = true), "manga|" + p.mangaKey + "|" + p.chapterUrl)
            if (notes.size > before && notes.drop(before).any { !it.endsWith("already reported") }) reported++
        }
        if (reported == 0 && notes.isEmpty()) return ""
        val head = if (reported == 0) {
            "Manga: nothing new to report."
        } else {
            "Manga: reported " + reported + " reading title(s):"
        }
        val body = notes.distinct().take(20).joinToString("\n")
        return head + if (body.isBlank()) "" else "\n" + body
    }

    /** Chapter number out of a chapter's display name ("Chapter 12", "Ch. 12", "12"). */
    fun chapterNumberFromName(name: String?): Int {
        if (name.isNullOrBlank()) return 0
        val pats = listOf(
            Regex("(?i)\\bchapter\\s*(\\d{1,4})"),
            Regex("(?i)\\bch\\.?\\s*(\\d{1,4})"),
            Regex("(?i)\\bepisode\\s*(\\d{1,4})"),
            Regex("^\\s*(\\d{1,4})\\s*$")
        )
        for (re in pats) {
            val m = re.find(name)
            if (m != null) return m.groupValues[1].toIntOrNull() ?: 0
        }
        return 0
    }

    /**
     * Asks every signed-in service who the user is — the card's "Test" button.
     * A sign-in that was revoked at the service shows up here rather than as a
     * watch that quietly never arrives.
     */
    suspend fun ping(store: AppStore): List<String> = withContext(Dispatchers.IO) {
        val accounts = runCatching { store.trackers() }.getOrDefault(emptyList())
        if (accounts.isEmpty()) return@withContext listOf("No tracker is signed in yet.")
        val clients = runCatching { store.trackerClients() }.getOrDefault(emptyList())
        accounts.map { account ->
            val client = clients.firstOrNull { it.kind == account.kind }
                ?: TrackerClient(account.kind)
            if (!client.ready) {
                "${account.kind.label}: the app id is missing — add it to sign in again"
            } else {
                val live = freshAccount(store, client, account)
                val check = TrackerApi.search(
                    client,
                    live.token,
                    TrackerMedia(title = "cowboy bebop", movie = false, episode = 1),
                )
                if (check.isSuccess) {
                    "${account.kind.label}: connected as ${account.user.ifBlank { "your account" }}"
                } else {
                    "${account.kind.label}: ${check.exceptionOrNull()?.message ?: "the service refused the request"}"
                }
            }
        }
    }
}
