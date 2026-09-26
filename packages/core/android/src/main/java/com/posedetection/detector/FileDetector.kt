package com.posedetection.detector

import android.content.Context
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import com.posedetection.LogCategory
import com.posedetection.PoseLog
import com.posedetection.engine.FrameStreams
import com.posedetection.performance.calibratorFor
import java.io.Closeable

/**
 * The landmarker a video job runs on, and the rule that picks its delegate.
 *
 * The GPU when this device's GPU check passed and no camera is running inference; the CPU
 * otherwise. A file job therefore never competes with a live preview for the GPU that preview's own
 * inference runs on. A device the check has never run on tries the GPU, and the first frame is the
 * check: if it fails, the job carries on on the CPU, and the answer is kept for the camera and for
 * the next job.
 *
 * The choice is made once, when the job starts. A camera started halfway through a long job shares
 * the GPU with it until the job ends, which is rarer and cheaper than rebuilding mid-job.
 */
internal class FileDetector(
    private val context: Context,
    private val modelFileName: String,
    private val maxPoses: Int,
    private val minConfidence: Float,
) : Closeable {
    private val calibrator = calibratorFor(context)
    private var detector: PoseDetector

    /** On the GPU with nothing yet to show it works here. */
    private var unproven: Boolean

    init {
        val verdict = calibrator.cachedGpu(modelFileName)
        val cameraBusy = FrameStreams.anyDetecting()
        val gpu = verdict != false && !cameraBusy
        unproven = gpu && verdict == null

        var built: PoseDetector? = null
        if (gpu) {
            built =
                runCatching { build(Delegate.GPU) }
                    .onFailure {
                        PoseLog.warn(
                            LogCategory.DETECTOR,
                        ) { "the GPU could not be built for a file job, using the CPU: ${it.message}" }
                        calibrator.storeGpu(false, modelFileName)
                        unproven = false
                    }.getOrNull()
        }
        detector = built ?: build(Delegate.CPU)
        val reason =
            when {
                cameraBusy -> ", because a camera is detecting"
                unproven -> ", unproven here"
                else -> ""
            }
        PoseLog.info(LogCategory.DETECTOR) { "file job on ${detector.delegate}$reason" }
    }

    /** VIDEO mode. A GPU that fails is replaced by the CPU once, and the frame is run again there. */
    fun detect(
        image: MPImage,
        timestampMs: Long,
    ): PoseLandmarkerResult {
        if (detector.delegate != Delegate.GPU) return detector.detectVideo(image, timestampMs)
        return try {
            val result = detector.detectVideo(image, timestampMs)
            if (unproven) {
                unproven = false
                calibrator.storeGpu(true, modelFileName)
            }
            result
        } catch (error: RuntimeException) {
            PoseLog.warn(
                LogCategory.DETECTOR,
            ) { "the GPU failed on a file job, moving it to the CPU: ${error.message}" }
            calibrator.storeGpu(false, modelFileName)
            unproven = false
            detector.close()
            detector = build(Delegate.CPU)
            detector.detectVideo(image, timestampMs)
        }
    }

    override fun close() {
        detector.close()
    }

    private fun build(delegate: Delegate): PoseDetector =
        PoseDetector.createForStillInput(
            context = context,
            modelFileName = modelFileName,
            maxPoses = maxPoses,
            video = true,
            minConfidence = minConfidence,
            delegate = delegate,
        )
}
