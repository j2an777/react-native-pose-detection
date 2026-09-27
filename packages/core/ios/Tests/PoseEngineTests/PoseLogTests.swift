import XCTest
@testable import PoseEngine

/// The level `setLogLevel()` sets, what a camera's `logLevel` prop does on top of it, and who hands
/// the buffered entries to JavaScript.
final class PoseLogTests: XCTestCase {
  private let camera = NSObject()
  private let other = NSObject()

  override func tearDown() {
    PoseLog.raise(camera, to: nil)
    PoseLog.raise(other, to: nil)
    PoseLog.setLevel(.off)
    PoseLog.releaseStream(camera)
    PoseLog.releaseStream(other)
    PoseLog.stopStream()
    super.tearDown()
  }

  private func buffer(_ messages: [String]) {
    PoseLog.setLevel(.info)
    for message in messages {
      PoseLog.info(.engine, message)
    }
  }

  private func messages(_ batch: [[String: Any]]?) -> [String]? {
    return batch?.compactMap { $0["message"] as? String }
  }

  func testACameraMountedWithoutTheLogLevelPropLeavesTheGlobalLevelAlone() {
    PoseLog.setLevels([.camera: .info])
    PoseLog.raise(camera, to: PoseLog.levelMask(for: nil))
    XCTAssertTrue(PoseLog.isEnabled(.info, .camera))
  }

  func testThePropRaisesTheLevelUntilItsCameraLetsGo() {
    PoseLog.setLevel(.warn)
    PoseLog.raise(camera, to: PoseLog.levelMask(for: ["triggers": "trace"]))
    XCTAssertTrue(PoseLog.isEnabled(.trace, .triggers))
    XCTAssertTrue(PoseLog.isEnabled(.warn, .camera))
    XCTAssertFalse(PoseLog.isEnabled(.info, .camera))

    PoseLog.raise(camera, to: nil)
    XCTAssertFalse(PoseLog.isEnabled(.info, .triggers))
    XCTAssertTrue(PoseLog.isEnabled(.warn, .triggers))
  }

  func testThePropNeverLowersWhatSetLogLevelAskedFor() {
    PoseLog.setLevel(.debug)
    PoseLog.raise(camera, to: PoseLog.levelMask(for: "error"))
    XCTAssertTrue(PoseLog.isEnabled(.debug, .detector))
  }

  func testTwoCamerasEachKeepTheirRaiseUntilTheyGo() {
    PoseLog.raise(camera, to: PoseLog.levelMask(for: ["camera": "debug"]))
    PoseLog.raise(other, to: PoseLog.levelMask(for: "info"))
    XCTAssertTrue(PoseLog.isEnabled(.debug, .camera))
    XCTAssertTrue(PoseLog.isEnabled(.info, .engine))

    PoseLog.raise(other, to: nil)
    XCTAssertTrue(PoseLog.isEnabled(.debug, .camera))
    XCTAssertFalse(PoseLog.isEnabled(.info, .engine))
  }

  func testSetLogLevelWithAMapChangesOnlyTheCategoriesItNames() {
    PoseLog.setLevel(.info)
    PoseLog.setLevels([.overlay: .off])
    XCTAssertFalse(PoseLog.isEnabled(.error, .overlay))
    XCTAssertTrue(PoseLog.isEnabled(.info, .calibration))
  }

  func testAMapOfUnknownCategoriesRaisesNothingAndAnythingElseIsNoRaise() {
    XCTAssertEqual(PoseLog.levelMask(for: ["nonsense": "trace"]), 0)
    XCTAssertNil(PoseLog.levelMask(for: 42))
  }

  func testWithNoCameraAttachedTheModuleHandsTheEntriesOver() {
    PoseLog.startStream()
    buffer(["a", "b"])
    XCTAssertEqual(messages(PoseLog.takeBatch(nil)), ["a", "b"])
    XCTAssertNil(PoseLog.takeBatch(nil))
  }

  func testAnAttachedCameraFlushesAndTheModuleGetsNothing() {
    PoseLog.startStream()
    PoseLog.claimStream(camera)
    buffer(["a"])
    XCTAssertNil(PoseLog.takeBatch(nil))
    XCTAssertEqual(messages(PoseLog.takeBatch(camera)), ["a"])
  }

  func testTheFirstCameraKeepsTheFlushAndTheModuleTakesItBackWhenItGoes() {
    PoseLog.startStream()
    PoseLog.claimStream(camera)
    PoseLog.claimStream(other)
    buffer(["a"])
    XCTAssertNil(PoseLog.takeBatch(other))

    PoseLog.releaseStream(camera)
    XCTAssertEqual(messages(PoseLog.takeBatch(nil)), ["a"])
  }

  func testNothingIsBufferedOrHandedOverWhileNobodyListens() {
    buffer(["a"])
    XCTAssertNil(PoseLog.takeBatch(nil))
    PoseLog.startStream()
    XCTAssertNil(PoseLog.takeBatch(nil))
  }

  func testAFullBufferOpensTheNextBatchWithHowManyWereDropped() throws {
    PoseLog.startStream()
    buffer((0..<260).map { "entry \($0)" })
    let batch = try XCTUnwrap(PoseLog.takeBatch(nil))
    XCTAssertEqual(batch.count, 257)
    XCTAssertEqual(batch.first?["level"] as? String, "warn")
    XCTAssertEqual((batch.first?["data"] as? [String: Int])?["droppedCount"], 4)
    XCTAssertEqual(batch[1]["message"] as? String, "entry 4")
    XCTAssertEqual(batch.last?["message"] as? String, "entry 259")
  }
}
