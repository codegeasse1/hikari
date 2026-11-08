package com.hikari.app.data

object PerfMode {

    private const val TV_ENGINE_MEMORY_CEILING = 64L * 1024 * 1024

    /** True while the user has the performance booster on. Read-only from the
     *  outside: [com.hikari.app.HikariApp] is the one that sets it. */
    @Volatile
    var on: Boolean = false
        private set

    /** Also true while the television's own performance mode is on: a TV stick
     *  and a struggling phone want the same things dropped. */
    @Volatile
    var tvOn: Boolean = false
        private set

    /** Either switch asking for less work. */
    val active: Boolean get() = on || tvOn

    @Volatile
    var tvDevice: Boolean = false
        private set

    fun setTvDevice(value: Boolean) {
        tvDevice = value
    }

    val tvEngineMemoryLimit: Long?
        get() = if (!tvDevice) {
            null
        } else {
            minOf(TV_ENGINE_MEMORY_CEILING, Runtime.getRuntime().maxMemory() / 4)
        }

    /** Called by the app's preference mirror — not a UI action. */
    fun set(on: Boolean, tvOn: Boolean) {
        this.on = on
        this.tvOn = tvOn
    }
}
