import AVFoundation
import UIKit

/// An `AVAssetReader` decodes each frame once, already scaled; an image generator's per-sample
/// seeks decode most frames several times over. See ADR 0012.
final class VideoFrameSampler {
  /// At 960 the model's 256-pixel body crop is sampled down for anyone over about 40% of a
  /// landscape frame, already more than the live camera's 480p gives.
  static let maxLongSide = 960

  struct Frame {
    let buffer: CVPixelBuffer
    let timestampMs: Int64
  }

  let orientation: UIImage.Orientation
  /// Upright, unlike the frames, which keep the file's storage orientation.
  let size: CGSize
  let startMs: Int64
  let endMs: Int64

  private let reader: AVAssetReader
  private let output: AVAssetReaderTrackOutput
  private let stepMs: Int64
  private var dueMs: Int64

  /// `endMs` at or below zero, or past the end, means the end of the clip.
  init(url: URL, fps: Int, startMs: Int64, endMs: Int64) throws {
    let asset = AVURLAsset(url: url)
    guard let track = AssetCompat.tracks(asset, of: .video).first else {
      throw StaticDetectionError(.videoDecodeFailed, "no video track in \(url.lastPathComponent)")
    }
    let durationMs = StaticDetection.durationMilliseconds(of: asset)
    self.startMs = min(max(0, startMs), durationMs)
    self.endMs = endMs >= 1 && endMs <= durationMs ? endMs : durationMs
    stepMs = max(1, 1_000 / Int64(max(1, fps)))
    dueMs = self.startMs

    orientation = VideoFrameSampler.orientation(for: AssetCompat.preferredTransform(track))
    // Capped and even, like an export's canvas; the decoder scales to it.
    let scaled = exportCanvasSize(display: AssetCompat.naturalSize(track), maxSize: VideoFrameSampler.maxLongSide)
    let turned = orientation == .left || orientation == .right
    size = turned ? CGSize(width: scaled.height, height: scaled.width) : scaled

    do {
      reader = try AVAssetReader(asset: asset)
    } catch {
      throw StaticDetectionError(.videoDecodeFailed, error.localizedDescription)
    }
    // Decodes only the range, from the keyframe before `startMs`.
    reader.timeRange = CMTimeRange(
      start: CMTime(value: CMTimeValue(self.startMs), timescale: 1_000),
      end: CMTime(value: CMTimeValue(self.endMs), timescale: 1_000)
    )
    output = AVAssetReaderTrackOutput(track: track, outputSettings: [
      kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
      kCVPixelBufferWidthKey as String: Int(scaled.width),
      kCVPixelBufferHeightKey as String: Int(scaled.height)
    ])
    // Each buffer is read once and released, so a copy would buy nothing.
    output.alwaysCopiesSampleData = false
    guard reader.canAdd(output) else {
      throw StaticDetectionError(.videoDecodeFailed, "could not read frames from \(url.lastPathComponent)")
    }
    reader.add(output)
    guard reader.startReading() else {
      let reason = reader.error?.localizedDescription ?? "could not start reading \(url.lastPathComponent)"
      throw StaticDetectionError(.videoDecodeFailed, reason)
    }
  }

  deinit {
    reader.cancelReading()
  }

  private enum Read {
    case end
    case skipped
    case frame(Frame)
  }

  /// Nil at the end of the range. Samples keep to a grid from `startMs`: a late frame shifts none.
  func next() throws -> Frame? {
    while true {
      // Per decoded frame, skipped ones too: the job never yields to a run loop to drain one.
      switch autoreleasepool(invoking: readOne) {
      case .skipped:
        continue
      case .frame(let frame):
        return frame
      case .end:
        if reader.status == .failed {
          let reason = reader.error?.localizedDescription ?? "the video could not be decoded"
          throw StaticDetectionError(.videoDecodeFailed, reason)
        }
        return nil
      }
    }
  }

  private func readOne() -> Read {
    guard let sample = output.copyNextSampleBuffer() else { return .end }
    let seconds = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample))
    let timestampMs = Int64((seconds * 1_000).rounded())
    guard timestampMs >= dueMs, let buffer = CMSampleBufferGetImageBuffer(sample) else { return .skipped }
    while dueMs <= timestampMs {
      dueMs += stepMs
    }
    return .frame(Frame(buffer: buffer, timestampMs: timestampMs))
  }

  func progress(of frame: Frame) -> Float {
    let span = max(1, endMs - startMs)
    return min(1, max(0, Float(frame.timestampMs - startMs) / Float(span)))
  }

  static func orientation(for transform: CGAffineTransform) -> UIImage.Orientation {
    switch (transform.a, transform.b, transform.c, transform.d) {
    case (0, 1, -1, 0): return .right
    case (0, -1, 1, 0): return .left
    case (-1, 0, 0, -1): return .down
    default: return .up
    }
  }
}
