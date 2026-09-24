package com.hikari.app.providers

import com.hikari.app.data.CatalogRef
import com.hikari.app.data.Episode
import com.hikari.app.data.MediaItem
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.StreamSource

interface ContentProvider {
    val config: ProviderConfig

    /**
     * Whether this provider's OWN extension is an 18+ one, or null when this kind
     * of provider cannot say.
     *
     * Most extensions declare it in metadata the app already reads — but the
     * CloudStream/Hikari/SkyStream/Nuvio family declares adult content per
     * TITLE, so the only thing that knows whether the EXTENSION is an adult one
     * is the extension itself. This is the hook for that one question, asked
     * once per extension when the adult-content switch is off and remembered on
     * the row (see [com.hikari.app.providers.ProviderManager.learnAdultFlags]),
     * so that switching the switch off hides an installed 18+ extension instead
     * of only hiding the ones not yet installed.
     *
     * Implementations may load the extension to answer (a CloudStream plugin
     * keeps its `supportedTypes` in its code); callers must therefore treat this
     * as blocking, ask it OFF the main thread, and ask it as rarely as possible.
     */
    fun adultExtension(): Boolean? = null

    /**
     * True when this provider exposes its own settings screen that Hikari can
     * open (CloudStream plugins do this via `Plugin.openSettings`). The
     * Extensions UI only shows a settings button for providers that return
     * true here — exactly like CloudStream.
     */
    val settingsAvailable: Boolean get() = false

    /**
     * True when [openSettings] can be honoured immediately. Must be cheap and
     * safe to read from the main thread (cache lookups only) — the Extensions
     * UI uses it to decide whether a tap needs a "loading…" indicator first.
     */
    val settingsReady: Boolean get() = settingsAvailable

    /**
     * Loads whatever [openSettings] needs (the Extensions UI shows the settings
     * gear from stored data, which can outlive the in-memory provider runtime).
     * Blocking — always call from IO. Returns false when this provider has no
     * settings screen at all.
     */
    fun prepareSettings(): Boolean = settingsAvailable

    /**
     * Throws away any cached plugin/provider instance so the NEXT
     * [prepareSettings]/[openSettings] rebuilds it from scratch with the
     * CURRENT activity. A plugin whose settings screen bailed out on a stale
     * activity (a `FragmentManager has been destroyed` IllegalStateException —
     * the plugin's callback had captured an activity that has since gone) only
     * recovers with a real rebuild: retrying the same instance re-runs the same
     * closure against the same dead activity. Blocking — call from IO.
     */
    fun rebuildSettings(context: android.content.Context): Boolean = prepareSettings()

    /**
     * Opens the provider's own settings UI. [activity] is the host activity the
     * settings screen should attach its dialogs/fragments to (null = let the
     * provider resolve the current one). Returns false when unsupported or when
     * opening failed.
     */
    fun openSettings(activity: android.app.Activity?): Boolean = false

    suspend fun catalogs(): List<CatalogRef>
    suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem>
    suspend fun search(query: String, page: Int): List<MediaItem>
    suspend fun getMeta(item: MediaItem): MediaItem
    suspend fun getEpisodes(item: MediaItem): List<Episode>?
    suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource>
}
