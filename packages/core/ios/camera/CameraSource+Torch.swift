import AVFoundation

/// The torch, kept apart from the session wiring: it is a property of whichever device is bound,
/// so every bind and every lens switch has to put the request back.
///
/// There is no flash mode. The session runs continuously for detection, and a strobe fired at the
/// shutter would blind the frames either side of it. A torch that stays on is the honest offer.
extension CameraSource {
  // MARK: - Main thread only

  /// Keeps the request even when this device has no flash, so switching back to the one that does
  /// restores the light rather than leaving the app's button on over a dark lens.
  func setTorch(_ on: Bool) {
    torchRequested = on
    applyTorch()
  }

  /// Mirrors the bound device's capability to `hasTorch` and puts the request back on the device.
  /// Call after every bind and after a switch lands — a rebind opens the device with torch off.
  func applyTorch() {
    let wanted = torchRequested
    sessionQueue.async { [self] in
      guard let device = input?.device, device.hasTorch else {
        DispatchQueue.main.async { self.hasTorch = false }
        return
      }
      // `isTorchAvailable` goes false while the device is hot. Skip the write, keep the capability:
      // the request stands and the next apply lights it once the device is willing again.
      if device.isTorchAvailable {
        do {
          try device.lockForConfiguration()
          defer { device.unlockForConfiguration() }
          device.torchMode = wanted ? .on : .off
        } catch {
          PoseLog.warn(.camera, "the torch would not switch: \(error.localizedDescription)")
        }
      } else if wanted {
        PoseLog.warn(.camera, "the torch is unavailable right now; the request stands")
      }
      DispatchQueue.main.async { self.hasTorch = true }
    }
  }
}
