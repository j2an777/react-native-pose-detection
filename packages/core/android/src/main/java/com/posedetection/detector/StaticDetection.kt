package com.posedetection.detector

import android.content.Context
import android.media.MediaExtractor
import android.net.Uri
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import com.posedetection.ErrorCode
import com.posedetection.LogCategory
import com.posedetection.PoseLog
import com.posedetection.Skeleton
import com.posedetection.engine.FrameShape
import com.posedetection.engine.Geometry
import com.posedetection.engine.OneEuroFilter
import com.posedetection.engine.PoseBox
import com.posedetection.engine.PoseTrack
import com.posedetection.engine.WireWriter
import com.posedetection.export.PoseExport
import com.posedetection.performance.FilePacer
import com.posedetection.performance.ThermalMonitor
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** A file job's failure, carrying the code its promise rejects with. */
internal class StaticDetectionError(
    val code: ErrorCode,
    message: String,
) : Exception(message)

/**
 * The same detector, without a camera.
 *
 * Nothing here calibrates: a file has no frame budget to hit, so it always runs at full quality.
 * What it does answer to is heat, through [FilePacer], and to the camera, through [FileDetector]'s
 * choice of delegate.
 */
internal object StaticDetection {
    private val cancelled = ConcurrentHashMap<Int, AtomicBoolean>()

    /**
     * Where photo and video detection run: one thread, below the camera's, and this package's own.
     * Expo runs every module's async functions on one shared thread, so a video job there held up
     * every other module in the app for as long as it ran, at the camera's priority.
     */
    val executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "pose-detection-files").apply {
                priority = Thread.MIN_PRIORITY
                isDaemon = true
            }
        }

    fun cancel(taskId: Int) {
        cancelled[taskId]?.set(true)
    }

    /** One entry per detected pose, the subject first, so a two-person photo decodes to two frames. */
    fun detectImage(
        context: Context,
        uri: String,
        options: StaticOptions,
        angleJoints: Array<String>,
        selection: IntArray?,
    ): ByteBuffer {
        val bitmap =
            StillImage.decode(context, uri, StillImage.DETECTION_MAX_PIXELS)
                ?: throw StaticDetectionError(ErrorCode.IMAGE_DECODE_FAILED, "could not read an image from $uri")
        val shape = shapeFor(options, angleJoints, selection)

        // Constructed inside the try: requireModel and createFromOptions both throw, and a throw
        // between decoding the bitmap and entering the try strands its pixels until GC.
        var detector: PoseDetector? = null
        return try {
            detector =
                PoseDetector.createForStillInput(
                    context = context,
                    modelFileName = requireModel(context),
                    maxPoses = options.maxPoses,
                    video = false,
                    minConfidence = options.minConfidence,
                )
            val result = detector.detectImage(BitmapImageBuilder(bitmap).build())
            val poses = PoseExport.poses(result)
            val subject = if (poses.size > 1) PoseBox.primary(poses.map { PoseBox.of(it) }) else 0
            val order = if (poses.isEmpty()) emptyList() else listOf(subject) + poses.indices.filter { it != subject }
            val frames =
                order.map { index ->
                    encode(poses[index], result, index, shape, bitmap.width, bitmap.height, null)
                }
            write(shape, frames, DoubleArray(frames.size))
        } finally {
            detector?.close()
            bitmap.recycle()
        }
    }

    /**
     * Sampled at `fps`, not at the video's own rate, and run through `VIDEO` mode with monotonic
     * timestamps so temporal tracking behaves the way it does live. Each frame carries its real
     * position in the video, which is what smoothing and velocity are measured against.
     */
    @Suppress("LongParameterList")
    fun detectVideo(
        context: Context,
        uri: String,
        options: StaticOptions,
        angleJoints: Array<String>,
        selection: IntArray?,
        taskId: Int,
        onProgress: (Float) -> Unit,
    ): ByteBuffer {
        val flag = AtomicBoolean(false)
        cancelled[taskId] = flag

        // Same reason as detectImage, and one more: `cancelled` belongs to an object, so a task id
        // that never reaches the finally leaks a map entry for the life of the process.
        var sampler: VideoFrameSampler? = null
        var detector: FileDetector? = null
        return try {
            sampler = VideoFrameSampler(context, uri, options.fps, options.startMs, options.endMs)
            val shape = shapeFor(options, angleJoints, selection)
            detector = FileDetector(context, requireModel(context), options.maxPoses, options.minConfidence)
            val pacer = FilePacer(ThermalMonitor(context)::readThermal)
            val tracker = VideoTracker(options.fps, options.smoothing, sampler.width, sampler.height)

            val frames = ArrayList<FloatArray>()
            val timestamps = ArrayList<Double>()
            var lastTimestamp = -1L
            while (!flag.get()) {
                val frame = sampler.next() ?: break
                // VIDEO mode rejects a timestamp that does not move forward, and a variable frame
                // rate clip can hand back two frames on the same millisecond.
                val timestamp = maxOf(frame.timestampMs, lastTimestamp + 1)
                lastTimestamp = timestamp
                val result = detector.detect(BitmapImageBuilder(frame.bitmap).build(), timestamp)
                val encoded = tracker.encode(result, shape, frame.timestampMs.toDouble())
                if (encoded != null) {
                    frames.add(encoded)
                    timestamps.add(frame.timestampMs.toDouble())
                } else {
                    PoseLog.debug(LogCategory.ENGINE) { "nobody found at ${frame.timestampMs} ms" }
                }
                onProgress(sampler.progress(frame))
                if (!pacer.rest { flag.get() }) break
            }

            onProgress(1f)
            write(shape, frames, timestamps.toDoubleArray())
        } finally {
            cancelled.remove(taskId)
            detector?.close()
            sampler?.close()
        }
    }

    private fun shapeFor(
        options: StaticOptions,
        angleJoints: Array<String>,
        selection: IntArray?,
    ): FrameShape =
        FrameShape(
            jointIndices = selection ?: FrameShape.ALL_JOINTS,
            worldLandmarks = options.worldLandmarks,
            angleJoints = if (options.angles) angleJoints else emptyArray(),
        )

    /**
     * The same block order the live path writes, because it is the same decoder on the other side.
     * [velocity] holds x and y, or is null for a frame that has none.
     */
    @Suppress("LongParameterList")
    fun encode(
        landmarks: FloatArray,
        result: PoseLandmarkerResult,
        poseIndex: Int,
        shape: FrameShape,
        frameWidth: Int,
        frameHeight: Int,
        velocity: FloatArray?,
    ): FloatArray {
        val frame = FloatArray(shape.floatsPerFrame)
        var cursor = 0

        for (position in shape.jointIndices.indices) {
            val base = shape.jointIndices[position] * Skeleton.LANDMARK_STRIDE
            System.arraycopy(landmarks, base, frame, cursor, Skeleton.LANDMARK_STRIDE)
            cursor += Skeleton.LANDMARK_STRIDE
        }

        if (shape.worldLandmarks) {
            val world = result.worldLandmarks()
            val points = if (world.size > poseIndex) world[poseIndex] else null
            for (position in shape.jointIndices.indices) {
                val joint = shape.jointIndices[position]
                val point = points?.getOrNull(joint)
                frame[cursor] = point?.x() ?: 0f
                frame[cursor + 1] = point?.y() ?: 0f
                frame[cursor + 2] = point?.z() ?: 0f
                frame[cursor + 3] = point?.visibility()?.orElse(0f) ?: 0f
                cursor += Skeleton.LANDMARK_STRIDE
            }
        }

        for (triple in shape.angleTriples) {
            frame[cursor] = Geometry.angleDegrees(landmarks, triple[0], triple[1], triple[2], frameWidth, frameHeight)
            cursor += 1
        }

        Geometry.centerOfMass(landmarks, frame, cursor)
        cursor += 2
        // Unknown, not zero, when there is no previous frame to differ from.
        frame[cursor] = velocity?.get(0) ?: Float.NaN
        frame[cursor + 1] = velocity?.get(1) ?: Float.NaN
        cursor += 2
        frame[cursor] = Geometry.bodySpan(landmarks)

        return frame
    }

    private fun write(
        shape: FrameShape,
        frames: List<FloatArray>,
        timestamps: DoubleArray,
    ): ByteBuffer {
        val buffer = WireWriter.allocate(shape, frames.size, 0)
        if (frames.isEmpty()) return buffer

        val meta = WireWriter.meta(buffer)
        for (index in frames.indices) {
            meta.put(timestamps.getOrElse(index) { 0.0 })
            meta.put(0.0)
        }

        val body = WireWriter.body(buffer, frames.size)
        for (frame in frames) body.put(frame, 0, shape.floatsPerFrame)

        buffer.rewind()
        return buffer
    }

    fun requireModel(context: Context): String =
        PoseDetector.findModelAsset(context)
            ?: throw StaticDetectionError(
                ErrorCode.MODEL_NOT_FOUND,
                "No pose model is bundled. Run the CLI or prebuild first.",
            )

    /** A file path, a `file://` URI or a `content://` one, for an extractor. */
    fun openExtractor(
        extractor: MediaExtractor,
        context: Context,
        uri: String,
    ) {
        val parsed = Uri.parse(uri)
        if (parsed.scheme == null || parsed.scheme == "file") {
            extractor.setDataSource(parsed.path ?: uri)
        } else {
            extractor.setDataSource(context, parsed, null)
        }
    }
}

