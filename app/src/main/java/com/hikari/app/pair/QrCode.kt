package com.hikari.app.pair

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * QR codes, both directions, with zxing-core (a small pure-Java library and the
 * only new dependency pairing needs — see the note in app/build.gradle.kts).
 *
 * The code is written DARK-ON-LIGHT and rendered with a white margin, always,
 * whatever the app's theme is: a QR is read by a camera that knows nothing about
 * dark mode, and an inverted or dark-on-dark code simply does not scan. The
 * screen that shows one puts it on a white card for the same reason.
 */
object QrCode {

    /** Pixels per side of the bitmap [encode] returns. A pairing payload is ~90
     *  characters, which is a 33x33 module grid at error-correction M — ~15 px
     *  per module at this size, i.e. a code that scans from a metre away. */
    const val SIZE_PX = 512

    /**
     * Encodes [text] as a QR bitmap. `MARGIN 1` (the quiet zone in modules) plus
     * the white card the caller draws around it is what makes the code findable;
     * a QR rendered edge-to-edge on a dark panel reads as a texture, not a code.
     */
    fun encode(text: String, sizePx: Int = SIZE_PX): Bitmap? = runCatching {
        val hints = HashMap<EncodeHintType, Any>().apply {
            put(EncodeHintType.MARGIN, 1)
            put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
            put(EncodeHintType.CHARACTER_SET, "UTF-8")
        }
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(sizePx * sizePx)
        val dark = Color.BLACK
        val light = Color.WHITE
        for (y in 0 until sizePx) {
            val row = y * sizePx
            for (x in 0 until sizePx) {
                pixels[row + x] = if (matrix.get(x, y)) dark else light
            }
        }
        Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
    }.getOrNull()

    /** Reads a QR out of a still image (a saved screenshot, a photo). Only used
     *  as a fallback path; the live scanner decodes frames with [decodePlanes]. */
    fun decode(bitmap: Bitmap): String? = runCatching {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        val reader = MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
        }
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    }.getOrNull()

    /**
     * Reads a QR out of one camera frame's luminance plane.
     *
     * The Y plane of a YUV_420_888 frame is already a greyscale image, which is
     * exactly what zxing wants — decoding it directly is the difference between
     * a scanner that keeps up with the preview and one that stutters, because
     * converting the frame to RGB first would be a full-frame colour pass per
     * frame for information the reader throws away.
     *
     * [rowStride] is the plane's own row length, which on most devices is WIDER
     * than [width] (the hardware pads each row for alignment) — passing the
     * visible width there shears the image and nothing ever decodes.
     */
    fun decodePlanes(data: ByteArray, width: Int, height: Int, rowStride: Int): String? {
        if (width <= 0 || height <= 0 || rowStride < width) return null
        return runCatching {
            val source = PlanarYUVLuminanceSource(
                data, rowStride, height, 0, 0, width, height, false
            )
            val reader = MultiFormatReader().apply {
                setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
            }
            val text = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
            reader.reset()
            text
        }.getOrNull()
    }
}
