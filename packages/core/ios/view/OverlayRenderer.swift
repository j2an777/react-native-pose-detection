import UIKit

/// Built once per config, not per frame like the renderer: no allocation on the draw path.
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

struct OverlayArc {
  let path: CGPath
  let color: CGColor
  let label: OverlayLabel?
}

struct OverlayLabel {
  let text: NSAttributedString
  let box: CGRect
  let cornerRadius: CGFloat
  let origin: CGPoint
}

struct OverlayPaths {
  let bones: CGPath?
  let joints: CGPath?
  let arcs: [OverlayArc]

  static let empty = OverlayPaths(bones: nil, joints: nil, arcs: [])
}

/// The overlay geometry, shared by the live layers and the exporter so the two cannot disagree.
/// Holds no view, so the export queue can build and draw it.
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

  /// 1 for the view, which draws in points; exports pass their pixel ratio.
  var scale: CGFloat = 1

  var lineWidth: CGFloat { config.lineWidth * scale }
  var pointRadius: CGFloat { config.pointRadius * scale }

  func paths() -> OverlayPaths {
    guard sourceWidth > 0, sourceHeight > 0 else { return .empty }
    return OverlayPaths(
      bones: config.connections ? bonesPath() : nil,
      joints: config.landmarks && pointRadius > 0 ? jointsPath() : nil,
      arcs: config.angles.isEmpty ? [] : angleArcs()
    )
  }

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

      guard isDrawable(from), isDrawable(to) else { continue }
      path.move(to: project(from))
      path.addLine(to: project(to))
    }
    return path.isEmpty ? nil : path
  }
}
