package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.SubtitleFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private val M3U8_LINK_REGEX =
    Regex("""https?://[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)

private val IFRAME_REGEX = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

private val fetchClient = OkHttpClient.Builder()
    .followRedirects(true)
    .followSslRedirects(true)
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

suspend fun newExtractorLink(
    source: String,
    name: String,
    url: String,
    type: ExtractorLinkType = ExtractorLinkType.M3U8,
    onLoad: suspend (ExtractorLink) -> Unit = {},
): ExtractorLink {
    val link = ExtractorLink(source = source, name = name, url = url, type = type)
    onLoad(link)
    return link
}

suspend fun loadExtractor(
    url: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean = loadExtractor(url, null, subtitleCallback, callback)

/**
 * Best-effort extractor resolution: emits HLS links found either directly in the URL,
 * or scraped from the page (following one iframe hop). Full CloudStream extractor APIs
 * aren't bundled, so embedded-host resolvers fall back to this.
 */
suspend fun loadExtractor(
    url: String,
    referer: String? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean = withContext(Dispatchers.IO) {
    var found = false
    try {
        if (url.contains(".m3u8", true)) {
            callback(
                ExtractorLink(
                    source = "Hikari",
                    name = "HLS",
                    url = url,
                    referer = referer,
                    type = ExtractorLinkType.M3U8,
                )
            )
            found = true
        } else {
            val page = fetchText(url, referer)
            if (page != null) {
                val direct = M3U8_LINK_REGEX.find(page)?.value
                if (direct != null) {
                    callback(
                        ExtractorLink(
                            source = "Hikari",
                            name = "HLS",
                            url = direct,
                            referer = referer,
                            type = ExtractorLinkType.M3U8,
                        )
                    )
                    found = true
                } else {
                    val iframe = IFRAME_REGEX.find(page)?.groupValues?.get(1)
                    if (iframe != null) {
                        val abs = resolveUrl(url, iframe)
                        val page2 = fetchText(abs, referer)
                        if (page2 != null) {
                            M3U8_LINK_REGEX.findAll(page2).forEach { m ->
                                callback(
                                    ExtractorLink(
                                        source = "Hikari",
                                        name = "HLS",
                                        url = m.value,
                                        referer = abs,
                                        type = ExtractorLinkType.M3U8,
                                    )
                                )
                                found = true
                            }
                        }
                    }
                }
            }
        }
    } catch (e: Exception) {
    }
    found
}

/** Decode `eval(function(p,a,c,k,e,r){...}('...',...))` packed scripts (classic JS packer). */
fun getAndUnpack(data: String): String {
    val regex = Regex(
        """eval\(function\(p,a,c,k,e,(?:r|d)\)\{(.*)\}\s*\(\s*'(.*)'\s*,(\d+),(\d+),\s*'(.*)'\s*\.split\('\|\'\),0,\{\}\)"""
    )
    val m = regex.find(data) ?: return data
    val payload = m.groupValues[2]
    val a = m.groupValues[3].toIntOrNull() ?: return data
    val c = m.groupValues[4].toIntOrNull() ?: return data
    val k = m.groupValues[5].split("|")

    fun e(cx: Int): String =
        if (cx < a) cx.toString(36)
        else e(cx / a) + (if (cx % a > 35) (cx % a + 29).toChar().toString() else (cx % a).toString(36))

    val replacements = HashMap<String, String>()
    for (i in 0 until c) {
        val token = e(i)
        replacements[token] = k.getOrNull(i) ?: token
    }
    var out = payload
    for ((token, replacement) in replacements) {
        if (token.length >= 2 && token != replacement) {
            out = out.replace(Regex("""\b${Regex.escape(token)}\b"""), replacement)
        }
    }
    return out
}

private suspend fun fetchText(url: String, referer: String?): String? = withContext(Dispatchers.IO) {
    try {
        val builder = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        if (referer != null) builder.header("Referer", referer)
        val resp = fetchClient.newCall(builder.build()).execute()
        if (resp.isSuccessful) resp.body?.string() else null
    } catch (e: Exception) {
        null
    }
}

private fun resolveUrl(base: String, rel: String): String {
    if (rel.startsWith("http://") || rel.startsWith("https://")) return rel
    return base.substringBeforeLast('/') + "/" + rel.trimStart('/')
}
