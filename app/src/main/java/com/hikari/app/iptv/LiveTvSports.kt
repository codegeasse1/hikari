package com.hikari.app.iptv

import android.content.Context
import com.hikari.app.HikariApp
import com.hikari.app.data.Logs
import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Sports hub: ESPN's free public scoreboard (scores, clocks, logos, colors, US
 * networks) plus games found in your own TV guide. Teams you follow surface on
 * the Sports page, the calendar and game alerts.
 */
class LiveTvSports {

    private lateinit var app: HikariApp

    fun init(context: Context) {
        app = context.applicationContext as HikariApp
    }

    private val lock = Mutex()

    /** League id -> that league's teams. */
    @Volatile private var teams: Map<String, List<SportsTeam>> = emptyMap()

    /** League id -> events in the loaded window. */
    @Volatile private var events: Map<String, List<SportsEvent>> = emptyMap()

    fun leagues(): List<SportsLeague> = SportsLeagueCatalog.leagues

    fun followedTeams(): Set<String> = LiveTvEngine.store.userState().followedTeams

    fun followTeam(uid: String, follow: Boolean) {
        LiveTvEngine.store.updateUserState { st ->
            st.copy(followedTeams = if (follow) st.followedTeams + uid else st.followedTeams - uid)
        }
    }

    fun teamUid(leagueId: String, teamId: String): String = "$leagueId|$teamId"

    suspend fun refreshLeague(league: SportsLeague, daysBack: Int = 2, daysAhead: Int = 7) {
        val from = System.currentTimeMillis() - daysBack * 86_400_000L
        val to = System.currentTimeMillis() + daysAhead * 86_400_000L
        val fetched = withContext(Dispatchers.IO) { fetchScoreboard(league, from, to) }
        lock.withLock {
            events = events + (league.id to fetched)
            if (teams[league.id].isNullOrEmpty()) {
                val t = withContext(Dispatchers.IO) { fetchTeams(league) }
                if (t.isNotEmpty()) teams = teams + (league.id to t)
            }
        }
    }

    fun eventsOf(leagueId: String?): List<SportsEvent> {
        val all = if (leagueId == null) events.values.flatten() else events[leagueId].orEmpty()
        return all.sortedBy { it.startMs }
    }

    fun liveNow(): List<SportsEvent> =
        events.values.flatten().filter { it.live }.sortedBy { it.startMs }

    fun teamsOf(leagueId: String): List<SportsTeam> = teams[leagueId].orEmpty()

    /** Finds your channels showing [event]: guide → channel name → network → your fix. */
    suspend fun matchEvent(event: SportsEvent): List<ChannelMatch> {
        val channels = LiveTvEngine.visibleChannels()
        if (channels.isEmpty()) return emptyList()
        val out = ArrayList<ChannelMatch>()
        val now = System.currentTimeMillis()
        val tokens = eventTokens(event)
        // 1. Your guide: a show around kick-off naming both teams (or the event).
        channels.forEach { ch ->
            val guide = runCatching {
                LiveTvEngine.guideWindow(ch, event.startMs - 30 * 60_000L, event.startMs + 30 * 60_000L)
            }.getOrDefault(emptyList())
            guide.forEach { p ->
                val t = (p.title + " " + (p.description.orEmpty())).lowercase()
                val hits = tokens.count { it in t }
                if (hits >= 2 || (event.title.isNotBlank() && event.title.lowercase() in t && hits >= 1)) {
                    out += ChannelMatch(ch.key, ch.name, "guide", p)
                    return@forEach
                }
            }
        }
        // 2. The channel's own name (event channels: "NFL 03: Bears @ Lions").
        channels.forEach { ch ->
            if (out.any { it.channelKey == ch.key }) return@forEach
            val n = ch.name.lowercase()
            val hits = tokens.count { it in n }
            if (hits >= 2) out += ChannelMatch(ch.key, ch.name, "name", null)
        }
        // 3. The network ESPN lists, with a sports show on at the time.
        if (event.network.isNotBlank()) {
            channels.forEach { ch ->
                if (out.any { it.channelKey == ch.key }) return@forEach
                if (event.network.lowercase() in ch.name.lowercase() || event.network.lowercase() in ch.group.lowercase()) {
                    val guide = runCatching {
                        LiveTvEngine.guideWindow(ch, event.startMs, event.startMs + eventLengthMs(event))
                    }.getOrDefault(emptyList())
                    if (guide.any { "sport" in (it.category.orEmpty().lowercase()) || tokens.any { tok -> tok in it.title.lowercase() } }) {
                        out += ChannelMatch(ch.key, ch.name, "network", guide.firstOrNull())
                    }
                }
            }
        }
        return out
    }

