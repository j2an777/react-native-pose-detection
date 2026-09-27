import XCTest
@testable import PoseEngine

/// The numbers are the ones `guides/performance.md` promises.
final class CalibratorTests: XCTestCase {
  private let model = "pose_landmarker_full.task"
  private var suiteName = ""
  private var defaults = UserDefaults.standard

  override func setUpWithError() throws {
    try super.setUpWithError()
    suiteName = "pose-calibrator-tests-\(UUID().uuidString)"
    defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
  }

  override func tearDown() {
    defaults.removePersistentDomain(forName: suiteName)
    super.tearDown()
  }

  private func make(memoryGiB: Float = 6) -> Calibrator {
    return Calibrator(defaults: defaults, memoryGiB: { memoryGiB })
  }

  @discardableResult
  private func feed(
    _ calibrator: Calibrator,
    ms: Float,
    count: Int,
    from startMs: Int64,
    stepMs: Int64 = 33
  ) -> (moved: Bool, endMs: Int64) {
    var moved = false
    var now = startMs
    for _ in 0..<count {
      if calibrator.record(inferenceMs: ms, nowMs: now) { moved = true }
      now += stepMs
    }
    return (moved, now)
  }

  func testNothingMeasuredMeansAMemoryTierAndNoMedian() {
    let calibrator = make(memoryGiB: 7.5)
    calibrator.start(modelFileName: model)
    XCTAssertEqual(calibrator.tier, .high)
    XCTAssertEqual(calibrator.p50InferenceMs, 0, "an unknown device, which the governor runs at the camera's rate")
    XCTAssertEqual(calibrator.phase, .calibrating)

    let middling = make(memoryGiB: 3.6)
    middling.start(modelFileName: model)
    XCTAssertEqual(middling.tier, .medium, "no step down on top of a guess")
  }

  func testTheFirstEstimateLandsAfterFifteenFrames() {
    let calibrator = make()
    calibrator.start(modelFileName: model)

    let warmup = feed(calibrator, ms: 20, count: 14, from: 1_000)
    XCTAssertFalse(warmup.moved, "14 frames is not an estimate")
    XCTAssertEqual(calibrator.p50InferenceMs, 0)

    let (moved, _) = feed(calibrator, ms: 20, count: 1, from: warmup.endMs)
    XCTAssertTrue(moved)
    XCTAssertEqual(calibrator.p50InferenceMs, 20)
    XCTAssertEqual(calibrator.tier, .high)
  }

  func testTheMedianShrugsOffOneSlowFrame() {
    let calibrator = make()
    calibrator.start(modelFileName: model)
    let fast = feed(calibrator, ms: 20, count: 14, from: 1_000)
    feed(calibrator, ms: 400, count: 1, from: fast.endMs)
    XCTAssertEqual(calibrator.p50InferenceMs, 20)
  }

  func testASteadyDeviceSettlesInsteadOfTwitching() {
    let calibrator = make()
    calibrator.start(modelFileName: model)

    let first = feed(calibrator, ms: 20, count: 15, from: 1_000)
    // Past the cooldown and a full window.
    let second = feed(calibrator, ms: 20, count: 120, from: first.endMs)
    XCTAssertTrue(second.moved, "settling is reported once so it can be persisted")
    XCTAssertEqual(calibrator.phase, .settled)

    let third = feed(calibrator, ms: 21, count: 120, from: second.endMs)
    XCTAssertFalse(third.moved, "a one millisecond wobble is inside the deadband")
    XCTAssertEqual(calibrator.p50InferenceMs, 20, "the published median did not chase it")
  }

  func testALoadedDeviceIsWalkedDownToWhatItCosts() {
    let calibrator = make()
    calibrator.start(modelFileName: model)
    let fast = feed(calibrator, ms: 20, count: 180, from: 1_000)

    let (moved, _) = feed(calibrator, ms: 60, count: 180, from: fast.endMs)
    XCTAssertTrue(moved)
    XCTAssertEqual(calibrator.tier, .low)
    XCTAssertEqual(calibrator.p50InferenceMs, 60)
  }

  func testARestartOnTheSameModelKeepsTheMeasurement() {
    let calibrator = make()
    calibrator.start(modelFileName: model)
    feed(calibrator, ms: 20, count: 15, from: 1_000)
    XCTAssertEqual(calibrator.p50InferenceMs, 20)

    calibrator.start(modelFileName: model)
    XCTAssertEqual(calibrator.p50InferenceMs, 20, "a camera restart is not a new device")

    calibrator.start(modelFileName: "pose_landmarker_lite.task")
    XCTAssertEqual(calibrator.p50InferenceMs, 0, "a different model is a different cost")
  }

  func testTheSecondLaunchStartsWhereTheFirstOneFinished() {
    let first = make()
    first.start(modelFileName: model)
    feed(first, ms: 20, count: 300, from: 1_000)
    XCTAssertEqual(first.phase, .settled)
    first.persist()

    let second = make()
    second.start(modelFileName: model)
    XCTAssertEqual(second.phase, .cached)
    XCTAssertEqual(second.tier, .high)
    XCTAssertEqual(second.p50InferenceMs, 20)
  }

  func testAGuessIsNotPersisted() {
    let first = make()
    first.start(modelFileName: model)
    feed(first, ms: 20, count: 15, from: 1_000)
    first.persist()

    let second = make()
    second.start(modelFileName: model)
    XCTAssertEqual(second.p50InferenceMs, 0, "one estimate is not a settled measurement")
  }

  func testTheGpuVerdictIsRememberedOnItsOwn() {
    let first = make()
    first.start(modelFileName: model)
    XCTAssertNil(first.gpuVerdict)
    first.recordGpuVerdict(false)

    let second = make()
    second.start(modelFileName: model)
    XCTAssertEqual(second.gpuVerdict, false)
    XCTAssertEqual(second.phase, .calibrating, "a verdict without a measurement is not a cached rate")
  }

  func testAFileJobsVerdictReachesTheCameraAndLeavesItsMeasurementAlone() {
    let camera = make()
    camera.start(modelFileName: model)
    feed(camera, ms: 20, count: 300, from: 1_000)
    camera.persist()
    XCTAssertNil(Calibrator.cachedGpu(modelFileName: model, defaults: defaults))

    Calibrator.storeGpu(true, modelFileName: model, defaults: defaults)
    XCTAssertEqual(Calibrator.cachedGpu(modelFileName: model, defaults: defaults), true)

    let next = make()
    next.start(modelFileName: model)
    XCTAssertEqual(next.gpuVerdict, true)
    XCTAssertEqual(next.p50InferenceMs, 20, "the measurement survives the file job's write")
  }

  func testACameraThatNeverProbedKeepsTheVerdictAFileJobRecorded() {
    let camera = make()
    camera.start(modelFileName: model)
    // Recorded while the camera runs, so the camera's own copy of the verdict is still nil.
    Calibrator.storeGpu(false, modelFileName: model, defaults: defaults)
    feed(camera, ms: 20, count: 300, from: 1_000)
    XCTAssertNil(camera.gpuVerdict)
    camera.persist()
    XCTAssertEqual(Calibrator.cachedGpu(modelFileName: model, defaults: defaults), false)
  }
}
