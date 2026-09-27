import AVFoundation
import Foundation
import MediaPipeTasksVision
import UIKit

struct StaticDetectionError: LocalizedError {
  let code: ErrorCode
  let message: String

  init(_ code: ErrorCode, _ message: String) {
    self.code = code
    self.message = message
  }

  var errorDescription: String? {
    return message
  }
}

/// No calibration: a file has no frame budget, so it runs at full quality, paced only for heat.
enum StaticDetection {
  private static let millisPerSecond: Double = 1_000
  private static let progressStep: Float = 0.02

  static let queue = DispatchQueue(label: "com.posedetection.files", qos: .utility)

  private static let running = CancelRegistry()

  static func cancel(taskId: Int) {
    running.cancel(taskId)
  }

  /// One frame per pose, the subject first.
  static func detectImage(
    uri: String,
    options: StaticOptions,
    angleJoints: [String],
    selection: [Int]?
  ) throws -> Data {
    guard let source = StillImage.source(uri: uri),
          let image = StillImage.decode(source, maxPixels: StillImage.detectionMaxPixels) else {
      throw StaticDetectionError(.imageDecodeFailed, "could not read an image from \(uri)")
    }
    let shape = shapeFor(options, angleJoints: angleJoints, selection: selection)

    let detector = try PoseDetector.createForStillInput(
      modelPath: try requireModel(),
      maxPoses: options.maxPoses,
      minConfidence: options.minConfidence,
      video: false
    )
    let result = try detector.detectImage(try MPImage(uiImage: UIImage(cgImage: image)))

    let poses = PoseExport.poses(result)
    let subject = poses.count > 1 ? PoseBox.primary(poses.map { PoseBox($0) }) : 0
    let order = poses.isEmpty ? [] : [subject] + poses.indices.filter { $0 != subject }
    let size = CGSize(width: image.width, height: image.height)
    let frames = order.map { index in
      encode(poses[index], result: result, poseIndex: index, shape: shape, size: size, velocity: (.nan, .nan))
    }
    return write(shape: shape, frames: frames, timestamps: [Double](repeating: 0, count: frames.count))
  }

  static func detectVideo(
    uri: String,
    options: StaticOptions,
    angleJoints: [String],
    selection: [Int]?,
    taskId: Int,
    onProgress: (Float) -> Void
  ) throws -> Data {
    running.begin(taskId)
    defer { running.end(taskId) }
    let isCancelled = { running.isCancelled(taskId) }

    let sampler = try VideoFrameSampler(
      url: JS.url(uri),
      fps: options.fps,
      startMs: options.startMs,
      endMs: options.endMs
    )
    let shape = shapeFor(options, angleJoints: angleJoints, selection: selection)
    let detector = try FileDetector(
      modelPath: try requireModel(),
      maxPoses: options.maxPoses,
      minConfidence: options.minConfidence
    )
    let pacer = FilePacer()
    let frames = UprightFrames(orientation: sampler.orientation, size: sampler.size)
    var tracker = VideoTracker(fps: options.fps, smoothing: options.smoothing, size: sampler.size)

    var encoded = [[Float]]()
    var timestamps = [Double]()
    var lastTimestamp = -1
    var lastReported: Float = 0

    while !isCancelled() {
      guard let frame = try sampler.next() else { break }
      try autoreleasepool {
        let timestamp = max(Int(frame.timestampMs), lastTimestamp + 1)
        lastTimestamp = timestamp
        guard let upright = frames.upright(frame.buffer) else {
          PoseLog.warn(.engine, "the frame at \(frame.timestampMs) ms could not be turned upright")
          return
        }
        let result = try detector.detect(try MPImage(pixelBuffer: upright, orientation: .up), timestampMs: timestamp)
        if let pose = tracker.encode(result, shape: shape, atMs: Double(frame.timestampMs)) {
          encoded.append(pose)
          timestamps.append(Double(frame.timestampMs))
        } else {
          PoseLog.debug(.engine, "nobody found at \(frame.timestampMs) ms")
        }
      }
      let progress = sampler.progress(of: frame)
      if progress >= lastReported + StaticDetection.progressStep {
        lastReported = progress
        onProgress(progress)
      }
      if !pacer.rest(isCancelled: isCancelled) { break }
    }

    onProgress(1)
    return write(shape: shape, frames: encoded, timestamps: timestamps)
  }

  // MARK: - Decoding

  static func durationMilliseconds(of asset: AVURLAsset) -> Int64 {
    let seconds = AssetCompat.durationSeconds(asset)
    guard seconds.isFinite, seconds > 0 else { return 0 }
    return Int64(seconds * millisPerSecond)
  }

