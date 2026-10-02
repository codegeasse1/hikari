package com.hikari.app.data
import com.hikari.app.HikariApp
import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

object AnimeMetadataRepository {
    data class Metadata(val title:String?=null,val rating:Double?=null,val nextEpisodeDate:String?=null,val source:String)
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
        val q="""query(\$search:String){Media(search:\$search,type:ANIME){title{userPreferred english romaji}averageScore nextAiringEpisode{airingAt episode}}}"""
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
