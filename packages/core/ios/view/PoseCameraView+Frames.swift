import Foundation
import ExpoModulesCore
import MediaPipeTasksVision

struct FrameScalars {
  let comX: Float
  let comY: Float
  let velocityX: Float
  let velocityY: Float
}

struct FrameTiming {
  let size: CaptureSize
  let nowMs: Int64
  let comparable: Bool
  let elapsedSeconds: Float
}

/// The result path, on MediaPipe's callback queue.
extension PoseCameraView: PoseDetectorObserver {
  func poseDetector(_ detector: PoseDetector, didDetect result: PoseLandmarkerResult, timestampMs: Int) {
    // Empty results count too: the model ran, and skipping them would freeze the rate.
    countResult(Monotonic.nowMs())
    let identity = ObjectIdentifier(detector)
    if identity != clockedWith {
      clockedWith = identity
      visibilityClock.reset()
    }
    if timestampMs < staleBefore.value {
      PoseLog.trace(.camera, "dropped a frame from the previous camera")
      // MediaPipe's filters took this frame in, and the next one is inverted against it.
      visibilityClock.reset()
      return
    }
    accept(result, timestampMs: timestampMs, visibilitySmoothed: detector.maxPoses == 1)
  }

  private func countResult(_ nowMs: Int64) {
    let previous = lastResultMs.value
    lastResultMs.value = nowMs

    // A gap restarts the window; averaging across a pause would publish a near-zero rate.
    if previous != 0 && nowMs - previous > PoseCameraView.fpsStaleAfterMs {
      framesInWindow = 0
      fpsWindowStartMs = nowMs
    }

    framesInWindow += 1
    if fpsWindowStartMs == 0 { fpsWindowStartMs = nowMs }
    let elapsed = nowMs - fpsWindowStartMs

    let due = elapsed >= PoseCameraView.fpsWindowMs
      || (measuredFps.value == 0
        && elapsed >= PoseCameraView.fpsFirstWindowMs
        && framesInWindow >= PoseCameraView.fpsFirstWindowFrames)
    guard due else { return }

    measuredFps.value = Int((Int64(framesInWindow) * PoseCameraView.fpsWindowMs) / max(elapsed, 1))
    framesInWindow = 0
    fpsWindowStartMs = nowMs
  }

  /// Camera poses only; `StaticDetection` applies the same rules to files. `visibilitySmoothed`:
  /// MediaPipe low-passed this result's visibility, which it does for one pose.
  func accept(_ result: PoseLandmarkerResult, timestampMs: Int, visibilitySmoothed: Bool) {
    let poses = result.landmarks
    guard !poses.isEmpty else {
      onPoseLost()
      return
    }
    let primaryIndex = poses.count > 1 ? primaryPose(poses) : 0
    let primary = poses[primaryIndex]
    guard primary.count >= Skeleton.landmarkCount else { return }

    // Monotonic like log entries: when the pose became known, not when the sensor exposed it.
    let nowMs = Monotonic.nowMs()
    lastPoseMs.value = nowMs

    copyPrimary(primary, timestampMs: timestampMs, visibilitySmoothed: visibilitySmoothed)

    // The largest body can change between frames; nothing carries across a change of person.
    let box = PoseBox(landmarkBuffer)
    if let previous = previousBox, box.overlap(previous) < PoseBox.sameBodyOverlap {
      PoseLog.debug(.engine, "the primary pose is somebody else now, starting its motion over")
      resetVelocity()
      smoothing.reset()
    }
    previousBox = box

    // Velocity across a gap (a switch, pause or background) is no real speed; see `Continuity`.
    let elapsedMs = Double(nowMs) - previousFrameMs.value
    let expectedFps = Double(idleFps.value ?? rate.value.fps)
    let comparable = previousFrameMs.value > 0 && elapsedMs > 0
      && elapsedMs <= Continuity.maxGapMs(fps: expectedFps)
    let elapsedSeconds = comparable ? Float(elapsedMs / PoseCameraView.millisPerSecond) : Float.nan

    let size = frameSize.value

    // Before anything reads a coordinate. Speed is in body spans; x is normalized by width, so
    // its span is scaled by the aspect.
    if propSmoothing {
      let span = Geometry.bodySpan(landmarkBuffer)
      let aspect = size.width > 0 ? Float(size.height) / Float(size.width) : 1
      smoothing.apply(to: &landmarkBuffer, elapsedSeconds: elapsedSeconds, scaleX: span * aspect, scaleY: span)
    } else {
      smoothing.reset()
    }

    if overlayOn.value {
      overlayView.submit(landmarkBuffer, width: size.width, height: size.height)
    }

    buildFrame(result: result, pose: primaryIndex, poseSize: primary.count, timing: FrameTiming(
      size: size,
      nowMs: nowMs,
      comparable: comparable,
      elapsedSeconds: elapsedSeconds
    ))
  }

