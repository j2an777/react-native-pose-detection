package com.posedetection.view

import android.content.Context
import android.graphics.Canvas
import android.view.View
import com.posedetection.Skeleton

internal class OverlayView(
    context: Context,
) : View(context) {
    // Held only to copy incoming (analysis thread) into landmarks (UI thread), so a draw never tears.
    private val frameLock = Any()
    private val incoming = FloatArray(Skeleton.LANDMARK_COUNT * Skeleton.LANDMARK_STRIDE)
    private var incomingHasPose = false
    private var incomingMirrored = false
    private var incomingWidth = 0
    private var incomingHeight = 0

    // UI thread only. Mirroring and size share the snapshot, so a camera switch never mixes frames.
    private val landmarks = FloatArray(Skeleton.LANDMARK_COUNT * Skeleton.LANDMARK_STRIDE)
    private var hasPose = false
    private var mirrored = false

    /** In display orientation, the space landmarks are normalized in: at 90 and 270 the sides swap. */
    private var sourceWidth = 0
    private var sourceHeight = 0

    private val renderer = OverlayRenderer(context.resources.displayMetrics.density)

    var config: OverlayConfig
        get() = renderer.config
        set(value) {
            if (value == renderer.config) return
            renderer.config = value
            invalidate()
        }

    init {
        setWillNotDraw(false)
    }

    fun setMirrored(mirrored: Boolean) {
        synchronized(frameLock) {
            incomingMirrored = mirrored
        }
    }

    /** Analysis thread. The size rides with the landmarks, so a draw never pairs them with an old one. */
    fun submit(
        frame: FloatArray,
        rotatedWidth: Int,
        rotatedHeight: Int,
    ) {
        synchronized(frameLock) {
            System.arraycopy(frame, 0, incoming, 0, incoming.size)
            incomingWidth = rotatedWidth
            incomingHeight = rotatedHeight
            incomingHasPose = true
        }
        postInvalidateOnAnimation()
    }

    fun clearPose() {
        synchronized(frameLock) {
            incomingHasPose = false
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        synchronized(frameLock) {
            hasPose = incomingHasPose
            mirrored = incomingMirrored
            sourceWidth = incomingWidth
            sourceHeight = incomingHeight
            if (hasPose) System.arraycopy(incoming, 0, landmarks, 0, landmarks.size)
        }

        if (!hasPose || sourceWidth == 0 || sourceHeight == 0) return
        if (width == 0 || height == 0) return

        renderer.draw(
            canvas,
            landmarks,
            OverlayProjection(
                sourceWidth,
                sourceHeight,
                width.toFloat(),
                height.toFloat(),
                ContentFit.FILL,
            ),
            mirrored,
            sourceWidth,
            sourceHeight,
        )
    }
}
