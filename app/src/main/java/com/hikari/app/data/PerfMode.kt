package com.hikari.app.data

/**
 * The performance booster's current value, readable SYNCHRONOUSLY from
 * anywhere in the app.
 *
 * The setting itself lives in [AppStore] (`perfModeFlow`), which is the right
 * place for it — but the code that has to act on it is not all composable:
 * the cross-extension search fan-out in
 * [com.hikari.app.data.ContentRepository] sizes itself from
 * `Runtime.availableProcessors()`, and the Nuvio runtime gates its engine
 * concurrency with a `Semaphore`. Both need one boolean NOW, on a hot path,
 * with no flow collection in sight (and one of them runs while the user is
 * already waiting for a server).
 *
 * [com.hikari.app.HikariApp] therefore mirrors the preference into this object
 * once at startup and on every change, so `PerfMode.on` is a plain field read.
 * It is deliberately a one-way mirror of a user SETTING — nothing here decides
 * anything, it only reports what the switch says.
 */
object PerfMode {

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

    /** Called by the app's preference mirror — not a UI action. */
    fun set(on: Boolean, tvOn: Boolean) {
        this.on = on
        this.tvOn = tvOn
    }
}
