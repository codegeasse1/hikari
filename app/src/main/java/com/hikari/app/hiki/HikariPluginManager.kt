package com.hikari.app.hiki

import android.content.Context
import com.hikari.app.core.LoadGate
import com.hikari.ext.HikariProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStreamReader

object HikariPluginManager {

    private val cache = java.util.concurrent.ConcurrentHashMap<String, List<HikariProvider>>()

    // Paths whose load() just failed, with the failure timestamp — a failed
    // load is not retried hot (see providersFor).
    private val lastFail = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Paths whose load() is running on this thread (re-entrancy guard). */
    private val loading = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private const val FAIL_RETRY_MS = 60_000L

    fun evict(path: String) {
        cache.remove(path)
        lastFail.remove(path)
    }

    @Volatile
    var lastError: String? = null
        private set

    fun providersFor(context: Context, file: File): List<HikariProvider> {
        val path = file.absolutePath
        cache[path]?.let { return it }
        val failAt = lastFail[path]
        if (failAt != null && System.currentTimeMillis() - failAt < FAIL_RETRY_MS) return emptyList()
        val lock = LoadGate.lockFor("hiki:$path")
        if (!LoadGate.acquire(lock)) return emptyList()
        try {
            cache[path]?.let { return it }
            if (path in loading) return emptyList()
            loading.add(path)
            try {
                val list = try {
                    LoadGate.withSlot { loadFile(context, file) }
                } catch (e: LoadGate.LoadQueueBusyException) {
                    lastError = e.message
                    emptyList()
                }
                if (list.isNotEmpty()) {
                    cache[path] = list
                    lastFail.remove(path)
                } else {
                    lastFail[path] = System.currentTimeMillis()
                }
                return list
            } finally {
                loading.remove(path)
            }
        } finally {
            lock.unlock()
        }
    }

    fun reload(context: Context, file: File): List<HikariProvider> {
        val path = file.absolutePath
        val lock = LoadGate.lockFor("hiki:$path")
        if (!LoadGate.acquire(lock)) return emptyList()
        try {
            loading.add(path)
            val list = try {
                LoadGate.withSlot { loadFile(context, file) }
            } catch (e: LoadGate.LoadQueueBusyException) {
                lastError = e.message
                emptyList()
            }
            if (list.isNotEmpty()) {
                cache[path] = list
                lastFail.remove(path)
            } else {
                cache.remove(path)
                lastFail[path] = System.currentTimeMillis()
            }
            return list
        } finally {
            loading.remove(path)
            lock.unlock()
        }
    }

    private fun loadFile(context: Context, file: File): List<HikariProvider> {
        lastError = null
        val errors = StringBuilder()
        fun record(what: String, e: Throwable) {
            if (errors.length < 4000) {
                errors.append(what).append(": ").append(e.javaClass.simpleName)
                    .append(": ").append(e.message).append("\n")
            }
        }

        try {
            if (!file.setReadOnly()) {
                record("setReadOnly", RuntimeException("could not mark ${file.name} read-only"))
            }
        } catch (e: Throwable) {
            record("setReadOnly", e)
        }

        val classLoader = try {
            dalvik.system.PathClassLoader(file.absolutePath, context.classLoader)
        } catch (e: Throwable) {
            record("PathClassLoader", e)
            lastError = errors.toString().trim().ifBlank { "Could not open ${file.name}" }
            return emptyList()
        }

        val mainClasses = try {
            val stream = classLoader.getResourceAsStream("manifest.json")
                ?: throw RuntimeException("no manifest.json in ${file.name}")
            val text = stream.use { InputStreamReader(it).readText() }
            val root = JSONObject(text)
            when (val mc = root.opt("mainClass")) {
                null -> throw RuntimeException("manifest.json has no mainClass")
                is JSONArray -> (0 until mc.length()).mapNotNull { mc.optString(it).ifBlank { null } }
                else -> listOf(mc.toString())
            }
        } catch (e: Throwable) {
            record("manifest", e)
            lastError = errors.toString().trim().ifBlank { "Invalid manifest.json in ${file.name}" }
            return emptyList()
        }

        val out = mutableListOf<HikariProvider>()
        for (className in mainClasses) {
            val instance = try {
                val cls = classLoader.loadClass(className)
                if (!HikariProvider::class.java.isAssignableFrom(cls)) {
                    throw RuntimeException("$className does not implement com.hikari.ext.HikariProvider")
                }
                cls.getDeclaredConstructor().newInstance() as HikariProvider
            } catch (e: Throwable) {
                record("loadClass $className", e)
                null
            }
            if (instance != null) out += instance
        }
        if (out.isEmpty()) {
            val detail = errors.toString().trim()
            lastError = if (detail.isNotBlank()) detail else "Extension registered no providers"
        }
        return out
    }
}
