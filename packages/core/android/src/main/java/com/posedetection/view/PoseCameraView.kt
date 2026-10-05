package com.posedetection.view

import android.Manifest
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.widget.FrameLayout
import androidx.camera.core.ImageAnalysis
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import com.posedetection.ErrorCode
import com.posedetection.LogCategory
import com.posedetection.PoseLog
import com.posedetection.Skeleton
import com.posedetection.camera.CameraSource
import com.posedetection.camera.Facing
import com.posedetection.camera.FrameConverter
import com.posedetection.detector.DelegateRequest
import com.posedetection.detector.DetectorCache
import com.posedetection.detector.PoseDetector
import com.posedetection.detector.StartPlan
import com.posedetection.engine.Continuity
import com.posedetection.engine.DEFAULT_FLUSH_MS
import com.posedetection.engine.DEFAULT_THROTTLE_MS
import com.posedetection.engine.DataMode
import com.posedetection.engine.DataSettings
import com.posedetection.engine.FrameContext
import com.posedetection.engine.FrameRingBuffer
import com.posedetection.engine.FrameShape
import com.posedetection.engine.FrameStream
import com.posedetection.engine.FrameStreams
import com.posedetection.engine.Geometry
import com.posedetection.engine.OneEuroFilter
import com.posedetection.engine.PoseBox
import com.posedetection.engine.TriggerEngine
import com.posedetection.engine.TriggerFiring
import com.posedetection.engine.TriggerSpec
import com.posedetection.engine.Upright
import com.posedetection.engine.VisibilityClock
import com.posedetection.performance.Budgets
import com.posedetection.performance.Calibrator
import com.posedetection.performance.CameraGeometry
import com.posedetection.performance.GeometryResolver
import com.posedetection.performance.IdleRates
import com.posedetection.performance.LimitedBy
import com.posedetection.performance.Profile
import com.posedetection.performance.RateDecision
import com.posedetection.performance.RateGovernor
import com.posedetection.performance.RateRequest
import com.posedetection.performance.ThermalHysteresis
import com.posedetection.performance.ThermalMonitor
import com.posedetection.performance.ThermalPolicy
import com.posedetection.performance.calibratorFor
import com.posedetection.performance.deviceMemoryGiB
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.views.ExpoView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

