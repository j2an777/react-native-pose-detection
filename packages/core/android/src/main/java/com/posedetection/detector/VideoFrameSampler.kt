package com.posedetection.detector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import com.posedetection.ErrorCode
import com.posedetection.LogCategory
import com.posedetection.PoseLog
import com.posedetection.export.ExportCanvas
import com.posedetection.export.ExportGl
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * A video's frames in order, each decoded once, handing back only the ones a sampling rate asks
 * for, upright and scaled down.
 *
 * `MediaMetadataRetriever.getFrameAtTime` seeks for every sample. Each seek decodes forward from the
 * keyframe before it and returns a full-size bitmap, so ten samples a second of a clip with
 * two-second keyframes decode most frames several times over. A codec decodes each frame exactly
 * once, and a frame between samples is released without being drawn, so it costs the decode and
 * nothing else. A sampled frame is drawn by GL into a small offscreen surface, which turns, scales
 * and converts it in one pass, and is read back into a bitmap from a small pool that every sample
 * reuses.
 *
 * Built, used and closed on one thread: the EGL context is bound to it.
 */
internal class VideoFrameSampler(
    context: Context,
    uri: String,
    fps: Int,
    startMs: Long,
    endMs: Long,
) : Closeable {
    class Frame(
        /** Valid until the next call to [next], which reuses its pixels for a later sample. */
        val bitmap: Bitmap,
        /** Where the frame sits in the video, in milliseconds. */
        val timestampMs: Long,
    )

    /** The upright frame, which is what landmarks are normalized against. */
    val width: Int
    val height: Int
    val startMs: Long
    val endMs: Long

    private val extractor = MediaExtractor()
    private var codec: MediaCodec? = null
    private var reader: FrameReader? = null
    private val rotation: Int
    private val stepUs = MICROS_PER_SECOND / fps.coerceAtLeast(1)
    private val startUs: Long
    private val endUs: Long
    private val info = MediaCodec.BufferInfo()
    private var inputDone = false
    private var outputDone = false
    private var lastOutputMs = SystemClock.elapsedRealtime()

    private lateinit var slots: SampleSlots<Bitmap>
    private val spare = ArrayDeque<Bitmap>()
    private var handedOut: Bitmap? = null

    init {
        try {
            StaticDetection.openExtractor(extractor, context, uri)
            val track = videoTrack(extractor) ?: throw decodeFailure("no video track in $uri")
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)

            // Unknown for some containers. The range then runs to the end, and progress stays at 0.
            val durationMs =
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION) / MICROS_PER_MILLI
                } else {
                    0L
                }
            val lastMs = if (durationMs > 0) durationMs else Long.MAX_VALUE
            this.startMs = startMs.coerceIn(0L, lastMs)
            this.endMs = if (endMs in 1..lastMs) endMs else lastMs
            endUs = if (this.endMs == Long.MAX_VALUE) Long.MAX_VALUE else this.endMs * MICROS_PER_MILLI
            startUs = this.startMs * MICROS_PER_MILLI
            slots = SampleSlots(startUs, endUs, stepUs)

            rotation =
                if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
            val turned = rotation == QUARTER || rotation == THREE_QUARTERS
            val naturalWidth = format.getInteger(MediaFormat.KEY_WIDTH)
            val naturalHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
            // Capped and even, exactly like an export's canvas, which is the size GL draws into.
            val size =
                ExportCanvas.size(
                    if (turned) naturalHeight else naturalWidth,
                    if (turned) naturalWidth else naturalHeight,
                    MAX_LONG_SIDE,
                )
            width = size[0]
            height = size[1]

            val reader = FrameReader(width, height)
            this.reader = reader

            extractor.seekTo(this.startMs * MICROS_PER_MILLI, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw decodeFailure("the video track has no type")
            val codec = MediaCodec.createDecoderByType(mime)
            this.codec = codec
            // The rotation is applied by GL, so it must not reach the decoder as well: some devices
            // honour it on a surface and the frame would come out turned twice.
            format.setInteger(MediaFormat.KEY_ROTATION, 0)
            codec.configure(format, reader.surface, null, 0)
            codec.start()
            PoseLog.debug(LogCategory.ENGINE) { "decoding $mime with ${codec.name} at ${width}x$height" }
        } catch (error: StaticDetectionError) {
            close()
            throw error
        } catch (error: Exception) {
            close()
            throw decodeFailure(error.message ?: "could not decode $uri")
        }
    }

    /**
     * The next sample, or null at the end of the range: each is the earliest frame in its slot of
     * the sampling grid, in time order whatever order the decoder returns frames in. See
     * [SampleSlots].
     */
    fun next(): Frame? {
        // The caller is done with the frame it had: its pixels can take a later sample.
        handedOut?.let { spare.addLast(it) }
        handedOut = null
        while (true) {
            val sample = slots.settled(outputDone)
            if (sample != null) {
                handedOut = sample.item
                return Frame(sample.item, sample.ptsUs / MICROS_PER_MILLI)
            }
            if (outputDone) return null
            decodeOne()
        }
    }

    private fun decodeOne() {
        val codec = codec ?: return
        val reader = reader ?: return
        if (!inputDone) inputDone = feed(codec)
        val index =
            try {
                codec.dequeueOutputBuffer(info, TIMEOUT_US)
            } catch (error: IllegalStateException) {
                throw decodeFailure(error.message ?: "the video could not be decoded")
            }
        if (index < 0) {
            if (SystemClock.elapsedRealtime() - lastOutputMs > STALL_MS) {
                throw decodeFailure("the decoder stopped producing frames")
            }
            return
        }
        lastOutputMs = SystemClock.elapsedRealtime()
        val ptsUs = info.presentationTimeUs
        val ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
        val slot = if (info.size > 0) slots.wants(ptsUs) else null
        PoseLog.trace(LogCategory.ENGINE) { "decoded ${ptsUs / MICROS_PER_MILLI} ms, slot ${slot ?: "none"}" }
        // Only a wanted frame is drawn. Every other one is handed back undrawn, which is free.
        codec.releaseOutputBuffer(index, slot != null)
        if (ended || slots.pastRange()) outputDone = true
        if (slot == null) return

        val current = slots.itemIn(slot)
        val bitmap = current ?: spare.removeFirstOrNull() ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        if (!reader.read(rotation, bitmap)) {
            PoseLog.warn(LogCategory.ENGINE) { "the frame at ${ptsUs / MICROS_PER_MILLI} ms never reached the reader" }
            if (current == null) spare.addLast(bitmap)
            return
        }
        slots.fill(slot, ptsUs, bitmap)
    }

    /** How far through the range a frame is, 0 to 1. */
    fun progress(frame: Frame): Float {
        if (endMs == Long.MAX_VALUE) return 0f
        val span = (endMs - startMs).coerceAtLeast(1L)
        return ((frame.timestampMs - startMs).toFloat() / span).coerceIn(0f, 1f)
    }

    /** True once the extractor has nothing left and the end of stream has been queued. */
    private fun feed(codec: MediaCodec): Boolean {
        val index = codec.dequeueInputBuffer(TIMEOUT_US)
        if (index < 0) return false
        val buffer = codec.getInputBuffer(index) ?: return false
        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) {
            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            return true
        }
        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
        extractor.advance()
        return false
    }

    override fun close() {
        codec?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        codec = null
        reader?.release()
        reader = null
        runCatching { extractor.release() }
        if (this::slots.isInitialized) slots.drain().forEach { it.recycle() }
        spare.forEach { it.recycle() }
        spare.clear()
        handedOut?.recycle()
        handedOut = null
    }

    private fun videoTrack(extractor: MediaExtractor): Int? {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return index
        }
        return null
    }

    private fun decodeFailure(message: String) = StaticDetectionError(ErrorCode.VIDEO_DECODE_FAILED, message)

    companion object {
        /**
         * The long side frames are drawn at. The detector sees 224 pixels of the whole frame and the
         * landmark model a 256-pixel crop around the body, so at 960 that crop is sampled down
         * rather than stretched for anybody taller than about 40% of a landscape frame. That is
         * already better than the live camera's 480p, and a file has no deadline to trade detail for.
         */
        const val MAX_LONG_SIDE = 960

        private const val MICROS_PER_SECOND = 1_000_000L
        private const val MICROS_PER_MILLI = 1_000L
        private const val TIMEOUT_US = 10_000L
        private const val STALL_MS = 5_000L
        private const val QUARTER = 90
        private const val THREE_QUARTERS = 270
    }
}

