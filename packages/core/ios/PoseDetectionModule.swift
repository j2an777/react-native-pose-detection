import AVFoundation
import ExpoModulesCore

public class PoseDetectionModule: Module {
  // The two functions below are declarations rather than logic: every line names one prop, one
  // function or one event, and splitting them further would only scatter the surface this module
  // exports across several places to satisfy a line count.
  // swiftlint:disable:next function_body_length
  public func definition() -> ModuleDefinition {
    Name("PoseDetection")

    Function("setLogLevel") { (config: Either<String, [String: String]>?) in
      applyLogLevel(unwrap(config))
    }

    // The buffer is global because the level mask is. A view runs the flush, see PoseLog.
    Function("startLogStream") { PoseLog.startStream() }
    Function("stopLogStream") { PoseLog.stopStream() }

    Events("onVideoProgress", "onExportProgress")

    // Both handed to this package's own queue rather than run on Expo's, which every module in the
    // app shares: a video job there held all of them up while it ran, at the camera's priority.
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

    /**
     Dispatched onto the export queue rather than run on Expo's, which is what keeps a long export
     off any thread the camera cares about. See `PoseExport` for the other three rules.
     */
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

    // Synchronous and on the JavaScript thread that calls them: a view function would run on main,
    // behind layout and the overlay, twice per tick. Each reads a view's frames through the id
    // `<PoseCamera>` gave it, and an id with no view behind it reads as an empty buffer. See ADR 0008.
    Function("drainFrames") { (streamId: Int) -> NativeArrayBuffer in
      NativeArrayBuffer.wrap(dataWithoutCopy: FrameStreams.shared.drain(streamId))
    }
    Function("snapshotFrame") { (streamId: Int) -> NativeArrayBuffer in
      NativeArrayBuffer.wrap(dataWithoutCopy: FrameStreams.shared.snapshot(streamId))
    }
    /// An unknown or spent ticket is an empty buffer, which is the documented contract.
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
      // Only `notDetermined` can produce a dialog. Asking again in any other state resolves
      // immediately with what is already true, which is what the JavaScript side documents.
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
}

extension PoseDetectionModule {
  // Extracted from `definition()` so each half stays readable; the DSL composes either way, and
  // like `definition()` this is a list of declarations rather than a long function.
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

      // What runs and at what size. `detection` parks and resumes the landmarker, `maxPoses` and
      // `minConfidence` are built into it and rebuild it, and `resolution` rebinds the camera.
      Prop("detection") { (view: PoseCameraView, value: Bool?) in view.setDetection(value ?? true) }
      Prop("maxPoses") { (view: PoseCameraView, value: Int?) in view.setMaxPoses(value ?? 1) }
      Prop("minConfidence") { (view: PoseCameraView, value: Double?) in view.setMinConfidence(value) }
      Prop("resolution") { (view: PoseCameraView, value: String?) in view.setResolution(value ?? "auto") }
      Prop("analysisResolution") { (view: PoseCameraView, value: String?) in
        view.setAnalysisResolution(value ?? "auto")
      }
      Prop("data") { (view: PoseCameraView, value: [String: Any]?) in view.setData(parseData(value)) }

      // Resolved by JavaScript, in ANGLE_JOINT_NAMES order. Re-deriving the set here would be a
      // second implementation of one rule, and a way for them to disagree.
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

      // Raises the global level while this camera exists, see PoseLog.raise. Absent withdraws it.
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

      // Every one of these runs on the main queue: ExpoModulesCore puts view functions there. None
      // is on the frame path; the frame reads are module functions above, on the JavaScript thread.
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

/// `Either.value` is internal to ExpoModulesCore, so the typed getters are the way in from out
/// here. Asked in declaration order because an `NSNumber` bridges to `Bool` and a dictionary never
/// does: reversing it would read `smoothing: true` as a config object.
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

/**
 A file that could not be read rejects with its decode code, a missing model with `MODEL_NOT_FOUND`,
 and anything that failed after the file was read with `DETECTION_FAILED`: three different things
 for the app to tell its user, where one code used to cover all of them.
 */
private func rejectFileJob(_ promise: Promise, _ error: Error) {
  if let failure = error as? StaticDetectionError {
    promise.reject(failure.code.rawValue, failure.message)
    return
  }
  promise.reject(ErrorCode.detectionFailed.rawValue, error.localizedDescription)
}

/// Resolved by JavaScript for the live path, and passed the same way here.
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
  // Absent is off. JavaScript resolves `'auto'` against `maxPoses` and always sends the answer, and
  // one pose is already smoothed inside MediaPipe.
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
