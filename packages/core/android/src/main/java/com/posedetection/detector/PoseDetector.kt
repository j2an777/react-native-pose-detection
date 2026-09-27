package com.posedetection.detector

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import com.posedetection.LogCategory
import com.posedetection.PoseLog
import com.posedetection.engine.Upright

internal enum class DelegateRequest { AUTO, GPU, CPU }

/**
 * The delegates a camera builds, in order. The first one that builds answers frames at once, and a
 * later one replaces it when it is ready.
 *
 * `auto` starts on the CPU and moves to the GPU. The GPU is the faster and cooler of the two once
 * running, but the slower to build: on a Redmi Note 12 it runs the full model in 89 ms against the
 * CPU's 122 ms at half the CPU time, and takes 1.9 s to build against 0.7 s. Starting on the GPU
 * left the camera up and the skeleton missing for most of two seconds. A GPU known not to work
 * here is not built at all.
 */
internal object StartPlan {
    private val CPU_ONLY = listOf(Delegate.CPU)
    private val GPU_ONLY = listOf(Delegate.GPU)
    private val CPU_THEN_GPU = listOf(Delegate.CPU, Delegate.GPU)

    fun delegates(
        request: DelegateRequest,
        gpuVerdict: Boolean?,
    ): List<Delegate> =
        when (request) {
            DelegateRequest.CPU -> CPU_ONLY
            DelegateRequest.GPU -> GPU_ONLY
            DelegateRequest.AUTO -> if (gpuVerdict == false) CPU_ONLY else CPU_THEN_GPU
        }
}

