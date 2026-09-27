import Foundation
import ExpoModulesCore

struct FrameMeasurements {
  let nowMs: Int64
  let timestampMs: Double
  let processingMs: Double
  let comX: Float
  let comY: Float
  let velocityX: Float
  let velocityY: Float
  let elapsedSeconds: Float
  let size: CaptureSize
}

/// Runs on MediaPipe's callback queue.
extension PoseCameraView {
  /// Before `deliver()`: a snapshot trigger claims this frame.
  func evaluateTriggers(layout: FrameShape, measurements: FrameMeasurements) {
    guard !triggers.isEmpty else { return }

    frameContext.landmarks = landmarkBuffer
    frameContext.previousLandmarks = hasPreviousLandmarks ? previousLandmarks : nil
    frameContext.elapsedSeconds = measurements.elapsedSeconds
    frameContext.comX = measurements.comX
    frameContext.comY = measurements.comY
    frameContext.comVelocityX = measurements.velocityX
    frameContext.comVelocityY = measurements.velocityY
    frameContext.frameWidth = measurements.size.width
    frameContext.frameHeight = measurements.size.height

    firings.removeAll(keepingCapacity: true)
    triggers.evaluate(frameContext, nowMs: measurements.nowMs, into: &firings)

    // Released now: a reference held past this frame makes the next buffer write copy it.
    frameContext.landmarks = []
    frameContext.previousLandmarks = nil

    guard !firings.isEmpty else { return }

    for firing in firings {
      let ticket = firing.wantsSnapshot
        ? frames.mintSnapshot(
          layout.scratch,
          timestampMs: measurements.timestampMs,
          processingMs: measurements.processingMs
        )
        : 0

      var payload: [String: Any] = [
        "id": firing.id,
        "phase": firing.phase,
        "count": firing.count,
        "timestamp": firing.timestampMs
      ]
      if let durationMs = firing.durationMs {
        payload["durationMs"] = durationMs
      }
      // Zero: the frame could not be held.
      if ticket != 0 {
        payload["snapshotId"] = ticket
      }

      DispatchQueue.main.async { [weak self] in self?.onTrigger(payload) }
    }
    firings.removeAll(keepingCapacity: true)
  }

  /// `deliver` only runs with a pose, so a batch buffered before somebody left flushes from here.
  func flushOwedBatch() {
    guard propMode == .batched else { return }
    let now = Monotonic.nowMs()
    guard now - lastEmitMs.value >= propFlushMs.value, frames.hasBuffered else { return }
    lastEmitMs.value = now
    tick()
  }

  func deliver(_ scratch: [Float], timestampMs: Double, processingMs: Double) {
    let mode = propMode
    let now = Monotonic.nowMs()
    let sinceEmit = now - lastEmitMs.value

    let due: Bool
    switch mode {
    case .off: due = false
    case .live: due = true
    case .throttled: due = sinceEmit >= propThrottleMs.value
    case .batched: due = sinceEmit >= propFlushMs.value
    }

    let buffered = mode == .live || mode == .batched || (mode == .throttled && due)

    frames.submit(scratch, timestampMs: timestampMs, processingMs: processingMs, buffered: buffered)

    guard due, mode != .off else { return }
    lastEmitMs.value = now
    tick()
  }

  private func tick() {
    let shouldTick = tickPending.mutate { pending -> Bool in
      guard !pending else { return false }
      pending = true
      return true
    }
    guard shouldTick else { return }

    DispatchQueue.main.async { [weak self] in
      guard let self = self else { return }
      self.tickPending.value = false
      self.onFrames([:])
    }
  }
}