  private func copyPrimary(_ primary: [NormalizedLandmark], timestampMs: Int, visibilitySmoothed: Bool) {
    for index in 0..<Skeleton.landmarkCount {
      let landmark = primary[index]
      let base = index * Skeleton.landmarkStride
      landmarkBuffer[base + Skeleton.offsetX] = landmark.x
      landmarkBuffer[base + Skeleton.offsetY] = landmark.y
      landmarkBuffer[base + Skeleton.offsetZ] = landmark.z
      landmarkBuffer[base + Skeleton.offsetVisibility] = landmark.visibility?.floatValue ?? 0
    }

    visibilityClocked = visibilitySmoothed
    if visibilitySmoothed {
      visibilityClock.apply(to: &landmarkBuffer, timestampMs: Double(timestampMs))
    } else {
      visibilityClock.reset()
    }
  }

  private func onPoseLost() {
    // MediaPipe starts its filters over on a frame with nobody in it.
    visibilityClock.reset()
    previousBox = nil
    overlayView.clearPose()
    frames.clearLatest()
    flushOwedBatch()
    resetVelocity()
    triggers.onPoseLost()
    smoothing.reset()
  }

  /// Largest box, ties to the most central. MediaPipe's order is only detection order.
  private func primaryPose(_ poses: [[NormalizedLandmark]]) -> Int {
    var best = 0
    var bestArea: Float = -1
    var bestOffset = Float.greatestFiniteMagnitude

    for index in poses.indices {
      let pose = poses[index]
      if pose.count < Skeleton.landmarkCount { continue }

      var minX = Float.greatestFiniteMagnitude
      var maxX = -Float.greatestFiniteMagnitude
      var minY = Float.greatestFiniteMagnitude
      var maxY = -Float.greatestFiniteMagnitude

      for point in pose {
        minX = min(minX, point.x)
        maxX = max(maxX, point.x)
        minY = min(minY, point.y)
        maxY = max(maxY, point.y)
      }

      let area = (maxX - minX) * (maxY - minY)
      let offset = abs((minX + maxX) / 2 - 0.5) + abs((minY + maxY) / 2 - 0.5)
      let better = area > bestArea + PoseBox.areaTieEpsilon
        || (abs(area - bestArea) <= PoseBox.areaTieEpsilon && offset < bestOffset)
      if better {
        best = index
        bestArea = area
        bestOffset = offset
      }
    }
    return best
  }

  private func resetVelocity() {
    previousComX = .nan
    previousComY = .nan
    previousFrameMs.value = 0
    hasPreviousLandmarks = false
  }

  /// The latest frame is recorded in every mode: `snapshotFrame()` must answer at `mode: 'off'`.
  private func buildFrame(result: PoseLandmarkerResult, pose: Int, poseSize: Int, timing: FrameTiming) {
    let size = timing.size
    let nowMs = timing.nowMs
    let elapsedSeconds = timing.elapsedSeconds

    // One read, so the shape and its scratch buffer come from the same layout.
    guard let layout = frameLayout.value else { return }

    if layout.worldLandmarks {
      fillWorldBuffer(result, pose: pose, poseSize: poseSize)
    }
    let cursor = writeBlocks(into: layout, size: size)
    let timestampMs = Double(nowMs)

    let scalars = writeScalars(into: layout, at: cursor, comparable: timing.comparable, elapsed: elapsedSeconds)
    let comX = scalars.comX
    let comY = scalars.comY

    let dispatched = detector.value?.dispatchNanos(for: result.timestampInMilliseconds) ?? 0
    let processingMs = dispatched == 0
      ? 0
      : Double(Monotonic.nowNanos() - dispatched) / PoseCameraView.nanosPerMilli

    evaluateTriggers(layout: layout, measurements: FrameMeasurements(
      nowMs: nowMs,
      timestampMs: timestampMs,
      processingMs: processingMs,
      comX: comX,
      comY: comY,
      velocityX: scalars.velocityX,
      velocityY: scalars.velocityY,
      elapsedSeconds: elapsedSeconds,
      size: size
    ))

    if processingMs > 0 {
      let moved = calibrator.record(inferenceMs: Float(processingMs), nowMs: nowMs)
      if moved {
        DispatchQueue.main.async { [weak self] in self?.onCalibrationMoved() }
      }
    }

    // Element-wise: assigning would share the buffer and make the next write copy it.
    for index in previousLandmarks.indices {
      previousLandmarks[index] = landmarkBuffer[index]
    }
    hasPreviousLandmarks = true
    previousComX = comX
    previousComY = comY
    previousFrameMs.value = timestampMs

    deliver(layout.scratch, timestampMs: timestampMs, processingMs: processingMs)
  }

