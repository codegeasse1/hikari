package com.hikari.app.reader.coil

import coil.request.ImageRequest
import coil.request.Options

/** Parameter key used to route a request through [TachiyomiReaderDecoder]'s border-crop path. */
internal const val KEY_CROP_BORDERS = "nekoread_crop_borders"

/**
 * Coil 2 request extension mirroring chimahon's Coil 3 builder helper. When [enabled], the request
 * is decoded by [TachiyomiReaderDecoder] (which crops the page's blank borders). The parameter
 * flows to [Options.parameters] so the decoder can see the flag — and, because Coil 2 parameters
 * participate in the request's memory cache key, cropped and uncropped decodes of the same page
 * never collide in the memory cache. (The disk cache stores the raw fetched bytes, not the decoded
 * bitmap, so it needs no differentiation here.)
 */
fun ImageRequest.Builder.cropBorders(enabled: Boolean): ImageRequest.Builder = apply {
    setParameter(KEY_CROP_BORDERS, enabled)
    if (enabled) {
        decoderFactory(TachiyomiReaderDecoder.Factory())
    }
}

/** Whether this request asked for border cropping (read from [Options.parameters]). */
val Options.cropBorders: Boolean
    get() = parameters.value<Boolean>(KEY_CROP_BORDERS) ?: false
