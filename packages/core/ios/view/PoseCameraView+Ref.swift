import ExpoModulesCore
import UIKit

extension PoseCameraView {
  func switchCamera(onDone: @escaping (String) -> Void, onFailed: @escaping (String) -> Void) {
    setFacingInternal(camera.targetFacing.opposite, onDone: onDone, onFailed: onFailed)
  }

  func setFacingInternal(
    _ target: Facing,
    onDone: ((String) -> Void)?,
    onFailed: ((String) -> Void)? = nil
  ) {
    camera.switchTo(
      target,
      onDone: { [weak self] facing in
        guard let self = self else { return }
        // Frames already queued can be stamped after this read: a frame or two drawn mis-mirrored.
        self.staleBefore.value = (self.detector.value?.lastTimestampMs ?? 0) + 1
        self.previousFrameMs.value = 0
        // A hold is continuous on one camera; the new one starts it over.
        self.triggers.onPoseLost()
        self.syncOverlayMirroring()

        // Settle an earlier switch first, so overlapping switches leave no promise dangling.
        self.completeSwitch()
        let name = facing.nameForJs
        // Weak: this closure is stored on the view.
        self.pendingSwitchDone = { [weak self] in
          self?.onCameraChange(["facing": name])
          onDone?(name)
        }
        self.awaitingFirstFrame.value = true
        self.switchTimer?.invalidate()
        self.switchTimer = Timer.scheduledTimer(
          withTimeInterval: PoseCameraView.switchFrameTimeoutSeconds,
          repeats: false
        ) { [weak self] _ in
          self?.completeSwitch()
        }
      },
      onFailed: { [weak self] code, error in
        let message = error?.localizedDescription ?? "The camera could not be switched."
        self?.emitError(code, message)
        onFailed?(message)
      }
    )
  }

  /// Main thread only. Idempotent, so the frame path and the timeout can both call it.
  func completeSwitch() {
    awaitingFirstFrame.value = false
    switchTimer?.invalidate()
    switchTimer = nil
    guard let done = pendingSwitchDone else { return }
    pendingSwitchDone = nil
    done()
  }

  func pauseCamera() {
    camera.setAnalyzerEnabled(false)
    camera.pause()
    parkDetector(for: PoseCameraView.parkedReleaseSeconds)
    overlayView.clearPose()
  }

  func resumeCamera() {
    camera.setAnalyzerEnabled(true)
    camera.resume { [weak self] code, error in
      self?.emitError(code, error)
    }
    syncOverlayMirroring()
  }

  func startDetection() {
    propDetection = true
    applyDetectionState()
  }

  func stopDetection() {
    propDetection = false
    applyDetectionState()
  }

  func setOverlayEnabled(_ enabled: Bool) {
    overlayEnabled = enabled
    applyOverlayEnabled()
  }

  /// Applies now, not at the next render. Calibration is kept: every profile budgets against it.
  func applyProfile(_ profile: Profile) {
    propProfile = profile
    applyPerformance(reason: "calibration")
    restartSessionIfGeometryChanged()
  }

  /// Static and handed only thread-safe values, because it runs on the JavaScript thread.
  static func liveState(
    measuredFps: Guarded<Int>,
    lastResultMs: Guarded<Int64>,
    rate: Guarded<RateDecision>,
    idleFps: Guarded<Int?>,
    feeding: Guarded<Bool>
  ) -> [String: Any] {
    let last = lastResultMs.value
    let fps = last != 0 && Monotonic.nowMs() - last <= PoseCameraView.fpsStaleAfterMs ? measuredFps.value : 0
    let limitedBy: LimitedBy
    if !feeding.value {
      limitedBy = .paused
    } else if idleFps.value != nil {
      limitedBy = .idle
    } else {
      limitedBy = rate.value.limitedBy
    }
    return ["fps": fps, "limitedBy": limitedBy.rawValue]
  }

  func currentMeasuredFps() -> Int {
    let last = lastResultMs.value
    guard last != 0, Monotonic.nowMs() - last <= PoseCameraView.fpsStaleAfterMs else { return 0 }
    return measuredFps.value
  }

  func currentState() -> [String: Any] {
    return [
      "facing": camera.facing.nameForJs,
      "active": camera.isBound,
      "detecting": feeding.value && (detector.value != nil || detectorPending),
      "fps": currentMeasuredFps(),
      "delegate": resolvedDelegate ?? "CPU",
      "deviceTier": calibrator.tier.rawValue,
      "limitedBy": currentLimitedBy().rawValue
    ]
  }

  func profileState() -> [String: Any] {
    return [
      "profile": propProfile.rawValue,
      "phase": calibrator.phase.rawValue,
      "source": calibrator.source.rawValue,
      "tier": calibrator.tier.rawValue,
      "resolved": [
        "delegate": resolvedDelegate ?? "CPU",
        "targetFps": currentTargetFps(),
        "preview": geometry.preview,
        "analysis": geometry.analysis
      ],
      "p50InferenceMs": calibrator.p50InferenceMs,
      "measuredFps": currentMeasuredFps(),
      "limitedBy": currentLimitedBy().rawValue,
      "cameraFps": cameraFps.value,
      "thermalState": thermal.state.rawValue,
      "lowPower": lowPower
    ]
  }

  func currentTargetFps() -> Int {
    let decided = rate.value.fps
    return idleFps.value.map { min($0, decided) } ?? decided
  }

  func currentLimitedBy() -> LimitedBy {
    guard propDetection, feeding.value, camera.isBound, detector.value != nil || detectorPending else {
      return .paused
    }
    if idleFps.value != nil { return .idle }
    return rate.value.limitedBy
  }

  // MARK: - Events

  func emitReadyOnce() {
    guard !readySent, camera.isBound else { return }
    // Held while a build is pending: onReady reports the delegate actually in use.
    guard !detectorPending else { return }
    readySent = true

    let variant = modelPath
      .map { PoseDetector.fileName(from: $0) }
      .map { $0.replacingOccurrences(of: "pose_landmarker_", with: "").replacingOccurrences(of: ".task", with: "") }
      ?? "full"

    onReady([
      "model": variant,
      "delegate": resolvedDelegate ?? "CPU",
      "delegateRequested": propDelegate,
      "targetFps": currentTargetFps(),
      "limitedBy": currentLimitedBy().rawValue,
      "deviceTier": calibrator.tier.rawValue,
      "resolution": camera.previewSize.forJs,
      "analysisResolution": camera.analysisSize.forJs,
      "facing": camera.facing.nameForJs
    ])
  }

  func emitPerformanceChange(reason: String) {
    onPerformanceChange([
      "reason": reason,
      "delegate": resolvedDelegate ?? "CPU",
      "targetFps": currentTargetFps(),
      "limitedBy": currentLimitedBy().rawValue,
      "analysisResolution": camera.analysisSize.forJs,
      "actualFps": currentMeasuredFps(),
      "thermalState": thermal.state.rawValue,
      "lowPower": lowPower
    ])
  }

  func emitError(_ code: ErrorCode, _ message: String) {
    PoseLog.error(.camera, "\(code.rawValue): \(message)")
    onError(["code": code.rawValue, "message": message, "fatal": code.fatal])
  }

  func emitError(_ code: ErrorCode, _ error: Error?) {
    emitError(code, error?.localizedDescription ?? code.rawValue)
  }
}

extension CaptureSize {
  var forJs: [String: Any] {
    return ["width": width, "height": height]
  }
}
