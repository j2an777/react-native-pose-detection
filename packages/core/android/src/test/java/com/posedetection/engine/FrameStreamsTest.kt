package com.posedetection.engine

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a synchronous read on the JavaScript thread finds a view's frames, and what it gets when it cannot. */
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
}
