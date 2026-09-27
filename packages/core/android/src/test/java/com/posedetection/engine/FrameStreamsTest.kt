package com.posedetection.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameStreamsTest {
    private fun stream() = FrameStream(FrameRingBuffer()) { mapOf("fps" to 30) }

    @Test
    fun `a registered stream is found by its id`() {
        val stream = stream()
        FrameStreams.register(stream, 7001)
        assertSame(stream, FrameStreams.stream(7001))
        FrameStreams.unregister(stream, 7001)
    }

    @Test
    fun `an unknown id reads as no stream and no live state`() {
        assertNull(FrameStreams.stream(9999))
        assertTrue(FrameStreams.live(9999).isEmpty())
    }

    @Test
    fun `unregistering leaves another view that reused the id alone`() {
        val first = stream()
        val second = stream()
        FrameStreams.register(first, 7002)
        FrameStreams.register(second, 7002)
        FrameStreams.unregister(first, 7002)
        assertSame(second, FrameStreams.stream(7002))
        FrameStreams.unregister(second, 7002)
        assertNull(FrameStreams.stream(7002))
    }

    @Test
    fun `any detecting is true only while some camera runs inference`() {
        var running = false
        val idle = stream()
        val live = FrameStream(FrameRingBuffer(), { running }) { emptyMap() }
        FrameStreams.register(idle, 7003)
        FrameStreams.register(live, 7004)
        assertFalse(FrameStreams.anyDetecting())
        running = true
        assertTrue(FrameStreams.anyDetecting())
        FrameStreams.unregister(idle, 7003)
        FrameStreams.unregister(live, 7004)
    }
}
