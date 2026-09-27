package com.posedetection.export

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.posedetection.LogCategory
import com.posedetection.PoseLog
import com.posedetection.detector.StaticDetection
import java.nio.ByteBuffer

/** Copied undecoded, off its own extractor: one extractor interleaves every track selected on it. */
internal class ExportAudio private constructor(
    private val extractor: MediaExtractor,
    val format: MediaFormat,
) {
    private val buffer: ByteBuffer =
        ByteBuffer.allocateDirect(
            if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceIn(MIN_BUFFER, MAX_BUFFER)
            } else {
                DEFAULT_BUFFER
            },
        )
    private val info = MediaCodec.BufferInfo()

    private var muxerIndex = -1
    private var pending = false
    private var drained = false

    /** Only while the muxer is starting: the one moment a track can be added. */
    fun addTo(muxer: MediaMuxer) {
        muxerIndex =
            runCatching { muxer.addTrack(format) }
                .onFailure {
                    PoseLog.warn(LogCategory.DETECTOR) {
                        "the export muxer refused the audio track, writing video only: ${it.message}"
                    }
                }.getOrDefault(-1)
    }

    /** Up to the video's position, so the file stays interleaved and streams. */
    fun drain(
        muxer: MediaMuxer,
        upToUs: Long,
    ) {
        if (muxerIndex < 0 || drained) return
        while (true) {
            if (!pending) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    drained = true
                    return
                }
                info.set(0, size, extractor.sampleTime, extractor.sampleFlags())
                pending = true
            }
            if (info.presentationTimeUs > upToUs) return

            buffer.position(0)
            buffer.limit(info.size)
            muxer.writeSampleData(muxerIndex, buffer, info)
            pending = false
            extractor.advance()
        }
    }

    fun release() {
        extractor.release()
    }

    private fun MediaExtractor.sampleFlags(): Int =
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0

    companion object {
        private const val MIN_BUFFER = 16 * 1024
        private const val DEFAULT_BUFFER = 256 * 1024
        private const val MAX_BUFFER = 1024 * 1024

        fun open(
            context: Context,
            uri: String,
        ): ExportAudio? {
            val extractor = MediaExtractor()
            return runCatching {
                StaticDetection.openExtractor(extractor, context, uri)
                for (index in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(index)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    if (!mime.startsWith("audio/")) continue
                    extractor.selectTrack(index)
                    return@runCatching ExportAudio(extractor, format)
                }
                null
            }.getOrNull().also { if (it == null) extractor.release() }
        }
    }
}
