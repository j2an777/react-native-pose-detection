package com.posedetection.detector

import android.os.Handler
import android.os.Looper
import com.google.mediapipe.tasks.core.Delegate
import com.posedetection.LogCategory
import com.posedetection.PoseLog
import java.util.concurrent.Executors

/**
 * Where camera landmarkers are built, and the one a camera screen leaves behind when it closes.
 *
 * A camera landmarker takes seconds to build on a low-end GPU: 1.9 s on a Redmi Note 12, against
 * 0.4 s for the camera it runs beside. A screen that is closed and opened again inside a minute takes
 * back the one it left instead of building another, and its skeleton is up as soon as its camera is.
 *
 * One slot, not a pool: one camera runs at a time, and a second parked landmarker is memory held
 * for a screen that is not coming back.
 */
internal object DetectorCache {
    /** How long a parked landmarker waits for a camera before its memory is given back. */
    const val KEEP_MS = 60_000L

    /**
     * Camera builds, and closes of parked landmarkers, one thread per delegate. Off the camera's own
     * analysis thread, so a GPU landmarker can build while a CPU one is already answering frames.
     * Apart from each other, so a CPU build never waits behind a GPU one: a camera screen closed
     * while its GPU landmarker was still building, and opened again, used to wait for that build
     * before its own CPU one could start, and its first skeleton took 3 s instead of 1.
     */
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

    /**
     * Runs [block] on [delegate]'s build thread. False when the thread is gone, which only a dying
     * process sees.
     */
    fun execute(
        delegate: Delegate,
        block: () -> Unit,
    ): Boolean =
        runCatching { (if (delegate == Delegate.GPU) gpuBuilds else cpuBuilds).execute(block) }
            .onFailure { PoseLog.warn(LogCategory.DETECTOR) { "the build thread is gone: ${it.message}" } }
            .isSuccess

    /**
     * Keeps [detector] for the next camera that fits it, and closes whatever was kept before. The
     * caller must not start another inference on it; one it already has running is waited for by
     * the detector's own lock before anybody else's can start.
     */
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

    /** The parked landmarker when it was built for exactly these settings, taken out of the cache. */
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

    /** Memory pressure, or a minute unused. */
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