/**
 * The subject of a video, followed from one sampled frame to the next: the same rules the live
 * view applies. The largest body is the subject, smoothing and velocity are measured against real
 * timestamps, and both start over when the subject is lost, changes, or a gap opens (see
 * [PoseTrack]).
 */
internal class VideoTracker(
    fps: Int,
    smoothing: Boolean,
    private val width: Int,
    private val height: Int,
) {
    private val track = PoseTrack(fps)
    private val smoothing = if (smoothing) OneEuroFilter() else null
    private val center = FloatArray(2)
    private val velocity = FloatArray(2)

    /** The subject's frame, or null when nobody was found. */
    fun encode(
        result: PoseLandmarkerResult,
        shape: FrameShape,
        timestampMs: Double,
    ): FloatArray? {
        val poses = PoseExport.poses(result)
        if (poses.isEmpty()) {
            track.lose()
            return null
        }
        val boxes = poses.map { PoseBox.of(it) }
        val subject = if (poses.size > 1) PoseBox.primary(boxes) else 0
        val landmarks = poses[subject]
        val elapsed = track.advance(boxes[subject], timestampMs)

        smoothing?.let {
            // Speed in body spans, as live: x is normalized by width, so its span is scaled to it.
            val span = Geometry.bodySpan(landmarks)
            val aspect = if (width > 0) height.toFloat() / width else 1f
            it.apply(landmarks, elapsed ?: Float.NaN, span * aspect, span)
        }

        Geometry.centerOfMass(landmarks, center, 0)
        track.velocity(center[0], center[1], elapsed, velocity, 0)
        return StaticDetection.encode(landmarks, result, subject, shape, width, height, velocity)
    }
}

internal fun <T> List<T>.getOrNull(index: Int): T? = if (index in indices) this[index] else null
