package com.posedetection.detector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaticOptionsTest {
    @Test
    fun `confidence follows maxPoses unless chosen`() {
        assertEquals(0.5f, StaticOptions.forImage(null).minConfidence, 0f)
        assertEquals(
            "a second person needs a lower bar",
            0.3f,
            StaticOptions.forImage(mapOf("maxPoses" to 2)).minConfidence,
            0f,
        )
        assertEquals(0.3f, StaticOptions.forVideo(mapOf("maxPoses" to 3)).minConfidence, 0f)
        assertEquals(
            0.45f,
            StaticOptions.forImage(mapOf("maxPoses" to 3, "minConfidence" to 0.45)).minConfidence,
            1e-6f,
        )
    }

    @Test
    fun `confidence is clamped to the documented range`() {
        assertEquals(0.1f, StaticOptions.forImage(mapOf("minConfidence" to 0.01)).minConfidence, 1e-6f)
        assertEquals(1f, StaticOptions.forImage(mapOf("minConfidence" to 1.0)).minConfidence, 0f)
        assertEquals(1f, StaticOptions.forImage(mapOf("minConfidence" to 7)).minConfidence, 0f)
        assertEquals(
            "NaN is no choice at all",
            0.5f,
            StaticOptions.forImage(mapOf("minConfidence" to Double.NaN)).minConfidence,
            0f,
        )
    }

    @Test
    fun `maxPoses stays between one and five`() {
        assertEquals(1, StaticOptions.forImage(mapOf("maxPoses" to 0)).maxPoses)
        assertEquals(5, StaticOptions.forImage(mapOf("maxPoses" to 40)).maxPoses)
    }

    @Test
    fun `a video samples at ten a second unless told and never below one`() {
        assertEquals(10, StaticOptions.forVideo(null).fps)
        assertEquals(1, StaticOptions.forVideo(mapOf("fps" to 0)).fps)
        assertEquals("slow motion is sampled as asked", 120, StaticOptions.forVideo(mapOf("fps" to 120)).fps)
    }

    @Test
    fun `smoothing is whatever JavaScript resolved and off for a photo`() {
        assertFalse("absent is off: one pose is smoothed inside MediaPipe", StaticOptions.forVideo(null).smoothing)
        assertTrue(StaticOptions.forVideo(mapOf("smoothing" to true)).smoothing)
        assertFalse(
            "a single frame has nothing to smooth",
            StaticOptions.forImage(mapOf("smoothing" to true)).smoothing,
        )
    }

    @Test
    fun `a trim range is never negative`() {
        assertEquals(0L, StaticOptions.forVideo(mapOf("startMs" to -500)).startMs)
        assertEquals("no end means the end of the clip", -1L, StaticOptions.forVideo(null).endMs)
    }

    @Test
    fun `the photo sample size keeps the long side at or above the cap`() {
        assertEquals(1, StillImage.sampleSize(1_500, 1_920))
        assertEquals(2, StillImage.sampleSize(4_032, 1_920))
        assertEquals(4, StillImage.sampleSize(8_064, 1_920))
        assertEquals("full size asked for", 1, StillImage.sampleSize(8_064, null))
    }
}
