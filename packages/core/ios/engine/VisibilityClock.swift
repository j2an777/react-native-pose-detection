import Foundation

/**
 Makes MediaPipe's visibility smoothing run on time rather than on frames.

 With one pose in a stream mode MediaPipe low-passes every landmark's visibility once per frame,
 `v = 0.1 × model + 0.9 × v'`, and passes the first frame of a track through unchanged
 (`pose_landmarks_detector_graph.cc`, `low_pass_filter.cc`). Once per frame means the same filter is
 three times slower at 10 fps than at 30: a hand raised into view takes seven frames to cross the
 overlay's 0.5, which is 0.23 s at 30 fps and 0.7 s on a phone that manages 10.

 The filter's whole state is its previous output, so it inverts exactly and gives back what the
 model said. That is filtered again here with a weight that grows with the time since the previous
 frame, chosen to equal MediaPipe's 0.1 at 30 fps: nothing changes on a device that keeps up, and a
 slower one reaches the same visibility in the same time. `VisibilityClock.kt` is the same class.

 Only for one pose, because only one pose is smoothed. MediaPipe's callback queue only.
 */
final class VisibilityClock {
  /// MediaPipe's per-frame weight for visibility, in `pose_landmarks_detector_graph.cc`.
  static let mediaPipeWeight: Float = 0.1
  /// The frame interval that weight is matched at: the 30 fps the camera is pinned to.
  static let referenceMs = 1_000.0 / 30.0
  /// A handover across a longer gap than this starts over instead.
  static let handoverMaxGapMs = 1_000.0
  /// Float error on an exact inversion is around 1e-6; this is far past it and far inside [0, 1].
  private static let tolerance: Float = 0.01

  /// MediaPipe's previous output per landmark, which is what the next one is inverted against.
  private var delivered = [Float](repeating: 0, count: Skeleton.landmarkCount)
  private var output = [Float](repeating: 0, count: Skeleton.landmarkCount)
  private var lastMs = Double.nan
  private var handingOver = false

  /**
   Whenever MediaPipe's filter may have started over without this seeing it: a lost pose, a frame
   dropped after MediaPipe answered it, a different landmarker. Starting over here too is always
   safe, because the next frame is then taken as it comes.
   */
  func reset() {
    lastMs = .nan
    handingOver = false
  }

  /**
   Another landmarker takes over the same track. Its filters start over from its own first frame,
   which the next `apply` only takes as the reference to invert against, while the visibility
   carries on where it was.
   */
  func handOver() {
    handingOver = true
  }

  /// Rewrites the visibility in `landmarks`, the wire layout, for a frame taken at `timestampMs`.
  func apply(to landmarks: inout [Float], timestampMs: Double) {
    let elapsed = timestampMs - lastMs
    let first = lastMs.isNaN || !(elapsed > 0)
    lastMs = timestampMs
    let weight = first ? 1 : VisibilityClock.weight(elapsedMs: elapsed)

    // Only across a short gap: after a long one what was visible then says nothing about now.
    let continuing = handingOver && !first && elapsed <= VisibilityClock.handoverMaxGapMs
    handingOver = false
    if continuing {
      for joint in 0..<Skeleton.landmarkCount {
        let index = joint * Skeleton.landmarkStride + Skeleton.offsetVisibility
        delivered[joint] = landmarks[index]
        landmarks[index] = output[joint]
      }
      return
    }

    for joint in 0..<Skeleton.landmarkCount {
      let index = joint * Skeleton.landmarkStride + Skeleton.offsetVisibility
      let smoothed = landmarks[index]
      let previous = delivered[joint]
      delivered[joint] = smoothed

      let model = previous + (smoothed - previous) / VisibilityClock.mediaPipeWeight
      // Outside what the filter could have produced means it started over on its own, and its
      // output is the model's again.
      if first || model < -VisibilityClock.tolerance || model > 1 + VisibilityClock.tolerance {
        output[joint] = smoothed
        continue
      }
      output[joint] += weight * (min(max(model, 0), 1) - output[joint])
      landmarks[index] = output[joint]
    }
  }

  /// The weight that, applied once over `elapsedMs`, does what MediaPipe's does per 30 fps frame.
  static func weight(elapsedMs: Double) -> Float {
    return Float(1 - pow(1 - Double(mediaPipeWeight), elapsedMs / referenceMs))
  }
}
