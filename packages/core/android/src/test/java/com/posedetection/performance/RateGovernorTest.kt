package com.posedetection.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rate model from `guides/performance.md`, the twin of the iOS suite. The first test is its
 * worked table, so a change that moves one of those numbers has to come here and say so.
 */
class RateGovernorTest {
    @Suppress("LongParameterList")
    private fun decide(
        profile: Profile = Profile.AUTO,
        policy: ThermalPolicy = ThermalPolicy.ADAPTIVE,
        thermal: ThermalState = ThermalState.NOMINAL,
        lowPower: Boolean = false,
        camera: Int = 30,
        p50: Float = 0f,
        fps: Int? = null,
    ) = RateGovernor.decide(RateRequest(profile, policy, thermal, lowPower, camera, p50, fps))

    @Test
    fun `the worked table holds`() {
        val rows =
            listOf(
                listOf(16, 30, 30, 15),
                listOf(20, 30, 30, 15),
                listOf(25, 30, 28, 15),
                listOf(30, 28, 23, 15),
                listOf(40, 21, 17, 12),
                listOf(60, 14, 11, 8),
            )
        for ((p50, nominal, fair, serious) in rows) {
            assertEquals("nominal at ${p50}ms", nominal, decide(p50 = p50.toFloat()).fps)
            assertEquals("fair at ${p50}ms", fair, decide(thermal = ThermalState.FAIR, p50 = p50.toFloat()).fps)
            assertEquals(
                "serious at ${p50}ms",
                serious,
                decide(thermal = ThermalState.SERIOUS, p50 = p50.toFloat()).fps,
            )
        }
    }

    @Test
    fun `an unmeasured device runs at the camera's rate rather than a guess`() {
        assertEquals(RateDecision(30, LimitedBy.CAMERA), decide())
    }

    @Test
    fun `the reason names the constraint that bound`() {
        assertEquals(LimitedBy.CAMERA, decide(p50 = 16f).limitedBy)
        assertEquals(LimitedBy.DEVICE, decide(p50 = 30f).limitedBy)
        assertEquals(LimitedBy.THERMAL, decide(thermal = ThermalState.FAIR, p50 = 30f).limitedBy)
        assertEquals(LimitedBy.THERMAL, decide(thermal = ThermalState.SERIOUS, p50 = 16f).limitedBy)
        assertEquals(LimitedBy.PROFILE, decide(profile = Profile.BALANCED, p50 = 16f).limitedBy)
    }

    @Test
    fun `critical heat pauses detection`() {
        val paused = decide(thermal = ThermalState.CRITICAL)
        assertTrue(paused.detectionPaused)
        assertEquals(LimitedBy.THERMAL, paused.limitedBy)
    }

    @Test
    fun `the camera is the ceiling even for an explicit target`() {
        assertEquals(RateDecision(30, LimitedBy.CAMERA), decide(fps = 60))
        assertEquals(RateDecision(24, LimitedBy.CAMERA), decide(camera = 24, p50 = 10f))
    }

    @Test
    fun `an explicit target is capped at what the device can finish`() {
        assertEquals(RateDecision(24, LimitedBy.TARGET), decide(p50 = 16f, fps = 24))
        // 1000 / 50 = 20: asking for 30 would only queue frames behind each other.
        assertEquals(RateDecision(20, LimitedBy.DEVICE), decide(p50 = 50f, fps = 30))
    }

    @Test
    fun `fair heat leaves an explicit target alone and serious heat halves it`() {
        assertEquals(30, decide(thermal = ThermalState.FAIR, p50 = 16f, fps = 30).fps)
        assertEquals(RateDecision(15, LimitedBy.THERMAL), decide(thermal = ThermalState.SERIOUS, p50 = 16f, fps = 30))
    }

    @Test
    fun `the floor holds a slow device up but not a hot one`() {
        assertEquals(RateDecision(RateGovernor.FLOOR_FPS, LimitedBy.DEVICE), decide(p50 = 200f))
        assertEquals(RateDecision(3, LimitedBy.THERMAL), decide(thermal = ThermalState.FAIR, p50 = 200f))
    }

