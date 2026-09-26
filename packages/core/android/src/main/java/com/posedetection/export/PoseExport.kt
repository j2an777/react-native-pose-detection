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

/**
 * Paints the skeleton into a copy of a picked image or video and writes it into the app's sandbox.
 *
 * **Nothing here is allowed to slow the live camera down.** That is the whole shape of this file,
 * not a footnote to it, and it is bought four ways:
 *
 * 1. **Its own detector.** Never the camera's landmarker. Built here, used here, closed here.
 * 2. **Never the GPU while a camera is detecting.** A video runs inference on the GPU only when no
 *    camera is running inference and this device's GPU check passed, and on the CPU otherwise, so
 *    an export cannot contend with a preview for the GPU its own inference runs on. See
 *    [com.posedetection.detector.FileDetector]. A photo is one inference and always runs on the
 *    CPU. The video pixel path uses the hardware codec and GL, because that is the platform's
 *    cheap path and the alternative, converting every frame in Kotlin, would compete for far more
 *    CPU than the codec ever does for GPU.
 * 3. **A single background thread at low priority.** Serial, so two exports queue up behind each
 *    other instead of ganging up on the camera, and below the camera's own threads so the
 *    scheduler starves the export first. Heat slows it further, see
 *    [com.posedetection.performance.FilePacer].
 * 4. **Bounded memory.** One frame decoded at a time, one buffer encoded at a time, nothing
 *    accumulated. A long video costs what a short one costs, which is what keeps an export from
 *    ending as a memory-pressure kill of the camera it was running beside.
 *
 * Every path closes what it opened in a `finally`, so a throw, a cancel and a clean finish all
 * unwind the same way.
 */
internal object PoseExport {
    private val VIDEO_EXTENSIONS =
        setOf("mp4", "mov", "m4v", "3gp", "avi", "mkv", "webm")

    /** Serial and below the camera. See the note above; this is rule 3 and rule 3 is why it is here. */
    val executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "pose-export").apply {
                priority = Thread.MIN_PRIORITY
                isDaemon = true
            }
        }

    private val cancelled = ConcurrentHashMap<Int, AtomicBoolean>()

    fun cancel(taskId: Int) {
        cancelled[taskId]?.set(true)
    }

    fun isVideo(uri: String): Boolean {
        val path = Uri.parse(uri).path ?: uri
        return VIDEO_EXTENSIONS.contains(path.substringAfterLast('.', "").lowercase())
    }

    fun run(
        context: Context,
        uri: String,
        raw: Map<*, *>?,
        taskId: Int,
        onProgress: (Float) -> Unit,
    ): ExportSummary {
        val sourceName =
            (Uri.parse(uri).lastPathSegment ?: "pose")
                .substringAfterLast('/')
                .substringBeforeLast('.')
        val options = ExportOptions.parse(context, raw, sourceName)
        // The two values that decide who gets painted, and the pair most worth seeing when an
        // export finds fewer bodies than expected: `minConfidence` follows `maxPoses` unless the
        // caller set it, and the resolved number is not visible from JavaScript otherwise.
        PoseLog.info(LogCategory.ENGINE) {
            "export maxPoses=${options.maxPoses} minConfidence=${options.minConfidence}"
        }

        // An entry exists only while the job runs, so cancelling a task nobody started is a no-op
        // rather than a note kept for the life of the process.
        val flag = AtomicBoolean(false)
        cancelled[taskId] = flag
        try {
            return if (isVideo(uri)) {
                VideoExporter(context, uri, options, flag, onProgress).run()
            } else {
                exportImage(context, uri, options, onProgress)
            }
        } finally {
            cancelled.remove(taskId)
        }
    }

    // MARK: Stills

    private fun exportImage(
        context: Context,
        uri: String,
        options: ExportOptions,
        onProgress: (Float) -> Unit,
    ): ExportSummary {
        // Detection first, on a picture no larger than it needs, freed before the one that is
        // painted is decoded: a full-size export of a 48-megapixel photo never holds both. At the
        // default size the two are the same decode, and it is done once.
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
        try {
            paint(painted, source, result, options)
            FileOutputStream(File(options.directory, "${options.fileName}.jpg")).use { stream ->
                painted.compress(Bitmap.CompressFormat.JPEG, options.quality, stream)
            }
        } finally {
            painted.recycle()
            source.recycle()
        }
        onProgress(1f)

        return ExportSummary(
            file = File(options.directory, "${options.fileName}.jpg"),
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
        // Fit, not fill: cropping a picture the user picked would cut away part of the very thing
        // they asked to have painted.
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
        // The rect is built here rather than on the projection: that class is deliberately free of
        // `android.graphics` so it can run under a plain JVM test.
        canvas.drawBitmap(source, null, projection.rect(), null)

        if (!options.drawOverlay) return

        val renderer = OverlayRenderer(ExportCanvas.overlayScale(target.width, target.height))
        renderer.config = options.overlay
        for (landmarks in poses(result)) {
            renderer.draw(
                canvas,
                landmarks,
                projection,
                // A file is never mirrored: what was picked is what gets painted.
                mirrored = false,
                sourceWidth = source.width,
                sourceHeight = source.height,
            )
        }
    }

    private fun OverlayProjection.rect(): RectF = RectF(left, top, left + width, top + height)

    /**
     * Every pose in the frame as flat buffers the renderer reads, empty when nobody was in it.
     *
     * All of them, not the front one: `maxPoses` is an export option, and painting one skeleton
     * onto a frame the caller asked to have five detected in would silently ignore what they asked
     * for.
     */
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
