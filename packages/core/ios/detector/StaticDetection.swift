import AVFoundation
import Foundation
import MediaPipeTasksVision
import UIKit

/// A file job's failure, carrying the code its promise rejects with.
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

/**
 The same detector, without a camera.

 Nothing here calibrates: a file has no frame budget to hit, so it always runs at full quality. What
 it does answer to is heat, through `FilePacer`, and to the camera, through `FileDetector`'s choice
 of delegate.
 */
enum StaticDetection {
  private static let millisPerSecond: Double = 1_000

  /**
   Where photo and video detection run: serial, below the camera's own queue, and this package's
   own. Expo runs every module's async functions on one shared queue, so a video job there held up
   every other module in the app for as long as it ran.
   */
  static let queue = DispatchQueue(label: "com.posedetection.files", qos: .utility)

  private static let running = CancelRegistry()

  static func cancel(taskId: Int) {
    running.cancel(taskId)
  }

  /// One entry per detected pose, the subject first, so a two-person photo decodes to two frames.
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

    // No try/finally around the decode, unlike Android: ARC releases the image and the detector
    // when a throw unwinds this frame, so there is no window where either can be stranded.
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

  /**
   Sampled at `fps`, not at the video's own rate, and run through VIDEO mode with monotonic
   timestamps so temporal tracking behaves the way it does live. Each frame carries its real
   position in the video, which is what smoothing and velocity are measured against.
   */
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

    guard let url = URL(string: uri) ?? URL(string: "file://\(uri)") else {
      throw StaticDetectionError(.videoDecodeFailed, "could not read a video from \(uri)")
    }
    let sampler = try VideoFrameSampler(url: url, fps: options.fps, startMs: options.startMs, endMs: options.endMs)
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

    while !isCancelled() {
      guard let frame = try sampler.next() else { break }
      // MPImage and everything MediaPipe allocates behind it are autoreleased, and this loop never
      // returns to a run loop, so without a pool per sample a long clip holds every one of them.
      try autoreleasepool {
        // VIDEO mode rejects a timestamp that does not move forward, and a variable frame rate clip
        // can hand back two frames on the same millisecond.
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
      onProgress(sampler.progress(of: frame))
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

  /// The same block order the live path writes, because it is the same decoder on the other side.
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

/**
 The subject of a video, followed from one sampled frame to the next: the same rules the live view
 applies. The largest body is the subject, smoothing and velocity are measured against real
 timestamps, and both start over when the subject is lost, changes, or a gap opens (see
 `PoseTrack`).
 */
struct VideoTracker {
  private var track: PoseTrack
  private let smoothing: OneEuroFilter?
  private let size: CGSize

  init(fps: Int, smoothing: Bool, size: CGSize) {
    track = PoseTrack(sampleFps: fps)
    self.smoothing = smoothing ? OneEuroFilter() : nil
    self.size = size
  }

  /// The subject's frame, or nil when nobody was found.
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
      // Speed in body spans, as live: x is normalized by width, so its span is scaled to it.
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
