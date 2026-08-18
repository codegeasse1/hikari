package com.hikari.app.hiki

import android.content.Context
import com.hikari.ext.HikariProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStreamReader

/**
 * Loads compiled Hikari `.hiki` extension archives exactly the way CloudStream
 * loads `.cs3` plugins (see Cs3PluginManager): mark the file read-only (Android
 * 14+ refuses writable dex files), open a PathClassLoader on the archive
 * (parent = the app, which resolves the com.hikari.ext API classes), read
 * manifest.json → mainClass, and instantiate each class implementing
 * [HikariProvider].
 *
 * manifest.json keys:
 *   { "name": "My Extension", "version": 1,
 *     "mainClass": "com.example.MyProvider" }          // or an array
 *
 * Instances are cached per file path. The last failure (if any) is surfaced on
 * [lastError] so the Extensions screen can show the real reason an extension
 * refused to load.
 */
object HikariPluginManager {

    private val cache = HashMap<String, List<HikariProvider>>()

    @Volatile
    var lastError: String? = null
        private set

    @Synchronized
    fun providersFor(context: Context, file: File): List<HikariProvider> =
        cache.getOrPut(file.absolutePath) { loadFile(context, file) }

    @Synchronized
    fun reload(context: Context, file: File): List<HikariProvider> {
        val list = loadFile(context, file)
        cache[file.absolutePath] = list
        return list
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
