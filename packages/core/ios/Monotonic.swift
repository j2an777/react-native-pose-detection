import Foundation

/// The one clock logs, frames and trigger holds compare against. It pauses while the device sleeps,
/// unlike Android's `elapsedRealtime`.
enum Monotonic {
  private static let nanosPerMilli: UInt64 = 1_000_000

  static func nowNanos() -> UInt64 {
    return clock_gettime_nsec_np(CLOCK_UPTIME_RAW)
  }

  static func nowMs() -> Int64 {
    return Int64(nowNanos() / nanosPerMilli)
  }
}
