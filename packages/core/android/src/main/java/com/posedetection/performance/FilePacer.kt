package com.posedetection.performance

import android.os.SystemClock
import com.posedetection.LogCategory
import com.posedetection.PoseLog

/** Heat costs a file job time, never quality: half speed at `serious`, paused at `critical`. */
internal class FilePacer(
    private val readThermal: () -> ThermalState,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val sleepMs: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val hysteresis = ThermalHysteresis()
    private var lastReadMs: Long? = null
    private var workStartMs = nowMs()

    val state: ThermalState
        get() = hysteresis.state

    /** Rests as long as the work took at `serious`, waits out `critical`; false if cancelled. */
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
        /** Keeps [rest] cheap enough to call after every frame. */
        const val READ_INTERVAL_MS = 1_000L

        /** How often a paused job rechecks the heat, and the longest a cancel goes unnoticed. */
        const val POLL_MS = 250L
    }
}
