import Foundation

/// The longest gap within one movement. Scales with rate: a fixed 200 ms breaks rates under 5 fps.
enum Continuity {
  static let minimumGapMs = 200.0

  /// Two and a half intervals: one late frame still continues, two missed ones do not.
  static let gapIntervals = 2.5

  static func maxGapMs(fps: Double) -> Double {
    guard fps.isFinite, fps > 0 else { return minimumGapMs }
    return max(minimumGapMs, gapIntervals * 1_000 / fps)
  }
}

/// One subject across a file's sampled frames. The live view keeps the same state in its fields.
struct PoseTrack {
  private let maxGapMs: Double
  private var previousBox: PoseBox?
  private var previousMs = 0.0
  private var previousComX = Float.nan
  private var previousComY = Float.nan

  init(sampleFps: Int) {
    maxGapMs = Continuity.maxGapMs(fps: Double(sampleFps))
  }

  /// The seconds since the frame this one continues, or nil when it starts the track over.
  mutating func advance(_ box: PoseBox, atMs timestampMs: Double) -> Float? {
    defer {
      previousBox = box
      previousMs = timestampMs
    }
    let elapsedMs = timestampMs - previousMs
    guard let previous = previousBox,
          elapsedMs > 0,
          elapsedMs <= maxGapMs,
          box.overlap(previous) >= PoseBox.sameBodyOverlap else {
      previousComX = .nan
      previousComY = .nan
      return nil
    }
    return Float(elapsedMs / 1_000)
  }

  /// A sampled frame with nobody in it. Whoever appears next starts over.
  mutating func lose() {
    previousBox = nil
    previousComX = .nan
    previousComY = .nan
  }

  /// Normalized units/s. NaN after a restart: 0 would read as a body measured and found still.
  mutating func velocity(comX: Float, comY: Float, elapsed: Float?) -> (x: Float, y: Float) {
    defer {
      previousComX = comX
      previousComY = comY
    }
    guard let elapsed = elapsed else { return (.nan, .nan) }
    return ((comX - previousComX) / elapsed, (comY - previousComY) / elapsed)
  }
}
