import AVFoundation
import UIKit

final class PreviewView: UIView {
  // swiftlint:disable:next static_over_final_class
  override class var layerClass: AnyClass {
    return AVCaptureVideoPreviewLayer.self
  }

  var previewLayer: AVCaptureVideoPreviewLayer? {
    return layer as? AVCaptureVideoPreviewLayer
  }

  override init(frame: CGRect) {
    super.init(frame: frame)
    previewLayer?.videoGravity = .resizeAspectFill
    backgroundColor = .black
  }

  @available(*, unavailable)
  required init?(coder: NSCoder) {
    fatalError("PreviewView is created in code, never from a nib")
  }
}
