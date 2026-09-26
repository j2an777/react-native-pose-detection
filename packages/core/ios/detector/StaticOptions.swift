import Foundation

/**
 How sure the model has to be before it calls something a body in a photo or a video, when the
 caller has not said. One decision with `maxPoses` rather than a second one: 0.5 for a single
 subject, which is MediaPipe's own, and 0.3 above that, which is where a second person actually
 appears rather than the first person twice. See guides/files.md for the measurements.
 */
enum StillConfidence {
  static let single: Float = 0.5
  static let several: Float = 0.3

  static func forMaxPoses(_ maxPoses: Int) -> Float {
    return maxPoses > 1 ? several : single
  }
}

/// What `detectOnImage` and `detectOnVideo` were asked for. Defaults from `guides/files.md`.
struct StaticOptions {
  let maxPoses: Int
  /// Follows `maxPoses` unless the caller chose one, exactly as an export's does.
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
      // A single frame has nothing to smooth against, so this is off whatever was asked.
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
      // JavaScript resolves `'auto'` against `maxPoses`. VIDEO mode already smooths one pose.
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
