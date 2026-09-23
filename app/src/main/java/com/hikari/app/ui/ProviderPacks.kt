package com.hikari.app.ui

import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import com.hikari.app.providers.ContentProvider

/**
 * One row of a provider list.
 *
 * Usually that is one provider. An Aniyomi (or manga) EXTENSION is the
 * exception: a single `.apk` can publish a whole family of sources, and a
 * multi-audio pack publishes the *same site* once per language under one name
 * — AnimeWorld India is nine sources (a generic one plus
 * Bengali/English/Hindi/Japanese/Malayalam/Marathi/Tamil/Telugu), all called
 * "AnimeWorld India". Hikari stores one provider per source (which is what makes
 * each one searchable, pinnable and switchable), so a user who installed ONE
 * extension saw it listed nine times — the reported "I am installing one
 * animeworld and in provider it showing 8 times".
 *
 * [ProviderPacks.rows] folds every source of one extension into a single
 * [ProviderPack], so every list that draws providers shows one row per
 * extension and the sources live inside it (see the `expanded` handling in the
 * Extensions screen and Home's picker). Nothing about how providers are STORED
 * or searched changes: the rows are only how they are shown.
 */
class ProviderPack(
    /** The sources of this extension, in the extension's own order. */
    val members: List<ContentProvider>,
    /** The name the extension is known by (whatever its sources' names have in
     *  common, e.g. "AnimeWorld India"). */
    val label: String,
    /** What tells each member apart, one entry per member in [members] order
     *  ("Bengali", "Source 1", …). Blank when the member IS the whole name. */
    val details: List<String>,
) {
    /** The provider a collapsed row acts as: the extension's first source. */
    val primary: ContentProvider get() = members[0]

    /** True when this row stands for more than one source. */
    val isPack: Boolean get() = members.size > 1

    /** The row's identity — the primary source's id, which is also the key the
     *  pins and the picker's selection use. */
    val key: String get() = primary.config.id

    /** "9 sources", for a row's supporting line. */
    val countLabel: String get() = if (isPack) "${members.size} sources" else ""

    /** "Bengali · English · Hindi · +5", for a row's supporting line. */
    val detailLabel: String
        get() {
            val named = details.filter { it.isNotBlank() && !it.startsWith("Source ") }
            if (named.isEmpty()) return ""
            return if (named.size <= 3) named.joinToString(" · ")
            else named.take(3).joinToString(" · ") + " · +" + (named.size - 3)
        }

    /** The label of one member, as drawn inside an expanded row. */
    fun memberLabel(index: Int): String {
        val d = details.getOrNull(index).orEmpty()
        if (d.isNotBlank()) return d
        // This member IS the extension's own name (the generic, multi-audio
        // source of a language pack) — the one row of the group with nothing to
        // add to it.
        return com.hikari.app.i18n.I18n.t("Default")
    }
}

object ProviderPacks {

    private const val ANIYOMI_PREFIX = "aniyomi|"
    private const val MANGA_PREFIX = "manga|"

    /**
     * The extension a provider row was published by, or null for every engine
     * whose providers are not one-source-of-many (`aniyomi|<pkg>|<index>` and
     * `manga|<pkg>|<index>` are the two that are — see
     * [com.hikari.app.aniyomi.AniyomiExtensionManager]).
     */
    fun packKeyOf(config: ProviderConfig): String? {
        val prefix = when (config.type) {
            ProviderType.ANIYOMI -> ANIYOMI_PREFIX
            ProviderType.MANGA -> MANGA_PREFIX
            else -> return null
        }
        if (!config.id.startsWith(prefix)) return null
        val index = config.id.substringAfterLast('|')
        if (index.toIntOrNull() == null) return null
        return config.id.dropLast(index.length + 1)
    }

    /**
     * [providers] as drawable rows, in their existing order, with each
     * extension's sources folded into one [ProviderPack].
     *
     * A group whose members share no usable name (nothing in common, so there is
     * no honest label for the row) is left as separate single rows — better the
     * old list than a row called "…".
     */
    fun rows(providers: List<ContentProvider>): List<ProviderPack> {
        val grouped = LinkedHashMap<String, MutableList<ContentProvider>>()
        val order = ArrayList<String>()
        for (p in providers) {
            val pkg = packKeyOf(p.config)
            val key = if (pkg == null) "id|" + p.config.id else "pack|" + pkg
            val bucket = grouped.getOrPut(key) {
                order += key
                mutableListOf()
            }
            // One source cannot appear twice in a row's list (the stored list
            // can, after an interrupted install).
            if (bucket.none { it.config.id == p.config.id }) bucket += p
        }
        val out = ArrayList<ProviderPack>(order.size)
        for (key in order) {
            val members = grouped[key].orEmpty()
            if (members.size <= 1) {
                val p = members.firstOrNull() ?: continue
                out += ProviderPack(listOf(p), p.config.name, listOf(""))
                continue
            }
            val label = commonLabel(members.map { it.config.name })
            if (label.length < 2) {
                members.forEach { out += ProviderPack(listOf(it), it.config.name, listOf("")) }
                continue
            }
            out += ProviderPack(members, label, members.map { detailOf(it.config.name, label) })
        }
        return out
    }

    /** What every member's name has in common, with the separator trimmed off. */
    private fun commonLabel(names: List<String>): String {
        if (names.isEmpty()) return ""
        var prefix = names[0]
        for (i in 1 until names.size) prefix = commonPrefix(prefix, names[i])
        return prefix.trim().trimEnd('·', '|', '-', '–', '—', ',').trim()
    }

    private fun commonPrefix(a: String, b: String): String {
        var i = 0
        while (i < a.length && i < b.length && a[i].lowercaseChar() == b[i].lowercaseChar()) i++
        return a.substring(0, i)
    }

    /** The part of [name] the group's [label] does not already say. */
    private fun detailOf(name: String, label: String): String {
        var rest = if (name.startsWith(label)) name.substring(label.length) else name
        rest = rest.trim().trimStart('·', '|', '-', '–', '—').trim()
        rest = rest.trimStart('(').trimEnd(')').trim()
        if (rest.toIntOrNull() != null) return "Source $rest"
        return rest
    }
}
