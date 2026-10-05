import ExpoModulesCore
import UIKit

extension PoseCameraView {
  func setFacing(_ value: String) { propFacing = value }
  func setDelegate(_ value: String) { propDelegate = value }
  func setActive(_ value: Bool) { propActive = value }

  func setDetection(_ value: Bool) { propDetection = value }

  /// `hasTorch` rides on `onCameraChange` because it changes with the lens: an app told only about
  /// `facing` would leave a torch button on a front camera that cannot light.
  func setTorch(_ value: Bool) {
    guard value != camera.torchRequested else { return }
    camera.setTorch(value)
    emitCameraChange()
  }

  func emitCameraChange(_ facing: String? = nil) {
    onCameraChange([
      "facing": facing ?? camera.facing.nameForJs,
      "hasTorch": camera.hasTorch,
      "torch": camera.torchOn
    ])
  }
  func setMaxPoses(_ value: Int) { propMaxPoses = min(max(value, 1), 5) }

  func setMinConfidence(_ value: Double?) {
    propMinConfidence = value.map { Float(min(max($0, 0.1), 1)) }
  }

  func resolvedMinConfidence() -> Float {
    if let chosen = propMinConfidence { return chosen }
    return propMaxPoses > 1 ? PoseCameraView.multiPoseConfidence : PoseCameraView.minConfidence
  }
  func setResolution(_ value: String) { propPreview = value }
  func setAnalysisResolution(_ value: String) { propAnalysis = value }

  func setOverlay(enabled: Bool, config: OverlayConfig) {
    overlayEnabled = enabled
    pendingOverlayConfig = config
  }

  func setData(_ config: DataSettings) {
    propMode = config.mode
    propThrottleMs.value = config.throttleMs
    propFlushMs.value = config.flushMs
    propLandmarks = config.landmarks
    propWorldLandmarks = config.worldLandmarks
  }

  /// Resolved and ordered by JavaScript; re-deriving it here could only disagree.
  func setAngleJoints(_ joints: [String]) { propAngleJoints = joints }
  func setSelection(_ indices: [Int]?) { propSelection = indices }
  func setProfile(_ value: Profile) { propProfile = value }

  /// Nil is `auto`, which is the only value calibration is allowed to move.
  func setTargetFps(_ value: Int?) {
    propTargetFps = value.map { min(max($0, PoseCameraView.minTargetFps), PoseCameraView.maxTargetFps) }
  }

  func setThermalPolicy(_ value: ThermalPolicy) { propThermalPolicy = value }

  func setStreamId(_ id: Int?) {
    guard id != streamId else { return }
    if let previous = streamId {
      FrameStreams.shared.unregister(stream, id: previous)
    }
    streamId = id
    if let id = id {
      FrameStreams.shared.register(stream, id: id)
    }
  }

  func setSmoothing(enabled: Bool, minCutoff: Float, beta: Float) {
    propSmoothing = enabled
    propMinCutoff = minCutoff
    propBeta = beta
  }

  func setTriggers(_ specs: [TriggerSpec]) {
    // Not deferred to `onPropsUpdated`, which would evaluate a frame against the old set.
    triggers.setTriggers(specs)
  }

  func onPropsUpdated() {
    overlayView.config = pendingOverlayConfig
    applyOverlayEnabled()

    applyFrameLayout()
    smoothing.configure(minCutoff: propMinCutoff, beta: propBeta)
    applyPerformance(reason: nil)

    // Only props move geometry, so calibration or heat never restarts the camera.
    let next = resolveGeometry()
    let geometryChanged = next != geometry
    adopt(next)
    let pinnedFacing = propFacing == "front" || propFacing == "back"
    camera.facingFallbackAllowed = !pinnedFacing

    if !propActive {
      stopSession()
      return
    }
    if !started {
      startSession()
      return
    }
    if geometryChanged {
      restartSession()
      return
    }

    applyDetectionState()

    // 'auto' keeps whatever lens is bound, including one `switchCamera()` chose.
    guard pinnedFacing else { return }
    let target = resolveFacing()
    guard target != camera.targetFacing else { return }
    // A paused session parks the facing instead of failing a switch nobody asked for.
    if camera.isBound {
      setFacingInternal(target, onDone: nil)
    } else {
      camera.setPendingFacing(target)
    }
  }

  func applyOverlayEnabled() {
    overlayView.isHidden = !overlayEnabled
    guard overlayEnabled != overlayOn.value else { return }
    overlayOn.value = overlayEnabled
    if !overlayEnabled { overlayView.clearPose() }
  }

  func resolveFacing() -> Facing {
    return propFacing == "back" ? .back : .front
  }

  /// Adopted only when it differs: adopting clears frames still waiting to be flushed.
  func applyFrameLayout() {
    let indices = propLandmarks ? (propSelection ?? FrameShape.allJoints) : []
    let next = FrameShape(jointIndices: indices, worldLandmarks: propWorldLandmarks, angleJoints: propAngleJoints)

    if let current = frameLayout.value, current.sameAs(next) { return }

    frameLayout.value = next
    frames.setLayout(next)
  }

  /// Emits `onPerformanceChange` when the rate moved and `reason` is set; says whether it did.
  @discardableResult
  func applyPerformance(reason: String?) -> Bool {
    let next = RateGovernor.decide(RateRequest(
      profile: propProfile,
      policy: propThermalPolicy,
      thermal: thermal.state,
      lowPower: lowPower,
      cameraFps: cameraFps.value,
      p50Ms: calibrator.p50InferenceMs,
      requestedFps: propTargetFps
    ))
    idleRates.value = Budgets.of(propProfile).idle

    let changed = next != rate.value
    rate.value = next
    guard let reason = reason, changed else { return false }
    emitPerformanceChange(reason: reason)
    return true
  }

  func resolveGeometry() -> CameraGeometry {
    return GeometryResolver.resolve(
      profile: propProfile,
      requestedPreview: propPreview,
      requestedAnalysis: propAnalysis,
      memoryGiB: memoryGiB
    )
  }

  /// Does not rebind; the camera picks the sizes up at its next bind.
  func adopt(_ next: CameraGeometry) {
    geometry = next
    let preview = CameraSource.previewSize(for: next.preview)
    camera.previewSize = preview
    camera.analysisSize = CameraSource.analysisSize(for: next.analysis, preview: preview)
  }
}
