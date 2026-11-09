package com.hikari.app.iptv

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Parental controls: groups (and VOD categories under "vod:<uid>") that need
 * the PIN. Adult groups lock automatically by name. Unlocked groups stay open
 * until the app closes.
 */
object LiveTvParental {

    private val ADULT = Regex(
        """(?i)(\bxxx\b|\badults?\b|\bporn|\b18\s*\+|\+\s*18\b|\berotic|\bplayboy\b|\bhustler\b|\bbrazzers\b|\bredlight\b|\bsexy?\b|\bnsfw\b)"""
    )

    private val _unlocked = MutableStateFlow<Set<String>>(emptySet())
    val unlocked: StateFlow<Set<String>> = _unlocked.asStateFlow()

    fun unlocked(id: String): Boolean = id in _unlocked.value

    fun isAdult(name: String): Boolean = ADULT.containsMatchIn(name)

    fun isAdultChannel(ch: LiveChannel): Boolean = isAdult(ch.group) || isAdult(ch.name)

    fun enabled(): Boolean = LiveTvEngine.store.userState().parentalPin.length >= 4

    fun isLocked(id: String, name: String): Boolean {
        val st = LiveTvEngine.store.userState()
        if (st.parentalPin.length < 4 || id in _unlocked.value) return false
        return id in st.lockedGroups || (st.lockAdultContent && isAdult(name))
    }

    /** True (and the group stays open) if [pin] is right. */
    fun unlock(id: String, pin: String): Boolean {
        if (pin.trim() != LiveTvEngine.store.userState().parentalPin) return false
        _unlocked.value = _unlocked.value + id
        return true
    }

    fun checkPin(pin: String): Boolean =
        pin.trim() == LiveTvEngine.store.userState().parentalPin

    fun setPin(pin: String) {
        LiveTvEngine.store.updateUserState { it.copy(parentalPin = pin.trim()) }
        relock()
    }

    /** Locks every unlocked group again. */
    fun relock() {
        _unlocked.value = emptySet()
    }
}
