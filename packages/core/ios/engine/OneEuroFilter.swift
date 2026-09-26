import Foundation

/**
 One-Euro filter over the landmark buffer, in place.

 The trade every smoother makes is lag against jitter. This one moves the cutoff with the speed of
 the signal: slow movement is filtered hard, because that is where jitter is visible and lag is
 not; fast movement is barely filtered, because that is where lag is visible and jitter is not.
 `minCutoff` sets how hard the slow case is filtered, `beta` how quickly it gets out of the way
 when things move.

 Visibility is left alone. It is a confidence, not a position, and smoothing it would make a joint
 that has just left frame keep reading as present.

 Casteljau et al., "1e Filter: A Simple Speed-based Low-pass Filter", CHI 2012.
 */
final class OneEuroFilter {
  /// x, y, z. Visibility is index 3 and is deliberately not one of these.
  private static let axes = 3

  /**
   The cutoff a body that is not moving is smoothed at. Low, because that is where jitter lives.

   This and `defaultBeta` are MediaPipe's own constants for pose landmarks, the filter it runs
   itself whenever it tracks one body. Measured in the same units, body spans per second, the two
   filters feel the same, so a session that goes from one pose to several does not suddenly lag.
   */
  static let defaultMinCutoff: Float = 0.05

  /**
   How hard the cutoff rises with speed, and the reason this filter is worth having.

   `cutoff = minCutoff + beta * speed`, with speed in body spans per second. A brisk arm moves
   about one span a second, which 80 turns into a cutoff near 80 Hz: no lag at all. A resting body
   stays near `minCutoff`, which is where the jitter is filtered out. The earlier default of 4, in
   frame units, left the skeleton a tenth of a second behind every slow movement.
   */
  static let defaultBeta: Float = 80

  /// The derivative's own cutoff. 1 Hz is the value the paper uses and rarely needs changing.
  private static let derivativeCutoff: Float = 1.0

  private static let tau = Float(2.0 * Double.pi)

  /// Filtered value and filtered derivative per axis, x/y/z of every landmark.
  private var values = [Float](repeating: 0, count: Skeleton.landmarkCount * axes)
  private var derivatives = [Float](repeating: 0, count: Skeleton.landmarkCount * axes)
  private var primed = false

  private(set) var minCutoff = OneEuroFilter.defaultMinCutoff
  private(set) var beta = OneEuroFilter.defaultBeta

  func configure(minCutoff: Float, beta: Float) {
    // A cutoff at or below zero divides by zero inside alpha and takes every landmark with it.
    let nextCutoff = (minCutoff.isNaN || minCutoff <= 0) ? OneEuroFilter.defaultMinCutoff : minCutoff
    let nextBeta = (beta.isNaN || beta < 0) ? OneEuroFilter.defaultBeta : beta

    if nextCutoff == self.minCutoff && nextBeta == self.beta { return }
    self.minCutoff = nextCutoff
    self.beta = nextBeta
    reset()
  }

  /// A discontinuity: a camera switch, a lost pose, a gap. Filtering across one invents motion.
  func reset() {
    primed = false
  }

  /**
   `elapsedSeconds` is the real interval, not a nominal one: the filter's whole behavior is a
   function of it, and feeding a constant makes it lie whenever a frame is late. A non-positive or
   unknown interval is a gap, so the frame passes through untouched and the filter starts over from
   it. Keeping the state from before the gap would filter the next frame against a position the body
   left long ago.

   `scaleX` and `scaleY` turn a speed in normalized units into one in body spans: the span in each
   axis's own units, so a distant subject's small movements count as much as a near one's large
   ones. Depth rides with x, which is how MediaPipe scales it. Left at 1, speeds are frame units.
   */
  func apply(to landmarks: inout [Float], elapsedSeconds: Float, scaleX: Float = 1, scaleY: Float = 1) {
    if !primed || elapsedSeconds.isNaN || elapsedSeconds <= 0 {
      seed(landmarks)
      return
    }

    let derivativeAlpha = alpha(cutoff: OneEuroFilter.derivativeCutoff, elapsedSeconds: elapsedSeconds)

    for joint in 0..<Skeleton.landmarkCount {
      let base = joint * Skeleton.landmarkStride
      let state = joint * OneEuroFilter.axes

      for axis in 0..<OneEuroFilter.axes {
        let raw = landmarks[base + axis]
        let slot = state + axis

        let scale = axis == 1 ? usableScale(scaleY) : usableScale(scaleX)
        let speed = (raw - values[slot]) / elapsedSeconds / scale
        let smoothedSpeed = derivatives[slot] + derivativeAlpha * (speed - derivatives[slot])
        derivatives[slot] = smoothedSpeed

        let cutoff = minCutoff + beta * abs(smoothedSpeed)
        let smoothed = values[slot] + alpha(cutoff: cutoff, elapsedSeconds: elapsedSeconds) * (raw - values[slot])

        values[slot] = smoothed
        landmarks[base + axis] = smoothed
      }
    }
  }

  private func seed(_ landmarks: [Float]) {
    for joint in 0..<Skeleton.landmarkCount {
      let base = joint * Skeleton.landmarkStride
      let state = joint * OneEuroFilter.axes
      for axis in 0..<OneEuroFilter.axes {
        values[state + axis] = landmarks[base + axis]
        derivatives[state + axis] = 0
      }
    }
    primed = true
  }

  /// A span can collapse to nothing on a half-visible body; dividing by it would read as infinite speed.
  private func usableScale(_ scale: Float) -> Float {
    guard scale.isFinite else { return 1 }
    return max(scale, OneEuroFilter.minimumScale)
  }

  private static let minimumScale: Float = 0.05

  private func alpha(cutoff: Float, elapsedSeconds: Float) -> Float {
    let timeConstant = 1 / (OneEuroFilter.tau * cutoff)
    return 1 / (1 + timeConstant / elapsedSeconds)
  }
}
