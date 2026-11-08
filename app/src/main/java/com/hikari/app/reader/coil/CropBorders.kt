package com.hikari.app.reader.coil

import coil.request.ImageRequest
import coil.request.Options

/** Parameter key used to route a request through [TachiyomiReaderDecoder]'s border-crop path. */
internal const val KEY_CROP_BORDERS = "nekoread_crop_borders"

fun ImageRequest.Builder.cropBorders(enabled: Boolean): ImageRequest.Builder = apply {
    setParameter(KEY_CROP_BORDERS, enabled)
    if (enabled) {
        decoderFactory(TachiyomiReaderDecoder.Factory())
    }
}

/** Whether this request asked for border cropping (read from [Options.parameters]). */
val Options.cropBorders: Boolean
    get() = parameters.value<Boolean>(KEY_CROP_BORDERS) ?: false
