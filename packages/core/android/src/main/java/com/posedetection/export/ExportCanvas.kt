package com.posedetection.export

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** No android.graphics on purpose, so a plain JVM test can run it. */
internal object ExportCanvas {
    const val DEFAULT_MAX_SIZE = 1920

    /** A phone screen is around this many dp across, the size the overlay defaults look right at. */
    private const val REFERENCE_EDGE = 400f

    /** Even on both axes: some encoders reject an odd size, and others round it, shifting every pixel. */
    fun size(
        displayWidth: Int,
        displayHeight: Int,
        maxSize: Int,
    ): IntArray {
        if (displayWidth <= 0 || displayHeight <= 0) return intArrayOf(2, 2)

        val longEdge = max(displayWidth, displayHeight).toFloat()
        // Only ever down: upscaling costs encode time and adds no detail.
        val scale = if (maxSize > 0) min(1f, maxSize / longEdge) else 1f
        return intArrayOf(even(displayWidth * scale), even(displayHeight * scale))
    }

    /** Overlay widths are in dp; scaled by the short edge, a line weighs what it does live, either way up. */
    fun overlayScale(
        canvasWidth: Int,
        canvasHeight: Int,
    ): Float {
        val shortEdge = min(canvasWidth, canvasHeight)
        if (shortEdge <= 0) return 1f
        return max(1f, shortEdge / REFERENCE_EDGE)
    }

    private fun even(value: Float): Int {
        val rounded = max(2, value.roundToInt())
        return rounded - rounded % 2
    }
}
