package com.posedetection.engine

import com.posedetection.Skeleton
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/** Pure and allocation-free, over the flat landmark buffer. */
internal object Geometry {
    /** Degrees, 0 to 180. `NaN` when the triangle is degenerate: 0 would read as a folded joint. */
    fun angleDegrees(
        landmarks: FloatArray,
        proximal: Int,
        vertex: Int,
        distal: Int,
        frameWidth: Int,
        frameHeight: Int,
    ): Float {
        if (frameWidth <= 0 || frameHeight <= 0) return Float.NaN
        // x is normalized by width and y by height; uncorrected, angles skew by tens of degrees.
        val aspect = frameWidth.toFloat() / frameHeight.toFloat()

        val vx = landmarks[vertex * Skeleton.LANDMARK_STRIDE]
        val vy = landmarks[vertex * Skeleton.LANDMARK_STRIDE + 1]

        val ax = (landmarks[proximal * Skeleton.LANDMARK_STRIDE] - vx) * aspect
        val ay = landmarks[proximal * Skeleton.LANDMARK_STRIDE + 1] - vy
        val bx = (landmarks[distal * Skeleton.LANDMARK_STRIDE] - vx) * aspect
        val by = landmarks[distal * Skeleton.LANDMARK_STRIDE + 1] - vy

        val magnitude = sqrt((ax * ax + ay * ay) * (bx * bx + by * by))
        if (magnitude < EPSILON) return Float.NaN

        // Floating point can push this a hair outside [-1, 1], where acos returns NaN.
        val cosine = ((ax * bx + ay * by) / magnitude).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cosine).toDouble()).toFloat()
    }

    /** In projected screen pixels: taken before projection, it lands outside a mirrored joint. */
    fun bisectorRadians(
        proximalX: Float,
        proximalY: Float,
        vertexX: Float,
        vertexY: Float,
        distalX: Float,
        distalY: Float,
    ): Float {
        val ax = proximalX - vertexX
        val ay = proximalY - vertexY
        val bx = distalX - vertexX
        val by = distalY - vertexY

        val aLength = sqrt(ax * ax + ay * ay)
        val bLength = sqrt(bx * bx + by * by)
        if (aLength < EPSILON || bLength < EPSILON) return Float.NaN

        val sumX = ax / aLength + bx / bLength
        val sumY = ay / aLength + by / bLength
        if (abs(sumX) < EPSILON && abs(sumY) < EPSILON) return Float.NaN

        return kotlin.math.atan2(sumY, sumX)
    }

    fun visibility(
        landmarks: FloatArray,
        joint: Int,
    ): Float = landmarks[joint * Skeleton.LANDMARK_STRIDE + Skeleton.OFFSET_VISIBILITY]

    /** `NaN` when nothing is visible. Not aspect-corrected: it is compared with uncorrected positions. */
    fun centerOfMass(
        landmarks: FloatArray,
        out: FloatArray,
        offset: Int,
    ) {
        var x = 0f
        var y = 0f
        var total = 0f

        for (index in COM_JOINTS.indices) {
            val joint = COM_JOINTS[index]
            val base = joint * Skeleton.LANDMARK_STRIDE
            val weight = COM_WEIGHTS[index] * landmarks[base + Skeleton.OFFSET_VISIBILITY]
            if (weight <= 0f) continue
            x += landmarks[base] * weight
            y += landmarks[base + 1] * weight
            total += weight
        }

        if (total < EPSILON) {
            out[offset] = Float.NaN
            out[offset + 1] = Float.NaN
            return
        }
        out[offset] = x / total
        out[offset + 1] = y / total
    }

    /** Normalized and uncorrected like [centerOfMass]: it divides other normalized distances. */
    fun bodySpan(landmarks: FloatArray): Float {
        val shoulderX = midpoint(landmarks, Skeleton.LEFT_SHOULDER, Skeleton.RIGHT_SHOULDER, 0)
        val shoulderY = midpoint(landmarks, Skeleton.LEFT_SHOULDER, Skeleton.RIGHT_SHOULDER, 1)
        val ankleX = midpoint(landmarks, Skeleton.LEFT_ANKLE, Skeleton.RIGHT_ANKLE, 0)
        val ankleY = midpoint(landmarks, Skeleton.LEFT_ANKLE, Skeleton.RIGHT_ANKLE, 1)

        val dx = shoulderX - ankleX
        val dy = shoulderY - ankleY
        return sqrt(dx * dx + dy * dy)
    }

    private fun midpoint(
        landmarks: FloatArray,
        left: Int,
        right: Int,
        axis: Int,
    ): Float =
        (landmarks[left * Skeleton.LANDMARK_STRIDE + axis] + landmarks[right * Skeleton.LANDMARK_STRIDE + axis]) / 2f

    private val COM_JOINTS =
        intArrayOf(
            Skeleton.LEFT_HIP,
            Skeleton.RIGHT_HIP,
            Skeleton.LEFT_KNEE,
            Skeleton.RIGHT_KNEE,
            Skeleton.LEFT_ANKLE,
            Skeleton.RIGHT_ANKLE,
        )

    private val COM_WEIGHTS = floatArrayOf(0.25f, 0.25f, 0.1f, 0.1f, 0.15f, 0.15f)

    private const val EPSILON = 1e-6f
}

