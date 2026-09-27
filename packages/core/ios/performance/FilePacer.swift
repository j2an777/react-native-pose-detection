import Foundation

/// Heat costs a file job time, never quality: half speed at `serious`, paused at `critical`.
final class FilePacer {
  /// Throttles thermal reads so `rest` is cheap enough to call after every frame.
  static let readIntervalMs: Int64 = 1_000

  /// How often a paused job looks at the heat again, and the longest a cancel waits to be noticed.
  static let pollMs: Int64 = 250

  private let readThermal: () -> ThermalState
  private let nowMs: () -> Int64
  private let sleepMs: (Int64) -> Void
  private var hysteresis = ThermalHysteresis()
  private var lastReadMs: Int64?
  private var workStartMs: Int64

  init(
    readThermal: @escaping () -> ThermalState = ThermalMonitor().readThermal,
    nowMs: @escaping () -> Int64 = Monotonic.nowMs,
    sleepMs: @escaping (Int64) -> Void = { Thread.sleep(forTimeInterval: Double($0) / 1_000) }
  ) {
    self.readThermal = readThermal
    self.nowMs = nowMs
    self.sleepMs = sleepMs
    workStartMs = nowMs()
  }

  var state: ThermalState {
    return hysteresis.state
  }

  /// Call between units of work. False when the job was cancelled while it rested.
  func rest(isCancelled: () -> Bool) -> Bool {
    let now = nowMs()
    let worked = max(0, now - workStartMs)
    read(now)

    if hysteresis.state == .serious {
      guard wait(worked, isCancelled: isCancelled) else { return false }
    }
    if hysteresis.state == .critical {
      PoseLog.info(.engine, "the device is critically hot, the file job is paused until it cools")
      while hysteresis.state == .critical {
        guard wait(FilePacer.pollMs, isCancelled: isCancelled) else { return false }
        read(nowMs())
      }
      PoseLog.info(.engine, "the device has cooled to \(hysteresis.state.rawValue), the file job resumes")
    }
    workStartMs = nowMs()
    return !isCancelled()
  }

  private func read(_ now: Int64) {
    if let last = lastReadMs, now - last < FilePacer.readIntervalMs { return }
    lastReadMs = now
    _ = hysteresis.update(readThermal(), nowMs: now)
  }

  private func wait(_ durationMs: Int64, isCancelled: () -> Bool) -> Bool {
    var remaining = durationMs
    while remaining > 0 {
      if isCancelled() { return false }
      let slice = min(remaining, FilePacer.pollMs)
      sleepMs(slice)
      remaining -= slice
    }
    return !isCancelled()
  }
}
