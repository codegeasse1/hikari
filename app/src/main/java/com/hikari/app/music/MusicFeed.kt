package com.hikari.app.music

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One more place the Home feed can pull a few shelves from. */
internal class FeedSource(val load: suspend () -> List<MShelf>)

/**
 * The endless part of the music Home page.
 *
 * Home first shows what YouTube Music sends back. When the person scrolls near the bottom,
 * [next] is called and brings one more batch of shelves (songs, playlists, artists, albums),
 * taken from, in this order:
 *   1. the remaining pages of the YouTube Music Home feed,
 *   2. things picked from what the person listened to (radio of recent songs, artist pages),
 *   3. charts, new releases and explore,
 *   4. a long list of genre / mood searches (songs, playlists, artists, albums).
 *
 * Every shelf is de-duplicated against what is already on screen, so the page keeps showing
 * new things for as long as the person scrolls. A source that fails is skipped, never fatal.
 */
class FeedCursor(
    private var homeToken: String?,
    chipTitle: String?,
    seed: List<MShelf>,
) {
    private val mutex = Mutex()
    private val seenItems = HashSet<String>()
    private val seenTitles = HashSet<String>()
    private val sources = ArrayDeque<FeedSource>()
    private var homePages = 0

    /** Every shelf this feed has produced so far, in order. */
    @Volatile
    var loaded: List<MShelf> = emptyList()
        private set

    /** True once there is nothing left to load. */
    @Volatile
    var done: Boolean = false
        private set

    init {
        for (s in seed) markSeen(s)
        sources.addAll(MusicFeed.buildSources(chipTitle))
    }

    private fun keyOf(item: MItem): String = item.kind.name + ":" + item.id

    private fun markSeen(shelf: MShelf) {
        val t = shelf.title.trim().lowercase()
        if (t.isNotEmpty()) seenTitles.add(t)
        for (i in shelf.items) seenItems.add(keyOf(i))
    }

    /** Caps long shelves and drops whatever the page already shows. */
    private fun tidy(raw: List<MShelf>): List<MShelf> {
        val out = ArrayList<MShelf>()
        for (shelf in raw) {
            val title = shelf.title.trim()
            if (title.isNotEmpty() && !seenTitles.add(title.lowercase())) continue
            val cap = if (shelf.asList) MAX_LIST_ROWS else MAX_CARDS
            val items = shelf.items.take(cap).filter { seenItems.add(keyOf(it)) }
            if (items.size < MIN_ITEMS) continue
            out.add(shelf.copy(items = items))
        }
        return out
    }

    private suspend fun <T> guard(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }

    /** The next batch of shelves (empty when [done] or when nothing usable came back). */
    suspend fun next(): List<MShelf> = mutex.withLock {
        var tries = 0
        while (!done && tries < MAX_TRIES) {
            tries++
            val raw: List<MShelf>
            val token = homeToken
            if (token != null && homePages < MAX_HOME_PAGES) {
                val r = guard { MusicApi.homeMore(token) }
                homePages++
                homeToken = r?.second
                raw = r?.first.orEmpty()
            } else {
                homeToken = null
                val src = sources.firstOrNull()
                if (src == null) {
                    done = true
                    break
                }
                raw = guard { src.load() }.orEmpty()
                sources.removeFirst()
            }
            val fresh = tidy(raw)
            if (fresh.isNotEmpty()) {
                loaded = loaded + fresh
                if (homeToken == null && sources.isEmpty()) done = true
                return@withLock fresh
            }
        }
        if (homeToken == null && sources.isEmpty()) done = true
        emptyList()
    }

    private companion object {
        const val MAX_TRIES = 5
        const val MAX_HOME_PAGES = 8
        const val MAX_LIST_ROWS = 10
        const val MAX_CARDS = 20
        const val MIN_ITEMS = 3
    }
}

object MusicFeed {
    /**
     * The feed behind the normal Home page (no mood chip selected). Kept here so that switching
     * tabs and coming back continues where it stopped instead of starting over.
     */
    var home: FeedCursor? = null

    private class Query(val title: String, val query: String, val kind: MKind)

    private fun song(s: MSong): MItem = MItem(
        MKind.SONG,
        s.id,
        s.title,
        listOf(s.artist, s.album).filter { it.isNotBlank() }.joinToString(" • "),
        s.thumb,
        s,
    )

