import AVFoundation
import MediaPipeTasksVision
import UIKit

/// Must never slow the live camera: its own detector, no GPU while a camera detects, a serial
/// `.utility` queue below the camera's, and one frame in memory at a time.
enum PoseExport {
  private static let videoExtensions: Set<String> = ["mp4", "mov", "m4v", "3gp", "avi", "mkv", "webm"]

  static let queue = DispatchQueue(label: "com.posedetection.export", qos: .utility)

  private static let running = CancelRegistry()

  static func cancel(taskId: Int) {
    running.cancel(taskId)
  }

  static func isVideo(uri: String) -> Bool {
    let ext = JS.url(uri).pathExtension.lowercased()
    return videoExtensions.contains(ext)
  }

  static func run(
    uri: String,
    raw: [String: Any]?,
    taskId: Int,
    onProgress: @escaping (Float) -> Void
  ) throws -> ExportSummary {
    let source = JS.url(uri)
    let options = try ExportOptions.parse(raw, sourceName: source.deletingPathExtension().lastPathComponent)
    // The resolved `minConfidence` is not visible from JavaScript otherwise.
    PoseLog.info(.engine, "export maxPoses=\(options.maxPoses) minConfidence=\(options.minConfidence)")

    running.begin(taskId)
    // A suspended app can be killed mid-write with nothing unwound; the background task buys time,
    // and its expiry cancels so the export leaves through the usual cleanup.
    let background = UIApplication.shared.beginBackgroundTask(withName: "pose-export") {
      running.cancel(taskId)
    }
    defer {
      running.end(taskId)
      if background != .invalid {
        UIApplication.shared.endBackgroundTask(background)
      }
    }

    if isVideo(uri: uri) {
      let exporter = VideoExporter(
        source: source,
        options: options,
        isCancelled: { running.isCancelled(taskId) },
        onProgress: onProgress
      )
      return try exporter.run()
    }
    return try exportImage(source: source, options: options, onProgress: onProgress)
  }

  // MARK: - Stills

  private static func exportImage(
    source: URL,
    options: ExportOptions,
    onProgress: @escaping (Float) -> Void
  ) throws -> ExportSummary {
    // Detect on a small decode, released before the painted one: a 48 MP export never holds both.
    let paintMax = options.maxSize > 0 ? options.maxSize : nil
    let shared = paintMax.map { $0 <= StillImage.detectionMaxPixels } ?? false
    let unreadable = ExportError("could not read an image from \(source.lastPathComponent)")
    guard let file = StillImage.source(uri: source.absoluteString) else { throw unreadable }

    let (result, kept) = try autoreleasepool { () -> (PoseLandmarkerResult, CGImage?) in
      let maxPixels = shared ? paintMax : StillImage.detectionMaxPixels
      guard let detectable = StillImage.decode(file, maxPixels: maxPixels) else { throw unreadable }
      let detector = try PoseDetector.createForStillInput(
        modelPath: try StaticDetection.requireModel(),
        maxPoses: options.maxPoses,
        minConfidence: options.minConfidence,
        video: false
      )
      let result = try detector.detectImage(try MPImage(uiImage: UIImage(cgImage: detectable)))
      return (result, shared ? detectable : nil)
    }
    onProgress(0.6)

    guard let picture = kept ?? StillImage.decode(file, maxPixels: paintMax) else { throw unreadable }
    let image = UIImage(cgImage: picture)
    let display = CGSize(width: picture.width, height: picture.height)
    let canvas = exportCanvasSize(display: display, maxSize: options.maxSize)
    // Fit, not fill: cropping would cut away part of what the user picked.
    let projection = OverlayProjection(
      source: display,
      bounds: CGRect(origin: .zero, size: canvas),
      fit: .fit
    )

    let painted = paint(
      image,
      result: result,
      options: options,
      projection: projection,
      geometry: (display: display, canvas: canvas)
    )

    let url = options.directory.appendingPathComponent("\(options.fileName).jpg")
    guard let data = painted.jpegData(compressionQuality: options.quality) else {
      throw ExportError("could not encode the painted image")
    }
    try data.write(to: url, options: .atomic)
    onProgress(1)

    return ExportSummary(
      url: url,
      width: Int(canvas.width),
      height: Int(canvas.height),
      durationMs: 0,
      frameCount: 1,
      posesFound: result.landmarks.count
    )
  }

  private static func paint(
    _ image: UIImage,
    result: PoseLandmarkerResult,
    options: ExportOptions,
    projection: OverlayProjection,
    geometry: (display: CGSize, canvas: CGSize)
  ) -> UIImage {
    let canvas = geometry.canvas
    let format = UIGraphicsImageRendererFormat()
    format.scale = 1
    format.opaque = true
    let scale = overlayScale(canvas: canvas)
    let palette = OverlayPalette(options.overlay, scale: scale)

    // `UIGraphicsImageRenderer` is safe off the main thread, so an export never hops onto it.
    return UIGraphicsImageRenderer(size: canvas, format: format).image { context in
      image.draw(in: projection.rect)
      guard options.drawOverlay else { return }
      for landmarks in poses(result) {
        var renderer = OverlayRenderer(
          config: options.overlay,
          palette: palette,
          landmarks: landmarks,
          projection: projection,
          // A file is never mirrored: what was picked is what gets painted.
          mirrored: false,
          sourceWidth: Int(geometry.display.width),
          sourceHeight: Int(geometry.display.height)
        )
        renderer.scale = scale
        renderer.draw(into: context.cgContext)
      }
    }
  }

  static func poses(_ result: PoseLandmarkerResult) -> [[Float]] {
    return result.landmarks.map { pose in
      var landmarks = [Float](repeating: 0, count: Skeleton.landmarkCount * Skeleton.landmarkStride)
      for index in 0..<min(Skeleton.landmarkCount, pose.count) {
        let point = pose[index]
        let base = index * Skeleton.landmarkStride
        landmarks[base + Skeleton.offsetX] = point.x
        landmarks[base + Skeleton.offsetY] = point.y
        landmarks[base + Skeleton.offsetZ] = point.z
        landmarks[base + Skeleton.offsetVisibility] = point.visibility?.floatValue ?? 0
      }
      return landmarks
    }
  }
}
