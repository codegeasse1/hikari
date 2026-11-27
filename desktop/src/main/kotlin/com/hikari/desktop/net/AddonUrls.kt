package com.hikari.desktop.net

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

object AddonUrls {
    fun manifestUrl(addonUrl: String): String {
        val uri = parseAddonUri(normalizeStremioLink(addonUrl))
        val currentPath = uri.rawPath.orEmpty()
        val manifestPath = if (currentPath.endsWith("/manifest.json", ignoreCase = true)) {
            currentPath
        } else {
            "${currentPath.trimEnd('/')}/manifest.json".ifBlank { "/manifest.json" }
        }
        return buildString {
            append(origin(uri))
            append(manifestPath)
            uri.rawQuery?.let { append('?').append(it) }
        }
    }

    fun catalogUrl(
        manifestUrl: String,
        type: String,
        catalogId: String,
        search: String? = null,
        skip: Int = 0,
    ): String {
        require(skip >= 0) { "skip must not be negative" }
        val extras = buildList {
            search?.takeIf(String::isNotBlank)?.let { add("search=${encodeValue(it)}") }
            if (skip > 0) add("skip=$skip")
        }.joinToString("&").ifBlank { null }
        return resourceUrl(manifestUrl, "catalog", type, catalogId, extras)
    }

    fun metaUrl(manifestUrl: String, type: String, id: String): String =
        resourceUrl(manifestUrl, "meta", type, id)

    fun streamUrl(manifestUrl: String, type: String, id: String): String =
        resourceUrl(manifestUrl, "stream", type, id)

    private fun resourceUrl(
        manifestUrl: String,
        resource: String,
        type: String,
        id: String,
        extra: String? = null,
    ): String {
        require(type.isNotBlank()) { "type must not be blank" }
        require(id.isNotBlank()) { "id must not be blank" }
        val manifest = parseAddonUri(manifestUrl(manifestUrl))
        val basePath = manifest.rawPath.orEmpty().removeSuffix("/manifest.json")
        return buildString {
            append(origin(manifest))
            append(basePath)
            append('/').append(resource)
            append('/').append(encodePathSegment(type))
            append('/').append(encodePathSegment(id))
            extra?.takeIf(String::isNotBlank)?.let {
                append('/').append(it)
            }
            append(".json")
            manifest.rawQuery?.let { append('?').append(it) }
        }
    }

    private fun parseAddonUri(input: String): URI {
        val trimmed = input.trim()
        require(trimmed.isNotEmpty()) { "Add-on URL must not be blank" }
        val candidate = when {
            trimmed.startsWith("//") -> "https:$trimmed"
            SCHEME_WITH_AUTHORITY.containsMatchIn(trimmed) -> trimmed
            else -> "https://$trimmed"
        }
        val uri = try {
            URI(candidate)
        } catch (error: Exception) {
            throw IllegalArgumentException("Add-on URL is invalid", error)
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme == "http" || scheme == "https") {
            "Add-on URL must use HTTP or HTTPS"
        }
        require(uri.host != null && uri.rawAuthority != null) {
            "Add-on URL must include a host"
        }
        return uri
    }

    private fun normalizeStremioLink(input: String): String {
        val trimmed = input.trim()
        if (!trimmed.startsWith(STREMIO_SCHEME, ignoreCase = true)) return trimmed

        var rest = trimmed.substring(STREMIO_SCHEME.length)
        rest = runCatching { URLDecoder.decode(rest, StandardCharsets.UTF_8) }.getOrDefault(rest)
        if (!rest.startsWith("http://", ignoreCase = true) && !rest.startsWith("https://", ignoreCase = true)) {
            require(!SCHEME_WITH_AUTHORITY.containsMatchIn(rest)) {
                "Stremio link must contain an HTTP or HTTPS add-on URL"
            }
            rest = "https://$rest"
        }
        rest = rest.substringBefore('?')
        if (!rest.trimEnd('/').endsWith("/manifest.json", ignoreCase = true)) {
            rest = rest.trimEnd('/') + "/manifest.json"
        }
        return rest
    }

    private fun origin(uri: URI): String =
        "${uri.scheme.lowercase(Locale.ROOT)}://${uri.rawAuthority}"

    private fun encodeValue(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private fun encodePathSegment(value: String): String =
        encodeValue(value).replace("%3A", ":")

    private const val STREMIO_SCHEME = "stremio://"
    private val SCHEME_WITH_AUTHORITY = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
}
