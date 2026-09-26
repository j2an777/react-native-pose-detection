package com.posedetection.detector

/**
 * How sure the model has to be before it calls something a body in a photo or a video, when the
 * caller has not said. One decision with `maxPoses` rather than a second one: 0.5 for a single
 * subject, which is MediaPipe's own, and 0.3 above that, which is where a second person actually
 * appears rather than the first person twice. See guides/files.md for the measurements.
 */
internal object StillConfidence {
    const val SINGLE = 0.5f
    const val SEVERAL = 0.3f

    fun forMaxPoses(maxPoses: Int): Float = if (maxPoses > 1) SEVERAL else SINGLE
}

/** What `detectOnImage` and `detectOnVideo` were asked for. Defaults from `guides/files.md`. */
internal class StaticOptions(
    val maxPoses: Int,
    /** Follows [maxPoses] unless the caller chose one, exactly as an export's does. */
    val minConfidence: Float,
    val angles: Boolean,
    val worldLandmarks: Boolean,
    val smoothing: Boolean,
    val fps: Int,
    val startMs: Long,
    val endMs: Long,
) {
    companion object {
        const val MAX_POSES_LIMIT = 5

        fun forImage(raw: Map<*, *>?): StaticOptions {
            val maxPoses = poses(raw?.get("maxPoses"))
            return StaticOptions(
                maxPoses = maxPoses,
                minConfidence = confidence(raw?.get("minConfidence"), maxPoses),
                angles = raw?.get("angles") as? Boolean ?: true,
                worldLandmarks = raw?.get("worldLandmarks") as? Boolean ?: false,
                // A single frame has nothing to smooth against, so this is off whatever was asked.
                smoothing = false,
                fps = 0,
                startMs = 0,
                endMs = 0,
            )
        }

        fun forVideo(raw: Map<*, *>?): StaticOptions {
            val maxPoses = poses(raw?.get("maxPoses"))
            return StaticOptions(
                maxPoses = maxPoses,
                minConfidence = confidence(raw?.get("minConfidence"), maxPoses),
                angles = raw?.get("angles") as? Boolean ?: true,
                worldLandmarks = raw?.get("worldLandmarks") as? Boolean ?: false,
                // JavaScript resolves `'auto'` against `maxPoses`. VIDEO mode already smooths one pose.
                smoothing = raw?.get("smoothing") as? Boolean ?: false,
                fps = finite(raw?.get("fps"))?.toInt()?.coerceAtLeast(1) ?: DEFAULT_FPS,
                startMs = finite(raw?.get("startMs"))?.toLong()?.coerceAtLeast(0L) ?: 0L,
                endMs = finite(raw?.get("endMs"))?.toLong() ?: -1L,
            )
        }

        private const val DEFAULT_FPS = 10

        private fun poses(value: Any?): Int = finite(value)?.toInt()?.coerceIn(1, MAX_POSES_LIMIT) ?: 1

        private fun confidence(
            value: Any?,
            maxPoses: Int,
        ): Float = finite(value)?.toFloat()?.coerceIn(0.1f, 1f) ?: StillConfidence.forMaxPoses(maxPoses)

        private fun finite(value: Any?): Double? = (value as? Number)?.toDouble()?.takeIf { it.isFinite() }
    }
}
