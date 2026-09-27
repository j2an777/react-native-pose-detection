import CoreImage
import CoreVideo
import ImageIO
import UIKit

/// Turns the pixels rather than passing an orientation: given a sideways clip, VIDEO mode loses the
/// body on about a third of its frames.
final class UprightFrames {
  /// What landmarks are normalized against.
  let size: CGSize

  private let orientation: CGImagePropertyOrientation
  // No colour management: detection wants the pixels as decoded, and matching costs a pass.
  private let context = CIContext(options: [.workingColorSpace: NSNull(), .outputColorSpace: NSNull()])
  private var pool: CVPixelBufferPool?

  init(orientation: UIImage.Orientation, size: CGSize) {
    self.orientation = UprightFrames.imageOrientation(orientation)
    self.size = size
  }

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
