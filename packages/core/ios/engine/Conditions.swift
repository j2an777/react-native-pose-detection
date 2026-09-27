import Foundation

/// Reused across frames and mutated in place: it is on the inference path.
final class FrameContext {
  var landmarks: [Float] = []
  var previousLandmarks: [Float]?

  /// `NaN` when there is no comparable previous frame, which makes every velocity unknown.
  var elapsedSeconds = Float.nan

  var comX = Float.nan
  var comY = Float.nan
  var comVelocityX = Float.nan
  var comVelocityY = Float.nan

  var frameWidth = 0
  var frameHeight = 0

  func axis(_ joint: Int, _ axis: Int) -> Float {
    return landmarks[joint * Skeleton.landmarkStride + axis]
  }

  /// Normalized units per second, uncorrected for aspect, like the positions thresholds use.
  func velocity(_ joint: Int, _ axis: Int) -> Float {
    guard let previous = previousLandmarks else { return .nan }
    if elapsedSeconds.isNaN || elapsedSeconds <= 0 { return .nan }

    let offset = joint * Skeleton.landmarkStride + axis
    return (landmarks[offset] - previous[offset]) / elapsedSeconds
  }
}

protocol PoseCondition {
  func matches(_ frame: FrameContext) -> Bool
}

let noJoint = -1
let axisX = 0
let axisY = 1

/// NaN is an absent bound, which constrains nothing, or an unmeasurable value, which never matches.
func withinBounds(
  _ value: Float,
  below: Float,
  above: Float,
  betweenMin: Float,
  betweenMax: Float
) -> Bool {
  if value.isNaN { return false }
  if !below.isNaN && value >= below { return false }
  if !above.isNaN && value <= above { return false }
  // Inclusive, unlike below and above: `between` names the range to be in.
  if !betweenMin.isNaN && (value < betweenMin || value > betweenMax) { return false }
  return true
}

struct AngleCondition: PoseCondition {
  let proximal: Int
  let vertex: Int
  let distal: Int
  let below: Float
  let above: Float
  let betweenMin: Float
  let betweenMax: Float

  func matches(_ frame: FrameContext) -> Bool {
    let value = Geometry.angleDegrees(
      frame.landmarks,
      proximal: proximal,
      vertex: vertex,
      distal: distal,
      frameWidth: frame.frameWidth,
      frameHeight: frame.frameHeight
    )
    return withinBounds(value, below: below, above: above, betweenMin: betweenMin, betweenMax: betweenMax)
  }
}

struct LandmarkCondition: PoseCondition {
  let axis: Int
  let joint: Int
  let below: Float
  let belowJoint: Int
  let above: Float
  let aboveJoint: Int

  func matches(_ frame: FrameContext) -> Bool {
    let value = frame.axis(joint, axis)
    let resolvedBelow = belowJoint == noJoint ? below : frame.axis(belowJoint, axis)
    let resolvedAbove = aboveJoint == noJoint ? above : frame.axis(aboveJoint, axis)
    return withinBounds(value, below: resolvedBelow, above: resolvedAbove, betweenMin: .nan, betweenMax: .nan)
  }
}

struct VelocityCondition: PoseCondition {
  let axis: Int
  /// `noJoint` means `centerOfMass`, whose velocity the wire already carries.
  let joint: Int
  let below: Float
  let above: Float

  func matches(_ frame: FrameContext) -> Bool {
    let value: Float
    if joint != noJoint {
      value = frame.velocity(joint, axis)
    } else if axis == axisX {
      value = frame.comVelocityX
    } else {
      value = frame.comVelocityY
    }
    return withinBounds(value, below: below, above: above, betweenMin: .nan, betweenMax: .nan)
  }
}

struct VisibilityCondition: PoseCondition {
  let joint: Int
  let above: Float

  func matches(_ frame: FrameContext) -> Bool {
    return Geometry.visibility(frame.landmarks, joint: joint) > above
  }
}

struct AllCondition: PoseCondition {
  let members: [any PoseCondition]

  func matches(_ frame: FrameContext) -> Bool {
    for member in members where !member.matches(frame) { return false }
    return true
  }
}

struct AnyCondition: PoseCondition {
  let members: [any PoseCondition]

  func matches(_ frame: FrameContext) -> Bool {
    for member in members where member.matches(frame) { return true }
    return false
  }
}

struct NeverCondition: PoseCondition {
  func matches(_ frame: FrameContext) -> Bool { return false }
}

struct NotCondition: PoseCondition {
  let inner: any PoseCondition

  func matches(_ frame: FrameContext) -> Bool {
    return !inner.matches(frame)
  }
}
