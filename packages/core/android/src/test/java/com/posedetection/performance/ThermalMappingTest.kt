package com.posedetection.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Raw statuses as documented: 0 NONE, 1 LIGHT, 2 MODERATE, 3 SEVERE, 4 CRITICAL, 5 EMERGENCY, 6 SHUTDOWN. */
class ThermalMappingTest {
    @Test
    fun `light throttling is not heat worth acting on`() {
        assertEquals(ThermalState.NOMINAL, ThermalMapping.fromStatus(0))
        assertEquals(ThermalState.NOMINAL, ThermalMapping.fromStatus(1))
    }

    @Test
    fun `severe halves the rate instead of pausing detection`() {
        assertEquals(ThermalState.FAIR, ThermalMapping.fromStatus(2))
        assertEquals(ThermalState.SERIOUS, ThermalMapping.fromStatus(3))
    }

    @Test
    fun `only critical and hotter pause`() {
        assertEquals(ThermalState.CRITICAL, ThermalMapping.fromStatus(4))
        assertEquals(ThermalState.CRITICAL, ThermalMapping.fromStatus(5))
        assertEquals(ThermalState.CRITICAL, ThermalMapping.fromStatus(6))
    }

    @Test
    fun `the forecast backs off before the status does`() {
        assertEquals(ThermalState.NOMINAL, ThermalMapping.fromHeadroom(0.6f))
        assertEquals(ThermalState.FAIR, ThermalMapping.fromHeadroom(0.85f))
        assertEquals(ThermalState.SERIOUS, ThermalMapping.fromHeadroom(0.97f))
        assertEquals(ThermalState.SERIOUS, ThermalMapping.fromHeadroom(1.2f))
    }

    @Test
    fun `a NaN forecast keeps the last reading`() {
        assertNull(ThermalMapping.fromHeadroom(Float.NaN))
    }
}
