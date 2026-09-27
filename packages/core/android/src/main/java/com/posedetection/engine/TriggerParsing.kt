package com.posedetection.engine

import com.posedetection.LogCategory
import com.posedetection.PoseLog
import com.posedetection.Skeleton

/** Lenient: JavaScript already validated, so what cannot be read is logged and never matches. */
internal fun parseTriggers(raw: List<*>?): List<TriggerSpec> {
    if (raw.isNullOrEmpty()) return emptyList()

    val specs = ArrayList<TriggerSpec>(raw.size)
    for (entry in raw) {
        val map = entry as? Map<*, *> ?: continue
        val id = map["id"] as? String ?: continue
        val enter = parseCondition(map["enter"])
        val exit = map["exit"]?.let(::parseCondition) ?: NotCondition(enter)

        specs.add(
            TriggerSpec(
                id = id,
                enter = enter,
                exit = exit,
                emit = TriggerEmit.from(map["emit"] as? String),
                debounceMs = duration(map["debounceMs"], 0L),
                minDurationMs = duration(map["minDurationMs"], 0L),
                snapshot = map["snapshot"] as? Boolean ?: false,
                throttleMs = duration(map["throttleMs"], DEFAULT_WHILE_THROTTLE_MS, floor = 1L),
            ),
        )
    }
    return specs
}

/** [floor] is 1 for `throttleMs`, where 0 would fire every frame. Debounce and minDuration allow 0. */
internal fun duration(
    value: Any?,
    fallback: Long,
    floor: Long = 0L,
): Long =
    // NaN and the infinities fall back, as on iOS: toLong() would turn them into 0 and Long.MAX_VALUE.
    (value as? Number)
        ?.toDouble()
        ?.takeIf { it.isFinite() }
        ?.toLong()
        ?.coerceAtLeast(floor) ?: fallback

internal fun parseCondition(raw: Any?): PoseCondition {
    val map =
        raw as? Map<*, *> ?: run {
            PoseLog.warn(LogCategory.TRIGGERS) { "a condition was not an object, it will never match" }
            return NeverCondition
        }

    (map["all"] as? List<*>)?.let { return AllCondition(parseMembers(it)) }
    (map["any"] as? List<*>)?.let { return AnyCondition(parseMembers(it)) }

    (map["angle"] as? String)?.let { joint ->
        val triple =
            Skeleton.angleTriple(joint) ?: run {
                PoseLog.warn(LogCategory.TRIGGERS) { "$joint has no angle, its condition will never match" }
                return NeverCondition
            }
        val between = map["between"] as? List<*>
        return AngleCondition(
            proximal = triple[0],
            vertex = triple[1],
            distal = triple[2],
            below = bound(map["below"]),
            above = bound(map["above"]),
            betweenMin = bound(between?.getOrNull(0)),
            betweenMax = bound(between?.getOrNull(1)),
        )
    }

    (map["landmarkX"] as? String)?.let { return landmarkCondition(map, it, AXIS_X) }
    (map["landmarkY"] as? String)?.let { return landmarkCondition(map, it, AXIS_Y) }
    (map["velocityX"] as? String)?.let { return velocityCondition(map, it, AXIS_X) }
    (map["velocityY"] as? String)?.let { return velocityCondition(map, it, AXIS_Y) }

    (map["visibility"] as? String)?.let { joint ->
        val index = jointIndex(joint) ?: return NeverCondition
        return VisibilityCondition(index, bound(map["above"]))
    }

    PoseLog.warn(LogCategory.TRIGGERS) { "a condition named no measurement, it will never match" }
    return NeverCondition
}

internal fun parseMembers(raw: List<*>): Array<PoseCondition> = Array(raw.size) { index -> parseCondition(raw[index]) }

internal fun landmarkCondition(
    map: Map<*, *>,
    joint: String,
    axis: Int,
): PoseCondition {
    val index = jointIndex(joint) ?: return NeverCondition
    val below = map["below"]
    val above = map["above"]

    return LandmarkCondition(
        axis = axis,
        joint = index,
        below = bound(below),
        belowJoint = (below as? String)?.let { jointIndex(it) } ?: NO_JOINT,
        above = bound(above),
        aboveJoint = (above as? String)?.let { jointIndex(it) } ?: NO_JOINT,
    )
}

internal fun velocityCondition(
    map: Map<*, *>,
    subject: String,
    axis: Int,
): PoseCondition {
    val index = if (subject == "centerOfMass") NO_JOINT else jointIndex(subject) ?: return NeverCondition
    return VelocityCondition(axis, index, bound(map["below"]), bound(map["above"]))
}

internal fun jointIndex(name: String): Int? {
    val index = Skeleton.indexOf(name)
    if (index >= 0) return index
    PoseLog.warn(LogCategory.TRIGGERS) { "$name is not a joint, its condition will never match" }
    return null
}

/** `NaN` when absent, see [withinBounds]. */
internal fun bound(value: Any?): Float = (value as? Number)?.toFloat() ?: Float.NaN

internal const val DEFAULT_WHILE_THROTTLE_MS = 250L
