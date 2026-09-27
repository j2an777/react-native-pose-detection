import AVFoundation
import MediaPipeTasksVision
import UIKit

extension VideoExporter {

  // swiftlint:disable:next function_body_length
  func encode(
    reader: ReadSide,
    writer: WriteSide,
    asset: AVURLAsset,
    geometry: ExportGeometry,
    output: URL
  ) throws -> ExportSummary {
    let detector = try FileDetector(
      modelPath: try StaticDetection.requireModel(),
      maxPoses: options.maxPoses,
      minConfidence: options.minConfidence
    )
    let pacer = FilePacer()
    // Detection gets an upright, smaller copy; the painting turns the full frame through UIKit.
    let upright = UprightFrames(
      orientation: geometry.orientation,
      size: exportCanvasSize(display: geometry.display, maxSize: VideoFrameSampler.maxLongSide)
    )
    let scale = overlayScale(canvas: geometry.canvas)
    let palette = OverlayPalette(options.overlay, scale: scale)

    try start(reader: reader, writer: writer)

    let durationMs = max(1, StaticDetection.durationMilliseconds(of: asset))
    var clock = SampleClock(stepMs: max(1, 1000 / options.sampleFps))

    // Copy-on-write makes each renderer's copy of a pose a retain rather than 132 floats.
    var poses = [[Float]]()
    var frameCount = 0
    var posesFound = 0
    var started = false
    var sampled = false
    var pendingAudio: CMSampleBuffer?

    while let sample = reader.video.copyNextSampleBuffer() {
      // Without a pool per frame, the autoreleased MPImage, CGImage and MediaPipe allocations of
      // every frame live until the export ends.
      try autoreleasepool {
        if isCancelled() { throw ExportCancelled() }
        guard let buffer = CMSampleBufferGetImageBuffer(sample) else { return }

        let presentation = CMSampleBufferGetPresentationTimeStamp(sample)
        if !started {
          writer.writer.startSession(atSourceTime: presentation)
          started = true
        }
        let positionMs = Int(CMTimeGetSeconds(presentation) * 1000)

        if let timestamp = clock.due(atMs: positionMs), let turned = upright.upright(buffer) {
          let image = try MPImage(pixelBuffer: turned, orientation: .up)
          poses = PoseExport.poses(try detector.detect(image, timestampMs: timestamp))
          posesFound += poses.isEmpty ? 0 : 1
          sampled = true
        }

        let renderers = poses.map {
          makeRenderer(palette: palette, landmarks: $0, geometry: geometry, scale: scale)
        }

        try paint(
          source: buffer,
          into: writer.adaptor,
          writer: writer.writer,
          at: presentation,
          geometry: geometry,
          renderers: renderers
        )
        frameCount += 1

        try drain(audio: reader, into: writer, upTo: presentation, pending: &pendingAudio)
        report(Float(positionMs) / Float(durationMs))
      }
      // Per sample, not per frame: the rest also covers the painting and encoding since the last.
      if sampled {
        sampled = false
        if !pacer.rest(isCancelled: isCancelled) { throw ExportCancelled() }
      }
    }

    if isCancelled() { throw ExportCancelled() }
    try drain(audio: reader, into: writer, upTo: .positiveInfinity, pending: &pendingAudio)

    if reader.reader.status == .failed {
      throw ExportError(reader.reader.error?.localizedDescription ?? "the video could not be decoded")
    }
    try finish(writer: writer)
    report(1)

    return ExportSummary(
      url: output,
      width: Int(geometry.canvas.width),
      height: Int(geometry.canvas.height),
      durationMs: Int(durationMs),
      frameCount: frameCount,
      posesFound: posesFound
    )
  }

  private func start(reader: ReadSide, writer: WriteSide) throws {
    guard reader.reader.startReading() else {
      throw ExportError(reader.reader.error?.localizedDescription ?? "could not start reading the video")
    }
    guard writer.writer.startWriting() else {
      throw ExportError(writer.writer.error?.localizedDescription ?? "could not start writing the export")
    }
  }

  private func makeRenderer(
    palette: OverlayPalette,
    landmarks: [Float],
    geometry: ExportGeometry,
    scale: CGFloat
  ) -> OverlayRenderer? {
    guard options.drawOverlay else { return nil }
    var renderer = OverlayRenderer(
      config: options.overlay,
      palette: palette,
      landmarks: landmarks,
      projection: geometry.projection,
      mirrored: false,
      sourceWidth: Int(geometry.display.width),
      sourceHeight: Int(geometry.display.height)
    )
    renderer.scale = scale
    return renderer
  }

  // MARK: - One frame

