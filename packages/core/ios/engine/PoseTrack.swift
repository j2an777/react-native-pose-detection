import Foundation

/**
 Whether two frames describe one continuous movement, which smoothing and velocity both have to know
 before they compare them.

 A gap is measured against the rate the frames were expected at, and is never shorter than 200 ms:
 at 30 fps a frame that late is a stall, while at 4 fps it is simply the next frame. A fixed 200 ms
 made every frame at a rate under 5 fps a first frame, with no velocity and no smoothing.
 */
enum Continuity {
  static let minimumGapMs = 200.0

  /// Two and a half intervals: one late frame still continues, two missed ones do not.
  static let gapIntervals = 2.5

  static func maxGapMs(fps: Double) -> Double {
    guard fps.isFinite, fps > 0 else { return minimumGapMs }
    return max(minimumGapMs, gapIntervals * 1_000 / fps)
  }
}

/**
 One subject followed through a file's sampled frames. This is the file-side twin of the state the live
 view keeps in its own fields.

 A frame continues the track unless it is the first one, follows a frame with nobody in it, arrives
 after a gap (see `Continuity`), or shows a different body (see `PoseBox.sameBodyOverlap`). A frame
 that does not continue the track starts it over: nothing measured across that boundary describes
 one movement.
 */
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

  /**
   Center-of-mass velocity in normalized units per second. NaN when the frame started the track over,
   because the first frame of a movement has nothing to differ from, and zero would read as a body that
   was measured and found to be still.
   */
  mutating func velocity(comX: Float, comY: Float, elapsed: Float?) -> (x: Float, y: Float) {
    defer {
      previousComX = comX
      previousComY = comY
    }
    guard let elapsed = elapsed else { return (.nan, .nan) }
    return ((comX - previousComX) / elapsed, (comY - previousComY) / elapsed)
  }
}
