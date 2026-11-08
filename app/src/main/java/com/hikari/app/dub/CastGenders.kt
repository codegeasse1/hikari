package com.hikari.app.dub

import com.hikari.app.net.Http
import com.hikari.app.nuvio.TmdbResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

// Gender lives in SpeakerVoices.kt (MALE, FEMALE, CHILD, NEUTRAL, UNKNOWN).

object CastGenders {
  private const val ANILIST = "https://graphql.anilist.co"
  private const val TMDB_BASE = "https://api.themoviedb.org/3"
  private const val TTL_MS = 30L * 24L * 60L * 60L * 1000L
  private const val EMPTY_TTL_MS = 6L * 60L * 60L * 1000L

  private val memory = ConcurrentHashMap<String, Map<String, Gender>>()
  private var disk: JSONObject? = null
  private val lock = Any()
  private val cacheFile: File get() = File(com.hikari.app.HikariApp.instance.filesDir, "dub-cast-genders.json")

  private val GENERIC: Set<String> = (
    "the and for with from voice voices self uncredited archive footage additional young younger " +
    "older old little mister miss mrs doctor captain general agent officer detective king queen " +
    "prince princess man woman boy girl guy kid child children mother father dad mom mum brother " +
    "sister baby student teacher narrator himself herself unknown extra other part role version hindi english"
    ).split(" ").filter { it.isNotEmpty() }.toHashSet()

