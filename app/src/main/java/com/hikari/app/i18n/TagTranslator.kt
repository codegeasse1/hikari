package com.hikari.app.i18n

import com.hikari.app.data.Translator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

object TagTranslator {

    /** 0 until something is cached, then steps once per completed batch. Compose
     *  labels read it so a late translation repaints just their chip. */
    val version = MutableStateFlow(0L)

    private const val MAX_ENTRIES = 1400
    private const val MAX_CHARS = 400

    private val ASCII_LETTER = Regex("[A-Za-z]")

    private val cache = ConcurrentHashMap<String, String>()
    private val order = ArrayDeque<String>()
    private val inFlight = HashSet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun key(lang: String, tag: String) = lang.trim().lowercase() + "|" + tag

    /** The translation for [tag], or null while there is none yet. Falls back to
     *  the persisted copy (which is what makes the View-based player — built
     *  once, synchronously, from `I18n.t`) come up translated on the next
     *  launch, and adopts it into memory when it does. */
    fun cached(lang: String, tag: String): String? {
        cache[key(lang, tag)]?.let { return it }
        val stored = Translator.uiCached(lang, tag) ?: return null
        remember(lang, tag, stored)
        return stored
    }

    private fun remember(lang: String, tag: String, translated: String) {
        val k = key(lang, tag)
        cache[k] = translated
        val dropped = synchronized(order) {
            order.addLast(k)
            if (order.size > MAX_ENTRIES) order.removeFirst() else null
        }
        dropped?.let { cache.remove(it) }
    }

    /** Asks for [tag] once. Safe to call from every composition: an entry that
     *  is already cached (or on its way) is a no-op. */
    fun request(lang: String, tag: String) {
        val t = tag.trim()
        if (t.isBlank() || t.length < 2 || t.length > MAX_CHARS) return
        if (!ASCII_LETTER.containsMatchIn(t)) return
        val code = gtxCode(lang)
        if (code.isBlank() || code.startsWith("en")) return
        val k = key(lang, t)
        synchronized(inFlight) {
            if (inFlight.size > 24 || !inFlight.add(k)) return
        }
        scope.launch {
            val out = runCatching { Translator.translateUi(t, code) }.getOrDefault(t)
            synchronized(inFlight) { inFlight.remove(k) }
            if (out.isBlank() || out == t) return@launch
            // An unbounded cache would grow with every title the user opens;
            // tags are a long tail, so it is capped and the oldest drop out.
            remember(lang, t, out)
            // …and written to the persisted cache, so the same work is not done
            // again on the next launch.
            runCatching { Translator.rememberUi(lang, t, out) }
            version.value = version.value + 1L
        }
    }

    /** The code the translation endpoint knows for an app language tag. */
    private fun gtxCode(lang: String): String = when (val l = lang.trim()) {
        "" -> ""
        "pt-BR" -> "pt"
        "zh-CN" -> "zh-CN"
        "zh-TW" -> "zh-TW"
        else -> l
    }
}
