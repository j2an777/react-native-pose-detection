import AVFoundation
import UIKit

/**
 The half of `CameraSource` that touches the capture session, split out so each file stays one
 concern: `CameraSource.swift` is the lifecycle the view drives, this is how a session is built,
 rebound and pointed the right way up.

 Everything under "Session queue only" runs on `sessionQueue` and nowhere else.
 */
extension CameraSource {
  // MARK: - Session queue only

  /// What one start asked for, captured on main so the session queue reads none of main's fields.
  struct SessionRequest {
    let target: Facing
    let previewSize: CaptureSize
    let analysisSize: CaptureSize
    let fallbackAllowed: Bool
    let analyzerEnabled: Bool
    let token: Int
  }

  /**
   Builds a session and starts it, returning the lens bound, which is not the one asked for when
   `auto` fell back. A start that was superseded while it configured leaves the session built but
   not running, so `resume()` has something to start.
   */
  func configure(_ request: SessionRequest) throws -> Facing {
    // What an earlier start left behind: a session a pause stopped, or one that never ran.
    if let previous = session {
      output?.setSampleBufferDelegate(nil, queue: nil)
      if previous.isRunning { previous.stopRunning() }
    }

    let session = AVCaptureSession()
    session.beginConfiguration()
    session.sessionPreset = CameraSource.preset(for: request.previewSize)

    let resolved = try resolveDevice(request.target, fallbackAllowed: request.fallbackAllowed)
    let deviceInput = try AVCaptureDeviceInput(device: resolved.device)
    guard session.canAddInput(deviceInput) else {
      session.commitConfiguration()
      throw CameraError("this device will not accept the \(resolved.facing.nameForJs) camera")
    }
    session.addInput(deviceInput)

    let videoOutput = AVCaptureVideoDataOutput()
    // A slow frame is dropped rather than queued, so the pipeline degrades in latency instead of
    // falling behind forever. The counterpart of STRATEGY_KEEP_ONLY_LATEST on Android.
    videoOutput.alwaysDiscardsLateVideoFrames = true
    videoOutput.videoSettings = videoSettings(request.analysisSize)
    guard session.canAddOutput(videoOutput) else {
      session.commitConfiguration()
      throw CameraError("this device will not accept a video data output")
    }
    session.addOutput(videoOutput)

    self.session = session
    self.input = deviceInput
    self.output = videoOutput
    self.boundFacing = resolved.facing

    applyOrientation(orientation)
    session.commitConfiguration()
    // After the commit: adding an input resets the device's frame durations to its defaults.
    pinFrameRate(resolved.device)

    if request.analyzerEnabled {
      videoOutput.setSampleBufferDelegate(sampleDelegate, queue: analysisQueue)
    }

    guard isCurrent(request.token) else {
      PoseLog.debug(.camera, "start superseded while configuring, session built but not started")
      return resolved.facing
    }

    // Before the layer is attached: assigning `previewLayer.session` opens its own configuration
    // block, and doing that from main while this queue is inside `startRunning` puts two on one
    // session, which AVFoundation aborts on. A simulator hid the overlap; an iPhone 15 did not.
    session.startRunning()

    // A pause that landed while `startRunning` blocked. Honoured here rather than left to find a
    // camera running behind a view that asked for it off.
    if !isCurrent(request.token) {
      session.stopRunning()
      PoseLog.debug(.camera, "start superseded while starting, session stopped again")
      return resolved.facing
    }

    PoseLog.info(
      .camera,
      "bound \(resolved.facing.nameForJs) preview=\(request.previewSize.width)x\(request.previewSize.height) "
        + "analysis=\(request.analysisSize.width)x\(request.analysisSize.height)"
    )
    return resolved.facing
  }

  func swapInput(to target: Facing) throws {
    guard let session = session else { throw CameraError("no capture session") }
    guard let device = device(for: target) else {
      throw CameraError("this device has no \(target.nameForJs) camera")
    }

    session.beginConfiguration()
    do {
      if let existing = input {
        session.removeInput(existing)
      }
      let next = try AVCaptureDeviceInput(device: device)
      guard session.canAddInput(next) else {
        // Put the old one back, so a failed swap leaves the session with a camera rather than none.
        if let existing = input, session.canAddInput(existing) { session.addInput(existing) }
        throw CameraError("this device will not accept the \(target.nameForJs) camera")
      }
      session.addInput(next)
      input = next
      boundFacing = target
      // Inside the configuration block, so no frame is ever delivered with the old rotation.
      applyOrientation(orientation)
    } catch {
      session.commitConfiguration()
      throw error
    }
    session.commitConfiguration()
    // The new lens starts at its own default frame durations, so it is pinned the same way.
    pinFrameRate(device)
  }

