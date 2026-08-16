package com.hikari.app.cs3

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Loads compiled CloudStream `.cs3` plugin archives exactly the way the real
 * CloudStream app does (see CloudStream-3 `PluginManager.loadPlugin`):
 *
 *  1. mark the file read-only — Android 14+ refuses to load a writable dex
 *     file (`SecurityException: Writable dex file ... is not allowed`),
 *  2. open a [dalvik.system.PathClassLoader] on the archive,
 *  3. read `manifest.json` → `pluginClassName` (+ `requiresResources`),
 *  4. instantiate that class with a no-arg constructor,
 *  5. set `filename`, load optional resources, call `load()`,
 *  6. collect the MainAPIs the plugin registers.
 *
 * As a fallback (older plugins with no/partial manifests) it also scans the
 * other classes in the dex for anything that looks like a plugin.
 *
 * Instances are cached per file path so provider state survives across calls.
 * The last failure (if any) is surfaced on [lastError] so the UI can show the
 * REAL reason a plugin refused to load.
 */
object Cs3PluginManager {

    private val cache = HashMap<String, List<MainAPI>>()

    @Volatile
    var lastError: String? = null
        private set

    private val errorDetails = StringBuilder()

    private fun record(what: String, e: Throwable) {
        val line = "$what: ${e.javaClass.simpleName}: ${e.message}"
        if (errorDetails.length < 4000) {
            errorDetails.append(line).append("\n")
        }
        android.util.Log.e("Cs3PluginManager", line, e)
    }

    @Synchronized
    fun apisFor(context: Context, file: File): List<MainAPI> =
        cache.getOrPut(file.absolutePath) { loadFile(context, file) }

    @Synchronized
    fun reload(context: Context, file: File): List<MainAPI> {
        val apis = loadFile(context, file)
        cache[file.absolutePath] = apis
        return apis
    }

    private fun loadFile(context: Context, file: File): List<MainAPI> {
        val apis = mutableListOf<MainAPI>()
        errorDetails.setLength(0)
        lastError = null

        // 1) CloudStream does this first: Android 14+ refuses writable dex files.
        try {
            if (!file.setReadOnly()) {
                record("setReadOnly failed", RuntimeException("could not mark ${file.name} read-only"))
            }
        } catch (e: Throwable) {
            record("setReadOnly threw", e)
        }

        // 2) Open a class loader on the archive (PathClassLoader, like CloudStream).
        val classLoader = try {
            dalvik.system.PathClassLoader(file.absolutePath, context.classLoader)
        } catch (e: Throwable) {
            record("PathClassLoader failed", e)
            finish(null, apis)
            return apis
        }

        // 3) manifest.json → pluginClassName (+ requiresResources)
        val manifest = try {
            readManifest(file)
        } catch (e: Throwable) {
            record("manifest read failed", e)
            null
        }
        val manifestClass = manifest?.pluginClassName

        // 4) names to consider: manifest class first, then every com./org. class
        //    in the dex as a fallback for plugins with no usable manifest.
        val names = dexClassNames(file)
        val loaded = HashSet<String>()
        val loadQueue = ArrayList<String>()
        manifestClass?.let { loadQueue.add(it) }
        for (n in names) {
            if (n.startsWith("com.lagradost.") || n.startsWith("com.hikari.")) continue
            if (n.startsWith("com.") || n.startsWith("org.")) {
                if (!loaded.contains(n)) loadQueue.add(n)
            }
        }

        for (name in loadQueue) {
            if (loaded.contains(name)) continue
            loaded.add(name)
            val cls = try {
                Class.forName(name, true, classLoader)
            } catch (e: Throwable) {
                record("Class.forName $name failed", e)
                continue
            }
            val looksLikePlugin =
                cls.isAnnotationPresent(CloudstreamPlugin::class.java) ||
                    BasePlugin::class.java.isAssignableFrom(cls) ||
                    Plugin::class.java.isAssignableFrom(cls)
            if (!looksLikePlugin) continue

            val instance = try {
                val ctor = cls.getDeclaredConstructor()
                @Suppress("DEPRECATION")
                ctor.isAccessible = true
                ctor.newInstance()
            } catch (e: Throwable) {
                record("instantiate $name failed", e)
                continue
            }

            // 5) CloudStream sets filename + optional resources, then load().
            try {
                if (instance is BasePlugin) instance.filename = file.absolutePath
                if (manifest != null && manifest.requiresResources && instance is Plugin) {
                    try {
                        val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
                        val addPath = AssetManager::class.java.getMethod("addAssetPath", String::class.java)
                        addPath.invoke(assets, file.absolutePath)
                        instance.resources = Resources(
                            assets as AssetManager,
                            context.resources.displayMetrics,
                            context.resources.configuration
                        )
                    } catch (e: Throwable) {
                        record("resource loading for $name failed", e)
                    }
                }
                when (instance) {
                    is Plugin -> {
                        instance.load(context)
                        apis += instance.apis
                    }
                    is BasePlugin -> {
                        instance.load()
                        apis += instance.apis
                    }
                }
            } catch (e: Throwable) {
                record("load() of $name threw", e)
            }
        }

        finish(manifestClass, apis)
        return apis
    }

