package com.hikari.app.data

/**
 * Dub/sub episode merging: some anime extensions list every episode twice —
 * once for the subtitled release and once for the dub ("I'm Luffy! …" /
 * "I'm Luffy! … (Dub)"). The detail page then shows 2×N rows for an
 * N-episode show, which reads as a broken list.
 *
 * [mergedFor] folds those pairs back into ONE row per (season, number) and
 * remembers the folded variants in [variantsFor], so the stream lookup can
 * still reach both audios: the merged episode plays normally, and the
 * player's Audio sheet offers Sub/Dub as sibling-server rows (see
 * PlayerActivity.audioVariantsFor / switchAudioVariant, which keep the
 * position in the film when switching).
 *
 * Merging is deliberately conservative: a group only folds when every row
 * strips down to the SAME base name once dub/sub/audio markers are removed.
 * Two genuinely different episodes that happen to share a number ("Part 1" /
 * "Part 2") keep their own rows — folding those would hide content.
 */
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

    /**
     * The episode's display name with its dub/sub/audio markers stripped, so
     * "Shanks! (Dub)" and "Shanks!" compare equal. Episode-number tags are
     * kept — they are the identity, not decoration.
     */
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

    /**
     * Folds dub/sub duplicate rows, registering the folded variants under
     * [itemKey] for [variantsFor]. Idempotent: running it over an already
     * merged list changes nothing (a lone row has no partner to fold with).
     */
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
                    out += group
                    continue
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

    /**
     * Tags a stream name with its audio when the name does not already say it,
     * in the bracket shape the player's Audio sheet reads as a language
     * variant ("… (Dub)" / "… (Sub)" — see audioTagOf).
     */
    fun tagStream(name: String, kind: String?): String {
        if (kind.isNullOrBlank()) return name
        if (audioKindOf(Episode(number = 0, id = name, name = name)) != null) return name
        return "$name ($kind)"
    }
}
