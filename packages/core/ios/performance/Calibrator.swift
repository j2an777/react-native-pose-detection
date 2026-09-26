import Foundation

/**
 What this device's inference costs, in the order `guides/performance.md` describes it.

 1. Measured: the median dispatch-to-result time over the last 60 frames that had a pose, first
    published after 15, so a session knows what its device costs within about half a second.
 2. Cached, so the second launch starts where the first one finished.

 Before either, the governor runs at the camera's rate. An unknown device is not a slow one, and
 half a second at the camera's rate costs less than a start that looks slow to the person watching.
 The memory probe that used to guess a rate now only names a tier until a measurement replaces it.
 */
final class Calibrator {
  enum Phase: String {
    case calibrating
    case settled
    case cached
  }

  enum Source: String {
    case staticProbe = "static"
    case measured
    case cache
  }

  /// Two seconds at 30 fps, which is long enough for a median to mean something.
  static let window = 60

  /// Half a second at 30 fps: enough that one slow frame cannot decide it, soon enough to matter.
  static let firstEstimate = 15

  /**
   The median is a copy and a sort, so it is refreshed every quarter window rather than every
   frame. Inference cost does not change in fifteen frames; recomputing inside that span is work on
   the hot path for a number that comes out the same.
   */
  static let medianStride = 15

  static let cooldownMs: Int64 = 3_000

  /// Moves smaller than this, in frames per second at the default duty, are noise.
  static let deadbandFps = 2

  /// Every rate past this is "faster than the camera", so differences above it mean nothing.
  static let comparisonCeilingFps = 60
  private static let comparisonDuty: Float = 0.85

  /// Tier labels from memory, used only until a measurement names the tier.
  private static let highMemoryGiB: Float = 5.5
  private static let mediumMemoryGiB: Float = 3.5

  /// Versioned: the first version cached a rate under a model that no longer exists.
  private static let defaultsPrefix = "react-native-pose-detection.v2."

  private(set) var tier: DeviceTier = .medium
  private(set) var phase: Phase = .calibrating
  private(set) var source: Source = .staticProbe

  /// The published median, or 0 before one exists. What the governor divides by.
  private(set) var p50InferenceMs: Float = 0

  /// What the GPU check decided for this device and model last time, or nil if it never ran here.
  private(set) var gpuVerdict: Bool?

  private var samples = [Float](repeating: 0, count: Calibrator.window)
  private var scratch = [Float](repeating: 0, count: Calibrator.window)
  private var sampleCount = 0
  private var cursor = 0
  private var sinceMedian = 0
  private var lastChangeMs: Int64 = 0
  private var modelFileName: String?

  private let defaults: UserDefaults
  private let memoryGiB: () -> Float

  init(defaults: UserDefaults = .standard, memoryGiB: @escaping () -> Float = GeometryResolver.deviceMemoryGiB) {
    self.defaults = defaults
    self.memoryGiB = memoryGiB
  }

  /**
   Loads what this device and model are known to cost. Runs on every session start and does nothing
   when the model has not changed: a camera restart is not a new device, and throwing the
   measurement away there sent the rate back to a guess every time a prop rebound the session.
   */
  func start(modelFileName: String) {
    guard modelFileName != self.modelFileName else { return }
    self.modelFileName = modelFileName
    sampleCount = 0
    cursor = 0
    sinceMedian = 0
    lastChangeMs = 0
    p50InferenceMs = 0
    gpuVerdict = nil

    if let cached = Calibrator.readCache(modelFileName, defaults) {
      gpuVerdict = cached.gpu
      if cached.p50Ms > 0 {
        tier = cached.tier
        p50InferenceMs = cached.p50Ms
        source = .cache
        phase = .cached
        PoseLog.info(.calibration, "starting from the cached \(tier.rawValue) tier, p50 \(p50InferenceMs)ms")
        return
      }
    }

    tier = staticTier()
    source = .staticProbe
    phase = .calibrating
    PoseLog.info(.calibration, "nothing measured yet, memory suggests the \(tier.rawValue) tier")
  }

  /**
   One frame's cost, dispatch to result. That span breathes with load: a rate the device cannot hold
   shows up as queue wait long before it shows up as heat, which is what closes the loop. Returns
   true when the published median or the tier moved, or when the measurement settled, which the
   caller answers by re-running the governor and persisting.
   */
  func record(inferenceMs: Float, nowMs: Int64) -> Bool {
    guard inferenceMs > 0, inferenceMs.isFinite else { return false }

    samples[cursor] = inferenceMs
    cursor = (cursor + 1) % Calibrator.window
    if sampleCount < Calibrator.window { sampleCount += 1 }
    sinceMedian += 1

    // The first estimate lands at `firstEstimate` samples, then one every `medianStride`.
    guard sampleCount >= Calibrator.firstEstimate, sinceMedian >= Calibrator.medianStride else { return false }
    sinceMedian = 0
    let candidate = median()

    // Hysteresis: a rate that just moved is given time to show what it costs before it moves again,
    // or a device sitting between two answers oscillates between them forever. The window itself
    // is kept: inference cost does not become untrue because the rate changed.
    if lastChangeMs != 0 && nowMs - lastChangeMs < Calibrator.cooldownMs { return false }

    let nextTier = AutoTuner.tier(p50Ms: candidate)
    let moved = p50InferenceMs == 0
      || nextTier != tier
      || abs(Calibrator.implied(candidate) - Calibrator.implied(p50InferenceMs)) > Calibrator.deadbandFps

    guard moved else {
      // Inside the deadband across a whole window with nowhere to move is what settled means.
      guard phase != .settled, sampleCount >= Calibrator.window else { return false }
      phase = .settled
      source = .measured
      PoseLog.info(.calibration, "settled at \(tier.rawValue), p50 \(p50InferenceMs)ms")
      return true
    }

    p50InferenceMs = candidate
    tier = nextTier
    source = .measured
    phase = .calibrating
    lastChangeMs = nowMs
    PoseLog.info(.calibration, "p50 \(candidate)ms, \(tier.rawValue) tier")
    return true
  }

