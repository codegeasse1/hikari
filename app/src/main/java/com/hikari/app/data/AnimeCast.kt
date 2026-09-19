package com.hikari.app.data

import com.hikari.app.HikariApp
import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The CHARACTERS of an anime title, from AniList.
 *
 * TMDB's `credits` for an anime lists the voice actors, whose faces mean
 * nothing to someone who knows the show — the characters are the cast the
 * viewer actually recognises (Luffy, Gojo, Eren), and AniList publishes exactly
 * that pairing: every character with its portrait and, as its second line, the
 * Japanese actor who voices them.
 *
 * Only a CONFIDENT match is used. A search hit is accepted when the requested
 * title and the hit's romaji/english/native title describe the same show (one
 * title's significant words contain the other's) and their years agree (within
 * two, since a season and its show can disagree by one), and when the year is
 * unknown on either side the title test alone must pass. Anything else returns
 * an empty list, which the caller reads as "keep the voice-actor list" — a
 * wrong show's characters would be worse than the cast TMDB already has.
 *
 * Decoration only, exactly like [Ratings]: the lookup runs in the background,
 * contributes nothing when it fails, goes out on the quiet HTTP client (a
 * metadata service must never raise the Home screen's "verification needed"
 * banner) and is cached on disk — characters never change, so a hit is kept for
 * a month and a miss is retried after a few hours.
 */
object AnimeCast {

    private const val ENDPOINT = "https://graphql.anilist.co"

    /** Characters shown; matches the Cast row's own ceiling of 20-ish. */
    private const val MAX = 24

    /** Characters of a show do not change: a month is a safe retention. */
    private const val TTL_MS = 30L * 24L * 60L * 60L * 1000L

    /** A miss is usually a search hiccup or a title AniList spells differently,
     *  so it is retried much sooner than a hit expires. */
    private const val EMPTY_TTL_MS = 6L * 60L * 60L * 1000L

    private val memory = ConcurrentHashMap<String, List<CastMember>>()

    private val lock = Any()
    private var disk: JSONObject? = null

    private val cacheFile: File get() = File(HikariApp.instance.filesDir, "anime-cast-cache.json")

    /**
     * The characters of [title], or an empty list when none can be established.
     * [cacheKey] is the caller's stable identity for the title (a TMDB id), so
     * the same show opened from five different extensions is one lookup.
     */
    suspend fun characters(title: String, year: Int?, cacheKey: String): List<CastMember> =
        withContext(Dispatchers.IO) {
            if (title.isBlank()) return@withContext emptyList()
            val key = cacheKey.takeIf { it.isNotBlank() }
                ?: (normalize(title) + "|" + (year ?: 0))
            memory[key]?.let { return@withContext it }
            readDisk(key)?.let {
                memory[key] = it
                return@withContext it
            }
            val found = runCatching { fetch(title, year) }.getOrDefault(emptyList())
            memory[key] = found
            writeDisk(key, found)
            found
        }

    /** One AniList GraphQL round-trip: search, then read the character edges. */
    private fun fetch(title: String, year: Int?): List<CastMember> {
        // `$search` is escaped because this is a Kotlin raw string: the GraphQL
        // variable is a literal `$search`, not an interpolation.
        val query = """
            query (${'$'}search: String) {
              Media(search: ${'$'}search, type: ANIME, sort: SEARCH_MATCH) {
                title { romaji english native }
                startDate { year }
                characters(sort: [ROLE, RELEVANCE], perPage: $MAX) {
                  edges {
                    role
                    node { name { full } image { medium } }
                    voiceActors(language: JAPANESE) { name { full } }
                  }
                }
              }
            }
        """.trimIndent()
        val body = JSONObject()
            .put("query", query)
            .put("variables", JSONObject().put("search", title.trim()))
            .toString()
        val text = Http.postStringQuiet(
            ENDPOINT,
            body,
            headers = mapOf("Accept" to "application/json"),
        ) ?: return emptyList()
        val media = JSONObject(text).optJSONObject("data")?.optJSONObject("Media")
            ?: return emptyList()
        if (!confidentMatch(title, year, media)) return emptyList()

        val edges = media.optJSONObject("characters")?.optJSONArray("edges") ?: return emptyList()
        val out = ArrayList<CastMember>(MAX)
        for (i in 0 until edges.length()) {
            if (out.size >= MAX) break
            val edge = edges.optJSONObject(i) ?: continue
            val node = edge.optJSONObject("node") ?: continue
            val name = node.optJSONObject("name")?.optString("full")?.trim().orEmpty()
            if (name.isBlank()) continue
            val image = node.optJSONObject("image")?.optString("medium")?.trim()
                ?.takeIf { it.startsWith("http") }
            // The Japanese actor is the character's second line; a character
            // with no listed actor falls back to its role, so the line never
            // reads as a blank.
            val voice = edge.optJSONArray("voiceActors")?.optJSONObject(0)
                ?.optJSONObject("name")?.optString("full")?.trim()?.takeIf { it.isNotBlank() }
            val role = edge.optString("role").trim().takeIf { it.isNotBlank() }
            out.add(
                CastMember(
                    name = name,
                    character = voice ?: role,
                    profileUrl = image,
                )
            )
        }
        return out
    }

