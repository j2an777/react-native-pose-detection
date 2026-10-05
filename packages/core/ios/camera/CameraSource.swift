import AVFoundation
import UIKit

enum Facing {
  case front
  case back

  var nameForJs: String {
    return self == .front ? "front" : "back"
  }

  var position: AVCaptureDevice.Position {
    return self == .front ? .front : .back
  }

  var opposite: Facing {
    return self == .front ? .back : .front
  }
}

/// Owns the capture session, on its own queue because `startRunning` blocks until the camera is up.
final class CameraSource {
  let sessionQueue = DispatchQueue(label: "com.posedetection.session")
  let analysisQueue: DispatchQueue
  weak var previewView: PreviewView?

  /// Weak: the view owns this camera. Never cleared: a reattached view restarts this same camera.
  weak var sampleDelegate: AVCaptureVideoDataOutputSampleBufferDelegate?

  // Session queue only.
  var session: AVCaptureSession?
  var input: AVCaptureDeviceInput?
  var output: AVCaptureVideoDataOutput?
  /// Nil when the device would not take a second output alongside the analysis one.
  var photoOutput: AVCapturePhotoOutput?
  var boundFacing: Facing = .front

  /// Main-thread mirror of the session state, so the view can report it without a queue hop.
  private(set) var facing: Facing = .front
  private(set) var isBound = false

  /// Where queued switches are heading, so two quick switches go there and back.
  private(set) var targetFacing: Facing = .front
  private var switchesInFlight = 0

  var previewSize = CaptureSize(width: 1280, height: 720)
  var analysisSize = CaptureSize(width: 640, height: 480)

  /// Only `auto` may fall back to the other lens; a pinned lens fails instead.
  var facingFallbackAllowed = false

  /// Main-thread mirror of the bound device's capability. False on every front camera.
  /// Not `isTorchAvailable`: that drops out while the device is hot, and a control that
  /// disappears mid-shoot reads as a bug. See `CameraSource+Torch`.
  var hasTorch = false

  /// What was asked for, which is not what is lit: a rebind opens the device with the torch off,
  /// and a lens without a flash never lights. Kept so the light returns with the back camera.
  var torchRequested = false

  /// Lit right now, as far as this side knows.
  var torchOn: Bool { torchRequested && hasTorch }

  /// Not 60: an iPhone 15 at 60 ran warm within minutes for a skeleton that looked identical.
  static let pinnedFps = 30

  /// Called on main with the rate the pinned camera actually delivers.
  var onFrameRate: ((Int) -> Void)?

  /// Bumped by every start, pause, resume and release so late work can tell it was superseded.
  /// Locked, not main-only: the session queue checks it around the blocking `startRunning`.
  let token = Guarded(0)

  /// Kept so `resume()` can re-run it.
  private var onBound: (() -> Void)?

  /// Written on main, read on the session queue.
  var orientation: AVCaptureVideoOrientation = .portrait

  private var analyzerEnabled = false

  init(previewView: PreviewView, analysisQueue: DispatchQueue, delegate: AVCaptureVideoDataOutputSampleBufferDelegate) {
    self.previewView = previewView
    self.analysisQueue = analysisQueue
    self.sampleDelegate = delegate
  }

  // MARK: - Lifecycle

  func start(facing: Facing, onBound: @escaping () -> Void, onFailed: @escaping (ErrorCode, Error?) -> Void) {
    let current = bump()
    self.facing = facing
    targetFacing = facing
    self.onBound = onBound
    orientation = currentOrientation()
    let request = SessionRequest(
      target: facing,
      previewSize: previewSize,
      analysisSize: analysisSize,
      fallbackAllowed: facingFallbackAllowed,
      analyzerEnabled: analyzerEnabled,
      token: current
    )

    sessionQueue.async { [weak self] in
      guard let self = self else { return }
      let resolved: Facing
      do {
        resolved = try self.configure(request)
      } catch {
        PoseLog.error(.camera, "camera start failed: \(error.localizedDescription)")
        let code: ErrorCode = error is CameraMissing ? .cameraUnavailable : .cameraStartFailed
        DispatchQueue.main.async {
          guard self.isCurrent(current) else { return }
          onFailed(code, error)
        }
        return
      }
      let session = self.session
      DispatchQueue.main.async {
        guard self.isCurrent(current) else { return }
        self.facing = resolved
        self.targetFacing = resolved
        self.attachPreview(session)
        self.isBound = true
        self.applyTorch()
        onBound()
      }
    }
  }

