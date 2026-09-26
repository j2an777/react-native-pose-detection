package com.posedetection.engine

import com.posedetection.Skeleton
import kotlin.math.abs

/**
 * One-Euro filter over the landmark buffer, in place.
 *
 * The trade every smoother makes is lag against jitter. This one moves the cutoff with the speed
 * of the signal: slow movement is filtered hard, because that is where jitter is visible and lag
 * is not; fast movement is barely filtered, because that is where lag is visible and jitter is
 * not. `minCutoff` sets how hard the slow case is filtered, `beta` how quickly it gets out of the
 * way when things move.
 *
 * Visibility is left alone. It is a confidence, not a position, and smoothing it would make a
 * joint that has just left frame keep reading as present.
 *
 * Casteljau et al., "1e Filter: A Simple Speed-based Low-pass Filter", CHI 2012.
 */
internal class OneEuroFilter {
    /** Filtered value and filtered derivative per axis, x/y/z of every landmark. */
    private val values = FloatArray(Skeleton.LANDMARK_COUNT * AXES)
    private val derivatives = FloatArray(Skeleton.LANDMARK_COUNT * AXES)
    private var primed = false

    var minCutoff = DEFAULT_MIN_CUTOFF
        private set
    var beta = DEFAULT_BETA
        private set

    fun configure(
        minCutoff: Float,
        beta: Float,
    ) {
        // A cutoff at or below zero divides by zero inside alpha and takes every landmark with it.
        val nextCutoff = if (minCutoff.isNaN() || minCutoff <= 0f) DEFAULT_MIN_CUTOFF else minCutoff
        val nextBeta = if (beta.isNaN() || beta < 0f) DEFAULT_BETA else beta

        if (nextCutoff == this.minCutoff && nextBeta == this.beta) return
        this.minCutoff = nextCutoff
        this.beta = nextBeta
        reset()
    }

    /** A discontinuity: a camera switch, a lost pose, a gap. Filtering across one invents motion. */
    fun reset() {
        primed = false
    }

    /**
     * [elapsedSeconds] is the real interval, not a nominal one: the filter's whole behavior is a
     * function of it, and feeding a constant makes it lie whenever a frame is late. A non-positive
     * or unknown interval is a gap, so the frame passes through untouched and the filter starts
     * over from it. Keeping the state from before the gap would filter the next frame against a
     * position the body left long ago.
     *
     * [scaleX] and [scaleY] turn a speed in normalized units into one in body spans: the span in
     * each axis's own units, so a distant subject's small movements count as much as a near one's
     * large ones. Depth rides with x, which is how MediaPipe scales it. Left at 1, speeds are frame
     * units.
     */
    fun apply(
        landmarks: FloatArray,
        elapsedSeconds: Float,
        scaleX: Float = 1f,
        scaleY: Float = 1f,
    ) {
        if (!primed || elapsedSeconds.isNaN() || elapsedSeconds <= 0f) {
            seed(landmarks)
            return
        }

        val derivativeAlpha = alpha(DERIVATIVE_CUTOFF, elapsedSeconds)

        for (joint in 0 until Skeleton.LANDMARK_COUNT) {
            val base = joint * Skeleton.LANDMARK_STRIDE
            val state = joint * AXES

            for (axis in 0 until AXES) {
                val raw = landmarks[base + axis]
                val slot = state + axis

                val scale = if (axis == 1) usableScale(scaleY) else usableScale(scaleX)
                val speed = (raw - values[slot]) / elapsedSeconds / scale
                val smoothedSpeed = derivatives[slot] + derivativeAlpha * (speed - derivatives[slot])
                derivatives[slot] = smoothedSpeed

                val cutoff = minCutoff + beta * abs(smoothedSpeed)
                val smoothed = values[slot] + alpha(cutoff, elapsedSeconds) * (raw - values[slot])

                values[slot] = smoothed
                landmarks[base + axis] = smoothed
            }
        }
    }

    private fun seed(landmarks: FloatArray) {
        for (joint in 0 until Skeleton.LANDMARK_COUNT) {
            val base = joint * Skeleton.LANDMARK_STRIDE
            val state = joint * AXES
            for (axis in 0 until AXES) {
                values[state + axis] = landmarks[base + axis]
                derivatives[state + axis] = 0f
            }
        }
        primed = true
    }

    /** A span can collapse to nothing on a half-visible body; dividing by it would read as infinite speed. */
    private fun usableScale(scale: Float): Float = if (scale.isFinite()) maxOf(scale, MINIMUM_SCALE) else 1f

    private fun alpha(
        cutoff: Float,
        elapsedSeconds: Float,
    ): Float {
        val timeConstant = 1f / (TAU * cutoff)
        return 1f / (1f + timeConstant / elapsedSeconds)
    }

    companion object {
        /** x, y, z. Visibility is index 3 and is deliberately not one of these. */
        private const val AXES = 3

        /**
         * The cutoff a body that is not moving is smoothed at. Low, because jitter lives there.
         *
         * This and [DEFAULT_BETA] are MediaPipe's own constants for pose landmarks, the filter it
         * runs itself whenever it tracks one body. Measured in the same units, body spans per
         * second, the two filters feel the same, so a session that goes from one pose to several
         * does not suddenly lag.
         */
        const val DEFAULT_MIN_CUTOFF = 0.05f

        /**
         * How hard the cutoff rises with speed, and the reason this filter is worth having.
         *
         * `cutoff = minCutoff + beta * speed`, with speed in body spans per second. A brisk arm
         * moves about one span a second, which 80 turns into a cutoff near 80 Hz: no lag at all. A
         * resting body stays near [DEFAULT_MIN_CUTOFF], which is where the jitter is filtered out.
         * The earlier default of 4, in frame units, left the skeleton a tenth of a second behind
         * every slow movement.
         */
        const val DEFAULT_BETA = 80f

        private const val MINIMUM_SCALE = 0.05f

        /** The derivative's own cutoff. 1 Hz is the value the paper uses and rarely needs changing. */
        private const val DERIVATIVE_CUTOFF = 1.0f

        private const val TAU = (2.0 * Math.PI).toFloat()
    }
}
