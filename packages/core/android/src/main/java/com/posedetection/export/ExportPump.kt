package com.posedetection.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import com.posedetection.LogCategory
import com.posedetection.PoseLog
import com.posedetection.performance.FilePacer
import com.posedetection.view.ContentFit
import com.posedetection.view.OverlayProjection
import com.posedetection.view.OverlayRenderer
import java.util.concurrent.atomic.AtomicBoolean

internal class Pose(
    val timeMs: Long,
    val bodies: List<FloatArray>,
)

internal object PoseTimeline {
    /** The latest sample at or before [timeMs], even an empty one, or -1 before the first. */
    fun at(
        poses: List<Pose>,
        timeMs: Long,
    ): Int {
        if (poses.isEmpty() || poses[0].timeMs > timeMs) return -1
        var low = 0
        var high = poses.size - 1
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (poses[middle].timeMs <= timeMs) low = middle else high = middle - 1
        }
        return low
    }
}

internal class OverlayPainter(
    private val target: Bitmap,
    canvasSize: IntArray,
    naturalWidth: Int,
    naturalHeight: Int,
    upright: Boolean,
    private val options: ExportOptions,
) {
    private val canvas = Canvas(target)
    private val sourceWidth = if (upright) naturalHeight else naturalWidth
    private val sourceHeight = if (upright) naturalWidth else naturalHeight

    // Fit, not fill, so nothing of the picked file is cropped away.
    private val projection =
        OverlayProjection(
            sourceWidth,
            sourceHeight,
            canvasSize[0].toFloat(),
            canvasSize[1].toFloat(),
            ContentFit.FIT,
        )
    private val renderer =
        OverlayRenderer(ExportCanvas.overlayScale(canvasSize[0], canvasSize[1])).apply {
            config = options.overlay
        }

    private var painted = Int.MIN_VALUE
    private var paintedEmpty = false

    fun bitmap(): Bitmap = target

    /** True when the bitmap changed and has to be uploaded again. */
    fun paint(
        poses: List<Pose>,
        index: Int,
    ): Boolean {
        if (index == painted) return false
        painted = index
        // A run of samples with nobody in them is one cleared bitmap, not one upload per sample.
        val empty = index < 0 || !options.drawOverlay || poses[index].bodies.isEmpty()
        if (empty && paintedEmpty) return false
        paintedEmpty = empty
        canvas.drawColor(0, PorterDuff.Mode.CLEAR)
        if (index >= 0 && options.drawOverlay) {
            for (landmarks in poses[index].bodies) {
                renderer.draw(
                    canvas,
                    landmarks,
                    projection,
                    mirrored = false,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                )
            }
        }
        return true
    }
}

/** Decode, render and encode on one thread: each frame serialises them anyway. */
@Suppress("LongParameterList")
internal class ExportPump(
    private val decoder: MediaCodec,
    private val encoder: MediaCodec,
    private val muxer: MediaMuxer,
    private val gl: ExportGl,
    private val audio: ExportAudio?,
    private val cancelled: AtomicBoolean,
    private val pacer: FilePacer,
) {
    /** The last presentation time written: the export's real duration. */
    var lastTimeUs = 0L
        private set

    private var warnedOrder = false

    fun run(
        extractor: MediaExtractor,
        rotation: Int,
        poses: List<Pose>,
        painter: OverlayPainter,
        onProgress: (Float) -> Unit,
    ): Int {
        val durationUs = extractor.trackDuration()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var decodeDone = false
        var encodeDone = false
        var muxerTrack = -1
        var frames = 0

        while (!encodeDone) {
            if (cancelled.get()) throw ExportCancelled()

            if (!inputDone) inputDone = feed(extractor)

            if (!decodeDone) {
                val index = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (index >= 0) {
                    // Some decoders return B-frames in decode order; a player cannot play time going back.
                    val backwards = frames > 0 && info.presentationTimeUs <= lastTimeUs
                    if (backwards && !warnedOrder) {
                        warnedOrder = true
                        PoseLog.warn(LogCategory.ENGINE) {
                            "the decoder returns frames out of order, so the export skips the late ones"
                        }
                    }
                    val render = info.size > 0 && !backwards
                    decoder.releaseOutputBuffer(index, render)
                    if (render && gl.awaitFrame()) {
                        gl.drawFrame(rotation)
                        val pose = PoseTimeline.at(poses, info.presentationTimeUs / MICROS_PER_MILLI)
                        gl.drawOverlay(painter.bitmap(), painter.paint(poses, pose))
                        gl.present(info.presentationTimeUs * NANOS_PER_MICRO)
                        frames++
                        lastTimeUs = info.presentationTimeUs
                        audio?.drain(muxer, info.presentationTimeUs)
                        if (durationUs > 0) onProgress(info.presentationTimeUs.toFloat() / durationUs)
                        if (!pacer.rest { cancelled.get() }) throw ExportCancelled()
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        decodeDone = true
                        encoder.signalEndOfInputStream()
                    }
                }
            }

            muxerTrack = drain(info, muxerTrack).also { encodeDone = it == FINISHED }
            if (encodeDone) muxerTrack = FINISHED
        }
        onProgress(1f)
        return frames
    }

    /** True once the end of stream has been queued. */
    private fun feed(extractor: MediaExtractor): Boolean {
        val index = decoder.dequeueInputBuffer(TIMEOUT_US)
        if (index < 0) return false
        val buffer = decoder.getInputBuffer(index) ?: return false

        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) {
            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            return true
        }
        decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
        extractor.advance()
        return false
    }

    /** The muxer's track index, or [FINISHED] after the encoder's end of stream. */
    private fun drain(
        info: MediaCodec.BufferInfo,
        track: Int,
    ): Int {
        var muxerTrack = track
        while (true) {
            val index = encoder.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    return muxerTrack
                }

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // The real format is known only now, and tracks go in before start(), so audio waits too.
                    muxerTrack = muxer.addTrack(encoder.outputFormat)
                    audio?.addTo(muxer)
                    muxer.start()
                }

                index >= 0 -> {
                    val buffer = encoder.getOutputBuffer(index)
                    // The config is already in the format the muxer started with; writing it would double it.
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buffer != null && info.size > 0 && !isConfig && muxerTrack >= 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(muxerTrack, buffer, info)
                    }
                    encoder.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        // Audio that outlasts the last video frame, so it is not cut off.
                        audio?.drain(muxer, Long.MAX_VALUE)
                        return FINISHED
                    }
                }
            }
        }
    }

    private fun MediaExtractor.trackDuration(): Long {
        val track = sampleTrackIndex
        if (track < 0) return 0
        val format = getTrackFormat(track)
        return if (format.containsKey(android.media.MediaFormat.KEY_DURATION)) {
            format.getLong(android.media.MediaFormat.KEY_DURATION)
        } else {
            0
        }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val MICROS_PER_MILLI = 1_000L
        const val NANOS_PER_MICRO = 1_000L
        const val FINISHED = -2
    }
}
