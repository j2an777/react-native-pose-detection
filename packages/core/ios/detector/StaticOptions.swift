import Foundation

/// MediaPipe's 0.5 for one subject; 0.3 for several, where a second person appears rather than the
/// first one twice. Measured in guides/files.md.
enum StillConfidence {
  static let single: Float = 0.5
  static let several: Float = 0.3

  static func forMaxPoses(_ maxPoses: Int) -> Float {
    return maxPoses > 1 ? several : single
  }
}

/// Defaults must match `guides/files.md`.
struct StaticOptions {
  let maxPoses: Int
  let minConfidence: Float
  let angles: Bool
  let worldLandmarks: Bool
  let smoothing: Bool
  let fps: Int
  let startMs: Int64
  let endMs: Int64

  static let maxPosesLimit = 5

  static func forImage(_ raw: [String: Any]?) -> StaticOptions {
    let maxPoses = poses(raw?["maxPoses"])
    return StaticOptions(
      maxPoses: maxPoses,
      minConfidence: confidence(raw?["minConfidence"], maxPoses: maxPoses),
      angles: JS.bool(raw?["angles"]) ?? true,
      worldLandmarks: JS.bool(raw?["worldLandmarks"]) ?? false,
      // One frame has nothing to smooth against.
      smoothing: false,
      fps: 0,
      startMs: 0,
      endMs: 0
    )
  }

  static func forVideo(_ raw: [String: Any]?) -> StaticOptions {
    let maxPoses = poses(raw?["maxPoses"])
    return StaticOptions(
      maxPoses: maxPoses,
      minConfidence: confidence(raw?["minConfidence"], maxPoses: maxPoses),
      angles: JS.bool(raw?["angles"]) ?? true,
      worldLandmarks: JS.bool(raw?["worldLandmarks"]) ?? false,
      smoothing: JS.bool(raw?["smoothing"]) ?? false,
      fps: max(1, JS.int(raw?["fps"]) ?? 10),
      startMs: max(0, JS.int64(raw?["startMs"]) ?? 0),
      endMs: JS.int64(raw?["endMs"]) ?? -1
    )
  }

  private static func poses(_ value: Any?) -> Int {
    guard let number = JS.int(value) else { return 1 }
    return min(max(1, number), maxPosesLimit)
  }

  private static func confidence(_ value: Any?, maxPoses: Int) -> Float {
    guard let number = JS.number(value), number.isFinite else {
      return StillConfidence.forMaxPoses(maxPoses)
    }
    return Float(min(max(number, 0.1), 1))
  }
}