class PoseCameraView(
    context: Context,
    appContext: AppContext,
) : ExpoView(context, appContext) {
    override val shouldUseAndroidLayout = true

    private val onReady by EventDispatcher<Map<String, Any?>>()
    private val onError by EventDispatcher<Map<String, Any?>>()
    private val onCameraChange by EventDispatcher<Map<String, Any?>>()

    /** Carries nothing. JavaScript answers it with `drainFrames()`, see ADR 0008. */
    private val onFrames by EventDispatcher<Map<String, Any?>>()

    /** Scalars plus a claim ticket. The frame cannot ride it, see ADR 0009. */
    private val onTrigger by EventDispatcher<Map<String, Any?>>()

    private val onPerformanceChange by EventDispatcher<Map<String, Any?>>()
    private val onLog by EventDispatcher<Map<String, Any?>>()

    private val previewView =
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    private val overlayView = OverlayView(context)

    // Inference runs here, synchronously per frame; landmarkers build on DetectorCache's threads.
    private val analysisExecutor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "pose-analysis").apply { priority = Thread.NORM_PRIORITY + 1 }
        }

    private val camera = CameraSource(context, previewView, analysisExecutor)
    private val converter = FrameConverter()

    /** `View.post` holds runnables while detached, which would strand a landmarker unclosed. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Written on main, read on the analysis thread, so a teardown is seen on the next frame. */
    @Volatile
    private var detector: PoseDetector? = null

    /** True for a CPU stand-in while a GPU one builds. Not measured: it would pace the GPU. */
    @Volatile
    private var detectorProvisional = false

    /** Set on a build thread, answered by the next frame. See [primeFromCamera]. */
    private val primingRequest = AtomicReference<PrimingRequest?>(null)

    @Volatile
    private var feeding = true

    private val releaseParked =
        Runnable {
            PoseLog.info(LogCategory.DETECTOR) { "the landmarker went unused, releasing it" }
            releaseDetector()
            releaseConverter()
        }

    /** The GPU failed at runtime: `gpu_fallback` is reported once the CPU rebuild lands. */
    private var fellBackToCpu = false

    /** Analysis thread only. */
    private val gpuFailureTimes = ArrayDeque<Long>(GPU_FAILURE_LIMIT)
    private var modelFileName: String? = null

    /** Main thread only, like the build settings below. */
    private var detectorPending = false

    /** Bumped on main by each teardown; builds of an older generation are never installed. */
    @Volatile
    private var detectorGeneration = 0

    private var detectorRequest: DelegateRequest? = null
    private var detectorMaxPoses = 0
    private var detectorMinConfidence = 0f

    /** Survives [releaseDetector] so `getState` reports the pipeline, not instance liveness. */
    private var resolvedDelegate: String? = null

    private val lastDetectionErrorMs = AtomicLong(0)

    /** A switch is reported on the new camera's first frame, or by [switchTimeout] if none. */
    private val awaitingFirstFrame = AtomicBoolean(false)
    private var pendingSwitchDone: (() -> Unit)? = null
    private val switchTimeout = Runnable { completeSwitch() }

    /** The bitmap is never rotated, so this is what stands the landmarks and frame size upright. */
    @Volatile
    private var frameRotationDegrees = 0

    /** Remembered because the current activity can change before detach. */
    private var observedOwner: LifecycleOwner? = null

    /** Results below this timestamp came from the previous camera. */
    private val staleBefore = AtomicLong(0)

    private val landmarkBuffer = FloatArray(Skeleton.LANDMARK_COUNT * Skeleton.LANDMARK_STRIDE)
    private val worldBuffer = FloatArray(Skeleton.LANDMARK_COUNT * Skeleton.LANDMARK_STRIDE)

    private val frames = FrameRingBuffer()

    /** Read synchronously on the JavaScript thread, see ADR 0010. */
    private val stream = FrameStream(frames, { feeding && detector != null }) { liveState() }
    private var streamId: Int? = null
    private val triggers = TriggerEngine()
    private val smoothing = OneEuroFilter()

    /** Analysis thread only; [clockedWith] is the landmarker whose frames it has seen. */
    private val visibilityClock = VisibilityClock()
    private var clockedWith: PoseDetector? = null

    private var visibilityClocked = false
    private val calibrator = calibratorFor(context)
    private val thermalMonitor = ThermalMonitor(context)

    /** Written on main, read on the analysis thread. */
    @Volatile
    private var rate = RateDecision(fps = 30, limitedBy = LimitedBy.CAMERA)

    @Volatile
    private var idleRates: IdleRates? = Budgets.of(Profile.AUTO).idle

    /** Null while a pose is recent. Written on the analysis thread. */
    @Volatile
    private var idleFps: Int? = null

    @Volatile
    private var cameraFps = CameraSource.PINNED_FPS

    /** Main thread only. A change takes a session restart. */
    private var geometry = CameraGeometry(preview = "720p", analysis = "480p")

    private val memoryGiB = deviceMemoryGiB(context)

    /** Main thread only, with [lowPower]: sampled on a timer, never on the frame path. */
    private val thermal = ThermalHysteresis()
    private var lowPower = false
    private val heatSampler =
        object : Runnable {
            override fun run() {
                mainHandler.postDelayed(this, ThermalMonitor.SAMPLE_INTERVAL_MS)
                sampleHeat()
            }
        }

    /** Analysis thread only. */
    private var nextDetectDueMs = 0.0

    /** Written on both threads. Volatile also because a 64-bit read can tear on armeabi-v7a. */
    @Volatile
    private var lastPoseMs = 0L

    /** Frames the model answered, not frames the camera delivered. Analysis thread only. */
    private var framesInWindow = 0
    private var fpsWindowStartMs = 0L

    @Volatile
    private var measuredFps = 0

    @Volatile
    private var lastResultMs = 0L

    /** Reused across frames: no allocation on the inference path. */
    private val frameContext = FrameContext()
    private val firings = ArrayList<TriggerFiring>(4)

    private val previousLandmarks = FloatArray(Skeleton.LANDMARK_COUNT * Skeleton.LANDMARK_STRIDE)
    private var hasPreviousLandmarks = false

    /** Written on main, read per frame. Volatile so the arrays inside are published with it. */
    @Volatile
    private var frameLayout: FrameShape? = null

    /** Analysis thread only. */
    private var previousComX = Float.NaN
    private var previousComY = Float.NaN

    private var previousBox: PoseBox? = null

    /** Also cleared on main by a camera switch; at zero, no velocity is measured. */
    @Volatile
    private var previousFrameMs = 0.0

    /** At most one tick in flight: one drain takes everything buffered. */
    private val tickPending = AtomicBoolean(false)

    private val lastEmitMs = AtomicLong(0)

    /** The first attached view flushes the shared log to `onLog`; with none, the module does. */
    private val logFlush =
        object : Runnable {
            override fun run() {
                mainHandler.postDelayed(this, PoseLog.FLUSH_MS)
                val entries = PoseLog.takeBatch(this@PoseCameraView) ?: return
                onLog(mapOf("entries" to entries))
            }
        }

    /** One instance, so a tick allocates no Runnable. */
    private val emitFramesTick =
        Runnable {
            tickPending.set(false)
            onFrames(EMPTY_PAYLOAD)
        }

    // Props, applied together in onPropsUpdated so a render that changes several rebinds once.
    private var propFacing: String = "auto"
    private var propDelegate: String = "auto"
    private var propActive: Boolean = true
    private var propDetection: Boolean = true
    private var propMaxPoses: Int = 1

    /** null is `'auto'`, resolved by [resolvedMinConfidence]. */
    private var propMinConfidence: Float? = null

    private var propPreview: String = "auto"
    private var propAnalysis: String = "auto"
    private var overlayEnabled: Boolean = true

    /** [overlayEnabled] for the analysis thread: while off, results skip the overlay. */
    @Volatile
    private var overlayOn = true
    private var pendingOverlayConfig: OverlayConfig = OverlayConfig()
    private var propMode: DataMode = DataMode.OFF

    // Written on main, read per frame, 64-bit: volatile against tearing, as [lastPoseMs].
    @Volatile
    private var propThrottleMs: Long = DEFAULT_THROTTLE_MS

    @Volatile
    private var propFlushMs: Long = DEFAULT_FLUSH_MS
    private var propLandmarks: Boolean = true
    private var propWorldLandmarks: Boolean = false
    private var propAngleJoints: Array<String> = EMPTY_NAMES
    private var propSelection: IntArray? = null
    private var propProfile: Profile = Profile.AUTO
    private var propTargetFps: Int? = null
    private var propThermalPolicy: ThermalPolicy = ThermalPolicy.ADAPTIVE
    private var propSmoothing = false
    private var propMinCutoff = OneEuroFilter.DEFAULT_MIN_CUTOFF
    private var propBeta = OneEuroFilter.DEFAULT_BETA

    private var started = false
    private var readySent = false

    init {
        val container =
            FrameLayout(context).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                setBackgroundColor(Color.BLACK)
            }
        container.addView(
            previewView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        container.addView(
            overlayView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        addView(container)

        // The camera's delivered rate is the governor's ceiling, known only once a lens is bound.
        camera.onFrameRate = { fps ->
            if (fps != cameraFps) {
                cameraFps = fps
                applyPerformance(reason = null)
            }
        }

        // Before any props: a frame landing first would otherwise find no layout and be dropped.
        applyFrameLayout()
    }

    // region props

    fun setFacing(value: String) {
        propFacing = value
    }

    fun setDelegate(value: String) {
        propDelegate = value
    }

    fun setActive(value: Boolean) {
        propActive = value
    }

    fun setDetection(value: Boolean) {
        propDetection = value
    }

    fun setTorch(value: Boolean) {
        if (value == camera.torchRequested) return
        camera.setTorch(value)
        emitCameraChange()
    }

    fun setMaxPoses(value: Int) {
        propMaxPoses = value.coerceIn(1, 5)
    }

    fun setMinConfidence(value: Double?) {
        propMinConfidence = value?.toFloat()?.coerceIn(0.1f, 1f)
    }

    private fun resolvedMinConfidence(): Float =
        propMinConfidence ?: if (propMaxPoses > 1) MULTI_POSE_CONFIDENCE else MIN_CONFIDENCE

    fun setResolution(value: String) {
        propPreview = value
    }

    fun setAnalysisResolution(value: String) {
        propAnalysis = value
    }

    internal fun setOverlay(
        enabled: Boolean,
        config: OverlayConfig,
    ) {
        overlayEnabled = enabled
        pendingOverlayConfig = config
    }

    internal fun setData(config: DataSettings) {
        propMode = config.mode
        propThrottleMs = config.throttleMs
        propFlushMs = config.flushMs
        propLandmarks = config.landmarks
        propWorldLandmarks = config.worldLandmarks
    }

    /** Already resolved and ordered by JavaScript: re-deriving that here could only disagree. */
    internal fun setAngleJoints(joints: Array<String>) {
        propAngleJoints = joints
    }

    internal fun setSelection(indices: IntArray?) {
        propSelection = indices
    }

    internal fun setProfile(value: Profile) {
        propProfile = value
    }

    /** Null is `auto`, the only value calibration may move. */
    internal fun setTargetFps(value: Int?) {
        propTargetFps = value?.coerceIn(MIN_TARGET_FPS, MAX_TARGET_FPS)
    }

    internal fun setThermalPolicy(value: ThermalPolicy) {
        propThermalPolicy = value
    }

    internal fun setSmoothing(
        enabled: Boolean,
        minCutoff: Float,
        beta: Float,
    ) {
        propSmoothing = enabled
        propMinCutoff = minCutoff
        propBeta = beta
    }

    internal fun setTriggers(specs: List<TriggerSpec>) {
        // Now, not in onPropsUpdated: applied late, one frame would run against the old set.
        triggers.setTriggers(specs)
    }

    fun onPropsUpdated() {
        overlayView.config = pendingOverlayConfig
        applyOverlayEnabled()

        applyFrameLayout()
        smoothing.configure(propMinCutoff, propBeta)
        applyPerformance(reason = null)

        // Only props move geometry, never calibration or heat: nothing learned restarts the camera.
        val next = resolveGeometry()
        val geometryChanged = next != geometry
        adopt(next)
        // Only 'auto' is documented to fall back to the other lens; a pinned one stays pinned.
        val pinnedFacing = propFacing == "front" || propFacing == "back"
        camera.facingFallbackAllowed = !pinnedFacing

        if (!propActive) {
            stopSession()
            return
        }

        if (!started) {
            startSession()
            return
        }

        if (geometryChanged) {
            restartSession()
            return
        }

        applyDetectionState()

        // 'auto' keeps whatever bound, the fallback lens or a switchCamera() included.
        if (!pinnedFacing) return
        val target = resolveFacing()
        if (target == camera.facing) return
        // A paused session parks the facing rather than fail a switch nobody asked for.
        if (camera.isBound) setFacingInternal(target, null) else camera.setPendingFacing(target)
    }

    // endregion

    // region session

    private fun applyOverlayEnabled() {
        overlayView.visibility = if (overlayEnabled) VISIBLE else GONE
        if (overlayEnabled == overlayOn) return
        overlayOn = overlayEnabled
        if (!overlayEnabled) overlayView.clearPose()
    }

    private fun resolveFacing(): Facing =
        when (propFacing) {
            "back" -> Facing.BACK
            "front" -> Facing.FRONT
            else -> Facing.FRONT
        }

    private fun startSession() {
        if (started) return

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            emitError(ErrorCode.PERMISSION_DENIED, "Camera permission has not been granted.")
            return
        }

        val owner = lifecycleOwnerOrNull()
        if (owner == null) {
            emitError(ErrorCode.CAMERA_START_FAILED, "No lifecycle owner is available for the camera.")
            return
        }

        val model = modelFileName ?: PoseDetector.findModelAsset(context)
        if (model == null) {
            emitError(
                ErrorCode.MODEL_NOT_FOUND,
                "No pose_landmarker_*.task in the app assets. Run `npx expo prebuild`, " +
                    "or `npx react-native-pose-detection fetch-model full` for bare React Native.",
            )
            return
        }
        modelFileName = model

        // A no-op for the model already measured, so a restart keeps its calibration.
        calibrator.start(model)
        adopt(resolveGeometry())
        applyPerformance(reason = null)

        started = true
        // The landmarker builds as the camera opens, not after: on a low-end phone it takes longer.
        applyDetectionState()
        camera.setAnalyzer(analyzer)
        camera.start(
            owner = owner,
            facing = resolveFacing(),
            onBound = {
                syncOverlayMirroring()
                applyDetectionState()
                emitReadyOnce()
            },
            onFailed = ::emitError,
        )
    }

    private fun stopSession() {
        if (!started) return
        camera.setAnalyzer(null)
        camera.pause()
        parkDetector(PARKED_RELEASE_MS)
        releaseConverter()
        overlayView.clearPose()
        completeSwitch()
        started = false
        readySent = false
    }

    private fun restartSession() {
        stopSession()
        startSession()
    }

    private fun applyDetectionState() {
        if (!propDetection) {
            parkDetector(PARKED_RELEASE_MS)
            overlayView.clearPose()
            // The camera came up even with detection off, and no build may be left to say so.
            emitReadyOnce()
            return
        }

        // Delegate, maxPoses and minConfidence are baked in at construction: a change rebuilds.
        val request = delegateRequest()
        val changed =
            request != detectorRequest ||
                propMaxPoses != detectorMaxPoses ||
                resolvedMinConfidence() != detectorMinConfidence
        if ((detector != null || detectorPending) && changed) {
            PoseLog.info(LogCategory.DETECTOR) { "delegate, maxPoses or minConfidence changed, rebuilding" }
            releaseDetector()
        }
        ensureDetector()
        resumeFeeding()
    }

    private fun parkDetector(delayMs: Long) {
        feeding = false
        // Frames stopping ends a hold, or a minDurationMs hold would count the paused time, and
        // leaves snapshot() no current frame.
        triggers.onPoseLost()
        frames.clearLatest()
        mainHandler.removeCallbacks(releaseParked)
        if (detector == null && !detectorPending) return
        mainHandler.postDelayed(releaseParked, delayMs)
    }

    private fun resumeFeeding() {
        mainHandler.removeCallbacks(releaseParked)
        feeding = true
    }

    private fun delegateRequest(): DelegateRequest =
        when (propDelegate) {
            "gpu" -> DelegateRequest.GPU
            "cpu" -> DelegateRequest.CPU
            else -> DelegateRequest.AUTO
        }

    /** Off main: the full model takes seconds to build on a low-end GPU. See [StartPlan]. */
    private fun ensureDetector() {
        if (detector != null || detectorPending) return
        val model = modelFileName ?: return

        val request = delegateRequest()
        val spec = BuildSpec(model, request, propMaxPoses, resolvedMinConfidence(), detectorGeneration)
        val plan = StartPlan.delegates(request, calibrator.gpuVerdict)
        detectorPending = true
        detectorRequest = request
        detectorMaxPoses = spec.maxPoses
        detectorMinConfidence = spec.minConfidence

        // The plan's last delegate only: where the GPU works, 'auto' never settles on a CPU one.
        val parked = takeParked(plan.last(), spec)
        val submitted =
            if (parked == null) {
                buildStep(plan, 0, spec, running = false, lastError = null)
            } else {
                DetectorCache.execute(parked.delegate) {
                    val reused = rewarm(parked)
                    if (reused != null) {
                        PoseLog.info(LogCategory.DETECTOR) { "took back the parked ${reused.delegate} landmarker" }
                        mainHandler.post { adoptDetector(reused, request, spec.generation, provisional = false) }
                    } else {
                        buildStep(plan, 0, spec, running = false, lastError = null)
                    }
                }
            }
        if (!submitted) {
            detectorPending = false
            parked?.close()
        }
    }

    private fun takeParked(
        delegate: Delegate,
        spec: BuildSpec,
    ): PoseDetector? {
        val exactly = if (delegate == Delegate.GPU) DelegateRequest.GPU else DelegateRequest.CPU
        return DetectorCache.take(spec.model, exactly, spec.maxPoses, spec.minConfidence)
    }

    private fun build(
        delegate: Delegate,
        spec: BuildSpec,
    ): PoseDetector = PoseDetector.createForCamera(context, spec.model, delegate, spec.maxPoses, spec.minConfidence)

    /** Build thread. Ends a parked landmarker's old track, or closes it if it cannot. */
    private fun rewarm(parked: PoseDetector): PoseDetector? =
        try {
            parked.warmUp()
            parked
        } catch (error: Throwable) {
            PoseLog.warn(
                LogCategory.DETECTOR,
            ) { "the parked landmarker failed its warm-up, building anew: ${error.message}" }
            parked.close()
            null
        }

    /**
     * Builds [plan] in order on each delegate's thread, so later landmarkers replace earlier ones.
     * First step on main, the rest on build threads; false when the build thread is gone.
     */
    private fun buildStep(
        plan: List<Delegate>,
        index: Int,
        spec: BuildSpec,
        running: Boolean,
        lastError: Throwable?,
    ): Boolean {
        if (index > plan.lastIndex) {
            if (!running) {
                val error = lastError ?: IllegalStateException("no delegate to build")
                PoseLog.error(LogCategory.DETECTOR) { "landmarker init failed: ${error.message}" }
                mainHandler.post { failDetector(error, spec.generation) }
            }
            return true
        }
        val delegate = plan[index]
        val provisional = index < plan.lastIndex
        return DetectorCache.execute(delegate) {
            // Stale before it started: leave the build thread and the cache to the current camera.
            if (spec.generation != detectorGeneration) return@execute
            var started = running
            var error = lastError
            try {
                val created = takeParked(delegate, spec)?.let(::rewarm) ?: build(delegate, spec)
                // Replacing one that is already answering frames, so it takes over mid-track.
                if (running) primeFromCamera(created)
                started = true
                mainHandler.post { adoptDetector(created, spec.request, spec.generation, provisional) }
            } catch (failure: Throwable) {
                error = failure
                PoseLog.warn(LogCategory.DETECTOR) { "the $delegate landmarker could not start: ${failure.message}" }
                if (delegate == Delegate.GPU && spec.request == DelegateRequest.AUTO) {
                    mainHandler.post { rejectGpu(spec.generation) }
                }
            }
            buildStep(plan, index + 1, spec, started, error)
        }
    }

    /**
     * Build thread. Runs the replacement on the newest camera frame so it takes over already
     * tracking; it takes over unprimed if no frame comes within [PRIMING_WAIT_MS].
     */
    private fun primeFromCamera(replacement: PoseDetector) {
        val request = PrimingRequest()
        primingRequest.set(request)
        request.ready.await(PRIMING_WAIT_MS, TimeUnit.MILLISECONDS)
        primingRequest.compareAndSet(request, null)
        // A late answer lands in an abandoned request, and its copy is left to the GC.
        val frame = request.frame ?: return
        try {
            replacement.detect(BitmapImageBuilder(frame.bitmap).build(), frame.rotationDegrees, frame.timestampMs)
        } catch (error: RuntimeException) {
            PoseLog.debug(LogCategory.DETECTOR) { "priming the new landmarker failed: ${error.message}" }
        } finally {
            frame.bitmap.recycle()
        }
    }

    /** Main thread, between frames: the replaced landmarker closes behind the running frame. */
    private fun adoptDetector(
        created: PoseDetector,
        request: DelegateRequest,
        generation: Int,
        provisional: Boolean,
    ) {
        if (generation != detectorGeneration) {
            // Stale: a finished one is parked for the next camera; a stand-in is not worth it.
            if (provisional) DetectorCache.closeLater(created) else DetectorCache.park(created)
            return
        }
        val replaced = detector
        detectorPending = false
        detector = created
        detectorProvisional = provisional
        resolvedDelegate = created.delegate.name
        // Built and warmed up proves the GPU works here; persisted so file jobs know too.
        if (created.delegate == Delegate.GPU && calibrator.gpuVerdict == null) calibrator.recordGpuVerdict(true)
        // Idle search counts from the first landmarker, so an empty room idles; a takeover keeps it.
        if (replaced == null) lastPoseMs = SystemClock.elapsedRealtime()

        if (replaced != null && replaced !== created) {
            closeDetector(replaced)
            PoseLog.info(
                LogCategory.DETECTOR,
            ) { "the ${created.delegate} landmarker took over from ${replaced.delegate}" }
            emitPerformanceChange("delegate")
        }
        val fellBack = fellBackToCpu
        if (fellBack) {
            fellBackToCpu = false
            emitPerformanceChange("gpu_fallback")
        }
        // 'auto' with a known-bad GPU starts on the CPU and says so; other paths report their own.
        val knownCpuOnly = request == DelegateRequest.AUTO && calibrator.gpuVerdict == false
        if (knownCpuOnly && created.delegate == Delegate.CPU && replaced == null && !fellBack) {
            emitError(ErrorCode.GPU_UNAVAILABLE, "The GPU delegate is unavailable, running on CPU.")
        }
        emitReadyOnce()
    }

    /** Main thread. The GPU 'auto' was building failed; later sessions start on the CPU. */
    private fun rejectGpu(generation: Int) {
        calibrator.recordGpuVerdict(false)
        if (generation != detectorGeneration) return
        detectorProvisional = false
        if (detector != null) {
            emitError(ErrorCode.GPU_UNAVAILABLE, "The GPU delegate is unavailable, running on CPU.")
        }
    }

    /** The profile as `getProfile()` reports it. */
    fun profileState(): Map<String, Any?> =
        mapOf(
            "profile" to propProfile.nameForJs(),
            "phase" to
                when (calibrator.phase) {
                    Calibrator.Phase.CALIBRATING -> "calibrating"
                    Calibrator.Phase.SETTLED -> "settled"
                    Calibrator.Phase.CACHED -> "cached"
                },
            "source" to
                when (calibrator.source) {
                    Calibrator.Source.STATIC -> "static"
                    Calibrator.Source.MEASURED -> "measured"
                    Calibrator.Source.CACHE -> "cache"
                },
            "tier" to calibrator.tier.nameForJs(),
            "resolved" to
                mapOf(
                    "delegate" to (resolvedDelegate ?: "CPU"),
                    "targetFps" to currentTargetFps(),
                    "preview" to geometry.preview,
                    "analysis" to geometry.analysis,
                ),
            "p50InferenceMs" to calibrator.p50InferenceMs,
            "measuredFps" to currentMeasuredFps(),
            "limitedBy" to currentLimitedBy().forJs,
            "cameraFps" to cameraFps,
            "thermalState" to thermal.state.nameForJs(),
            "lowPower" to lowPower,
        )

    private fun currentTargetFps(): Int {
        val decided = rate.fps
        val idle = idleFps ?: return decided
        return minOf(idle, decided)
    }

    private fun currentLimitedBy(): LimitedBy {
        if (!propDetection || !feeding || !camera.isBound || (detector == null && !detectorPending)) {
            return LimitedBy.PAUSED
        }
        if (idleFps != null) return LimitedBy.IDLE
        return rate.limitedBy
    }

    /** From the ref: an explicit choice, so it applies now rather than at the next render. */
    internal fun applyProfile(profile: Profile) {
        propProfile = profile
        applyPerformance(reason = "calibration")
        restartSessionIfGeometryChanged()
    }

    /** For a profile set from the ref, the one geometry change that does not arrive as a prop. */
    private fun restartSessionIfGeometryChanged() {
        val next = resolveGeometry()
        if (next == geometry) return
        adopt(next)
        if (started) restartSession()
    }

    private fun resolveGeometry(): CameraGeometry =
        GeometryResolver.resolve(propProfile, propPreview, propAnalysis, memoryGiB)

    /** Takes effect at the next bind; does not rebind. */
    private fun adopt(next: CameraGeometry) {
        geometry = next
        camera.previewSize = CameraSource.previewSizeFor(next.preview)
        camera.analysisSize = CameraSource.analysisSizeFor(next.analysis)
    }

    private fun failDetector(
        error: Throwable,
        generation: Int,
    ) {
        if (generation != detectorGeneration) return
        detectorPending = false
        // Else getState would report a delegate nothing runs on.
        resolvedDelegate = null
        emitError(
            ErrorCode.DETECTOR_INIT_FAILED,
            error.message ?: "The pose landmarker could not be created.",
        )
        emitReadyOnce()
    }

    /**
     * Cleared on main so the next frame stops; the close queues behind the frame running now.
     * [keepForNextCamera] parks it for the next camera instead, unless it is only a CPU stand-in.
     */
    private fun releaseDetector(keepForNextCamera: Boolean = false) {
        mainHandler.removeCallbacks(releaseParked)
        detectorGeneration++
        detectorPending = false
        detectorRequest = null
        val doomed = detector ?: return
        detector = null
        val provisional = detectorProvisional
        detectorProvisional = false
        if (keepForNextCamera && !provisional) {
            // At once, not behind the running frame: the next camera is often built in this same
            // pass. The detector's own lock holds whoever takes it until that frame is done.
            DetectorCache.park(doomed)
        } else {
            closeDetector(doomed)
        }
    }

    private fun closeDetector(doomed: PoseDetector) {
        // Rejected means the analysis thread is gone, so closing here cannot race a frame.
        if (!onAnalysisThread { doomed.close() }) doomed.close()
    }

    /** Queued: the bitmaps belong to the analysis thread and the frame using them. */
    private fun releaseConverter() {
        onAnalysisThread { converter.release() }
    }

    /** Serial with detection, so a close or reset never overlaps a frame. */
    private fun onAnalysisThread(block: () -> Unit): Boolean =
        runCatching { analysisExecutor.execute(block) }
            .onFailure { PoseLog.warn(LogCategory.DETECTOR) { "the analysis thread is gone: ${it.message}" } }
            .isSuccess

    // endregion

    // region frame path

    private val analyzer =
        ImageAnalysis.Analyzer { proxy ->
            // One missed close stalls the analyzer forever, so it is the only thing in the finally.
            try {
                if (awaitingFirstFrame.compareAndSet(true, false)) post { completeSwitch() }

                if (!feeding) return@Analyzer
                val detector = this.detector ?: return@Analyzer
                if (detector !== clockedWith) {
                    // A replacement mid-session carries on the same track; a first one starts it.
                    if (clockedWith == null) visibilityClock.reset() else visibilityClock.handOver()
                    clockedWith = detector
                }
                val now = SystemClock.elapsedRealtime()

                val decision = rate
                if (decision.detectionPaused) return@Analyzer
                if (!frameIsDue(now, decision)) return@Analyzer

                val rotation = proxy.imageInfo.rotationDegrees
                frameRotationDegrees = rotation
                val bitmap = converter.convert(proxy)
                // Copied: the converter reuses its bitmap for the next frame. Once per build.
                primingRequest.getAndSet(null)?.let { request ->
                    val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    request.frame = PrimingFrame(copy, rotation, proxy.imageInfo.timestamp / 1_000_000)
                    request.ready.countDown()
                }
                val image = BitmapImageBuilder(bitmap).build()
                val started = System.nanoTime()
                val result =
                    try {
                        detector.detect(image, rotation, proxy.imageInfo.timestamp / 1_000_000)
                    } catch (error: RuntimeException) {
                        onDetectionError(error)
                        return@Analyzer
                    }
                val processingMs = (System.nanoTime() - started) / NANOS_PER_MILLI
                onLandmarks(result, bitmap.width, bitmap.height, processingMs)
            } catch (error: Throwable) {
                PoseLog.warn(LogCategory.DETECTOR) { "frame dropped: ${error.message}" }
            } finally {
                proxy.close()
            }
        }

    /**
     * Schedules from when the last frame was due, not when it ran: the latter snaps the rate to
     * divisors of the sensor's, so 24 fps under a 30 Hz sensor would run at 15.
     */
    private fun frameIsDue(
        nowMs: Long,
        decision: RateDecision,
    ): Boolean {
        val fps = idleAdjusted(decision.fps, nowMs)
        if (fps <= 0) return false

        val now = nowMs.toDouble()
        if (now + PACING_JITTER_MS < nextDetectDueMs) return false

        // More than an interval late is a stall: restart the schedule rather than run a backlog.
        val intervalMs = MILLIS_PER_SECOND / fps
        nextDetectDueMs =
            if (now - nextDetectDueMs > intervalMs) now + intervalMs else nextDetectDueMs + intervalMs
        return true
    }

    /** Idle search: the rate drops with nobody in frame; the first frame to find a pose ends it. */
    private fun idleAdjusted(
        fps: Int,
        nowMs: Long,
    ): Int {
        val lastPose = lastPoseMs
        val idle = if (lastPose == 0L) null else idleRates?.rate(nowMs - lastPose)
        val effective = idle?.let { minOf(it, fps) }

        if (effective != idleFps) {
            idleFps = effective
            PoseLog.debug(LogCategory.ENGINE) { effective?.let { "idle at $it fps" } ?: "a pose is back, idle over" }
            mainHandler.post { emitPerformanceChange("idle") }
        }
        return effective ?: fps
    }

    /** On the analysis thread. An empty result still counts: the model ran. */
    private fun countResult(nowMs: Long) {
        val previous = lastResultMs
        lastResultMs = nowMs

        // A gap restarts the window: averaging across a pause publishes a near-zero rate.
        if (previous != 0L && nowMs - previous > FPS_STALE_AFTER_MS) {
            framesInWindow = 0
            fpsWindowStartMs = nowMs
        }

        framesInWindow += 1
        if (fpsWindowStartMs == 0L) fpsWindowStartMs = nowMs
        val elapsed = nowMs - fpsWindowStartMs

        val due =
            elapsed >= FPS_WINDOW_MS ||
                (measuredFps == 0 && elapsed >= FPS_FIRST_WINDOW_MS && framesInWindow >= FPS_FIRST_WINDOW_FRAMES)
        if (!due) return

        measuredFps = ((framesInWindow * MILLIS_PER_SECOND) / maxOf(elapsed, 1)).toInt()
        framesInWindow = 0
        fpsWindowStartMs = nowMs
    }

    private fun currentMeasuredFps(): Int {
        val last = lastResultMs
        if (last == 0L || SystemClock.elapsedRealtime() - last > FPS_STALE_AFTER_MS) return 0
        return measuredFps
    }

    private fun sampleHeat() {
        val heatMoved = thermal.update(thermalMonitor.readThermal(), SystemClock.elapsedRealtime())
        val power = thermalMonitor.readLowPower()
        val powerMoved = power != lowPower
        lowPower = power
        if (!heatMoved && !powerMoved) return

        PoseLog.info(
            LogCategory.ENGINE,
        ) { "heat is ${thermal.state.nameForJs()}, low power ${if (lowPower) "on" else "off"}" }
        val reason = if (heatMoved) "thermal" else "lowPower"
        // Reported even when the policy leaves the rate alone: the app may act on heat itself.
        if (!applyPerformance(reason)) post { emitPerformanceChange(reason) }
    }

    /** Analysis thread. [imageWidth] and [imageHeight] are the buffer's, before rotation. */
    private fun onLandmarks(
        result: PoseLandmarkerResult,
        imageWidth: Int,
        imageHeight: Int,
        processingMs: Double,
    ) {
        countResult(SystemClock.elapsedRealtime())
        if (result.timestampMs() < staleBefore.get()) {
            PoseLog.trace(LogCategory.CAMERA) { "dropped a frame from the previous camera" }
            // MediaPipe's filters took this frame in, and the next one is inverted against it.
            visibilityClock.reset()
            return
        }

        val poses = result.landmarks()
        if (poses.isEmpty()) {
            // MediaPipe starts its filters over on a frame with nobody in it.
            visibilityClock.reset()
            previousBox = null
            overlayView.clearPose()
            // No frame is current without a pose; velocity and smoothing must not bridge the gap.
            frames.clearLatest()
            flushOwedBatch()
            resetVelocity()
            triggers.onPoseLost()
            smoothing.reset()
            return
        }

        val primaryIndex = primaryPose(poses)
        val pose = poses[primaryIndex]
        if (pose.size < Skeleton.LANDMARK_COUNT) return

        // The log channel's clock, so log lines map to frames. Taken when known, not when exposed.
        val nowMs = SystemClock.elapsedRealtime()
        lastPoseMs = nowMs

        // MediaPipe answers in buffer coordinates whatever rotation it ran at; turned upright here.
        val quarter = Upright.quarterOf(frameRotationDegrees)
        for (index in 0 until Skeleton.LANDMARK_COUNT) {
            val landmark = pose[index]
            val base = index * Skeleton.LANDMARK_STRIDE
            val x = landmark.x()
            val y = landmark.y()
            landmarkBuffer[base + Skeleton.OFFSET_X] = Upright.x(x, y, quarter)
            landmarkBuffer[base + Skeleton.OFFSET_Y] = Upright.y(x, y, quarter)
            landmarkBuffer[base + Skeleton.OFFSET_Z] = landmark.z()
            // Not orElse(0f): that takes an Object, so the literal is boxed once per landmark.
            val visibility = landmark.visibility()
            landmarkBuffer[base + Skeleton.OFFSET_VISIBILITY] = if (visibility.isPresent) visibility.get() else 0f
        }

        // MediaPipe smooths visibility per frame, and only for one pose; see VisibilityClock.
        visibilityClocked = clockedWith?.maxPoses == 1
        if (visibilityClocked) {
            visibilityClock.apply(landmarkBuffer, result.timestampMs().toDouble())
        } else {
            visibilityClock.reset()
        }

        // With several people the primary can change between frames, and its motion must restart.
        val box = PoseBox.of(landmarkBuffer)
        val previous = previousBox
        if (previous != null && box.overlap(previous) < PoseBox.SAME_BODY_OVERLAP) {
            PoseLog.debug(LogCategory.ENGINE) { "the primary pose is somebody else now, starting its motion over" }
            resetVelocity()
            smoothing.reset()
        }
        previousBox = box

        // No velocity across a gap (a switch, a pause, the background); see Continuity.
        val elapsedMs = nowMs.toDouble() - previousFrameMs
        val expectedFps = (idleFps ?: rate.fps).toDouble()
        val comparable =
            previousFrameMs > 0.0 && elapsedMs > 0.0 && elapsedMs <= Continuity.maxGapMs(expectedFps)
        val elapsedSeconds = if (comparable) (elapsedMs / MILLIS_PER_SECOND).toFloat() else Float.NaN

        // The landmarks are upright already, so the size is turned to match.
        val rotation = frameRotationDegrees
        val frameWidth = if (rotation % 180 == 0) imageWidth else imageHeight
        val frameHeight = if (rotation % 180 == 0) imageHeight else imageWidth

        // Before anything reads a coordinate, so overlay, geometry, triggers and wire agree. Speed
        // is in body spans; x is normalized by width, so its span is scaled by the aspect.
        if (propSmoothing) {
            val span = Geometry.bodySpan(landmarkBuffer)
            val aspect = if (frameWidth > 0) frameHeight.toFloat() / frameWidth else 1f
            smoothing.apply(landmarkBuffer, elapsedSeconds, span * aspect, span)
        } else {
            smoothing.reset()
        }

        if (overlayOn) overlayView.submit(landmarkBuffer, frameWidth, frameHeight)

        buildFrame(
            result,
            primaryIndex,
            pose.size,
            frameWidth,
            frameHeight,
            nowMs,
            comparable,
            elapsedSeconds,
            processingMs,
        )
    }

    /** Largest box, then nearest the centre. MediaPipe's own order is only detection order. */
    private fun primaryPose(
        poses: List<List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>>,
    ): Int {
        if (poses.size <= 1) return 0

        var best = 0
        var bestArea = -1f
        var bestOffset = Float.MAX_VALUE

        for (index in poses.indices) {
            val pose = poses[index]
            if (pose.size < Skeleton.LANDMARK_COUNT) continue

            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE

            // Indexed, not for-in: a List iterator here is an allocation per pose per frame.
            for (position in pose.indices) {
                val point = pose[position]
                if (point.x() < minX) minX = point.x()
                if (point.x() > maxX) maxX = point.x()
                if (point.y() < minY) minY = point.y()
                if (point.y() > maxY) maxY = point.y()
            }

            val area = (maxX - minX) * (maxY - minY)
            val offset = abs((minX + maxX) / 2f - 0.5f) + abs((minY + maxY) / 2f - 0.5f)

            val better =
                area > bestArea + PoseBox.AREA_TIE_EPSILON ||
                    (abs(area - bestArea) <= PoseBox.AREA_TIE_EPSILON && offset < bestOffset)
            if (better) {
                best = index
                bestArea = area
                bestOffset = offset
            }
        }
        return best
    }

    private fun resetVelocity() {
        previousComX = Float.NaN
        previousComY = Float.NaN
        previousFrameMs = 0.0
        hasPreviousLandmarks = false
    }

    /** Analysis thread. Encodes one frame into the wire layout. */
    @Suppress("LongParameterList")
    private fun buildFrame(
        result: PoseLandmarkerResult,
        pose: Int,
        poseSize: Int,
        frameWidth: Int,
        frameHeight: Int,
        nowMs: Long,
        comparable: Boolean,
        elapsedSeconds: Float,
        processingMs: Double,
    ) {
        // Read once: the scratch buffer belongs to the shape, so the two always match.
        val layout = frameLayout ?: return
        val scratch = layout.scratch

        val indices = layout.jointIndices
        var cursor = 0

        for (position in indices.indices) {
            val base = indices[position] * Skeleton.LANDMARK_STRIDE
            scratch[cursor] = landmarkBuffer[base]
            scratch[cursor + 1] = landmarkBuffer[base + 1]
            scratch[cursor + 2] = landmarkBuffer[base + 2]
            scratch[cursor + 3] = landmarkBuffer[base + 3]
            cursor += Skeleton.LANDMARK_STRIDE
        }

        if (layout.worldLandmarks) {
            fillWorldBuffer(result, pose, poseSize)
            for (position in indices.indices) {
                val base = indices[position] * Skeleton.LANDMARK_STRIDE
                scratch[cursor] = worldBuffer[base]
                scratch[cursor + 1] = worldBuffer[base + 1]
                scratch[cursor + 2] = worldBuffer[base + 2]
                scratch[cursor + 3] = worldBuffer[base + 3]
                cursor += Skeleton.LANDMARK_STRIDE
            }
        }

        val triples = layout.angleTriples
        for (position in triples.indices) {
            val triple = triples[position]
            scratch[cursor] =
                Geometry.angleDegrees(landmarkBuffer, triple[0], triple[1], triple[2], frameWidth, frameHeight)
            cursor += 1
        }

        val timestampMs = nowMs.toDouble()

        Geometry.centerOfMass(landmarkBuffer, scratch, cursor)
        val comX = scratch[cursor]
        val comY = scratch[cursor + 1]
        cursor += 2

        if (comparable) {
            scratch[cursor] = (comX - previousComX) / elapsedSeconds
            scratch[cursor + 1] = (comY - previousComY) / elapsedSeconds
        } else {
            // NaN, not zero: zero would read as a body measured to be still.
            scratch[cursor] = Float.NaN
            scratch[cursor + 1] = Float.NaN
        }
        cursor += 2

        val velocityX = scratch[cursor - 2]
        val velocityY = scratch[cursor - 1]
        scratch[cursor] = Geometry.bodySpan(landmarkBuffer)

        evaluateTriggers(
            nowMs = nowMs,
            timestampMs = timestampMs,
            processingMs = processingMs,
            scratch = scratch,
            comX = comX,
            comY = comY,
            velocityX = velocityX,
            velocityY = velocityY,
            elapsedSeconds = elapsedSeconds,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
        )

        // Every profile is measured: each budgets its rate against this device's inference cost.
        if (processingMs > 0.0 && !detectorProvisional) {
            val moved = calibrator.record(processingMs.toFloat(), nowMs)
            if (moved) post { onCalibrationMoved() }
        }

        System.arraycopy(landmarkBuffer, 0, previousLandmarks, 0, landmarkBuffer.size)
        hasPreviousLandmarks = true

        previousComX = comX
        previousComY = comY
        previousFrameMs = timestampMs

        deliver(scratch, timestampMs, processingMs)
    }

    /** Before [deliver]: a `snapshot: true` trigger claims the frame it fired on. */
    @Suppress("LongParameterList")
    private fun evaluateTriggers(
        nowMs: Long,
        timestampMs: Double,
        processingMs: Double,
        scratch: FloatArray,
        comX: Float,
        comY: Float,
        velocityX: Float,
        velocityY: Float,
        elapsedSeconds: Float,
        frameWidth: Int,
        frameHeight: Int,
    ) {
        if (triggers.isEmpty) return

        frameContext.landmarks = landmarkBuffer
        frameContext.previousLandmarks = if (hasPreviousLandmarks) previousLandmarks else null
        frameContext.elapsedSeconds = elapsedSeconds
        frameContext.comX = comX
        frameContext.comY = comY
        frameContext.comVelocityX = velocityX
        frameContext.comVelocityY = velocityY
        frameContext.frameWidth = frameWidth
        frameContext.frameHeight = frameHeight

        firings.clear()
        triggers.evaluate(frameContext, nowMs, firings)
        if (firings.isEmpty()) return

        for (index in firings.indices) {
            val firing = firings[index]
            val ticket =
                if (firing.wantsSnapshot) frames.mintSnapshot(scratch, timestampMs, processingMs) else 0

            val payload = HashMap<String, Any?>(TRIGGER_PAYLOAD_SLOTS)
            payload["id"] = firing.id
            payload["phase"] = firing.phase
            payload["count"] = firing.count
            payload["timestamp"] = firing.timestampMs
            // Held as Double? and stored as-is: `?.let { payload[k] = it }` unboxes then re-boxes.
            val durationMs: Double? = firing.durationMs
            if (durationMs != null) payload["durationMs"] = durationMs
            // Zero means the frame could not be held, so no ticket is offered.
            if (ticket != 0) payload["snapshotId"] = ticket

            mainHandler.post { onTrigger(payload) }
        }
        firings.clear()
    }

    private fun onCalibrationMoved() {
        applyPerformance(reason = "calibration")
        calibrator.persist()
    }

    /** [deliver] sees only frames with a pose; this flushes a batch on time once nobody is left. */
    private fun flushOwedBatch() {
        if (propMode != DataMode.BATCHED) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastEmitMs.get() < propFlushMs || !frames.hasBuffered()) return
        lastEmitMs.set(now)
        if (tickPending.compareAndSet(false, true)) mainHandler.post(emitFramesTick)
    }

    /** Records the latest frame in every mode: `snapshotFrame()` answers at `mode: 'off'`. */
    private fun deliver(
        scratch: FloatArray,
        timestampMs: Double,
        processingMs: Double,
    ) {
        val mode = propMode
        val now = SystemClock.elapsedRealtime()
        val sinceEmit = now - lastEmitMs.get()

        val due =
            when (mode) {
                DataMode.OFF -> false
                DataMode.LIVE -> true
                DataMode.THROTTLED -> sinceEmit >= propThrottleMs
                DataMode.BATCHED -> sinceEmit >= propFlushMs
            }

        val buffered = mode == DataMode.LIVE || mode == DataMode.BATCHED || (mode == DataMode.THROTTLED && due)

        frames.submit(scratch, timestampMs, processingMs, buffered)

        if (!due || mode == DataMode.OFF) return
        lastEmitMs.set(now)
        if (tickPending.compareAndSet(false, true)) mainHandler.post(emitFramesTick)
    }

    /** Indexed by [pose]: `worldLandmarks()[0]` is MediaPipe's first detection, not the primary. */
    private fun fillWorldBuffer(
        result: PoseLandmarkerResult,
        pose: Int,
        poseSize: Int,
    ) {
        val world = result.worldLandmarks()
        val points = if (pose < world.size) world[pose] else null

        if (points == null || points.size < poseSize) {
            java.util.Arrays.fill(worldBuffer, 0f)
            return
        }

        // Turned with the screen landmarks, so a world x still points the way screen x does.
        val quarter = Upright.quarterOf(frameRotationDegrees)
        for (index in 0 until Skeleton.LANDMARK_COUNT) {
            val landmark = points[index]
            val base = index * Skeleton.LANDMARK_STRIDE
            val x = landmark.x()
            val y = landmark.y()
            worldBuffer[base + Skeleton.OFFSET_X] = Upright.worldX(x, y, quarter)
            worldBuffer[base + Skeleton.OFFSET_Y] = Upright.worldY(x, y, quarter)
            worldBuffer[base + Skeleton.OFFSET_Z] = landmark.z()
            // MediaPipe gives these the screen visibility, so they take the re-timed one too.
            if (visibilityClocked) {
                worldBuffer[base + Skeleton.OFFSET_VISIBILITY] = landmarkBuffer[base + Skeleton.OFFSET_VISIBILITY]
                continue
            }
            val visibility = landmark.visibility()
            worldBuffer[base + Skeleton.OFFSET_VISIBILITY] = if (visibility.isPresent) visibility.get() else 0f
        }
    }

    /** On the analysis thread. Rate limited: a dead delegate fails every frame. */
    private fun onDetectionError(error: RuntimeException) {
        PoseLog.warn(LogCategory.DETECTOR) { "inference failed: ${error.message}" }
        val now = SystemClock.elapsedRealtime()
        if (detector?.delegate == Delegate.GPU && noteGpuFailure(now)) mainHandler.post { fallBackToCpu() }
        val previous = lastDetectionErrorMs.get()
        if (now - previous < DETECTION_ERROR_INTERVAL_MS) return
        if (!lastDetectionErrorMs.compareAndSet(previous, now)) return
        val message = error.message ?: "inference failed"
        post { emitError(ErrorCode.DETECTION_FAILED, message) }
    }

    private fun noteGpuFailure(now: Long): Boolean {
        while (gpuFailureTimes.isNotEmpty() && now - gpuFailureTimes.first() > GPU_FAILURE_WINDOW_MS) {
            gpuFailureTimes.removeFirst()
        }
        gpuFailureTimes.addLast(now)
        if (gpuFailureTimes.size < GPU_FAILURE_LIMIT) return false
        gpuFailureTimes.clear()
        return true
    }

    /** `auto` only: an explicit `'gpu'` keeps reporting its failures instead of being overruled. */
    private fun fallBackToCpu() {
        if (delegateRequest() != DelegateRequest.AUTO || detector?.delegate != Delegate.GPU) return
        PoseLog.warn(LogCategory.DETECTOR) { "the GPU delegate keeps failing on this device, rebuilding on the CPU" }
        calibrator.recordGpuVerdict(false)
        fellBackToCpu = true
        releaseDetector()
        ensureDetector()
        emitError(ErrorCode.GPU_UNAVAILABLE, "The GPU delegate failed on this device, running on CPU.")
    }

    // endregion

    // region ref methods

    fun switchCamera(
        onDone: (String) -> Unit,
        onFailed: (String) -> Unit,
    ) {
        val target = if (camera.facing == Facing.FRONT) Facing.BACK else Facing.FRONT
        setFacingInternal(target, onDone, onFailed)
    }

    /**
     * `hasTorch` rides along because it changes with the lens, and an app that only hears about
     * `facing` would leave a torch button on a front camera that cannot light.
     */
    private fun emitCameraChange(facing: String = camera.facing.nameForJs()) {
        onCameraChange(
            mapOf(
                "facing" to facing,
                "hasTorch" to camera.hasTorch,
                "torch" to camera.torchOn,
            ),
        )
    }

    internal fun setFacingInternal(
        target: Facing,
        onDone: ((String) -> Unit)?,
        onFailed: ((String) -> Unit)? = null,
    ) {
        camera.switchTo(
            target = target,
            onDone = { facing ->
                // A frame in flight may slip past; at worst one or two draw with the new mirroring.
                staleBefore.set((detector?.lastTimestampMs ?: 0L) + 1)
                previousFrameMs = 0.0
                // A hold is continuous on one camera; the new one starts it over.
                triggers.onPoseLost()
                syncOverlayMirroring()

                // Settle an earlier switch first so overlapping ones leave no promise dangling.
                completeSwitch()
                val name = facing.nameForJs()
                pendingSwitchDone = {
                    emitCameraChange(name)
                    onDone?.invoke(name)
                }
                awaitingFirstFrame.set(true)
                postDelayed(switchTimeout, SWITCH_FRAME_TIMEOUT_MS)
            },
            onFailed = { code, error ->
                emitError(code, error?.message ?: "The camera could not be switched.")
                onFailed?.invoke(error?.message ?: code.name)
            },
        )
    }

    /** Main thread only. Idempotent, so the frame path and the timeout can both call it. */
    private fun completeSwitch() {
        awaitingFirstFrame.set(false)
        removeCallbacks(switchTimeout)
        val done = pendingSwitchDone ?: return
        pendingSwitchDone = null
        done()
    }

    /** Facing is main-thread state, so it is pushed on bind rather than read per frame. */
    private fun syncOverlayMirroring() {
        overlayView.setMirrored(camera.facing == Facing.FRONT)
    }

    fun pauseCamera() {
        camera.setAnalyzer(null)
        camera.pause()
        parkDetector(PARKED_RELEASE_MS)
        overlayView.clearPose()
    }

    fun resumeCamera() {
        camera.setAnalyzer(analyzer)
        camera.resume(::emitError)
        syncOverlayMirroring()
    }

    fun startDetection() {
        propDetection = true
        applyDetectionState()
    }

    fun stopDetection() {
        propDetection = false
        applyDetectionState()
    }

    /** Detection and the preview keep running; a still does not interrupt the analysis output. */
    fun takePhoto(
        quality: Double,
        mirrorFront: Boolean,
        onDone: (Map<String, Any>) -> Unit,
        onFailed: (String) -> Unit,
    ) {
        camera.capturePhoto(quality = quality, mirrorFront = mirrorFront) { result ->
            result
                .onSuccess { photo ->
                    PoseLog.debug(LogCategory.CAMERA) {
                        "photo written: ${photo.width}x${photo.height}, ${photo.size} bytes"
                    }
                    onDone(photo.payload)
                }.onFailure { error ->
                    PoseLog.warn(LogCategory.CAMERA) { "photo failed: ${error.message}" }
                    onFailed(error.message ?: "the photo could not be taken")
                }
        }
    }

    fun setOverlayEnabled(enabled: Boolean) {
        overlayEnabled = enabled
        applyOverlayEnabled()
    }

    /** Emits `onPerformanceChange` when the rate moved and [reason] is set; says whether it did. */
    private fun applyPerformance(reason: String?): Boolean {
        val next =
            RateGovernor.decide(
                RateRequest(
                    profile = propProfile,
                    policy = propThermalPolicy,
                    thermal = thermal.state,
                    lowPower = lowPower,
                    cameraFps = cameraFps,
                    p50Ms = calibrator.p50InferenceMs,
                    requestedFps = propTargetFps,
                ),
            )
        idleRates = Budgets.of(propProfile).idle

        val changed = next != rate
        rate = next
        if (reason == null || !changed) return false

        post { emitPerformanceChange(reason) }
        return true
    }

    private fun emitPerformanceChange(reason: String) {
        onPerformanceChange(
            mapOf(
                "reason" to reason,
                "delegate" to (resolvedDelegate ?: "CPU"),
                "targetFps" to currentTargetFps(),
                "limitedBy" to currentLimitedBy().forJs,
                "analysisResolution" to (camera.boundAnalysisSize ?: camera.analysisSize).toMap(),
                "actualFps" to currentMeasuredFps(),
                "thermalState" to thermal.state.nameForJs(),
                "lowPower" to lowPower,
            ),
        )
    }

    /** Adopted only when it differs: a no-op re-render would clear frames waiting for a flush. */
    private fun applyFrameLayout() {
        val indices =
            when {
                !propLandmarks -> EMPTY_INDICES
                else -> propSelection ?: FrameShape.ALL_JOINTS
            }
        val next = FrameShape(indices, propWorldLandmarks, propAngleJoints)

        val current = frameLayout
        if (current != null && current.sameAs(next)) return

        frameLayout = next
        frames.setLayout(next)
    }

    fun setStreamId(id: Int?) {
        if (id == streamId) return
        streamId?.let { FrameStreams.unregister(stream, it) }
        streamId = id
        id?.let { FrameStreams.register(stream, it) }
    }

    /** Runs on the JavaScript thread: volatile fields only. */
    private fun liveState(): Map<String, Any?> {
        val limitedBy =
            when {
                !feeding -> LimitedBy.PAUSED
                idleFps != null -> LimitedBy.IDLE
                else -> rate.limitedBy
            }
        return mapOf("fps" to currentMeasuredFps(), "limitedBy" to limitedBy.forJs)
    }

    fun currentState(): Map<String, Any?> =
        mapOf(
            "facing" to camera.facing.nameForJs(),
            "active" to camera.isBound,
            "detecting" to (feeding && (detector != null || detectorPending)),
            "fps" to currentMeasuredFps(),
            "delegate" to (resolvedDelegate ?: "CPU"),
            "deviceTier" to calibrator.tier.nameForJs(),
            "limitedBy" to currentLimitedBy().forJs,
            "hasTorch" to camera.hasTorch,
            "torch" to camera.torchOn,
        )

    // endregion

    private fun emitReadyOnce() {
        if (readySent || !camera.isBound) return
        // onReady reports the delegate in use, which a pending build has not settled yet.
        if (detectorPending) return
        readySent = true

        val variant =
            modelFileName
                ?.removePrefix("pose_landmarker_")
                ?.removeSuffix(".task")
                ?: "full"

        onReady(
            mapOf(
                "model" to variant,
                "delegate" to (resolvedDelegate ?: "CPU"),
                "delegateRequested" to propDelegate,
                "targetFps" to currentTargetFps(),
                "limitedBy" to currentLimitedBy().forJs,
                "deviceTier" to calibrator.tier.nameForJs(),
                // What CameraX settled on: it treats the presets as targets.
                "resolution" to (camera.boundPreviewSize ?: camera.previewSize).toMap(),
                "analysisResolution" to (camera.boundAnalysisSize ?: camera.analysisSize).toMap(),
                "facing" to camera.facing.nameForJs(),
            ),
        )
    }

    private fun emitError(
        code: ErrorCode,
        message: String,
    ) {
        PoseLog.error(LogCategory.CAMERA) { "$code: $message" }
        onError(mapOf("code" to code.name, "message" to message, "fatal" to code.fatal))
    }

    private fun emitError(
        code: ErrorCode,
        error: Throwable?,
    ) {
        emitError(code, error?.message ?: code.name)
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        camera.updateTargetRotation()
    }

    /** No configuration change fires for a 180 degree turn, so the display is watched too. */
    private val displayListener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit

            override fun onDisplayRemoved(displayId: Int) = Unit

            override fun onDisplayChanged(displayId: Int) {
                if (displayId != previewView.display?.displayId) return
                camera.updateTargetRotation()
            }
        }

    private val displayManager: DisplayManager?
        get() = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager

    override fun onDetachedFromWindow() {
        unregisterEverything()
        stopObservingLifecycle()
        releaseForDetach(keepForReattach = true)
        super.onDetachedFromWindow()
    }

    /**
     * Undoes [onAttachedToWindow]. Destroy calls it too: a view destroyed without a detach would
     * otherwise leak its Activity through each registration. Idempotent.
     */
    private fun unregisterEverything() {
        runCatching { context.applicationContext.unregisterComponentCallbacks(memoryCallbacks) }
        runCatching { displayManager?.unregisterDisplayListener(displayListener) }
        mainHandler.removeCallbacks(logFlush)
        mainHandler.removeCallbacks(heatSampler)
        PoseLog.releaseStream(this)
    }

    /** Views do not receive `onTrimMemory`, the application does, so subscribe while attached. */
    private val memoryCallbacks =
        object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) = this@PoseCameraView.onTrimMemory(level)

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Required by ComponentCallbacks, superseded by onTrimMemory")
            override fun onLowMemory() = this@PoseCameraView.onTrimMemory(TRIM_MEMORY_COMPLETE_LEVEL)
        }

    /** CameraX stops the session on its own; the landmarker is the half it does not know about. */
    private val lifecycleObserver =
        object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                PoseLog.info(LogCategory.CAMERA) { "backgrounded, parking the detector" }
                parkDetector(AWAY_RELEASE_MS)
                releaseConverter()
                overlayView.clearPose()
            }

            override fun onStart(owner: LifecycleOwner) {
                if (!started || !propActive) return
                PoseLog.info(LogCategory.CAMERA) { "foregrounded, restoring detection" }
                applyDetectionState()
            }
        }

    /** The process is next to be killed. The landmarker is the largest block we can give back. */
    fun onTrimMemory(level: Int) {
        if (level >= TRIM_MEMORY_COMPLETE_LEVEL) {
            PoseLog.warn(LogCategory.DETECTOR) { "trim level $level, releasing the landmarker" }
            releaseDetector()
            DetectorCache.clear()
            releaseConverter()
            overlayView.clearPose()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.applicationContext.registerComponentCallbacks(memoryCallbacks)
        observeLifecycle()
        displayManager?.registerDisplayListener(displayListener, null)
        // Claimed now, not on the first tick, or the module flushes this camera's first entries.
        PoseLog.claimStream(this)
        mainHandler.removeCallbacks(logFlush)
        mainHandler.postDelayed(logFlush, PoseLog.FLUSH_MS)
        mainHandler.removeCallbacks(heatSampler)
        heatSampler.run()
        // A reattach restores what the props already say instead of waiting for one to change.
        onPropsUpdated()
    }

    private fun observeLifecycle() {
        stopObservingLifecycle()
        val owner = lifecycleOwnerOrNull() ?: return
        observedOwner = owner
        owner.lifecycle.addObserver(lifecycleObserver)
    }

    private fun stopObservingLifecycle() {
        observedOwner?.lifecycle?.removeObserver(lifecycleObserver)
        observedOwner = null
    }

    private fun lifecycleOwnerOrNull(): LifecycleOwner? =
        context as? LifecycleOwner ?: appContext.currentActivity as? LifecycleOwner

    /** Detaching is not destruction: the analysis thread stays for a reattach. */
    private fun releaseForDetach(keepForReattach: Boolean) {
        camera.setAnalyzer(null)
        camera.release()
        if (keepForReattach) parkDetector(AWAY_RELEASE_MS) else releaseDetector(keepForNextCamera = true)
        releaseConverter()
        completeSwitch()
        started = false
        readySent = false
    }

    /** Called from `OnViewDestroys`, where the view really is going away. */
    fun releaseEverything() {
        streamId?.let { FrameStreams.unregister(stream, it) }
        PoseLog.raise(this, null)
        unregisterEverything()
        releaseForDetach(keepForReattach = false)
        stopObservingLifecycle()
        // Not shutdownNow: the queued close of the landmarker has to run first.
        analysisExecutor.shutdown()
    }

    private companion object {
        /**
         * 0.6 tracks one subject cleanly but returns one pose whatever `maxPoses` says; 0.3 is
         * measured to find a second person. See guides/reference/pose-camera.md.
         */
        const val MIN_CONFIDENCE = 0.6f
        const val MULTI_POSE_CONFIDENCE = 0.3f
        const val MILLIS_PER_SECOND = 1_000.0
        const val NANOS_PER_MILLI = 1_000_000.0

        /** id, phase, count, timestamp, and at most durationMs and snapshotId. */
        const val TRIGGER_PAYLOAD_SLOTS = 6

        const val MIN_TARGET_FPS = 1
        const val MAX_TARGET_FPS = 60

        const val FPS_WINDOW_MS = 1_000L

        const val FPS_STALE_AFTER_MS = 2_000L

        /** Publishes early so the readout is not zero beside a visibly tracking skeleton. */
        const val FPS_FIRST_WINDOW_MS = 250L
        const val FPS_FIRST_WINDOW_FRAMES = 3

        /** Sensor clocks jitter by a few ms; a strict compare would drop a frame one ms early. */
        const val PACING_JITTER_MS = 5.0

        /** Long enough that a detection toggle, pause or geometry restart skips the rebuild. */
        const val PARKED_RELEASE_MS = 60_000L

        /** The same, for an app in the background or a view off screen. */
        const val AWAY_RELEASE_MS = 30_000L

        /** Three GPU failures inside a second is a delegate that does not work on this device. */
        const val GPU_FAILURE_LIMIT = 3
        const val GPU_FAILURE_WINDOW_MS = 1_000L
        val EMPTY_PAYLOAD = emptyMap<String, Any?>()
        val EMPTY_NAMES = emptyArray<String>()
        val EMPTY_INDICES = IntArray(0)
        const val TRIM_MEMORY_COMPLETE_LEVEL = 80
        const val DETECTION_ERROR_INTERVAL_MS = 1_000L
        const val SWITCH_FRAME_TIMEOUT_MS = 1_500L

        /** Several frames at any camera rate, and too short a delay to notice. */
        const val PRIMING_WAIT_MS = 250L
    }
}

private class BuildSpec(
    val model: String,
    val request: DelegateRequest,
    val maxPoses: Int,
    val minConfidence: Float,
    val generation: Int,
)

private class PrimingFrame(
    val bitmap: Bitmap,
    val rotationDegrees: Int,
    val timestampMs: Long,
)

private class PrimingRequest {
    val ready = CountDownLatch(1)

    @Volatile
    var frame: PrimingFrame? = null
}

internal fun Facing.nameForJs(): String = if (this == Facing.FRONT) "front" else "back"

internal fun Size.toMap(): Map<String, Any?> = mapOf("width" to width, "height" to height)
