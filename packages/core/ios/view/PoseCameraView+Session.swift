import AVFoundation
import ExpoModulesCore
import MediaPipeTasksVision
import UIKit

extension PoseCameraView {
  func startSession() {
    guard !started else { return }

    guard AVCaptureDevice.authorizationStatus(for: .video) == .authorized else {
      emitError(.permissionDenied, "Camera permission has not been granted.")
      return
    }

    guard let model = modelPath ?? PoseDetector.findModelPath() else {
      emitError(
        .modelNotFound,
        "No pose_landmarker_*.task in the app bundle. Run `npx expo prebuild`, "
          + "or `npx react-native-pose-detection fetch-model full` for bare React Native."
      )
      return
    }
    modelPath = model

    // A no-op for a model already measured, so a restart keeps its calibration.
    calibrator.start(modelFileName: PoseDetector.fileName(from: model))
    adopt(resolveGeometry())
    applyPerformance(reason: nil)

    started = true
    camera.setAnalyzerEnabled(true)
    camera.start(
      facing: resolveFacing(),
      onBound: { [weak self] in
        guard let self = self else { return }
        self.syncOverlayMirroring()
        self.applyDetectionState()
        self.emitReadyOnce()
      },
      onFailed: { [weak self] code, error in
        self?.emitError(code, error)
      }
    )
  }

  func stopSession() {
    guard started else { return }
    camera.setAnalyzerEnabled(false)
    camera.pause()
    parkDetector(for: PoseCameraView.parkedReleaseSeconds)
    overlayView.clearPose()
    completeSwitch()
    started = false
    readySent = false
  }

  func restartSession() {
    stopSession()
    startSession()
  }

  /// For a profile set from the ref, the one geometry change that does not arrive as a prop.
  func restartSessionIfGeometryChanged() {
    let next = resolveGeometry()
    guard next != geometry else { return }
    adopt(next)
    if started { restartSession() }
  }

  func applyDetectionState() {
    guard propDetection else {
      parkDetector(for: PoseCameraView.parkedReleaseSeconds)
      overlayView.clearPose()
      // A running camera with detection off still came up, and nothing else will emit ready.
      emitReadyOnce()
      return
    }

    // The delegate, maxPoses and minConfidence are fixed at construction, so a change rebuilds.
    let request = delegateRequest()
    let live = detector.value != nil || detectorPending
    let changed = request != detectorRequest
      || propMaxPoses != detectorMaxPoses
      || resolvedMinConfidence() != detectorMinConfidence
    if live && changed {
      PoseLog.info(.detector, "delegate, maxPoses or minConfidence changed, rebuilding")
      releaseDetector()
    }
    ensureDetector()
    resumeFeeding()
  }

  func parkDetector(for seconds: TimeInterval) {
    feeding.value = false
    // Frames stopping ends a hold, or a `minDurationMs` hold would count the paused time.
    triggers.onPoseLost()
    releaseTimer?.invalidate()
    releaseTimer = nil
    guard detector.value != nil || detectorPending else { return }
    releaseTimer = Timer.scheduledTimer(withTimeInterval: seconds, repeats: false) { [weak self] _ in
      PoseLog.info(.detector, "the landmarker went unused, releasing it")
      self?.releaseDetector()
    }
  }

  func resumeFeeding() {
    releaseTimer?.invalidate()
    releaseTimer = nil
    feeding.value = true
  }

  func delegateRequest() -> DelegateRequest {
    switch propDelegate {
    case "gpu": return .gpu
    case "cpu": return .cpu
    default: return .auto
    }
  }

  /// Builds on the analysis queue: the heavy model takes seconds and `auto` runs a probe
  /// inference first, which on main is a watchdog kill.
  func ensureDetector() {
    guard detector.value == nil, !detectorPending, let model = modelPath else { return }

    let request = delegateRequest()
    let maxPoses = propMaxPoses
    let minConfidence = resolvedMinConfidence()
    let knownGpu = calibrator.gpuVerdict
    let generation = detectorGeneration
    detectorPending = true
    detectorRequest = request
    detectorMaxPoses = maxPoses
    detectorMinConfidence = minConfidence

    analysisQueue.async { [weak self] in
      guard let self = self else { return }
      do {
        let created = try PoseDetector.create(
          modelPath: model,
          request: request,
          maxPoses: maxPoses,
          minConfidence: minConfidence,
          knownGpu: knownGpu
        )
        self.preWarm(created)
        DispatchQueue.main.async { self.adoptDetector(created, request: request, generation: generation) }
      } catch {
        PoseLog.error(.detector, "landmarker init failed: \(error.localizedDescription)")
        DispatchQueue.main.async { self.failDetector(error, generation: generation) }
      }
    }
  }

