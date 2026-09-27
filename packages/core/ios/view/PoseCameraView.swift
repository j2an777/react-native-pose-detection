import ExpoModulesCore
import UIKit

/// Threads: main owns every unguarded field unless marked; `analysisQueue` takes buffers and
/// runs detect; MediaPipe's callback queue encodes results; `CameraSource` owns the session queue.
public class PoseCameraView: ExpoView {
  /// 0.6 keeps scenery from reading as a body but returns one pose whatever `maxPoses` says; 0.3
  /// is where a second person was measured to appear. See guides/reference/pose-camera.md.
  static let minConfidence: Float = 0.6
  static let multiPoseConfidence: Float = 0.3
  static let millisPerSecond = 1_000.0
  static let nanosPerMilli = 1_000_000.0

  static let minTargetFps = 1
  static let maxTargetFps = 60

  static let fpsWindowMs: Int64 = 1_000

  /// After this long without a result, `getState().fps` reports zero.
  static let fpsStaleAfterMs: Int64 = 2_000

  /// The first window publishes early, so the readout is not stuck at zero for a second.
  static let fpsFirstWindowMs: Int64 = 250
  static let fpsFirstWindowFrames = 3

  /// Sensor clocks jitter, so a frame a few milliseconds early still counts as on time.
  static let pacingJitterMs = 5.0

  /// Long enough that toggling detection or the camera, or a geometry restart, skips a rebuild.
  static let parkedReleaseSeconds: TimeInterval = 60
  /// How long a backgrounded or detached view keeps its landmarker.
  static let awayReleaseSeconds: TimeInterval = 30

  static let gpuFailureLimit = 3
  static let gpuFailureWindowMs: Int64 = 1_000

  static let preWarmSize: CGFloat = 256
  static let detectionErrorIntervalMs: Int64 = 1_000
  static let switchFrameTimeoutSeconds = 1.5

  let onReady = EventDispatcher()
  let onError = EventDispatcher()
  let onCameraChange = EventDispatcher()

  /// Carries nothing. JavaScript answers it with `drainFrames()`, see ADR 0008.
  let onFrames = EventDispatcher()

  /// Scalars plus a claim ticket. The frame cannot ride it, see ADR 0009.
  let onTrigger = EventDispatcher()

  let onPerformanceChange = EventDispatcher()
  let onLog = EventDispatcher()

  let previewView = PreviewView(frame: .zero)
  let overlayView = OverlayView(frame: .zero)

  let analysisQueue = DispatchQueue(label: "com.posedetection.analysis", qos: .userInitiated)

  private(set) lazy var camera = CameraSource(
    previewView: previewView,
    analysisQueue: analysisQueue,
    delegate: self
  )

  let detector = Guarded<PoseDetector?>(nil)

  /// False while detection is off, paused or away; the landmarker stays built meanwhile.
  let feeding = Guarded(true)

  var releaseTimer: Timer?

  var fellBackToCpu = false

  /// Callback queue only.
  var gpuFailureTimes = [Int64]()

  var modelPath: String?

  /// Main thread only. A teardown bumps `detectorGeneration` so a build that lands late is dropped.
  var detectorPending = false
  var detectorGeneration = 0
  var detectorRequest: DelegateRequest?
  var detectorMaxPoses = 0
  var detectorMinConfidence: Float = 0

  /// Survives `releaseDetector`: `getState` reports the pipeline, not whether it is built.
  var resolvedDelegate: String?

  let lastDetectionErrorMs = Guarded<Int64>(0)

  /// A switch is reported on the new camera's first frame, or by `switchTimer` if none comes.
  let awaitingFirstFrame = Guarded<Bool>(false)
  var pendingSwitchDone: (() -> Void)?
  var switchTimer: Timer?

  /// Results below this timestamp came from the previous camera.
  let staleBefore = Guarded<Int>(0)

  // Callback-queue only.
  var landmarkBuffer = [Float](repeating: 0, count: Skeleton.landmarkCount * Skeleton.landmarkStride)
  var worldBuffer = [Float](repeating: 0, count: Skeleton.landmarkCount * Skeleton.landmarkStride)
  var previousLandmarks = [Float](repeating: 0, count: Skeleton.landmarkCount * Skeleton.landmarkStride)
  var hasPreviousLandmarks = false
  var previousComX = Float.nan
  var previousComY = Float.nan
  var previousBox: PoseBox?

  let frames = FrameRingBuffer()

