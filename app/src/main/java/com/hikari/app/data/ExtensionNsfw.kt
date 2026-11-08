package com.hikari.app.data

import android.content.Context
import com.hikari.app.aniyomi.AniyomiExtensionManager
import com.hikari.app.manga.MangaExtensionManager
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object ExtensionNsfw {

    /** `.ext` file path → tagged 18+? Only ever holds definite answers. */
    private val cache = ConcurrentHashMap<String, Boolean>()

    fun isNsfw(context: Context, config: ProviderConfig): Boolean {
        config.nsfw?.let { return it }
        // 2. What a loaded extension has already told us (see [remember]).
        val path = config.url
        if (path.isBlank()) return false
        cache[path]?.let { return it }
        // 3. The extension's own metadata, for the ecosystems that publish it.
        val tagged = when (config.type) {
            ProviderType.MANGA ->
                MangaExtensionManager.extensionOf(context, File(path))?.isNsfw
            ProviderType.ANIYOMI ->
                AniyomiExtensionManager.extensionOf(context, File(path))?.isNsfw
            else -> false
        } ?: return false
        cache[path] = tagged
        return tagged
    }

    fun decidableFromMetadata(config: ProviderConfig): Boolean =
        config.type == ProviderType.MANGA || config.type == ProviderType.ANIYOMI ||
            config.type == ProviderType.FORK_VIDEO

    /** Records an answer a loaded extension gave about itself (its `.ext` file). */
    fun remember(path: String, adult: Boolean) {
        if (path.isNotBlank()) cache[path] = adult
    }

    fun allows(context: Context, config: ProviderConfig): Boolean =
        NsfwGate.enabled || !isNsfw(context, config)

    fun filter(context: Context, rows: List<ProviderConfig>): List<ProviderConfig> {
        if (NsfwGate.enabled) return rows
        return rows.filter { !isNsfw(context, it) }
    }

    fun repoEntryNsfw(o: JSONObject, tvTypes: List<String> = emptyList()): Boolean {
        if (tvTypes.any { it.equals("NSFW", ignoreCase = true) }) return true
        val warning = o.optString("contentWarning").trim()
        if (warning.equals("CONTENT_WARNING_NSFW", ignoreCase = true)) return true
        if (warning.toIntOrNull()?.let { it > 0 } == true) return true
        o.opt("nsfw")?.let { flag ->
            return when (flag) {
                is Boolean -> flag
                else -> flag.toString().trim() == "1"
            }
        }
        return false
    }
}
