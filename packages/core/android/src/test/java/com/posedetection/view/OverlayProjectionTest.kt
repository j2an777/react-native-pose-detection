package com.posedetection.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Case for case the same as OverlayProjectionTests.swift, so both platforms project alike. */
class OverlayProjectionTest {
    private val viewWidth = 400f
    private val viewHeight = 800f

    private fun projection(
        sourceWidth: Int,
        sourceHeight: Int,
        fit: ContentFit,
    ) = OverlayProjection(sourceWidth, sourceHeight, viewWidth, viewHeight, fit)

    @Test
    fun `fill covers the view on a wider source`() {
        val projection = projection(1920, 1080, ContentFit.FILL)

        assertEquals(800f, projection.height, 0.001f)
        assertEquals(800f * 16f / 9f, projection.width, 0.001f)
        assertEquals((400f - 800f * 16f / 9f) / 2f, projection.left, 0.001f)
        assertEquals(0f, projection.top, 0.001f)
    }

    @Test
    fun `fit shows the whole source on a wider source`() {
        val projection = projection(1920, 1080, ContentFit.FIT)

        assertEquals(400f, projection.width, 0.001f)
        assertEquals(400f * 9f / 16f, projection.height, 0.001f)
        assertEquals(0f, projection.left, 0.001f)
        assertEquals((800f - 400f * 9f / 16f) / 2f, projection.top, 0.001f)
    }

    @Test
    fun `fit and fill agree when the aspects match`() {
        val fill = projection(200, 400, ContentFit.FILL)
        val fit = projection(200, 400, ContentFit.FIT)

        assertEquals(fill.left, fit.left, 0.001f)
        assertEquals(fill.top, fit.top, 0.001f)
        assertEquals(fill.width, fit.width, 0.001f)
        assertEquals(fill.height, fit.height, 0.001f)
        assertEquals(viewWidth, fit.width, 0.001f)
        assertEquals(viewHeight, fit.height, 0.001f)
    }

    @Test
    fun `fit never exceeds the bounds`() {
        for ((sourceWidth, sourceHeight) in listOf(4000 to 10, 10 to 4000, 640 to 480)) {
            val projection = projection(sourceWidth, sourceHeight, ContentFit.FIT)
            assertTrue("$sourceWidth x $sourceHeight", projection.width <= viewWidth + 0.001f)
            assertTrue("$sourceWidth x $sourceHeight", projection.height <= viewHeight + 0.001f)
        }
    }

    @Test
    fun `fill never leaves a gap`() {
        for ((sourceWidth, sourceHeight) in listOf(4000 to 10, 10 to 4000, 640 to 480)) {
            val projection = projection(sourceWidth, sourceHeight, ContentFit.FILL)
            assertTrue("$sourceWidth x $sourceHeight", projection.width >= viewWidth - 0.001f)
            assertTrue("$sourceWidth x $sourceHeight", projection.height >= viewHeight - 0.001f)
        }
    }

    @Test
    fun `corners of the source land on corners of the content rect`() {
        val projection = projection(1920, 1080, ContentFit.FIT)

        // 0,0 is the picture's top-left, not the view's: the letterbox is accounted for.
        assertEquals(projection.left, projection.x(0f, false), 0.001f)
        assertEquals(projection.top, projection.y(0f), 0.001f)
        assertEquals(projection.left + projection.width, projection.x(1f, false), 0.001f)
        assertEquals(projection.top + projection.height, projection.y(1f), 0.001f)
    }

    @Test
    fun `mirroring flips x about the centre of the picture`() {
        val projection = projection(1080, 1920, ContentFit.FILL)

        val plain = projection.x(0.25f, false)
        val mirrored = projection.x(0.25f, true)

        assertEquals(projection.left + projection.width / 2f, (plain + mirrored) / 2f, 0.001f)
    }

    @Test
    fun `mirroring is its own inverse`() {
        val projection = projection(640, 480, ContentFit.FIT)

        assertEquals(projection.x(1f - 0.3f, false), projection.x(0.3f, true), 0.001f)
    }

    @Test
    fun `a degenerate source falls back to the bounds rather than dividing by zero`() {
        for ((sourceWidth, sourceHeight) in listOf(0 to 100, 100 to 0, 0 to 0)) {
            val projection = projection(sourceWidth, sourceHeight, ContentFit.FIT)
            assertEquals("$sourceWidth x $sourceHeight", 0f, projection.left, 0.001f)
            assertEquals("$sourceWidth x $sourceHeight", 0f, projection.top, 0.001f)
            assertEquals("$sourceWidth x $sourceHeight", viewWidth, projection.width, 0.001f)
            assertEquals("$sourceWidth x $sourceHeight", viewHeight, projection.height, 0.001f)
        }
    }

    @Test
    fun `a degenerate view is not projected into`() {
        val projection = OverlayProjection(16, 9, 0f, 400f, ContentFit.FIT)

        assertEquals(0f, projection.width, 0.001f)
        assertEquals(400f, projection.height, 0.001f)
    }

    @Test
    fun `the two platforms agree on a 4 by 3 source in a tall view`() {
        val projection = projection(640, 480, ContentFit.FILL)

        assertEquals(800f, projection.height, 0.001f)
        assertEquals(800f * 4f / 3f, projection.width, 0.001f)
        assertEquals(projection.left + projection.width / 2f, projection.x(0.5f, false), 0.001f)
    }
}
