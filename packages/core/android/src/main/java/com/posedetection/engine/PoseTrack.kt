package com.posedetection.engine

/**
 * Whether two frames describe one continuous movement, which smoothing and velocity both have to
 * know before they compare them.
 *
 * A gap is measured against the rate the frames were expected at, and is never shorter than
 * 200 ms: at 30 fps a frame that late is a stall, while at 4 fps it is simply the next frame. A
 * fixed 200 ms made every frame at a rate under 5 fps a first frame, with no velocity and no
 * smoothing.
 */
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

/**
 * One subject followed through a file's sampled frames. This is the file-side twin of the state the
 * live view keeps in its own fields.
 *
 * A frame continues the track unless it is the first one, follows a frame with nobody in it,
 * arrives after a gap (see [Continuity]), or shows a different body (see
 * [PoseBox.SAME_BODY_OVERLAP]). A frame that does not continue the track starts it over: nothing
 * measured across that boundary describes one movement.
 */
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

    /**
     * Center-of-mass velocity in normalized units per second, written into [out] at [offset]. NaN
     * when the frame started the track over, because the first frame of a movement has nothing to
     * differ from, and zero would read as a body that was measured and found to be still.
     */
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