    /**
     * True when [media] really is the title that was asked for: one of its
     * titles has to describe the same show as [title] (see [sameShow]) and the
     * years must not contradict each other.
     */
    private fun confidentMatch(title: String, year: Int?, media: JSONObject): Boolean {
        val t = media.optJSONObject("title")
        val candidates = listOf("romaji", "english", "native")
            .mapNotNull { t?.optString(it)?.trim()?.takeIf { s -> s.isNotBlank() } }
        if (candidates.none { sameShow(title, it) }) return false
        val found = media.optJSONObject("startDate")?.optInt("year")?.takeIf { it > 0 }
        if (year != null && year > 0 && found != null) {
            // A season and its show can legitimately disagree by one year, and
            // TMDB's first-air year does not always match AniList's start year.
            if (kotlin.math.abs(found - year) > 2) return false
        }
        return true
    }

    /** True when [a] and [b] name the same show: every significant word of one
     *  is present in the other. Shared words are what "Renegade Immortal" and
     *  "Xian Ni" have in common — none — which is why an unmatched title falls
     *  back to TMDB's voice-actor cast instead of guessing. */
    private fun sameShow(a: String, b: String): Boolean {
        val left = tokens(a)
        val right = tokens(b)
        if (left.isEmpty() || right.isEmpty()) return false
        return left.all { right.contains(it) } || right.all { left.contains(it) }
    }

    private val STOP_WORDS = setOf(
        "the", "a", "an", "of", "and", "or", "to", "in", "no", "wa", "ga", "tv",
        "season", "part", "movie", "film", "special", "specials", "ova", "ona",
    )

    /** Lowercased significant words of a title, with punctuation, bracketed
     *  asides and stop words removed — "Renegade Immortal (Xian Ni) Season 1"
     *  becomes [renegade, immortal, xian, ni, 1]. */
    internal fun tokens(raw: String): List<String> {
        val cleaned = raw.lowercase(Locale.ROOT)
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("\\[[^\\]]*\\]"), " ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        return cleaned.split(' ').filter { it.isNotBlank() && it !in STOP_WORDS }
    }

    /** [tokens] joined back into one string, used for the memory/disk key. */
    private fun normalize(raw: String): String = tokens(raw).joinToString("-")

    private fun parseList(arr: JSONArray?): List<CastMember> {
        if (arr == null) return emptyList()
        val out = ArrayList<CastMember>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("n").trim()
            if (name.isBlank()) continue
            out.add(
                CastMember(
                    name = name,
                    character = o.optString("c").trim().takeIf { it.isNotBlank() },
                    profileUrl = o.optString("p").trim().takeIf { it.isNotBlank() },
                )
            )
        }
        return out
    }

    private fun readDisk(key: String): List<CastMember>? = synchronized(lock) {
        val root = disk ?: runCatching {
            cacheFile.takeIf { it.exists() }?.let { JSONObject(it.readText()) }
        }.getOrNull()?.also { disk = it }
        val entry = root?.optJSONObject(key) ?: return null
        val age = System.currentTimeMillis() - entry.optLong("at")
        val list = parseList(entry.optJSONArray("list"))
        val ttl = if (list.isEmpty()) EMPTY_TTL_MS else TTL_MS
        if (age > ttl) null else list
    }

    private fun writeDisk(key: String, list: List<CastMember>) {
        synchronized(lock) {
            runCatching {
                val root = disk ?: runCatching {
                    cacheFile.takeIf { it.exists() }?.let { JSONObject(it.readText()) }
                }.getOrNull() ?: JSONObject()
                disk = root
                val arr = JSONArray()
                list.forEach { m ->
                    arr.put(
                        JSONObject()
                            .put("n", m.name)
                            .put("c", m.character ?: "")
                            .put("p", m.profileUrl ?: "")
                    )
                }
                root.put(key, JSONObject().put("at", System.currentTimeMillis()).put("list", arr))
                // Bounded, like the ratings cache: nothing here is worth growing
                // without limit. Oldest entries go first.
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
