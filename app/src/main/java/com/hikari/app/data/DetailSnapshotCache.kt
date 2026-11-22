package com.hikari.app.data

/** Last-visit page state for the detail screen: paint instantly on reopen,
 *  then the normal load overwrites each section as fresher data lands
 *  (stale-while-revalidate). Episodes are NOT stored here — the repository's
 *  own episode cache already publishes those first.
 *
 *  Bounded (a dozen titles, text only — posters stay in Coil's disk cache),
 *  6-hour TTL, and keyed with the metadata fingerprint, so a settings toggle
 *  or a new addon install re-resolves instead of serving the old text. */
object DetailSnapshotCache {

    data class Snapshot(
        val meta: MediaItem,
        val extras: TitleExtras?,
        val related: List<MediaItem>,
        val similar: List<MediaItem>,
        val recommended: List<MediaItem>,
        val addonLogo: String?,
        val fingerprint: String,
        val atMs: Long,
    )

    private const val MAX_TITLES = 12
    private const val TTL_MS = 6 * 60 * 60 * 1000L

    private val map = object : LinkedHashMap<String, Snapshot>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Snapshot>?) =
            size > MAX_TITLES
    }

    @Synchronized
    fun get(key: String, fingerprint: String): Snapshot? {
        val s = map[key] ?: return null
        if (System.currentTimeMillis() - s.atMs > TTL_MS) {
            map.remove(key)
            return null
        }
        if (s.fingerprint != fingerprint) {
            map.remove(key)
            return null
        }
        return s
    }

    @Synchronized
    fun put(key: String, snap: Snapshot) {
        map[key] = snap
    }

    @Synchronized
    fun evict(key: String) {
        map.remove(key)
    }
}
