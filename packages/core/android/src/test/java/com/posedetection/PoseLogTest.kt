package com.posedetection

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The level `setLogLevel()` sets, what a camera's `logLevel` prop does on top of it, and who hands
 * the buffered entries to JavaScript.
 */
class PoseLogTest {
    private val camera = Any()
    private val other = Any()

    @After
    fun reset() {
        PoseLog.raise(camera, null)
        PoseLog.raise(other, null)
        PoseLog.setLevel(LogLevel.OFF)
        PoseLog.releaseStream(camera)
        PoseLog.releaseStream(other)
        PoseLog.stopStream()
    }

    private fun buffer(vararg messages: String) {
        messages.forEachIndexed { index, message ->
            PoseLog.record(LogLevel.INFO, LogCategory.ENGINE, message, index.toLong())
        }
    }

    private fun messages(batch: List<Map<String, Any?>>?) = batch?.map { it["message"] }

    @Test
    fun `a camera mounted without the logLevel prop leaves the global level alone`() {
        PoseLog.setLevels(mapOf(LogCategory.CAMERA to LogLevel.INFO))
        PoseLog.raise(camera, PoseLog.levelMask(null))
        assertTrue(PoseLog.isEnabled(LogLevel.INFO, LogCategory.CAMERA))
    }

    @Test
    fun `the prop raises the level until its camera lets go`() {
        PoseLog.setLevel(LogLevel.WARN)
        PoseLog.raise(camera, PoseLog.levelMask(mapOf("triggers" to "trace")))
        assertTrue(PoseLog.isEnabled(LogLevel.TRACE, LogCategory.TRIGGERS))
        assertTrue(PoseLog.isEnabled(LogLevel.WARN, LogCategory.CAMERA))
        assertFalse(PoseLog.isEnabled(LogLevel.INFO, LogCategory.CAMERA))

        PoseLog.raise(camera, null)
        assertFalse(PoseLog.isEnabled(LogLevel.INFO, LogCategory.TRIGGERS))
        assertTrue(PoseLog.isEnabled(LogLevel.WARN, LogCategory.TRIGGERS))
    }

    @Test
    fun `the prop never lowers what setLogLevel asked for`() {
        PoseLog.setLevel(LogLevel.DEBUG)
        PoseLog.raise(camera, PoseLog.levelMask("error"))
        assertTrue(PoseLog.isEnabled(LogLevel.DEBUG, LogCategory.DETECTOR))
    }

    @Test
    fun `two cameras each keep their raise until they go`() {
        PoseLog.raise(camera, PoseLog.levelMask(mapOf("camera" to "debug")))
        PoseLog.raise(other, PoseLog.levelMask("info"))
        assertTrue(PoseLog.isEnabled(LogLevel.DEBUG, LogCategory.CAMERA))
        assertTrue(PoseLog.isEnabled(LogLevel.INFO, LogCategory.ENGINE))

        PoseLog.raise(other, null)
        assertTrue(PoseLog.isEnabled(LogLevel.DEBUG, LogCategory.CAMERA))
        assertFalse(PoseLog.isEnabled(LogLevel.INFO, LogCategory.ENGINE))
    }

    @Test
    fun `setLogLevel with a map changes only the categories it names`() {
        PoseLog.setLevel(LogLevel.INFO)
        PoseLog.setLevels(mapOf(LogCategory.OVERLAY to LogLevel.OFF))
        assertFalse(PoseLog.isEnabled(LogLevel.ERROR, LogCategory.OVERLAY))
        assertTrue(PoseLog.isEnabled(LogLevel.INFO, LogCategory.CALIBRATION))
    }

    @Test
    fun `a map of unknown categories raises nothing and anything else is no raise`() {
        assertEquals(0, PoseLog.levelMask(mapOf("nonsense" to "trace")))
        assertNull(PoseLog.levelMask(42))
    }

    @Test
    fun `with no camera attached the module hands the entries over`() {
        PoseLog.startStream()
        buffer("a", "b")
        assertEquals(listOf("a", "b"), messages(PoseLog.takeBatch(null)))
        assertNull(PoseLog.takeBatch(null))
    }

    @Test
    fun `an attached camera flushes and the module gets nothing`() {
        PoseLog.startStream()
        PoseLog.claimStream(camera)
        buffer("a")
        assertNull(PoseLog.takeBatch(null))
        assertEquals(listOf("a"), messages(PoseLog.takeBatch(camera)))
    }

    @Test
    fun `the first camera keeps the flush and the module takes it back when it goes`() {
        PoseLog.startStream()
        PoseLog.claimStream(camera)
        PoseLog.claimStream(other)
        buffer("a")
        assertNull(PoseLog.takeBatch(other))

        PoseLog.releaseStream(camera)
        assertEquals(listOf("a"), messages(PoseLog.takeBatch(null)))
    }

    @Test
    fun `nothing is buffered or handed over while nobody listens`() {
        buffer("a")
        assertNull(PoseLog.takeBatch(null))
        PoseLog.startStream()
        assertNull(PoseLog.takeBatch(null))
    }

    @Test
    fun `a full buffer opens the next batch with how many were dropped`() {
        PoseLog.startStream()
        buffer(*Array(260) { "entry $it" })
        val batch = PoseLog.takeBatch(null)!!
        assertEquals(257, batch.size)
        assertEquals("warn", batch[0]["level"])
        assertEquals(mapOf("droppedCount" to 4), batch[0]["data"])
        assertEquals("entry 4", batch[1]["message"])
        assertEquals("entry 259", batch.last()["message"])
    }
}
