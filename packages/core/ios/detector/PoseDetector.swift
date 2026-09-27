import Foundation
import MediaPipeTasksVision
import UIKit

private struct LandmarkerSpec {
  let modelPath: String
  let delegateKind: Delegate
  let maxPoses: Int
  let minConfidence: Float
  let runningMode: RunningMode
}

enum DelegateRequest {
  case auto
  case gpu
  case cpu
}

/// Called on MediaPipe's callback thread.
protocol PoseDetectorObserver: AnyObject {
  func poseDetector(_ detector: PoseDetector, didDetect result: PoseLandmarkerResult, timestampMs: Int)
  func poseDetector(_ detector: PoseDetector, didFail error: Error)
}

/// The landmarker takes its delegate when built, before the detector that wraps it can exist.
private final class LiveStreamRelay: NSObject, PoseLandmarkerLiveStreamDelegate {
  weak var detector: PoseDetector?

  func poseLandmarker(
    _ poseLandmarker: PoseLandmarker,
    didFinishDetection result: PoseLandmarkerResult?,
    timestampInMilliseconds: Int,
    error: Error?
  ) {
    detector?.handle(result: result, timestampMs: timestampInMilliseconds, error: error)
  }
}

final class PoseDetector {
  /// A power of two: the cursor masks.
  private static let dispatchSlots = 8
  private static let probeSize: CGFloat = 256

  private let landmarker: PoseLandmarker
  /// The landmarker holds its delegate weakly; this keeps the relay alive.
  private let relay: LiveStreamRelay?
  let delegateKind: Delegate
  let modelFileName: String
  /// MediaPipe smooths visibility only when this is 1, and `VisibilityClock` re-times that.
  let maxPoses: Int

  /// The GPU probe's answer when this build ran one, for the caller to cache; nil otherwise.
  let probedGpu: Bool?

  weak var observer: PoseDetectorObserver?

  /// The analysis queue writes what MediaPipe's callback queue reads.
  private let lock = NSLock()

  /// Clamped: one non-increasing timestamp kills LIVE_STREAM, and camera ones can repeat in a ms.
  private var lastTimestamp = 0

  /// A ring, not one field: `detectAsync` returns early, so several frames can be in flight.
  private var dispatchTimestamps = [Int](repeating: 0, count: dispatchSlots)
  private var dispatchNanos = [UInt64](repeating: 0, count: dispatchSlots)
  private var dispatchCursor = 0

  fileprivate init(
    landmarker: PoseLandmarker,
    relay: LiveStreamRelay?,
    delegateKind: Delegate,
    modelFileName: String,
    maxPoses: Int,
    probedGpu: Bool? = nil
  ) {
    self.landmarker = landmarker
    self.relay = relay
    self.delegateKind = delegateKind
    self.modelFileName = modelFileName
    self.maxPoses = maxPoses
    self.probedGpu = probedGpu
  }

  var lastTimestampMs: Int {
    lock.lock()
    defer { lock.unlock() }
    return lastTimestamp
  }

  func dispatchNanos(for timestampMs: Int) -> UInt64 {
    lock.lock()
    defer { lock.unlock() }
    for slot in 0..<PoseDetector.dispatchSlots where dispatchTimestamps[slot] == timestampMs {
      return dispatchNanos[slot]
    }
    return 0
  }

  @discardableResult
  func detect(image: MPImage, cameraTimestampMs: Int) throws -> Int {
    lock.lock()
    let timestamp = max(cameraTimestampMs, lastTimestamp + 1)
    lastTimestamp = timestamp
    let slot = dispatchCursor & (PoseDetector.dispatchSlots - 1)
    dispatchTimestamps[slot] = timestamp
    dispatchNanos[slot] = Monotonic.nowNanos()
    dispatchCursor = slot + 1
    lock.unlock()

    try landmarker.detectAsync(image: image, timestampInMilliseconds: timestamp)
    return timestamp
  }

  func detectImage(_ image: MPImage) throws -> PoseLandmarkerResult {
    return try landmarker.detect(image: image)
  }

  func detectVideo(_ image: MPImage, timestampMs: Int) throws -> PoseLandmarkerResult {
    return try landmarker.detect(videoFrame: image, timestampInMilliseconds: timestampMs)
  }

  /// ARC frees the landmarker; this stops a result in flight from reaching a view tearing down.
  func shutdown() {
    observer = nil
  }
}

extension PoseDetector {
  fileprivate func handle(result: PoseLandmarkerResult?, timestampMs: Int, error: Error?) {
    if let error = error {
      observer?.poseDetector(self, didFail: error)
      return
    }
    guard let result = result else { return }
    observer?.poseDetector(self, didDetect: result, timestampMs: timestampMs)
  }
}

extension PoseDetector {
  /// The plugin bundles one model; sorted so a bundle with two picks the same one every launch.
  static func findModelPath() -> String? {
    guard let resources = Bundle.main.resourcePath else { return nil }
    let contents = (try? FileManager.default.contentsOfDirectory(atPath: resources)) ?? []
    guard let name = contents
      .filter({ $0.hasPrefix("pose_landmarker_") && $0.hasSuffix(".task") })
      .sorted()
      .first else { return nil }
    return (resources as NSString).appendingPathComponent(name)
  }

