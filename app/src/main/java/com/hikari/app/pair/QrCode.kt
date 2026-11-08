package com.hikari.app.pair

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrCode {

    /** Pixels per side of the bitmap [encode] returns. A pairing payload is ~90
     *  characters, which is a 33x33 module grid at error-correction M — ~15 px
     *  per module at this size, i.e. a code that scans from a metre away. */
    const val SIZE_PX = 512

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
