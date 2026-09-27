package com.posedetection.export

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.posedetection.detector.FileDetector
import com.posedetection.detector.StaticDetection
import com.posedetection.detector.StaticDetectionError
import com.posedetection.detector.VideoFrameSampler
import com.posedetection.performance.FilePacer
import com.posedetection.performance.ThermalMonitor
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** Detect, then transcode: a minute of landmarks is ~300 KB, and the transcode's pixels stay on the GPU. */
internal class VideoExporter(
    private val context: Context,
    private val uri: String,
    private val options: ExportOptions,
    private val cancelled: AtomicBoolean,
    private val onProgress: (Float) -> Unit,
) {
    private var lastReported = -1f

    /** One pacer for both passes, so heat measured during detection carries into the transcode. */
    private val pacer = FilePacer(ThermalMonitor(context)::readThermal)

    fun run(): ExportSummary {
        val poses = detect()
        if (cancelled.get()) throw ExportCancelled()
        return transcode(poses)
    }

    /** Frames arrive upright, the space the transcode draws in, so landmarks need no correction. */
    private fun detect(): List<Pose> {
        var sampler: VideoFrameSampler? = null
        var detector: FileDetector? = null
        val poses = ArrayList<Pose>()
        try {
            sampler =
                try {
                    VideoFrameSampler(context, uri, options.sampleFps, 0L, -1L)
                } catch (error: StaticDetectionError) {
                    throw ExportError(error.message ?: "could not read frames from $uri")
                }
            detector =
                FileDetector(
                    context,
                    StaticDetection.requireModel(context),
                    options.maxPoses,
                    options.minConfidence,
                )

            var lastTimestamp = -1L
            while (!cancelled.get()) {
                val frame = sampler.next() ?: break
                val timestamp = maxOf(frame.timestampMs, lastTimestamp + 1)
                lastTimestamp = timestamp
                val bodies = PoseExport.poses(detector.detect(BitmapImageBuilder(frame.bitmap).build(), timestamp))
                // Empty samples too: they stop the skeleton being painted after the person leaves.
                poses.add(Pose(frame.timestampMs, bodies))
                report(DETECT_SHARE * sampler.progress(frame))
                if (!pacer.rest { cancelled.get() }) break
            }
        } finally {
            detector?.close()
            sampler?.close()
        }
        return poses
    }

    @Suppress("LongMethod")
    private fun transcode(poses: List<Pose>): ExportSummary {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var gl: ExportGl? = null
        var overlay: Bitmap? = null
        var audio: ExportAudio? = null
        val output = File(options.directory, "${options.fileName}.mp4")

        // Staged, then renamed in one step: a failure leaves no fake finished file and keeps the old one.
        val staging = File(options.directory, "${options.fileName}${PoseExport.STAGING_SUFFIX}.mp4")
        staging.delete()
        var complete = false

        try {
            StaticDetection.openExtractor(extractor, context, uri)
            val track = videoTrack(extractor)
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)

            val rotation =
                if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                    format.getInteger(MediaFormat.KEY_ROTATION)
                } else {
                    0
                }
            val naturalWidth = format.getInteger(MediaFormat.KEY_WIDTH)
            val naturalHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
            val upright = rotation == 90 || rotation == 270
            val canvas =
                ExportCanvas.size(
                    if (upright) naturalHeight else naturalWidth,
                    if (upright) naturalWidth else naturalHeight,
                    options.maxSize,
                )

            encoder = createEncoder(canvas[0], canvas[1], format)
            gl = ExportGl(encoder.createInputSurface())
            encoder.start()
            gl.setViewport(canvas[0], canvas[1])

            decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            // GL applies the rotation; some decoders also honour it on a surface, turning the frame twice.
            format.setInteger(MediaFormat.KEY_ROTATION, 0)
            decoder.configure(format, gl.decoderSurface, null, 0)
            decoder.start()

            muxer = MediaMuxer(staging.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            overlay = Bitmap.createBitmap(canvas[0], canvas[1], Bitmap.Config.ARGB_8888)

            audio = ExportAudio.open(context, uri)

            val painter = OverlayPainter(overlay, canvas, naturalWidth, naturalHeight, upright, options)
            val pump = ExportPump(decoder, encoder, muxer, gl, audio, cancelled, pacer)
            val frames =
                pump.run(extractor, rotation, poses, painter) { done ->
                    report(DETECT_SHARE + (1f - DETECT_SHARE) * done)
                }

            // Here, not in the finally: the rename needs the index stop() writes, and must fail the export.
            muxer.stop()
            if (!staging.renameTo(output)) throw ExportError("the export could not be moved into place")
            complete = true
            return ExportSummary(
                file = output,
                width = canvas[0],
                height = canvas[1],
                durationMs = (pump.lastTimeUs / MICROS_PER_MILLI).toInt(),
                frameCount = frames,
                posesFound = poses.count { it.bodies.isNotEmpty() },
            )
        } finally {
            runCatching { decoder?.stop() }
            decoder?.release()
            runCatching { encoder?.stop() }
            encoder?.release()
            gl?.release()
            overlay?.recycle()
            // Stopped on the happy path above; an incomplete muxer has nothing stoppable in it.
            runCatching { muxer?.release() }
            audio?.release()
            extractor.release()
            if (!complete) staging.delete()
        }
    }

    private fun videoTrack(extractor: MediaExtractor): Int {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return index
        }
        throw ExportError("no video track in $uri")
    }

    private fun createEncoder(
        width: Int,
        height: Int,
        source: MediaFormat,
    ): MediaCodec {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, width * height * BITS_PER_PIXEL)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate(source))
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_SECONDS)

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        return encoder
    }

    /** Some containers store the rate as a float, and getInteger throws on one. */
    private fun frameRate(source: MediaFormat): Int {
        if (!source.containsKey(MediaFormat.KEY_FRAME_RATE)) return DEFAULT_FRAME_RATE
        val rate =
            runCatching { source.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
                .recoverCatching { source.getFloat(MediaFormat.KEY_FRAME_RATE) }
                .getOrNull()
        return rate?.takeIf { it.isFinite() && it >= 1f }?.roundToInt() ?: DEFAULT_FRAME_RATE
    }

    private fun report(progress: Float) {
        val clamped = progress.coerceIn(0f, 1f)
        if (clamped < lastReported + PROGRESS_STEP && clamped < 1f) return
        lastReported = clamped
        onProgress(clamped)
    }

    private companion object {
        const val MICROS_PER_MILLI = 1_000L
        const val BITS_PER_PIXEL = 8
        const val DEFAULT_FRAME_RATE = 30
        const val I_FRAME_SECONDS = 1
        const val PROGRESS_STEP = 0.02f

        /** Detection is the slow pass, so it owns most of the bar. */
        const val DETECT_SHARE = 0.7f
    }
}
