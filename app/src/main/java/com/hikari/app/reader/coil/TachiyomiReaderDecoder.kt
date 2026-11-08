package com.hikari.app.reader.coil

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.drawable.BitmapDrawable
import coil.ImageLoader
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.decode.ImageSource
import coil.fetch.SourceResult
import coil.request.Options
import coil.size.Dimension
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonBorderDetector
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile

class TachiyomiReaderDecoder(
    private val source: ImageSource,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult {
        val bitmap = (if (options.cropBorders) decodeCropped() else decodeWhole())
            ?: throw IllegalStateException("Decode returned a null bitmap")
        return DecodeResult(
            drawable = BitmapDrawable(options.context.resources, bitmap),
            isSampled = true,
        )
    }

    private fun decodeWhole(): Bitmap? {
        val file = source.fileOrNull()?.toFile()
        return if (file != null) {
            decodeFileWhole(file)
        } else {
            val bytes = readBytes()
            decodeBytesWhole(bytes)
        }
    }

    private fun decodeFileWhole(file: File): Bitmap? {
        val (w, h) = readDims(file)
        val sampleSize = calculateInSampleSize(w, h, targetWidthPx(), targetHeightPx())
        if (options.config == Bitmap.Config.HARDWARE && !options.allowRgb565) {
            val hardware = runCatching {
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.HARDWARE
                    },
                )
            }.getOrNull()
            if (hardware != null) return hardware
        }
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                if (options.allowRgb565) inPreferredConfig = Bitmap.Config.RGB_565
            },
        )
    }

    private fun decodeBytesWhole(bytes: ByteArray): Bitmap {
        val (w, h) = readDims(bytes)
        val sampleSize = calculateInSampleSize(w, h, targetWidthPx(), targetHeightPx())
        return BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                if (options.allowRgb565) inPreferredConfig = Bitmap.Config.RGB_565
            },
        )
    }

    private fun decodeCropped(): Bitmap? {
        val file = source.fileOrNull()?.toFile()
        return if (file != null) decodeCroppedFile(file) else decodeCroppedBytes(readBytes())
    }

    private fun decodeCroppedFile(file: File): Bitmap? {
        // detectContentBounds closes the stream; the empty rect means "no crop detected" (or error)
        // and we fall back to a plain decode.
        val bounds = WebtoonBorderDetector.detectContentBounds(FileInputStream(file))
        if (bounds.isEmpty) return decodeFileWhole(file)
        val (w, h) = readDims(file)
        val sampleSize = calculateInSampleSize(w, h, targetWidthPx(), targetHeightPx())
        return try {
            val regionDecoder = BitmapRegionDecoder.newInstance(file.absolutePath, true)
                ?: return decodeFileWhole(file)
            val bitmap = regionDecoder.decodeRegion(
                bounds,
                BitmapFactory.Options().apply { inSampleSize = sampleSize },
            )
            regionDecoder.recycle()
            // decodeRegion can return null for some streams; never feed a null bitmap to Coil
            // (it would render as a silent black page) — fall back to the whole decode.
            bitmap ?: decodeFileWhole(file)
        } catch (e: Exception) {
            // Region decoding can fail on exotic formats (e.g. AVIF); fall back to a whole decode.
            decodeFileWhole(file)
        }
    }

    private fun decodeCroppedBytes(bytes: ByteArray): Bitmap {
        val bounds = WebtoonBorderDetector.detectContentBounds(ByteArrayInputStream(bytes))
        if (bounds.isEmpty) return decodeBytesWhole(bytes)
        val (w, h) = readDims(bytes)
        val sampleSize = calculateInSampleSize(w, h, targetWidthPx(), targetHeightPx())
        return try {
            val regionDecoder = BitmapRegionDecoder.newInstance(ByteArrayInputStream(bytes), true)
                ?: return decodeBytesWhole(bytes)
            val bitmap = regionDecoder.decodeRegion(
                bounds,
                BitmapFactory.Options().apply { inSampleSize = sampleSize },
            )
            regionDecoder.recycle()
            bitmap ?: decodeBytesWhole(bytes)
        } catch (e: Exception) {
            decodeBytesWhole(bytes)
        }
    }

    private fun readBytes(): ByteArray =
        source.source().use { it.readByteArray() }

    private fun readDims(file: File): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        return opts.outWidth to opts.outHeight
    }

    private fun readDims(bytes: ByteArray): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        return opts.outWidth to opts.outHeight
    }

    /** The request's target width in pixels, or null if the request didn't fix a width. */
    private fun targetWidthPx(): Int? = (options.size.width as? Dimension.Pixels)?.px

    /** The request's target height in pixels, or null if the request didn't fix a height. */
    private fun targetHeightPx(): Int? = (options.size.height as? Dimension.Pixels)?.px

    private fun calculateInSampleSize(srcW: Int, srcH: Int, dstW: Int?, dstH: Int?): Int {
        if (dstW == null && dstH == null) return 1
        val maxW = dstW ?: Int.MAX_VALUE
        val maxH = dstH ?: Int.MAX_VALUE
        var sample = 1
        while (srcW / sample > maxW || srcH / sample > maxH) {
            sample *= 2
        }
        return sample
    }

    /**
     * Applies this decoder for opt-in crop requests (set as the request's decoderFactory), or for
     * AVIF/JXL/HEIF container formats (registered in the ImageLoader's component registry).
     */
    class Factory : Decoder.Factory {
        override fun create(result: SourceResult, options: Options, imageLoader: ImageLoader): Decoder? {
            val source = result.source
            return if (options.cropBorders || isApplicable(source)) {
                TachiyomiReaderDecoder(source, options)
            } else {
                null
            }
        }

        private fun isApplicable(source: ImageSource): Boolean {
            val file = source.fileOrNull()?.toFile() ?: return false
            return try {
                RandomAccessFile(file, "r").use { raf ->
                    val bytes = ByteArray(12)
                    val n = raf.read(bytes)
                    if (n < 8) return false
                    val four = String(bytes, 4, 4, Charsets.US_ASCII)
                    val isAVIF = four == "avif"
                    val isJXL = (n >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0x0A.toByte()) ||
                        four == "jxl "
                    val isHEIF = four in setOf("heic", "heix", "hevc", "heim", "mif1")
                    isAVIF || isJXL || isHEIF
                }
            } catch (e: Exception) {
                false
            }
        }
    }
}