  /// Read on the JavaScript thread: its closures capture thread-safe values only, never the view.
  private(set) lazy var stream = FrameStream(
    frames: frames,
    readDetecting: { [feeding, detector] in feeding.value && detector.value != nil },
    readLive: { [measuredFps, lastResultMs, rate, idleFps, feeding] in
      PoseCameraView.liveState(
        measuredFps: measuredFps,
        lastResultMs: lastResultMs,
        rate: rate,
        idleFps: idleFps,
        feeding: feeding
      )
    }
  )
  var streamId: Int?
  let triggers = TriggerEngine()
  let smoothing = OneEuroFilter()

  /// Callback queue only. Starts over with each new landmarker, whose filters start over too.
  let visibilityClock = VisibilityClock()
  var clockedWith: ObjectIdentifier?
  var visibilityClocked = false
  let calibrator = Calibrator()
  let thermalMonitor = ThermalMonitor()

  let rate = Guarded(RateDecision(fps: 30, limitedBy: .camera))

  let idleRates = Guarded<IdleRates?>(Budgets.of(.auto).idle)

  /// nil while a pose is recent. Written on the analysis queue.
  let idleFps = Guarded<Int?>(nil)

  let cameraFps = Guarded(30)

  var geometry = CameraGeometry(preview: "720p", analysis: "480p")

  let memoryGiB = GeometryResolver.deviceMemoryGiB()

  /// Recorded at dispatch, since results carry no size. Changes only on a rotation or rebind, so a
  /// result that reads it a frame late still gets its own size.
  let frameSize = Guarded(CaptureSize(width: 0, height: 0))

  var thermal = ThermalHysteresis()
  var lowPower = false
  var heatTimer: Timer?

  /// Analysis queue only.
  var nextDetectDueMs = 0.0

  let lastPoseMs = Guarded<Int64>(0)

  /// Counted per result, so fps is what the model completed. Window fields: callback queue only.
  var framesInWindow = 0
  var fpsWindowStartMs: Int64 = 0
  let measuredFps = Guarded<Int>(0)
  let lastResultMs = Guarded<Int64>(0)

  let frameContext = FrameContext()
  var firings = [TriggerFiring]()

  let frameLayout = Guarded<FrameShape?>(nil)

  let previousFrameMs = Guarded<Double>(0)

  /// At most one tick in flight, so a stalled JavaScript side does not queue one per frame.
  let tickPending = Guarded<Bool>(false)

  let lastEmitMs = Guarded<Int64>(0)

  var logTimer: Timer?

  var observerTokens = [NSObjectProtocol]()

  // Props, applied together in `onPropsUpdated` so one render rebinds the session at most once.
  var propFacing = "auto"
  var propDelegate = "auto"
  var propActive = true
  var propDetection = true
  var propMaxPoses = 1
  /// nil is `'auto'`, resolved by `resolvedMinConfidence()`.
  var propMinConfidence: Float?
  var propPreview = "auto"
  var propAnalysis = "auto"
  var overlayEnabled = true
  /// `overlayEnabled`, readable from the callback queue.
  let overlayOn = Guarded(true)
  var pendingOverlayConfig = OverlayConfig()
  var propMode = DataMode.off
  let propThrottleMs = Guarded<Int64>(defaultThrottleMs)
  let propFlushMs = Guarded<Int64>(defaultFlushMs)
  var propLandmarks = true
  var propWorldLandmarks = false
  var propAngleJoints = [String]()
  var propSelection: [Int]?
  var propProfile = Profile.auto
  var propTargetFps: Int?
  var propThermalPolicy = ThermalPolicy.adaptive
  var propSmoothing = false
  var propMinCutoff = OneEuroFilter.defaultMinCutoff
  var propBeta = OneEuroFilter.defaultBeta

  var started = false
  var readySent = false

  public required init(appContext: AppContext? = nil) {
    super.init(appContext: appContext)

    backgroundColor = .black
    clipsToBounds = true
    addSubview(previewView)
    addSubview(overlayView)

    camera.onFrameRate = { [weak self] fps in
      guard let self = self, fps != self.cameraFps.value else { return }
      self.cameraFps.value = fps
      self.applyPerformance(reason: nil)
    }

    // Before props arrive, so a first frame is not dropped for want of a layout.
    applyFrameLayout()
  }

  public override func layoutSubviews() {
    super.layoutSubviews()
    previewView.frame = bounds
    overlayView.frame = bounds
  }

  deinit {
    // Also covers a view released without ever being detached.
    removeObservers()
    if let id = streamId {
      FrameStreams.shared.unregister(stream, id: id)
    }
    logTimer?.invalidate()
    heatTimer?.invalidate()
    releaseTimer?.invalidate()
    switchTimer?.invalidate()
    PoseLog.releaseStream(self)
    PoseLog.raise(self, to: nil)
  }
}
