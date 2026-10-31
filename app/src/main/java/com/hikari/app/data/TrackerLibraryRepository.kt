package com.hikari.app.data

import com.hikari.app.HikariApp
import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

object TrackerLibraryRepository {
    data class Shelf(val key: String, val title: String, val items: List<MediaItem>)

    /** Why a tracker pick last produced nothing, keyed by pick key
     *  (`tracker:trakt`) — read by Home's empty state so a dead login says
     *  so instead of a blank "Couldn't load". */
    val errors = java.util.concurrent.ConcurrentHashMap<String, String>()

    suspend fun load(app: HikariApp, sourceOverride: String? = null): Result<List<Shelf>> = withContext(Dispatchers.IO) {
        val key = (sourceOverride ?: runCatching { app.store.trackerLibrarySource() }.getOrDefault("nuvio")).lowercase()
        val r = when (key) {
            "trakt" -> loadTrakt(app)
            "simkl" -> loadSimkl(app)
            "anilist" -> loadAniList(app)
            "mal" -> loadMal(app)
            "kitsu" -> loadKitsu(app)
            "shikimori" -> loadShikimori(app)
            "mdblist" -> Result.failure(IllegalStateException("MDBList library requires a connected MDBList account."))
            else -> Result.success(emptyList())
        }
        r.onFailure { errors["tracker:$key"] = it.message ?: "couldn't load" }
            .onSuccess { shelves ->
                if (shelves.isEmpty()) {
                    // Connected but nothing to show — the blank catalogue the
                    // user reported. A note (not silence) so Home's empty
                    // state and the next log both say it.
                    errors["tracker:$key"] =
                        "Connected, but its watchlist, history, collection and lists are all empty — add something on that service first."
                } else {
                    errors.remove("tracker:$key")
                }
                Logs.log("Tracker", "$key: ${shelves.size} shelves")
            }
        r
    }

