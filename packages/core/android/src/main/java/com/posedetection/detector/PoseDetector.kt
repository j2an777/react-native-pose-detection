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

/** `auto` answers on the CPU while the GPU builds: 1.9 s to the CPU's 0.7 s on a Redmi Note 12. */
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
    val maxPoses: Int,
    val minConfidence: Float,
) {
    /** Clamped: VIDEO mode rejects a timestamp that does not increase, and camera ones can repeat. */
    @Volatile
    var lastTimestampMs = 0L
        private set

    /** A camera parks this without waiting for its running frame, so whoever runs it next waits here. */
    private val lock = Any()

    /** VIDEO, not LIVE_STREAM, which copies each frame back: 17 MB/s of garbage at 10 fps. */
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

    /** Pays the slow first inference, proves a GPU works and ends any track; before any camera frame. */
    fun warmUp() {
        val blank = Bitmap.createBitmap(WARM_UP_SIZE, WARM_UP_SIZE, Bitmap.Config.ARGB_8888)
        try {
            detect(BitmapImageBuilder(blank).build(), 0, 0)
        } finally {
            // The answer is back before detect returns, so nothing is reading the pixels now.
            blank.recycle()
        }
    }

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

    fun detectImage(image: MPImage): PoseLandmarkerResult = landmarker.detect(image)

    fun detectVideo(
        image: MPImage,
        timestampMs: Long,
    ): PoseLandmarkerResult = landmarker.detectForVideo(image, timestampMs)

    companion object {
        /** CPU by default: for one photo, compiling the GPU's shaders costs more than the inference. */
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

        /** Blocks for seconds on a low-end GPU, so never on main; throws if [delegate] cannot run here. */
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

        /** Built once: per frame this is three allocations for a value with four states. */
        private val ROTATION_OPTIONS =
            Array(QUARTER_TURNS) { quarter ->
                ImageProcessingOptions
                    .builder()
                    .setRotationDegrees(mediaPipeDegrees(quarter * DEGREES_PER_QUARTER))
                    .build()
            }

        fun rotationOptions(rotationDegrees: Int): ImageProcessingOptions =
            ROTATION_OPTIONS[Upright.quarterOf(rotationDegrees)]

        /** CameraX's clockwise turn, negated: MediaPipe turns the image the other way by what it is given. */
        fun mediaPipeDegrees(rotationDegrees: Int): Int = -(Upright.quarterOf(rotationDegrees) * DEGREES_PER_QUARTER)

        private const val QUARTER_TURNS = 4
        private const val DEGREES_PER_QUARTER = 90

        private const val WARM_UP_SIZE = 256
    }
}
