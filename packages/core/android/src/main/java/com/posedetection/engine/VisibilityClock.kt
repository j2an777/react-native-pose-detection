package com.posedetection.engine

import com.posedetection.Skeleton
import kotlin.math.pow

/**
 * With one pose MediaPipe low-passes visibility per frame (`v = 0.1 × model + 0.9 × v'`), so it
 * lags at low fps. This inverts that exactly and refilters on elapsed time. Analysis thread only.
 */
internal class VisibilityClock {
    /** MediaPipe's previous output per landmark, which is what the next one is inverted against. */
    private val delivered = FloatArray(Skeleton.LANDMARK_COUNT)
    private val output = FloatArray(Skeleton.LANDMARK_COUNT)
    private var lastMs = Double.NaN
    private var handingOver = false

    /** When MediaPipe's filter may have restarted unseen: lost pose, dropped result, new landmarker. */
    fun reset() {
        lastMs = Double.NaN
        handingOver = false
    }

    /** A new landmarker (GPU for CPU) continues the track: its first frame only seeds the inversion. */
    fun handOver() {
        handingOver = true
    }

    /** Rewrites visibility in place in [landmarks], which is in wire layout. */
    fun apply(
        landmarks: FloatArray,
        timestampMs: Double,
    ) {
        val elapsed = timestampMs - lastMs
        val first = lastMs.isNaN() || !(elapsed > 0.0)
        lastMs = timestampMs
        val weight = if (first) 1f else weight(elapsed)

        // Only across a short gap: after a long one what was visible then says nothing about now.
        val continuing = handingOver && !first && elapsed <= HANDOVER_MAX_GAP_MS
        handingOver = false
        if (continuing) {
            for (joint in 0 until Skeleton.LANDMARK_COUNT) {
                val index = joint * Skeleton.LANDMARK_STRIDE + Skeleton.OFFSET_VISIBILITY
                delivered[joint] = landmarks[index]
                landmarks[index] = output[joint]
            }
            return
        }

        for (joint in 0 until Skeleton.LANDMARK_COUNT) {
            val index = joint * Skeleton.LANDMARK_STRIDE + Skeleton.OFFSET_VISIBILITY
            val smoothed = landmarks[index]
            val previous = delivered[joint]
            delivered[joint] = smoothed

            val model = previous + (smoothed - previous) / MEDIAPIPE_WEIGHT
            // Unreachable by the filter, so MediaPipe restarted it and this is the model's own value.
            if (first || model < -TOLERANCE || model > 1f + TOLERANCE) {
                output[joint] = smoothed
                continue
            }
            output[joint] += weight * (model.coerceIn(0f, 1f) - output[joint])
            landmarks[index] = output[joint]
        }
    }

    companion object {
        /** MediaPipe's per-frame weight for visibility, in `pose_landmarks_detector_graph.cc`. */
        const val MEDIAPIPE_WEIGHT = 0.1f

        /** The frame interval that weight is matched at: the 30 fps the camera is pinned to. */
        const val REFERENCE_MS = 1_000.0 / 30.0

        const val HANDOVER_MAX_GAP_MS = 1_000.0

        /** Far above the ~1e-6 float error of an exact inversion, far inside [0, 1]. */
        private const val TOLERANCE = 0.01f

        /** Applied once over [elapsedMs], does what MediaPipe's weight does per 30 fps frame. */
        fun weight(elapsedMs: Double): Float = (1.0 - (1.0 - MEDIAPIPE_WEIGHT).pow(elapsedMs / REFERENCE_MS)).toFloat()
    }
}
