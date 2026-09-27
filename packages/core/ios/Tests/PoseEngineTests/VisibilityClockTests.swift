import XCTest
@testable import PoseEngine

final class VisibilityClockTests: XCTestCase {
  private let clock = VisibilityClock()

  private final class MediaPipeFilter {
    private var value = Float.nan

    func apply(_ model: Float) -> Float {
      let weight = VisibilityClock.mediaPipeWeight
      value = value.isNaN ? model : weight * model + (1 - weight) * value
      return value
    }
  }

  private func frame(_ visibility: Float) -> [Float] {
    var landmarks = [Float](repeating: 0, count: Skeleton.landmarkCount * Skeleton.landmarkStride)
    for joint in 0..<Skeleton.landmarkCount {
      landmarks[joint * Skeleton.landmarkStride + Skeleton.offsetVisibility] = visibility
    }
    return landmarks
  }

  private func run(_ models: [Float], intervalMs: Double) -> [Float] {
    let mediaPipe = MediaPipeFilter()
    return models.enumerated().map { index, model in
      var landmarks = frame(mediaPipe.apply(model))
      clock.apply(to: &landmarks, timestampMs: Double(index) * intervalMs)
      return landmarks[Skeleton.offsetVisibility]
    }
  }

  func testAt30FpsTheWeightIsMediaPipesOwn() {
    XCTAssertEqual(VisibilityClock.weight(elapsedMs: VisibilityClock.referenceMs), 0.1, accuracy: 1e-6)
    XCTAssertEqual(VisibilityClock.weight(elapsedMs: 0), 0)
  }

  func testAt30FpsNothingChanges() {
    let models: [Float] = [0.1, 0.9, 0.9, 0.4, 0.95, 0.95, 0.2, 0.7]
    let mediaPipe = MediaPipeFilter()
    let expected = models.map(mediaPipe.apply)
    let clocked = run(models, intervalMs: VisibilityClock.referenceMs)
    for index in models.indices {
      XCTAssertEqual(clocked[index], expected[index], accuracy: 1e-5)
    }
  }

  func testAt10FpsAFrameMovesAsFarAsThreeDoAt30() {
    let at10 = run([0.1, 0.9, 0.9, 0.9], intervalMs: 100)
    let at30 = MediaPipeFilter()
    let reference = ([0.1] + [Float](repeating: 0.9, count: 9)).map(at30.apply)
    XCTAssertEqual(at10[1], reference[3], accuracy: 1e-4)
    XCTAssertEqual(at10[2], reference[6], accuracy: 1e-4)
    XCTAssertEqual(at10[3], reference[9], accuracy: 1e-4)
  }

  func testTheFirstFrameAfterAResetPassesThrough() {
    _ = run([0.2, 0.2], intervalMs: 100)
    clock.reset()
    var landmarks = frame(0.8)
    clock.apply(to: &landmarks, timestampMs: 500)
    XCTAssertEqual(landmarks[Skeleton.offsetVisibility], 0.8)
  }

  func testAFilterThatStartedOverUnseenIsTakenAsItComes() {
    var first = frame(0.9)
    clock.apply(to: &first, timestampMs: 0)
    // 0.9 then 0.1 in one frame is a move MediaPipe's filter cannot make; only a restart can.
    var landmarks = frame(0.1)
    clock.apply(to: &landmarks, timestampMs: 100)
    XCTAssertEqual(landmarks[Skeleton.offsetVisibility], 0.1)
  }

  func testAHandoverKeepsTheVisibilityWhereItWas() throws {
    let before = try XCTUnwrap(run([0.2, 0.95, 0.95, 0.95], intervalMs: 100).last)
    clock.handOver()

    var landmarks = frame(0.6)
    clock.apply(to: &landmarks, timestampMs: 400)
    XCTAssertEqual(landmarks[Skeleton.offsetVisibility], before)

    var next = frame(0.1 * 0.95 + 0.9 * 0.6)
    clock.apply(to: &next, timestampMs: 500)
    let expected = before + VisibilityClock.weight(elapsedMs: 100) * (0.95 - before)
    XCTAssertEqual(next[Skeleton.offsetVisibility], expected, accuracy: 1e-4)
  }
}
