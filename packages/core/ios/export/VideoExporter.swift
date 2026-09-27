import AVFoundation
import MediaPipeTasksVision
import UIKit

struct ReadSide {
  let reader: AVAssetReader
  let video: AVAssetReaderTrackOutput
  let audio: AVAssetReaderTrackOutput?
  let audioFormat: CMFormatDescription?
}

/// `projection` places both the frame and the skeleton, so the two cannot drift apart.
struct ExportGeometry {
  let display: CGSize
  let canvas: CGSize
  let orientation: UIImage.Orientation
  let projection: OverlayProjection
}

struct WriteSide {
  let writer: AVAssetWriter
  let video: AVAssetWriterInput
  let audio: AVAssetWriterInput?
  let adaptor: AVAssetWriterInputPixelBufferAdaptor
}

struct ExportCancelled: Error {}

/// Detects at `sampleFps` and holds the last pose between samples, as the live overlay does. Bakes
/// rotation into the pixels, since many web and server players ignore a track transform.
final class VideoExporter {
  /// Long enough not to spin, short enough that a ready encoder never sits idle for long.
  static let encoderPollSeconds: TimeInterval = 0.002
  static let progressStep: Float = 0.02

  let source: URL
  let options: ExportOptions
  let isCancelled: () -> Bool
  let onProgress: (Float) -> Void

  var lastReportedProgress: Float = -1

  init(
    source: URL,
    options: ExportOptions,
    isCancelled: @escaping () -> Bool,
    onProgress: @escaping (Float) -> Void
  ) {
    self.source = source
    self.options = options
    self.isCancelled = isCancelled
    self.onProgress = onProgress
  }

  func run() throws -> ExportSummary {
    let asset = AVURLAsset(url: source)
    guard let track = AssetCompat.tracks(asset, of: .video).first else {
      throw ExportError("no video track in \(source.lastPathComponent)")
    }

    let transform = AssetCompat.preferredTransform(track)
    let natural = AssetCompat.naturalSize(track)
    let display = CGSize(
      width: abs(natural.width * transform.a + natural.height * transform.c),
      height: abs(natural.width * transform.b + natural.height * transform.d)
    )
    let canvas = exportCanvasSize(display: display, maxSize: options.maxSize)
    let output = options.directory.appendingPathComponent("\(options.fileName).mp4")

    // Staged, so dying mid-write never leaves a fake finished export or costs the previous one.
    let staging = options.directory.appendingPathComponent("\(options.fileName).partial.mp4")
    try? FileManager.default.removeItem(at: staging)

    let reader = try makeReader(asset: asset, track: track)
    let writer = try makeWriter(output: staging, canvas: canvas, audioFormat: reader.audioFormat)

    var finished = false
    defer {
      if !finished {
        reader.reader.cancelReading()
        writer.writer.cancelWriting()
        try? FileManager.default.removeItem(at: staging)
      }
    }

    let summary = try encode(
      reader: reader,
      writer: writer,
      asset: asset,
      geometry: ExportGeometry(
        display: display,
        canvas: canvas,
        orientation: VideoFrameSampler.orientation(for: transform),
        projection: OverlayProjection(
          source: display,
          bounds: CGRect(origin: .zero, size: canvas),
          fit: .fit
        )
      ),
      output: output
    )
    // rename(2) replaces in one step: FileManager's move won't overwrite, and removing first
    // reopens the window staging closes.
    guard rename(staging.path, output.path) == 0 else {
      throw ExportError("the export could not be moved into place")
    }
    finished = true
    return summary
  }

  // MARK: - Pipeline

  func makeReader(asset: AVURLAsset, track: AVAssetTrack) throws -> ReadSide {
    let reader = try AVAssetReader(asset: asset)
    let video = AVAssetReaderTrackOutput(
      track: track,
      outputSettings: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
    )
    video.alwaysCopiesSampleData = false
    guard reader.canAdd(video) else { throw ExportError("could not read frames from the video") }
    reader.add(video)

    // Audio passes through compressed: re-encoding costs time and quality for nothing.
    var audio: AVAssetReaderTrackOutput?
    var audioFormat: CMFormatDescription?
    if let audioTrack = AssetCompat.tracks(asset, of: .audio).first,
       let format = AssetCompat.formatDescription(audioTrack) {
      let output = AVAssetReaderTrackOutput(track: audioTrack, outputSettings: nil)
      output.alwaysCopiesSampleData = false
      if reader.canAdd(output) {
        reader.add(output)
        audio = output
        audioFormat = format
      } else {
        PoseLog.warn(.detector, "the export reader refused the audio track, writing video only")
      }
    }
    return ReadSide(reader: reader, video: video, audio: audio, audioFormat: audioFormat)
  }

  func makeWriter(output: URL, canvas: CGSize, audioFormat: CMFormatDescription?) throws -> WriteSide {
    let writer = try AVAssetWriter(outputURL: output, fileType: .mp4)
    let video = AVAssetWriterInput(mediaType: .video, outputSettings: [
      AVVideoCodecKey: AVVideoCodecType.h264,
      AVVideoWidthKey: Int(canvas.width),
      AVVideoHeightKey: Int(canvas.height),
      AVVideoCompressionPropertiesKey: [
        AVVideoAverageBitRateKey: Int(canvas.width * canvas.height * 8),
        AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel
      ]
    ])
    // A file job: back pressure, not dropped frames.
    video.expectsMediaDataInRealTime = false
    guard writer.canAdd(video) else { throw ExportError("could not write video to the export") }
    writer.add(video)

    let adaptor = AVAssetWriterInputPixelBufferAdaptor(
      assetWriterInput: video,
      sourcePixelBufferAttributes: [
        kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
        kCVPixelBufferWidthKey as String: Int(canvas.width),
        kCVPixelBufferHeightKey as String: Int(canvas.height),
        kCVPixelBufferIOSurfacePropertiesKey as String: [:]
      ]
    )

    var audio: AVAssetWriterInput?
    if let audioFormat = audioFormat {
      let input = AVAssetWriterInput(mediaType: .audio, outputSettings: nil, sourceFormatHint: audioFormat)
      input.expectsMediaDataInRealTime = false
      if writer.canAdd(input) {
        writer.add(input)
        audio = input
      } else {
        PoseLog.warn(.detector, "the export writer refused the audio track, writing video only")
      }
    }
    return WriteSide(writer: writer, video: video, audio: audio, adaptor: adaptor)
  }
}
