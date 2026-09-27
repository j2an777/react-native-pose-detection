package com.posedetection.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilePacerTest {
    private var now = 0L
    private var heat = ThermalState.NOMINAL
    private var slept = 0L

    private fun pacer() =
        FilePacer(
            readThermal = { heat },
            nowMs = { now },
            sleepMs = { duration ->
                slept += duration
                now += duration
            },
        )

    @Test
    fun `no rest up to fair`() {
        val pacer = pacer()
        heat = ThermalState.FAIR
        now += 100
        assertTrue(pacer.rest { false })
        assertEquals(0L, slept)
    }

    @Test
    fun `serious rests as long as the work took`() {
        val pacer = pacer()
        heat = ThermalState.SERIOUS
        now += 120
        assertTrue(pacer.rest { false })
        assertEquals("half speed", 120L, slept)
    }

    @Test
    fun `critical waits until the heat has been lower for 30 seconds`() {
        val pacer = pacer()
        heat = ThermalState.CRITICAL
        val done =
            pacer.rest {
                // Cools five seconds into the pause; hysteresis holds it for thirty more.
                if (now >= 5_000) heat = ThermalState.FAIR
                false
            }
        assertTrue(done)
        assertTrue(now in 35_000L until 37_000L)
        assertEquals(ThermalState.FAIR, pacer.state)
    }

    @Test
    fun `a cancel during a pause is answered within one poll`() {
        val pacer = pacer()
        heat = ThermalState.CRITICAL
        assertFalse(pacer.rest { now >= 1_000 })
        assertTrue(now <= 1_000 + FilePacer.POLL_MS)
    }

    @Test
    fun `heat is read at most once a second`() {
        var reads = 0
        val pacer =
            FilePacer(
                readThermal = {
                    reads++
                    ThermalState.NOMINAL
                },
                nowMs = { now },
                sleepMs = {},
            )
        repeat(10) {
            now += 50
            pacer.rest { false }
        }
        assertEquals(1, reads)
    }
}