  /// Only a settled, measured answer is worth persisting. A guess is not worth a second launch.
  func persist() {
    guard let model = modelFileName, phase == .settled, source == .measured else { return }
    write(model)
  }

  /// The GPU check is the slow half of building a landmarker, so its answer outlives the process.
  func recordGpuVerdict(_ usable: Bool) {
    gpuVerdict = usable
    guard let model = modelFileName else { return }
    write(model)
  }

  /// The rate a median implies at the default duty, capped where more stops meaning anything.
  static func implied(_ p50Ms: Float) -> Int {
    let capacity = RateGovernor.capacity(duty: comparisonDuty, p50Ms: p50Ms) ?? comparisonCeilingFps
    return min(capacity, comparisonCeilingFps)
  }

  private func staticTier() -> DeviceTier {
    let memory = memoryGiB()
    if memory >= Calibrator.highMemoryGiB { return .high }
    return memory >= Calibrator.mediumMemoryGiB ? .medium : .low
  }

  private func median() -> Float {
    let count = min(sampleCount, Calibrator.window)
    // Element-wise: `scratch = samples` would share storage and make the sort copy-on-write a fresh
    // buffer every call, which is an allocation on the frame path.
    for index in 0..<count {
      scratch[index] = samples[index]
    }
    scratch[0..<count].sort()
    return scratch[count / 2]
  }

  /// A verdict this calibrator never saw is kept rather than erased: a file job may have recorded it.
  private func write(_ model: String) {
    let verdict = gpuVerdict ?? Calibrator.readCache(model, defaults)?.gpu
    let gpu = verdict.map { $0 ? "gpu" : "cpu" } ?? ""
    defaults.set("\(tier.rawValue)|\(p50InferenceMs)|\(gpu)", forKey: Calibrator.cacheKey(model))
  }

  /// The GPU verdict alone, for a file job that has no calibrator of its own.
  static func cachedGpu(modelFileName: String, defaults: UserDefaults = .standard) -> Bool? {
    return readCache(modelFileName, defaults)?.gpu
  }

  /// Records a file job's verdict beside whatever the camera measured, which it leaves as it was.
  static func storeGpu(_ usable: Bool, modelFileName: String, defaults: UserDefaults = .standard) {
    let cached = readCache(modelFileName, defaults)
    let tier = cached?.tier ?? .medium
    let gpu = usable ? "gpu" : "cpu"
    defaults.set("\(tier.rawValue)|\(cached?.p50Ms ?? 0)|\(gpu)", forKey: cacheKey(modelFileName))
  }

  /// One cache entry. `p50Ms` is 0 when only the GPU check has run.
  private struct Cached {
    let tier: DeviceTier
    let p50Ms: Float
    let gpu: Bool?
  }

  /// `tier|p50|gpu`.
  private static func readCache(_ model: String, _ defaults: UserDefaults) -> Cached? {
    guard let stored = defaults.string(forKey: Calibrator.cacheKey(model)) else { return nil }
    let parts = stored.split(separator: "|", omittingEmptySubsequences: false)
    guard parts.count == 3, let tier = DeviceTier(rawValue: String(parts[0])) else { return nil }
    let p50 = Float(parts[1]).flatMap { $0.isFinite && $0 > 0 ? $0 : nil } ?? 0
    let gpu: Bool? = parts[2] == "gpu" ? true : parts[2] == "cpu" ? false : nil
    return Cached(tier: tier, p50Ms: p50, gpu: gpu)
  }

  /**
   Device, model, OS version and MediaPipe version. Any of them changing invalidates by producing a
   different key rather than by anything having to notice and clear the old one.
   */
  private static func cacheKey(_ modelFileName: String) -> String {
    let os = ProcessInfo.processInfo.operatingSystemVersion
    return "\(defaultsPrefix)\(hardwareModel())|\(modelFileName)|\(os.majorVersion).\(os.minorVersion)"
      + "|\(MediaPipeVersion.pinned)"
  }

  /// `iPhone16,2` and the like. `UIDevice.model` only ever answers "iPhone", which separates nothing.
  private static func hardwareModel() -> String {
    var info = utsname()
    uname(&info)
    let machine = info.machine
    let size = MemoryLayout.size(ofValue: machine)
    return withUnsafePointer(to: machine) { pointer in
      pointer.withMemoryRebound(to: CChar.self, capacity: size) { String(cString: $0) }
    }
  }
}

/// The MediaPipe release the podspec pins. `wireParity.test.ts` keeps the two in step.
enum MediaPipeVersion {
  static let pinned = "0.10.35"
}

/// The OS thermal status and Low Power Mode, read separately: one is heat, the other is intent.
final class ThermalMonitor {
  static let sampleIntervalSeconds: TimeInterval = 1

  func readThermal() -> ThermalState {
    switch ProcessInfo.processInfo.thermalState {
    case .nominal: return .nominal
    case .fair: return .fair
    case .serious: return .serious
    case .critical: return .critical
    @unknown default: return .nominal
    }
  }

  func readLowPower() -> Bool {
    return ProcessInfo.processInfo.isLowPowerModeEnabled
  }
}
