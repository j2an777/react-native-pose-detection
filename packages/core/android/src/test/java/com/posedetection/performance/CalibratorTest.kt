package com.posedetection.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The twin of the iOS suite. */
class CalibratorTest {
    private val model = "pose_landmarker_full.task"

    private class MemoryStore : CalibrationStore {
        val entries = HashMap<String, String>()

        override fun read(key: String): String? = entries[key]

        override fun write(
            key: String,
            value: String,
        ) {
            entries[key] = value
        }
    }

    private val store = MemoryStore()

    private fun make(
        memoryGiB: Float = 6f,
        cores: Int = 8,
    ) = Calibrator(store, "test|34", { memoryGiB }, { cores })

    /** Returns whether anything moved, and the time after the last sample. */
    private fun feed(
        calibrator: Calibrator,
        ms: Float,
        count: Int,
        from: Long,
        step: Long = 33,
    ): Pair<Boolean, Long> {
        var moved = false
        var now = from
        repeat(count) {
            if (calibrator.record(ms, now)) moved = true
            now += step
        }
        return moved to now
    }

    @Test
    fun `nothing measured means a device tier and no median`() {
        val calibrator = make(memoryGiB = 7.5f)
        calibrator.start(model)
        assertEquals(DeviceTier.HIGH, calibrator.tier)
        assertEquals("an unknown device, which the governor runs at the camera's rate", 0f, calibrator.p50InferenceMs)
        assertEquals(Calibrator.Phase.CALIBRATING, calibrator.phase)

        val middling = make(memoryGiB = 3.6f, cores = 6)
        middling.start(model)
        assertEquals("no step down on top of a guess", DeviceTier.MEDIUM, middling.tier)
    }

    @Test
    fun `the first estimate lands after fifteen frames`() {
        val calibrator = make()
        calibrator.start(model)

        val (warm, end) = feed(calibrator, 20f, 14, 1_000)
        assertFalse("14 frames is not an estimate", warm)
        assertEquals(0f, calibrator.p50InferenceMs)

        val (moved, _) = feed(calibrator, 20f, 1, end)
        assertTrue(moved)
        assertEquals(20f, calibrator.p50InferenceMs)
        assertEquals(DeviceTier.HIGH, calibrator.tier)
    }

    @Test
    fun `the median shrugs off one slow frame`() {
        val calibrator = make()
        calibrator.start(model)
        val (_, end) = feed(calibrator, 20f, 14, 1_000)
        feed(calibrator, 400f, 1, end)
        assertEquals(20f, calibrator.p50InferenceMs)
    }

    @Test
    fun `a steady device settles instead of twitching`() {
        val calibrator = make()
        calibrator.start(model)

        val (_, first) = feed(calibrator, 20f, 15, 1_000)
        // Past the cooldown and a full window, so nothing is left to move.
        val (settled, second) = feed(calibrator, 20f, 120, first)
        assertTrue("settling is reported once so it can be persisted", settled)
        assertEquals(Calibrator.Phase.SETTLED, calibrator.phase)

        val (wobbled, _) = feed(calibrator, 21f, 120, second)
        assertFalse("a one millisecond wobble is inside the deadband", wobbled)
        assertEquals("the published median did not chase it", 20f, calibrator.p50InferenceMs)
    }

    @Test
    fun `a loaded device is walked down to what it costs`() {
        val calibrator = make()
        calibrator.start(model)
        val (_, end) = feed(calibrator, 20f, 180, 1_000)

        val (moved, _) = feed(calibrator, 60f, 180, end)
        assertTrue(moved)
        assertEquals(DeviceTier.LOW, calibrator.tier)
        assertEquals(60f, calibrator.p50InferenceMs)
    }

    @Test
    fun `a restart on the same model keeps the measurement`() {
        val calibrator = make()
        calibrator.start(model)
        feed(calibrator, 20f, 15, 1_000)
        assertEquals(20f, calibrator.p50InferenceMs)

        calibrator.start(model)
        assertEquals("a camera restart is not a new device", 20f, calibrator.p50InferenceMs)

        calibrator.start("pose_landmarker_lite.task")
        assertEquals("a different model is a different cost", 0f, calibrator.p50InferenceMs)
    }

    @Test
    fun `the second launch starts where the first one finished`() {
        val first = make()
        first.start(model)
        feed(first, 20f, 300, 1_000)
        assertEquals(Calibrator.Phase.SETTLED, first.phase)
        first.persist()

        val second = make()
        second.start(model)
        assertEquals(Calibrator.Phase.CACHED, second.phase)
        assertEquals(DeviceTier.HIGH, second.tier)
        assertEquals(20f, second.p50InferenceMs)
    }

    @Test
    fun `a guess is not persisted`() {
        val first = make()
        first.start(model)
        feed(first, 20f, 15, 1_000)
        first.persist()

        val second = make()
        second.start(model)
        assertEquals("one estimate is not a settled measurement", 0f, second.p50InferenceMs)
    }

    @Test
    fun `the gpu verdict is remembered on its own`() {
        val first = make()
        first.start(model)
        assertNull(first.gpuVerdict)
        first.recordGpuVerdict(false)

        val second = make()
        second.start(model)
        assertEquals(false, second.gpuVerdict)
        assertEquals("a verdict without a measurement is not a cached rate", Calibrator.Phase.CALIBRATING, second.phase)
    }

    @Test
    fun `a file job's verdict reaches the camera and leaves its measurement alone`() {
        val camera = make()
        camera.start(model)
        feed(camera, 20f, 300, 1_000)
        camera.persist()
        assertNull(make().cachedGpu(model))

        make().storeGpu(true, model)
        assertEquals(true, make().cachedGpu(model))

        val next = make()
        next.start(model)
        assertEquals(true, next.gpuVerdict)
        assertEquals("the measurement survives the file job's write", 20f, next.p50InferenceMs)
    }

    @Test
    fun `a camera that never probed keeps the verdict a file job recorded`() {
        val camera = make()
        camera.start(model)
        // Recorded while the camera runs, so the camera's own copy of the verdict is still null.
        make().storeGpu(false, model)
        feed(camera, 20f, 300, 1_000)
        assertNull(camera.gpuVerdict)
        camera.persist()
        assertEquals(false, make().cachedGpu(model))
    }
}
