import Foundation

/// One Euro filter over the landmark buffer, in place (Casiez et al., CHI 2012).
final class OneEuroFilter {
  /// x, y, z only: smoothing visibility would keep a joint that just left frame reading as present.
  private static let axes = 3

  /// This and `defaultBeta` are MediaPipe's own pose constants, for speed in body spans per second.
  static let defaultMinCutoff: Float = 0.05

  static let defaultBeta: Float = 80

  /// 1 Hz, the paper's value.
  private static let derivativeCutoff: Float = 1.0

  private static let tau = Float(2.0 * Double.pi)

  private var values = [Float](repeating: 0, count: Skeleton.landmarkCount * axes)
  private var derivatives = [Float](repeating: 0, count: Skeleton.landmarkCount * axes)
  private var primed = false

  private(set) var minCutoff = OneEuroFilter.defaultMinCutoff
  private(set) var beta = OneEuroFilter.defaultBeta

  func configure(minCutoff: Float, beta: Float) {
    // A cutoff at or below zero divides by zero in alpha() and takes every landmark with it.
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

  /// Takes the real interval: a non-positive or NaN one is a gap, and the frame reseeds the filter.
  /// `scaleX`, `scaleY`: the body span per axis, so speed is in spans per second. z rides with x.
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

  /// A half-visible body's span can collapse to zero, which would read as infinite speed.
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