  /// Rolls back on failure, and settles exactly once even if a pause or release overtakes it.
  func switchTo(_ target: Facing, onDone: @escaping (Facing) -> Void, onFailed: @escaping (ErrorCode, Error?) -> Void) {
    guard isBound else {
      onFailed(.cameraSwitchFailed, CameraError("camera is not running"))
      return
    }
    // No `auto` fallback here: a missing lens fails the switch (guides/camera-control.md).
    guard device(for: target) != nil else {
      onFailed(.cameraSwitchFailed, CameraError("this device has no \(target.nameForJs) camera"))
      return
    }

    let current = token.value
    targetFacing = target
    switchesInFlight += 1

    let landedOrSuperseded: (Bool) -> Void = { superseded in
      if superseded {
        onFailed(.cameraSwitchFailed, CameraError("the camera was paused or released before the switch finished"))
      } else {
        onDone(target)
      }
    }

    sessionQueue.async { [weak self] in
      guard let self = self else { return }
      guard self.isCurrent(current), self.session != nil else {
        self.finishSwitch(current, landed: nil) { _ in
          onFailed(.cameraSwitchFailed, CameraError("the camera stopped before the switch ran"))
        }
        return
      }
      let previous = self.boundFacing
      if previous == target {
        // An earlier queued switch already got here.
        self.finishSwitch(current, landed: target, then: landedOrSuperseded)
        return
      }
      do {
        try self.swapInput(to: target)
        PoseLog.debug(.camera, "switched \(previous.nameForJs) to \(target.nameForJs)")
        self.finishSwitch(current, landed: target, then: landedOrSuperseded)
      } catch {
        PoseLog.warn(.camera, "switch to \(target.nameForJs) failed, rolling back: \(error.localizedDescription)")
        do {
          try self.swapInput(to: previous)
          self.finishSwitch(current, landed: previous) { _ in
            onFailed(.cameraSwitchFailed, error)
          }
        } catch let rollbackError {
          self.finishSwitch(current, landed: nil) { _ in
            self.isBound = false
            onFailed(.cameraUnavailable, rollbackError)
          }
        }
      }
    }
  }

  func updateTargetRotation() {
    let next = currentOrientation()
    orientation = next
    applyPreviewOrientation()
    sessionQueue.async { [weak self] in
      self?.applyOrientation(next)
    }
    PoseLog.debug(.camera, "target rotation now \(next.rawValue)")
  }

  func setPendingFacing(_ target: Facing) {
    guard !isBound else { return }
    facing = target
    targetFacing = target
  }

  func setAnalyzerEnabled(_ enabled: Bool) {
    analyzerEnabled = enabled
    let queue = analysisQueue
    sessionQueue.async { [weak self] in
      guard let self = self else { return }
      self.output?.setSampleBufferDelegate(enabled ? self.sampleDelegate : nil, queue: enabled ? queue : nil)
    }
  }

  /// Queued rather than guarded on `isBound`, so a pause during startup still stops that session.
  func pause() {
    bump()
    isBound = false
    sessionQueue.async { [weak self] in
      guard let session = self?.session, session.isRunning else { return }
      session.stopRunning()
      PoseLog.info(.camera, "session stopped")
    }
  }

  /// Also re-runs the preview attach and `onBound`, which a session paused mid-startup never had.
  func resume(onFailed: @escaping (ErrorCode, Error?) -> Void) {
    guard !isBound else { return }
    let current = bump()
    let target = targetFacing

    sessionQueue.async { [weak self] in
      guard let self = self, self.isCurrent(current) else { return }
      guard let session = self.session else {
        DispatchQueue.main.async {
          guard self.isCurrent(current) else { return }
          self.start(facing: target, onBound: self.onBound ?? {}, onFailed: onFailed)
        }
        return
      }

      // A facing parked while paused.
      if target != self.boundFacing {
        do {
          try self.swapInput(to: target)
        } catch {
          PoseLog.warn(.camera, "could not resume on \(target.nameForJs): \(error.localizedDescription)")
        }
      }
      if !session.isRunning { session.startRunning() }
      guard self.isCurrent(current) else {
        session.stopRunning()
        return
      }

      let bound = self.boundFacing
      DispatchQueue.main.async {
        guard self.isCurrent(current) else { return }
        self.facing = bound
        self.targetFacing = bound
        self.attachPreview(session)
        self.isBound = true
        self.applyTorch()
        self.onBound?()
      }
    }
  }

  func release() {
    bump()
    isBound = false
    hasTorch = false
    onBound = nil
    previewView?.previewLayer?.session = nil
    // Strong capture: the session must stop even if the view is gone by then.
    sessionQueue.async { [self] in
      output?.setSampleBufferDelegate(nil, queue: nil)
      session?.stopRunning()
      session = nil
      input = nil
      output = nil
      photoOutput = nil
    }
  }

  // MARK: - Tokens

  @discardableResult
  private func bump() -> Int {
    return token.mutate { value -> Int in
      value += 1
      return value
    }
  }

  func isCurrent(_ candidate: Int) -> Bool {
    return token.value == candidate
  }

  // MARK: - Switch bookkeeping, main thread

  /// Settles one switch on main. `landed` is nil when the switch never reached the session.
  private func finishSwitch(_ current: Int, landed: Facing?, then settle: @escaping (_ superseded: Bool) -> Void) {
    DispatchQueue.main.async {
      self.switchesInFlight -= 1
      let superseded = !self.isCurrent(current)
      if !superseded, let landed = landed {
        self.facing = landed
        // The new device opened with its torch off, and may not have one at all.
        self.applyTorch()
        // The preview keeps its connection across an input swap, still mirrored for the old lens.
        self.applyPreviewOrientation()
      }
      self.syncTargetWhenIdle()
      settle(superseded)
    }
  }

  private func syncTargetWhenIdle() {
    if switchesInFlight <= 0 {
      switchesInFlight = 0
      targetFacing = facing
    }
  }
}
