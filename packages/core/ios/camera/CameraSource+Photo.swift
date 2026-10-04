import AVFoundation

/// Stills, kept apart from the frame pipeline: the analysis output is what the detector lives on,
/// and a photo is a second, occasional consumer of the same session.
extension CameraSource {
  // MARK: - Session queue only

  /// Optional on purpose: an entry-level camera that will not take a second output still detects,
  /// it just cannot photograph. Failing the session over a still would be the wrong trade.
  func addPhotoOutput(to session: AVCaptureSession) {
    let output = AVCapturePhotoOutput()
    guard session.canAddOutput(output) else {
      photoOutput = nil
      PoseLog.warn(.camera, "this device will not add a photo output; takePhoto is unavailable")
      return
    }
    session.addOutput(output)
    photoOutput = output
  }

  // MARK: - Main thread only

  /// `settle` runs on main, exactly once.
  ///
  /// The capture itself is fired on the session queue: `photoOutput` belongs to that queue, and
  /// `capturePhoto(with:delegate:)` is what Apple's own samples call there.
  func capturePhoto(
    quality: Double,
    mirrorFront: Bool,
    settle: @escaping (Result<CapturedPhoto, Error>) -> Void
  ) {
    guard isBound else {
      settle(.failure(CaptureError("the camera is not running")))
      return
    }
    // The subject framed a mirrored preview, so the front camera matches it by default.
    let mirror = mirrorFront && facing == .front
    let current = token.value

    sessionQueue.async { [weak self] in
      guard let self = self else {
        DispatchQueue.main.async { settle(.failure(CaptureError("the camera was released"))) }
        return
      }
      guard self.isCurrent(current), let output = self.photoOutput else {
        let reason =
          self.photoOutput == nil
            ? "this device cannot take photos while detecting"
            : "the camera stopped before the photo was taken"
        DispatchQueue.main.async { settle(.failure(CaptureError(reason))) }
        return
      }
      PhotoCapture.capture(
        with: output,
        quality: quality,
        mirror: mirror,
        orientation: self.orientation,
        settle: settle
      )
    }
  }
}
