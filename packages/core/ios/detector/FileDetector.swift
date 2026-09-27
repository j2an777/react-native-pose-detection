import MediaPipeTasksVision

/// GPU unless its check failed here or a camera is detecting; on an unchecked device the first
/// frame is the check. Chosen once per job: a camera starting mid-job shares the GPU until it ends.
final class FileDetector {
  private let modelPath: String
  private let maxPoses: Int
  private let minConfidence: Float
  private var detector: PoseDetector
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
