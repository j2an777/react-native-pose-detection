import Foundation

/// Pure functions over the flat landmark buffer: no allocation, no state.
enum Geometry {
  private static let epsilon: Float = 1e-6
  private static let degreesPerRadian = Float(180.0 / Double.pi)

  /// Degrees, 0 to 180. NaN when degenerate: 0 would read as a folded joint.
  /// x is scaled by aspect: normalized space is anisotropic on a non-square frame.
  static func angleDegrees(
    _ landmarks: [Float],
    proximal: Int,
    vertex: Int,
    distal: Int,
    frameWidth: Int,
    frameHeight: Int
  ) -> Float {
    guard frameWidth > 0, frameHeight > 0 else { return .nan }
    let aspect = Float(frameWidth) / Float(frameHeight)

    let vx = landmarks[vertex * Skeleton.landmarkStride]
    let vy = landmarks[vertex * Skeleton.landmarkStride + 1]

    let ax = (landmarks[proximal * Skeleton.landmarkStride] - vx) * aspect
    let ay = landmarks[proximal * Skeleton.landmarkStride + 1] - vy
    let bx = (landmarks[distal * Skeleton.landmarkStride] - vx) * aspect
    let by = landmarks[distal * Skeleton.landmarkStride + 1] - vy

    let magnitude = ((ax * ax + ay * ay) * (bx * bx + by * by)).squareRoot()
    if magnitude < epsilon { return .nan }

    // Floating point can push this a hair outside [-1, 1], where acos returns NaN.
    let cosine = min(max((ax * bx + ay * by) / magnitude, -1), 1)
    return acos(cosine) * degreesPerRadian
  }

  /// Takes projected screen pixels: a pre-projection direction is wrong on a mirrored preview.
  static func bisectorRadians(
    proximalX: Float,
    proximalY: Float,
    vertexX: Float,
    vertexY: Float,
    distalX: Float,
    distalY: Float
  ) -> Float {
    let ax = proximalX - vertexX
    let ay = proximalY - vertexY
    let bx = distalX - vertexX
    let by = distalY - vertexY

    let aLength = (ax * ax + ay * ay).squareRoot()
    let bLength = (bx * bx + by * by).squareRoot()
    if aLength < epsilon || bLength < epsilon { return .nan }

    let sumX = ax / aLength + bx / bLength
    let sumY = ay / aLength + by / bLength
    if abs(sumX) < epsilon && abs(sumY) < epsilon { return .nan }

    return atan2(sumY, sumX)
  }

  static func visibility(_ landmarks: [Float], joint: Int) -> Float {
    return landmarks[joint * Skeleton.landmarkStride + Skeleton.offsetVisibility]
  }

  /// Weighted by visibility, so one occluded leg does not drag it; NaN when nothing is visible.
  /// Normalized and uncorrected for aspect, like the positions it is compared against.
  static func centerOfMass(_ landmarks: [Float], into out: inout [Float], at offset: Int) {
    var sumX: Float = 0
    var sumY: Float = 0
    var total: Float = 0

    for index in comJoints.indices {
      let base = comJoints[index] * Skeleton.landmarkStride
      let weight = comWeights[index] * landmarks[base + Skeleton.offsetVisibility]
      if weight <= 0 { continue }
      sumX += landmarks[base] * weight
      sumY += landmarks[base + 1] * weight
      total += weight
    }

    if total < epsilon {
      out[offset] = .nan
      out[offset + 1] = .nan
      return
    }
    out[offset] = sumX / total
    out[offset + 1] = sumY / total
  }

  /// Normalized, uncorrected for aspect: it is a divisor for other normalized distances.
  static func bodySpan(_ landmarks: [Float]) -> Float {
    let shoulderX = midpoint(landmarks, Skeleton.leftShoulder, Skeleton.rightShoulder, axis: 0)
    let shoulderY = midpoint(landmarks, Skeleton.leftShoulder, Skeleton.rightShoulder, axis: 1)
    let ankleX = midpoint(landmarks, Skeleton.leftAnkle, Skeleton.rightAnkle, axis: 0)
    let ankleY = midpoint(landmarks, Skeleton.leftAnkle, Skeleton.rightAnkle, axis: 1)

    let dx = shoulderX - ankleX
    let dy = shoulderY - ankleY
    return (dx * dx + dy * dy).squareRoot()
  }

  private static func midpoint(_ landmarks: [Float], _ left: Int, _ right: Int, axis: Int) -> Float {
    let lhs = landmarks[left * Skeleton.landmarkStride + axis]
    let rhs = landmarks[right * Skeleton.landmarkStride + axis]
    return (lhs + rhs) / 2
  }

  private static let comJoints = [
    Skeleton.leftHip, Skeleton.rightHip,
    Skeleton.leftKnee, Skeleton.rightKnee,
    Skeleton.leftAnkle, Skeleton.rightAnkle
  ]

  private static let comWeights: [Float] = [0.25, 0.25, 0.1, 0.1, 0.15, 0.15]
}

/// Normalized bounding box. Overlap across frames tells whether the primary is the same person.
struct PoseBox: Equatable {
  let minX: Float
  let minY: Float
  let maxX: Float
  let maxY: Float

  /// Below this much overlap two consecutive primary poses are different people.
  static let sameBodyOverlap: Float = 0.3

  static let areaTieEpsilon: Float = 1e-4

  /// The largest box, ties broken by distance from centre; MediaPipe's order means nothing.
  /// The live view's `primaryPose` applies the same rule to raw landmarks.
  static func primary(_ boxes: [PoseBox]) -> Int {
    var best = 0
    var bestArea: Float = -1
    var bestOffset = Float.greatestFiniteMagnitude
    for (index, box) in boxes.enumerated() {
      let area = box.area
      let offset = abs((box.minX + box.maxX) / 2 - 0.5) + abs((box.minY + box.maxY) / 2 - 0.5)
      let better = area > bestArea + areaTieEpsilon
        || (abs(area - bestArea) <= areaTieEpsilon && offset < bestOffset)
      if better {
        best = index
        bestArea = area
        bestOffset = offset
      }
    }
    return best
  }

  init(_ landmarks: [Float]) {
    var minX = Float.greatestFiniteMagnitude
    var minY = Float.greatestFiniteMagnitude
    var maxX = -Float.greatestFiniteMagnitude
    var maxY = -Float.greatestFiniteMagnitude
    for joint in 0..<Skeleton.landmarkCount {
      let base = joint * Skeleton.landmarkStride
      minX = min(minX, landmarks[base])
      maxX = max(maxX, landmarks[base])
      minY = min(minY, landmarks[base + 1])
      maxY = max(maxY, landmarks[base + 1])
    }
    self.init(minX: minX, minY: minY, maxX: maxX, maxY: maxY)
  }

  init(minX: Float, minY: Float, maxX: Float, maxY: Float) {
    self.minX = minX
    self.minY = minY
    self.maxX = maxX
    self.maxY = maxY
  }

  /// Intersection over union, 0 to 1.
  func overlap(_ other: PoseBox) -> Float {
    let width = min(maxX, other.maxX) - max(minX, other.minX)
    let height = min(maxY, other.maxY) - max(minY, other.minY)
    guard width > 0, height > 0 else { return 0 }
    let intersection = width * height
    let union = area + other.area - intersection
    return union > 0 ? intersection / union : 0
  }

  var area: Float {
    return max(0, maxX - minX) * max(0, maxY - minY)
  }
}