  /// Returns where scalars start. Writes via `layout.scratch`: a local var would copy the array.
  private func writeBlocks(into layout: FrameShape, size: CaptureSize) -> Int {
    var cursor = 0

    for joint in layout.jointIndices {
      let base = joint * Skeleton.landmarkStride
      layout.scratch[cursor] = landmarkBuffer[base]
      layout.scratch[cursor + 1] = landmarkBuffer[base + 1]
      layout.scratch[cursor + 2] = landmarkBuffer[base + 2]
      layout.scratch[cursor + 3] = landmarkBuffer[base + 3]
      cursor += Skeleton.landmarkStride
    }

    if layout.worldLandmarks {
      for joint in layout.jointIndices {
        let base = joint * Skeleton.landmarkStride
        layout.scratch[cursor] = worldBuffer[base]
        layout.scratch[cursor + 1] = worldBuffer[base + 1]
        layout.scratch[cursor + 2] = worldBuffer[base + 2]
        layout.scratch[cursor + 3] = worldBuffer[base + 3]
        cursor += Skeleton.landmarkStride
      }
    }

    for triple in layout.angleTriples {
      layout.scratch[cursor] = Geometry.angleDegrees(
        landmarkBuffer,
        proximal: triple[0],
        vertex: triple[1],
        distal: triple[2],
        frameWidth: size.width,
        frameHeight: size.height
      )
      cursor += 1
    }
    return cursor
  }

  private func writeScalars(
    into layout: FrameShape,
    at start: Int,
    comparable: Bool,
    elapsed: Float
  ) -> FrameScalars {
    var cursor = start
    Geometry.centerOfMass(landmarkBuffer, into: &layout.scratch, at: cursor)
    let comX = layout.scratch[cursor]
    let comY = layout.scratch[cursor + 1]
    cursor += 2

    if comparable {
      layout.scratch[cursor] = (comX - previousComX) / elapsed
      layout.scratch[cursor + 1] = (comY - previousComY) / elapsed
    } else {
      // NaN, not zero: zero would read as a body measured to be still.
      layout.scratch[cursor] = .nan
      layout.scratch[cursor + 1] = .nan
    }
    let velocityX = layout.scratch[cursor]
    let velocityY = layout.scratch[cursor + 1]
    cursor += 2

    layout.scratch[cursor] = Geometry.bodySpan(landmarkBuffer)
    return FrameScalars(comX: comX, comY: comY, velocityX: velocityX, velocityY: velocityY)
  }

  /// Indexed, not `worldLandmarks[0]`: with several poses the primary is not always first.
  private func fillWorldBuffer(_ result: PoseLandmarkerResult, pose: Int, poseSize: Int) {
    let world = result.worldLandmarks
    let points = pose < world.count ? world[pose] : []
    guard points.count >= poseSize else {
      for index in worldBuffer.indices {
        worldBuffer[index] = 0
      }
      return
    }

    for index in 0..<Skeleton.landmarkCount {
      let landmark = points[index]
      let base = index * Skeleton.landmarkStride
      worldBuffer[base + Skeleton.offsetX] = landmark.x
      worldBuffer[base + Skeleton.offsetY] = landmark.y
      worldBuffer[base + Skeleton.offsetZ] = landmark.z
      // MediaPipe gives these the screen landmarks' visibility, so they take the re-timed one too.
      worldBuffer[base + Skeleton.offsetVisibility] = visibilityClocked
        ? landmarkBuffer[base + Skeleton.offsetVisibility]
        : landmark.visibility?.floatValue ?? 0
    }
  }
}
