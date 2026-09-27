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

/**
 Owns the capture session. Knows about frames, not poses.

 **The session, its input and its output belong to `sessionQueue`**, a serial queue of its own,
 unlike Android where CameraX forces the session onto the main thread. `startRunning` blocks until
 the camera is up, so running it on main would stall the first frame of every mount behind it.
 Everything else here is main-thread state, and callbacks hop to main because the view's state
 lives there.

 Sample buffers are delivered on `analysisQueue` and inference runs there too, so a buffer never
 escapes the callback it arrived in.
 */
final class CameraSource {
  let sessionQueue = DispatchQueue(label: "com.posedetection.session")
  let analysisQueue: DispatchQueue
  weak var previewView: PreviewView?

  /**
   Weak because the view owns this camera, and never cleared, because a detached view that comes
   back starts this same camera again. Clearing it on release once left a reattached view with a
   running preview and no frames reaching the detector, which is the counterpart of Android
   setting its analyzer again on every bind.
   */
  weak var sampleDelegate: AVCaptureVideoDataOutputSampleBufferDelegate?

  // Session queue only.
  var session: AVCaptureSession?
  var input: AVCaptureDeviceInput?
  var output: AVCaptureVideoDataOutput?
  /// The lens actually attached to `session`, which is what a rollback returns to.
  var boundFacing: Facing = .front

  /// Main-thread mirror of the session state, so the view can report it without a queue hop.
  private(set) var facing: Facing = .front
  private(set) var isBound = false

  /**
   The lens once every switch already asked for has landed. A switch made while another is still
   rebinding starts from here rather than from `facing`, so two quick switches go there and back,
   the way two synchronous binds do on Android.
   */
  private(set) var targetFacing: Facing = .front
  private var switchesInFlight = 0

  var previewSize = CaptureSize(width: 1280, height: 720)
  var analysisSize = CaptureSize(width: 640, height: 480)

  /// `auto` prefers front and falls back to back. A pinned lens fails instead of falling back.
  var facingFallbackAllowed = false

  /**
   The rate the sensor is held at. Thirty, because inference is never run faster than frames arrive
   and an iPhone 15 asked for 60 ran warm within minutes for a skeleton that looked identical.
   */
  static let pinnedFps = 30

  /// Told, on main, what the bound camera actually delivers once it has been pinned.
  var onFrameRate: ((Int) -> Void)?

  /**
   Bumped by every start, pause, resume and release, and compared by whatever lands a turn later to
   learn whether it has been superseded. Behind a lock rather than main-thread state like the rest,
   because the session queue has to read it too: it is how a pause that lands while `startRunning`
   is still blocking keeps the camera off instead of leaving it running behind a view that asked for
   it off.
   */
  let token = Guarded(0)

  /// Kept so `resume()` can re-run what a start runs once the camera is up, as Android does.
  private var onBound: (() -> Void)?

