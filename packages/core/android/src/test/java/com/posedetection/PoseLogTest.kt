package com.posedetection

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The level `setLogLevel()` sets, and what a camera's `logLevel` prop does on top of it. */
class PoseLogTest {
    private val camera = Any()
    private val other = Any()

    @After
    fun reset() {
        PoseLog.raise(camera, null)
        PoseLog.raise(other, null)
        PoseLog.setLevel(LogLevel.OFF)
    }

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
}
