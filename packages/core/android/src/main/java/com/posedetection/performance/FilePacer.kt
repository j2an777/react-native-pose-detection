package com.posedetection.performance

import android.os.SystemClock
import com.posedetection.LogCategory
import com.posedetection.PoseLog

/**
 * How a video job answers heat: full speed up to `fair`, half speed at `serious`, and paused at
 * `critical` until the device cools. A file has no deadline, so heat costs it time and never
 * quality. The same frames are detected, only later.
 *
 * Half speed is a rest as long as the work before it. Readings go through the live view's
 * [ThermalHysteresis], so a job slows as soon as it heats and speeds up only after 30 s cooler,
 * rather than flapping at the boundary. Reads are throttled to one a second, which leaves [rest]
 * cheap enough to call after every frame.
 */
internal class FilePacer(
    private val readThermal: () -> ThermalState,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val sleepMs: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val hysteresis = ThermalHysteresis()
    private var lastReadMs: Long? = null
    private var workStartMs = nowMs()

    /** The heat this job is acting on, after hysteresis. */
    val state: ThermalState
        get() = hysteresis.state

    /**
     * Called between two units of work. Rests as long as the work took at `serious`, and waits out
     * `critical`. Returns false when the job was cancelled while it rested.
     */
    fun rest(isCancelled: () -> Boolean): Boolean {
        val now = nowMs()
        val worked = (now - workStartMs).coerceAtLeast(0L)
        read(now)

        if (hysteresis.state == ThermalState.SERIOUS && !wait(worked, isCancelled)) return false
        if (hysteresis.state == ThermalState.CRITICAL) {
            PoseLog.info(LogCategory.ENGINE) { "the device is critically hot, the file job is paused until it cools" }
            while (hysteresis.state == ThermalState.CRITICAL) {
                if (!wait(POLL_MS, isCancelled)) return false
                read(nowMs())
            }
            PoseLog.info(LogCategory.ENGINE) { "the device has cooled to ${hysteresis.state}, the file job resumes" }
        }
        workStartMs = nowMs()
        return !isCancelled()
    }

    private fun read(now: Long) {
        val last = lastReadMs
        if (last != null && now - last < READ_INTERVAL_MS) return
        lastReadMs = now
        hysteresis.update(readThermal(), now)
    }

    /** Sleeps in short slices so a cancel is answered within one of them. */
    private fun wait(
        durationMs: Long,
        isCancelled: () -> Boolean,
    ): Boolean {
        var remaining = durationMs
        while (remaining > 0) {
            if (isCancelled()) return false
            val slice = minOf(remaining, POLL_MS)
            sleepMs(slice)
            remaining -= slice
        }
        return !isCancelled()
    }

    companion object {
        const val READ_INTERVAL_MS = 1_000L

        /** How often a paused job looks at the heat again, and the longest a cancel waits to be noticed. */
        const val POLL_MS = 250L
    }
}
