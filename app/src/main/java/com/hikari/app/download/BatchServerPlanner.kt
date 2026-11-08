package com.hikari.app.download

import com.hikari.app.data.StreamSource

/**
 * Batch-download server planning.
 *
 * The old batch picker listed the UNION of every server any selected episode
 * had, then — at queue time — matched each episode with a very loose rule
 * (same provider, then ANY usable server). That caused two problems:
 *
 *  1. An episode that none of the extensions had (e.g. a not-yet-released
 *     E17) only failed AFTER the user had picked a server and queued the
 *     rest ("Queued 5 of 6 — no server for E17").
 *  2. An episode that lacked the picked server was silently queued from some
 *     other server, which could be a different audio (Hindi vs English).
 *
 * This object gives the picker the information it needs up front:
 *  - which servers ("flavours") exist, and for WHICH episodes,
 *  - which audio languages / resolutions are on offer, so the user can choose
 *    audio / quality first and see only matching servers + episodes,
 *  - a strict per-episode matcher that never swaps the audio language
 *    unless the caller explicitly allows it (Auto mode).
 *
 * Nothing here is invented: audio / resolution are only read from what the
 * server's own name / details already say.
 */
object BatchServerPlanner {

    /**
     * One selectable server for the batch.
     *
     * @property key       provider id + normalised server name (stable across episodes).
     * @property sample    a real source carrying this server (used to probe qualities/audio).
     * @property audio     audio language read from the name ("Hindi"), "" when unknown.
     * @property height    resolution read from the name (1080), 0 when unknown.
     * @property covered   episode keys (see DetailScreen.dlEpKey) this server exists for.
     */
    data class Flavour(
        val key: String,
        val sample: StreamSource,
        val audio: String,
        val height: Int,
        val covered: Set<String>,
    )

    private val langs = listOf(
        "hindi", "english", "tamil", "telugu", "malayalam", "kannada",
        "bengali", "marathi", "punjabi", "gujarati", "urdu", "french",
        "spanish", "german", "arabic", "japanese", "korean", "portuguese",
        "russian", "italian", "turkish", "indonesian", "thai", "chinese",
        "mandarin", "cantonese", "original",
    )
    private val langAlt = langs.joinToString("|")

    /** Same downloadable rule the rest of the batch flow uses. */
    fun isDownloadable(s: StreamSource): Boolean =
        !s.isTorrent && !s.externalUrl && s.ytId.isNullOrBlank() &&
            s.url.startsWith("http", ignoreCase = true)

