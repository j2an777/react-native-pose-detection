import UIKit

/**
 Colors and text attributes derived from a config, built once and reused across draws.

 Separate from `OverlayRenderer` because a renderer is built per frame and this is not: converting
 a packed color and building a font on every frame would be allocation on the draw path, which is
 the one place this package does not allocate.
 */
struct OverlayPalette {
  let stroke: CGColor
  let arcs: [CGColor]
  let labelAttributes: [NSAttributedString.Key: Any]

  init(_ config: OverlayConfig, scale: CGFloat = 1) {
    stroke = config.color.uiColor.cgColor
    arcs = config.angles.map { ($0.color ?? config.color).uiColor.cgColor }
    labelAttributes = [
      .font: UIFont.systemFont(ofSize: OverlayRenderer.labelFontSize * scale, weight: .semibold)
    ]
  }
}

/// One angle's arc, and its label when the spec asks for one.
struct OverlayArc {
  let path: CGPath
  let color: CGColor
  let label: OverlayLabel?
}

/// A degree label: the text, and the rounded box drawn behind it so it reads on any frame.
struct OverlayLabel {
  let text: NSAttributedString
  let box: CGRect
  let cornerRadius: CGFloat
  let origin: CGPoint
}

/// Everything one pose draws, as paths in the target's coordinates.
struct OverlayPaths {
  let bones: CGPath?
  let joints: CGPath?
  let arcs: [OverlayArc]

  static let empty = OverlayPaths(bones: nil, joints: nil, arcs: [])
}

/**
 The skeleton, as paths, and drawn into any `CGContext`.

 This is the only place the overlay's geometry is worked out. The live view hands `paths()` to shape
 layers, which the GPU composites; the exporter draws the same paths into a context over the pixel
 buffer it is about to encode. Neither knows how the other works, and because the geometry lives
 here rather than in either of them, a painted export and a live preview of the same pose cannot
 disagree about where a joint goes. `OverlayProjection` makes the same guarantee one level down,
 for the rect the pose is projected into.

 A value type with no reference to a view, so it is safe to build and draw on the export queue.
 */
struct OverlayRenderer {
  static let labelFontSize: CGFloat = 13
  static let labelGap: CGFloat = 18
  static let labelPadding: CGFloat = 5
  static let degreesPerRadian = CGFloat(180.0 / Double.pi)
  static let arcWidthRatio: CGFloat = 0.75
  static let labelBackground = UIColor(white: 0, alpha: 140.0 / 255.0).cgColor

  let config: OverlayConfig
  let palette: OverlayPalette
  let landmarks: [Float]
  let projection: OverlayProjection
  let mirrored: Bool
  let sourceWidth: Int
  let sourceHeight: Int

  /**
   Multiplies every width, radius and font size.

   A view draws in points, where a `lineWidth` of 3 is 3 points on a screen a few hundred points
   wide. An export draws in pixels, where 3 would be a hair on a 1080 pixel frame, so the same
   config would produce a skeleton nobody can see. The exporter passes the ratio that puts the two
   back on the same footing; the view passes 1 and is unaffected.
   */
  var scale: CGFloat = 1

  var lineWidth: CGFloat { config.lineWidth * scale }
  var pointRadius: CGFloat { config.pointRadius * scale }

  /// The pose as paths. Parts the config turns off are nil or empty rather than drawn somewhere.
  func paths() -> OverlayPaths {
    guard sourceWidth > 0, sourceHeight > 0 else { return .empty }
    return OverlayPaths(
      bones: config.connections ? bonesPath() : nil,
      joints: config.landmarks && pointRadius > 0 ? jointsPath() : nil,
      arcs: config.angles.isEmpty ? [] : angleArcs()
    )
  }

  /// The export's path: the same geometry the live layers show, stroked and filled into `context`.
  func draw(into context: CGContext) {
    let paths = self.paths()

    if let bones = paths.bones {
      context.setStrokeColor(palette.stroke)
      context.setLineWidth(lineWidth)
      context.setLineCap(.round)
      context.addPath(bones)
      context.strokePath()
    }
    if let joints = paths.joints {
      context.setFillColor(palette.stroke)
      context.addPath(joints)
      context.fillPath()
    }
    for arc in paths.arcs {
      context.setLineWidth(lineWidth * OverlayRenderer.arcWidthRatio)
      context.setStrokeColor(arc.color)
      context.addPath(arc.path)
      context.strokePath()
      guard let label = arc.label else { continue }
      context.setFillColor(OverlayRenderer.labelBackground)
      context.addPath(CGPath(
        roundedRect: label.box,
        cornerWidth: label.cornerRadius,
        cornerHeight: label.cornerRadius,
        transform: nil
      ))
      context.fillPath()
      // Needs a current UIKit context, which the export pushes around the whole frame.
      label.text.draw(at: label.origin)
    }
  }

  /// Normalized frame coordinates to target points, through the projection this was built with.
  func project(_ joint: Int) -> CGPoint {
    let base = joint * Skeleton.landmarkStride
    return projection.point(
      x: CGFloat(landmarks[base + Skeleton.offsetX]),
      y: CGFloat(landmarks[base + Skeleton.offsetY]),
      mirrored: mirrored
    )
  }

  private func isDrawable(_ joint: Int) -> Bool {
    if Geometry.visibility(landmarks, joint: joint) < config.minVisibility { return false }
    guard let only = config.only else { return true }
    return only[joint]
  }

  private func jointsPath() -> CGPath? {
    let radius = pointRadius
    let path = CGMutablePath()
    for joint in 0..<Skeleton.landmarkCount where isDrawable(joint) {
      let point = project(joint)
      path.addEllipse(in: CGRect(x: point.x - radius, y: point.y - radius, width: radius * 2, height: radius * 2))
    }
    return path.isEmpty ? nil : path
  }

  private func bonesPath() -> CGPath? {
    let path = CGMutablePath()
    var index = 0
    while index < Skeleton.connections.count {
      let from = Skeleton.connections[index]
      let to = Skeleton.connections[index + 1]
      index += 2

      // A segment with one bad endpoint is a line to a guess, so it is not drawn at all.
      guard isDrawable(from), isDrawable(to) else { continue }
      path.move(to: project(from))
      path.addLine(to: project(to))
    }
    return path.isEmpty ? nil : path
  }
}
