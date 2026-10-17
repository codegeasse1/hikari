package com.hikari.app.data

import android.app.ActivityManager
import android.content.Context
import android.os.Debug

/**
 * The app's memory ceiling and its current usage, as one short line for the log.
 *
 * Why this exists: "the app gets laggy while the servers load, and sometimes it
 * almost crashes" is a question about MEMORY, and the app's own log carried no
 * memory numbers at all — the Java heap limit, the native heap the QuickJS
 * engines allocate from, and the device's own free RAM were all invisible, so a
 * report could only ever say "it feels slow". One line at startup, one at the
 * end of every server-search pass, and one whenever Android trims the process is
 * enough to see a pass that walks up to the ceiling.
 *
 * The two heaps are NOT one budget, which is why both are printed:
 *
 *  - the JAVA heap is capped by the device's `dalvik.vm.heapgrowthlimit`, or by
 *    `dalvik.vm.heapsize` since the manifest asks for a large heap (see the
 *    `android:largeHeap` attribute) — the numbers Android reports through
 *    [ActivityManager.getMemoryClass] / [ActivityManager.getLargeMemoryClass];
 *  - QuickJS's VM, the decoder and OkHttp allocate NATIVE memory, which that cap
 *    does not cover. Its only limit is the device's free RAM and the low-memory
 *    killer, so "raise the heap" does nothing for it — the levers there are
 *    fewer live engines and [com.hikari.app.nuvio.NuvioRuntime]'s per-engine
 *    budget.
 */
object MemoryReport {

    private const val MB = 1024L * 1024L

    /** "java 61/512MB native 84MB" — what the process holds right now. */
    fun short(): String {
        val runtime = Runtime.getRuntime()
        val max = runtime.maxMemory() / MB
        val used = (runtime.totalMemory() - runtime.freeMemory()) / MB
        val native = Debug.getNativeHeapAllocatedSize() / MB
        return "java ${used}/${max}MB native ${native}MB"
    }

    /** The DEVICE's own budget plus [short] — the startup line, and the one
     *  that answers "what heap does this phone actually give the app?". */
    fun device(context: Context): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return short()
        val info = ActivityManager.MemoryInfo()
        runCatching { am.getMemoryInfo(info) }
        val classMb = runCatching { am.memoryClass }.getOrDefault(0)
        val largeMb = runCatching { am.largeMemoryClass }.getOrDefault(0)
        return "heap class ${classMb}MB large ${largeMb}MB" +
            " · device ${info.totalMem / MB}MB total, ${info.availMem / MB}MB free" +
            " (low: ${info.lowMemory}) · " + short()
    }
}
