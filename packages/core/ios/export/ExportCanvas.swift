import CoreGraphics

/// Capped on the long edge with the aspect kept. Both axes even: H.264 rejects an odd size on some
/// devices and silently rounds it on others, shifting every pixel off the projected skeleton.
func exportCanvasSize(display: CGSize, maxSize: Int) -> CGSize {
  let longEdge = max(display.width, display.height)
  guard longEdge > 0, display.width > 0, display.height > 0 else {
    return CGSize(width: 2, height: 2)
  }

  // Never up: a bigger copy of the same picture only costs encode time.
  let scale = maxSize > 0 ? min(1, CGFloat(maxSize) / longEdge) : 1
  return CGSize(
    width: even(display.width * scale),
    height: even(display.height * scale)
  )
}

private func even(_ value: CGFloat) -> CGFloat {
  let rounded = max(2, value.rounded())
  return rounded - rounded.truncatingRemainder(dividingBy: 2)
}

/// About a phone screen's width in points, where the overlay defaults were tuned.
private let referenceEdge: CGFloat = 400

/// Multiplies overlay widths and radii so a config drawn in pixels looks as it does in points on a
/// screen. By the short edge, so landscape and portrait get the same weight of line.
func overlayScale(canvas: CGSize) -> CGFloat {
  let shortEdge = min(canvas.width, canvas.height)
  guard shortEdge > 0 else { return 1 }
  return max(1, shortEdge / referenceEdge)
}