  private func adoptDetector(_ created: PoseDetector, request: DelegateRequest, generation: Int) {
    guard generation == detectorGeneration else {
      created.shutdown()
      return
    }
    detectorPending = false
    created.observer = self
    detector.value = created
    resolvedDelegate = created.delegateKind == .GPU ? "GPU" : "CPU"
    // The probe runs once per device and model.
    if let probed = created.probedGpu {
      calibrator.recordGpuVerdict(probed)
    }
    // Idle search counts from here: a camera opened on an empty room is idle too.
    lastPoseMs.value = Monotonic.nowMs()
    if fellBackToCpu {
      fellBackToCpu = false
      emitPerformanceChange(reason: "gpu_fallback")
    }

    // Only 'auto' can land on the CPU. Skipped in a simulator, where the CPU is deliberate:
    // an error there would teach people to ignore this channel. `PoseDetector` logs it instead.
    #if !targetEnvironment(simulator)
    if request != .cpu && created.delegateKind == .CPU {
      emitError(.gpuUnavailable, "The GPU delegate is unavailable, running on CPU.")
    }
    #endif
    emitReadyOnce()
  }

  /// The first inference through a new graph is several times slower, so a blank frame pays it.
  /// Run before adoption, so no camera frame reaches the landmarker first and has its track ended.
  private func preWarm(_ created: PoseDetector) {
    autoreleasepool {
      do {
        let size = CGSize(width: PoseCameraView.preWarmSize, height: PoseCameraView.preWarmSize)
        let blank = UIGraphicsImageRenderer(size: size).image { context in
          UIColor.black.setFill()
          context.fill(CGRect(origin: .zero, size: size))
        }
        try created.detect(image: try MPImage(uiImage: blank), cameraTimestampMs: 0)
      } catch {
        PoseLog.debug(.detector, "pre-warm did not run: \(error.localizedDescription)")
      }
    }
  }

  private func failDetector(_ error: Error, generation: Int) {
    guard generation == detectorGeneration else { return }
    detectorPending = false
    resolvedDelegate = nil
    emitError(.detectorInitFailed, error.localizedDescription)
    emitReadyOnce()
  }

  func releaseDetector() {
    releaseTimer?.invalidate()
    releaseTimer = nil
    detectorGeneration += 1
    detectorPending = false
    detectorRequest = nil
    guard let doomed = detector.value else { return }
    doomed.shutdown()
    detector.value = nil
    // Released on the queue that hands it frames, so the deallocation cannot overlap a detect call.
    analysisQueue.async { _ = doomed }
  }

  /// Only for `auto`: an explicit 'gpu' keeps reporting its failures rather than being overruled.
  func fallBackToCpu() {
    guard delegateRequest() == .auto, detector.value?.delegateKind == .GPU else { return }
    PoseLog.warn(.detector, "the GPU delegate keeps failing on this device, rebuilding on the CPU")
    calibrator.recordGpuVerdict(false)
    fellBackToCpu = true
    releaseDetector()
    ensureDetector()
    emitError(.gpuUnavailable, "The GPU delegate failed on this device, running on CPU.")
  }

  func syncOverlayMirroring() {
    overlayView.setMirrored(camera.facing == .front)
  }

  func onCalibrationMoved() {
    applyPerformance(reason: "calibration")
    calibrator.persist()
  }

  /// Reported even when the policy says not to act on it, so an app can decide for itself.
  func sampleHeat() {
    let heatMoved = thermal.update(thermalMonitor.readThermal(), nowMs: Monotonic.nowMs())
    let power = thermalMonitor.readLowPower()
    let powerMoved = power != lowPower
    lowPower = power
    guard heatMoved || powerMoved else { return }

    PoseLog.info(.engine, "heat is \(thermal.state.rawValue), low power \(lowPower ? "on" : "off")")
    applyPerformance(reason: heatMoved ? "thermal" : "lowPower")
  }
}

// MARK: - Inference failures

extension PoseCameraView {
  /// Callback queue. Rate limited: a dead delegate fails every frame.
  func poseDetector(_ detector: PoseDetector, didFail error: Error) {
    PoseLog.warn(.detector, "inference failed: \(error.localizedDescription)")
    let now = Monotonic.nowMs()
    if detector.delegateKind == .GPU && noteGpuFailure(now) {
      DispatchQueue.main.async { [weak self] in self?.fallBackToCpu() }
    }
    let shouldReport = lastDetectionErrorMs.mutate { last -> Bool in
      guard now - last >= PoseCameraView.detectionErrorIntervalMs else { return false }
      last = now
      return true
    }
    guard shouldReport else { return }

    let message = error.localizedDescription
    DispatchQueue.main.async { [weak self] in self?.emitError(.detectionFailed, message) }
  }

  private func noteGpuFailure(_ now: Int64) -> Bool {
    gpuFailureTimes.removeAll { now - $0 > PoseCameraView.gpuFailureWindowMs }
    gpuFailureTimes.append(now)
    guard gpuFailureTimes.count >= PoseCameraView.gpuFailureLimit else { return false }
    gpuFailureTimes.removeAll()
    return true
  }
}