    private suspend fun loadTrakt(app: HikariApp): Result<List<Shelf>> {
        val account = app.store.trackers().firstOrNull { it.kind == TrackerKind.TRAKT }
            ?: return Result.failure(IllegalStateException("Connect Trakt in Settings → Trackers first."))
        val client = app.store.trackerClients().firstOrNull { it.kind == TrackerKind.TRAKT }
            ?: TrackerClient(TrackerKind.TRAKT)
        if (!client.ready) return Result.failure(IllegalStateException("Trakt app credentials are not configured."))
        val headers = mapOf("Authorization" to "Bearer ${account.token}", "trakt-api-version" to "2", "trakt-api-key" to client.id)
        var user = account.user
        if (user.isBlank()) {
            user = runCatching {
                Http.get("https://api.trakt.tv/users/settings", headers).use { r ->
                    if (r.code == 401) return Result.failure(IllegalStateException("Trakt login expired (HTTP 401) — reconnect Trakt in Settings → Trackers."))
                    if (!r.isSuccessful) return Result.failure(IllegalStateException("Trakt username is unavailable (HTTP ${r.code}); reconnect Trakt."))
                    org.json.JSONObject(r.body?.string().orEmpty()).optJSONObject("user")?.optString("username").orEmpty()
                }
            }.getOrDefault("")
        }
        // "me" is Trakt's own alias for the token owner — when the stored
        // username is stale (rename) or missing, the endpoints below still
        // answer under "me" instead of failing the whole catalogue.
        val who = listOf(user.takeIf { it.isNotBlank() }.orEmpty(), "me").distinct().filter { it.isNotBlank() }
        if (who.isEmpty()) return Result.failure(IllegalStateException("Trakt username is unavailable; reconnect Trakt."))
        val out = ArrayList<Shelf>()

        // One wave now asks all endpoints at once (see below), so the shared
        // state here is thread-safe: an atomic 401 flag and a synchronized
        // diagnosis list. Every endpoint always reports (HTTP + raw + kept),
        // even at zero — the old code only logged when raw > 0, so a
        // fully-empty account and six failed requests looked identical in the
        // log ("trakt shelves=0" with no reason). Failures answer null;
        // empties answer an empty array.
        //
        // `extended=full,images` (not bare `full`): Trakt strips the `images`
        // object without the images flag, which is exactly how a shelf of
        // real rows ended up with blank clapperboard posters.
        val traktUnauthorized = java.util.concurrent.atomic.AtomicBoolean(false)
        val diag = java.util.Collections.synchronizedList(ArrayList<String>())
        suspend fun get(path: String, type: MediaType): List<MediaItem> {
            for (u in who) {
                val r = runCatching {
                    Http.get("https://api.trakt.tv/users/${enc(u)}$path", headers)
                }.getOrNull() ?: run {
                    diag += "$path: network error"; continue
                }
                r.use { res ->
                    if (res.code == 401) {
                        traktUnauthorized.set(true)
                        diag += "$path: HTTP 401"
                        return@use
                    }
                    if (!res.isSuccessful) {
                        diag += "$path: HTTP ${res.code}"
                        return@use
                    }
                    val arr = runCatching { JSONArray(res.body?.string().orEmpty()) }.getOrDefault(JSONArray())
                    val kept = buildList {
                        for (i in 0 until arr.length()) {
                            traktItem(arr.optJSONObject(i), type)?.let(::add)
                        }
                    }
                    Logs.log("Tracker", "trakt $path raw=" + arr.length() + " kept=" + kept.size)
                    diag += "$path: raw=" + arr.length() + " kept=" + kept.size
                    if (arr.length() > 0 || u == who.last()) return kept
                }
            }
            return emptyList()
        }

        // Ten endpoints, one wave: the old code walked them one after
        // another on a single worker (~6s in the log before the first shelf
        // could draw). Same requests, same parsing — just concurrently.
        val movies: List<MediaItem>
        val shows: List<MediaItem>
        val watchedMovies: List<MediaItem>
        val historyMovies: List<MediaItem>
        val watchedShows: List<MediaItem>
        val historyShows: List<MediaItem>
        val collectionMovies: List<MediaItem>
        val collectionShows: List<MediaItem>
        val favoriteMovies: List<MediaItem>
        val favoriteShows: List<MediaItem>
        kotlinx.coroutines.coroutineScope {
            val wm = async { get("/watchlist/movies?extended=full,images&page=1&limit=250", MediaType.MOVIE) }
            val ws = async { get("/watchlist/shows?extended=full,images&page=1&limit=250", MediaType.SERIES) }
            val wmed = async { get("/watched/movies?extended=full,images&page=1&limit=250", MediaType.MOVIE) }
            val hmed = async { get("/history/movies?extended=full,images&page=1&limit=250", MediaType.MOVIE) }
            val wsh = async { get("/watched/shows?extended=full,images&page=1&limit=250", MediaType.SERIES) }
            val hsh = async { get("/history/shows?extended=full,images&page=1&limit=250", MediaType.SERIES) }
            val cm = async { get("/collection/movies?extended=full,images&page=1&limit=250", MediaType.MOVIE) }
            val cs = async { get("/collection/shows?extended=full,images&page=1&limit=250", MediaType.SERIES) }
            val fm = async { get("/favorites/movies?extended=full,images&page=1&limit=250", MediaType.MOVIE) }
            val fs = async { get("/favorites/shows?extended=full,images&page=1&limit=250", MediaType.SERIES) }
            movies = wm.await()
            shows = ws.await()
            watchedMovies = wmed.await()
            historyMovies = hmed.await()
            watchedShows = wsh.await()
            historyShows = hsh.await()
            collectionMovies = cm.await()
            collectionShows = cs.await()
            favoriteMovies = fm.await()
            favoriteShows = fs.await()
        }
        if (movies.isNotEmpty()) out += Shelf("trakt.watchlist.movies", "Trakt Watchlist · Movies", movies)
        if (shows.isNotEmpty()) out += Shelf("trakt.watchlist.shows", "Trakt Watchlist · Shows", shows)
        // Watched (all-time) AND history (recent plays): sync pushes to
        // history, so an account whose history lives there must show it even
        // when the watched roll-up is empty.
        val hm = (watchedMovies + historyMovies).distinctBy { it.uniqueId }
        if (hm.isNotEmpty()) out += Shelf("trakt.history.movies", "Trakt History · Movies", hm)
        val hs = (watchedShows + historyShows).distinctBy { it.uniqueId }
        if (hs.isNotEmpty()) out += Shelf("trakt.history.shows", "Trakt History · Shows", hs)
        val cm = collectionMovies.distinctBy { it.uniqueId }
        if (cm.isNotEmpty()) out += Shelf("trakt.collection.movies", "Trakt Collection · Movies", cm)
        val cs = collectionShows.distinctBy { it.uniqueId }
        if (cs.isNotEmpty()) out += Shelf("trakt.collection.shows", "Trakt Collection · Shows", cs)
        val fm = favoriteMovies.distinctBy { it.uniqueId }
        if (fm.isNotEmpty()) out += Shelf("trakt.favorites.movies", "Trakt Favorites · Movies", fm)
        val fs = favoriteShows.distinctBy { it.uniqueId }
        if (fs.isNotEmpty()) out += Shelf("trakt.favorites.shows", "Trakt Favorites · Shows", fs)

        val lists = runCatching {
            Http.get("https://api.trakt.tv/users/${enc(who.first())}/lists", headers).use { response ->
                if (!response.isSuccessful) JSONArray() else JSONArray(response.body?.string().orEmpty())
            }
        }.getOrDefault(JSONArray())

        for (i in 0 until lists.length()) {
            val list = lists.optJSONObject(i) ?: continue
            val ids = list.optJSONObject("ids")
            val slug = ids?.optString("slug").orEmpty().ifBlank {
                list.optString("name").lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            }
            if (slug.isBlank()) continue
            val response = runCatching {
                Http.get("https://api.trakt.tv/users/${enc(who.first())}/lists/${enc(slug)}/items?extended=full,images&page=1&limit=1000", headers)
            }.getOrNull() ?: continue
            if (!response.isSuccessful) continue
            val array = runCatching { response.use { JSONArray(it.body?.string().orEmpty()) } }.getOrNull() ?: continue
            val items = buildList {
                for (j in 0 until array.length()) {
                    val row = array.optJSONObject(j) ?: continue
                    val type = row.optString("type")
                    // Season/episode rows belong to their show — resolve the
                    // show object so custom lists of episodes still shelf.
                    val media = row.optJSONObject(type)
                        ?: row.optJSONObject("show")
                        ?: row.optJSONObject("movie")
                        ?: continue
                    val mt = if (type == "movie") MediaType.MOVIE else MediaType.SERIES
                    traktMedia(media, mt)?.let(::add)
                }
            }.distinctBy { it.uniqueId }
            if (items.isNotEmpty()) out += Shelf("trakt.list.$slug", list.optString("name", slug), items)
        }
        if (out.isNotEmpty()) enrichTraktPosters(out)
        if (out.isEmpty() && traktUnauthorized.get()) return Result.failure(IllegalStateException("Trakt login expired (HTTP 401) — reconnect Trakt in Settings → Trackers."))
        if (out.isEmpty()) {
            // Not silent: the log + empty-state now say whether the account
            // is truly empty or every endpoint failed.
            Logs.log("Tracker", "trakt empty: " + diag.joinToString(" | ").take(1000))
            errors["tracker:trakt"] =
                "Trakt answered but every shelf came back empty (" + diag.take(6).joinToString("; ").take(300) + ") — add something on Trakt first, or reconnect if this looks wrong."
        }
        Logs.log("Tracker", "trakt shelves=" + out.size + out.joinToString("") { " · " + it.title + "=" + it.items.size })
        return Result.success(out)
    }

