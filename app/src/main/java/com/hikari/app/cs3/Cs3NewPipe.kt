package com.hikari.app.cs3

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

/**
 * Host-side NewPipe downloader — CloudStream parity.
 *
 * CloudStream calls NewPipe.init(...) in CommonActivity.onCreate, so EVERY
 * plugin that drives NewPipe extractors (the YouTube plugin builds its
 * trending/music/gaming/podcasts sections on kiosk + search extractors)
 * has a working downloader from the first frame. Hikari only initialized
 * NewPipe lazily for music, so those plugins ran against a null downloader:
 * the extractor threw, the plugin swallowed it and returned an empty page,
 * and the catalogue read "Page fetched but no items parsed" — while the
 * same extension in CloudStream listed fine.
 *
 * The downloader itself is a plain OkHttp client with a desktop Chrome UA,
 * multi-value headers preserved and 429 mapped to ReCaptchaException —
 * the same shape as CloudStream's DownloaderTestImpl.
 */
object Cs3NewPipe {
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"

    private class PlainDownloader : Downloader() {
        private val client: OkHttpClient =
            OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()

        override fun execute(request: Request): Response {
            val data = request.dataToSend()
            val body = data?.toRequestBody(null, 0, data.size)
            val rb = okhttp3.Request.Builder()
                .method(request.httpMethod(), body)
                .url(request.url())
                .addHeader("User-Agent", UA)
            for ((name, values) in request.headers()) {
                if (values.size > 1) {
                    rb.removeHeader(name)
                    for (v in values) rb.addHeader(name, v)
                } else if (values.size == 1) {
                    rb.header(name, values[0])
                }
            }
            val resp = client.newCall(rb.build()).execute()
            if (resp.code == 429) {
                resp.close()
                throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            }
            val text = resp.body.string()
            return Response(
                resp.code, resp.message, resp.headers.toMultimap(),
                text, resp.request.url.toString(),
            )
        }
    }

    @Volatile
    private var inited = false

    /** A process-wide NewPipe downloader before any plugin runs. Safe to call twice. */
    fun initIfNeeded() {
        if (inited) return
        synchronized(this) {
            if (inited) return
            runCatching {
                if (NewPipe.getDownloader() == null) NewPipe.init(PlainDownloader())
                inited = true
            }
        }
    }
}
