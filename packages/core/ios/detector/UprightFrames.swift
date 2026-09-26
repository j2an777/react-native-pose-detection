import CoreImage
import CoreVideo
import ImageIO
import UIKit

/**
 Video frames turned upright and scaled for detection, as pixels MediaPipe reads as they are.

 MediaPipe takes an orientation alongside a buffer, and in VIDEO mode it loses the body on about a
 third of the frames of a clip stored sideways, which is how a phone records portrait video. The
 same clip stored upright is tracked on every frame. So the pixels are turned before MediaPipe sees
 them and handed over as `.up`, which is what the live camera does through its capture connection,
 and what Android does in GL.

 One Core Image pass turns and scales each sampled frame on the GPU, into a buffer from a small
 pool, so a job allocates a handful of buffers rather than one per sample. A frame that is already
 upright at the size asked for is handed back untouched.
 */
final class UprightFrames {
  /// The upright size frames come out at, which is what landmarks are normalized against.
  let size: CGSize

  private let orientation: CGImagePropertyOrientation
  // No colour management: detection wants the pixels as decoded, and matching costs a pass.
  private let context = CIContext(options: [.workingColorSpace: NSNull(), .outputColorSpace: NSNull()])
  private var pool: CVPixelBufferPool?

  init(orientation: UIImage.Orientation, size: CGSize) {
    self.orientation = UprightFrames.imageOrientation(orientation)
    self.size = size
  }

  /// The frame upright at `size`, or nil when no buffer could be had for it.
  func upright(_ buffer: CVPixelBuffer) -> CVPixelBuffer? {
    let width = CVPixelBufferGetWidth(buffer)
    let height = CVPixelBufferGetHeight(buffer)
    if orientation == .up && width == Int(size.width) && height == Int(size.height) {
      return buffer
    }

    var image = CIImage(cvPixelBuffer: buffer).oriented(orientation)
    let extent = image.extent
    guard extent.width > 0, extent.height > 0 else { return nil }
    image = image
      .transformed(by: CGAffineTransform(translationX: -extent.minX, y: -extent.minY))
      .transformed(by: CGAffineTransform(scaleX: size.width / extent.width, y: size.height / extent.height))

    guard let pool = pool ?? makePool(), let output = take(from: pool) else { return nil }
    context.render(image, to: output)
    return output
  }

  private func makePool() -> CVPixelBufferPool? {
    let attributes: [String: Any] = [
      kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
      kCVPixelBufferWidthKey as String: Int(size.width),
      kCVPixelBufferHeightKey as String: Int(size.height),
      kCVPixelBufferIOSurfacePropertiesKey as String: [:]
    ]
    var created: CVPixelBufferPool?
    CVPixelBufferPoolCreate(nil, nil, attributes as CFDictionary, &created)
    pool = created
    return created
  }

  private func take(from pool: CVPixelBufferPool) -> CVPixelBuffer? {
    var buffer: CVPixelBuffer?
    guard CVPixelBufferPoolCreatePixelBuffer(nil, pool, &buffer) == kCVReturnSuccess else { return nil }
    return buffer
  }

  /// The EXIF orientation a UIKit one means, which is what Core Image turns by.
  static func imageOrientation(_ orientation: UIImage.Orientation) -> CGImagePropertyOrientation {
    switch orientation {
    case .up: return .up
    case .down: return .down
    case .left: return .left
    case .right: return .right
    case .upMirrored: return .upMirrored
    case .downMirrored: return .downMirrored
    case .leftMirrored: return .leftMirrored
    case .rightMirrored: return .rightMirrored
    @unknown default: return .up
    }
  }
}
