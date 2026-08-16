package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.res.Resources
import com.lagradost.cloudstream3.MainAPI

annotation class CloudstreamPlugin

/**
 * Base class for the two plugin styles the compiled providers use:
 *  - `BasePlugin` → override `load()` (no Context)
 *  - `Plugin` → override `load(context)`
 *
 * Mirrors the real CloudStream3 `BasePlugin` ABI (filename, registerMainAPI,
 * resources on [Plugin]).
 */
open class BasePlugin {

    var filename: String = ""

    internal val apis = mutableListOf<MainAPI>()

    open fun load() {
    }

    fun registerMainAPI(mainAPI: MainAPI) {
        apis.add(mainAPI)
    }
}

open class Plugin : BasePlugin() {

    var resources: Resources? = null

    open fun load(context: Context) {
    }
}