/** Normalized. Overlap across frames tells when the primary pose has become somebody else. */
internal data class PoseBox(
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
) {
    /** Intersection over union. */
    fun overlap(other: PoseBox): Float {
        val width = minOf(maxX, other.maxX) - maxOf(minX, other.minX)
        val height = minOf(maxY, other.maxY) - maxOf(minY, other.minY)
        if (width <= 0f || height <= 0f) return 0f
        val intersection = width * height
        val union = area() + other.area() - intersection
        return if (union > 0f) intersection / union else 0f
    }

    fun area(): Float = maxOf(0f, maxX - minX) * maxOf(0f, maxY - minY)

    companion object {
        /** Below this much overlap two consecutive primary poses are different people. */
        const val SAME_BODY_OVERLAP = 0.3f

        const val AREA_TIE_EPSILON = 1e-4f

        /** The largest box, ties to the most central. The live view's `primaryPose` must agree. */
        fun primary(boxes: List<PoseBox>): Int {
            var best = 0
            var bestArea = -1f
            var bestOffset = Float.MAX_VALUE
            for ((index, box) in boxes.withIndex()) {
                val area = box.area()
                val offset = abs((box.minX + box.maxX) / 2 - 0.5f) + abs((box.minY + box.maxY) / 2 - 0.5f)
                val better =
                    area > bestArea + AREA_TIE_EPSILON ||
                        (abs(area - bestArea) <= AREA_TIE_EPSILON && offset < bestOffset)
                if (better) {
                    best = index
                    bestArea = area
                    bestOffset = offset
                }
            }
            return best
        }

        fun of(landmarks: FloatArray): PoseBox {
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (joint in 0 until Skeleton.LANDMARK_COUNT) {
                val base = joint * Skeleton.LANDMARK_STRIDE
                minX = minOf(minX, landmarks[base])
                maxX = maxOf(maxX, landmarks[base])
                minY = minOf(minY, landmarks[base + 1])
                maxY = maxOf(maxY, landmarks[base + 1])
            }
            return PoseBox(minX, minY, maxX, maxY)
        }
    }
}

/**
 * MediaPipe answers in the unrotated buffer's frame whatever rotation it is given; this turns the
 * landmarks upright, as iOS gets them. [quarter] is clockwise quarter turns, `rotationDegrees / 90`.
 */
internal object Upright {
    fun x(
        x: Float,
        y: Float,
        quarter: Int,
    ): Float =
        when (quarter and 3) {
            1 -> 1f - y
            2 -> 1f - x
            3 -> y
            else -> x
        }

    fun y(
        x: Float,
        y: Float,
        quarter: Int,
    ): Float =
        when (quarter and 3) {
            1 -> x
            2 -> 1f - y
            3 -> 1f - x
            else -> y
        }

    /** A vector about the hips, which is what world landmarks are, turned the same way. */
    fun worldX(
        x: Float,
        y: Float,
        quarter: Int,
    ): Float =
        when (quarter and 3) {
            1 -> -y
            2 -> -x
            3 -> y
            else -> x
        }

    fun worldY(
        x: Float,
        y: Float,
        quarter: Int,
    ): Float =
        when (quarter and 3) {
            1 -> x
            2 -> -y
            3 -> -x
            else -> y
        }

    /** CameraX's clockwise degrees as quarter turns, whatever multiple of 90 it arrives as. */
    fun quarterOf(rotationDegrees: Int): Int = Math.floorMod(rotationDegrees / DEGREES_PER_QUARTER, QUARTERS)

    private const val DEGREES_PER_QUARTER = 90
    private const val QUARTERS = 4
}
