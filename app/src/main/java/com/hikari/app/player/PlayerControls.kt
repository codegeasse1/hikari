package com.hikari.app.player

import org.json.JSONObject

enum class PlayerControlSlot(val key: String, val label: String, val blurb: String) {
    TOP_BAR("top", "Top bar", "Up with the title and the back button"),
    BOTTOM_LEFT("left", "Bottom left", "Left end of the controls row"),
    BOTTOM_RIGHT("right", "Bottom right", "Right end of the controls row"),
    HIDDEN("hidden", "Hidden", "Not shown at all");

    companion object {
        fun fromKey(key: String?): PlayerControlSlot =
            entries.firstOrNull { it.key == key } ?: BOTTOM_LEFT
    }
}

enum class PlayerControl(
    val key: String,
    val label: String,
    val desc: String,
    val defaultSlot: PlayerControlSlot,
) {
    FAVORITE("favorite", "Favourite", "Add the title to your library", PlayerControlSlot.TOP_BAR),
    DOWNLOAD("download", "Download", "Save an offline copy of this video", PlayerControlSlot.TOP_BAR),
    PIP("pip", "Picture-in-picture", "Float the video over other apps", PlayerControlSlot.TOP_BAR),
    OPTIONS("options", "Player settings", "Playback, subtitles and appearance", PlayerControlSlot.TOP_BAR),
    LOCK("lock", "Lock controls", "Hide the controls until you unlock", PlayerControlSlot.TOP_BAR),
    SPEED("speed", "Playback speed", "Cycle through 1x, 1.25x, 1.5x, 2x", PlayerControlSlot.BOTTOM_LEFT),
    EPISODES("episodes", "Episodes", "Jump to another episode", PlayerControlSlot.BOTTOM_LEFT),
    CHANNELS("channels", "Channels", "Switch to another channel in the same group", PlayerControlSlot.BOTTOM_LEFT),
    SOURCES("sources", "Servers", "Switch between the servers found", PlayerControlSlot.BOTTOM_LEFT),
    QUALITY("quality", "Quality", "Pick a video track", PlayerControlSlot.BOTTOM_LEFT),
    AUDIO("audio", "Audio track", "Pick an audio track", PlayerControlSlot.BOTTOM_LEFT),
    SUBS("subs", "Subtitles", "Choose or turn off subtitles", PlayerControlSlot.BOTTOM_LEFT),
    ROTATE("rotate", "Rotate", "Rotate the video 90°", PlayerControlSlot.BOTTOM_LEFT),
    SKIP("skip", "Skip intro", "Jump past the opening titles", PlayerControlSlot.BOTTOM_LEFT),
    RESIZE("resize", "Resize video", "Fit, crop or stretch the picture", PlayerControlSlot.BOTTOM_RIGHT),
    ENHANCE("enhance", "Enhance", "Switch the video enhance preset", PlayerControlSlot.BOTTOM_RIGHT),
    EXTERNAL("external", "External player", "Play this video in another app", PlayerControlSlot.BOTTOM_RIGHT);

    companion object {
        fun fromKey(key: String?): PlayerControl? = entries.firstOrNull { it.key == key }
    }
}

object PlayerControlsConfig {

    fun defaults(): Map<PlayerControl, PlayerControlSlot> =
        PlayerControl.entries.associateWith { it.defaultSlot }

    fun decode(json: String?): Map<PlayerControl, PlayerControlSlot> {
        val out = defaults().toMutableMap()
        if (json.isNullOrBlank()) return out
        runCatching {
            val obj = JSONObject(json)
            for (c in PlayerControl.entries) {
                val v = obj.optString(c.key, "")
                if (v.isNotBlank()) out[c] = PlayerControlSlot.fromKey(v)
            }
        }
        return out
    }

    fun encode(map: Map<PlayerControl, PlayerControlSlot>): String {
        val obj = JSONObject()
        for (c in PlayerControl.entries) obj.put(c.key, (map[c] ?: c.defaultSlot).key)
        return obj.toString()
    }

    /** True when [map] is the layout the player ships with. */
    fun isDefault(map: Map<PlayerControl, PlayerControlSlot>): Boolean =
        PlayerControl.entries.all { (map[it] ?: it.defaultSlot) == it.defaultSlot }
}
