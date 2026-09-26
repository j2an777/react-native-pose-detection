import Foundation

enum DeviceTier: String {
  case low
  case medium
  case high
}

enum Profile: String {
  case auto
  case efficient
  case balanced
  case quality
  case unrestricted

  static func from(_ value: String?) -> Profile {
    return Profile(rawValue: value ?? "") ?? .auto
  }
}

enum ThermalPolicy {
  case adaptive
  case criticalOnly
  case off

  static func from(_ value: String?) -> ThermalPolicy {
    switch value {
    case "critical-only": return .criticalOnly
    case "off": return .off
    default: return .adaptive
    }
  }
}

/// The OS states this package acts on. Everything hotter than `serious` is `critical`.
enum ThermalState: String {
  case nominal
  case fair
  case serious
  case critical

  /// Hotter is higher, so two readings compare without a switch at every call site.
  var rank: Int {
    switch self {
    case .nominal: return 0
    case .fair: return 1
    case .serious: return 2
    case .critical: return 3
    }
  }
}

/// Why the inference rate is what it is. Reported with every rate, so a number below what was asked
/// for always comes with its reason.
enum LimitedBy: String {
  /// The camera's own frame rate: nothing faster exists to run on.
  case camera
  /// What this device can finish within its duty budget, as measured.
  case device
  /// An explicit `targetFps`.
  case target
  /// The ceiling a named profile sets below the camera's rate.
  case profile
  /// Heat, including detection paused at `critical`.
  case thermal
  /// Low Power Mode or Battery Saver.
  case lowPower
  /// Nobody has been in frame for a while.
  case idle
  /// Detection is off, or the camera is not running.
  case paused
}

/// Pixel dimensions, reported to JavaScript and compared to decide whether a rebind is needed.
struct CaptureSize: Equatable {
  let width: Int
  let height: Int

  var longestSide: Int {
    return max(width, height)
  }
}

/// Inference rates while nobody is in frame: soon after they leave, and once they have been gone
/// long enough that the device is probably propped up on a stand.
struct IdleRates: Equatable {
  let first: Int
  let deep: Int

  static let firstAfterMs: Int64 = 2_000
  static let deepAfterMs: Int64 = 20_000

  /// The idle rate for this long without a pose, or nil while a pose is recent.
  func rate(sinceLastPoseMs: Int64) -> Int? {
    if sinceLastPoseMs > IdleRates.deepAfterMs { return deep }
    if sinceLastPoseMs > IdleRates.firstAfterMs { return first }
    return nil
  }
}

/**
 One profile's row of the governor, the table in `guides/performance.md`.

 The duty is the share of time inference may occupy. Running near capacity but not at it keeps
 MediaPipe's one-frame queue empty, which is the lowest latency the landmarker has, and leaves the
 thermal margin that keeps a long session cool: at 100% every frame waits for the one before it.
 */
struct ProfileBudget {
  /// A ceiling below the camera's rate, or nil for the camera's own.
  let ceiling: Int?
  let dutyNominal: Float
  let dutyFair: Float
  /// Nil turns idle search off.
  let idle: IdleRates?
  /// False for the one profile that opts out of every heat response short of critical.
  let heatBelowCritical: Bool
  /// `efficient` also scales its rate at `fair`: it is the profile that treats warmth as a reason.
  let scaleAtFair: Bool
  /// `nil` follows the device's memory, otherwise a preset.
  let preview: String?
  let analysis: String
}

enum Budgets {
  static func of(_ profile: Profile) -> ProfileBudget {
    switch profile {
    case .auto:
      return ProfileBudget(
        ceiling: nil, dutyNominal: 0.85, dutyFair: 0.70, idle: IdleRates(first: 12, deep: 5),
        heatBelowCritical: true, scaleAtFair: false, preview: nil, analysis: "480p"
      )
    case .quality:
      return ProfileBudget(
        ceiling: nil, dutyNominal: 0.95, dutyFair: 0.85, idle: IdleRates(first: 15, deep: 8),
        heatBelowCritical: true, scaleAtFair: false, preview: "1080p", analysis: "480p"
      )
    case .balanced:
      return ProfileBudget(
        ceiling: 24, dutyNominal: 0.70, dutyFair: 0.60, idle: IdleRates(first: 12, deep: 5),
        heatBelowCritical: true, scaleAtFair: false, preview: "720p", analysis: "480p"
      )
    case .efficient:
      return ProfileBudget(
        ceiling: 15, dutyNominal: 0.50, dutyFair: 0.40, idle: IdleRates(first: 8, deep: 3),
        heatBelowCritical: true, scaleAtFair: true, preview: "720p", analysis: "360p"
      )
    case .unrestricted:
      return ProfileBudget(
        ceiling: nil, dutyNominal: 1.0, dutyFair: 1.0, idle: nil,
        heatBelowCritical: false, scaleAtFair: false, preview: "1080p", analysis: "480p"
      )
    }
  }
}

