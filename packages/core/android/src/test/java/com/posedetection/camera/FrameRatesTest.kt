package com.posedetection.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameRatesTest {
    @Test
    fun `a fixed range at the target wins`() {
        assertEquals(30 to 30, FrameRates.choose(listOf(15 to 30, 30 to 30, 7 to 30, 15 to 15), 30))
    }

    @Test
    fun `otherwise the highest floor that still reaches the target`() {
        assertEquals(24 to 30, FrameRates.choose(listOf(7 to 30, 24 to 30, 15 to 30), 30))
    }

    @Test
    fun `a camera that cannot reach the target keeps its fastest`() {
        assertEquals(24 to 24, FrameRates.choose(listOf(15 to 15, 15 to 24, 24 to 24), 30))
    }

    @Test
    fun `only faster ranges leave the default alone`() {
        assertNull(FrameRates.choose(listOf(60 to 60, 30 to 60), 30))
        assertNull(FrameRates.choose(emptyList(), 30))
    }
}