  private fun tokens(raw: String): List<String> {
    val cleaned = raw.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9 ]"), " ")
    return cleaned.split(' ').filter { it.length >= 3 && it !in GENERIC }
  }

  suspend fun load(title: String, year: Int?): Map<String, Gender> = withContext(Dispatchers.IO) {
    val shown = title.trim()
    if (shown.isBlank()) return@withContext emptyMap()
    val key = tokens(shown).joinToString("-") + "|" + (year ?: 0)
    if (key.length < 3) return@withContext emptyMap()
    memory[key]?.let { return@withContext it }
    readDisk(key)?.let { memory[key] = it; return@withContext it }
    var found: Map<String, Gender> = runCatching { fromAniList(shown) }.getOrDefault(emptyMap())
    if (found.isEmpty()) {
      found = runCatching { fromTmdb(shown, year) }.getOrDefault(emptyMap())
    }
    memory[key] = found
    writeDisk(key, found)
    found
  }

  fun genderForLine(line: String, cast: Map<String, Gender>): Gender {
    if (cast.isEmpty()) return Gender.UNKNOWN
    val t = line.trim()
    if (t.isEmpty()) return Gender.UNKNOWN
    val pre = t.substringBefore(':')
    if (':' in t && pre.length in 2..28) {
      val ok = pre.all { it.isLetter() || it == ' ' || it == '.' || it == '\'' }
      if (ok) {
        val first = pre.trim().lowercase(java.util.Locale.ROOT).split(' ').firstOrNull { it.length >= 3 }
        if (first != null && cast[first] != null) return cast[first]!!
        val full = cast[pre.trim().lowercase(java.util.Locale.ROOT)]
        if (full != null) return full
      }
    }
    var best: Gender? = null
    var bestLen = 0
    for (w in tokens(t)) {
      val g = cast[w] ?: continue
      if (w.length > bestLen) { best = g; bestLen = w.length }
    }
    return best ?: Gender.UNKNOWN
  }

  private fun fromAniList(title: String): Map<String, Gender> {
    val query = "query (\$search: String) { Media(search: \$search, type: ANIME, sort: SEARCH_MATCH) { id characters(sort: [ROLE, RELEVANCE], perPage: 25) { edges { node { name { full } gender } } } } } }"
    val body = JSONObject().put("query", query).put("variables", JSONObject().put("search", title)).toString()
    val text = try {
      Http.postStringQuiet(ANILIST, body, headers = mapOf("Accept" to "application/json", "Content-Type" to "application/json")) ?: return emptyMap()
    } catch (e: Exception) { return emptyMap() }
    val media = JSONObject(text).optJSONObject("data")?.optJSONObject("Media") ?: return emptyMap()
    val edges = media.optJSONObject("characters")?.optJSONArray("edges") ?: return emptyMap()
    val male = mutableSetOf<String>()
    val female = mutableSetOf<String>()
    for (i in 0 until edges.length()) {
      val node = edges.optJSONObject(i)?.optJSONObject("node") ?: continue
      val name = node.optJSONObject("name")?.optString("full").orEmpty().trim()
      val g = node.optString("gender").trim().lowercase(java.util.Locale.ROOT)
      if (name.isBlank()) continue
      val gender = when {
        g.startsWith("female") || g == "f" -> Gender.FEMALE
        g.startsWith("male") || g == "m" -> Gender.MALE
        else -> continue
      }
      for (w in tokens(name)) {
        if (gender == Gender.MALE) male.add(w) else female.add(w)
      }
    }
    return mergeSets(male, female)
  }

  private fun fromTmdb(title: String, year: Int?): Map<String, Gender> {
    val key = try { TmdbResolver.builtinKey() } catch (e: Exception) { "" }
    if (key.isBlank()) return emptyMap()
    val q = java.net.URLEncoder.encode(title, "UTF-8")
    val ids = ArrayList<Pair<String, String>>()
    for (kind in listOf("movie", "tv")) {
      val url = TMDB_BASE + "/search/" + kind + "?api_key=" + key + "&query=" + q + "&include_adult=true"
      val txt = Http.getStringQuiet(url) ?: continue
      val arr = JSONObject(txt).optJSONArray("results") ?: continue
      var bestId = ""
      var bestScore = -1
      for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val nm = if (kind == "movie") o.optString("title") else o.optString("name")
        val dt = if (kind == "movie") o.optString("release_date") else o.optString("first_air_date")
        val y = dt.take(4).toIntOrNull()
        if (!sameTitle(title, nm)) continue
        var score = 0
        if (year != null && y != null && kotlin.math.abs(year - y) <= 2) score += 10
        if (score > bestScore) { bestScore = score; bestId = o.optInt("id").toString() }
      }
      if (bestId.isNotBlank() && bestId != "0") ids.add(Pair(kind, bestId))
      if (ids.isNotEmpty()) break
    }
    if (ids.isEmpty()) return emptyMap()
    val kind = ids.first().first
    val id = ids.first().second
    val credUrl = TMDB_BASE + "/" + kind + "/" + id + "/credits?api_key=" + key
    val ctxt = Http.getStringQuiet(credUrl) ?: return emptyMap()
    val cast = JSONObject(ctxt).optJSONArray("cast") ?: return emptyMap()
    val male = mutableSetOf<String>()
    val female = mutableSetOf<String>()
    for (i in 0 until minOf(cast.length(), 40)) {
      val o = cast.optJSONObject(i) ?: continue
      val ch = o.optString("character").orEmpty()
      val g = o.optInt("gender", 0)
      val gender = when (g) { 1 -> Gender.FEMALE; 2 -> Gender.MALE; else -> continue }
      if (ch.isBlank()) continue
      for (w in tokens(ch)) {
        if (gender == Gender.MALE) male.add(w) else female.add(w)
      }
    }
    return mergeSets(male, female)
  }

  private fun mergeSets(male: Set<String>, female: Set<String>): Map<String, Gender> {
    val out = HashMap<String, Gender>()
    for (w in male) if (w !in female) out[w] = Gender.MALE
    for (w in female) if (w !in male) out[w] = Gender.FEMALE
    return out
  }

  private fun sameTitle(a: String, b: String): Boolean {
    val ta = tokens(a); val tb = tokens(b)
    if (ta.isEmpty() || tb.isEmpty()) return false
    return ta.all { it in tb } || tb.all { it in ta }
  }

  private fun readDisk(key: String): Map<String, Gender>? = synchronized(lock) {
    try {
      val root = disk ?: runCatching {
        cacheFile.takeIf { it.exists() }?.let { JSONObject(it.readText()) }
      }.getOrNull()?.also { disk = it } ?: return null
      val e = root.optJSONObject(key) ?: return null
      val age = System.currentTimeMillis() - e.optLong("at")
      val obj = e.optJSONObject("map") ?: return null
      val out = HashMap<String, Gender>()
      obj.keys().forEach { k -> out[k] = when (obj.optString(k)) { "M" -> Gender.MALE; "F" -> Gender.FEMALE; else -> Gender.UNKNOWN } }
      out.values.removeAll(setOf(Gender.UNKNOWN))
      val ttl = if (out.isEmpty()) EMPTY_TTL_MS else TTL_MS
      if (age > ttl) null else out
    } catch (e: Exception) { null }
  }

  private fun writeDisk(key: String, map: Map<String, Gender>) {
    synchronized(lock) {
      runCatching {
        val root = disk ?: runCatching {
          cacheFile.takeIf { it.exists() }?.let { JSONObject(it.readText()) }
        }.getOrNull() ?: JSONObject()
        disk = root
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, if (v == Gender.MALE) "M" else "F") }
        root.put(key, JSONObject().put("at", System.currentTimeMillis()).put("map", o))
        if (root.length() > 200) {
          val keys = root.keys().asSequence().toList()
          val oldest = keys.sortedBy { root.optJSONObject(it)?.optLong("at") ?: 0L }
          oldest.take(keys.size - 200).forEach { root.remove(it) }
        }
        cacheFile.writeText(root.toString())
      }
    }
  }
}
