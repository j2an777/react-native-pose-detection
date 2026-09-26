import Foundation

/**
 How a video job answers heat: full speed up to `fair`, half speed at `serious`, and paused at
 `critical` until the device cools. A file has no deadline, so heat costs it time and never
 quality. The same frames are detected, only later.

 Half speed is a rest as long as the work before it. Readings go through the live view's
 `ThermalHysteresis`, so a job slows as soon as it heats and speeds up only after 30 s cooler,
 rather than flapping at the boundary. Reads are throttled to one a second, which leaves `rest`
 cheap enough to call after every frame.
 */
final class FilePacer {
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

  /// The heat this job is acting on, after hysteresis.
  var state: ThermalState {
    return hysteresis.state
  }

  /**
   Called between two units of work. Rests as long as the work took at `serious`, and waits out
   `critical`. Returns false when the job was cancelled while it rested.
   */
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

  /// Sleeps in short slices so a cancel is answered within one of them.
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
