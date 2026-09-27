package com.posedetection.engine

internal enum class TriggerEmit {
    ENTER,
    EXIT,
    CYCLE,
    WHILE,
    ;

    companion object {
        fun from(value: String?): TriggerEmit =
            when (value) {
                "exit" -> EXIT
                "cycle" -> CYCLE
                "while" -> WHILE
                else -> ENTER
            }
    }
}

internal class TriggerSpec(
    val id: String,
    val enter: PoseCondition,
    /** Defaults to not-`enter`, or a trigger with no `exit` could never return to idle. */
    val exit: PoseCondition,
    val emit: TriggerEmit,
    val debounceMs: Long,
    val minDurationMs: Long,
    val snapshot: Boolean,
    val throttleMs: Long,
)

/** One fired trigger. Scalars only: a frame cannot ride an event, see ADR 0009. */
internal class TriggerFiring(
    val id: String,
    val phase: String,
    val count: Int,
    val timestampMs: Double,
    val durationMs: Double?,
    val wantsSnapshot: Boolean,
)

/** The state machine from `guides/reference/trigger-schema.md`, one per trigger. */
internal class TriggerRuntime(
    val spec: TriggerSpec,
    initialCount: Int = 0,
) {
    var active = false
        private set

    /** Completed cycles. Survives a props update and a camera switch; only unmount resets it. */
    var count = initialCount
        private set

    private var holdSince = 0L
    private var activeSince = 0L
    private var lastFireMs = 0L
    private var lastWhileMs = 0L

    /** Breaks a hold but keeps the active state: stepping out mid-rep neither ends nor abandons it. */
    fun onPoseLost() {
        holdSince = 0L
    }

    fun evaluate(
        frame: FrameContext,
        nowMs: Long,
    ): TriggerFiring? = if (active) evaluateActive(frame, nowMs) else evaluateIdle(frame, nowMs)

    private fun evaluateIdle(
        frame: FrameContext,
        nowMs: Long,
    ): TriggerFiring? {
        if (!spec.enter.matches(frame)) {
            holdSince = 0L
            return null
        }

        if (holdSince == 0L) holdSince = nowMs
        if (nowMs - holdSince < spec.minDurationMs) return null
        // Debounce suppresses re-entry, not the hold, which keeps being measured.
        if (lastFireMs != 0L && nowMs - lastFireMs < spec.debounceMs) return null

        active = true
        activeSince = nowMs
        holdSince = 0L
        lastWhileMs = 0L

        if (spec.emit != TriggerEmit.ENTER) return null
        lastFireMs = nowMs
        return TriggerFiring(spec.id, "enter", count, frameTimestamp(nowMs), null, spec.snapshot)
    }

    private fun evaluateActive(
        frame: FrameContext,
        nowMs: Long,
    ): TriggerFiring? {
        if (spec.exit.matches(frame)) {
            if (holdSince == 0L) holdSince = nowMs
            if (nowMs - holdSince < spec.minDurationMs) return null

            active = false
            holdSince = 0L
            count += 1

            return when (spec.emit) {
                TriggerEmit.CYCLE -> {
                    lastFireMs = nowMs
                    TriggerFiring(
                        spec.id,
                        "cycle",
                        count,
                        frameTimestamp(nowMs),
                        (nowMs - activeSince).toDouble(),
                        spec.snapshot,
                    )
                }

                TriggerEmit.EXIT -> {
                    lastFireMs = nowMs
                    TriggerFiring(spec.id, "exit", count, frameTimestamp(nowMs), null, spec.snapshot)
                }

                else -> {
                    null
                }
            }
        }

        holdSince = 0L
        if (spec.emit != TriggerEmit.WHILE) return null
        // `enter` must hold, not just `exit` fail: between the two thresholds this must not fire.
        if (!spec.enter.matches(frame)) return null
        if (lastWhileMs != 0L && nowMs - lastWhileMs < spec.throttleMs) return null

        lastWhileMs = nowMs
        lastFireMs = nowMs
        return TriggerFiring(spec.id, "enter", count, frameTimestamp(nowMs), null, spec.snapshot)
    }

    private fun frameTimestamp(nowMs: Long): Double = nowMs.toDouble()
}

internal class TriggerEngine {
    /** Spans the whole [evaluate], so a rep ending during `setTriggers` on main carries over. */
    private val lock = Any()

    /** Volatile as well, for [isEmpty], the one read taken without the lock. */
    @Volatile
    private var runtimes: Array<TriggerRuntime> = emptyArray()

    val isEmpty: Boolean
        get() = runtimes.isEmpty()

    fun setTriggers(specs: List<TriggerSpec>) {
        synchronized(lock) {
            val previous = runtimes
            runtimes =
                Array(specs.size) { index ->
                    val spec = specs[index]
                    val carried = previous.firstOrNull { it.spec.id == spec.id }
                    TriggerRuntime(spec, carried?.count ?: 0)
                }
        }
    }

    fun onPoseLost() {
        synchronized(lock) {
            for (runtime in runtimes) runtime.onPoseLost()
        }
    }

    /** Appends to [into] so a frame that fires nothing allocates nothing. */
    fun evaluate(
        frame: FrameContext,
        nowMs: Long,
        into: MutableList<TriggerFiring>,
    ) {
        synchronized(lock) {
            for (runtime in runtimes) {
                val firing = runtime.evaluate(frame, nowMs) ?: continue
                into.add(firing)
            }
        }
    }
}
