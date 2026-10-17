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

    /**
     * The flat ceiling for ONE engine's JavaScript heap on a television — see
     * [tvEngineMemoryLimit]. 64MB is still an order of magnitude above what a
     * working provider uses.
     */
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

    /**
     * True when the DEVICE ITSELF is a television — set from
     * [com.hikari.app.tv.TvMode.deviceIsTelevision], never from the user's
     * layout override.
     *
     * Deliberately separate from [tvOn], which is a SETTING the user can switch
     * off. A box with 1-1.5GB of RAM still has 1-1.5GB of RAM with every switch
     * off, so the engine budgets below are sized from what the hardware IS, not
     * from a preference. This is also what makes them television-only: it is
     * false on every phone and tablet, so a phone's search fan-out, detail
     * screen and engines are untouched by any of it.
     */
    @Volatile
    var tvDevice: Boolean = false
        private set

    /**
     * Called next to [com.hikari.app.tv.TvMode.detect] — at process start and
     * again from the Activity, since a few boxes only settle their UI mode once
     * an Activity exists. Idempotent.
     */
    fun setTvDevice(value: Boolean) {
        tvDevice = value
    }

    /**
     * The ceiling for ONE engine's JavaScript heap on a television, or null off
     * one (where each runtime keeps its own, larger default — see
     * [com.hikari.app.nuvio.NuvioRuntime]).
     *
     * A QuickJS engine's memory is NATIVE: it is not on the Java heap, so the
     * low-memory killer sees it as anonymous memory the whole process owns, and
     * that is what it acts on. The runtimes allow 256MB per engine — generous on
     * purpose (a cheerio tree over a few MB of HTML is tens of MB) — but that
     * adds up fast. Opening a detail page asks EVERY matched extension for its
     * meta and its episode list at once, so the engines arrive in a burst while
     * the page is also decoding a hero image, and a handful of engines each free
     * to grow to 256MB is several times the memory a 1GB box is comfortable
     * lending one app: the app is killed mid-load, which is the reported
     * "freezing and crashed while the detail screen and episodes load".
     *
     * Bounded twice over: 64MB flat, and never more than a quarter of this
     * process's own heap, so a device the platform already caps at 128MB cannot
     * hand engines more memory than the process has in total.
     */
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
