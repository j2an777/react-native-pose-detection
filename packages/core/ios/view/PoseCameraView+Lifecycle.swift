import ExpoModulesCore
import UIKit

extension PoseCameraView {
  public override func didMoveToWindow() {
    super.didMoveToWindow()
    if window == nil {
      detachFromWindow()
    } else {
      attachToWindow()
    }
  }

  private func attachToWindow() {
    removeObservers()
    observe(UIApplication.didReceiveMemoryWarningNotification) { $0.handleMemoryWarning() }
    observe(UIApplication.didEnterBackgroundNotification) { $0.handleBackground() }
    observe(UIApplication.willEnterForegroundNotification) { $0.handleForeground() }
    // A 180-degree turn fires nothing else, and a stale rotation leaves landmarks upside down.
    observe(UIDevice.orientationDidChangeNotification) { $0.camera.updateTargetRotation() }
    // Heat applies at once; the timer catches cooling, which counts only after it has held.
    observe(ProcessInfo.thermalStateDidChangeNotification) { $0.sampleHeat() }
    observe(Notification.Name.NSProcessInfoPowerStateDidChange) { $0.sampleHeat() }

    // Claimed now, not on the first tick, so this camera's `onLog` sees its own start.
    PoseLog.claimStream(self)
    startLogTimer()
    startHeatTimer()
    // A reattached view restores what the props already say.
    onPropsUpdated()
  }

  /// Not destruction: a view scrolled out of a list comes back.
  private func detachFromWindow() {
    removeObservers()
    logTimer?.invalidate()
    logTimer = nil
    heatTimer?.invalidate()
    heatTimer = nil
    PoseLog.releaseStream(self)

    camera.setAnalyzerEnabled(false)
    camera.release()
    parkDetector(for: PoseCameraView.awayReleaseSeconds)
    completeSwitch()
    started = false
    readySent = false
  }

  private func observe(_ name: Notification.Name, _ handler: @escaping (PoseCameraView) -> Void) {
    let token = NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
      guard let self = self else { return }
      handler(self)
    }
    observerTokens.append(token)
  }

  func removeObservers() {
    for token in observerTokens {
      NotificationCenter.default.removeObserver(token)
    }
    observerTokens.removeAll()
  }

  /// The landmarker is the largest block of memory there is to give back.
  private func handleMemoryWarning() {
    PoseLog.warn(.detector, "memory warning, releasing the landmarker")
    releaseDetector()
    overlayView.clearPose()
  }

  /// AVFoundation stops the session itself; parking makes a quick return instant.
  private func handleBackground() {
    PoseLog.info(.camera, "backgrounded, parking the detector")
    parkDetector(for: PoseCameraView.awayReleaseSeconds)
    overlayView.clearPose()
  }

  private func handleForeground() {
    guard started, propActive else { return }
    PoseLog.info(.camera, "foregrounded, restoring detection")
    applyDetectionState()
  }

  /// The first attached view drains the shared log; with none attached, the module flushes.
  private func startLogTimer() {
    logTimer?.invalidate()
    logTimer = Timer.scheduledTimer(
      withTimeInterval: PoseLog.flushSeconds,
      repeats: true
    ) { [weak self] _ in
      self?.flushLog()
    }
  }

  private func startHeatTimer() {
    heatTimer?.invalidate()
    sampleHeat()
    heatTimer = Timer.scheduledTimer(
      withTimeInterval: ThermalMonitor.sampleIntervalSeconds,
      repeats: true
    ) { [weak self] _ in
      self?.sampleHeat()
    }
  }

  private func flushLog() {
    guard let entries = PoseLog.takeBatch(self) else { return }
    onLog(["entries": entries])
  }
}
