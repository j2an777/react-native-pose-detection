import XCTest
@testable import PoseEngine

final class StaticOptionsTests: XCTestCase {
  func testConfidenceFollowsMaxPosesUnlessChosen() {
    XCTAssertEqual(StaticOptions.forImage(nil).minConfidence, 0.5)
    XCTAssertEqual(StaticOptions.forImage(["maxPoses": 2]).minConfidence, 0.3, "a second person needs a lower bar")
    XCTAssertEqual(StaticOptions.forVideo(["maxPoses": 3]).minConfidence, 0.3)
    XCTAssertEqual(StaticOptions.forImage(["maxPoses": 3, "minConfidence": 0.45]).minConfidence, 0.45)
  }

  func testConfidenceIsClampedToTheDocumentedRange() {
    XCTAssertEqual(StaticOptions.forImage(["minConfidence": 0.01]).minConfidence, 0.1)
    XCTAssertEqual(StaticOptions.forImage(["minConfidence": 1.0]).minConfidence, 1.0)
    XCTAssertEqual(StaticOptions.forImage(["minConfidence": 7]).minConfidence, 1.0)
    XCTAssertEqual(StaticOptions.forImage(["minConfidence": Double.nan]).minConfidence, 0.5, "NaN is no choice at all")
  }

  func testMaxPosesStaysBetweenOneAndFive() {
    XCTAssertEqual(StaticOptions.forImage(["maxPoses": 0]).maxPoses, 1)
    XCTAssertEqual(StaticOptions.forImage(["maxPoses": 40]).maxPoses, 5)
  }

  func testAVideoSamplesAtTenAFrameUnlessToldAndNeverBelowOne() {
    XCTAssertEqual(StaticOptions.forVideo(nil).fps, 10)
    XCTAssertEqual(StaticOptions.forVideo(["fps": 0]).fps, 1)
    XCTAssertEqual(StaticOptions.forVideo(["fps": 120]).fps, 120, "slow motion is sampled as asked")
  }

  func testSmoothingIsWhateverJavaScriptResolvedAndOffForAPhoto() {
    XCTAssertFalse(StaticOptions.forVideo(nil).smoothing, "absent is off: one pose is smoothed inside MediaPipe")
    XCTAssertTrue(StaticOptions.forVideo(["smoothing": true]).smoothing)
    XCTAssertFalse(StaticOptions.forImage(["smoothing": true]).smoothing, "a single frame has nothing to smooth")
  }

  func testATrimRangeIsNeverNegative() {
    XCTAssertEqual(StaticOptions.forVideo(["startMs": -500]).startMs, 0)
    XCTAssertEqual(StaticOptions.forVideo(nil).endMs, -1, "no end means the end of the clip")
  }
}