/// Preview and analysis presets. Fixed for a session: nothing the governor learns may restart the camera.
struct CameraGeometry: Equatable {
  let preview: String
  let analysis: String
}

enum GeometryResolver {
  /**
   Where `auto` moves the preview to 1080p. A phone sold as 6 GB reports a little under 6 GiB, so
   the threshold sits below the marketing number rather than on it; the old one sat on it, stepped
   down once more on top, and opened those phones at 640x480.
   */
  static let highMemoryGiB: Float = 5.5
  static let bytesPerGiB: Float = 1_073_741_824

  static func resolve(
    profile: Profile,
    requestedPreview: String,
    requestedAnalysis: String,
    memoryGiB: Float
  ) -> CameraGeometry {
    let budget = Budgets.of(profile)
    let autoPreview = budget.preview ?? (memoryGiB >= highMemoryGiB ? "1080p" : "720p")
    return CameraGeometry(
      preview: requestedPreview == "auto" ? autoPreview : requestedPreview,
      analysis: requestedAnalysis == "auto" ? budget.analysis : requestedAnalysis
    )
  }

  static func deviceMemoryGiB() -> Float {
    return Float(ProcessInfo.processInfo.physicalMemory) / bytesPerGiB
  }
}

/// Every input the rate depends on, so the governor takes one value rather than seven.
struct RateRequest {
  let profile: Profile
  let policy: ThermalPolicy
  let thermal: ThermalState
  let lowPower: Bool
  /// The camera's delivered rate, after it was pinned.
  let cameraFps: Int
  /// Median dispatch-to-result time, or 0 before anything was measured or cached.
  let p50Ms: Float
  let requestedFps: Int?
}

/// What the governor decided. A rate of zero means detection is paused.
struct RateDecision: Equatable {
  let fps: Int
  let limitedBy: LimitedBy

  var detectionPaused: Bool {
    return fps <= 0
  }
}

/**
 The rate model from `guides/performance.md`, in one place so it cannot be applied in two
 different orders by two different callers.

 ```text
 nominal  min(camera, capacity(duty nominal))
 fair     min(camera, capacity(duty fair))
 serious  min(camera / 2, capacity(0.5))
 critical detection paused, preview kept
 ```

 where `capacity(d) = floor(d × 1000 ÷ p50)`: the rate at which inference is busy a share `d` of
 the time. The camera is the ceiling because inferring faster than frames arrive is impossible,
 and 30 is where it is pinned: an iPhone 15 asked for 60 ran warm within minutes for a skeleton
 that looked identical at half that.
 */
enum RateGovernor {
  /// Below this a governed skeleton reads as broken. Heat and idle may go lower; the device may not.
  static let floorFps = 10
  static let lowPowerCeiling = 24
  static let fairScale: Float = 0.75
  static let seriousDuty: Float = 0.5

  /**
   The rate at which inference is busy a share `duty` of the time. Nil before anything has been
   measured, which is not a slow device but an unknown one: the ceiling applies until it is known.
   */
  static func capacity(duty: Float, p50Ms: Float) -> Int? {
    guard p50Ms > 0, p50Ms.isFinite else { return nil }
    return Int((duty * 1_000 / p50Ms).rounded(.down))
  }

  /// The heat the rules act on: the policy and the profile decide which readings count at all.
  static func effectiveHeat(_ request: RateRequest) -> ThermalState {
    switch request.policy {
    case .off:
      return .nominal
    case .criticalOnly:
      return request.thermal == .critical ? .critical : .nominal
    case .adaptive:
      guard Budgets.of(request.profile).heatBelowCritical else {
        return request.thermal == .critical ? .critical : .nominal
      }
      return request.thermal
    }
  }

