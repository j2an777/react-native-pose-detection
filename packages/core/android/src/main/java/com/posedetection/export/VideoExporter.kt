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

/**
 * Two passes: detect the poses, then transcode the video with them painted on.
 *
 * Detection runs first, over the whole clip at `sampleFps`, and keeps only landmarks: a minute of
 * video at ten samples a second is about three hundred kilobytes, so the whole result fits in
 * memory and the transcode never has to wait for an inference. The alternative, detecting inside
 * the transcode loop, would mean reading frames back off the GPU to get pixels MediaPipe can see,
 * upside down and at full resolution, on every sampled frame.
 *
 * The transcode itself never leaves the GPU: decoder to [ExportGl] to encoder. The skeleton is
 * drawn into a bitmap only when the pose changes, ten times a second rather than thirty, and
 * uploaded as a texture.
 */
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

    // MARK: Pass one, the poses

    /**
     * Frames come back already turned upright, which is the same space the transcode draws in, so
     * the landmarks need no correction between the two passes. Each pose carries its frame's real
     * position in the video, which is what the transcode matches frames against.
     */
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
                // Every sample, the empty ones too: an empty one is what stops the skeleton being
                // painted once the person has left, as on iOS.
                poses.add(Pose(frame.timestampMs, bodies))
                // The detect pass is the slow half, so it owns most of the progress bar.
                report(DETECT_SHARE * sampler.progress(frame))
                if (!pacer.rest { cancelled.get() }) break
            }
        } finally {
            detector?.close()
            sampler?.close()
        }
        return poses
    }

    // MARK: Pass two, the picture

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

        // Written under a staging name and renamed into place at the end, so a process that dies
        // mid-write can never leave behind something that looks like a finished export. Whatever
        // a dead process does leave is swept the next time the directory is prepared. The last
        // export under this name stays where it is until then: the rename replaces it in one
        // step, so a cancel or a failure never costs the file this one would have replaced.
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
            // The rotation is applied by the renderer, so it must not travel to the decoder as
            // well: some devices honour it on a surface and the frame would come out turned twice.
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

            // Finalized here rather than in the cleanup below, because the rename must not happen
            // before the muxer has written the file's index, and a rename that fails has to be a
            // failed export rather than a summary pointing at nothing.
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
            // A half written file looks like a finished export to anything that finds it later.
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

    /**
     * The source's frame rate, which some containers store as a float: `getInteger` on one throws.
     * A rate the encoder cannot use falls back to 30.
     */
    private fun frameRate(source: MediaFormat): Int {
        if (!source.containsKey(MediaFormat.KEY_FRAME_RATE)) return DEFAULT_FRAME_RATE
        val rate =
            runCatching { source.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
                .recoverCatching { source.getFloat(MediaFormat.KEY_FRAME_RATE) }
                .getOrNull()
        return rate?.takeIf { it.isFinite() && it >= 1f }?.roundToInt() ?: DEFAULT_FRAME_RATE
    }

    /**
     * Throttled, because a frame by frame progress event is thirty crossings a second for a number
     * nobody can read that fast.
     */
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
