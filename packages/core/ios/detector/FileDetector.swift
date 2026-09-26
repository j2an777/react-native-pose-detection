import MediaPipeTasksVision

/**
 The landmarker a video job runs on, and the rule that picks its delegate.

 The GPU when this device's GPU check passed and no camera is running inference; the CPU otherwise.
 A file job therefore never competes with a live preview for the GPU that preview's own inference
 runs on. A device the check has never run on tries the GPU, and the first frame is the check: if it
 fails, the job carries on on the CPU, and the answer is kept for the camera and for the next job.

 The choice is made once, when the job starts. A camera started halfway through a long job shares
 the GPU with it until the job ends, which is rarer and cheaper than rebuilding mid-job.
 */
final class FileDetector {
  private let modelPath: String
  private let maxPoses: Int
  private let minConfidence: Float
  private var detector: PoseDetector
  /// On the GPU with nothing yet to show it works here.
  private var unproven: Bool

  init(modelPath: String, maxPoses: Int, minConfidence: Float) throws {
    self.modelPath = modelPath
    self.maxPoses = maxPoses
    self.minConfidence = minConfidence

    let modelFileName = PoseDetector.fileName(from: modelPath)
    let verdict = Calibrator.cachedGpu(modelFileName: modelFileName)
    let cameraBusy = FrameStreams.shared.anyDetecting()
    let gpu = !PoseDetector.isSimulator && verdict != false && !cameraBusy
    unproven = gpu && verdict == nil

    var built: PoseDetector?
    if gpu {
      do {
        built = try FileDetector.build(modelPath, maxPoses, minConfidence, .GPU)
      } catch {
        let reason = error.localizedDescription
        PoseLog.warn(.detector, "the GPU could not be built for a file job, using the CPU: \(reason)")
        Calibrator.storeGpu(false, modelFileName: modelFileName)
        unproven = false
      }
    }
    detector = try built ?? FileDetector.build(modelPath, maxPoses, minConfidence, .CPU)
    let reason = cameraBusy ? ", because a camera is detecting" : verdict == nil && gpu ? ", unproven here" : ""
    PoseLog.info(.detector, "file job on \(detector.delegateKind == .GPU ? "GPU" : "CPU")\(reason)")
  }

  var delegateKind: Delegate {
    return detector.delegateKind
  }

  /// VIDEO mode. A GPU that fails is replaced by the CPU once, and the frame is run again there.
  func detect(_ image: MPImage, timestampMs: Int) throws -> PoseLandmarkerResult {
    guard detector.delegateKind == .GPU else {
      return try detector.detectVideo(image, timestampMs: timestampMs)
    }
    let modelFileName = PoseDetector.fileName(from: modelPath)
    do {
      let result = try detector.detectVideo(image, timestampMs: timestampMs)
      if unproven {
        unproven = false
        Calibrator.storeGpu(true, modelFileName: modelFileName)
      }
      return result
    } catch {
      PoseLog.warn(.detector, "the GPU failed on a file job, moving it to the CPU: \(error.localizedDescription)")
      Calibrator.storeGpu(false, modelFileName: modelFileName)
      unproven = false
      detector = try FileDetector.build(modelPath, maxPoses, minConfidence, .CPU)
      return try detector.detectVideo(image, timestampMs: timestampMs)
    }
  }

  private static func build(
    _ modelPath: String,
    _ maxPoses: Int,
    _ minConfidence: Float,
    _ delegateKind: Delegate
  ) throws -> PoseDetector {
    return try PoseDetector.createForStillInput(
      modelPath: modelPath,
      maxPoses: maxPoses,
      minConfidence: minConfidence,
      video: true,
      delegateKind: delegateKind
    )
  }
}