    private suspend fun loadSimkl(app: HikariApp): Result<List<Shelf>> {
        val account = app.store.trackers().firstOrNull { it.kind == TrackerKind.SIMKL }
            ?: return Result.failure(IllegalStateException("Connect Simkl in Settings → Trackers first."))
        val client = app.store.trackerClients().firstOrNull { it.kind == TrackerKind.SIMKL }
            ?: TrackerClient(TrackerKind.SIMKL)
        if (!client.ready) return Result.failure(IllegalStateException("Simkl app credentials are not configured."))
        val response = runCatching {
            Http.get("https://api.simkl.com/sync/all-items/?extended=full", mapOf(
                "Authorization" to "Bearer ${account.token}",
                "simkl-api-key" to client.id,
            ))
        }.getOrElse { return Result.failure(it) }
        if (!response.isSuccessful) return Result.failure(IllegalStateException("Simkl returned HTTP ${response.code}" + if (response.code == 401) " — reconnect Simkl in Settings → Trackers." else "."))
        val root = JSONObject(response.body?.string().orEmpty())
        val out = ArrayList<Shelf>()

        fun parse(key: String, title: String, type: MediaType, anime: Boolean = false, manga: Boolean = false) {
            val array = root.optJSONArray(key) ?: return
            val items = buildList {
                for (i in 0 until array.length()) {
                    val row = array.optJSONObject(i) ?: continue
                    val item = row.optJSONObject("movie") ?: row.optJSONObject("show") ?: row.optJSONObject("anime") ?: row.optJSONObject("manga") ?: row
                    simklMedia(item, type, anime, manga)?.let(::add)
                }
            }.distinctBy { it.uniqueId }
            if (items.isNotEmpty()) out += Shelf("simkl.$key", title, items)
        }
        parse("movies", "Simkl · Movies", MediaType.MOVIE)
        parse("tv_shows", "Simkl · TV Shows", MediaType.SERIES)
        parse("anime", "Simkl · Anime", MediaType.SERIES, anime = true)
        parse("manga", "Simkl · Manga", MediaType.SERIES, manga = true)
        return Result.success(out)
    }