    /** Case/space-insensitive name without a trailing dedup count like " (2)". */
    fun normName(n: String): String =
        n.lowercase().trim()
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""\s*\(\d+\)\s*$"""), "")
            .trim()

    /** Identity of a server across episodes: provider + full normalised name. */
    fun serverKey(s: StreamSource): String = s.providerId + "|" + normName(s.name)

    /**
     * Audio language the server states ("Hindi", "English", …), "Multi" /
     * "Dual" for multi-audio streams, "" when the text says nothing.
     * Subtitle mentions ("English Sub", "Sub: English") are ignored so a
     * Japanese-audio / English-sub server is not mistaken for an English dub.
     */
    fun audioOf(s: StreamSource): String {
        val low = (s.name + " " + s.details).lowercase()
        if ("multi" in low && "audio" in low) return "Multi"
        if ("dual" in low && "audio" in low) return "Dual"
        // Drop subtitle mentions first.
        val noSubs = low
            .replace(Regex("""sub(s|titles?|bed)?[\s:_\-]*($langAlt)"""), " ")
            .replace(Regex("""($langAlt)[\s_\-]*sub(s|titles?|bed)?"""), " ")
        // Strongest signal: the language sits next to the word audio / dub.
        Regex("""($langAlt)[\s_\-]*(audio|dub|dubbed)""").find(noSubs)?.let {
            return it.groupValues[1].replaceFirstChar { c -> c.uppercase() }
        }
        Regex("""(audio|dub|dubbed)[\s:_\-]*($langAlt)""").find(noSubs)?.let {
            return it.groupValues[2].replaceFirstChar { c -> c.uppercase() }
        }
        Regex("""(^|[^a-z])($langAlt)([^a-z]|$)""").find(noSubs)?.let {
            return it.groupValues[2].replaceFirstChar { c -> c.uppercase() }
        }
        return ""
    }

    /** Resolution the server's name/details state (1080, 720, …), 0 when unknown. */
    fun heightOf(s: StreamSource): Int {
        val t = s.name + " " + s.details
        Regex("""(?<![0-9])(2160|1440|1080|720|576|480|360|240)\s*p""", RegexOption.IGNORE_CASE)
            .find(t)?.let { return it.groupValues[1].toInt() }
        if (Regex("""(^|[^a-z0-9])(4k|uhd)([^a-z0-9]|$)""", RegexOption.IGNORE_CASE).containsMatchIn(t)) return 2160
        if (Regex("""(^|[^a-z0-9])fhd([^a-z0-9]|$)""", RegexOption.IGNORE_CASE).containsMatchIn(t)) return 1080
        return 0
    }

    /**
     * Every distinct downloadable server across the selected episodes with the
     * set of episodes it exists for. Most-covering first; ties keep discovery
     * order (the title's own provider first), so the best server is on top.
     */
    fun buildFlavours(epKeys: List<String>, cache: Map<String, List<StreamSource>>): List<Flavour> {
        val first = LinkedHashMap<String, StreamSource>()
        val cover = HashMap<String, MutableSet<String>>()
        for (ek in epKeys) {
            val list = cache[ek] ?: continue
            for (s in list) {
                if (!isDownloadable(s)) continue
                val k = serverKey(s)
                if (!first.containsKey(k)) first[k] = s
                cover.getOrPut(k) { LinkedHashSet() }.add(ek)
            }
        }
        return first.map { (k, s) ->
            Flavour(k, s, audioOf(s), heightOf(s), cover[k] ?: emptySet())
        }.sortedWith(compareByDescending<Flavour> { it.covered.size })
    }

    /** Servers matching the chosen audio ("" = any) and resolution (0 = any). */
    fun filter(flavours: List<Flavour>, audio: String, height: Int): List<Flavour> =
        flavours.filter { f ->
            (audio.isBlank() || f.audio.equals(audio, ignoreCase = true)) &&
                (height <= 0 || f.height == height)
        }

    /** Episodes that at least one of [flavours] can download. */
    fun availableEpisodes(flavours: List<Flavour>): Set<String> {
        val out = LinkedHashSet<String>()
        for (f in flavours) out.addAll(f.covered)
        return out
    }

    /** True when [a] and [b] are the same mirror (same provider+name, or same name). */
    fun sameServer(a: StreamSource, b: StreamSource): Boolean =
        serverKey(a) == serverKey(b) || normName(a.name) == normName(b.name)

    /**
     * The right source for ONE episode given the server the user picked.
     *
     * Order: the exact same server → the same-named mirror → another server
     * with the SAME audio (same provider first, closest resolution). When the
     * picked server's audio is known and the episode has no server with that
     * audio, [strictAudio] decides: true = skip the episode (never swap the
     * language behind the user's back), false = take any usable server (Auto).
     */
    fun bestForEpisode(
        servers: List<StreamSource>,
        wanted: StreamSource,
        strictAudio: Boolean = true,
    ): StreamSource? {
        val usable = servers.filter { isDownloadable(it) }
        if (usable.isEmpty()) return null
        val wantedKey = serverKey(wanted)
        usable.firstOrNull { serverKey(it) == wantedKey }?.let { return it }
        val wantedName = normName(wanted.name)
        usable.firstOrNull { normName(it.name) == wantedName }?.let { return it }

        val audio = audioOf(wanted)
        val height = heightOf(wanted)
        val pool = if (audio.isNotBlank()) {
            usable.filter { audioOf(it).equals(audio, ignoreCase = true) }
        } else {
            usable
        }
        if (pool.isEmpty()) return if (strictAudio) null else usable[0]

        val sameProvider = pool.filter { it.providerId == wanted.providerId }
        val ranked = if (sameProvider.isNotEmpty()) sameProvider else pool
        if (height <= 0) return ranked[0]
        return ranked.minByOrNull { c ->
            val h = heightOf(c)
            if (h <= 0) 5000 else kotlin.math.abs(h - height)
        } ?: ranked[0]
    }

    /** "E12–E16, E18" / "S2 E3–6" from (season, number) pairs. */
    fun compactEpisodes(items: List<Pair<Int, Int>>): String {
        if (items.isEmpty()) return ""
        val out = ArrayList<String>()
        val bySeason = items.sortedWith(compareBy({ it.first }, { it.second })).groupBy { it.first }
        for ((season, list) in bySeason) {
            val nums = list.map { it.second }.distinct()
            val prefix = if (season > 1) "S" + season + " E" else "E"
            var i = 0
            while (i < nums.size) {
                var j = i
                while (j + 1 < nums.size && nums[j + 1] == nums[j] + 1) j++
                out.add(if (j == i) prefix + nums[i] else prefix + nums[i] + "–" + nums[j])
                i = j + 1
            }
        }
        return out.joinToString(", ")
    }
}