  static func requireModel() throws -> String {
    guard let path = PoseDetector.findModelPath() else {
      throw StaticDetectionError(.modelNotFound, "No pose model is bundled. Run the CLI or prebuild first.")
    }
    return path
  }

  // MARK: - Encoding

  private static func shapeFor(_ options: StaticOptions, angleJoints: [String], selection: [Int]?) -> FrameShape {
    return FrameShape(
      jointIndices: selection ?? FrameShape.allJoints,
      worldLandmarks: options.worldLandmarks,
      angleJoints: options.angles ? angleJoints : []
    )
  }

  /// Must match the live path's block order: JavaScript decodes both the same way.
  static func encode(
    _ landmarks: [Float],
    result: PoseLandmarkerResult,
    poseIndex: Int,
    shape: FrameShape,
    size: CGSize,
    velocity: (x: Float, y: Float)
  ) -> [Float] {
    var frame = [Float](repeating: 0, count: shape.floatsPerFrame)
    var cursor = 0

    for joint in shape.jointIndices {
      let base = joint * Skeleton.landmarkStride
      for offset in 0..<Skeleton.landmarkStride {
        frame[cursor + offset] = landmarks[base + offset]
      }
      cursor += Skeleton.landmarkStride
    }

    if shape.worldLandmarks {
      let world = result.worldLandmarks
      let points = world.count > poseIndex ? world[poseIndex] : nil
      for joint in shape.jointIndices {
        let point = (points?.count ?? 0) > joint ? points?[joint] : nil
        frame[cursor] = point?.x ?? 0
        frame[cursor + 1] = point?.y ?? 0
        frame[cursor + 2] = point?.z ?? 0
        frame[cursor + 3] = point?.visibility?.floatValue ?? 0
        cursor += Skeleton.landmarkStride
      }
    }

    for triple in shape.angleTriples {
      frame[cursor] = Geometry.angleDegrees(
        landmarks,
        proximal: triple[0],
        vertex: triple[1],
        distal: triple[2],
        frameWidth: Int(size.width),
        frameHeight: Int(size.height)
      )
      cursor += 1
    }

    Geometry.centerOfMass(landmarks, into: &frame, at: cursor)
    cursor += 2
    frame[cursor] = velocity.x
    frame[cursor + 1] = velocity.y
    cursor += 2
    frame[cursor] = Geometry.bodySpan(landmarks)

    return frame
  }

  private static func write(shape: FrameShape, frames: [[Float]], timestamps: [Double]) -> Data {
    var buffer = WireWriter.allocate(shape: shape, frameCount: frames.count, droppedCount: 0)
    guard !frames.isEmpty else { return buffer }

    for (index, frame) in frames.enumerated() {
      WireWriter.writeMeta(
        into: &buffer,
        frameIndex: index,
        timestampMs: index < timestamps.count ? timestamps[index] : 0,
        processingMs: 0
      )
      WireWriter.writeFrame(
        into: &buffer,
        frameCount: frames.count,
        frameIndex: index,
        from: frame,
        sourceOffset: 0,
        count: shape.floatsPerFrame
      )
    }
    return buffer
  }
}

/// Follows a video's subject by the live view's rules; `PoseTrack` decides when it starts over.
struct VideoTracker {
  private var track: PoseTrack
  private let smoothing: OneEuroFilter?
  private let size: CGSize

  init(fps: Int, smoothing: Bool, size: CGSize) {
    track = PoseTrack(sampleFps: fps)
    self.smoothing = smoothing ? OneEuroFilter() : nil
    self.size = size
  }

  mutating func encode(_ result: PoseLandmarkerResult, shape: FrameShape, atMs timestampMs: Double) -> [Float]? {
    let poses = PoseExport.poses(result)
    guard !poses.isEmpty else {
      track.lose()
      return nil
    }
    let boxes = poses.map { PoseBox($0) }
    let subject = poses.count > 1 ? PoseBox.primary(boxes) : 0
    var landmarks = poses[subject]
    let elapsed = track.advance(boxes[subject], atMs: timestampMs)

    if let smoothing = smoothing {
      // In body spans, as live: x is width-normalized, so its span is scaled by the aspect.
      let span = Geometry.bodySpan(landmarks)
      let aspect = size.width > 0 ? Float(size.height / size.width) : 1
      smoothing.apply(to: &landmarks, elapsedSeconds: elapsed ?? .nan, scaleX: span * aspect, scaleY: span)
    }

    var center = [Float](repeating: 0, count: 2)
    Geometry.centerOfMass(landmarks, into: &center, at: 0)
    let velocity = track.velocity(comX: center[0], comY: center[1], elapsed: elapsed)
    return StaticDetection.encode(
      landmarks,
      result: result,
      poseIndex: subject,
      shape: shape,
      size: size,
      velocity: velocity
    )
  }
}