  static func fileName(from path: String) -> String {
    return (path as NSString).lastPathComponent
  }

  /// CPU by default: for one photo, compiling the GPU's shaders costs more than the inference.
  static func createForStillInput(
    modelPath: String,
    maxPoses: Int,
    minConfidence: Float = StillConfidence.single,
    video: Bool,
    delegateKind: Delegate = .CPU
  ) throws -> PoseDetector {
    let landmarker = try build(LandmarkerSpec(
      modelPath: modelPath,
      delegateKind: delegateKind,
      maxPoses: maxPoses,
      minConfidence: minConfidence,
      runningMode: video ? .video : .image
    ), observer: nil)
    return PoseDetector(
      landmarker: landmarker,
      relay: nil,
      delegateKind: delegateKind,
      modelFileName: fileName(from: modelPath),
      maxPoses: maxPoses
    )
  }

  /// `knownGpu` is an earlier probe's answer for this device and model. It lets `auto` skip the
  /// probe, a throwaway landmarker that took half the time from mount to the first skeleton.
  static func create(
    modelPath: String,
    request: DelegateRequest,
    maxPoses: Int,
    minConfidence: Float,
    knownGpu: Bool? = nil
  ) throws -> PoseDetector {
    var probed: Bool?
    let delegateKind: Delegate
    if request == .auto, let knownGpu = knownGpu {
      delegateKind = knownGpu && !isSimulator ? .GPU : .CPU
    } else {
      delegateKind = resolveDelegate(request, modelPath: modelPath)
      if request == .auto && !isSimulator { probed = delegateKind == .GPU }
    }

    let relay = LiveStreamRelay()
    let landmarker = try build(LandmarkerSpec(
      modelPath: modelPath,
      delegateKind: delegateKind,
      maxPoses: maxPoses,
      minConfidence: minConfidence,
      runningMode: .liveStream
    ), observer: relay)
    let detector = PoseDetector(
      landmarker: landmarker,
      relay: relay,
      delegateKind: delegateKind,
      modelFileName: fileName(from: modelPath),
      maxPoses: maxPoses,
      probedGpu: probed
    )
    relay.detector = detector

    let kind = delegateKind == .GPU ? "GPU" : "CPU"
    let how = probed == nil ? (request == .auto ? ", from the cached probe" : "") : ", after a probe"
    PoseLog.info(.detector, "landmarker ready on \(kind) with \(detector.modelFileName)\(how)")
    return detector
  }

  static var isSimulator: Bool {
    #if targetEnvironment(simulator)
    return true
    #else
    return false
    #endif
  }

  /// Never the GPU on a simulator: MediaPipe's Metal frame conversion `abort()`s there on the first
  /// frame, which no probe can catch.
  private static func resolveDelegate(_ request: DelegateRequest, modelPath: String) -> Delegate {
    #if targetEnvironment(simulator)
    if request != .cpu {
      PoseLog.warn(.detector, "the simulator has no usable GPU for MediaPipe, using CPU")
    }
    return .CPU
    #else
    switch request {
    case .cpu: return .CPU
    case .gpu: return .GPU
    case .auto: return gpuProducesAnInference(modelPath: modelPath) ? .GPU : .CPU
    }
    #endif
  }

  /// Some GPU delegates build fine and fail on the first frame; IMAGE mode makes that catchable.
  private static func gpuProducesAnInference(modelPath: String) -> Bool {
    do {
      let probe = try build(LandmarkerSpec(
        modelPath: modelPath,
        delegateKind: .GPU,
        maxPoses: 1,
        minConfidence: 0.5,
        runningMode: .image
      ), observer: nil)
      let renderer = UIGraphicsImageRenderer(size: CGSize(width: probeSize, height: probeSize))
      let blank = renderer.image { context in
        UIColor.black.setFill()
        context.fill(CGRect(x: 0, y: 0, width: probeSize, height: probeSize))
      }
      _ = try probe.detect(image: try MPImage(uiImage: blank))
      return true
    } catch {
      PoseLog.warn(.detector, "GPU delegate rejected on probe, using CPU: \(error.localizedDescription)")
      return false
    }
  }

  private static func build(_ spec: LandmarkerSpec, observer: PoseLandmarkerLiveStreamDelegate?) throws
    -> PoseLandmarker {
    let options = PoseLandmarkerOptions()
    options.baseOptions.modelAssetPath = spec.modelPath
    options.baseOptions.delegate = spec.delegateKind
    options.runningMode = spec.runningMode
    options.numPoses = spec.maxPoses
    options.minPoseDetectionConfidence = spec.minConfidence
    options.minPosePresenceConfidence = spec.minConfidence
    options.minTrackingConfidence = spec.minConfidence
    if spec.runningMode == .liveStream {
      options.poseLandmarkerLiveStreamDelegate = observer
    }
    return try PoseLandmarker(options: options)
  }
}
