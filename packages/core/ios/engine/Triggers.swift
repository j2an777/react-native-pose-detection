import Foundation

enum TriggerEmit {
  case enter
  case exit
  case cycle
  case whileActive

  static func from(_ value: String?) -> TriggerEmit {
    switch value {
    case "exit": return .exit
    case "cycle": return .cycle
    case "while": return .whileActive
    default: return .enter
    }
  }
}

struct TriggerSpec {
  let id: String
  let enter: any PoseCondition
  let exit: any PoseCondition
  let emit: TriggerEmit
  let debounceMs: Int64
  let minDurationMs: Int64
  let snapshot: Bool
  let throttleMs: Int64
}

/// Scalars only: a frame cannot ride an event, see ADR 0009.
struct TriggerFiring {
  let id: String
  let phase: String
  let count: Int
  let timestampMs: Double
  let durationMs: Double?
  let wantsSnapshot: Bool
}

/// One trigger's state machine, as in `guides/reference/trigger-schema.md`.
final class TriggerRuntime {
  let spec: TriggerSpec

  private(set) var active = false

  /// Completed cycles. Survives a props update and a camera switch; only unmount resets it.
  private(set) var count: Int

  private var holdSince: Int64 = 0
  private var activeSince: Int64 = 0
  private var lastFireMs: Int64 = 0
  private var lastWhileMs: Int64 = 0

  init(spec: TriggerSpec, initialCount: Int = 0) {
    self.spec = spec
    self.count = initialCount
  }

  /// Breaks a hold, which must be continuous, but keeps an active trigger active.
  func onPoseLost() {
    holdSince = 0
  }

  func evaluate(_ frame: FrameContext, nowMs: Int64) -> TriggerFiring? {
    return active ? evaluateActive(frame, nowMs) : evaluateIdle(frame, nowMs)
  }

  private func evaluateIdle(_ frame: FrameContext, _ nowMs: Int64) -> TriggerFiring? {
    guard spec.enter.matches(frame) else {
      holdSince = 0
      return nil
    }

    if holdSince == 0 { holdSince = nowMs }
    if nowMs - holdSince < spec.minDurationMs { return nil }
    // Debounce delays re-entry, not the hold: `holdSince` keeps counting meanwhile.
    if lastFireMs != 0 && nowMs - lastFireMs < spec.debounceMs { return nil }

    active = true
    activeSince = nowMs
    holdSince = 0
    lastWhileMs = 0

    guard spec.emit == .enter else { return nil }
    lastFireMs = nowMs
    return TriggerFiring(
      id: spec.id,
      phase: "enter",
      count: count,
      timestampMs: Double(nowMs),
      durationMs: nil,
      wantsSnapshot: spec.snapshot
    )
  }

  private func evaluateActive(_ frame: FrameContext, _ nowMs: Int64) -> TriggerFiring? {
    if spec.exit.matches(frame) {
      if holdSince == 0 { holdSince = nowMs }
      if nowMs - holdSince < spec.minDurationMs { return nil }

      active = false
      holdSince = 0
      count += 1

      switch spec.emit {
      case .cycle:
        lastFireMs = nowMs
        return TriggerFiring(
          id: spec.id,
          phase: "cycle",
          count: count,
          timestampMs: Double(nowMs),
          durationMs: Double(nowMs - activeSince),
          wantsSnapshot: spec.snapshot
        )
      case .exit:
        lastFireMs = nowMs
        return TriggerFiring(
          id: spec.id,
          phase: "exit",
          count: count,
          timestampMs: Double(nowMs),
          durationMs: nil,
          wantsSnapshot: spec.snapshot
        )
      default:
        return nil
      }
    }

    holdSince = 0
    guard spec.emit == .whileActive else { return nil }
    // `while` needs `enter` to hold: between an enter and an exit threshold, both fail.
    guard spec.enter.matches(frame) else { return nil }
    if lastWhileMs != 0 && nowMs - lastWhileMs < spec.throttleMs { return nil }

    lastWhileMs = nowMs
    lastFireMs = nowMs
    return TriggerFiring(
      id: spec.id,
      phase: "enter",
      count: count,
      timestampMs: Double(nowMs),
      durationMs: nil,
      wantsSnapshot: spec.snapshot
    )
  }
}

/// Every trigger on one camera, rebuilt on a `triggers` prop change with counts carried by id.
final class TriggerEngine {
  /// Held for the whole evaluation, not around a copy: a per-frame array copy is ARC traffic.
  private let lock = NSLock()
  private var runtimes: [TriggerRuntime] = []

  var isEmpty: Bool {
    lock.lock()
    defer { lock.unlock() }
    return runtimes.isEmpty
  }

  func setTriggers(_ specs: [TriggerSpec]) {
    lock.lock()
    defer { lock.unlock() }
    let previous = runtimes
    runtimes = specs.map { spec in
      let carried = previous.first { $0.spec.id == spec.id }
      return TriggerRuntime(spec: spec, initialCount: carried?.count ?? 0)
    }
  }

  func onPoseLost() {
    lock.lock()
    defer { lock.unlock() }
    for runtime in runtimes {
      runtime.onPoseLost()
    }
  }

  /// Appends to `sink`, so a frame that fires nothing allocates nothing.
  func evaluate(_ frame: FrameContext, nowMs: Int64, into sink: inout [TriggerFiring]) {
    lock.lock()
    defer { lock.unlock() }
    for runtime in runtimes {
      guard let firing = runtime.evaluate(frame, nowMs: nowMs) else { continue }
      sink.append(firing)
    }
  }
}