    private fun finish(manifestClass: String?, apis: List<MainAPI>) {
        if (apis.isEmpty()) {
            val details = errorDetails.toString().trim()
            lastError = if (details.isNotBlank()) {
                details
            } else {
                "No CloudStream plugin class found (manifest class: $manifestClass)"
            }
        }
    }

    private class Manifest(
        val pluginClassName: String?,
        val requiresResources: Boolean,
    )

    private fun readManifest(file: File): Manifest? {
        var manifestText: String? = null
        ZipInputStream(file.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.name == "manifest.json") {
                    manifestText = zis.readBytes().toString(Charsets.UTF_8)
                }
                zis.closeEntry()
            }
        }
        manifestText ?: return null
        val manifest = JSONObject(manifestText)
        return Manifest(
            pluginClassName = manifest.optString("pluginClassName").takeIf { it.isNotBlank() },
            requiresResources = manifest.optBoolean("requiresResources", false),
        )
    }

    /** Parses class names straight from classes.dex (handles packed id tables). */
    private fun dexClassNames(file: File): List<String> {
        val names = mutableListOf<String>()
        try {
            val dexBytes = mutableListOf<ByteArray>()
            ZipInputStream(file.inputStream().buffered()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (entry.name.endsWith(".dex")) dexBytes.add(zis.readBytes())
                    zis.closeEntry()
                }
            }
            for (bytes in dexBytes) {
                names += dexClassNames(bytes)
            }
        } catch (e: Exception) {
        }
        return names.distinct()
    }

    private fun dexClassNames(bytes: ByteArray): List<String> {
        val out = mutableListOf<String>()
        try {
            fun u32(o: Int) =
                (bytes[o].toLong() and 0xff) or
                    ((bytes[o + 1].toLong() and 0xff) shl 8) or
                    ((bytes[o + 2].toLong() and 0xff) shl 16) or
                    ((bytes[o + 3].toLong() and 0xff) shl 24)

            if (bytes.size < 8) return out
            if (bytes[0] != 'd'.code.toByte() || bytes[1] != 'e'.code.toByte() || bytes[2] != 'x'.code.toByte()) return out

            fun uleb(start: Int): Pair<Long, Int> {
                var result = 0L
                var shift = 0
                var idx = start
                while (true) {
                    val b = bytes[idx].toInt() and 0xff
                    idx++
                    result = result or ((b and 0x7f).toLong() shl shift)
                    if (b and 0x80 == 0) break
                    shift += 7
                }
                return result to idx
            }

            fun readString(off: Int): String {
                val (_, start) = uleb(off)
                var i = start
                val sb = StringBuilder()
                while (true) {
                    val b = bytes[i].toInt() and 0xff
                    i++
                    if (b == 0) break
                    when {
                        b < 0x80 -> sb.append(b.toChar())
                        (b and 0xe0) == 0xc0 -> {
                            sb.append((((b and 0x1f) shl 6) or (bytes[i].toInt() and 0x3f)).toChar())
                            i++
                        }
                        (b and 0xf0) == 0xe0 -> {
                            sb.append(
                                (((b and 0x0f) shl 12) or
                                    ((bytes[i].toInt() and 0x3f) shl 6) or
                                    (bytes[i + 1].toInt() and 0x3f)).toChar()
                            )
                            i += 2
                        }
                        else -> return ""
                    }
                }
                return sb.toString()
            }

            val stringIdsSize = u32(0x38).toInt()
            val stringIdsOff = u32(0x3c).toInt()
            val typeIdsSize = u32(0x40).toInt()
            val typeIdsOff = u32(0x44).toInt()
            val classDefsSize = u32(0x60).toInt()
            val classDefsOff = u32(0x64).toInt()
            if (stringIdsOff + stringIdsSize * 4 > bytes.size) return out

            val strings = Array(stringIdsSize) { "" }
            for (i in 0 until stringIdsSize) {
                strings[i] = readString(u32(stringIdsOff + i * 4).toInt())
            }
            val types = Array(typeIdsSize) { "" }
            for (i in 0 until typeIdsSize) {
                types[i] = strings[u32(typeIdsOff + i * 4).toInt()]
            }
            for (i in 0 until classDefsSize) {
                val clsIdx = u32(classDefsOff + i * 32).toInt()
                val t = types[clsIdx]
                if (t.startsWith("L") && t.endsWith(";")) {
                    out.add(t.substring(1, t.length - 1).replace('/', '.'))
                }
            }
        } catch (e: Exception) {
        }
        return out
    }
}