  /**
   Pins the sensor to a steady `CameraSource.pinnedFps`, and reports what the device actually runs
   at to `onFrameRate`, which the governor takes as its ceiling.

   Left alone, auto-exposure may lengthen frames in a dim room, which halves the camera's rate and
   the skeleton's with it. Holding the minimum frame duration too trades a little exposure in the
   dark for a rate the pipeline can count on; detection copes with a noisier frame far better than
   with half as many. A format that cannot hold the rate keeps the fastest it has.
   */
  func pinFrameRate(_ device: AVCaptureDevice) {
    let ranges = device.activeFormat.videoSupportedFrameRateRanges
    let fastest = ranges.map(\.maxFrameRate).max() ?? Double(CameraSource.pinnedFps)
    let fps = min(Double(CameraSource.pinnedFps), fastest).rounded(.down)

    if fps >= 1 {
      let duration = CMTime(value: 1, timescale: CMTimeScale(fps))
      do {
        try device.lockForConfiguration()
        device.activeVideoMinFrameDuration = duration
        if ranges.contains(where: { $0.minFrameRate <= fps && fps <= $0.maxFrameRate }) {
          device.activeVideoMaxFrameDuration = duration
        }
        device.unlockForConfiguration()
      } catch {
        PoseLog.warn(.camera, "could not pin the frame rate: \(error.localizedDescription)")
      }
    }

    let seconds = CMTimeGetSeconds(device.activeVideoMinFrameDuration)
    let delivered = seconds.isFinite && seconds > 0 ? Int((1 / seconds).rounded()) : CameraSource.pinnedFps
    PoseLog.info(.camera, "camera delivers \(delivered) fps")
    DispatchQueue.main.async { [weak self] in self?.onFrameRate?(delivered) }
  }

  func applyOrientation(_ orientation: AVCaptureVideoOrientation) {
    guard let connection = output?.connection(with: .video) else { return }
    CaptureRotation.apply(orientation, to: connection)
    // Never mirrored: the landmarks have to describe the real world.
    CaptureRotation.mirror(false, on: connection)
  }

  func videoSettings(_ analysisSize: CaptureSize) -> [String: Any] {
    return [
      kCVPixelBufferPixelFormatTypeKey as String: Int(kCVPixelFormatType_32BGRA),
      kCVPixelBufferWidthKey as String: analysisSize.width,
      kCVPixelBufferHeightKey as String: analysisSize.height
    ]
  }

  func resolveDevice(
    _ target: Facing,
    fallbackAllowed: Bool
  ) throws -> (device: AVCaptureDevice, facing: Facing) {
    if let device = device(for: target) {
      return (device, target)
    }
    guard fallbackAllowed, let fallback = device(for: target.opposite) else {
      throw CameraMissing(facing: target)
    }
    PoseLog.info(.camera, "no \(target.nameForJs) camera on this device, using \(target.opposite.nameForJs)")
    return (fallback, target.opposite)
  }

  func device(for target: Facing) -> AVCaptureDevice? {
    return AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: target.position)
  }

  // MARK: - Main thread only

  /// Idempotent: resuming a session the layer already shows must not reassign it.
  func attachPreview(_ session: AVCaptureSession?) {
    guard let layer = previewView?.previewLayer, let session = session else { return }
    if layer.session !== session {
      layer.session = session
    }
    applyPreviewOrientation()
  }

  func applyPreviewOrientation() {
    guard let connection = previewView?.previewLayer?.connection else { return }
    CaptureRotation.apply(orientation, to: connection)
    CaptureRotation.mirror(facing == .front, on: connection)
  }

  func currentOrientation() -> AVCaptureVideoOrientation {
    guard Thread.isMainThread, let interface = previewView?.window?.windowScene?.interfaceOrientation else {
      return orientation
    }
    return CaptureRotation.videoOrientation(for: interface)
  }
}