    private fun eventTokens(event: SportsEvent): List<String> {
        val out = LinkedHashSet<String>()
        listOf(event.homeName, event.awayName, event.homeAbbr, event.awayAbbr).forEach { n ->
            n.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.forEach { out += it }
        }
        return out.toList()
    }

    private fun eventLengthMs(event: SportsEvent): Long {
        val league = leagues().firstOrNull { it.id == event.leagueId }
        return (league?.minutes ?: 120) * 60_000L
    }

    // ---- ESPN ----

    private fun fetchScoreboard(league: SportsLeague, fromMs: Long, toMs: Long): List<SportsEvent> {
        val path = league.espnPath ?: return emptyList()
        val fmt = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("America/New_York")
        }
        val out = ArrayList<SportsEvent>()
        var day = fromMs
        var guard = 0
        while (day <= toMs && guard++ < 12) {
            val label = fmt.format(java.util.Date(day))
            val url = "$ESPN$path/scoreboard?dates=$label&limit=1000"
            runCatching {
                val text = Http.getString(url, mapOf("Accept" to "application/json")) ?: return@runCatching
                out += parseScoreboard(JSONObject(text), league)
            }.onFailure { Logs.log("LiveTv", "sports $path $label: ${it.message}") }
            day += 86_400_000L
        }
        return out.distinctBy { it.id }
    }

    private fun fetchTeams(league: SportsLeague): List<SportsTeam> {
        val path = league.espnPath ?: return emptyList()
        return runCatching {
            val text = Http.getString("$ESPN$path/teams?limit=1000", mapOf("Accept" to "application/json")) ?: return emptyList()
            val root = JSONObject(text)
            val list = root.optJSONObject("sports")?.optJSONArray("leagues")?.optJSONObject(0)
                ?.optJSONArray("teams") ?: return emptyList()
            List(list.length()) { i ->
                val t = list.optJSONObject(i)?.optJSONObject("team") ?: return@List null
                SportsTeam(
                    id = t.optString("id"),
                    leagueId = league.id,
                    name = t.optString("displayName"),
                    abbr = t.optString("abbreviation"),
                    logo = t.optString("logo").ifBlank { null },
                    color = t.optString("color").ifBlank { null },
                )
            }.filterNotNull()
        }.getOrDefault(emptyList())
    }

    private fun parseScoreboard(root: JSONObject, league: SportsLeague): List<SportsEvent> {
        val out = ArrayList<SportsEvent>()
        val events = root.optJSONArray("events") ?: return out
        for (i in 0 until events.length()) {
            val e = events.optJSONObject(i) ?: continue
            val id = e.optString("id")
            val date = e.optString("date")
            val startMs = runCatching {
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.parse(date)?.time
            }.getOrNull() ?: continue
            val status = e.optJSONObject("status")?.optJSONObject("type")?.optString("name").orEmpty()
            val clock = e.optJSONObject("status")?.optString("displayClock").orEmpty()
            val comp = e.optJSONArray("competitions")?.optJSONObject(0) ?: continue
            val competitors = comp.optJSONArray("competitors")
            var home = JSONObject()
            var away = JSONObject()
            for (c in 0 until (competitors?.length() ?: 0)) {
                val co = competitors!!.optJSONObject(c) ?: continue
                if (co.optString("homeAway") == "home") home = co else away = co
            }
            val broadcasts = comp.optJSONArray("broadcasts")
            var network = ""
            for (b in 0 until (broadcasts?.length() ?: 0)) {
                network = broadcasts!!.optJSONObject(b)?.optJSONArray("names")?.optString(0).orEmpty()
                if (network.isNotBlank()) break
            }
            fun teamName(o: JSONObject): String = o.optJSONObject("team")?.optString("displayName").orEmpty()
            fun teamAbbr(o: JSONObject): String = o.optJSONObject("team")?.optString("abbreviation").orEmpty()
            fun teamLogo(o: JSONObject): String? = o.optJSONObject("team")?.optString("logo")?.ifBlank { null }
            fun score(o: JSONObject): String = o.optString("score")
            out += SportsEvent(
                id = league.id + "|" + id,
                leagueId = league.id,
                leagueName = league.name,
                title = "${teamAbbr(away)} @ ${teamAbbr(home)}",
                startMs = startMs,
                status = status,
                clock = clock,
                homeName = teamName(home),
                awayName = teamName(away),
                homeAbbr = teamAbbr(home),
                awayAbbr = teamAbbr(away),
                homeScore = score(home),
                awayScore = score(away),
                homeLogo = teamLogo(home),
                awayLogo = teamLogo(away),
                network = network,
                live = status.equals("STATUS_IN_PROGRESS", ignoreCase = true),
                final = status.equals("STATUS_FINAL", ignoreCase = true),
            )
        }
        return out
    }

    companion object {
        const val ESPN = "https://site.api.espn.com/apis/site/v2/sports"
    }
}