    private suspend fun loadAniList(app: HikariApp): Result<List<Shelf>> {
        val account=app.store.trackers().firstOrNull{it.kind==TrackerKind.ANILIST}
            ?: return Result.failure(IllegalStateException("Connect AniList in Settings \u2192 Trackers first."))
        val out=ArrayList<Shelf>()
        // Anime lists, then manga lists: a tracker that also holds manga shows
        // both — manga rows carry rawType "manga", which is what sends their
        // tap to the manga engines instead of the video detail page.
        val animeLists=aniListCollection(app, account, "ANIME")
            ?: return Result.failure(IllegalStateException("AniList did not return a library."))
        for(i in 0 until animeLists.length()){
            val l=animeLists.optJSONObject(i)?:continue;val name=l.optString("name").ifBlank{"AniList"};val a=l.optJSONArray("entries")?:JSONArray()
            val items=buildList{for(j in 0 until a.length())aniListMedia(a.optJSONObject(j)?.optJSONObject("media"))?.let(::add)}.distinctBy{it.uniqueId}
            if(items.isNotEmpty())out+=Shelf("anilist."+i+"."+name,name,items)
        }
        val mangaLists=aniListCollection(app, account, "MANGA") ?: JSONArray()
        for(i in 0 until mangaLists.length()){
            val l=mangaLists.optJSONObject(i)?:continue;val name=l.optString("name").ifBlank{"AniList Manga"};val a=l.optJSONArray("entries")?:JSONArray()
            val items=buildList{for(j in 0 until a.length())aniListMedia(a.optJSONObject(j)?.optJSONObject("media"), manga=true)?.let(::add)}.distinctBy{it.uniqueId}
            if(items.isNotEmpty())out+=Shelf("anilist.manga."+i+"."+name,name,items)
        }
        return Result.success(out)
    }
    private suspend fun aniListCollection(app: HikariApp, account: com.hikari.app.data.TrackerAccount, type: String): JSONArray? {
        val query = "query(\$name:String){MediaListCollection(userName: \$name,type:"+type+"){lists{name entries{media{id title{userPreferred english romaji} coverImage{large} startDate{year} averageScore nextAiringEpisode{airingAt episode}}}}}}"
        // AniList's MediaListCollection is user-specific. The access token
        // must be sent as a Bearer token; without it a connected account can still
        // return an empty/unauthorized collection.
        val raw = Http.postStringQuiet(
            "https://graphql.anilist.co",
            JSONObject()
                .put("query", query)
                .put("variables", JSONObject().put("name", anilistUser(app, account)))
                .toString(),
            mapOf(
                "Authorization" to "Bearer " + account.token,
                "Accept" to "application/json",
                "Content-Type" to "application/json",
            ),
        ) ?: return null
        return runCatching {
            JSONObject(raw).optJSONObject("data")?.optJSONObject("MediaListCollection")?.optJSONArray("lists")?:JSONArray()
        }.getOrDefault(JSONArray())
    }
    private suspend fun anilistUser(app: HikariApp, account: com.hikari.app.data.TrackerAccount): String {
        if (account.user.isNotBlank()) return account.user
        val raw = Http.postStringQuiet(
            "https://graphql.anilist.co",
            JSONObject().put("query", "{Viewer{name}}").toString(),
            mapOf("Authorization" to "Bearer " + account.token, "Accept" to "application/json", "Content-Type" to "application/json"),
        ) ?: return ""
        return JSONObject(raw).optJSONObject("data")?.optJSONObject("Viewer")?.optString("name").orEmpty()
    }
    private suspend fun loadMal(app:HikariApp):Result<List<Shelf>>{
        val account=app.store.trackers().firstOrNull{it.kind==TrackerKind.MAL}
            ?:return Result.failure(IllegalStateException("Connect MyAnimeList in Settings → Trackers first."))
        // One authenticated probe so an expired token reports 401 instead of
        // five silent empty shelves.
        runCatching { Http.get("https://api.myanimelist.net/v2/users/@me", mapOf("Authorization" to "Bearer "+account.token)) }.getOrNull()?.let {
            if (it.code == 401) return Result.failure(IllegalStateException("MyAnimeList login expired (HTTP 401) — reconnect MAL in Settings → Trackers."))
        }
        val statuses=listOf("watching" to "Watching","plan_to_watch" to "Plan to Watch","completed" to "Completed","on_hold" to "On Hold","dropped" to "Dropped")
        val out=ArrayList<Shelf>()
        for((status,label) in statuses){
            val raw=Http.getStringQuiet("https://api.myanimelist.net/v2/users/@me/animelist?status="+status+"&limit=1000&fields=id,title,main_picture,start_date,mean",mapOf("Authorization" to "Bearer "+account.token))?:continue
            val a=JSONObject(raw).optJSONArray("data")?:continue
            val items=buildList{for(i in 0 until a.length())malMedia(a.optJSONObject(i)?.optJSONObject("node"))?.let(::add)}
            if(items.isNotEmpty())out+=Shelf("mal."+status,label,items)
        }
        // Manga lists live on a separate endpoint with their own statuses.
        val mangaStatuses=listOf("reading" to "Reading","plan_to_read" to "Plan to Read","completed" to "Completed","on_hold" to "On Hold","dropped" to "Dropped")
        for((status,label) in mangaStatuses){
            val raw=Http.getStringQuiet("https://api.myanimelist.net/v2/users/@me/mangalist?status="+status+"&limit=1000&fields=id,title,main_picture,start_date,mean",mapOf("Authorization" to "Bearer "+account.token))?:continue
            val a=JSONObject(raw).optJSONArray("data")?:continue
            val items=buildList{for(i in 0 until a.length())malMedia(a.optJSONObject(i)?.optJSONObject("node"), manga=true)?.let(::add)}
            if(items.isNotEmpty())out+=Shelf("mal.manga."+status,"Manga · "+label,items)
        }
        return Result.success(out)
    }
    private suspend fun loadKitsu(app:HikariApp):Result<List<Shelf>>{
        val account=app.store.trackers().firstOrNull{it.kind==TrackerKind.KITSU}
            ?:return Result.failure(IllegalStateException("Connect Kitsu in Settings → Trackers first."))
        if(account.userId.isBlank())return Result.failure(IllegalStateException("Kitsu user id is unavailable; reconnect Kitsu."))
        runCatching { Http.get("https://kitsu.io/api/edge/users/${enc(account.userId)}", mapOf("Authorization" to "Bearer "+account.token)) }.getOrNull()?.let {
            if (it.code == 401) return Result.failure(IllegalStateException("Kitsu login expired (HTTP 401) — reconnect Kitsu in Settings → Trackers."))
        }
        val raw=Http.getStringQuiet("https://kitsu.io/api/edge/users/"+enc(account.userId)+"/library-entries?page%5Blimit%5D=500&include=anime",mapOf("Authorization" to "Bearer "+account.token))
            ?:return Result.failure(IllegalStateException("Kitsu library could not be loaded — check the connection, then reconnect Kitsu if it stays empty."))
        val root=JSONObject(raw);val data=root.optJSONArray("data")?:JSONArray();val inc=root.optJSONArray("included")?:JSONArray();val anime=HashMap<String,JSONObject>()
        for(i in 0 until inc.length()){val o=inc.optJSONObject(i)?:continue;if(o.optString("type")=="anime")anime[o.optString("id")]=o}
        val groups=linkedMapOf<String,MutableList<MediaItem>>()
        for(i in 0 until data.length()){
            val e=data.optJSONObject(i)?:continue;val at=e.optJSONObject("attributes")?:continue
            val rel=e.optJSONObject("relationships")?.optJSONObject("anime")?.optJSONObject("data");val o=rel?.optString("id")?.let{anime[it]}?:continue;val aa=o.optJSONObject("attributes")?:continue
            val score=at.optDouble("ratingTwenty",0.0).takeIf{it>0}?.div(2.0)
            val item=MediaItem("kitsu",o.optString("id"),aa.optString("canonicalTitle").ifBlank{"Untitled"},MediaType.SERIES,aa.optJSONObject("posterImage")?.optString("original"),rawType="anime",rating=score,metadataSource="Kitsu")
            groups.getOrPut(at.optString("status").ifBlank{"unknown"}){ArrayList()}.add(item)
        }
        val shelves=ArrayList<Shelf>(groups.map { entry -> val status = entry.key; Shelf("kitsu."+status, "Kitsu · "+status.replace('_',' ').replaceFirstChar{it.uppercase()}, entry.value) })
        // Manga entries live beside the anime ones, keyed the same way but
        // including manga instead of anime.
        runCatching {
            val mraw=Http.getStringQuiet("https://kitsu.io/api/edge/users/"+enc(account.userId)+"/library-entries?page%5Blimit%5D=500&include=manga",mapOf("Authorization" to "Bearer "+account.token))
            if(!mraw.isNullOrBlank()){
                val mroot=JSONObject(mraw);val mdata=mroot.optJSONArray("data")?:JSONArray();val minc=mroot.optJSONArray("included")?:JSONArray();val manga=HashMap<String,JSONObject>()
                for(i in 0 until minc.length()){val o=minc.optJSONObject(i)?:continue;if(o.optString("type")=="manga")manga[o.optString("id")]=o}
                val mgroups=linkedMapOf<String,MutableList<MediaItem>>()
                for(i in 0 until mdata.length()){
                    val e=mdata.optJSONObject(i)?:continue;val at=e.optJSONObject("attributes")?:continue
                    val rel=e.optJSONObject("relationships")?.optJSONObject("manga")?.optJSONObject("data");val o=rel?.optString("id")?.let{manga[it]}?:continue;val aa=o.optJSONObject("attributes")?:continue
                    val score=at.optDouble("ratingTwenty",0.0).takeIf{it>0}?.div(2.0)
                    val item=MediaItem("kitsu",o.optString("id"),aa.optString("canonicalTitle").ifBlank{"Untitled"},MediaType.SERIES,aa.optJSONObject("posterImage")?.optString("original"),rawType="manga",rating=score,metadataSource="Kitsu")
                    mgroups.getOrPut(at.optString("status").ifBlank{"unknown"}){ArrayList()}.add(item)
                }
                mgroups.forEach { entry -> val status = entry.key; shelves += Shelf("kitsu.manga."+status, "Kitsu · Manga · "+status.replace('_',' ').replaceFirstChar{it.uppercase()}, entry.value) }
            }
        }
        return Result.success(shelves)
    }
    private suspend fun loadShikimori(app:HikariApp):Result<List<Shelf>>{
        val account=app.store.trackers().firstOrNull{it.kind==TrackerKind.SHIKIMORI}
            ?:return Result.failure(IllegalStateException("Connect Shikimori in Settings → Trackers first."))
        runCatching { Http.get("https://shikimori.one/api/users/whoami", mapOf("Authorization" to "Bearer "+account.token)) }.getOrNull()?.let {
            if (it.code == 401) return Result.failure(IllegalStateException("Shikimori login expired (HTTP 401) — reconnect Shikimori in Settings → Trackers."))
        }
        val uid=account.userId.ifBlank{
            val raw=Http.getStringQuiet("https://shikimori.one/api/users/whoami",mapOf("Authorization" to "Bearer "+account.token))
            JSONObject(raw?:return Result.failure(IllegalStateException("Shikimori account id is unavailable — reconnect Shikimori."))).optInt("id",0).toString()
        }
        suspend fun pages(target: String, status: String, key: String): List<org.json.JSONObject> {
            val rows = ArrayList<org.json.JSONObject>()
            for (page in 1..20) {
                val raw=Http.getStringQuiet("https://shikimori.one/api/user_rates?user_id="+uid+"&target_type="+target+"&status="+status+"&limit=100&page="+page,mapOf("Authorization" to "Bearer "+account.token))?:break
                val a=runCatching { JSONArray(raw) }.getOrNull() ?: break
                if (a.length() == 0) break
                for(i in 0 until a.length()){ a.optJSONObject(i)?.let { rows += it } }
                if (a.length() < 100) break
            }
            return rows
        }
        val statuses=listOf("watching" to "Watching","planned" to "Planned","completed" to "Completed","on_hold" to "On Hold","dropped" to "Dropped","rewatching" to "Rewatching")
        val out=ArrayList<Shelf>()
        for((status,label) in statuses){
            val rows = pages("Anime", status, "anime")
            val items=buildList{for(r in rows)shikiMedia(r.optJSONObject("anime"))?.let(::add)}.distinctBy { it.uniqueId }
            if(items.isNotEmpty())out+=Shelf("shikimori."+status,"Shikimori · "+label,items)
        }
        for((status,label) in statuses){
            val rows = pages("Manga", status, "manga")
            val items=buildList{for(r in rows)shikiMedia(r.optJSONObject("manga"), manga=true)?.let(::add)}.distinctBy { it.uniqueId }
            if(items.isNotEmpty())out+=Shelf("shikimori.manga."+status,"Shikimori · Manga · "+label,items)
        }
        return Result.success(out)
    }
    private fun aniListMedia(o:JSONObject?, manga: Boolean = false):MediaItem?{
        if(o==null)return null;val id=o.optInt("id",0);if(id<=0)return null;val t=o.optJSONObject("title")
        val title=t?.optString("userPreferred").orEmpty().ifBlank{t?.optString("english").orEmpty()}.ifBlank{t?.optString("romaji").orEmpty()}.ifBlank{"Untitled"}
        // English title as the search key; display stays the tracker's own
        // (see AnimeCatalogRepository — romaji display titles match no
        // database, which is what left tracker episodes bare).
        val english=t?.optString("english")?.trim().orEmpty().takeIf{it.isNotBlank()&&!it.equals(title,ignoreCase=true)}.orEmpty()
        val next=o.optJSONObject("nextAiringEpisode")?.optLong("airingAt",0L)?.takeIf{it>0}?.let{java.time.Instant.ofEpochSecond(it).toString()}
        return MediaItem("anilist",id.toString(),title,MediaType.SERIES,o.optJSONObject("coverImage")?.optString("large"),o.optJSONObject("startDate")?.optInt("year",0)?.takeIf{it>0},rawType=if(manga)"manga" else "anime",rating=o.optDouble("averageScore",0.0).takeIf{it>0}?.div(10.0),nextEpisodeDate=next,metadataSource="AniList",originalTitle=english)
    }
    private fun malMedia(o:JSONObject?, manga: Boolean = false):MediaItem?{
        if(o==null)return null;val id=o.optInt("id",0);if(id<=0)return null
        return MediaItem("mal",id.toString(),o.optString("title").ifBlank{o.optJSONObject("title")?.optString("title").orEmpty()}.ifBlank{"Untitled"},MediaType.SERIES,o.optJSONObject("main_picture")?.optString("large")?.ifBlank{o.optJSONObject("main_picture")?.optString("medium")},o.optString("start_date").take(4).toIntOrNull(),rawType=if(manga)"manga" else "anime",rating=o.optDouble("mean",0.0).takeIf{it>0},metadataSource="MyAnimeList")
    }
    private fun shikiMedia(o:JSONObject?, manga: Boolean = false):MediaItem?{
        if(o==null)return null;val id=o.optInt("id",0);if(id<=0)return null
        val rawImg = o.optString("image").trim().ifBlank{o.optJSONObject("image")?.optString("original").orEmpty()}
        val img = when {
            rawImg.startsWith("http") -> rawImg
            rawImg.startsWith("//") -> "https:$rawImg"
            rawImg.startsWith("/") -> "https://shikimori.one$rawImg"
            rawImg.isNotBlank() -> "https://shikimori.one/$rawImg"
            else -> null
        }
        return MediaItem("shikimori",id.toString(),o.optString("name").ifBlank{o.optString("russian").ifBlank{"Untitled"}},MediaType.SERIES,img,o.optString("aired_on").take(4).toIntOrNull(),rawType=if(manga)"manga" else "anime",metadataSource="Shikimori")
    }
    private fun traktItem(row: JSONObject?, type: MediaType): MediaItem? {
        if (row == null) return null
        // Watchlist/history/collection rows wrap the media ({movie/show} +
        // metadata), history rows wrap it as {movie/show + watched_at}, and
        // several Trakt endpoints return the media object itself (title + ids
        // at the top level) — accept all, else a whole library silently
        // becomes zero rows.
        val wrapped = row.optJSONObject("movie") ?: row.optJSONObject("show")
        if (wrapped != null) return traktMedia(wrapped, type)
        if (row.has("title")) return traktMedia(row, type)
        return null
    }

