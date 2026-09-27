package com.posedetection.export

import org.junit.Assert.assertEquals
import org.junit.Test

class PoseTimelineTest {
    private val somebody = listOf(FloatArray(4))

    private val samples =
        listOf(
            Pose(100, somebody),
            Pose(200, somebody),
            Pose(300, emptyList()),
            Pose(400, emptyList()),
            Pose(500, somebody),
        )

    @Test
    fun `a frame takes the latest sample at or before it`() {
        assertEquals(0, PoseTimeline.at(samples, 100))
        assertEquals(0, PoseTimeline.at(samples, 199))
        assertEquals(1, PoseTimeline.at(samples, 200))
        assertEquals(4, PoseTimeline.at(samples, 10_000))
    }

    @Test
    fun `a frame before the first sample is painted with nothing, not a pose from later on`() {
        assertEquals(-1, PoseTimeline.at(samples, 0))
        assertEquals(-1, PoseTimeline.at(samples, 99))
    }

    @Test
    fun `after the person has left, frames take the empty sample and nobody is painted`() {
        val index = PoseTimeline.at(samples, 350)
        assertEquals(2, index)
        assertEquals(0, samples[index].bodies.size)
    }

    @Test
    fun `no samples at all paints nothing`() {
        assertEquals(-1, PoseTimeline.at(emptyList(), 100))
    }
}
