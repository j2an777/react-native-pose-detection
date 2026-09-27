package com.posedetection.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import com.posedetection.LogCategory
import com.posedetection.PoseLog
import com.posedetection.Skeleton
import com.posedetection.detector.PoseDetector
import com.posedetection.detector.StaticDetection
import com.posedetection.detector.StillImage
import com.posedetection.view.ContentFit
import com.posedetection.view.OverlayProjection
import com.posedetection.view.OverlayRenderer
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Never slows the live camera: its own detector, one low-priority thread, one frame of pixels at a time. */
internal object PoseExport {
    private val VIDEO_EXTENSIONS =
        setOf("mp4", "mov", "m4v", "3gp", "avi", "mkv", "webm")

    const val STAGING_SUFFIX = ".partial"

    /** Serial, which the staging sweep relies on, and below the camera's priority. */
    val executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "pose-export").apply {
                priority = Thread.MIN_PRIORITY
                isDaemon = true
            }
        }

    private val cancelled = ConcurrentHashMap<Int, AtomicBoolean>()

    /** Registers at enqueue, so a cancel that lands while the job waits in the queue is kept. */
    fun enqueue(taskId: Int) {
        cancelled.putIfAbsent(taskId, AtomicBoolean(false))
    }

    fun cancel(taskId: Int) {
        cancelled[taskId]?.set(true)
    }

    /** Content type first: a picker's `content://` URI usually has no extension. */
    fun isVideo(
        context: Context,
        uri: String,
    ): Boolean {
        val parsed = Uri.parse(uri)
        if (parsed.scheme == "content") {
            val type = runCatching { context.contentResolver.getType(parsed) }.getOrNull()
            if (type != null) return type.startsWith("video/")
        }
        val path = parsed.path ?: uri
        return VIDEO_EXTENSIONS.contains(path.substringAfterLast('.', "").lowercase())
    }

    fun run(
        context: Context,
        uri: String,
        raw: Map<*, *>?,
        taskId: Int,
        onProgress: (Float) -> Unit,
    ): ExportSummary {
        val flag = cancelled.getOrPut(taskId) { AtomicBoolean(false) }
        try {
            // Cancelled while it waited in the queue.
            if (flag.get()) throw ExportCancelled()

            val sourceName =
                (Uri.parse(uri).lastPathSegment ?: "pose")
                    .substringAfterLast('/')
                    .substringBeforeLast('.')
            val options = ExportOptions.parse(context, raw, sourceName)
            // The resolved minConfidence is not visible from JavaScript otherwise.
            PoseLog.info(LogCategory.ENGINE) {
                "export maxPoses=${options.maxPoses} minConfidence=${options.minConfidence}"
            }

            return if (isVideo(context, uri)) {
                VideoExporter(context, uri, options, flag, onProgress).run()
            } else {
                exportImage(context, uri, options, onProgress)
            }
        } finally {
            cancelled.remove(taskId)
        }
    }

    private fun exportImage(
        context: Context,
        uri: String,
        options: ExportOptions,
        onProgress: (Float) -> Unit,
    ): ExportSummary {
        // Detect on a smaller decode, freed before the painted one, so a huge photo is never held twice.
        val paintMax = options.maxSize.takeIf { it > 0 }
        val shared = paintMax != null && paintMax <= StillImage.DETECTION_MAX_PIXELS
        val detectable =
            StillImage.decode(context, uri, if (shared) paintMax else StillImage.DETECTION_MAX_PIXELS)
                ?: throw ExportError("could not read an image from $uri")

        var detector: PoseDetector? = null
        val result: PoseLandmarkerResult
        try {
            detector =
                PoseDetector.createForStillInput(
                    context,
                    StaticDetection.requireModel(context),
                    options.maxPoses,
                    video = false,
                    minConfidence = options.minConfidence,
                )
            result = detector.detectImage(BitmapImageBuilder(detectable).build())
        } catch (error: Throwable) {
            detectable.recycle()
            throw error
        } finally {
            detector?.close()
        }
        onProgress(0.6f)

        val source =
            if (shared) {
                detectable
            } else {
                detectable.recycle()
                StillImage.decode(context, uri, paintMax) ?: throw ExportError("could not read an image from $uri")
            }

        val canvas = ExportCanvas.size(source.width, source.height, options.maxSize)
        val painted = Bitmap.createBitmap(canvas[0], canvas[1], Bitmap.Config.ARGB_8888)
        val output = File(options.directory, "${options.fileName}.jpg")
        // Staged and renamed, so a failed encode never leaves a truncated file that looks finished.
        val staging = File(options.directory, "${options.fileName}$STAGING_SUFFIX.jpg")
        try {
            paint(painted, source, result, options)
            FileOutputStream(staging).use { stream ->
                if (!painted.compress(Bitmap.CompressFormat.JPEG, options.quality, stream)) {
                    throw ExportError("could not encode the painted image")
                }
            }
            if (!staging.renameTo(output)) throw ExportError("the export could not be moved into place")
        } finally {
            staging.delete()
            painted.recycle()
            source.recycle()
        }
        onProgress(1f)

        return ExportSummary(
            file = output,
            width = canvas[0],
            height = canvas[1],
            durationMs = 0,
            frameCount = 1,
            posesFound = result.landmarks().size,
        )
    }

    private fun paint(
        target: Bitmap,
        source: Bitmap,
        result: PoseLandmarkerResult,
        options: ExportOptions,
    ) {
        val projection =
            OverlayProjection(
                source.width,
                source.height,
                target.width.toFloat(),
                target.height.toFloat(),
                ContentFit.FIT,
            )
        val canvas = Canvas(target)
        canvas.drawColor(Color.BLACK)
        // Built here: the projection stays free of android.graphics for its JVM tests.
        canvas.drawBitmap(source, null, projection.rect(), null)

        if (!options.drawOverlay) return

        val renderer = OverlayRenderer(ExportCanvas.overlayScale(target.width, target.height))
        renderer.config = options.overlay
        for (landmarks in poses(result)) {
            renderer.draw(
                canvas,
                landmarks,
                projection,
                mirrored = false,
                sourceWidth = source.width,
                sourceHeight = source.height,
            )
        }
    }

    private fun OverlayProjection.rect(): RectF = RectF(left, top, left + width, top + height)

    /** Every pose, not just the subject: painting one of the maxPoses asked for would ignore the rest. */
    fun poses(result: PoseLandmarkerResult): List<FloatArray> =
        result.landmarks().map { pose ->
            val landmarks = FloatArray(Skeleton.LANDMARK_COUNT * Skeleton.LANDMARK_STRIDE)
            val count = minOf(Skeleton.LANDMARK_COUNT, pose.size)
            for (index in 0 until count) {
                val point = pose[index]
                val base = index * Skeleton.LANDMARK_STRIDE
                landmarks[base + Skeleton.OFFSET_X] = point.x()
                landmarks[base + Skeleton.OFFSET_Y] = point.y()
                landmarks[base + Skeleton.OFFSET_Z] = point.z()
                landmarks[base + Skeleton.OFFSET_VISIBILITY] =
                    if (point.visibility().isPresent) point.visibility().get() else 0f
            }
            landmarks
        }
}
