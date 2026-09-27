import CoreGraphics

/// `.fill` is `resizeAspectFill`, for the camera; `.fit` is `resizeAspect`, for static media.
enum ContentFit {
  case fill
  case fit
}

/// The one computation behind both the picture's rect and the skeleton, so they agree to the pixel.
struct OverlayProjection: Equatable {
  let rect: CGRect

  init(source: CGSize, bounds: CGRect, fit: ContentFit) {
    guard source.width > 0, source.height > 0, bounds.width > 0, bounds.height > 0 else {
      self.rect = bounds
      return
    }

    let sourceAspect = source.width / source.height
    let viewAspect = bounds.width / bounds.height
    let heightLeads = fit == .fill ? sourceAspect > viewAspect : sourceAspect < viewAspect

    let width: CGFloat
    let height: CGFloat
    if heightLeads {
      height = bounds.height
      width = height * sourceAspect
    } else {
      width = bounds.width
      height = width / sourceAspect
    }

    self.rect = CGRect(
      x: bounds.minX + (bounds.width - width) / 2,
      y: bounds.minY + (bounds.height - height) / 2,
      width: width,
      height: height
    )
  }

  func point(x: CGFloat, y: CGFloat, mirrored: Bool) -> CGPoint {
    let posX = mirrored ? 1 - x : x
    return CGPoint(x: rect.minX + posX * rect.width, y: rect.minY + y * rect.height)
  }
}