  static func decide(_ request: RateRequest) -> RateDecision {
    let heat = effectiveHeat(request)
    guard heat != .critical else { return RateDecision(fps: 0, limitedBy: .thermal) }

    let camera = max(1, request.cameraFps)
    var decision = request.requestedFps.map { explicit($0, camera: camera, p50Ms: request.p50Ms) }
      ?? governed(request, camera: camera, heat: heat)

    if heat == .serious {
      var halved = max(1, camera / 2)
      if let capacity = capacity(duty: seriousDuty, p50Ms: request.p50Ms) {
        halved = min(halved, max(1, capacity))
      }
      if halved < decision.fps {
        decision = RateDecision(fps: halved, limitedBy: .thermal)
      }
    }

    // The person asking for less work. An explicit target and `unrestricted` are someone having
    // already decided, so they keep their rate and the OS throttles the silicon on its own.
    if request.lowPower, request.requestedFps == nil, request.profile != .unrestricted,
       decision.fps > lowPowerCeiling {
      decision = RateDecision(fps: lowPowerCeiling, limitedBy: .lowPower)
    }
    return decision
  }

  /**
   An explicit target, capped at what the device can finish. Feeding MediaPipe faster than that
   only queues frames behind each other, which adds a frame of latency and buys nothing.
   */
  private static func explicit(_ requested: Int, camera: Int, p50Ms: Float) -> RateDecision {
    var fps = max(1, min(requested, camera))
    var reason: LimitedBy = requested > camera ? .camera : .target
    if let capacity = capacity(duty: 1, p50Ms: p50Ms), capacity < fps {
      fps = max(1, capacity)
      reason = .device
    }
    return RateDecision(fps: fps, limitedBy: reason)
  }

  private static func governed(_ request: RateRequest, camera: Int, heat: ThermalState) -> RateDecision {
    let budget = Budgets.of(request.profile)
    let ceiling = min(camera, budget.ceiling ?? camera)
    var fps = ceiling
    var reason: LimitedBy = ceiling < camera ? .profile : .camera

    if let capacity = capacity(duty: budget.dutyNominal, p50Ms: request.p50Ms), capacity < fps {
      fps = max(capacity, min(floorFps, ceiling))
      reason = .device
    }

    if heat == .fair {
      if let capacity = capacity(duty: budget.dutyFair, p50Ms: request.p50Ms), capacity < fps {
        fps = max(1, capacity)
        reason = .thermal
      }
      if budget.scaleAtFair {
        fps = max(1, Int(Float(fps) * fairScale))
        reason = .thermal
      }
    }
    return RateDecision(fps: fps, limitedBy: reason)
  }
}

/**
 The thermal state the governor acts on, which is not always the one the OS just reported.

 Heat is adopted the moment it rises. Cooling is adopted only after it has held for thirty seconds,
 at the warmest level seen during that time, so a device hovering on a boundary does not flap the
 rate up and down every second, which reads as stutter and saves nothing.
 */
struct ThermalHysteresis {
  static let coolDownMs: Int64 = 30_000

  private(set) var state: ThermalState = .nominal
  private var coolerSinceMs: Int64 = 0
  private var coolerCandidate: ThermalState = .nominal

  /// Feeds one reading. Returns true when the state the governor acts on changed.
  mutating func update(_ raw: ThermalState, nowMs: Int64) -> Bool {
    if raw.rank >= state.rank {
      coolerSinceMs = 0
      guard raw.rank > state.rank else { return false }
      state = raw
      return true
    }

    if coolerSinceMs == 0 {
      coolerSinceMs = nowMs
      coolerCandidate = raw
      return false
    }
    if raw.rank > coolerCandidate.rank {
      coolerCandidate = raw
    }
    guard nowMs - coolerSinceMs >= ThermalHysteresis.coolDownMs else { return false }
    state = coolerCandidate
    coolerSinceMs = 0
    return true
  }
}

/// The tier is a label now, reported so an app can reason about the device. It drives nothing.
enum AutoTuner {
  /// A p50 that sustains ~25 fps and up is a device that can carry high-tier work.
  static let highTierMaxP50Ms: Float = 22
  static let mediumTierMaxP50Ms: Float = 45

  static func tier(p50Ms: Float) -> DeviceTier {
    if p50Ms <= highTierMaxP50Ms { return .high }
    return p50Ms <= mediumTierMaxP50Ms ? .medium : .low
  }
}
