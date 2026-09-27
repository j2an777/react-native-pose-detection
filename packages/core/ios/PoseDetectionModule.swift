import AVFoundation
import ExpoModulesCore

public class PoseDetectionModule: Module {
  /// Hands logs to JavaScript while no camera owns the flush, e.g. during a file job or an export.
  private var logFlush: DispatchSourceTimer?

  // A list of declarations, not logic: splitting it would only scatter the exported surface.
  // swiftlint:disable:next function_body_length
  public func definition() -> ModuleDefinition {
    Name("PoseDetection")

    Function("setLogLevel") { (config: Either<String, [String: String]>?) in
      applyLogLevel(unwrap(config))
    }

    Function("startLogStream") { [weak self] in
      PoseLog.startStream()
      self?.startLogFlush()
    }
    Function("stopLogStream") { [weak self] in
      PoseLog.stopStream()
      self?.stopLogFlush()
    }
    OnDestroy { [weak self] in
      self?.stopLogFlush()
    }

    Events("onVideoProgress", "onExportProgress", "onLog")

    // File jobs run on our own queues, not the one Expo shares with every module. See ADR 0012.
    AsyncFunction("detectOnImage") { (uri: String, options: [String: Any]?, promise: Promise) in
      StaticDetection.queue.async {
        do {
          let buffer = try StaticDetection.detectImage(
            uri: uri,
            options: StaticOptions.forImage(options),
            angleJoints: angleJoints(from: options),
            selection: selection(from: options)
          )
          promise.resolve(NativeArrayBuffer.wrap(dataWithoutCopy: buffer))
        } catch {
          rejectFileJob(promise, error)
        }
      }
    }

    AsyncFunction("detectOnVideo") { (uri: String, options: [String: Any]?, taskId: Int, promise: Promise) in
      StaticDetection.queue.async { [weak self] in
        do {
          let buffer = try StaticDetection.detectVideo(
            uri: uri,
            options: StaticOptions.forVideo(options),
            angleJoints: angleJoints(from: options),
            selection: selection(from: options),
            taskId: taskId,
            onProgress: { progress in
              self?.sendEvent("onVideoProgress", ["taskId": taskId, "progress": progress])
            }
          )
          promise.resolve(NativeArrayBuffer.wrap(dataWithoutCopy: buffer))
        } catch {
          rejectFileJob(promise, error)
        }
      }
    }

    Function("cancelDetectOnVideo") { (taskId: Int) in
      StaticDetection.cancel(taskId: taskId)
    }

    AsyncFunction("exportPose") { (uri: String, options: [String: Any]?, taskId: Int, promise: Promise) in
      PoseExport.queue.async { [weak self] in
        do {
          let summary = try PoseExport.run(uri: uri, raw: options, taskId: taskId) { progress in
            self?.sendEvent("onExportProgress", ["taskId": taskId, "progress": progress])
          }
          promise.resolve(summary.payload)
        } catch is ExportCancelled {
          promise.reject(ErrorCode.exportCancelled.rawValue, "the export was cancelled")
        } catch {
          promise.reject(ErrorCode.exportFailed.rawValue, error.localizedDescription)
        }
      }
    }

    Function("cancelExportPose") { (taskId: Int) in
      PoseExport.cancel(taskId: taskId)
    }

    // On the JS thread: a view function would queue behind main. See ADR 0010.
    Function("drainFrames") { (streamId: Int) -> NativeArrayBuffer in
      NativeArrayBuffer.wrap(dataWithoutCopy: FrameStreams.shared.drain(streamId))
    }
    Function("snapshotFrame") { (streamId: Int) -> NativeArrayBuffer in
      NativeArrayBuffer.wrap(dataWithoutCopy: FrameStreams.shared.snapshot(streamId))
    }
    Function("takeTriggerSnapshot") { (streamId: Int, snapshotId: Int) -> NativeArrayBuffer in
      NativeArrayBuffer.wrap(dataWithoutCopy: FrameStreams.shared.takeSnapshot(streamId, ticket: snapshotId))
    }
    Function("readLiveState") { (streamId: Int) -> [String: Any] in
      FrameStreams.shared.live(streamId)
    }

    AsyncFunction("getCameraPermission") { () -> [String: Any] in
      return currentCameraPermission()
    }

    AsyncFunction("requestCameraPermission") { (promise: Promise) in
      let status = AVCaptureDevice.authorizationStatus(for: .video)
      guard status == .notDetermined else {
        promise.resolve(permissionResult(status))
        return
      }
      AVCaptureDevice.requestAccess(for: .video) { _ in
        promise.resolve(permissionResult(AVCaptureDevice.authorizationStatus(for: .video)))
      }
    }

    cameraView()
  }

