package com.posedetection.detector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import com.posedetection.ErrorCode
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.URL
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/** Always a software ARGB_8888 bitmap: MediaPipe reads the pixels and takes no other format. */
internal object StillImage {
    /** The model sees a 256 px crop: at 1920 anyone over a seventh of the frame is still sampled down. */
    const val DETECTION_MAX_PIXELS = 1920

    /** Upright, long side at most [maxPixels] (null: full size), never upscaled; null if unreadable. */
    fun decode(
        context: Context,
        uri: String,
        maxPixels: Int?,
    ): Bitmap? {
        val decoded =
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    decodeWithImageDecoder(context, uri, maxPixels)
                } else {
                    decodeWithBitmapFactory(context, uri, maxPixels)
                }
            }.getOrNull() ?: return null
        if (decoded.config == Bitmap.Config.ARGB_8888) return decoded
        // A wide-gamut or HDR photo can decode to half floats, which MediaPipe does not take.
        val converted = decoded.copy(Bitmap.Config.ARGB_8888, false)
        decoded.recycle()
        return converted
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeWithImageDecoder(
        context: Context,
        uri: String,
        maxPixels: Int?,
    ): Bitmap {
        val parsed = Uri.parse(uri)
        val source =
            when (parsed.scheme) {
                null -> ImageDecoder.createSource(File(uri))
                in REMOTE -> ImageDecoder.createSource(ByteBuffer.wrap(download(uri)))
                else -> ImageDecoder.createSource(context.contentResolver, parsed)
            }
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            val longSide = max(info.size.width, info.size.height)
            if (maxPixels != null && longSide > maxPixels) {
                val factor = maxPixels.toFloat() / longSide
                decoder.setTargetSize(
                    max(1, (info.size.width * factor).roundToInt()),
                    max(1, (info.size.height * factor).roundToInt()),
                )
            }
        }
    }

    private fun decodeWithBitmapFactory(
        context: Context,
        uri: String,
        maxPixels: Int?,
    ): Bitmap? {
        // Read three times (bounds, pixels, EXIF), so a download is fetched once and read from memory.
        val downloaded = if (Uri.parse(uri).scheme in REMOTE) download(uri) else null
        val open = { downloaded?.let { ByteArrayInputStream(it) } ?: open(context, uri) }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options =
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = sampleSize(max(bounds.outWidth, bounds.outHeight), maxPixels)
            }
        val sampled = open().use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        val orientation =
            runCatching {
                open().use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        return transform(sampled, orientation, maxPixels)
    }

    fun sampleSize(
        longSide: Int,
        maxPixels: Int?,
    ): Int {
        if (maxPixels == null || maxPixels <= 0) return 1
        var sample = 1
        while (longSide / (sample * 2) >= maxPixels) sample *= 2
        return sample
    }

    /** BitmapFactory ignores EXIF orientation, so it is applied here with the last downscale step. */
    private fun transform(
        bitmap: Bitmap,
        orientation: Int,
        maxPixels: Int?,
    ): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
                matrix.setScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_180 -> {
                matrix.setRotate(HALF_TURN)
            }

            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setScale(1f, -1f)
            }

            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(QUARTER_TURN)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_90 -> {
                matrix.setRotate(QUARTER_TURN)
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-QUARTER_TURN)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_270 -> {
                matrix.setRotate(-QUARTER_TURN)
            }
        }
        val longSide = max(bitmap.width, bitmap.height)
        if (maxPixels != null && longSide > maxPixels) {
            val factor = maxPixels.toFloat() / longSide
            matrix.postScale(factor, factor)
        }
        if (matrix.isIdentity) return bitmap
        val result = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (result !== bitmap) bitmap.recycle()
        return result
    }

    private fun open(
        context: Context,
        uri: String,
    ): InputStream {
        val parsed = Uri.parse(uri)
        if (parsed.scheme == null) return File(uri).inputStream()
        return context.contentResolver.openInputStream(parsed)
            ?: throw StaticDetectionError(ErrorCode.IMAGE_DECODE_FAILED, "could not open $uri")
    }

    private fun download(uri: String): ByteArray = URL(uri).openStream().use { it.readBytes() }

    private val REMOTE = setOf("http", "https")
    private const val QUARTER_TURN = 90f
    private const val HALF_TURN = 180f
}
