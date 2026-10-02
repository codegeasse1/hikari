package com.hikari.app.data
import com.hikari.app.HikariApp
import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

object AnimeMetadataRepository {
    data class Metadata(val title:String?=null,val rating:Double?=null,val nextEpisodeDate:String?=null,val source:String)

    data class SeasonLayout(val season: Int, val episodes: Int)

    private val seasonCache = ConcurrentHashMap<String, List<SeasonLayout>>()

    /**
     * AniList keeps numbered TV seasons as separate Media entries linked by
     * PREQUEL/SEQUEL relations. Some providers flatten those entries into one
     * episode list, so the detail page never receives season numbers. This
     * resolver returns a layout only when multiple numbered TV seasons exist.
     */
    suspend fun seasonLayout(title: String): List<SeasonLayout> = withContext(Dispatchers.IO) {
        val key = title.trim().lowercase()
        if (key.isBlank()) return@withContext emptyList()
        seasonCache[key]?.let { return@withContext it }

        val searchQuery = "query(\$search:String!){Page(perPage:10){media(search:\$search,type:ANIME){id format episodes title{userPreferred english romaji}}}}"
        val raw = Http.postStringQuiet(
            "https://graphql.anilist.co",
            JSONObject()
                .put("query", searchQuery)
                .put("variables", JSONObject().put("search", title.trim()))
                .toString()
        ) ?: return@withContext emptyList()

        val media = JSONObject(raw)
            .optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media")
            ?: return@withContext emptyList()

        fun titleOf(o: JSONObject): String =
            o.optJSONObject("title")?.optString("userPreferred").orEmpty().ifBlank {
                o.optJSONObject("title")?.optString("english").orEmpty()
            }.ifBlank {
                o.optJSONObject("title")?.optString("romaji").orEmpty()
            }

        fun norm(s: String): String = s.lowercase()
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .replace(Regex("""\s+"""), " ").trim()

        fun score(wanted: String, candidate: String): Int {
            val a = norm(wanted); val b = norm(candidate)
            if (a.isBlank() || b.isBlank()) return 0
            if (a == b) return 100
            if (b.contains(a) || a.contains(b)) return 90
            val ta = a.split(" ").filter { it.length > 2 }.toSet()
            val tb = b.split(" ").filter { it.length > 2 }.toSet()
            if (ta.isEmpty() || tb.isEmpty()) return 0
            return (ta.intersect(tb).size * 100) / maxOf(ta.size, tb.size)
        }

        fun explicitSeason(name: String): Int? {
            val patterns = listOf(
                Regex("""(?i)\b(\d+)(?:st|nd|rd|th)\s+season\b"""),
                Regex("""(?i)\bseason\s*(\d+)\b"""),
                Regex("""(?i)\b(first|second|third|fourth|fifth|sixth)\s+season\b"""),
            )
            for (r in patterns) {
                val m = r.find(name) ?: continue
                m.groupValues.getOrNull(1)?.toIntOrNull()?.let { return it }
                return when (m.groupValues.getOrNull(1)?.lowercase()) {
                    "first" -> 1
                    "second" -> 2
                    "third" -> 3
                    "fourth" -> 4
                    "fifth" -> 5
                    "sixth" -> 6
                    else -> null
                }
            }
            return null
        }

        val base = (0 until media.length())
            .mapNotNull { media.optJSONObject(it) }
            .filter { it.optString("format").equals("TV", true) }
            .maxByOrNull { o ->
                val n = titleOf(o)
                score(title, n) + if (explicitSeason(n) == null) 15 else 0
            } ?: return@withContext emptyList()

        val baseId = base.optInt("id", 0)
        if (baseId <= 0) return@withContext emptyList()

        data class Node(val id: Int, val title: String, val episodes: Int, val relation: String)
        val nodes = LinkedHashMap<Int, Node>()
        val queue = ArrayDeque<Pair<Int, String>>()
        val seen = HashSet<Int>()
        queue.add(baseId to "BASE")

        val relationQuery = "query(\$id:Int!){Media(id:\$id,type:ANIME){id format episodes seasonYear title{userPreferred english romaji} relations{edges{relationType node{id format episodes seasonYear title{userPreferred english romaji}}}}}}"
        while (queue.isNotEmpty() && seen.size < 12) {
            val (id, relation) = queue.removeFirst()
            if (!seen.add(id)) continue
            val body = Http.postStringQuiet(
                "https://graphql.anilist.co",
                JSONObject().put("query", relationQuery)
                    .put("variables", JSONObject().put("id", id)).toString()
            ) ?: continue
            val m = JSONObject(body).optJSONObject("data")?.optJSONObject("Media") ?: continue
            if (!m.optString("format").equals("TV", true)) continue
            val name = titleOf(m)
            if (score(title, name) < 55 && id != baseId) continue
            val count = m.optInt("episodes", 0)
            if (count > 0) nodes[id] = Node(id, name, count, relation)

            val edges = m.optJSONObject("relations")?.optJSONArray("edges") ?: continue
            for (i in 0 until edges.length()) {
                val edge = edges.optJSONObject(i) ?: continue
                val rel = edge.optString("relationType")
                if (rel != "SEQUEL" && rel != "PREQUEL") continue
                val child = edge.optJSONObject("node") ?: continue
                val childId = child.optInt("id", 0)
                if (childId > 0 && childId !in seen) {
                    val childName = titleOf(child)
                    if (score(title, childName) >= 55) queue.add(childId to rel)
                }
            }
        }

        if (nodes.size < 2) return@withContext emptyList()
        val used = HashSet<Int>()
        val grouped = LinkedHashMap<Int, Int>()
        val unresolved = ArrayList<Node>()

        nodes.values.forEach { node ->
            val season = if (node.id == baseId) 1 else explicitSeason(node.title)
            if (season != null && season > 0) {
                grouped[season] = (grouped[season] ?: 0) + node.episodes
                used.add(season)
            } else unresolved += node
        }

        if (unresolved.isNotEmpty()) {
            var next = 1
            unresolved.sortedBy { it.title }.forEach { node ->
                while (next in used) next++
                grouped[next] = (grouped[next] ?: 0) + node.episodes
                used.add(next); next++
            }
        }

        val result = grouped.entries.filter { it.key > 0 && it.value > 0 }
            .sortedBy { it.key }.map { SeasonLayout(it.key, it.value) }
        if (result.size >= 2) seasonCache[key] = result
        result
    }