  private func startLogFlush() {
    logFlush?.cancel()
    let timer = DispatchSource.makeTimerSource(queue: .main)
    timer.schedule(deadline: .now() + PoseLog.flushSeconds, repeating: PoseLog.flushSeconds)
    timer.setEventHandler { [weak self] in
      guard let entries = PoseLog.takeBatch(nil) else { return }
      self?.sendEvent("onLog", ["entries": entries])
    }
    timer.resume()
    logFlush = timer
  }

  private func stopLogFlush() {
    logFlush?.cancel()
    logFlush = nil
  }
}

extension PoseDetectionModule {
  // swiftlint:disable:next function_body_length
  fileprivate func cameraView() -> ViewDefinition<PoseCameraView> {
    return View(PoseCameraView.self) {
      Events(
        "onReady",
        "onError",
        "onCameraChange",
        "onFrames",
        "onTrigger",
        "onPerformanceChange",
        "onLog"
      )

      Prop("facing") { (view: PoseCameraView, value: String?) in view.setFacing(value ?? "auto") }
      Prop("delegate") { (view: PoseCameraView, value: String?) in view.setDelegate(value ?? "auto") }
      Prop("active") { (view: PoseCameraView, value: Bool?) in view.setActive(value ?? true) }

      Prop("detection") { (view: PoseCameraView, value: Bool?) in view.setDetection(value ?? true) }
      Prop("maxPoses") { (view: PoseCameraView, value: Int?) in view.setMaxPoses(value ?? 1) }
      Prop("minConfidence") { (view: PoseCameraView, value: Double?) in view.setMinConfidence(value) }
      Prop("resolution") { (view: PoseCameraView, value: String?) in view.setResolution(value ?? "auto") }
      Prop("analysisResolution") { (view: PoseCameraView, value: String?) in
        view.setAnalysisResolution(value ?? "auto")
      }
      Prop("data") { (view: PoseCameraView, value: [String: Any]?) in view.setData(parseData(value)) }

      // Already resolved by JavaScript, in ANGLE_JOINT_NAMES order; not re-derived here.
      Prop("angleJoints") { (view: PoseCameraView, value: [String]?) in view.setAngleJoints(value ?? []) }
      Prop("selection") { (view: PoseCameraView, value: [String]?) in
        view.setSelection(value.map(parseSelection))
      }
      Prop("profile") { (view: PoseCameraView, value: String?) in view.setProfile(Profile.from(value)) }
      Prop("targetFps") { (view: PoseCameraView, value: Int?) in view.setTargetFps(value) }
      Prop("streamId") { (view: PoseCameraView, value: Int?) in view.setStreamId(value) }
      Prop("thermalPolicy") { (view: PoseCameraView, value: String?) in
        view.setThermalPolicy(ThermalPolicy.from(value))
      }

      Prop("smoothing") { (view: PoseCameraView, value: Either<Bool, [String: Any]>?) in
        applySmoothing(view, unwrap(value))
      }

      Prop("logLevel") { (view: PoseCameraView, value: Either<String, [String: String]>?) in
        PoseLog.raise(view, to: PoseLog.levelMask(for: unwrap(value)))
      }

      Prop("triggers") { (view: PoseCameraView, value: [[String: Any]]?) in
        view.setTriggers(parseTriggers(value?.map { $0 as Any }))
      }

      Prop("overlay") { (view: PoseCameraView, value: Either<Bool, [String: Any]>?) in
        applyOverlay(view, unwrap(value))
      }

      OnViewDidUpdateProps { (view: PoseCameraView) in
        view.onPropsUpdated()
      }

      // ExpoModulesCore runs view functions on the main queue.
      AsyncFunction("switchCamera") { (view: PoseCameraView, promise: Promise) in
        view.switchCamera(
          onDone: { _ in promise.resolve(nil) },
          onFailed: { message in promise.reject("CAMERA_SWITCH_FAILED", message) }
        )
      }

      AsyncFunction("setFacing") { (view: PoseCameraView, facing: String, promise: Promise) in
        view.setFacingInternal(
          facing == "back" ? .back : .front,
          onDone: { _ in promise.resolve(nil) },
          onFailed: { message in promise.reject("CAMERA_SWITCH_FAILED", message) }
        )
      }

      AsyncFunction("pause") { (view: PoseCameraView) in view.pauseCamera() }
      AsyncFunction("resume") { (view: PoseCameraView) in view.resumeCamera() }
      AsyncFunction("startDetection") { (view: PoseCameraView) in view.startDetection() }
      AsyncFunction("stopDetection") { (view: PoseCameraView) in view.stopDetection() }
      AsyncFunction("setOverlayEnabled") { (view: PoseCameraView, enabled: Bool) in
        view.setOverlayEnabled(enabled)
      }
      AsyncFunction("getState") { (view: PoseCameraView) -> [String: Any] in view.currentState() }
      AsyncFunction("getProfile") { (view: PoseCameraView) -> [String: Any] in view.profileState() }
      AsyncFunction("setProfile") { (view: PoseCameraView, profile: String) in
        view.applyProfile(Profile.from(profile))
      }

    }
  }
}

