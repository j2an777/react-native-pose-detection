import UIKit

/// The callback queue writes `incoming`; main copies it under `frameLock` and renders the copy.
/// Shape layers, not `draw(_:)`: a CPU redraw is ~12 MB per result at iPhone 15 full screen.
final class OverlayView: UIView {

  private let frameLock = NSLock()
  private var incoming = [Float](repeating: 0, count: Skeleton.landmarkCount * Skeleton.landmarkStride)
  private var incomingHasPose = false
  private var incomingMirrored = false
  private var incomingWidth = 0
  private var incomingHeight = 0

  // Main-thread copy; mirroring and size ride with the landmarks so a switch never mixes them.
  private var landmarks = [Float](repeating: 0, count: Skeleton.landmarkCount * Skeleton.landmarkStride)
  private var hasPose = false
  private var mirrored = false
  private var sourceWidth = 0
  private var sourceHeight = 0

  /// At most one render in flight; UIKit has no `postInvalidateOnAnimation`.
  private var renderPending = false

  private let bones = CAShapeLayer()
  private let joints = CAShapeLayer()
  private var arcLayers = [CAShapeLayer]()
  private var labelBoxes = [CAShapeLayer]()
  private var labelTexts = [CATextLayer]()

  var config = OverlayConfig() {
    didSet {
      guard config != oldValue else { return }
      palette = OverlayPalette(config)
      render()
    }
  }

  private var palette = OverlayPalette(OverlayConfig())

  override init(frame: CGRect) {
    super.init(frame: frame)
    backgroundColor = .clear
    isOpaque = false
    isUserInteractionEnabled = false

    bones.fillColor = nil
    bones.lineCap = .round
    bones.lineJoin = .round
    joints.strokeColor = nil
    layer.addSublayer(bones)
    layer.addSublayer(joints)
  }

  @available(*, unavailable)
  required init?(coder: NSCoder) {
    fatalError("OverlayView is created in code, never from a nib")
  }

  override func layoutSubviews() {
    super.layoutSubviews()
    render()
  }

  func setMirrored(_ value: Bool) {
    frameLock.lock()
    incomingMirrored = value
    frameLock.unlock()
    requestRender()
  }

  func submit(_ frame: [Float], width: Int, height: Int) {
    frameLock.lock()
    for index in 0..<incoming.count {
      incoming[index] = frame[index]
    }
    incomingWidth = width
    incomingHeight = height
    incomingHasPose = true
    frameLock.unlock()
    requestRender()
  }

  func clearPose() {
    frameLock.lock()
    incomingHasPose = false
    frameLock.unlock()
    requestRender()
  }

  private func requestRender() {
    frameLock.lock()
    if renderPending {
      frameLock.unlock()
      return
    }
    renderPending = true
    frameLock.unlock()

    DispatchQueue.main.async { [weak self] in
      guard let self = self else { return }
      self.frameLock.lock()
      self.renderPending = false
      self.frameLock.unlock()
      self.render()
    }
  }

  private func render() {
    frameLock.lock()
    hasPose = incomingHasPose
    mirrored = incomingMirrored
    sourceWidth = incomingWidth
    sourceHeight = incomingHeight
    if hasPose {
      for index in 0..<landmarks.count {
        landmarks[index] = incoming[index]
      }
    }
    frameLock.unlock()

    let drawable = hasPose && sourceWidth > 0 && sourceHeight > 0 && bounds.width > 0 && bounds.height > 0
    let paths = drawable
      ? OverlayRenderer(
        config: config,
        palette: palette,
        landmarks: landmarks,
        projection: OverlayProjection(
          source: CGSize(width: sourceWidth, height: sourceHeight),
          bounds: bounds,
          fit: .fill
        ),
        mirrored: mirrored,
        sourceWidth: sourceWidth,
        sourceHeight: sourceHeight
      ).paths()
      : .empty

    CATransaction.begin()
    CATransaction.setDisableActions(true)
    apply(paths)
    CATransaction.commit()
  }

  private func apply(_ paths: OverlayPaths) {
    for sublayer in [bones, joints] where sublayer.frame != bounds {
      sublayer.frame = bounds
    }

    bones.path = paths.bones
    bones.strokeColor = palette.stroke
    bones.lineWidth = config.lineWidth
    joints.path = paths.joints
    joints.fillColor = palette.stroke

    growArcLayers(to: paths.arcs.count)
    for index in arcLayers.indices {
      let arc = index < paths.arcs.count ? paths.arcs[index] : nil
      let shape = arcLayers[index]
      shape.frame = bounds
      shape.path = arc?.path
      shape.strokeColor = arc?.color
      shape.lineWidth = config.lineWidth * OverlayRenderer.arcWidthRatio

      let label = arc?.label
      labelBoxes[index].isHidden = label == nil
      labelTexts[index].isHidden = label == nil
      guard let label = label else { continue }
      labelBoxes[index].frame = bounds
      labelBoxes[index].path = CGPath(
        roundedRect: label.box,
        cornerWidth: label.cornerRadius,
        cornerHeight: label.cornerRadius,
        transform: nil
      )
      labelTexts[index].frame = CGRect(origin: label.origin, size: label.text.size())
      labelTexts[index].string = label.text
    }
  }

  /// Only ever grows: a config with fewer angles hides the extra layers.
  private func growArcLayers(to count: Int) {
    while arcLayers.count < count {
      let arc = CAShapeLayer()
      arc.fillColor = nil
      arc.lineCap = .round

      let box = CAShapeLayer()
      box.fillColor = OverlayRenderer.labelBackground
      box.strokeColor = nil

      let text = CATextLayer()
      text.contentsScale = traitCollection.displayScale > 0 ? traitCollection.displayScale : 2
      text.alignmentMode = .left

      layer.addSublayer(arc)
      layer.addSublayer(box)
      layer.addSublayer(text)
      arcLayers.append(arc)
      labelBoxes.append(box)
      labelTexts.append(text)
    }
  }
}
