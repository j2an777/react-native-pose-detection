package com.posedetection.engine

import com.posedetection.Skeleton
import kotlin.math.abs

/** The One-Euro filter (Casiez et al., CHI 2012), in place over the landmark buffer. */
internal class OneEuroFilter {
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
     * [elapsedSeconds] is the real interval, not a nominal one; `NaN` or non-positive restarts here.
     * [scaleX] and [scaleY] are the body span per axis, making speeds spans per second; z uses x's.
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

    /** A half-visible body's span can collapse to nothing, which would read as infinite speed. */
    private fun usableScale(scale: Float): Float = if (scale.isFinite()) maxOf(scale, MINIMUM_SCALE) else 1f

    private fun alpha(
        cutoff: Float,
        elapsedSeconds: Float,
    ): Float {
        val timeConstant = 1f / (TAU * cutoff)
        return 1f / (1f + timeConstant / elapsedSeconds)
    }

    companion object {
        /** x, y, z. Not visibility: smoothed, it keeps a joint that left the frame looking present. */
        private const val AXES = 3

        /** Hz. With [DEFAULT_BETA], MediaPipe's own single-pose values, in the same body-span units. */
        const val DEFAULT_MIN_CUTOFF = 0.05f

        /** A brisk arm, about one body span a second, lifts the cutoff to about 80 Hz: no lag. */
        const val DEFAULT_BETA = 80f

        private const val MINIMUM_SCALE = 0.05f

        /** Hz, the paper's value. */
        private const val DERIVATIVE_CUTOFF = 1.0f

        private const val TAU = (2.0 * Math.PI).toFloat()
    }
}
