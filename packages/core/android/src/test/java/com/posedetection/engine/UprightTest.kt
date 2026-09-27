package com.posedetection.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** Each case is a corner of the sensor buffer and where a clockwise turn puts it. */
class UprightTest {
    private fun upright(
        x: Float,
        y: Float,
        quarter: Int,
    ) = Pair(Upright.x(x, y, quarter), Upright.y(x, y, quarter))

    @Test
    fun `an upright buffer is left as it is`() {
        assertEquals(Pair(0.2f, 0.7f), upright(0.2f, 0.7f, 0))
    }

    @Test
    fun `a quarter turn clockwise takes the top left corner to the top right`() {
        assertEquals(Pair(1f, 0f), upright(0f, 0f, 1))
        assertEquals(Pair(1f, 1f), upright(1f, 0f, 1))
        assertEquals(Pair(0f, 0f), upright(0f, 1f, 1))
    }

    @Test
    fun `a half turn takes each corner to the opposite one`() {
        assertEquals(Pair(1f, 1f), upright(0f, 0f, 2))
        assertEquals(Pair(0f, 1f), upright(1f, 0f, 2))
    }

    @Test
    fun `three quarter turns, a front camera's usual, take the top left corner to the bottom left`() {
        assertEquals(Pair(0f, 1f), upright(0f, 0f, 3))
        assertEquals(Pair(0f, 0f), upright(1f, 0f, 3))
        assertEquals(Pair(1f, 1f), upright(0f, 1f, 3))
    }

    @Test
    fun `two points side by side in the buffer are one above the other once a quarter turn round`() {
        val left = upright(0.4f, 0.5f, 3)
        val right = upright(0.6f, 0.5f, 3)
        assertEquals(left.first, right.first, 1e-6f)
        assertEquals(0.2f, left.second - right.second, 1e-6f)
    }

    @Test
    fun `a world vector turns the way the screen does about the middle`() {
        for (quarter in 0..3) {
            val screenX = Upright.x(0.5f + 0.1f, 0.5f + 0.3f, quarter) - 0.5f
            val screenY = Upright.y(0.5f + 0.1f, 0.5f + 0.3f, quarter) - 0.5f
            assertEquals(screenX, Upright.worldX(0.1f, 0.3f, quarter), 1e-6f)
            assertEquals(screenY, Upright.worldY(0.1f, 0.3f, quarter), 1e-6f)
        }
    }

    @Test
    fun `rotation degrees become quarter turns whatever multiple of 90 they arrive as`() {
        assertEquals(0, Upright.quarterOf(0))
        assertEquals(1, Upright.quarterOf(90))
        assertEquals(3, Upright.quarterOf(270))
        assertEquals(0, Upright.quarterOf(360))
        assertEquals(3, Upright.quarterOf(-90))
    }
}
