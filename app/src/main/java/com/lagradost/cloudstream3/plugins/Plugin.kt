package com.lagradost.cloudstream3.plugins

import android.content.Context
import com.lagradost.cloudstream3.MainAPI

annotation class CloudstreamPlugin

/**
 * Base class for the two plugin styles the compiled providers use:
 *  - `BasePlugin` → override `load()` (no Context)
 *  - `Plugin` → override `load(context)`
 */
open class BasePlugin {

    internal val apis = mutableListOf<MainAPI>()

    open fun load() {
    }

    fun registerMainAPI(mainAPI: MainAPI) {
        apis.add(mainAPI)
    }
}

open class Plugin : BasePlugin() {

    open fun load(context: Context) {
    }
}
