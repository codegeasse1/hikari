package com.hikari.app.tracker

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object TrackerRedirect {

    private val _incoming = MutableStateFlow<String?>(null)

    /** The last `hikari://oauth` link handed to the app from a browser. */
    val incoming: StateFlow<String?> = _incoming

    @Volatile
    var dialogOpen: Boolean = false

    /** Offers a redirect to whoever can finish it. */
    fun deliver(url: String) {
        _incoming.value = url
    }

    /** Takes the current redirect, leaving nothing for the next reader. */
    fun consume() {
        _incoming.value = null
    }
}