    @Test
    fun `low power caps only the governed rate`() {
        assertEquals(RateDecision(24, LimitedBy.LOW_POWER), decide(lowPower = true, p50 = 16f))
        assertEquals("an explicit target has already decided", 30, decide(lowPower = true, p50 = 16f, fps = 30).fps)
        assertEquals(30, decide(profile = Profile.UNRESTRICTED, lowPower = true, p50 = 16f).fps)
    }

    @Test
    fun `profiles are rows of the same model`() {
        assertEquals(24, decide(profile = Profile.BALANCED, p50 = 16f).fps)
        assertEquals(15, decide(profile = Profile.EFFICIENT, p50 = 16f).fps)
        assertEquals("0.95 of a 30ms device is still 31", 30, decide(profile = Profile.QUALITY, p50 = 30f).fps)
        assertEquals(30, decide(profile = Profile.UNRESTRICTED, p50 = 30f).fps)
        assertEquals(
            "efficient is the one profile that treats warmth as a reason",
            RateDecision(11, LimitedBy.THERMAL),
            decide(profile = Profile.EFFICIENT, thermal = ThermalState.FAIR, p50 = 16f),
        )
    }

    @Test
    fun `the policy and the profile decide which heat counts`() {
        assertEquals(30, decide(profile = Profile.UNRESTRICTED, thermal = ThermalState.SERIOUS, p50 = 16f).fps)
        assertTrue(decide(profile = Profile.UNRESTRICTED, thermal = ThermalState.CRITICAL).detectionPaused)
        assertEquals(30, decide(policy = ThermalPolicy.CRITICAL_ONLY, thermal = ThermalState.SERIOUS, p50 = 16f).fps)
        assertTrue(decide(policy = ThermalPolicy.CRITICAL_ONLY, thermal = ThermalState.CRITICAL).detectionPaused)
        assertFalse(decide(policy = ThermalPolicy.OFF, thermal = ThermalState.CRITICAL).detectionPaused)
    }

    @Test
    fun `auto preview follows memory and never opens at 480p`() {
        fun auto(memory: Float) = GeometryResolver.resolve(Profile.AUTO, "auto", "auto", memory)
        assertEquals("a phone sold as 6 GB", CameraGeometry("1080p", "480p"), auto(5.6f))
        assertEquals("720p", auto(3.6f).preview)
        assertEquals("720p", auto(1.9f).preview)
    }

    @Test
    fun `explicit presets win and profiles set their own`() {
        assertEquals(CameraGeometry("720p", "360p"), GeometryResolver.resolve(Profile.EFFICIENT, "auto", "auto", 8f))
        assertEquals("1080p", GeometryResolver.resolve(Profile.QUALITY, "auto", "auto", 2f).preview)
        assertEquals(CameraGeometry("1080p", "720p"), GeometryResolver.resolve(Profile.EFFICIENT, "1080p", "720p", 2f))
    }

    @Test
    fun `idle comes in two steps and unrestricted has none`() {
        val idle = IdleRates(first = 12, deep = 5)
        assertNull(idle.rate(1_500))
        assertEquals(12, idle.rate(2_500))
        assertEquals(5, idle.rate(25_000))
        assertNull(Budgets.of(Profile.UNRESTRICTED).idle)
    }

    @Test
    fun `heat is adopted at once and cooling only once it has held`() {
        val heat = ThermalHysteresis()
        assertTrue(heat.update(ThermalState.SERIOUS, 0))
        assertEquals(ThermalState.SERIOUS, heat.state)

        assertFalse(heat.update(ThermalState.FAIR, 1_000))
        assertFalse(heat.update(ThermalState.NOMINAL, 20_000))
        assertEquals("cooler readings are not trusted yet", ThermalState.SERIOUS, heat.state)

        assertTrue(heat.update(ThermalState.NOMINAL, 31_000))
        assertEquals("the warmest reading seen while cooling", ThermalState.FAIR, heat.state)
    }

    @Test
    fun `a reading back at the current level restarts the cooling clock`() {
        val heat = ThermalHysteresis()
        heat.update(ThermalState.FAIR, 0)
        heat.update(ThermalState.NOMINAL, 1_000)
        assertFalse(heat.update(ThermalState.FAIR, 20_000))
        assertFalse(heat.update(ThermalState.NOMINAL, 32_000))
        assertTrue(heat.update(ThermalState.NOMINAL, 62_000))
        assertEquals(ThermalState.NOMINAL, heat.state)
    }
}
