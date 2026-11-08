package com.hikari.app.data

import android.app.ActivityManager
import android.content.Context
import android.os.Debug

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
