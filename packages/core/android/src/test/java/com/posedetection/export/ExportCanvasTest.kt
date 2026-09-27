package com.posedetection.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportCanvasTest {
    @Test
    fun `a cap smaller than the source scales both axes together`() {
        val canvas = ExportCanvas.size(3840, 2160, 1920)
        assertEquals(1920, canvas[0])
        assertEquals(1080, canvas[1])
    }

    @Test
    fun `the aspect ratio survives the cap`() {
        val canvas = ExportCanvas.size(1080, 1920, 720)
        assertEquals(1080f / 1920f, canvas[0].toFloat() / canvas[1].toFloat(), 0.01f)
    }

    @Test
    fun `a source smaller than the cap is left alone`() {
        val canvas = ExportCanvas.size(640, 480, 1920)
        assertEquals(640, canvas[0])
        assertEquals(480, canvas[1])
    }

    @Test
    fun `zero means the source's own size`() {
        val canvas = ExportCanvas.size(3840, 2160, 0)
        assertEquals(3840, canvas[0])
        assertEquals(2160, canvas[1])
    }

    @Test
    fun `both axes come back even`() {
        for (width in 1..64) {
            for (height in intArrayOf(1, 3, 17, 99)) {
                val canvas = ExportCanvas.size(width, height, 0)
                assertEquals("width $width", 0, canvas[0] % 2)
                assertEquals("height $height", 0, canvas[1] % 2)
                assertTrue(canvas[0] >= 2)
                assertTrue(canvas[1] >= 2)
            }
        }
    }

    @Test
    fun `a degenerate source still produces an encodable size`() {
        assertEquals(2, ExportCanvas.size(0, 0, 1920)[0])
        assertEquals(2, ExportCanvas.size(100, 0, 1920)[1])
    }

    @Test
    fun `the portrait long edge is the one that is capped`() {
        val canvas = ExportCanvas.size(1080, 1920, 960)
        assertEquals(540, canvas[0])
        assertEquals(960, canvas[1])
    }

    /** 1080 / 400 is near a phone's density of 3, so a lineWidth of 3 lands near its 9 px. */
    @Test
    fun `a full hd canvas scales roughly like a phone screen`() {
        assertEquals(2.7f, ExportCanvas.overlayScale(1080, 1920), 0.01f)
    }

    @Test
    fun `orientation does not change the scale`() {
        assertEquals(
            ExportCanvas.overlayScale(1920, 1080),
            ExportCanvas.overlayScale(1080, 1920),
            0.0001f,
        )
    }

    @Test
    fun `a small canvas never thins the skeleton`() {
        assertEquals(1f, ExportCanvas.overlayScale(240, 320), 0.0001f)
        assertEquals(1f, ExportCanvas.overlayScale(0, 0), 0.0001f)
    }

    @Test
    fun `a four k canvas scales up rather than staying hair thin`() {
        assertEquals(5.4f, ExportCanvas.overlayScale(3840, 2160), 0.01f)
    }
}
