import Foundation

/// Re-runs MediaPipe's per-frame visibility filter (`v = 0.1 × model + 0.9 × v'`) on elapsed time,
/// equal at 30 fps. One pose, MediaPipe's callback queue only. Twin of `VisibilityClock.kt`.
final class VisibilityClock {
  /// MediaPipe's per-frame weight for visibility, in `pose_landmarks_detector_graph.cc`.
  static let mediaPipeWeight: Float = 0.1
  /// The frame interval that weight is matched at: the 30 fps the camera is pinned to.
  static let referenceMs = 1_000.0 / 30.0
  /// A handover across a longer gap starts over: what was visible then says nothing about now.
  static let handoverMaxGapMs = 1_000.0
  /// Float error on an exact inversion is around 1e-6; this is far past it and far inside [0, 1].
  private static let tolerance: Float = 0.01

  /// MediaPipe's previous output per landmark, which is what the next one is inverted against.
  private var delivered = [Float](repeating: 0, count: Skeleton.landmarkCount)
  private var output = [Float](repeating: 0, count: Skeleton.landmarkCount)
  private var lastMs = Double.nan
  private var handingOver = false

  /// When MediaPipe's filter may have restarted unseen: lost pose, dropped frame, new landmarker.
  func reset() {
    lastMs = .nan
    handingOver = false
  }

  /// A new landmarker takes over: its first frame only seeds the inversion; visibility carries on.
  func handOver() {
    handingOver = true
  }

  func apply(to landmarks: inout [Float], timestampMs: Double) {
    let elapsed = timestampMs - lastMs
    let first = lastMs.isNaN || !(elapsed > 0)
    lastMs = timestampMs
    let weight = first ? 1 : VisibilityClock.weight(elapsedMs: elapsed)

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
      // Out of the filter's range: it restarted on its own, so its output is the model's again.
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
