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

/**
 * A photo decoded upright and no larger than it needs to be.
 *
 * `BitmapFactory.decodeStream` decodes the whole file at full size and ignores the EXIF
 * orientation: a 12-megapixel photo is a 48 MB bitmap, and a portrait one stored sideways reaches
 * MediaPipe sideways. From Android 9, `ImageDecoder` decodes straight to the size asked for with
 * the orientation applied. Before it, `BitmapFactory` samples the decode down by a power of two and
 * the orientation is applied afterwards, from the file's EXIF.
 *
 * Always a software ARGB_8888 bitmap: MediaPipe reads the pixels, which a hardware bitmap does not
 * allow, and takes no other format.
 */
internal object StillImage {
    /**
     * The long side a photo is decoded to for detection. The detector sees 224 pixels of the whole
     * frame and the landmark model a 256-pixel crop around the body, so at 1920 that crop is sampled
     * down rather than stretched for anybody taller than about a seventh of the picture. A larger
     * decode costs memory and finds nobody new.
     */
    const val DETECTION_MAX_PIXELS = 1920

    /**
     * Upright, with the long side at most [maxPixels], or at full size when that is null. Never
     * upscaled. Null when the source cannot be read or is not an image.
     */
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
            // Both sides scaled by one factor, so the aspect holds whichever way the size is reported.
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
        // Read three times: bounds, pixels, EXIF. A download is fetched once and read from memory.
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

    /** The largest power of two that still leaves the long side at or above [maxPixels]. */
    fun sampleSize(
        longSide: Int,
        maxPixels: Int?,
    ): Int {
        if (maxPixels == null || maxPixels <= 0) return 1
        var sample = 1
        while (longSide / (sample * 2) >= maxPixels) sample *= 2
        return sample
    }

    /** The EXIF orientation applied and the last step of the downscale, in one pass. */
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

    /** A picture fetched whole, as iOS does: the photo is decoded from memory, never streamed twice. */
    private fun download(uri: String): ByteArray = URL(uri).openStream().use { it.readBytes() }

    private val REMOTE = setOf("http", "https")
    private const val QUARTER_TURN = 90f
    private const val HALF_TURN = 180f
}
