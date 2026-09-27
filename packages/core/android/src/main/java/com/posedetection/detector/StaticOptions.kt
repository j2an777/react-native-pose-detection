package com.posedetection.detector

/** 0.3 is where a second person appears without the first counting twice; see guides/files.md. */
internal object StillConfidence {
    const val SINGLE = 0.5f
    const val SEVERAL = 0.3f

    fun forMaxPoses(maxPoses: Int): Float = if (maxPoses > 1) SEVERAL else SINGLE
}

/** Defaults as documented in guides/files.md. */
internal class StaticOptions(
    val maxPoses: Int,
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
                // One frame has nothing to smooth against.
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
                // Absent is off: JavaScript resolves 'auto' against maxPoses.
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
