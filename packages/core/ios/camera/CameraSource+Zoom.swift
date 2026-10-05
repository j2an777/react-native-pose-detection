import AVFoundation

/// Zoom, kept apart from the session wiring for the same reason the torch is: it belongs to
/// whichever device is bound, so every bind and every lens switch has to settle it again.
///
/// The factor is what the device understands — 1 is the whole sensor, not "no zoom on a wide
/// lens". A phone whose back camera starts at 0.5× reports `minZoom` below 1, and clamping to
/// the device's own range is what keeps a pinch from throwing.
extension CameraSource {
  // MARK: - Main thread only

  /// Clamps into the bound device's range and applies now. Returns what was actually set, which a
  /// pinch needs in order to keep its own scale honest.
  @discardableResult
  func setZoom(_ factor: Double) -> Double {
    let settled = min(max(factor, minZoom), maxZoom)
    zoomRequested = settled
    applyZoom()
    return settled
  }

  /// Mirrors the bound device's range to main and puts the request back on the device.
  /// Call after every bind and after a switch lands — a new lens has its own range, and a
  /// telephoto factor carried onto an ultra-wide would frame something nobody asked for.
  func applyZoom() {
    sessionQueue.async { [self] in
      guard let device = input?.device else {
        DispatchQueue.main.async {
          self.minZoom = 1
          self.maxZoom = 1
          self.zoom = 1
        }
        return
      }
      let low = Double(device.minAvailableVideoZoomFactor)
      // Devices report absurd ceilings once they start interpolating pixels. Past the optical
      // range it is upscaling, and a skeleton drawn on upscaled mush is worse than a small one.
      let high = min(Double(device.maxAvailableVideoZoomFactor), CameraSource.zoomCeiling)
      let wanted = min(max(zoomRequested, low), high)
      do {
        try device.lockForConfiguration()
        defer { device.unlockForConfiguration() }
        device.videoZoomFactor = CGFloat(wanted)
      } catch {
        PoseLog.warn(.camera, "the zoom would not change: \(error.localizedDescription)")
      }
      DispatchQueue.main.async {
        self.minZoom = low
        self.maxZoom = high
        self.zoomRequested = wanted
        self.zoom = wanted
      }
    }
  }
}
