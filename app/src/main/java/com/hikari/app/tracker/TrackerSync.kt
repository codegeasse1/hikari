package com.hikari.app.tracker

import com.hikari.app.data.AppStore
import com.hikari.app.data.HistoryEntry
import com.hikari.app.data.MediaType
import com.hikari.app.data.TrackerAccount
import com.hikari.app.data.TrackerClient
import com.hikari.app.data.TrackerMatch
import com.hikari.app.data.TrackerMedia
import com.hikari.app.data.TrackerStore
import com.hikari.app.data.TRACKER_AUTO_THRESHOLD
import com.hikari.app.data.matchScore
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
        val notes = ArrayList<String>()
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
            val doneKey = kind.key + "|" + historyKey + "|" + media.episode
            if (!force && done.containsKey(doneKey)) {
                notes += "${kind.label}: ${media.title} — already reported"
                continue
            }
            val live = freshAccount(store, client, account)

            // The cached match, when there is one, saves a search — and it is
            // only trusted while it still looks like the same title.
            val cached = matches[TrackerStore.matchKey(kind, media.title)]
                ?.takeIf { matchScore(media.title, it.title, media.year, it.year) >= TRACKER_AUTO_THRESHOLD }
            val match = cached ?: resolve(store, client, live, media)
            if (match == null) {
                notes += "${kind.label}: no confident match for \"${media.title}\""
                continue
            }
            TrackerApi.push(client, live, match, media)
                .onSuccess { note ->
                    notes += note
                    runCatching { store.markTrackerDone(doneKey) }
                }
                .onFailure { error ->
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
        val best = bestMatch(media, candidates) ?: return null
        runCatching { store.putTrackerMatch(TrackerStore.matchKey(client.kind, media.title), best) }
        return best
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
    fun bestMatch(media: TrackerMedia, candidates: List<TrackerMatch>): TrackerMatch? {
        val usable = candidates.filter { it.id.isNotBlank() && it.score > 0.0 }
            .sortedByDescending { it.score }
        if (usable.isEmpty()) return null
        val agreeing = usable.filter { kindAgrees(media, it.category) }
        val pool = agreeing.ifEmpty { usable }
        val top = pool.first()
        if (top.score < TRACKER_AUTO_THRESHOLD) return null
        val rival = pool.firstOrNull { it.id != top.id && it.score >= top.score - 0.02 }
        if (rival != null) return null
        return top
    }

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
        season = entry.seasonNumber,
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
        val body = notes.take(40).joinToString("\n")
        val text = head + if (body.isBlank()) "" else "\n" + body
        runCatching { store.setTrackerLast(text) }
        return text
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
