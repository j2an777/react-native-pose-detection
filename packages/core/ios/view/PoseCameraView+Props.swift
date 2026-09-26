import ExpoModulesCore
import UIKit

/// The props, and what one batch of them does to the session.
extension PoseCameraView {
  func setFacing(_ value: String) { propFacing = value }
  func setDelegate(_ value: String) { propDelegate = value }
  func setActive(_ value: Bool) { propActive = value }

  func setDetection(_ value: Bool) { propDetection = value }
  func setMaxPoses(_ value: Int) { propMaxPoses = min(max(value, 1), 5) }

  /// Baked into the landmarker at construction, so a change rebuilds it. See `applyDetectionState`.
  func setMinConfidence(_ value: Double?) {
    propMinConfidence = value.map { Float(min(max($0, 0.1), 1)) }
  }

  /// The prop, or the value `maxPoses` implies when nobody has chosen one.
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

  /// Already resolved and ordered by JavaScript. Reproducing that rule here would be a way to disagree.
  func setAngleJoints(_ joints: [String]) { propAngleJoints = joints }
  func setSelection(_ indices: [Int]?) { propSelection = indices }
  func setProfile(_ value: Profile) { propProfile = value }

  /// Nil is `auto`, which is the only value calibration is allowed to move.
  func setTargetFps(_ value: Int?) {
    propTargetFps = value.map { min(max($0, PoseCameraView.minTargetFps), PoseCameraView.maxTargetFps) }
  }

  func setThermalPolicy(_ value: ThermalPolicy) { propThermalPolicy = value }

  /// The id JavaScript reads this view's frames by, on its own thread. See `FrameStreams`.
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
    // Not deferred to `onPropsUpdated`: the engine carries counts across by id, so applying it
    // twice would be harmless but applying it late would evaluate one frame against the old set.
    triggers.setTriggers(specs)
  }

  /// Runs once per prop batch. Only a resolution change takes the rebind path.
  func onPropsUpdated() {
    overlayView.config = pendingOverlayConfig
    applyOverlayEnabled()

    applyFrameLayout()
    smoothing.configure(minCutoff: propMinCutoff, beta: propBeta)
    applyPerformance(reason: nil)

    // Only the props move geometry. What calibration or heat learns never does, which is what keeps
    // an unrelated prop change from restarting the camera behind somebody's back.
    let next = resolveGeometry()
    let geometryChanged = next != geometry
    adopt(next)
    // Only 'auto' is documented to fall back to the other lens; a pinned one stays pinned.
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

    // 'auto' takes whatever the device could bind, the fallback lens included, and it is also what
    // `switchCamera()` leaves behind, so only a pinned facing is reconciled here.
    guard pinnedFacing else { return }
    let target = resolveFacing()
    guard target != camera.targetFacing else { return }
    // Reconciling a prop is not the interactive switch, and a paused session has nothing to switch,
    // so the value is parked for the next bind instead of failing a switch nobody asked for.
    if camera.isBound {
      setFacingInternal(target, onDone: nil)
    } else {
      camera.setPendingFacing(target)
    }
  }

  /// Hidden is also idle: no result is copied over or rendered while nobody can see it.
  func applyOverlayEnabled() {
    overlayView.isHidden = !overlayEnabled
    guard overlayEnabled != overlayOn.value else { return }
    overlayOn.value = overlayEnabled
    if !overlayEnabled { overlayView.clearPose() }
  }

  func resolveFacing() -> Facing {
    return propFacing == "back" ? .back : .front
  }

  /**
   The layout is rebuilt on every props batch but only adopted when it differs: a re-render that
   changes nothing about `data` would otherwise clear frames that were waiting to be flushed.
   */
  func applyFrameLayout() {
    let indices = propLandmarks ? (propSelection ?? FrameShape.allJoints) : []
    let next = FrameShape(jointIndices: indices, worldLandmarks: propWorldLandmarks, angleJoints: propAngleJoints)

    if let current = frameLayout.value, current.sameAs(next) { return }

    frameLayout.value = next
    frames.setLayout(next)
  }

  /**
   Runs the governor and adopts its decision. `reason` is what `onPerformanceChange` reports; nil
   means this is a props update rather than something the engine decided, and fires no event.
   */
  func applyPerformance(reason: String?) {
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
    guard let reason = reason, changed else { return }
    emitPerformanceChange(reason: reason)
  }

  /// The presets the props and profile ask for, on this device. Main thread only.
  func resolveGeometry() -> CameraGeometry {
    return GeometryResolver.resolve(
      profile: propProfile,
      requestedPreview: propPreview,
      requestedAnalysis: propAnalysis,
      memoryGiB: memoryGiB
    )
  }

  /// Records the presets and sizes the camera binds at next. Does not rebind on its own.
  func adopt(_ next: CameraGeometry) {
    geometry = next
    let preview = CameraSource.previewSize(for: next.preview)
    camera.previewSize = preview
    camera.analysisSize = CameraSource.analysisSize(for: next.analysis, preview: preview)
  }
}
