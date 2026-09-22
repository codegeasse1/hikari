package com.hikari.app.data

import android.content.Context
import com.hikari.app.aniyomi.AniyomiExtensionManager
import com.hikari.app.manga.MangaExtensionManager
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Which INSTALLED extensions are tagged 18+, for the adult-content switch.
 *
 * **Why this is not a field on [ProviderConfig].** The tag belongs to the
 * extension's own metadata (`tachiyomi.extension.nsfw` on a Mihon/manga build,
 * `tachiyomi.animeextension.nsfw` and Aniyomi's `contentWarning` on an anime
 * one) and Hikari never copies it into its stored provider rows — a row carries
 * only the id, the name, the `.ext` file it was installed from and the repo it
 * came from. Reading it therefore means asking the manager that loads the
 * extension, which is what this object does. The alternative — persisting the
 * flag at install time — would have to be backfilled for every extension the
 * user already has, and an extension that was installed before the flag existed
 * would silently stay visible with the switch off, which is the one failure this
 * feature must not have.
 *
 * **Cost.** Loading an extension instantiates its classes, so a call here is
 * BLOCKING and must never happen on the UI thread (the Aniyomi loader returns
 * null outright if it is). It is only ever asked when the switch is OFF — with
 * the default (ON) nothing is looked up at all — and the answer is cached per
 * `.ext` file, so the cost is one metadata load per extension per session, on
 * the same loader cache the provider list already pays for at startup.
 *
 * **A load that fails is not a verdict.** An extension that will not load
 * cannot serve a catalogue either (the provider that would query it needs the
 * same loaded metadata), so a failed lookup answers "not tagged", and — this is
 * the important part — does NOT cache that answer: the retry window inside the
 * loader ([com.hikari.app.manga.MangaExtensionManager.extensionOf]) is what
 * decides when to try again, and a permanent `false` here would outlive it.
 */
object ExtensionNsfw {

    /** `.ext` file path → tagged 18+? Only ever holds definite answers. */
    private val cache = ConcurrentHashMap<String, Boolean>()

    /**
     * The extension a provider row was installed from, whether it is tagged 18+.
     * False for every engine that has no such concept (CloudStream/Hikari/
     * SkyStream/Nuvio plugins declare their adult content per TITLE, not per
     * extension — see [NsfwGate.isAdult], which is what catches those).
     */
    fun isNsfw(context: Context, config: ProviderConfig): Boolean {
        val path = config.url
        if (path.isBlank()) return false
        cache[path]?.let { return it }
        val tagged = when (config.type) {
            ProviderType.MANGA ->
                MangaExtensionManager.extensionOf(context, File(path))?.isNsfw
            ProviderType.ANIYOMI ->
                AniyomiExtensionManager.extensionOf(context, File(path))?.isNsfw
            else -> false
        } ?: return false
        cache[path] = tagged
        return tagged
    }

    /**
     * Whether a provider row may be USED: an 18+ extension only with the switch
     * on. The provider list itself is built through this (see
     * [com.hikari.app.providers.ProviderManager]), so with the switch off an
     * adult extension is not instantiated at all — its catalogues cannot appear
     * on Home, skip the title filter, or be searched into existence by a
     * collection that still references it.
     *
     * One consequence, deliberately accepted and worth knowing while reading a
     * bug report: with the switch off an installed 18+ extension is not listed
     * on the Extensions screen either, so it cannot be uninstalled from there
     * until the switch is turned back on.
     */
    fun allows(context: Context, config: ProviderConfig): Boolean =
        NsfwGate.enabled || !isNsfw(context, config)

    /**
     * [rows] without the extensions the switch hides, in order. Cheap and
     * allocation-free with the switch on (the default), which is the path every
     * startup takes.
     */
    fun filter(context: Context, rows: List<ProviderConfig>): List<ProviderConfig> {
        if (NsfwGate.enabled) return rows
        return rows.filter { !isNsfw(context, it) }
    }

    /**
     * Whether a REPO LISTING entry is tagged 18+ — the pre-install half of the
     * same rule, read from the fields the three ecosystems actually publish:
     *
     *  * `tvTypes` containing `NSFW` — how a CloudStream repo declares one (the
     *    same `TvType.NSFW` that [MediaItem.nsfw] comes from, but per plugin);
     *  * `nsfw` — a `1`/`true` flag on the older Mihon/Aniyomi index entries
     *    (`"code":17,…, "nsfw":0`);
     *  * `contentWarning` — the modern Mihon/Aniyomi spelling, a string
     *    `CONTENT_WARNING_NSFW` (keiyoushi's index) or an int warning level.
     *
     * [tvTypes] is passed in because the caller has usually already read it (the
     * CloudStream listing spells it as a JSON array, the Mihon one doesn't have
     * the field at all).
     */
    fun repoEntryNsfw(o: JSONObject, tvTypes: List<String> = emptyList()): Boolean {
        if (tvTypes.any { it.equals("NSFW", ignoreCase = true) }) return true
        val warning = o.optString("contentWarning").trim()
        if (warning.equals("CONTENT_WARNING_NSFW", ignoreCase = true)) return true
        if (warning.toIntOrNull()?.let { it > 0 } == true) return true
        o.opt("nsfw")?.let { flag ->
            return when (flag) {
                is Boolean -> flag
                else -> flag.toString().trim() == "1"
            }
        }
        return false
    }
}
