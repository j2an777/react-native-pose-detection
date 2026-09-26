package com.posedetection.camera

import android.content.Context
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
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

/**
 * Owns the capture session. Knows about frames, not poses.
 *
 * **Every field here is main thread only.** CameraX requires `bindToLifecycle` on main, so main
 * is the serial session queue rather than a second queue racing it. Analysis runs on
 * [analysisExecutor] so inference never blocks the UI.
 */
internal class CameraSource(
    private val context: Context,
    private val previewView: PreviewView,
    private val analysisExecutor: Executor,
) {
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null

    /**
     * This source's own session, which is all it ever unbinds when it stops. The provider is one per
     * process: a view unmounting while its replacement is already bound used to `unbindAll()` on
     * its way out and take the new view's camera with it, leaving a preview with no frames.
     */
    private var boundConfig: SessionConfig? = null
    private var analyzer: ImageAnalysis.Analyzer? = null
    private var lifecycleOwner: LifecycleOwner? = null

    var facing: Facing = Facing.FRONT
        private set

    var isBound: Boolean = false
        private set

    var previewSize: Size = Size(1280, 720)
    var analysisSize: Size = Size(640, 480)

    /** `auto` prefers front and falls back to back. A pinned lens fails instead of falling back. */
    var facingFallbackAllowed: Boolean = false

    /** Told, on main, what the bound camera actually delivers once it has been pinned. */
    var onFrameRate: ((Int) -> Unit)? = null

    /** Tells the provider callback, which lands a turn later, whether its session still exists. */
    private var startToken = 0

    /** Kept so `resume()` can re-issue a start whose provider fetch a `pause()` cancelled. */
    private var onBound: (() -> Unit)? = null

    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)

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
            // A pause, a release or a newer start landed while the provider was being fetched, so
            // this binding is no longer wanted. Without the token it would bring the camera and the
            // landmarker back up behind a session that has already stopped.
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
                onFailed(ErrorCode.CAMERA_START_FAILED, error)
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
        // The `auto` fallback belongs on the first bind, not here. Letting it run would rebind the
        // lens that is already up, flash the preview, and resolve the switch as a success that
        // changed nothing. guides/camera-control.md promises a CAMERA_SWITCH_FAILED instead.
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
                // The previous camera is gone too. This is no longer recoverable.
                isBound = false
                onFailed(ErrorCode.CAMERA_UNAVAILABLE, rollbackError)
            }
        }
    }

    /** Called on a configuration change so the analysis buffer keeps arriving upright. */
    fun updateTargetRotation() {
        val rotation = currentRotation()
        analysis?.targetRotation = rotation
        PoseLog.debug(LogCategory.CAMERA) { "target rotation now $rotation" }
    }

    /** Parks a facing change made while unbound so the next bind picks it up. */
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

        // A pause that landed while the provider was still being fetched cancelled that start and
        // left `provider` null, so there is nothing to rebind to. Re-issuing the start is what
        // makes pause-then-resume during startup recoverable instead of permanently dead.
        if (provider == null) {
            start(owner, facing, onBound ?: {}, onFailed)
            return
        }
        try {
            bind(facing)
            onBound?.invoke()
        } catch (error: Throwable) {
            onFailed(ErrorCode.CAMERA_START_FAILED, error)
        }
    }

    fun release() {
        startToken++
        analysis?.clearAnalyzer()
        unbindOwn()
        analysis = null
        analyzer = null
        provider = null
        lifecycleOwner = null
        onBound = null
        isBound = false
    }

    /** A session another view has since replaced is already unbound, and unbinding it again is a no-op. */
    private fun unbindOwn() {
        val config = boundConfig ?: return
        boundConfig = null
        runCatching { provider?.unbind(config) }
            .onFailure { PoseLog.warn(LogCategory.CAMERA) { "unbinding the session threw: ${it.message}" } }
    }

    private fun bind(target: Facing) {
        val provider = this.provider ?: throw IllegalStateException("no camera provider")
        val owner = this.lifecycleOwner ?: throw IllegalStateException("no lifecycle owner")

        val lens = resolveAvailable(provider, target)
        val rotation = currentRotation()

        val preview =
            Preview
                .Builder()
                .setResolutionSelector(previewSelector(previewSize))
                .setTargetRotation(rotation)
                .build()
        // Before the bind, so the session opens once with both streams. Attached after, it opened
        // with the analysis stream alone and was rebuilt at once to add the preview; that second
        // open raced the camera still closing from the session before it, CameraX reported the
        // camera unavailable and did not retry, and a remount sat with a preview and no frames.
        preview.surfaceProvider = previewView.surfaceProvider

        // RGBA_8888 is converted by CameraX in native code (libyuv), which is far cheaper than a
        // YUV to RGB pass in Kotlin and hands MediaPipe the one layout it takes without a copy.
        // KEEP_ONLY_LATEST means a slow frame is dropped rather than queued, so the pipeline
        // degrades in latency instead of falling behind forever.
        val analysis =
            ImageAnalysis
                .Builder()
                .setResolutionSelector(analysisSelector(analysisSize))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setTargetRotation(rotation)
                .build()

        analyzer?.let { analysis.setAnalyzer(analysisExecutor, it) }

        val selector = selectorFor(lens)
        val useCases = listOf<UseCase>(preview, analysis)
        val range = pinnedRange(provider, selector, useCases)
        val config =
            if (range !=
                null
            ) {
                SessionConfig(useCases = useCases, frameRateRange = range)
            } else {
                SessionConfig(useCases)
            }

        // All, not just this source's own: one lifecycle owner can hold one camera, so the newest
        // session takes it, and a view still bound somewhere else would make this bind throw.
        provider.unbindAll()
        boundConfig = null
        provider.bindToLifecycle(owner, selector, config)
        boundConfig = config
        val delivered = range?.upper ?: PINNED_FPS
        onFrameRate?.invoke(delivered)

        this.analysis = analysis
        this.facing = lens
        this.isBound = true

        PoseLog.info(LogCategory.CAMERA) {
            "bound $lens preview=${previewSize.width}x${previewSize.height} " +
                "analysis=${analysisSize.width}x${analysisSize.height} rotation=$rotation " +
                "frames=${range?.let { "${it.lower}-${it.upper}" } ?: "default"} fps"
        }
    }

    /**
     * The frame rate range the session is bound at: the steadiest one this camera offers for these
     * use cases that tops out at [PINNED_FPS]. Left to itself, auto-exposure is free to drop to a
     * handful of frames a second in a dim room, which halves the skeleton's rate with the camera's.
     * Null leaves the camera's own default, which is what a device that offers no such range gets.
     */
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

    /** Binding a lens the device lacks throws and leaves a dead preview, so resolve first. */
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

    /**
     * The analysis stream at the preview's aspect, and never much bigger than asked for. Every pixel
     * past what MediaPipe keeps is converted to RGBA, copied and thrown away inside the graph, which
     * resizes to 256 by 256. A camera without the exact size used to fall back higher, to 720p and
     * past it; the filter keeps the fallback within an eighth of the requested short side.
     */
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
        /**
         * The rate the sensor is held at. Thirty, because inference is never run faster than frames
         * arrive and a phone asked for 60 ran warm within minutes for a skeleton that looked identical.
         */
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

/**
 * Which camera frame rate range to pin, kept apart from CameraX so the choice is testable on its own.
 */
internal object FrameRates {
    /**
     * `[target, target]` where the camera offers it; otherwise the range that tops out at [target]
     * with the highest floor, so auto-exposure has the least room to slow down; otherwise the
     * fastest that stays below it. Null when every range is faster, which leaves the default alone.
     */
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