/// `Either.value` is internal to ExpoModulesCore, so the typed getters are the way in.
private func unwrap(_ either: Either<String, [String: String]>?) -> Any? {
  guard let either else { return nil }
  if let name: String = either.get() { return name }
  if let map: [String: String] = either.get() { return map }
  return nil
}

private func unwrap(_ either: Either<Bool, [String: Any]>?) -> Any? {
  guard let either else { return nil }
  if let flag: Bool = either.get() { return flag }
  if let map: [String: Any] = either.get() { return map }
  return nil
}

private func rejectFileJob(_ promise: Promise, _ error: Error) {
  if let failure = error as? StaticDetectionError {
    promise.reject(failure.code.rawValue, failure.message)
    return
  }
  promise.reject(ErrorCode.detectionFailed.rawValue, error.localizedDescription)
}

func angleJoints(from options: [String: Any]?) -> [String] {
  guard let names = JS.strings(options?["angleJoints"]) else { return Skeleton.angleJointNames }
  return names
}

func selection(from options: [String: Any]?) -> [Int]? {
  guard let names = JS.strings(options?["select"]) else { return nil }
  return parseSelection(names)
}

func applyLogLevel(_ config: Any?) {
  if let name = JS.string(config) {
    PoseLog.setLevel(LogLevel.from(name))
    return
  }
  if let map = config as? [String: String] {
    PoseLog.setLevels(PoseLog.levels(from: map))
    return
  }
  PoseLog.setLevel(.off)
}

func applySmoothing(_ view: PoseCameraView, _ value: Any?) {
  // Absent is off: JavaScript always sends `'auto'` already resolved against `maxPoses`.
  guard !JS.isNull(value) else {
    view.setSmoothing(enabled: false, minCutoff: OneEuroFilter.defaultMinCutoff, beta: OneEuroFilter.defaultBeta)
    return
  }
  if let enabled = JS.bool(value) {
    view.setSmoothing(
      enabled: enabled,
      minCutoff: OneEuroFilter.defaultMinCutoff,
      beta: OneEuroFilter.defaultBeta
    )
    return
  }
  guard let map = JS.dictionary(value) else {
    view.setSmoothing(enabled: false, minCutoff: OneEuroFilter.defaultMinCutoff, beta: OneEuroFilter.defaultBeta)
    return
  }
  view.setSmoothing(
    enabled: true,
    minCutoff: JS.number(map["minCutoff"]).map(Float.init) ?? OneEuroFilter.defaultMinCutoff,
    beta: JS.number(map["beta"]).map(Float.init) ?? OneEuroFilter.defaultBeta
  )
}

func applyOverlay(_ view: PoseCameraView, _ value: Any?) {
  guard !JS.isNull(value) else {
    view.setOverlay(enabled: true, config: OverlayConfig())
    return
  }
  if let enabled = JS.bool(value) {
    view.setOverlay(enabled: enabled, config: OverlayConfig())
    return
  }
  guard let map = JS.dictionary(value) else {
    view.setOverlay(enabled: true, config: OverlayConfig())
    return
  }
  view.setOverlay(enabled: true, config: parseOverlay(map))
}
