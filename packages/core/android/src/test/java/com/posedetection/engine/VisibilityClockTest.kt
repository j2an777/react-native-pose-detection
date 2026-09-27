package com.posedetection.engine

import com.posedetection.Skeleton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibilityClockTest {
    private val clock = VisibilityClock()

    /** MediaPipe's own filter, per frame: the first value passes through, then 0.1 of each new one. */
    private class MediaPipeFilter {
        private var value = Float.NaN

        fun apply(model: Float): Float {
            value =
                if (value.isNaN()) {
                    model
                } else {
                    VisibilityClock.MEDIAPIPE_WEIGHT * model +
                        (1f - VisibilityClock.MEDIAPIPE_WEIGHT) * value
                }
            return value
        }
    }

    private fun frame(visibility: Float): FloatArray {
        val landmarks = FloatArray(Skeleton.LANDMARK_COUNT * Skeleton.LANDMARK_STRIDE)
        for (joint in 0 until Skeleton.LANDMARK_COUNT) {
            landmarks[joint * Skeleton.LANDMARK_STRIDE + Skeleton.OFFSET_VISIBILITY] = visibility
        }
        return landmarks
    }

    private fun visibility(landmarks: FloatArray): Float = landmarks[Skeleton.OFFSET_VISIBILITY]

    /** Feeds [models] through MediaPipe's filter at [intervalMs] and then the clock, returning what comes out. */
    private fun run(
        models: List<Float>,
        intervalMs: Double,
    ): List<Float> {
        val mediaPipe = MediaPipeFilter()
        return models.mapIndexed { index, model ->
            val landmarks = frame(mediaPipe.apply(model))
            clock.apply(landmarks, index * intervalMs)
            visibility(landmarks)
        }
    }

    @Test
    fun `at 30 fps the weight is MediaPipe's own`() {
        assertEquals(0.1f, VisibilityClock.weight(VisibilityClock.REFERENCE_MS), 1e-6f)
        assertEquals(0f, VisibilityClock.weight(0.0), 0f)
    }

    @Test
    fun `at 30 fps nothing changes`() {
        val models = listOf(0.1f, 0.9f, 0.9f, 0.4f, 0.95f, 0.95f, 0.2f, 0.7f)
        val mediaPipe = MediaPipeFilter()
        val expected = models.map(mediaPipe::apply)

        val clocked = run(models, VisibilityClock.REFERENCE_MS)

        for (index in models.indices) assertEquals(expected[index], clocked[index], 1e-5f)
    }

    @Test
    fun `at 10 fps a frame moves as far as three do at 30`() {
        // A hand raised into view: the model goes from 0.1 to 0.9 and stays there.
        val at10 = run(listOf(0.1f, 0.9f, 0.9f, 0.9f), 100.0)

        val at30 = MediaPipeFilter()
        val reference = listOf(0.1f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f).map(at30::apply)

        assertEquals(reference[3], at10[1], 1e-4f)
        assertEquals(reference[6], at10[2], 1e-4f)
        assertEquals(reference[9], at10[3], 1e-4f)
    }

    @Test
    fun `a raised hand crosses the overlay's 0_5 in the time it takes at 30 fps`() {
        val models = listOf(0.1f) + List(12) { 0.95f }
        val at10 = run(models, 100.0)
        // Seven frames at 30 fps is 233 ms, so the third frame at 10 fps, 200 ms in, is still short
        // of it and the fourth, 300 ms in, is past it. Unclocked it took seven 10 fps frames.
        assertTrue("${at10[2]}", at10[2] < 0.5f)
        assertTrue("${at10[3]}", at10[3] > 0.5f)
    }

    @Test
    fun `the first frame after a reset passes through`() {
        run(listOf(0.2f, 0.2f), 100.0)
        clock.reset()

        val landmarks = frame(0.8f)
        clock.apply(landmarks, 500.0)
        assertEquals(0.8f, visibility(landmarks), 0f)
    }

    @Test
    fun `a filter that started over unseen is taken as it comes, not inverted into nonsense`() {
        clock.apply(frame(0.9f), 0.0)
        // 0.9 then 0.1 in one frame is a move MediaPipe's filter cannot make; only a restart can.
        val landmarks = frame(0.1f)
        clock.apply(landmarks, 100.0)
        assertEquals(0.1f, visibility(landmarks), 0f)
    }

    @Test
    fun `a handover keeps the visibility where it was`() {
        val before = run(listOf(0.2f, 0.95f, 0.95f, 0.95f), 100.0).last()
        clock.handOver()

        // The new landmarker's filter starts over, from its own first frame.
        val landmarks = frame(0.6f)
        clock.apply(landmarks, 400.0)
        assertEquals(before, visibility(landmarks), 0f)

        // And the next frame is inverted against that new filter, not the old one.
        val next = frame(0.1f * 0.95f + 0.9f * 0.6f)
        clock.apply(next, 500.0)
        val expected = before + VisibilityClock.weight(100.0) * (0.95f - before)
        assertEquals(expected, visibility(next), 1e-4f)
    }

    @Test
    fun `a handover after a long gap starts over instead`() {
        run(listOf(0.95f, 0.95f), 100.0)
        clock.handOver()

        val landmarks = frame(0.3f)
        clock.apply(landmarks, 100.0 + VisibilityClock.HANDOVER_MAX_GAP_MS + 1.0)
        assertEquals(0.3f, visibility(landmarks), 0f)
    }
}
