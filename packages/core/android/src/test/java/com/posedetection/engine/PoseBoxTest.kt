package com.posedetection.engine

import com.posedetection.Skeleton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseBoxTest {
    @Test
    fun `the same body moving one frame overlaps well`() {
        val before = PoseBox(0.30f, 0.10f, 0.60f, 0.90f)
        val after = PoseBox(0.32f, 0.11f, 0.62f, 0.91f)
        assertTrue(after.overlap(before) > 0.8f)
    }

    @Test
    fun `someone else across the room does not overlap at all`() {
        val left = PoseBox(0.05f, 0.2f, 0.35f, 0.95f)
        val right = PoseBox(0.60f, 0.2f, 0.90f, 0.95f)
        assertEquals(0f, left.overlap(right), 0f)
        assertTrue(left.overlap(right) < PoseBox.SAME_BODY_OVERLAP)
    }

    @Test
    fun `the box is read from the landmark buffer`() {
        val landmarks = FloatArray(Skeleton.LANDMARK_COUNT * Skeleton.LANDMARK_STRIDE) { 0.5f }
        landmarks[Skeleton.NOSE * Skeleton.LANDMARK_STRIDE + Skeleton.OFFSET_Y] = 0.1f
        landmarks[Skeleton.LEFT_ANKLE * Skeleton.LANDMARK_STRIDE + Skeleton.OFFSET_Y] = 0.9f
        landmarks[Skeleton.LEFT_WRIST * Skeleton.LANDMARK_STRIDE + Skeleton.OFFSET_X] = 0.2f
        assertEquals(PoseBox(0.2f, 0.1f, 0.5f, 0.9f), PoseBox.of(landmarks))
    }

    @Test
    fun `the primary is the largest body`() {
        val near = PoseBox(0.5f, 0.1f, 0.9f, 0.95f)
        val far = PoseBox(0.1f, 0.4f, 0.2f, 0.6f)
        assertEquals("detection order means nothing about the subject", 1, PoseBox.primary(listOf(far, near)))
    }

    @Test
    fun `a tie goes to whoever is nearer the centre`() {
        val edge = PoseBox(0.0f, 0.3f, 0.2f, 0.7f)
        val centre = PoseBox(0.4f, 0.3f, 0.6f, 0.7f)
        assertEquals(1, PoseBox.primary(listOf(edge, centre)))
    }
}
