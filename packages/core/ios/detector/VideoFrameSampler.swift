import AVFoundation
import UIKit

/**
 A video's frames in order, each decoded once and scaled down, handing back only the ones a
 sampling rate asks for.

 `AVAssetImageGenerator` seeks for every sample. Each seek decodes forward from the keyframe before
 it at full size, so ten samples a second of a clip with two-second keyframes decode most frames
 several times over. A reader decodes each frame exactly once, and the scaling happens in the
 decoder's own output path, so what reaches MediaPipe is already small.

 Frames keep the file's storage orientation. `orientation` is what MediaPipe is told, so landmarks
 come back in the upright picture's space, which is the space `size` describes.
 */
final class VideoFrameSampler {
  /**
   The long side frames are decoded to. The detector sees 224 pixels of the whole frame and the
   landmark model a 256-pixel crop around the body, so at 960 that crop is sampled down rather than
   stretched for anybody taller than about 40% of a landscape frame. That is already better than
   the live camera's 480p, and a file has no deadline to trade detail for.
   */
  static let maxLongSide = 960

  struct Frame {
    let buffer: CVPixelBuffer
    /// Where the frame sits in the video, in milliseconds.
    let timestampMs: Int64
  }

  let orientation: UIImage.Orientation
  /// The upright frame, which is what landmarks are normalized against.
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
    // Capped and even, exactly like an export's canvas, which is what the decoder scales to.
    let scaled = exportCanvasSize(display: AssetCompat.naturalSize(track), maxSize: VideoFrameSampler.maxLongSide)
    let turned = orientation == .left || orientation == .right
    size = turned ? CGSize(width: scaled.height, height: scaled.width) : scaled

    do {
      reader = try AVAssetReader(asset: asset)
    } catch {
      throw StaticDetectionError(.videoDecodeFailed, error.localizedDescription)
    }
    // The reader starts at the keyframe before `startMs` and stops at `endMs`, so a trimmed range
    // decodes the range and not the whole clip.
    reader.timeRange = CMTimeRange(
      start: CMTime(value: CMTimeValue(self.startMs), timescale: 1_000),
      end: CMTime(value: CMTimeValue(self.endMs), timescale: 1_000)
    )
    output = AVAssetReaderTrackOutput(track: track, outputSettings: [
      kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
      kCVPixelBufferWidthKey as String: Int(scaled.width),
      kCVPixelBufferHeightKey as String: Int(scaled.height)
    ])
    // Detection reads each buffer once and lets it go, so a copy per frame would buy nothing.
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

  /**
   The next frame a sample is due at, or nil at the end of the range. Samples stay on an even grid
   from `startMs`, so one late frame does not push every later sample back with it.
   */
  func next() throws -> Frame? {
    while true {
      // One pool per decoded frame, skipped ones included: this loop never returns to a run loop,
      // and the reader's autoreleased objects would otherwise pile up until the job ends.
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

  /// How far through the range a frame is, 0 to 1.
  func progress(of frame: Frame) -> Float {
    let span = max(1, endMs - startMs)
    return min(1, max(0, Float(frame.timestampMs - startMs) / Float(span)))
  }

  /// A track's transform as the orientation MediaPipe and UIKit take.
  static func orientation(for transform: CGAffineTransform) -> UIImage.Orientation {
    switch (transform.a, transform.b, transform.c, transform.d) {
    case (0, 1, -1, 0): return .right
    case (0, -1, 1, 0): return .left
    case (-1, 0, 0, -1): return .down
    default: return .up
    }
  }
}
