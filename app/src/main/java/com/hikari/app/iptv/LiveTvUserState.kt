package com.hikari.app.iptv

/**
 * Small user-state edits (channel/group reorder) shared by the Manage screen.
 * Everything runs through the store so it persists and the guide picks it up.
 */
object LiveTvUserStateAccess {

    /** Moves one channel up (dir=-1) or down (dir=+1) inside [groupId]. */
    fun moveChannel(groupId: String, channelKey: String, dir: Int) {
        if (groupId.isBlank()) return
        LiveTvEngine.store.updateUserState { st ->
            val cur = st.channelOrder[groupId].orEmpty()
            val base = if (cur.isEmpty()) {
                val g = LiveTvEngine.groupsOf(null).firstOrNull { it.id == groupId }
                    ?: return@updateUserState st
                LiveTvEngine.channelsInGroup(g, null).map { it.key }
            } else cur
            val i = base.indexOf(channelKey)
            val j = (i + dir).coerceIn(0, base.size - 1)
            if (i < 0 || i == j) return@updateUserState st
            val next = base.toMutableList()
            next.removeAt(i)
            next.add(j, channelKey)
            st.copy(channelOrder = st.channelOrder + (groupId to next))
        }
    }

    /** Moves one group up (dir=-1) or down (dir=+1) in the guide order. */
    fun moveGroup(groupId: String, dir: Int) {
        LiveTvEngine.store.updateUserState { st ->
            val cur = st.groupOrder.ifEmpty { LiveTvEngine.groupsOf(null).map { it.id } }
            val i = cur.indexOf(groupId)
            val j = (i + dir).coerceIn(0, cur.size - 1)
            if (i < 0 || i == j || cur.isEmpty()) return@updateUserState st
            val next = cur.toMutableList()
            next.removeAt(i)
            next.add(j, groupId)
            st.copy(groupOrder = next)
        }
    }
}
