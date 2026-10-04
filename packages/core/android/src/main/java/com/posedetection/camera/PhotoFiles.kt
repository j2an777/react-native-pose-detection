package com.posedetection.camera

import android.content.Context
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.util.UUID

/** What a finished capture hands back to JavaScript. */
internal class CapturedPhoto(
    val uri: String,
    val width: Int,
    val height: Int,
    val size: Int,
    val mirrored: Boolean,
) {
    val payload: Map<String, Any>
        get() =
            mapOf(
                "uri" to uri,
                "width" to width,
                "height" to height,
                "size" to size,
                "mirrored" to mirrored,
            )
}

/**
 * Where stills land and how they are measured.
 *
 * The cache directory, not the gallery: this package never asks for a storage permission, and an
 * app that wants the photo kept can move it. Nothing prunes these, so the app owns the cleanup.
 */
internal object PhotoFiles {
    private const val DIRECTORY = "pose-photos"

    fun create(context: Context): File {
        val directory = File(context.cacheDir, DIRECTORY)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("could not create the photo directory")
        }
        return File(directory, "${UUID.randomUUID()}.jpg")
    }

    /**
     * Reads the written file back for its real dimensions. Decodes bounds only, so a 12 MP still
     * costs a header read rather than 48 MB of bitmap.
     *
     * [quality] is accepted for parity with iOS and deliberately unused: CameraX writes the
     * sensor's own JPEG, and re-encoding it here would cost a full decode to lose detail.
     */
    fun describe(
        file: File,
        @Suppress("UNUSED_PARAMETER") quality: Double,
        mirrored: Boolean,
    ): CapturedPhoto {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalStateException("the photo could not be read back")
        }

        // CameraX writes rotation into EXIF rather than rotating pixels, so the stored buffer can
        // be the sensor's landscape frame while the photo is a portrait one. Report what a viewer
        // will show, not what the bytes happen to be laid out as.
        // The platform reader, not `androidx.exifinterface`: this only needs the orientation tag
        // of a JPEG the platform just wrote, and the AndroidX one would be a new dependency.
        @Suppress("DEPRECATION")
        val quarterTurned =
            runCatching {
                when (ExifInterface(file.absolutePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90,
                    ExifInterface.ORIENTATION_ROTATE_270,
                    ExifInterface.ORIENTATION_TRANSPOSE,
                    ExifInterface.ORIENTATION_TRANSVERSE,
                    -> true
                    else -> false
                }
            }.getOrDefault(false)

        return CapturedPhoto(
            uri = Uri.fromFile(file).toString(),
            width = if (quarterTurned) bounds.outHeight else bounds.outWidth,
            height = if (quarterTurned) bounds.outWidth else bounds.outHeight,
            size = file.length().toInt(),
            mirrored = mirrored,
        )
    }
}
