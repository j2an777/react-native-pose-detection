package com.posedetection.engine

/** A gap scales with the expected rate: 250 ms is a stall at 30 fps but the next frame at 4 fps. */
internal object Continuity {
    const val MINIMUM_GAP_MS = 200.0

    /** Two and a half intervals: one late frame still continues, two missed ones do not. */
    const val GAP_INTERVALS = 2.5

    fun maxGapMs(fps: Double): Double {
        if (!fps.isFinite() || fps <= 0.0) return MINIMUM_GAP_MS
        return maxOf(MINIMUM_GAP_MS, GAP_INTERVALS * MILLIS_PER_SECOND / fps)
    }

    private const val MILLIS_PER_SECOND = 1_000.0
}

/** One subject through a file's sampled frames: the file-side twin of the live view's own state. */
internal class PoseTrack(
    sampleFps: Int,
) {
    private val maxGapMs = Continuity.maxGapMs(sampleFps.toDouble())
    private var previousBox: PoseBox? = null
    private var previousMs = 0.0
    private var previousComX = Float.NaN
    private var previousComY = Float.NaN

    /** The seconds since the frame this one continues, or null when it starts the track over. */
    fun advance(
        box: PoseBox,
        timestampMs: Double,
    ): Float? {
        val previous = previousBox
        val elapsedMs = timestampMs - previousMs
        previousBox = box
        previousMs = timestampMs
        val continues =
            previous != null &&
                elapsedMs > 0.0 &&
                elapsedMs <= maxGapMs &&
                box.overlap(previous) >= PoseBox.SAME_BODY_OVERLAP
        if (!continues) {
            previousComX = Float.NaN
            previousComY = Float.NaN
            return null
        }
        return (elapsedMs / MILLIS_PER_SECOND).toFloat()
    }

    /** A sampled frame with nobody in it. Whoever appears next starts over. */
    fun lose() {
        previousBox = null
        previousComX = Float.NaN
        previousComY = Float.NaN
    }

    /** Normalized units per second. `NaN` on a restart: 0 would read as a body measured still. */
    fun velocity(
        comX: Float,
        comY: Float,
        elapsed: Float?,
        out: FloatArray,
        offset: Int,
    ) {
        if (elapsed == null) {
            out[offset] = Float.NaN
            out[offset + 1] = Float.NaN
        } else {
            out[offset] = (comX - previousComX) / elapsed
            out[offset + 1] = (comY - previousComY) / elapsed
        }
        previousComX = comX
        previousComY = comY
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000.0
    }
}
