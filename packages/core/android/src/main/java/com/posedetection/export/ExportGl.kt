package com.posedetection.export

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Decoder to encoder through GL: a Canvas cannot draw on an encoder's input surface. */
internal class ExportGl(
    encoderSurface: Surface,
) {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var externalProgram = 0
    private var flatProgram = 0
    private var externalTexture = 0
    private var overlayTexture = 0

    lateinit var decoderSurface: Surface
        private set

    private lateinit var surfaceTexture: SurfaceTexture
    private val frameAvailable = Object()
    private var hasFrame = false

    private val stMatrix = FloatArray(MATRIX_SIZE)
    private val positions =
        ByteBuffer
            .allocateDirect(QUAD_FLOATS * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    private val texCoords = floatBuffer(TEX_IDENTITY)
    private val overlayTexCoords = floatBuffer(TEX_FLIPPED)

    init {
        setUpEgl(encoderSurface)
        externalProgram = buildProgram(VERTEX_SHADER, EXTERNAL_FRAGMENT_SHADER)
        flatProgram = buildProgram(VERTEX_SHADER, FLAT_FRAGMENT_SHADER)
        externalTexture = createTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        overlayTexture = createTexture(GLES20.GL_TEXTURE_2D)

        surfaceTexture = SurfaceTexture(externalTexture)
        surfaceTexture.setOnFrameAvailableListener {
            synchronized(frameAvailable) {
                hasFrame = true
                frameAvailable.notifyAll()
            }
        }
        decoderSurface = Surface(surfaceTexture)
    }

    private fun setUpEgl(encoderSurface: Surface) {
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
                // Without this the config cannot be used with a MediaCodec input surface.
                EGL_RECORDABLE_ANDROID,
                1,
                EGL14.EGL_NONE,
            )
        val configs = arrayOfNulls<EGLConfig>(1)
        val found = IntArray(1)
        check(
            EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, found, 0) && found[0] > 0,
        ) { "no EGL config the encoder can record from" }

        context =
            EGL14.eglCreateContext(
                display,
                configs[0],
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0,
            )
        check(context != EGL14.EGL_NO_CONTEXT) { "could not create an EGL context" }

        surface =
            EGL14.eglCreateWindowSurface(
                display,
                configs[0],
                encoderSurface,
                intArrayOf(EGL14.EGL_NONE),
                0,
            )
        check(surface != EGL14.EGL_NO_SURFACE) { "could not wrap the encoder's surface" }
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "could not bind the EGL context" }
    }

    fun awaitFrame(): Boolean {
        synchronized(frameAvailable) {
            val deadline = System.currentTimeMillis() + FRAME_TIMEOUT_MS
            while (!hasFrame) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return false
                frameAvailable.wait(remaining)
            }
            hasFrame = false
        }
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(stMatrix)
        return true
    }

    /** Rotation is baked in, not left as metadata that many players ignore and show sideways. */
    fun drawFrame(rotationDegrees: Int) {
        GLES20.glDisable(GLES20.GL_BLEND)
        positions.clear()
        positions.put(quadFor(rotationDegrees)).position(0)
        draw(externalProgram, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTexture, texCoords, stMatrix)
    }

    fun drawOverlay(
        overlay: Bitmap,
        changed: Boolean,
    ) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexture)
        if (changed) GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, overlay, 0)

        GLES20.glEnable(GLES20.GL_BLEND)
        // The overlay bitmap is premultiplied, which is what Android hands back from a Canvas.
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        positions.clear()
        positions.put(QUAD_0).position(0)
        draw(flatProgram, GLES20.GL_TEXTURE_2D, overlayTexture, overlayTexCoords, IDENTITY)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun draw(
        program: Int,
        target: Int,
        texture: Int,
        coords: FloatBuffer,
        matrix: FloatArray,
    ) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTexture"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, matrix, 0)

        val position = GLES20.glGetAttribLocation(program, "aPosition")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, positions)

        val coordinate = GLES20.glGetAttribLocation(program, "aTextureCoord")
        GLES20.glEnableVertexAttribArray(coordinate)
        coords.position(0)
        GLES20.glVertexAttribPointer(coordinate, 2, GLES20.GL_FLOAT, false, 0, coords)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(coordinate)
    }

    fun setViewport(
        width: Int,
        height: Int,
    ) {
        GLES20.glViewport(0, 0, width, height)
    }

    fun present(timeNanos: Long) {
        EGLExt.eglPresentationTimeANDROID(display, surface, timeNanos)
        EGL14.eglSwapBuffers(display, surface)
    }

    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        surface = EGL14.EGL_NO_SURFACE

        if (this::decoderSurface.isInitialized) decoderSurface.release()
        if (this::surfaceTexture.isInitialized) surfaceTexture.release()
    }

    private fun createTexture(target: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(target, ids[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun floatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer
            .allocateDirect(values.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        /** Long enough for any real decoder, short enough that one that stalls does not hang a job. */
        const val FRAME_TIMEOUT_MS = 2_500L
        const val MATRIX_SIZE = 16
        const val QUAD_FLOATS = 8

        fun buildProgram(
            vertex: String,
            fragment: String,
        ): Int {
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, vertex))
            GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
            GLES20.glLinkProgram(program)

            val linked = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
            check(linked[0] != 0) { "could not link the export shader: ${GLES20.glGetProgramInfoLog(program)}" }
            return program
        }

        private fun compile(
            type: Int,
            source: String,
        ): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)

            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            check(compiled[0] != 0) { "could not compile the export shader: ${GLES20.glGetShaderInfoLog(shader)}" }
            return shader
        }

        fun quadFor(rotationDegrees: Int): FloatArray =
            when (((rotationDegrees % 360) + 360) % 360) {
                90 -> QUAD_90
                180 -> QUAD_180
                270 -> QUAD_270
                else -> QUAD_0
            }

        /** The positions turn, not the texture coordinates, so the decoder's matrix still applies. */
        val QUAD_0 = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val QUAD_90 = floatArrayOf(-1f, 1f, -1f, -1f, 1f, 1f, 1f, -1f)
        val QUAD_180 = floatArrayOf(1f, 1f, -1f, 1f, 1f, -1f, -1f, -1f)
        val QUAD_270 = floatArrayOf(1f, -1f, 1f, 1f, -1f, -1f, -1f, 1f)

        val TEX_IDENTITY = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        /** A bitmap's first row is its top one and GL's is its bottom one. */
        val TEX_FLIPPED = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)

        val IDENTITY =
            floatArrayOf(
                1f,
                0f,
                0f,
                0f,
                0f,
                1f,
                0f,
                0f,
                0f,
                0f,
                1f,
                0f,
                0f,
                0f,
                0f,
                1f,
            )

        const val VERTEX_SHADER =
            """
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = aPosition;
                vTextureCoord = (uTexMatrix * aTextureCoord).xy;
            }
            """

        const val EXTERNAL_FRAGMENT_SHADER =
            """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
            """

        const val FLAT_FRAGMENT_SHADER =
            """
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform sampler2D sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
            """
    }
}
