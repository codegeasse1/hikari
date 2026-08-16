package com.lagradost.cloudstream3.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object M3u8Helper {

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val BANDWIDTH_REGEX = Regex("""BANDWIDTH=(\d+)""")

    /**
     * Fetch an HLS playlist and produce playable links: one per variant for master
     * playlists, a single link otherwise.
     */
    suspend fun generateM3u8(
        name: String,
        m3u8Url: String,
        referer: String? = null,
        videoSize: Int? = null,
        headers: Map<String, String> = emptyMap(),
        nameSuffix: String? = null,
    ): List<ExtractorLink> = withContext(Dispatchers.IO) {
        try {
            val builder = Request.Builder().url(m3u8Url)
            headers.forEach { (k, v) -> builder.header(k, v) }
            if (referer != null) builder.header("Referer", referer)
            val resp = client.newCall(builder.build()).execute()
            if (!resp.isSuccessful) return@withContext emptyList()
            val text = resp.body?.string().orEmpty()
            val base = m3u8Url.substringBeforeLast('/')
            val links = mutableListOf<ExtractorLink>()

            if (text.contains("#EXT-X-STREAM-INF")) {
                val lines = text.lines()
                var i = 0
                while (i < lines.size) {
                    val line = lines[i].trim()
                    if (line.startsWith("#EXT-X-STREAM-INF")) {
                        val bandwidth = BANDWIDTH_REGEX.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                        i++
                        val variant = lines.getOrNull(i)?.trim().orEmpty()
                        if (variant.isNotBlank() && !variant.startsWith("#")) {
                            val full = if (variant.startsWith("http")) variant else "$base/$variant"
                            val qualityKbps = (bandwidth / 1000).takeIf { it > 0 }
                            links += ExtractorLink(
                                source = name,
                                name = if (qualityKbps != null) "$name ${qualityKbps}k" else name,
                                url = full,
                                referer = referer,
                                quality = qualityKbps ?: Qualities.Unknown.value,
                                type = ExtractorLinkType.M3U8,
                            )
                        }
                    }
                    i++
                }
            } else if (text.contains("#EXTM3U")) {
                links += ExtractorLink(
                    source = name,
                    name = name,
                    url = m3u8Url,
                    referer = referer,
                    quality = videoSize ?: Qualities.Unknown.value,
                    type = ExtractorLinkType.M3U8,
                )
            }
            links.distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
