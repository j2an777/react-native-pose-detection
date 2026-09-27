package com.posedetection.engine

import com.posedetection.Skeleton

/** Reused and mutated in place: it is on the inference path, which must not allocate per frame. */
internal class FrameContext {
    var landmarks: FloatArray = EMPTY
    var previousLandmarks: FloatArray? = null

    /** `NaN` when there is no comparable previous frame, which makes every velocity unknown. */
    var elapsedSeconds = Float.NaN

    var comX = Float.NaN
    var comY = Float.NaN
    var comVelocityX = Float.NaN
    var comVelocityY = Float.NaN

    var frameWidth = 0
    var frameHeight = 0

    fun axis(
        joint: Int,
        axis: Int,
    ): Float = landmarks[joint * Skeleton.LANDMARK_STRIDE + axis]

    /** Normalized units per second, uncorrected for aspect to match thresholds. `NaN` when unknown. */
    fun velocity(
        joint: Int,
        axis: Int,
    ): Float {
        val previous = previousLandmarks ?: return Float.NaN
        if (elapsedSeconds.isNaN() || elapsedSeconds <= 0f) return Float.NaN

        val offset = joint * Skeleton.LANDMARK_STRIDE + axis
        return (landmarks[offset] - previous[offset]) / elapsedSeconds
    }

    private companion object {
        val EMPTY = FloatArray(0)
    }
}

/** Abstract, not sealed: tests subclass it to drive the state machine without real geometry. */
internal abstract class PoseCondition {
    abstract fun matches(frame: FrameContext): Boolean
}

internal const val NO_JOINT = -1
internal const val AXIS_X = 0
internal const val AXIS_Y = 1

/** `NaN` is an absent bound, which does not constrain, or an unmeasurable value, which never matches. */
internal fun withinBounds(
    value: Float,
    below: Float,
    above: Float,
    betweenMin: Float,
    betweenMax: Float,
): Boolean {
    if (value.isNaN()) return false
    if (!below.isNaN() && value >= below) return false
    if (!above.isNaN() && value <= above) return false
    // Inclusive, unlike below and above: `between` names the range to be in.
    if (!betweenMin.isNaN() && (value < betweenMin || value > betweenMax)) return false
    return true
}

internal class AngleCondition(
    private val proximal: Int,
    private val vertex: Int,
    private val distal: Int,
    private val below: Float,
    private val above: Float,
    private val betweenMin: Float,
    private val betweenMax: Float,
) : PoseCondition() {
    override fun matches(frame: FrameContext): Boolean {
        val value =
            Geometry.angleDegrees(
                frame.landmarks,
                proximal,
                vertex,
                distal,
                frame.frameWidth,
                frame.frameHeight,
            )
        return withinBounds(value, below, above, betweenMin, betweenMax)
    }
}

/** A bound naming a joint reads that joint in the same frame, so it holds at any camera distance. */
internal class LandmarkCondition(
    private val axis: Int,
    private val joint: Int,
    private val below: Float,
    private val belowJoint: Int,
    private val above: Float,
    private val aboveJoint: Int,
) : PoseCondition() {
    override fun matches(frame: FrameContext): Boolean {
        val value = frame.axis(joint, axis)
        val resolvedBelow = if (belowJoint == NO_JOINT) below else frame.axis(belowJoint, axis)
        val resolvedAbove = if (aboveJoint == NO_JOINT) above else frame.axis(aboveJoint, axis)
        return withinBounds(value, resolvedBelow, resolvedAbove, Float.NaN, Float.NaN)
    }
}

internal class VelocityCondition(
    private val axis: Int,
    /** [NO_JOINT] is `centerOfMass`, whose velocity is already computed for the wire. */
    private val joint: Int,
    private val below: Float,
    private val above: Float,
) : PoseCondition() {
    override fun matches(frame: FrameContext): Boolean {
        val value =
            when {
                joint != NO_JOINT -> frame.velocity(joint, axis)
                axis == AXIS_X -> frame.comVelocityX
                else -> frame.comVelocityY
            }
        return withinBounds(value, below, above, Float.NaN, Float.NaN)
    }
}

internal class VisibilityCondition(
    private val joint: Int,
    private val above: Float,
) : PoseCondition() {
    override fun matches(frame: FrameContext): Boolean = Geometry.visibility(frame.landmarks, joint) > above
}

internal class AllCondition(
    private val members: Array<PoseCondition>,
) : PoseCondition() {
    override fun matches(frame: FrameContext): Boolean {
        for (member in members) if (!member.matches(frame)) return false
        return true
    }
}

internal class AnyCondition(
    private val members: Array<PoseCondition>,
) : PoseCondition() {
    override fun matches(frame: FrameContext): Boolean {
        for (member in members) if (member.matches(frame)) return true
        return false
    }
}

/** What an unparseable condition becomes. Never matching beats matching for the wrong reason. */
internal object NeverCondition : PoseCondition() {
    override fun matches(frame: FrameContext): Boolean = false
}

internal class NotCondition(
    private val inner: PoseCondition,
) : PoseCondition() {
    override fun matches(frame: FrameContext): Boolean = !inner.matches(frame)
}
