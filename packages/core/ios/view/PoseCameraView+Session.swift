import AVFoundation
import ExpoModulesCore
import MediaPipeTasksVision
import UIKit

/// Bringing the camera and the detector up and down, and keeping the two in step.
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

    // A no-op for the model already measured, so a restart keeps what the device was measured to
    // cost instead of going back to the camera's rate and measuring it all over again.
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
    // Parked rather than released: a camera switched back on soon, or a restart for new geometry,
    // finds the landmarker still built. It is released if it stays unused.
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

  /**
   `detection = false` stops frames reaching the landmarker at once and returns its memory after a
   minute unused. Turning detection back on inside that minute is instant rather than a rebuild.
   */
  func applyDetectionState() {
    guard propDetection else {
      parkDetector(for: PoseCameraView.parkedReleaseSeconds)
      overlayView.clearPose()
      // Nothing else will emit ready once the pending build is discarded, and a camera that is
      // running with detection off is still a camera that came up.
      emitReadyOnce()
      return
    }

    // maxPoses and the delegate are baked into the landmarker at construction, so a change to
    // either has to rebuild it rather than wait for the next unrelated restart to notice.
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

  /// Stops frames reaching the landmarker now, and frees it after `seconds` if nothing wanted it back.
  func parkDetector(for seconds: TimeInterval) {
    feeding.value = false
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

  /**
   Runs on the analysis queue. The heavy model takes seconds to build and `auto` runs a probe
   inference first, which on main is a watchdog kill on every foreground.
   */
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
        DispatchQueue.main.async { self.adoptDetector(created, request: request, generation: generation) }
      } catch {
        PoseLog.error(.detector, "landmarker init failed: \(error.localizedDescription)")
        DispatchQueue.main.async { self.failDetector(error, generation: generation) }
      }
    }
  }

  private func adoptDetector(_ created: PoseDetector, request: DelegateRequest, generation: Int) {
    guard generation == detectorGeneration else {
      // A teardown landed while this was still building, so it is dropped instead of installed.
      created.shutdown()
      return
    }
    detectorPending = false
    created.observer = self
    detector.value = created
    resolvedDelegate = created.delegateKind == .GPU ? "GPU" : "CPU"
    // The probe runs once per device and model; every later build takes this answer instead.
    if let probed = created.probedGpu {
      calibrator.recordGpuVerdict(probed)
    }
    // Idle search counts from here: a camera opened on an empty room is idle too.
    lastPoseMs.value = Monotonic.nowMs()
    preWarm(created)
    if fellBackToCpu {
      fellBackToCpu = false
      emitPerformanceChange(reason: "gpu_fallback")
    }

    // The one path that actually downgrades is 'auto'. An explicit 'gpu' is pinned and never falls
    // back, so comparing the resolved delegate against the request is the whole test.
    //
    // Not in a simulator. There the CPU is a deliberate choice rather than a device falling short,
    // and reporting it as a problem would train people to ignore the one channel that tells them
    // their real phone has a problem. It is on the log instead, from `PoseDetector`.
    #if !targetEnvironment(simulator)
    if request != .cpu && created.delegateKind == .CPU {
      emitError(.gpuUnavailable, "The GPU delegate is unavailable, running on CPU.")
    }
    #endif
    emitReadyOnce()
  }

  /**
   One inference on a blank frame, on the analysis queue, before the user's first real one. The
   first inference through a freshly built graph is several times slower than the rest, and without
   this the frame that pays for that is the one somebody is watching.
   */
  private func preWarm(_ created: PoseDetector) {
    analysisQueue.async {
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
  }

  private func failDetector(_ error: Error, generation: Int) {
    guard generation == detectorGeneration else { return }
    detectorPending = false
    // The build that would have set it failed, so keeping the previous value would report a
    // delegate that nothing is running on.
    resolvedDelegate = nil
    emitError(.detectorInitFailed, error.localizedDescription)
    emitReadyOnce()
  }

  /**
   The analysis queue may be inside `detectAsync` right now, so the observer is cleared on main,
   stopping the next result, and the reference is dropped behind the frame already running.
   */
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

  /**
   A GPU delegate that keeps failing on this device is rebuilt on the CPU, and the cached probe
   answer flips so the next launch does not try the GPU again. Only `auto`: an explicit `'gpu'` is a
   decision, and it keeps reporting its failures instead of being overruled.
   */
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

  /**
   Heat and power, read on a one-second timer and on the OS's own notifications, never on the frame
   path. Heat is adopted at once and cooling only after it has held, see `ThermalHysteresis`.
   Reported even when the policy says not to act on it, so an app can decide for itself.
   */
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