  private func paint(
    source: CVPixelBuffer,
    into adaptor: AVAssetWriterInputPixelBufferAdaptor,
    writer: AVAssetWriter,
    at time: CMTime,
    geometry: ExportGeometry,
    renderers: [OverlayRenderer?]
  ) throws {
    let canvas = geometry.canvas
    guard let pool = adaptor.pixelBufferPool else {
      throw ExportError("the encoder gave back no buffer pool")
    }
    var optional: CVPixelBuffer?
    guard CVPixelBufferPoolCreatePixelBuffer(nil, pool, &optional) == kCVReturnSuccess,
          let destination = optional else {
      throw ExportError("could not take a frame buffer from the encoder")
    }

    CVPixelBufferLockBaseAddress(source, .readOnly)
    CVPixelBufferLockBaseAddress(destination, [])
    defer {
      CVPixelBufferUnlockBaseAddress(destination, [])
      CVPixelBufferUnlockBaseAddress(source, .readOnly)
    }

    guard let context = CGContext(
      data: CVPixelBufferGetBaseAddress(destination),
      width: Int(geometry.canvas.width),
      height: Int(geometry.canvas.height),
      bitsPerComponent: 8,
      bytesPerRow: CVPixelBufferGetBytesPerRow(destination),
      space: CGColorSpaceCreateDeviceRGB(),
      bitmapInfo: CGImageAlphaInfo.noneSkipFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue
    ) else {
      throw ExportError("could not draw into the encoder's frame buffer")
    }

    // Core Graphics counts up from the bottom: flip into the top-down space UIKit and the overlay
    // expect, or every frame encodes upside down.
    context.translateBy(x: 0, y: canvas.height)
    context.scaleBy(x: 1, y: -1)
    UIGraphicsPushContext(context)
    defer { UIGraphicsPopContext() }

    context.setFillColor(UIColor.black.cgColor)
    context.fill(CGRect(origin: .zero, size: canvas))
    if let image = VideoExporter.wrap(source) {
      // Drawn upright, as detection saw it, so the landmarks already match.
      UIImage(cgImage: image, scale: 1, orientation: geometry.orientation).draw(in: geometry.projection.rect)
    }
    for renderer in renderers {
      renderer?.draw(into: context)
    }

    try awaitReady(adaptor.assetWriterInput, writer: writer)
    guard adaptor.append(destination, withPresentationTime: time) else {
      throw ExportError(writer.error?.localizedDescription ?? "the encoder rejected a frame")
    }
  }

  /// Gives up when the writer fails, as readiness then never returns, or when cancelled.
  private func awaitReady(_ input: AVAssetWriterInput, writer: AVAssetWriter) throws {
    while !input.isReadyForMoreMediaData {
      guard writer.status == .writing else {
        throw ExportError(writer.error?.localizedDescription ?? "the export stopped being written")
      }
      if isCancelled() { throw ExportCancelled() }
      Thread.sleep(forTimeInterval: VideoExporter.encoderPollSeconds)
    }
  }

  /// No copy, and a no-op release: the buffer owns the memory. Valid only while it stays locked.
  private static func wrap(_ buffer: CVPixelBuffer) -> CGImage? {
    let height = CVPixelBufferGetHeight(buffer)
    let bytesPerRow = CVPixelBufferGetBytesPerRow(buffer)
    guard let base = CVPixelBufferGetBaseAddress(buffer),
          let provider = CGDataProvider(
            dataInfo: nil,
            data: base,
            size: bytesPerRow * height,
            releaseData: { _, _, _ in }
          ) else { return nil }

    return CGImage(
      width: CVPixelBufferGetWidth(buffer),
      height: height,
      bitsPerComponent: 8,
      bitsPerPixel: 32,
      bytesPerRow: bytesPerRow,
      space: CGColorSpaceCreateDeviceRGB(),
      bitmapInfo: [.byteOrder32Little, CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipFirst.rawValue)],
      provider: provider,
      decode: nil,
      shouldInterpolate: false,
      intent: .defaultIntent
    )
  }

  // MARK: - The other track, and the end

  /// Keeps audio interleaved with video: one reader feeds both, and draining unevenly stalls it.
  private func drain(
    audio reader: ReadSide,
    into writer: WriteSide,
    upTo time: CMTime,
    pending: inout CMSampleBuffer?
  ) throws {
    guard let output = reader.audio, let input = writer.audio else { return }
    while true {
      guard let sample = pending ?? output.copyNextSampleBuffer() else { return }
      pending = nil
      if CMSampleBufferGetPresentationTimeStamp(sample) > time {
        pending = sample
        return
      }
      try awaitReady(input, writer: writer.writer)
      // Not fatal: a gap in the sound beats no file.
      if !input.append(sample) {
        PoseLog.warn(.engine, "the export dropped an audio sample")
      }
    }
  }

  private func finish(writer: WriteSide) throws {
    writer.video.markAsFinished()
    writer.audio?.markAsFinished()

    // `finishWriting` returns before the file is closed, and JavaScript is told the file exists.
    let done = DispatchSemaphore(value: 0)
    writer.writer.finishWriting { done.signal() }
    done.wait()

    if writer.writer.status != .completed {
      throw ExportError(writer.writer.error?.localizedDescription ?? "the export could not be written")
    }
  }

  /// Throttled: per frame it is thirty crossings a second for a number nobody reads that fast.
  private func report(_ progress: Float) {
    let clamped = min(1, max(0, progress))
    guard clamped >= lastReportedProgress + VideoExporter.progressStep || clamped >= 1 else { return }
    lastReportedProgress = clamped
    onProgress(clamped)
  }
}

struct SampleClock {
  let stepMs: Int
  private var nextMs = 0
  private var lastTimestampMs = -1

  init(stepMs: Int) {
    self.stepMs = stepMs
  }

  /// Nil when no sample is due. VIDEO mode needs rising timestamps; a VFR clip can repeat a ms.
  mutating func due(atMs positionMs: Int) -> Int? {
    guard positionMs >= nextMs else { return nil }
    let timestamp = max(positionMs, lastTimestampMs + 1)
    lastTimestampMs = timestamp
    nextMs = positionMs + stepMs
    return timestamp
  }
}