    private fun filterFor(kind: MKind): String = when (kind) {
        MKind.SONG -> MusicApi.FILTER_SONG
        MKind.ALBUM -> MusicApi.FILTER_ALBUM
        MKind.ARTIST -> MusicApi.FILTER_ARTIST
        MKind.PLAYLIST -> MusicApi.FILTER_PLAYLIST
        MKind.VIDEO -> MusicApi.FILTER_VIDEO
    }

    private fun searchSource(q: Query) = FeedSource {
        val items = MusicApi.search(q.query, filterFor(q.kind)).flatMap { it.items }.filter { it.kind == q.kind }
        listOf(MShelf(q.title, items, q.kind == MKind.SONG))
    }

    private fun browseSource(browseId: String, fallbackTitle: String) = FeedSource {
        MusicApi.browseShelves(browseId).first.map {
            if (it.title.isBlank()) it.copy(title = fallbackTitle) else it
        }
    }

    /** "Because you played ..." : the YouTube Music radio of one song the person played. */
    private fun radioSource(seed: MSong) = FeedSource {
        val songs = MusicApi.radio(seed.id).take(12)
        listOf(MShelf("Because you played " + seed.title, songs.map { song(it) }, true))
    }

    /** An artist the person plays a lot: their songs, plus the page's albums / similar artists. */
    private fun artistSource(name: String) = FeedSource {
        val hit = MusicApi.search(name, MusicApi.FILTER_ARTIST).flatMap { it.items }
            .firstOrNull { it.kind == MKind.ARTIST }
        if (hit == null) {
            emptyList()
        } else {
            val d = MusicApi.detail(hit.id, hit.title, hit.thumb)
            val out = ArrayList<MShelf>()
            if (d.songs.size >= 3) out.add(MShelf("More from " + hit.title, d.songs.take(10).map { song(it) }, true))
            val more = d.shelves
                .filter { it.items.size >= 3 }
                .sortedByDescending { it.title.contains("like", ignoreCase = true) }
                .take(3)
            for (s in more) {
                val t = s.title.trim()
                out.add(s.copy(title = if (t.isEmpty()) hit.title else hit.title + " · " + t))
            }
            out
        }
    }

    private fun primaryArtist(raw: String): String =
        raw.split(",", "&", " x ", " feat.", " ft.").firstOrNull().orEmpty().trim()

