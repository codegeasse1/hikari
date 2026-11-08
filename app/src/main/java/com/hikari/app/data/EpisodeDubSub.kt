package com.hikari.app.data

object EpisodeDubSub {

    private val dubRe = Regex(
        "(?i)(\\benglish\\s*dub\\b|\\bdub(?:bed)?\\b|\\blatino\\b|\\bdoblado\\b|\\bdoblaje\\b|\\bcastellano\\b)",
    )
    private val subRe = Regex(
        "(?i)(\\bsub(?:bed|title)?s?\\b|\\blegendado\\b|\\bvostfr\\b|\\bsoftsub\\b)",
    )
    private val bracketAudioRe = Regex("[\\(\\[]([^\\)\\]]*?)[\\)\\]]")
    private val episodeTagRe = Regex(
        "(?i)\\b(ep(?:isode)?\\s*\\d+|e\\d+|第\\d+集|\\d+\\s*集)\\b",
    )

    /** "Dub", "Sub", or null when the row names no audio. */
    fun audioKindOf(ep: Episode): String? {
        val text = ((ep.name ?: "") + " " + ep.id).trim()
        if (text.isBlank()) return null
        if (dubRe.containsMatchIn(text)) return "Dub"
        if (subRe.containsMatchIn(text)) return "Sub"
        return null
    }

    fun baseNameOf(ep: Episode): String {
        var name = (ep.name ?: "").trim()
        if (name.isEmpty()) name = "episode ${ep.number}"
        name = bracketAudioRe.replace(name) { m ->
            val inner = m.groupValues[1]
            if (dubRe.containsMatchIn(inner) || subRe.containsMatchIn(inner) ||
                inner.trim().equals("audio", true)
            ) " " else m.value
        }
        name = dubRe.replace(name, " ")
        name = subRe.replace(name, " ")
        name = name.replace(Regex("(?i)\\boriginal\\s*audio\\b"), " ")
        return name.replace(Regex("\\s+"), " ").trim().trim('-', '–', '—', ':', '·', '|').trim()
    }

    fun mergedFor(itemKey: String, episodes: List<Episode>): List<Episode> {
        if (episodes.size < 2) return episodes
        val out = ArrayList<Episode>(episodes.size)
        for ((_, group) in episodes.groupBy { it.season to it.number }) {
            if (group.size < 2) {
                out += group
                continue
            }
            val bases = group.map { baseNameOf(it).lowercase() }.distinct()
            if (bases.size != 1 || bases.first().isBlank()) {
                out += group
                continue
            }
            val kinds = group.map { audioKindOf(it) }.distinct()
            val hasMarker = kinds.any { it != null }
            if (!hasMarker) {
                val ids = group.map { it.id }.distinct()
                if (ids.size != 1) {
                    val names = group.map { (it.name ?: "").trim() }
                        .filter { it.isNotEmpty() }
                        .map { it.lowercase() }
                        .distinct()
                    if (names.size > 1) {
                        out += group
                        continue
                    }
                }
            }
            val primary = group.firstOrNull { audioKindOf(it) == "Sub" }
                ?: group.firstOrNull { audioKindOf(it) == null }
                ?: group.first()
            register(itemKey, primary.season, primary.number, group)
            val cleanName = group.mapNotNull { it.name?.trim()?.ifBlank { null } }
                .minByOrNull { it.length }
                ?.let { stripKindSuffix(it) }
            out += primary.copy(
                name = cleanName ?: primary.name,
                image = group.firstNotNullOfOrNull { it.image },
                overview = group.firstNotNullOfOrNull { it.overview?.ifBlank { null } },
                released = group.firstNotNullOfOrNull { it.released?.ifBlank { null } },
                rating = group.firstNotNullOfOrNull { it.rating },
                runtime = group.firstNotNullOfOrNull { it.runtime },
            )
        }
        out.sortWith(compareBy({ it.season }, { it.number }))
        return out
    }

    private fun stripKindSuffix(name: String): String {
        var n = name.trim()
        n = Regex("(?i)\\s*[\\(\\[][^\\)\\]]*(?:dub|sub|audio)[^\\)\\]]*[\\]\\)]\\s*$").replace(n, "").trim()
        n = Regex("(?i)\\s*[-–—·|]\\s*(?:dub|sub)(?:bed)?\\s*$").replace(n, "").trim()
        return n.ifBlank { name }
    }

    // ---- variant sidecar ----

    private data class Key(val item: String, val season: Int, val number: Int)

    private val variants = java.util.concurrent.ConcurrentHashMap<Key, List<Episode>>()

    private fun register(itemKey: String, season: Int, number: Int, group: List<Episode>) {
        variants[Key(itemKey, season, number)] = group.toList()
        if (variants.size > 400) {
            val drop = variants.keys.firstOrNull()
            if (drop != null) variants.remove(drop)
        }
    }

    /** The folded-away rows for a merged episode, or empty when it stands alone. */
    fun variantsFor(itemKey: String, season: Int, number: Int): List<Episode> =
        variants[Key(itemKey, season, number)].orEmpty()

    /** The siblings of [primary] that carry a DIFFERENT audio, for stream expansion. */
    fun siblingVariants(itemKey: String, primary: Episode): List<Episode> {
        val group = variantsFor(itemKey, primary.season, primary.number)
        if (group.size < 2) return emptyList()
        val primaryKind = audioKindOf(primary)
        return group.filter { it.id != primary.id || audioKindOf(it) != primaryKind }
    }

    fun kindOfStream(name: String, url: String): String? {
        val text = (name + " " + url).trim()
        if (text.isBlank()) return null
        if (dubRe.containsMatchIn(text)) return "Dub"
        if (subRe.containsMatchIn(text)) return "Sub"
        return null
    }

    /** True when a server name already carries an audio tag the Audio sheet reads. */
    fun isTagged(name: String): Boolean {
        if (name.isBlank()) return false
        bracketAudioRe.findAll(name).any { m ->
            val inner = m.groupValues[1]
            inner.contains("audio", ignoreCase = true) ||
                dubRe.containsMatchIn(inner) ||
                subRe.containsMatchIn(inner)
        }.let { if (it) return true }
        return audioKindOf(Episode(number = 0, id = name, name = name)) != null
    }

    fun tagStream(name: String, kind: String?): String {
        if (kind.isNullOrBlank()) return name
        if (audioKindOf(Episode(number = 0, id = name, name = name)) != null) return name
        return "$name ($kind)"
    }
}