  /// Read on the session queue, written on main when the device rotates.
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
        // A pinned lens the device does not have is documented as its own code.
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
        onBound()
      }
    }
  }

  /**
   Rebinds, restoring the old lens on failure. `onDone` reports the lens actually bound.

   Every call settles exactly once. A switch made while another is rebinding queues behind it; one
   that a pause or release overtakes fails with `CAMERA_SWITCH_FAILED` rather than leaving its
   promise pending forever.
   */
  func switchTo(_ target: Facing, onDone: @escaping (Facing) -> Void, onFailed: @escaping (ErrorCode, Error?) -> Void) {
    guard isBound else {
      onFailed(.cameraSwitchFailed, CameraError("camera is not running"))
      return
    }
    // The `auto` fallback belongs on the first bind, not here. Letting it run would rebind the lens
    // that is already up, flash the preview, and resolve the switch as a success that changed
    // nothing. guides/camera-control.md promises a CAMERA_SWITCH_FAILED instead.
    guard device(for: target) != nil else {
      onFailed(.cameraSwitchFailed, CameraError("this device has no \(target.nameForJs) camera"))
      return
    }

    let current = token.value
    targetFacing = target
    switchesInFlight += 1

    // Resolves a switch that did land, unless something overtook it on the way back to main.
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
        // Already there, because an earlier switch in the queue took it there.
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
          // The previous camera is gone too. This is no longer recoverable.
          self.finishSwitch(current, landed: nil) { _ in
            self.isBound = false
            onFailed(.cameraUnavailable, rollbackError)
          }
        }
      }
    }
  }

  /// Called on a rotation so the analysis buffer and the preview both keep arriving upright.
  func updateTargetRotation() {
    let next = currentOrientation()
    orientation = next
    applyPreviewOrientation()
    sessionQueue.async { [weak self] in
      self?.applyOrientation(next)
    }
    PoseLog.debug(.camera, "target rotation now \(next.rawValue)")
  }

  /// Parks a facing change made while unbound so the next bind, or resume, picks it up.
  func setPendingFacing(_ target: Facing) {
    guard !isBound else { return }
    facing = target
    targetFacing = target
  }

  /**
   Detaching the delegate rather than tearing the session down. It is the exact counterpart of
   `ImageAnalysis.clearAnalyzer()`, and it means a paused detector does not cost a camera restart.
   */
  func setAnalyzerEnabled(_ enabled: Bool) {
    analyzerEnabled = enabled
    let queue = analysisQueue
    sessionQueue.async { [weak self] in
      guard let self = self else { return }
      self.output?.setSampleBufferDelegate(enabled ? self.sampleDelegate : nil, queue: enabled ? queue : nil)
    }
  }

  /**
   Stops the session whatever state it is in. Queued rather than guarded on `isBound`, so a pause
   that lands while a start is still configuring runs after it and finds the session it built.
   */
  func pause() {
    bump()
    isBound = false
    sessionQueue.async { [weak self] in
      guard let session = self?.session, session.isRunning else { return }
      session.stopRunning()
      PoseLog.info(.camera, "session stopped")
    }
  }

  /**
   Restarts what `pause()` stopped and re-runs what a start runs once the camera is up: the preview
   attach and `onBound`. A pause that landed during startup left a session that was built but
   never started, and without both of those it came back as a running camera with no preview, no
   detector and no `onReady`.
   */
  func resume(onFailed: @escaping (ErrorCode, Error?) -> Void) {
    guard !isBound else { return }
    let current = bump()
    let target = targetFacing

    sessionQueue.async { [weak self] in
      guard let self = self, self.isCurrent(current) else { return }
      guard let session = self.session else {
        // Nothing was ever built: the start failed, or a release came first. Start over.
        DispatchQueue.main.async {
          guard self.isCurrent(current) else { return }
          self.start(facing: target, onBound: self.onBound ?? {}, onFailed: onFailed)
        }
        return
      }

      // A facing parked while paused, which Android's resume binds the same way.
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
        self.onBound?()
      }
    }
  }

  func release() {
    bump()
    isBound = false
    onBound = nil
    previewView?.previewLayer?.session = nil
    // Strongly: the view may be gone by the time this runs, and the session still has to stop.
    sessionQueue.async { [self] in
      output?.setSampleBufferDelegate(nil, queue: nil)
      session?.stopRunning()
      session = nil
      input = nil
      output = nil
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

  /**
   Hops to main and settles one switch exactly once. `landed` is the lens bound once this switch is
   done, or nil when it never got as far as the session. `settle` is told whether a start, pause,
   resume or release overtook the switch meanwhile, in which case nothing it bound is recorded.
   */
  private func finishSwitch(_ current: Int, landed: Facing?, then settle: @escaping (_ superseded: Bool) -> Void) {
    DispatchQueue.main.async {
      self.switchesInFlight -= 1
      let superseded = !self.isCurrent(current)
      if !superseded, let landed = landed {
        self.facing = landed
        // The preview layer keeps its connection across an input swap, so its mirroring is the old
        // lens's until this runs. Without it, switching front to back leaves the preview mirrored
        // and the overlay lands on the wrong side of the body.
        self.applyPreviewOrientation()
      }
      self.syncTargetWhenIdle()
      settle(superseded)
    }
  }

  /// Once nothing is queued, the target is whatever is actually bound. Main thread only.
  private func syncTargetWhenIdle() {
    if switchesInFlight <= 0 {
      switchesInFlight = 0
      targetFacing = facing
    }
  }
}
