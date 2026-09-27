package com.posedetection.detector

import android.os.Handler
import android.os.Looper
import com.google.mediapipe.tasks.core.Delegate
import com.posedetection.LogCategory
import com.posedetection.PoseLog
import java.util.concurrent.Executors

/** Parks the last camera landmarker: one takes 1.9 s to build on a Redmi Note 12's GPU. */
internal object DetectorCache {
    const val KEEP_MS = 60_000L

    /** One thread per delegate, so a CPU build never waits behind a slow GPU one. */
    private val cpuBuilds = buildThread("pose-build-cpu")
    private val gpuBuilds = buildThread("pose-build-gpu")

    private fun buildThread(name: String) =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, name).apply { isDaemon = true }
        }

    private val lock = Any()
    private var parked: PoseDetector? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val expire =
        Runnable {
            PoseLog.info(LogCategory.DETECTOR) { "the parked landmarker went unused, releasing it" }
            clear()
        }

    /** False when the thread is gone, which only a dying process sees. */
    fun execute(
        delegate: Delegate,
        block: () -> Unit,
    ): Boolean =
        runCatching { (if (delegate == Delegate.GPU) gpuBuilds else cpuBuilds).execute(block) }
            .onFailure { PoseLog.warn(LogCategory.DETECTOR) { "the build thread is gone: ${it.message}" } }
            .isSuccess

    /** The caller must not start another inference on [detector]; a running one ends under its lock. */
    fun park(detector: PoseDetector) {
        val previous =
            synchronized(lock) {
                val previous = parked
                parked = detector
                previous
            }
        if (previous != null && previous !== detector) closeLater(previous)
        mainHandler.removeCallbacks(expire)
        mainHandler.postDelayed(expire, KEEP_MS)
        PoseLog.debug(LogCategory.DETECTOR) { "parked the ${detector.delegate} landmarker for the next camera" }
    }

    fun take(
        modelFileName: String,
        request: DelegateRequest,
        maxPoses: Int,
        minConfidence: Float,
    ): PoseDetector? {
        val taken =
            synchronized(lock) {
                val candidate = parked ?: return null
                if (!candidate.fits(modelFileName, request, maxPoses, minConfidence)) return null
                parked = null
                candidate
            }
        mainHandler.removeCallbacks(expire)
        return taken
    }

    fun clear() {
        val doomed =
            synchronized(lock) {
                val doomed = parked
                parked = null
                doomed
            }
        mainHandler.removeCallbacks(expire)
        doomed?.let(::closeLater)
    }

    /** Off main: closing a GPU landmarker tears down its GL context. */
    fun closeLater(detector: PoseDetector) {
        if (!execute(detector.delegate) { detector.close() }) detector.close()
    }
}
