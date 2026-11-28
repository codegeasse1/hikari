package com.hikari.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import javax.imageio.ImageIO
import javax.imageio.ImageReader

private val HOST_LIKE_ARTWORK_URL = Regex("^[A-Za-z0-9][A-Za-z0-9.-]*\\.[A-Za-z]{2,}(/.*)?$")

internal fun upgradeArtworkUrl(rawUrl: String): String {
    var url = rawUrl
    // Cinemeta/metahub thumbnails: prefer medium over the tiny default.
    if (url.contains("images.metahub.space/poster/small")) {
        url = url.replace("images.metahub.space/poster/small", "images.metahub.space/poster/medium")
    }
    if (url.contains("images.metahub.space/logo/small")) {
        url = url.replace("images.metahub.space/logo/small", "images.metahub.space/logo/medium")
    }
    return url
}

internal fun posterArtworkUrl(posterUrl: String?, backgroundUrl: String?): String? {
    val poster = posterUrl?.trim()?.takeIf(String::isNotEmpty)?.let(::upgradeArtworkUrl)
    if (poster != null) return poster
    return backgroundUrl?.trim()?.takeIf(String::isNotEmpty)
}

/** Hero/detail backdrops prefer the landscape background, then fall back to the poster. */
internal fun backdropArtworkUrl(backgroundUrl: String?, posterUrl: String?): String? =
    backgroundUrl?.trim()?.takeIf(String::isNotEmpty)
        ?: posterUrl?.trim()?.takeIf(String::isNotEmpty)?.let(::upgradeArtworkUrl)

internal fun safeArtworkUri(rawUrl: String?): URI? {
    val value = rawUrl?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val normalizedValue = when {
        value.startsWith("//") -> "https:$value"
        HOST_LIKE_ARTWORK_URL.matches(value) -> "https://$value"
        else -> value
    }
    val uri = runCatching { URI(normalizedValue) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.')
    if (scheme !in setOf("http", "https") || host.isNullOrBlank() || uri.rawUserInfo != null) return null
    if (uri.port == 0 || uri.port > 65535) return null
    if (isLocalArtworkHost(host)) return null
    return uri
}

private fun isLocalArtworkHost(host: String): Boolean {
    if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
        host.endsWith(".lan") || host.endsWith(".internal")
    ) return true
    if (':' in host) return true
    val parts = host.split('.')
    if (parts.size == 4 && parts.all { it.toIntOrNull() != null }) return true
    return false
}

@Composable
internal fun ArtworkMonogram(title: String, modifier: Modifier = Modifier) {
    val initial = title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "•"
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(12.dp),
        ) {
            Text(
                initial,
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun ArtworkPoster(url: String?, title: String, modifier: Modifier = Modifier) {
    val image by produceState<ImageBitmap?>(initialValue = null, key1 = url) {
        value = ArtworkImageLoader.load(url)
    }
    if (image != null) {
        Image(
            bitmap = image!!,
            contentDescription = "Poster: $title",
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        ArtworkMonogram(title = title, modifier = modifier)
    }
}

@Composable
internal fun ArtworkBackdrop(url: String?, title: String, modifier: Modifier = Modifier) {
    val image by produceState<ImageBitmap?>(initialValue = null, key1 = url) {
        value = ArtworkImageLoader.load(url)
    }
    if (image != null) {
        Image(
            bitmap = image!!,
            contentDescription = "Backdrop: $title",
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        ArtworkMonogram(title = title, modifier = modifier)
    }
}

private object ArtworkImageLoader {
    private const val MAX_RESPONSE_BYTES = 6 * 1024 * 1024
    private const val MAX_IMAGE_DIMENSION = 4096
    private const val MAX_IMAGE_PIXELS = 16_000_000L
    private const val MAX_REDIRECTS = 3

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(4))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    suspend fun load(rawUrl: String?): ImageBitmap? = withContext(Dispatchers.IO) {
        val uri = safeArtworkUri(rawUrl) ?: return@withContext null
        val image = runCatching { fetch(uri, redirects = 0) }.getOrNull() ?: return@withContext null
        runCatching { image.toComposeImageBitmap() }.getOrNull()
    }

    private fun fetch(uri: URI, redirects: Int): BufferedImage? {
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(8))
            .header("Accept", "image/png, image/jpeg, image/gif, image/webp, image/*;q=0.8")
            .header("Referer", "https://v3-cinemeta.strem.io/")
            .header("User-Agent", "Hikari Desktop/0.12.166")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        val body = response.body()
        try {
            if (response.statusCode() in 300..399) {
                if (redirects >= MAX_REDIRECTS) return null
                val location = response.headers().firstValue("Location").orElse(null) ?: return null
                val redirected = runCatching { uri.resolve(location) }.getOrNull() ?: return null
                val safeRedirect = safeArtworkUri(redirected.toString()) ?: return null
                return fetch(safeRedirect, redirects + 1)
            }
            if (response.statusCode() !in 200..299) return null
            val contentType = response.headers().firstValue("Content-Type")
                .orElse("")
                .substringBefore(';')
                .trim()
                .lowercase(Locale.ROOT)
            if (!contentType.startsWith("image/") || contentType == "image/svg+xml") return null
            val declaredLength = response.headers().firstValueAsLong("Content-Length")
            if (declaredLength.isPresent && declaredLength.asLong > MAX_RESPONSE_BYTES) return null
            val bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1)
            if (bytes.size > MAX_RESPONSE_BYTES) return null
            return decode(bytes)
        } finally {
            body.close()
        }
    }

    private fun decode(bytes: ByteArray): BufferedImage? {
        val input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) ?: return null
        input.use { imageInput ->
            val readers = ImageIO.getImageReaders(imageInput)
            if (!readers.hasNext()) return null
            val reader: ImageReader = readers.next()
            try {
                reader.input = imageInput
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (width <= 0 || height <= 0 || width > MAX_IMAGE_DIMENSION || height > MAX_IMAGE_DIMENSION) {
                    return null
                }
                if (width.toLong() * height.toLong() > MAX_IMAGE_PIXELS) return null
                return reader.read(0)
            } finally {
                reader.dispose()
            }
        }
    }
}