    private fun traktMedia(o: JSONObject, type: MediaType): MediaItem? {
        val title = o.optString("title").ifBlank { "Untitled" }
        val year = o.optInt("year", 0).takeIf { it > 0 }
        // Trakt serves several sizes; take the sharpest one it sent. Anything
        // here beats the blank clapperboard tile — and rows that still have
        // nothing get a TMDB poster by id afterwards (see enrichTraktPosters).
        val images = o.optJSONObject("images")
        val poster = images?.optJSONObject("poster")
        val image = listOf(
            poster?.optString("full"),
            poster?.optString("medium"),
            poster?.optString("thumb"),
        ).firstOrNull { !it.isNullOrBlank() && it != "null" }
        val ids = o.optJSONObject("ids")
        val tmdb = ids?.optInt("tmdb", 0) ?: 0
        val imdb = ids?.optString("imdb")?.takeIf { it.startsWith("tt") }
        val traktId = ids?.optInt("trakt", 0) ?: 0
        val slug = ids?.optString("slug").orEmpty()
        // Anime/donghua on Trakt frequently carry neither a TMDB nor an IMDb
        // id — dropping those rows emptied whole libraries even though the
        // sync history proves the account holds them. Keep them as virtual
        // tracker rows (resolved by title at open, like every other tracker
        // row) instead of deleting them from the shelf — even when Trakt
        // sent no ids object at all.
        if (tmdb <= 0 && imdb == null && traktId <= 0) {
            val key = slug.ifBlank { title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-') + (year?.let { "-$it" } ?: "") }
            if (key.isBlank()) return null
            return MediaItem(providerId = "trakt", id = "slug:$key", title = title,
                type = type, posterUrl = image, year = year,
                rawType = if (type == MediaType.MOVIE) "movie" else "series")
        }
        val id = if (tmdb > 0) tmdb.toString() else imdb ?: traktId.toString()
        val provider = if (tmdb > 0) "tmdb" else if (imdb != null) "stremio" else "trakt"
        return MediaItem(providerId = provider, id = id, title = title,
            type = type, posterUrl = image, year = year,
            rawType = if (provider == "tmdb") "tmdb" else if (type == MediaType.MOVIE) "movie" else "series")
    }

    private fun simklMedia(o: JSONObject, type: MediaType, anime: Boolean = false, manga: Boolean = false): MediaItem? {
        val ids = o.optJSONObject("ids") ?: return null
        val tmdb = ids.optInt("tmdb", 0)
        val imdb = ids.optString("imdb").takeIf { it.startsWith("tt") }
        // Anime Simkl has not mapped yet carries neither id: keep it as a
        // tracker row (playable by title search) instead of dropping it from
        // the shelf entirely. Only anime gets this fallback — anything else
        // would misread a live-action row as anime downstream.
        // Manga rows get the same treatment (a Simkl manga id instead): a
        // manga entry must never fall through to the anime path.
        val simklId = ids.optInt("simkl", 0)
        var id: String? = if (tmdb > 0) tmdb.toString() else imdb
        if (id == null && (anime || manga)) id = simklId.takeIf { it > 0 }?.toString()
        val finalId = id ?: return null
        val provider = if (tmdb > 0) "tmdb" else if (imdb != null) "stremio" else "simkl"
        return MediaItem(providerId = provider, id = finalId,
            title = o.optString("title").ifBlank { o.optString("name") }.ifBlank { "Untitled" },
            type = type, year = o.optInt("year", 0).takeIf { it > 0 },
            posterUrl = simklPoster(o.optString("poster")),
            rawType = if (manga) "manga" else if (provider == "tmdb") "tmdb" else if (provider == "simkl") "anime" else if (type == MediaType.MOVIE) "movie" else "series")
    }

    /** Simkl serves bare image ids, not URLs (same shape as the detail lookup
     *  in TrackerAnimeResolver): values that already are URLs pass through. */
    private fun simklPoster(raw: String): String? {
        val p = raw.trim()
        if (p.isBlank() || p == "null") return null
        if (p.startsWith("http")) return p
        return "https://wsrv.nl/?url=https://simkl.in/posters/" + p + "_m.webp&q=90"
    }

    /** TMDB id → poster URL for rows Trakt left imageless. Process-wide: a
     *  library reload must not re-ask for the same posters. Null values are
     *  never stored (a miss this load may hit next load); [traktTmdbBare] tracks
     *  ids TMDB has no poster for so they are not re-asked every load. */
    private val traktTmdbPosters = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private val traktTmdbBare = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<Int, Boolean>()
    )

