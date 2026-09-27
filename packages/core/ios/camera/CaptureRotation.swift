import AVFoundation
import UIKit

enum CaptureRotation {
  /// The raw values line up; it is `UIDeviceOrientation` whose landscape cases are swapped.
  static func videoOrientation(for interface: UIInterfaceOrientation) -> AVCaptureVideoOrientation {
    return AVCaptureVideoOrientation(rawValue: interface.rawValue) ?? .portrait
  }

  /// Apple's mapping from the deprecated `videoOrientation` to `videoRotationAngle`.
  static func angle(for orientation: AVCaptureVideoOrientation) -> CGFloat {
    switch orientation {
    case .portrait: return 90
    case .portraitUpsideDown: return 270
    case .landscapeRight: return 0
    case .landscapeLeft: return 180
    @unknown default: return 90
    }
  }

  static func apply(_ orientation: AVCaptureVideoOrientation, to connection: AVCaptureConnection) {
    if #available(iOS 17.0, *) {
      let angle = self.angle(for: orientation)
      if connection.isVideoRotationAngleSupported(angle) {
        connection.videoRotationAngle = angle
      }
      return
    }
    if connection.isVideoOrientationSupported {
      connection.videoOrientation = orientation
    }
  }

  static func mirror(_ mirrored: Bool, on connection: AVCaptureConnection) {
    guard connection.isVideoMirroringSupported else { return }
    connection.automaticallyAdjustsVideoMirroring = false
    connection.isVideoMirrored = mirrored
  }
}
