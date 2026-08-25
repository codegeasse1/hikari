package com.hikari.app.data

import com.hikari.app.net.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray

/**
 * Translates the app's own content (media titles, descriptions, episode names)
 * into English for the extensions the user enabled "always translate" on.
 *
 * - Everything goes through Google's free gtx endpoint (no API key, no CORS
 *   since the app does the request itself).
 * - Text that is already English is recognized by its stopwords and skipped
 *   without a request; anything else is auto-detected and translated.
 * - Results are cached in memory (with a persisted copy in the store) so
 *   re-loading a catalog doesn't re-translate everything.
 *
 * Only the [enabledIds] set of provider ids is ever translated, so extensions
 * the user didn't enable stay untouched.
 */
object Translator {

    private val lock = Any()
    private val cache = LinkedHashMap<String, String>()
    private val done = HashSet<String>()
    private val enabled = HashSet<String>()
    @Volatile
    private var initialized = false
    private var lastPersist = 0L

    @Volatile
    private var storeRef: AppStore? = null

    private val LETTERS =
        Regex("[A-Za-z\\u00C0-\\u024F\\u0370-\\u03FF\\u0400-\\u04FF\\u3040-\\u30FF\\u3400-\\u9FFF\\uAC00-\\uD7AF\\uF900-\\uFAFF]")
    private val PUNCT_ONLY =
        Regex("""^[\d\s.,!?%$#@&*()/\-+='"<>\[\]{}|\\:;_~^`\u00A0]+$""")
    private val ASCII = Regex("""^[\x00-\x7F]+$""")
    private val EN_STOP = Regex(
        """^(the|and|of|to|in|is|are|was|were|for|with|on|at|by|this|that|these|those|you|your|we|our|they|their|them|it|its|a|an|or|but|as|from|not|be|have|has|had|i|me|my|do|does|did|what|which|who|when|where|why|there|here|can|will|would|should|could|then|than|so|if|up|out|about|just|more|most|all|any|some|also|only|into|over|under|no|yes)$""",
        RegexOption.IGNORE_CASE
    )

    /** Loads the persisted config + translation cache. Call once at app start. */
    suspend fun init(store: AppStore) {
        storeRef = store
        val pairs = runCatching { store.translateCache() }.getOrDefault(emptyList())
        val enabledSet = runCatching { store.translateProviders() }.getOrDefault(emptySet())
        synchronized(lock) {
            cache.clear()
            for ((k, v) in pairs.takeLast(2000)) cache[k] = v
            enabled.clear()
            enabled += enabledSet
            initialized = true
        }
    }

    /** Which provider ids currently have "always translate" on. */
    fun enabledIds(): Set<String> {
        synchronized(lock) {
            if (initialized) return enabled.toSet()
            // Not initialized yet (first frame after launch): nothing enabled.
            return emptySet()
        }
    }

    fun isOn(providerId: String): Boolean = providerId in enabledIds()

    /** Turns per-extension translation on/off and persists the choice. */
    suspend fun enable(providerId: String, on: Boolean) {
        storeRef?.let { runCatching { it.setTranslateProvider(providerId, on) } }
        synchronized(lock) {
            if (on) enabled.add(providerId) else enabled.remove(providerId)
        }
    }

    /** True when [text] should be sent to the translator. */
    fun shouldTranslate(text: String): Boolean {
        if (text.isBlank()) return false
        val t = text.trim()
        if (t.length < 2) return false
        if (!LETTERS.containsMatchIn(t)) return false
        if (PUNCT_ONLY.matches(t)) return false
        synchronized(lock) {
            if (cache.containsKey(t)) return true
            if (done.contains(t)) return false
        }
        if (ASCII.matches(t) && looksEnglish(t)) {
            synchronized(lock) { done.add(t) }
            return false
        }
        return true
    }

    /** Translates one string to English (returns it unchanged when it can't
     *  be translated or is already English). */
    suspend fun translate(text: String): String {
        if (!shouldTranslate(text)) return text
        synchronized(lock) {
            cache[text]?.let { return it }
            if (done.contains(text)) return text
        }
        val translated = fetch(text)
        if (translated.isBlank() || translated == text) {
            synchronized(lock) { done.add(text) }
            return text
        }
        synchronized(lock) {
            cache[text] = translated
            if (cache.size > 2500) {
                val it = cache.entries.iterator()
                it.next()
                it.remove()
            }
        }
        maybePersist()
        return translated
    }

    /** Translates many strings in parallel (capped concurrency), preserving
     *  order and returning each input unchanged when untranslatable. */
    suspend fun translateAll(texts: List<String>): List<String> = coroutineScope {
        val gate = Semaphore(6)
        texts.map { t ->
            async(Dispatchers.IO) { gate.withPermit { translate(t) } }
        }.awaitAll()
    }

    private fun looksEnglish(t: String): Boolean {
        for (m in Regex("[A-Za-z]+").findAll(t)) {
            if (EN_STOP.matches(m.value)) return true
        }
        return false
    }

    private fun fetch(text: String): String {
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx" +
            "&sl=auto&tl=en&dt=t&q=" + java.net.URLEncoder.encode(text, "UTF-8")
        return try {
            val body = Http.get(url).use { it.body?.string() } ?: return ""
            JSONArray(body).optJSONArray(0)?.optJSONArray(0)?.optString(0).orEmpty()
        } catch (e: Exception) {
            ""
        }
    }

    private fun maybePersist() {
        val now = System.currentTimeMillis()
        if (now - lastPersist < 10_000) return
        lastPersist = now
        val store = storeRef ?: return
        val snapshot = synchronized(lock) { cache.entries.toList().takeLast(1500).map { it.key to it.value } }
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { store.setTranslateCache(snapshot) }
        }
    }
}
