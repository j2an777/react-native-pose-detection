package com.posedetection.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a file job carries from one sampled frame to the next, and when it has to start over. */
class PoseTrackTest {
    private val body = PoseBox(0.3f, 0.1f, 0.6f, 0.9f)
    private val someoneElse = PoseBox(0.7f, 0.1f, 0.95f, 0.9f)

    @Test
    fun `the gap follows the rate and never drops under 200 ms`() {
        assertEquals(200.0, Continuity.maxGapMs(30.0), 1e-9)
        assertEquals(250.0, Continuity.maxGapMs(10.0), 1e-9)
        assertEquals(1_250.0, Continuity.maxGapMs(2.0), 1e-9)
        assertEquals("an unknown rate falls back to the floor", 200.0, Continuity.maxGapMs(0.0), 1e-9)
    }

    @Test
    fun `the first frame starts the track`() {
        val track = PoseTrack(10)
        assertNull(track.advance(body, 0.0))
        assertEquals(0.1f, track.advance(body, 100.0)!!, 1e-6f)
    }

    @Test
    fun `the real interval is reported not the nominal one`() {
        val track = PoseTrack(10)
        track.advance(body, 0.0)
        assertEquals("a 24 fps clip sampled at 10", 0.125f, track.advance(body, 125.0)!!, 1e-6f)
    }

    @Test
    fun `a gap longer than two and a half samples starts over`() {
        val track = PoseTrack(10)
        track.advance(body, 0.0)
        assertNull(track.advance(body, 400.0))
    }

    @Test
    fun `a frame with nobody in it starts over`() {
        val track = PoseTrack(10)
        track.advance(body, 0.0)
        track.lose()
        assertNull(track.advance(body, 100.0))
    }

    @Test
    fun `somebody else starts over`() {
        val track = PoseTrack(10)
        track.advance(body, 0.0)
        assertNull(track.advance(someoneElse, 100.0))
    }

    @Test
    fun `velocity is unknown on the first frame and measured after`() {
        val track = PoseTrack(10)
        val out = FloatArray(2)
        track.velocity(0.5f, 0.5f, track.advance(body, 0.0), out, 0)
        assertTrue("unknown, not zero", out[0].isNaN() && out[1].isNaN())

        track.velocity(0.6f, 0.4f, track.advance(body, 100.0), out, 0)
        assertEquals(1f, out[0], 1e-5f)
        assertEquals(-1f, out[1], 1e-5f)
    }

    @Test
    fun `velocity is not measured across a lost pose`() {
        val track = PoseTrack(10)
        val out = FloatArray(2)
        track.velocity(0.5f, 0.5f, track.advance(body, 0.0), out, 0)
        track.lose()
        track.velocity(0.9f, 0.9f, track.advance(body, 100.0), out, 0)
        track.velocity(0.9f, 0.9f, track.advance(body, 200.0), out, 0)
        assertEquals("measured from the frame after the gap", 0f, out[0], 1e-6f)
    }
}