    suspend fun enrich(app:HikariApp,item:MediaItem):Metadata?=withContext(Dispatchers.IO){
        val anime=item.rawType.equals("anime",true)||item.providerId in setOf("anilist","mal","kitsu","shikimori")
        if(!anime||!runCatching{app.store.animeMetadataEnabled()}.getOrDefault(true))return@withContext null
        val mode=runCatching{app.store.animeMetadataSource()}.getOrDefault("auto").lowercase()
        val ani=runCatching{aniList(item.searchTitle)}.getOrNull()
        val sim=if(mode=="anilist")null else runCatching{simkl(app,item)}.getOrNull()
        if(ani==null&&sim==null)return@withContext null
        Metadata(ani?.title?:sim?.title,sim?.rating?:ani?.rating,ani?.nextEpisodeDate,listOfNotNull(sim?.source,ani?.source).distinct().joinToString(" + "))
    }
    private fun aniList(title:String):Metadata?{
        if(title.isBlank())return null
        val q = "query(\$search:String){Media(search: \$search,type:ANIME){title{userPreferred english romaji}averageScore nextAiringEpisode{airingAt episode}}}"
        val raw=Http.postStringQuiet("https://graphql.anilist.co",JSONObject().put("query",q).put("variables",JSONObject().put("search",title)).toString())?:return null
        val m=JSONObject(raw).optJSONObject("data")?.optJSONObject("Media")?:return null;val t=m.optJSONObject("title");val n=m.optJSONObject("nextAiringEpisode")
        val next=n?.optLong("airingAt",0L)?.takeIf{it>0}?.let{java.time.Instant.ofEpochSecond(it).toString()}
        val score=m.optDouble("averageScore",0.0).takeIf{it>0}?.div(10.0)
        return Metadata(t?.optString("userPreferred").takeIf{!it.isNullOrBlank()}?:t?.optString("english").takeIf{!it.isNullOrBlank()},score,next,"AniList")
    }
    private suspend fun simkl(app:HikariApp,item:MediaItem):Metadata?{
        val client=app.store.trackerClients().firstOrNull{it.kind==TrackerKind.SIMKL}?:return null;if(!client.ready)return null
        val url=if(item.providerId.equals("tmdb",true))
            "https://api.simkl.com/ratings?tmdb="+URLEncoder.encode(item.id,"UTF-8")+"&type=anime&fields=simkl,ext,rank,release_status,year&client_id="+URLEncoder.encode(client.id,"UTF-8")
        else{
            val raw=Http.getStringQuiet("https://api.simkl.com/search/anime?q="+URLEncoder.encode(item.searchTitle,"UTF-8")+"&client_id="+URLEncoder.encode(client.id,"UTF-8"))?:return null
            val a=org.json.JSONArray(raw);if(a.length()==0)return null;val f=a.optJSONObject(0)?:return null
            val id=f.optJSONObject("ids")?.optInt("simkl",0)?.takeIf{it>0}?:f.optInt("id",0).takeIf{it>0}?:return null
            "https://api.simkl.com/ratings?simkl="+id+"&fields=simkl,ext,rank,release_status,year&client_id="+URLEncoder.encode(client.id,"UTF-8")
        }
        val raw=Http.getStringQuiet(url)?:return null;val s=JSONObject(raw).optJSONObject("simkl")
        return Metadata(item.title,s?.optDouble("rating",0.0)?.takeIf{it>0},null,"Simkl")
    }
}
