import UIKit

/**
 Draws the skeleton over the preview. Nothing here crosses to JavaScript.

 The detector's callback thread writes `incoming`, the main thread renders from `landmarks`, and
 `frameLock` is held only for the copy between them. Without it a render already in flight can read
 some joints from one frame and the rest from the next, and the skeleton snaps apart.

 Shape layers rather than `draw(_:)`. A view that draws itself re-rasterizes its whole backing store
 on the CPU for every result, about 12 MB at an iPhone 15's full-screen size, thirty times a second;
 a shape layer is handed a path and the GPU composites it. The paths come from `OverlayRenderer`,
 the same geometry the exporter paints with, so live and exported skeletons cannot disagree.
 */
final class OverlayView: UIView {

  private let frameLock = NSLock()
  private var incoming = [Float](repeating: 0, count: Skeleton.landmarkCount * Skeleton.landmarkStride)
  private var incomingHasPose = false
  private var incomingMirrored = false
  private var incomingWidth = 0
  private var incomingHeight = 0

  // Everything below is the snapshot taken under the lock at the top of `render`, and is touched
  // only on the main thread from there on. Mirroring and the source size ride in the same snapshot
  // as the landmarks, so a camera switch can never draw new landmarks with the old mirroring.
  private var landmarks = [Float](repeating: 0, count: Skeleton.landmarkCount * Skeleton.landmarkStride)
  private var hasPose = false
  private var mirrored = false
  private var sourceWidth = 0
  private var sourceHeight = 0

  /// At most one render in flight. UIKit has no `postInvalidateOnAnimation`, so this coalesces.
  private var renderPending = false

  private let bones = CAShapeLayer()
  private let joints = CAShapeLayer()
  /// One per configured angle, reused across frames and hidden when an angle has nothing to show.
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

  /// Rebuilt when the config changes, never on the render path.
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
    // The projection depends on the bounds, so the current pose is laid out again at the new size.
    render()
  }

  func setMirrored(_ value: Bool) {
    frameLock.lock()
    incomingMirrored = value
    frameLock.unlock()
    requestRender()
  }

  /**
   Called from the detector's callback thread; copies into the view's buffer and asks for a render.

   The size travels with the landmarks rather than in a call of its own. Two critical sections let
   a render land between them and use new landmarks with the previous frame size, which is exactly
   the interleaving the snapshot in this class exists to prevent.
   */
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

  /// One hop to main per render at most, however many frames arrive in between.
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

  /// Main thread. Swaps the layers' paths in one transaction with implicit animations off.
  private func render() {
    // One copy under the lock, then the rest runs on a frame that cannot change underneath it.
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
          // The preview fills, so the skeleton fills with it.
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

  /// Layers are only ever added: a config with fewer angles hides the rest instead of freeing them.
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