/**
 * The GL half of [VideoFrameSampler]: an offscreen surface the size of a sample, the decoder's
 * frames arriving as an external texture, and a read back into a bitmap.
 *
 * `glReadPixels` returns the bottom row first and a bitmap starts with the top one, so the quad is
 * drawn upside down, which lands the picture in the bitmap upright. The rotation quads are the
 * export's own, so a sampled frame and an exported one are turned by the same table.
 */
private class FrameReader(
    private val width: Int,
    private val height: Int,
) {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private val program: Int
    private val texture: Int
    private val surfaceTexture: SurfaceTexture
    val surface: Surface

    private val frameAvailable = Object()
    private var hasFrame = false
    private val stMatrix = FloatArray(ExportGl.MATRIX_SIZE)
    private val positions = floats(ExportGl.QUAD_FLOATS)
    private val texCoords = floats(ExportGl.TEX_IDENTITY.size).apply { put(ExportGl.TEX_IDENTITY).position(0) }
    private val pixels = ByteBuffer.allocateDirect(width * height * BYTES_PER_PIXEL).order(ByteOrder.nativeOrder())

    init {
        setUpEgl()
        program = ExportGl.buildProgram(ExportGl.VERTEX_SHADER, ExportGl.EXTERNAL_FRAGMENT_SHADER)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        surfaceTexture = SurfaceTexture(texture)
        surfaceTexture.setDefaultBufferSize(width, height)
        // Delivered on main, never on the thread that built this: a thread with a looper that is
        // blocked waiting for the frame would never run the callback that says it arrived.
        surfaceTexture.setOnFrameAvailableListener({
            synchronized(frameAvailable) {
                hasFrame = true
                frameAvailable.notifyAll()
            }
        }, Handler(Looper.getMainLooper()))
        surface = Surface(surfaceTexture)
        GLES20.glViewport(0, 0, width, height)
    }

    private fun setUpEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "could not initialise EGL" }
        val attributes =
            intArrayOf(
                EGL14.EGL_RED_SIZE,
                8,
                EGL14.EGL_GREEN_SIZE,
                8,
                EGL14.EGL_BLUE_SIZE,
                8,
                EGL14.EGL_ALPHA_SIZE,
                8,
                EGL14.EGL_RENDERABLE_TYPE,
                EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE,
                EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
        val configs = arrayOfNulls<EGLConfig>(1)
        val found = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, found, 0) && found[0] > 0) {
            "no EGL config for an offscreen surface"
        }
        context =
            EGL14.eglCreateContext(
                display,
                configs[0],
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0,
            )
        check(context != EGL14.EGL_NO_CONTEXT) { "could not create an EGL context" }
        eglSurface =
            EGL14.eglCreatePbufferSurface(
                display,
                configs[0],
                intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE),
                0,
            )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "could not create an offscreen surface" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "could not bind the EGL context" }
    }

    /** Waits for the frame the decoder was just told to draw, then reads it upright into [into]. */
    fun read(
        rotationDegrees: Int,
        into: Bitmap,
    ): Boolean {
        synchronized(frameAvailable) {
            val deadline = SystemClock.elapsedRealtime() + ExportGl.FRAME_TIMEOUT_MS
            while (!hasFrame) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) return false
                frameAvailable.wait(remaining)
            }
            hasFrame = false
        }
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(stMatrix)

        val quad = ExportGl.quadFor(rotationDegrees)
        positions.clear()
        for (index in quad.indices) {
            // Odd entries are y: flipped, so the bottom-up read lands top-down in the bitmap.
            positions.put(if (index % 2 == 1) -quad[index] else quad[index])
        }
        positions.position(0)

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTexture"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, stMatrix, 0)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, positions)
        val coordinate = GLES20.glGetAttribLocation(program, "aTextureCoord")
        GLES20.glEnableVertexAttribArray(coordinate)
        texCoords.position(0)
        GLES20.glVertexAttribPointer(coordinate, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(coordinate)

        pixels.rewind()
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        pixels.rewind()
        into.copyPixelsFromBuffer(pixels)
        return true
    }

    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        surface.release()
        surfaceTexture.release()
    }

    private fun floats(count: Int): FloatBuffer =
        ByteBuffer
            .allocateDirect(count * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

    private companion object {
        const val BYTES_PER_PIXEL = 4
    }
}
