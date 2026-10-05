package com.posedetection.camera

import android.content.Context
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionFilter
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.posedetection.ErrorCode
import com.posedetection.LogCategory
import com.posedetection.PoseLog
import java.util.concurrent.Executor

internal enum class Facing { FRONT, BACK }

/** Reported as CAMERA_UNAVAILABLE rather than CAMERA_START_FAILED. */
internal class CameraMissing(
    facing: Facing,
) : IllegalStateException("this device has no $facing camera")

/** Main thread only: CameraX binds on main, so main is the session queue. */
internal class CameraSource(
    private val context: Context,
    private val previewView: PreviewView,
    private val analysisExecutor: Executor,
) {
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null

    /** Null when this camera would not bind a third use case; detection still runs. */
    private var capture: ImageCapture? = null

    /** All a stop unbinds: the provider is per process and may already hold a newer view's session. */
    private var boundConfig: SessionConfig? = null

    /** The bound camera, for torch and anything else that goes through CameraControl. */
    private var camera: Camera? = null
    private var analyzer: ImageAnalysis.Analyzer? = null
    private var lifecycleOwner: LifecycleOwner? = null

    var facing: Facing = Facing.FRONT
        private set

    var isBound: Boolean = false
        private set

    var previewSize: Size = Size(1280, 720)
    var analysisSize: Size = Size(640, 480)

    /** What the bound session delivers; CameraX settles near the asked size. Null until bound. */
    var boundPreviewSize: Size? = null
        private set
    var boundAnalysisSize: Size? = null
        private set

    /** True for `auto`; a pinned lens fails instead of falling back. */
    var facingFallbackAllowed: Boolean = false

    /** False on a front camera and on back cameras without a flash unit. Unbound reads false. */
    val hasTorch: Boolean
        get() = camera?.cameraInfo?.hasFlashUnit() == true

    /**
     * What was asked for, which is not what is lit: a rebind drops the torch, and a lens without a
     * flash never lights at all. Kept so a switch back to a lens that has one restores it rather
     * than leaving the app's button on while the light is off.
     */
    var torchRequested: Boolean = false
        private set

    /** True when the torch is actually lit right now. */
    val torchOn: Boolean
        get() = torchRequested && hasTorch

    /** Called on main with the frame rate the bound session delivers. */
    var onFrameRate: ((Int) -> Unit)? = null

    /** Bumped by every start, pause and release, so a late provider callback can tell it is stale. */
    private var startToken = 0

    private var onBound: (() -> Unit)? = null

    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)

    /** Main thread. Remembers the request even when this camera has no flash; see [torchRequested]. */
    fun setTorch(on: Boolean) {
        torchRequested = on
        applyTorch()
    }

    private fun applyTorch() {
        val control = camera?.takeIf { it.cameraInfo.hasFlashUnit() }?.cameraControl ?: return
        runCatching { control.enableTorch(torchRequested) }
            .onFailure { PoseLog.warn(LogCategory.CAMERA) { "the torch would not switch: ${it.message}" } }
    }

    fun setAnalyzer(analyzer: ImageAnalysis.Analyzer?) {
        this.analyzer = analyzer
        val analysis = this.analysis ?: return
        if (analyzer == null) {
            analysis.clearAnalyzer()
        } else {
            analysis.setAnalyzer(analysisExecutor, analyzer)
        }
    }

    fun start(
        owner: LifecycleOwner,
        facing: Facing,
        onBound: () -> Unit,
        onFailed: (ErrorCode, Throwable?) -> Unit,
    ) {
        val token = ++startToken
        this.lifecycleOwner = owner
        this.facing = facing
        this.onBound = onBound

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (token != startToken) {
                PoseLog.debug(LogCategory.CAMERA) { "ignoring a stale camera provider callback" }
                return@addListener
            }
            try {
                provider = future.get()
                bind(this.facing)
                onBound()
            } catch (error: Throwable) {
                PoseLog.error(LogCategory.CAMERA) { "camera provider failed: ${error.message}" }
                onFailed(startFailure(error), error)
            }
        }, mainExecutor)
    }

    /** Rebinds, restoring the old lens on failure. `onDone` reports the lens actually bound. */
    fun switchTo(
        target: Facing,
        onDone: (Facing) -> Unit,
        onFailed: (ErrorCode, Throwable?) -> Unit,
    ) {
        if (!isBound) {
            onFailed(ErrorCode.CAMERA_SWITCH_FAILED, IllegalStateException("camera is not running"))
            return
        }
        if (target == facing) {
            onDone(facing)
            return
        }
        // Checked here: bind()'s `auto` fallback would rebind the current lens and report success.
        val available = provider?.let { hasCamera(it, target) } ?: false
        if (!available) {
            onFailed(
                ErrorCode.CAMERA_SWITCH_FAILED,
                IllegalStateException("this device has no $target camera"),
            )
            return
        }

        val previous = facing
        try {
            bind(target)
            PoseLog.debug(LogCategory.CAMERA) { "switched $previous to $facing" }
            onDone(facing)
        } catch (error: Throwable) {
            PoseLog.warn(LogCategory.CAMERA) { "switch to $target failed, rolling back: ${error.message}" }
            try {
                bind(previous)
                onFailed(ErrorCode.CAMERA_SWITCH_FAILED, error)
            } catch (rollbackError: Throwable) {
                isBound = false
                onFailed(ErrorCode.CAMERA_UNAVAILABLE, rollbackError)
            }
        }
    }

    fun updateTargetRotation() {
        val rotation = currentRotation()
        analysis?.targetRotation = rotation
        PoseLog.debug(LogCategory.CAMERA) { "target rotation now $rotation" }
    }

    fun setPendingFacing(target: Facing) {
        if (isBound) return
        facing = target
    }

    fun pause() {
        startToken++
        if (!isBound) return
        unbindOwn()
        isBound = false
        PoseLog.info(LogCategory.CAMERA) { "session stopped" }
    }

    fun resume(onFailed: (ErrorCode, Throwable?) -> Unit) {
        if (isBound) return
        val owner = lifecycleOwner ?: return

        // A pause during the provider fetch cancelled that start, so issue it again.
        if (provider == null) {
            start(owner, facing, onBound ?: {}, onFailed)
            return
        }
        try {
            bind(facing)
            onBound?.invoke()
        } catch (error: Throwable) {
            onFailed(startFailure(error), error)
        }
    }

    fun release() {
        startToken++
        analysis?.clearAnalyzer()
        unbindOwn()
        analysis = null
        capture = null
        analyzer = null
        provider = null
        lifecycleOwner = null
        onBound = null
        isBound = false
    }

    private fun unbindOwn() {
        camera = null
        val config = boundConfig ?: return
        boundConfig = null
        runCatching { provider?.unbind(config) }
            .onFailure { PoseLog.warn(LogCategory.CAMERA) { "unbinding the session threw: ${it.message}" } }
    }

    private fun bind(target: Facing) {
        val provider = this.provider ?: throw IllegalStateException("no camera provider")
        val owner = this.lifecycleOwner ?: throw IllegalStateException("no lifecycle owner")

        val lens = resolveAvailable(provider, target)
        // Binding a lens that is not there throws something generic from deep inside CameraX.
        if (!hasCamera(provider, lens)) throw CameraMissing(lens)
        val rotation = currentRotation()

        val preview =
            Preview
                .Builder()
                .setResolutionSelector(previewSelector(previewSize))
                .setTargetRotation(rotation)
                .build()
        // Before the bind, so the session opens once: adding it after forced a reopen that lost the camera.
        preview.surfaceProvider = previewView.surfaceProvider

        // RGBA_8888: CameraX converts natively, far cheaper than a YUV-to-RGB pass in Kotlin.
        val analysis =
            ImageAnalysis
                .Builder()
                .setResolutionSelector(analysisSelector(analysisSize))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setTargetRotation(rotation)
                .build()

        analyzer?.let { analysis.setAnalyzer(analysisExecutor, it) }

        val capture =
            ImageCapture
                .Builder()
                // Latency, not quality: this is a framing shot, and the shutter should feel instant.
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetRotation(rotation)
                .build()

        val selector = selectorFor(lens)
        // All: one lifecycle owner holds one camera, and another view's session would make this throw.
        provider.unbindAll()
        boundConfig = null

        // Preview + analysis + capture needs a LIMITED camera or better. On a LEGACY device the
        // third use case throws, and detection matters more than stills, so we drop it and rebind.
        var bound = bindUseCases(provider, owner, selector, listOf(preview, analysis, capture))
        if (bound == null) {
            PoseLog.warn(LogCategory.CAMERA) {
                "this camera will not bind a capture use case; takePhoto is unavailable"
            }
            bound = bindUseCases(provider, owner, selector, listOf(preview, analysis))
                ?: throw IllegalStateException("this camera will not bind a preview and analysis session")
            this.capture = null
        } else {
            this.capture = capture
        }

        boundConfig = bound.config
        this.camera = bound.camera
        // A rebind opens the camera fresh with the torch off, so put back what was asked for.
        applyTorch()
        boundPreviewSize = preview.resolutionInfo?.resolution
        boundAnalysisSize = analysis.resolutionInfo?.resolution
        val delivered = bound.range?.upper ?: PINNED_FPS
        onFrameRate?.invoke(delivered)

        this.analysis = analysis
        this.facing = lens
        this.isBound = true

        PoseLog.info(LogCategory.CAMERA) {
            "bound $lens preview=${sizeText(boundPreviewSize, previewSize)} " +
                "analysis=${sizeText(boundAnalysisSize, analysisSize)} rotation=$rotation " +
                "frames=${bound.range?.let { "${it.lower}-${it.upper}" } ?: "default"} fps " +
                "stills=${if (this.capture != null) "yes" else "no"}"
        }
    }

    private class BoundSession(
        val config: SessionConfig,
        val range: Range<Int>?,
        val camera: Camera,
    )

    /**
     * Binds one set of use cases, pinning the frame rate when this camera supports it. Returns null
     * when CameraX refuses the combination, which is how a LEGACY camera reports "too many".
     */
    private fun bindUseCases(
        provider: ProcessCameraProvider,
        owner: LifecycleOwner,
        selector: CameraSelector,
        useCases: List<UseCase>,
    ): BoundSession? {
        val range = pinnedRange(provider, selector, useCases)
        val config =
            if (range != null) {
                SessionConfig(useCases = useCases, frameRateRange = range)
            } else {
                SessionConfig(useCases)
            }
        return runCatching {
            BoundSession(config, range, provider.bindToLifecycle(owner, selector, config))
        }.onFailure {
            // Leave nothing half-bound for the retry to trip over.
            runCatching { provider.unbindAll() }
            PoseLog.debug(LogCategory.CAMERA) { "binding ${useCases.size} use cases failed: ${it.message}" }
        }.getOrNull()
    }

    // MARK: Stills

    /**
     * Main thread. [settle] runs on main, exactly once. Detection and the preview keep running.
     */
    fun capturePhoto(
        quality: Double,
        mirrorFront: Boolean,
        settle: (Result<CapturedPhoto>) -> Unit,
    ) {
        if (!isBound) {
            settle(Result.failure(IllegalStateException("the camera is not running")))
            return
        }
        val capture =
            this.capture ?: run {
                settle(Result.failure(IllegalStateException("this device cannot take photos while detecting")))
                return
            }

        val mirror = mirrorFront && facing == Facing.FRONT
        val file =
            runCatching { PhotoFiles.create(context) }
                .getOrElse {
                    settle(Result.failure(it))
                    return
                }

        // The sensor writes a JPEG already; `quality` re-encodes only when it would shrink it.
        capture.targetRotation = currentRotation()
        val metadata = ImageCapture.Metadata().apply { isReversedHorizontal = mirror }
        val options =
            ImageCapture.OutputFileOptions
                .Builder(file)
                .setMetadata(metadata)
                .build()

        capture.takePicture(
            options,
            mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    settle(runCatching { PhotoFiles.describe(file, quality, mirror) })
                }

                override fun onError(error: ImageCaptureException) {
                    file.delete()
                    settle(Result.failure(error))
                }
            },
        )
    }

    /** Pinned so auto-exposure cannot drop to a few fps in a dim room; null keeps the default. */
    private fun pinnedRange(
        provider: ProcessCameraProvider,
        selector: CameraSelector,
        useCases: List<UseCase>,
    ): Range<Int>? {
        val supported =
            runCatching { provider.getCameraInfo(selector).getSupportedFrameRateRanges(SessionConfig(useCases)) }
                .getOrDefault(emptySet())
        val chosen = FrameRates.choose(supported.map { it.lower to it.upper }, PINNED_FPS) ?: return null
        val range = Range(chosen.first, chosen.second)
        val supportedAsSession =
            runCatching {
                provider
                    .getCameraInfo(selector)
                    .isSessionConfigSupported(SessionConfig(useCases = useCases, frameRateRange = range))
            }.getOrDefault(false)
        return range.takeIf { supportedAsSession }
    }

    private fun startFailure(error: Throwable): ErrorCode =
        if (error is CameraMissing) ErrorCode.CAMERA_UNAVAILABLE else ErrorCode.CAMERA_START_FAILED

    private fun resolveAvailable(
        provider: ProcessCameraProvider,
        target: Facing,
    ): Facing {
        if (!facingFallbackAllowed || hasCamera(provider, target)) return target
        val fallback = if (target == Facing.FRONT) Facing.BACK else Facing.FRONT
        if (!hasCamera(provider, fallback)) return target
        PoseLog.info(LogCategory.CAMERA) { "no $target camera on this device, using $fallback" }
        return fallback
    }

    private fun sizeText(
        bound: Size?,
        asked: Size,
    ): String =
        if (bound == null || bound == asked) {
            "${asked.width}x${asked.height}"
        } else {
            "${bound.width}x${bound.height} (asked ${asked.width}x${asked.height})"
        }

    // hasCamera throws CameraInfoUnavailableException, which is the same answer as false here.
    private fun hasCamera(
        provider: ProcessCameraProvider,
        target: Facing,
    ): Boolean = runCatching { provider.hasCamera(selectorFor(target)) }.getOrDefault(false)

    private fun selectorFor(target: Facing): CameraSelector =
        CameraSelector
            .Builder()
            .requireLensFacing(
                if (target == Facing.FRONT) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK,
            ).build()

    private fun currentRotation(): Int = previewView.display?.rotation ?: Surface.ROTATION_0

    private fun previewSelector(size: Size) =
        ResolutionSelector
            .Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
            ).build()

    /** Falls back at most an eighth above the asked short side: MediaPipe resizes to 256x256 anyway. */
    private fun analysisSelector(size: Size): ResolutionSelector {
        val limit = (minOf(size.width, size.height) * ANALYSIS_SLACK).toInt()
        return ResolutionSelector
            .Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
            ).setResolutionFilter(
                ResolutionFilter { sizes, _ ->
                    sizes.filter { minOf(it.width, it.height) <= limit }.ifEmpty { sizes }
                },
            ).build()
    }

    companion object {
        /** At 60 a phone ran warm within minutes, for a skeleton that looked identical. */
        const val PINNED_FPS = 30

        private const val ANALYSIS_SLACK = 1.125f

        fun previewSizeFor(preset: String): Size =
            when (preset) {
                "480p" -> Size(640, 480)
                "1080p" -> Size(1920, 1080)
                else -> Size(1280, 720)
            }

        /** At the preview's 16:9, so the analysis frame and the preview crop the same way. */
        fun analysisSizeFor(preset: String): Size =
            when (preset) {
                "360p" -> Size(640, 360)
                "720p" -> Size(1280, 720)
                else -> Size(854, 480)
            }
    }
}

internal object FrameRates {
    /** The highest floor that tops out at [target], else the fastest range below; null if all are faster. */
    fun choose(
        ranges: List<Pair<Int, Int>>,
        target: Int,
    ): Pair<Int, Int>? {
        val reaching = ranges.filter { it.second == target }
        if (reaching.isNotEmpty()) return reaching.maxBy { it.first }
        val below = ranges.filter { it.second < target }
        return below.maxWithOrNull(compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first })
    }
}
