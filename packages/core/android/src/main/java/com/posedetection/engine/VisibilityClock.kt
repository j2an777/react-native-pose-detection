package com.posedetection.engine

import com.posedetection.Skeleton
import kotlin.math.pow

/**
 * Makes MediaPipe's visibility smoothing run on time rather than on frames.
 *
 * With one pose in VIDEO mode MediaPipe low-passes every landmark's visibility once per frame,
 * `v = 0.1 × model + 0.9 × v'`, and passes the first frame of a track through unchanged
 * (`pose_landmarks_detector_graph.cc`, `low_pass_filter.cc`). Once per frame means the same filter
 * is three times slower at 10 fps than at 30. On a Redmi Note 12 at 10 fps a hand raised into view
 * took seven frames, 0.7 s, to cross the overlay's 0.5 and be drawn, against 0.23 s on an iPhone at
 * 30 fps, and a hand lowered out of view was drawn for as long after it had gone.
 *
 * The filter's whole state is its previous output, which is the previous frame's visibility, so it
 * inverts exactly and gives back what the model said. That is filtered again here with a weight
 * that grows with the time since the previous frame, chosen to equal MediaPipe's 0.1 at 30 fps:
 * nothing changes on a device that keeps up, and a slower one reaches the same visibility in the
 * same time.
 *
 * Only for one pose, because only one pose is smoothed. Analysis thread only.
 */
internal class VisibilityClock {
    /** MediaPipe's previous output per landmark, which is what the next one is inverted against. */
    private val delivered = FloatArray(Skeleton.LANDMARK_COUNT)
    private val output = FloatArray(Skeleton.LANDMARK_COUNT)
    private var lastMs = Double.NaN
    private var handingOver = false

    /**
     * Whenever MediaPipe's filter may have started over without this seeing it: a lost pose, a
     * frame that was dropped after MediaPipe answered it, a different landmarker. Starting over here
     * too is always safe, because the next frame is then taken as it comes.
     */
    fun reset() {
        lastMs = Double.NaN
        handingOver = false
    }

    /**
     * Another landmarker takes over the same track, as when the GPU replaces the CPU mid-session.
     * Its filters start over from its own first frame, which the next [apply] only takes as the
     * reference to invert against, while the visibility carries on where it was. Starting over
     * instead dropped a joint's visibility from 0.95 to the new filter's 0.79 at the handover.
     */
    fun handOver() {
        handingOver = true
    }

    /** Rewrites the visibility in [landmarks], the wire layout, for a frame taken at [timestampMs]. */
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
            // Outside what the filter could have produced means it started over on its own, and
            // its output is the model's again.
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

        /** A handover across a longer gap than this starts over instead. */
        const val HANDOVER_MAX_GAP_MS = 1_000.0

        /** Float error on an exact inversion is around 1e-6; this is far past it and far inside [0, 1]. */
        private const val TOLERANCE = 0.01f

        /** The weight that, applied once over [elapsedMs], does what MediaPipe's does per 30 fps frame. */
        fun weight(elapsedMs: Double): Float = (1.0 - (1.0 - MEDIAPIPE_WEIGHT).pow(elapsedMs / REFERENCE_MS)).toFloat()
    }
}
