import AVFoundation
import UIKit

/// What a finished capture hands back to JavaScript.
struct CapturedPhoto {
  let uri: String
  let width: Int
  let height: Int
  let size: Int
  let mirrored: Bool

  var payload: [String: Any] {
    return ["uri": uri, "width": width, "height": height, "size": size, "mirrored": mirrored]
  }
}

struct CaptureError: LocalizedError {
  let message: String
  init(_ message: String) { self.message = message }
  var errorDescription: String? { return message }
}

/// One capture, one delegate.
///
/// `AVCapturePhotoOutput` holds its delegate weakly, so a delegate that is only a local would be
/// gone before the sensor answers. This keeps itself alive from `capture()` until it settles, and
/// settles exactly once however it ends.
final class PhotoCapture: NSObject, AVCapturePhotoCaptureDelegate {
  private let quality: Double
  private let mirror: Bool
  private let settle: (Result<CapturedPhoto, Error>) -> Void

  /// The strong reference that outlives the caller's stack frame. Cleared when we settle.
  private var selfReference: PhotoCapture?
  private var settled = false

  private init(quality: Double, mirror: Bool, settle: @escaping (Result<CapturedPhoto, Error>) -> Void) {
    self.quality = quality
    self.mirror = mirror
    self.settle = settle
  }

  /// `output` must already be in a running session. `settle` is always called, on the main queue.
  static func capture(
    with output: AVCapturePhotoOutput,
    quality: Double,
    mirror: Bool,
    orientation: AVCaptureVideoOrientation,
    settle: @escaping (Result<CapturedPhoto, Error>) -> Void
  ) {
    let delegate = PhotoCapture(quality: quality, mirror: mirror, settle: settle)
    delegate.selfReference = delegate

    let settings = AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.jpeg])
    settings.photoQualityPrioritization = .balanced

    // The connection carries rotation and mirroring; doing it here means no CPU pass afterwards.
    if let connection = output.connection(with: .video) {
      CaptureRotation.apply(orientation, to: connection)
      CaptureRotation.mirror(mirror, on: connection)
    }

    output.capturePhoto(with: settings, delegate: delegate)
  }

  func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
    if let error = error {
      finish(.failure(error))
      return
    }
    guard let data = photo.fileDataRepresentation() else {
      finish(.failure(CaptureError("the camera returned a photo with no data")))
      return
    }
    finish(write(data))
  }

  /// Fires when processing never started, e.g. the session stopped mid-capture. `didFinish...Photo`
  /// does not run then, so without this the promise would hang forever.
  func photoOutput(
    _ output: AVCapturePhotoOutput,
    didFinishCaptureFor resolvedSettings: AVCaptureResolvedPhotoSettings,
    error: Error?
  ) {
    guard let error = error else { return }
    finish(.failure(error))
  }

  private func write(_ data: Data) -> Result<CapturedPhoto, Error> {
    // Re-encoding at the asked quality: the sensor's JPEG is near-lossless and large.
    let encoded: Data
    let pixelSize: CGSize
    if let image = UIImage(data: data) {
      pixelSize = CGSize(
        width: image.size.width * image.scale,
        height: image.size.height * image.scale
      )
      encoded = quality >= 1 ? data : (image.jpegData(compressionQuality: CGFloat(quality)) ?? data)
    } else {
      return .failure(CaptureError("the photo could not be decoded"))
    }

    let directory = FileManager.default.temporaryDirectory.appendingPathComponent("pose-photos", isDirectory: true)
    let url = directory.appendingPathComponent("\(UUID().uuidString).jpg")
    do {
      try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
      try encoded.write(to: url, options: .atomic)
    } catch {
      return .failure(CaptureError("the photo could not be written: \(error.localizedDescription)"))
    }

    return .success(
      CapturedPhoto(
        uri: url.absoluteString,
        width: Int(pixelSize.width.rounded()),
        height: Int(pixelSize.height.rounded()),
        size: encoded.count,
        mirrored: mirror
      )
    )
  }

  private func finish(_ result: Result<CapturedPhoto, Error>) {
    guard !settled else { return }
    settled = true
    let settle = self.settle
    DispatchQueue.main.async {
      settle(result)
      // Last: releasing before the callback would free the closure it is standing in.
      self.selfReference = nil
    }
  }
}