    /** Alternates the two lists: a0, b0, a1, b1 ... then whatever is left. */
    private fun <T> interleave(a: List<T>, b: List<T>): List<T> {
        val out = ArrayList<T>(a.size + b.size)
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            if (i < a.size) out.add(a[i])
            if (i < b.size) out.add(b[i])
        }
        return out
    }

    private fun mk(kind: MKind, title: String, query: String) = Query(title, query, kind)

    private val SONG_QUERIES = listOf(
        mk(MKind.SONG, "Trending songs", "trending songs"),
        mk(MKind.SONG, "Bollywood hits", "bollywood hit songs"),
        mk(MKind.SONG, "Bangla hits", "bangla new songs"),
        mk(MKind.SONG, "Hip-hop heat", "hip hop hits"),
        mk(MKind.SONG, "Chill vibes", "chill songs"),
        mk(MKind.SONG, "Throwback hits", "90s hits"),
        mk(MKind.SONG, "Workout energy", "workout songs"),
        mk(MKind.SONG, "Romantic songs", "romantic songs"),
        mk(MKind.SONG, "K-pop favourites", "kpop songs"),
        mk(MKind.SONG, "Lo-fi beats", "lofi songs"),
        mk(MKind.SONG, "Sad songs", "sad songs"),
        mk(MKind.SONG, "Party starters", "party songs"),
        mk(MKind.SONG, "Punjabi beats", "punjabi hit songs"),
        mk(MKind.SONG, "Acoustic & unplugged", "acoustic unplugged songs"),
    )

    private val PLAYLIST_QUERIES = listOf(
        mk(MKind.PLAYLIST, "Popular playlists", "top hits playlist"),
        mk(MKind.PLAYLIST, "Bollywood playlists", "bollywood playlist"),
        mk(MKind.PLAYLIST, "Chill playlists", "chill playlist"),
        mk(MKind.PLAYLIST, "Workout playlists", "workout playlist"),
        mk(MKind.PLAYLIST, "Party playlists", "party playlist"),
        mk(MKind.PLAYLIST, "Bangla playlists", "bangla playlist"),
        mk(MKind.PLAYLIST, "Focus & study", "study music playlist"),
        mk(MKind.PLAYLIST, "Road trip", "road trip playlist"),
        mk(MKind.PLAYLIST, "Late night", "late night playlist"),
        mk(MKind.PLAYLIST, "Sleep", "sleep music playlist"),
    )

    private val ARTIST_QUERIES = listOf(
        mk(MKind.ARTIST, "Popular Bollywood singers", "bollywood singers"),
        mk(MKind.ARTIST, "Bengali singers", "bengali singers"),
        mk(MKind.ARTIST, "Pop stars", "pop singers"),
        mk(MKind.ARTIST, "Rap artists", "rappers"),
        mk(MKind.ARTIST, "Rock bands", "rock bands"),
        mk(MKind.ARTIST, "K-pop artists", "kpop artists"),
        mk(MKind.ARTIST, "Indie artists", "indie artists"),
        mk(MKind.ARTIST, "Legendary voices", "legendary singers"),
    )

    private val ALBUM_QUERIES = listOf(
        mk(MKind.ALBUM, "New Hindi albums", "new hindi albums"),
        mk(MKind.ALBUM, "Pop albums", "best pop albums"),
        mk(MKind.ALBUM, "Bangla albums", "bangla albums"),
        mk(MKind.ALBUM, "Hip-hop albums", "best hip hop albums"),
        mk(MKind.ALBUM, "Chill albums", "chill albums"),
        mk(MKind.ALBUM, "Rock albums", "classic rock albums"),
    )

    /** Songs, playlists, artists, albums, songs, playlists ... each kind in a fresh random order. */
    private fun genreSources(): List<FeedSource> {
        val songs = SONG_QUERIES.shuffled()
        val lists = PLAYLIST_QUERIES.shuffled()
        val artists = ARTIST_QUERIES.shuffled()
        val albums = ALBUM_QUERIES.shuffled()
        val mixed = interleave(interleave(songs, artists), interleave(lists, albums))
        return mixed.map { searchSource(it) }
    }

    internal fun buildSources(chipTitle: String?): List<FeedSource> {
        // A mood chip is selected (Chill, Workout...): fill the page with that mood only.
        if (!chipTitle.isNullOrBlank()) {
            val t = chipTitle.trim()
            val queries = listOf(
                Query("$t songs", "$t songs", MKind.SONG),
                Query("$t playlists", "$t playlists", MKind.PLAYLIST),
                Query("$t artists", "$t artists", MKind.ARTIST),
                Query("$t albums", "$t albums", MKind.ALBUM),
                Query("Best $t songs", "best $t songs", MKind.SONG),
                Query("New $t songs", "new $t songs", MKind.SONG),
                Query("$t mixes", "$t mix", MKind.PLAYLIST),
            )
            return queries.map { searchSource(it) }
        }

        val plays = MusicStore.plays.value
        val recent = plays.sortedByDescending { it.last }.map { it.song }.distinctBy { it.id }.take(3)
        val topArtists = plays
            .groupBy { primaryArtist(it.song.artist) }
            .filterKeys { it.isNotBlank() }
            .mapValues { e -> e.value.sumOf { it.count } }
            .entries
            .sortedByDescending { it.value }
            .take(2)
            .map { it.key }

        val personal = ArrayList<FeedSource>()
        recent.getOrNull(0)?.let { personal.add(radioSource(it)) }
        topArtists.getOrNull(0)?.let { personal.add(artistSource(it)) }
        recent.getOrNull(1)?.let { personal.add(radioSource(it)) }
        topArtists.getOrNull(1)?.let { personal.add(artistSource(it)) }
        recent.getOrNull(2)?.let { personal.add(radioSource(it)) }

        val generic = listOf(
            browseSource("FEmusic_charts", "Charts"),
            browseSource("FEmusic_new_releases_albums", "New releases"),
            browseSource("FEmusic_explore", "Explore"),
        )
        return interleave(personal, generic) + genreSources()
    }
}
