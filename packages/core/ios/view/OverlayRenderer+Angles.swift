import UIKit

extension OverlayRenderer {
  func angleArcs() -> [OverlayArc] {
    var arcs = [OverlayArc]()
    arcs.reserveCapacity(config.angles.count)

    for (index, spec) in config.angles.enumerated() {
      let vertex = spec.triple[1]
      if Geometry.visibility(landmarks, joint: vertex) < spec.minVisibility { continue }

      let degrees = Geometry.angleDegrees(
        landmarks,
        proximal: spec.triple[0],
        vertex: vertex,
        distal: spec.triple[2],
        frameWidth: sourceWidth,
        frameHeight: sourceHeight
      )
      if degrees.isNaN { continue }

      let proximal = project(spec.triple[0])
      let center = project(vertex)
      let distal = project(spec.triple[2])

      // In target points, after mirror and fill, so the arc opens into the joint on either camera.
      let bisector = Geometry.bisectorRadians(
        proximalX: Float(proximal.x),
        proximalY: Float(proximal.y),
        vertexX: Float(center.x),
        vertexY: Float(center.y),
        distalX: Float(distal.x),
        distalY: Float(distal.y)
      )
      if bisector.isNaN { continue }

      let color = index < palette.arcs.count ? palette.arcs[index] : palette.stroke

      let sweep = CGFloat(degrees) / OverlayRenderer.degreesPerRadian
      let start = CGFloat(bisector) - sweep / 2
      let path = CGMutablePath()
      path.addArc(
        center: center,
        radius: spec.radius * scale,
        startAngle: start,
        endAngle: start + sweep,
        clockwise: false
      )

      let label = spec.label
        ? makeLabel(degrees: degrees, spec: spec, center: center, bisector: CGFloat(bisector), color: color)
        : nil
      arcs.append(OverlayArc(path: path, color: color, label: label))
    }
    return arcs
  }

  private func makeLabel(
    degrees: Float,
    spec: AngleOverlaySpec,
    center: CGPoint,
    bisector: CGFloat,
    color: CGColor
  ) -> OverlayLabel {
    let labelRadius = (spec.radius + OverlayRenderer.labelGap) * scale
    let anchor = CGPoint(x: center.x + cos(bisector) * labelRadius, y: center.y + sin(bisector) * labelRadius)

    var attributes = palette.labelAttributes
    attributes[.foregroundColor] = UIColor(cgColor: color)
    let text = NSAttributedString(string: format(degrees: degrees, decimals: spec.decimals), attributes: attributes)
    let size = text.size()
    let padding = OverlayRenderer.labelPadding * scale

    return OverlayLabel(
      text: text,
      box: CGRect(
        x: anchor.x - size.width / 2 - padding,
        y: anchor.y - size.height / 2 - padding / 2,
        width: size.width + padding * 2,
        height: size.height + padding
      ),
      cornerRadius: padding,
      origin: CGPoint(x: anchor.x - size.width / 2, y: anchor.y - size.height / 2)
    )
  }

  private func format(degrees: Float, decimals: Int) -> String {
    if decimals <= 0 {
      return "\(max(0, Int(degrees.rounded())))\u{00B0}"
    }
    // Fixed locale: a de-DE device would otherwise render "90,5".
    return String(format: "%.\(decimals)f\u{00B0}", locale: Locale(identifier: "en_US_POSIX"), degrees)
  }
}