internal class PoseDetector private constructor(
    private val landmarker: PoseLandmarker,
    val delegate: Delegate,
    val modelFileName: String,
    /** Baked in at construction, and what decides whether a parked landmarker fits a new camera. */
    val maxPoses: Int,
    val minConfidence: Float,
) {
    /**
     * VIDEO mode rejects a timestamp that does not strictly increase. Camera timestamps can repeat
     * within a millisecond, so the value is clamped.
     *
     * Written on whichever thread runs the landmarker, read on main when a switch completes. A
     * stale read there costs one dropped frame.
     */
    @Volatile
    var lastTimestampMs = 0L
        private set

    /**
     * Held for every inference and for the close. A landmarker is handed between threads, from the
     * build thread to a camera's analysis thread and from one camera to the next through
     * [DetectorCache], and a camera going away parks it at once rather than after the frame it may
     * still be running: whoever runs it next waits here for that frame to finish instead.
     */
    private val lock = Any()

    /**
     * One camera frame, answered before this returns, on the analysis thread.
     *
     * VIDEO mode rather than LIVE_STREAM, which tracks across frames the same way. LIVE_STREAM
     * hands every result back with a copy of the frame it came from, a new bitmap the size of the
     * analysis buffer, for a caller that only reads its size. On a Redmi Note 12 that was 17 MB a
     * second for the collector at ten frames, and moving to VIDEO took a fifth off the process's
     * CPU. Answered in place, a frame also never waits behind another: CameraX drops what arrives
     * while this runs, and the next frame converted is the newest one.
     */
    fun detect(
        image: MPImage,
        rotationDegrees: Int,
        cameraTimestampMs: Long,
    ): PoseLandmarkerResult =
        synchronized(lock) {
            val timestamp = maxOf(cameraTimestampMs, lastTimestampMs + 1)
            lastTimestampMs = timestamp
            landmarker.detectForVideo(image, rotationOptions(rotationDegrees), timestamp)
        }

    /**
     * One inference on a blank frame, before the camera's first. It does three jobs:
     *
     * - The first inference through a freshly built graph costs several times what the rest do,
     *   and this is where it is paid rather than on the first frame somebody is watching.
     * - On the GPU it is the check that the delegate works here. Construction succeeds on devices
     *   whose GPU then fails on the first real frame, and VIDEO mode answers synchronously, so the
     *   failure throws here instead of on the camera's thread.
     * - A blank frame finds nobody, which ends any track: a landmarker taken back from
     *   [DetectorCache] starts from nobody, not from somebody who stood there a minute ago.
     *
     * It has to run before the analyzer can reach the landmarker. Run after the first camera
     * frame, it ended the track that frame had just started, and the model found the person twice.
     */
    fun warmUp() {
        val blank = Bitmap.createBitmap(WARM_UP_SIZE, WARM_UP_SIZE, Bitmap.Config.ARGB_8888)
        try {
            detect(BitmapImageBuilder(blank).build(), 0, 0)
        } finally {
            // The answer is back before detect returns, so nothing is reading the pixels now.
            blank.recycle()
        }
    }

    /** Whether this landmarker is what a camera asking for these settings would have built. */
    fun fits(
        modelFileName: String,
        request: DelegateRequest,
        maxPoses: Int,
        minConfidence: Float,
    ): Boolean {
        if (modelFileName != this.modelFileName) return false
        if (maxPoses != this.maxPoses || minConfidence != this.minConfidence) return false
        return when (request) {
            DelegateRequest.AUTO -> true
            DelegateRequest.GPU -> delegate == Delegate.GPU
            DelegateRequest.CPU -> delegate == Delegate.CPU
        }
    }

    fun close() {
        synchronized(lock) {
            runCatching { landmarker.close() }
                .onFailure { PoseLog.warn(LogCategory.DETECTOR) { "closing the landmarker threw: ${it.message}" } }
        }
    }

    /** IMAGE and VIDEO mode are synchronous, so there is no result listener to route. */
    fun detectImage(image: MPImage): PoseLandmarkerResult = landmarker.detect(image)

    fun detectVideo(
        image: MPImage,
        timestampMs: Long,
    ): PoseLandmarkerResult = landmarker.detectForVideo(image, timestampMs)

    companion object {
        /**
         * A detector for a file rather than a camera. The CPU unless the caller has decided
         * otherwise: a photo is one inference, and compiling the GPU's shaders costs more than
         * running it. See [FileDetector] for when a video gets the GPU.
         */
        @Suppress("LongParameterList")
        fun createForStillInput(
            context: Context,
            modelFileName: String,
            maxPoses: Int,
            video: Boolean,
            minConfidence: Float = StillConfidence.SINGLE,
            delegate: Delegate = Delegate.CPU,
        ): PoseDetector {
            val landmarker =
                build(
                    context = context,
                    modelFileName = modelFileName,
                    delegate = delegate,
                    maxPoses = maxPoses,
                    minConfidence = minConfidence,
                    runningMode = if (video) RunningMode.VIDEO else RunningMode.IMAGE,
                )
            return PoseDetector(landmarker, delegate, modelFileName, maxPoses, minConfidence)
        }

        /** The plugin installs exactly one model, so listing beats being told which variant. */
        fun findModelAsset(context: Context): String? =
            context.assets
                .list("")
                ?.firstOrNull { it.startsWith("pose_landmarker_") && it.endsWith(".task") }

        /**
         * A camera's landmarker on exactly [delegate], built and warmed up, so the first frame the
         * analyzer hands it is an ordinary one. Throws when the delegate cannot build here, or
         * builds and then cannot run, which is how a GPU that does not work is found. Blocks for
         * as long as the build takes, which on a low-end GPU is seconds, so never on main.
         */
        fun createForCamera(
            context: Context,
            modelFileName: String,
            delegate: Delegate,
            maxPoses: Int,
            minConfidence: Float,
        ): PoseDetector {
            val landmarker =
                build(
                    context = context,
                    modelFileName = modelFileName,
                    delegate = delegate,
                    maxPoses = maxPoses,
                    minConfidence = minConfidence,
                    runningMode = RunningMode.VIDEO,
                )
            val detector = PoseDetector(landmarker, delegate, modelFileName, maxPoses, minConfidence)
            try {
                detector.warmUp()
            } catch (error: Throwable) {
                detector.close()
                throw error
            }
            PoseLog.info(LogCategory.DETECTOR) { "landmarker ready on $delegate with $modelFileName" }
            return detector
        }

        private fun build(
            context: Context,
            modelFileName: String,
            delegate: Delegate,
            maxPoses: Int,
            minConfidence: Float,
            runningMode: RunningMode,
        ): PoseLandmarker {
            val baseOptions =
                BaseOptions
                    .builder()
                    .setModelAssetPath(modelFileName)
                    .setDelegate(delegate)
                    .build()

            val options =
                PoseLandmarker.PoseLandmarkerOptions
                    .builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(runningMode)
                    .setNumPoses(maxPoses)
                    .setMinPoseDetectionConfidence(minConfidence)
                    .setMinPosePresenceConfidence(minConfidence)
                    .setMinTrackingConfidence(minConfidence)
                    .build()

            return PoseLandmarker.createFromOptions(context, options)
        }

        /**
         * Built once. The builder, the AutoValue instance and the boxed rotation it holds were
         * three allocations per frame for a value with four possible states that changes when the
         * device turns, not when a frame arrives.
         *
         * See [mediaPipeDegrees] for the sign.
         */
        private val ROTATION_OPTIONS =
            Array(QUARTER_TURNS) { quarter ->
                ImageProcessingOptions
                    .builder()
                    .setRotationDegrees(mediaPipeDegrees(quarter * DEGREES_PER_QUARTER))
                    .build()
            }

        fun rotationOptions(rotationDegrees: Int): ImageProcessingOptions =
            ROTATION_OPTIONS[Upright.quarterOf(rotationDegrees)]

        /**
         * What MediaPipe is handed for a buffer CameraX says needs [rotationDegrees] clockwise to
         * stand upright: the same turn, negated, because MediaPipe turns the image the other way by
         * the amount it is given. A frame dumped on a Redmi Note 12 settles it. Handed +270 for its
         * front camera, the model found the face and put the shoulders above the head: a person
         * upside down, which it finds late and draws scrambled, and whose landmarks still gather
         * around a close face, which is how that got past a look at the screen.
         */
        fun mediaPipeDegrees(rotationDegrees: Int): Int = -(Upright.quarterOf(rotationDegrees) * DEGREES_PER_QUARTER)

        private const val QUARTER_TURNS = 4
        private const val DEGREES_PER_QUARTER = 90

        private const val WARM_UP_SIZE = 256
    }
}
