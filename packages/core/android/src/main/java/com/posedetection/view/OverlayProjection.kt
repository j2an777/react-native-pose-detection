package com.posedetection.view

/** The camera preview fills the view; static media fits in it. */
enum class ContentFit {
    FILL,
    FIT,
}

/** Free of android.graphics for JVM tests; OverlayProjection.swift's tests assert the same cases. */
class OverlayProjection(
    sourceWidth: Int,
    sourceHeight: Int,
    viewWidth: Float,
    viewHeight: Float,
    fit: ContentFit,
) {
    val left: Float
    val top: Float
    val width: Float
    val height: Float

    init {
        if (sourceWidth <= 0 || sourceHeight <= 0 || viewWidth <= 0f || viewHeight <= 0f) {
            left = 0f
            top = 0f
            width = viewWidth
            height = viewHeight
        } else {
            val sourceAspect = sourceWidth.toFloat() / sourceHeight.toFloat()
            val viewAspect = viewWidth / viewHeight
            // Fill takes the larger scale, fit the smaller: only the comparison differs.
            val heightLeads =
                if (fit == ContentFit.FILL) sourceAspect > viewAspect else sourceAspect < viewAspect

            if (heightLeads) {
                height = viewHeight
                width = height * sourceAspect
            } else {
                width = viewWidth
                height = width / sourceAspect
            }
            left = (viewWidth - width) / 2f
            top = (viewHeight - height) / 2f
        }
    }

    /** Landmarks are un-mirrored; [mirrored] matches a front camera's mirrored preview. */
    fun x(
        normalizedX: Float,
        mirrored: Boolean,
    ): Float = left + (if (mirrored) 1f - normalizedX else normalizedX) * width

    fun y(normalizedY: Float): Float = top + normalizedY * height
}