    /**
     * Fills the posters Trakt itself did not send. Only rows WITH a TMDB id
     * qualify — an exact id lookup, never a title search, so a wrong show's
     * artwork can never land on a row. Bounded (80 lookups per load, all at
     * once) and best-effort: a slow TMDB delays shelves, never fails them.
     */
    private suspend fun enrichTraktPosters(shelves: MutableList<Shelf>) {
        val missing = shelves.flatMap { it.items }
            .filter { it.posterUrl.isNullOrBlank() && it.providerId == "tmdb" }
            .mapNotNull { item ->
                val id = item.id.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
                if (traktTmdbPosters.containsKey(id) || traktTmdbBare.contains(id)) return@mapNotNull null
                Triple(item, id, item.type == MediaType.MOVIE)
            }
            .distinctBy { it.second }
            .take(80)
        if (missing.isNotEmpty()) {
            coroutineScope {
                missing.map { (_, id, isMovie) ->
                    async {
                        val poster = runCatching { traktTmdbPoster(id, isMovie) }.getOrNull()
                        if (poster != null) traktTmdbPosters[id] = poster
                        else traktTmdbBare.add(id)
                    }
                }.forEach { runCatching { it.await() } }
            }
        }
        if (traktTmdbPosters.isEmpty()) return
        for (i in shelves.indices) {
            val shelf = shelves[i]
            var changed = false
            val items = shelf.items.map { item ->
                if (!item.posterUrl.isNullOrBlank() || item.providerId != "tmdb") return@map item
                val poster = item.id.toIntOrNull()?.let { traktTmdbPosters[it] } ?: return@map item
                changed = true
                item.copy(posterUrl = poster)
            }
            if (changed) shelves[i] = shelf.copy(items = items)
        }
    }

    private suspend fun traktTmdbPoster(tmdbId: Int, isMovie: Boolean): String? {
        val obj = com.hikari.app.nuvio.TmdbResolver
            .apiGet(if (isMovie) "/movie/$tmdbId" else "/tv/$tmdbId", emptyMap())
            ?: return null
        return obj.optString("poster_path").trim()
            .takeIf { it.isNotBlank() && it != "null" }
            ?.let { "https://image.tmdb.org/t/p/w342$it" }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}

private fun JSONArray.orEmpty(): List<Any?> = List(length()) { opt(it) }
