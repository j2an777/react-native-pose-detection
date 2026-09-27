package com.posedetection.detector

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CameraX names the clockwise turn a buffer needs to stand upright; MediaPipe turns the image the
 * other way by what it is handed. A frame dumped on a Redmi Note 12 is the evidence: handed +270
 * for its front camera, the model put the shoulders above the head.
 */
class RotationTest {
    @Test
    fun `a front camera's 270 is handed over negated, so the model sees the person upright`() {
        assertEquals(-270, PoseDetector.mediaPipeDegrees(270))
    }

    @Test
    fun `a back camera's 90 is handed over negated too`() {
        assertEquals(-90, PoseDetector.mediaPipeDegrees(90))
    }

    @Test
    fun `an upright buffer is handed over unturned`() {
        assertEquals(0, PoseDetector.mediaPipeDegrees(0))
        assertEquals(0, PoseDetector.mediaPipeDegrees(360))
    }

    @Test
    fun `a turn that arrives negative is read as the quarter it lands on`() {
        assertEquals(-270, PoseDetector.mediaPipeDegrees(-90))
    }
}
