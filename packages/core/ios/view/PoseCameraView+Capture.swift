import AVFoundation
import ExpoModulesCore
import MediaPipeTasksVision

/// Runs on `analysisQueue` and detects there, so a sample buffer never outlives its callback.
extension PoseCameraView: AVCaptureVideoDataOutputSampleBufferDelegate {
  public func captureOutput(
    _ output: AVCaptureOutput,
    didOutput sampleBuffer: CMSampleBuffer,
    from connection: AVCaptureConnection
  ) {
    // MPImage's CoreVideo objects are autoreleased, and a busy queue's pool never drains itself.
    autoreleasepool {
      let wasAwaiting = awaitingFirstFrame.mutate { pending -> Bool in
        let was = pending
        pending = false
        return was
      }
      if wasAwaiting {
        DispatchQueue.main.async { [weak self] in self?.completeSwitch() }
      }

      guard feeding.value, let detector = detector.value else { return }
      let now = Monotonic.nowMs()

      let decision = rate.value
      if decision.detectionPaused { return }
      guard frameIsDue(now, decision) else { return }

      guard let pixels = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
      let size = CaptureSize(width: CVPixelBufferGetWidth(pixels), height: CVPixelBufferGetHeight(pixels))
      if size != frameSize.value {
        frameSize.value = size
        PoseLog.info(.camera, "analysis buffers arrive at \(size.width)x\(size.height)")
      }

      do {
        // Always `.up`: the capture connection has already rotated the buffer.
        let image = try MPImage(sampleBuffer: sampleBuffer, orientation: .up)
        try detector.detect(image: image, cameraTimestampMs: presentationMilliseconds(sampleBuffer))
      } catch {
        PoseLog.warn(.detector, "frame dropped: \(error.localizedDescription)")
      }
    }
  }

  private func presentationMilliseconds(_ sampleBuffer: CMSampleBuffer) -> Int {
    let seconds = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sampleBuffer))
    guard seconds.isFinite, seconds > 0 else { return 0 }
    return Int(seconds * PoseCameraView.millisPerSecond)
  }

  /// Paces from when the last frame was due, not when it ran: pacing from the accepted frame snaps
  /// to sensor divisors, so 24 fps on a 30 Hz sensor would fall to 15.
  private func frameIsDue(_ nowMs: Int64, _ decision: RateDecision) -> Bool {
    let fps = idleAdjusted(decision.fps, nowMs)
    guard fps > 0 else { return false }

    let now = Double(nowMs)
    guard now + PoseCameraView.pacingJitterMs >= nextDetectDueMs else { return false }

    // More than an interval late is a stall: restart the schedule rather than run a backlog.
    let intervalMs = PoseCameraView.millisPerSecond / Double(fps)
    nextDetectDueMs = now - nextDetectDueMs > intervalMs ? now + intervalMs : nextDetectDueMs + intervalMs
    return true
  }

  private func idleAdjusted(_ fps: Int, _ nowMs: Int64) -> Int {
    let lastPose = lastPoseMs.value
    let idle = lastPose == 0 ? nil : idleRates.value?.rate(sinceLastPoseMs: nowMs - lastPose)
    let effective = idle.map { min($0, fps) }

    if effective != idleFps.value {
      idleFps.value = effective
      PoseLog.debug(.engine, effective.map { "idle at \($0) fps" } ?? "a pose is back, idle over")
      DispatchQueue.main.async { [weak self] in self?.emitPerformanceChange(reason: "idle") }
    }
    return effective ?? fps
  }
}
