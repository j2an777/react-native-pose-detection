import XCTest
@testable import PoseEngine

/// The level `setLogLevel()` sets, and what a camera's `logLevel` prop does on top of it.
final class PoseLogTests: XCTestCase {
  private let camera = NSObject()
  private let other = NSObject()

  override func tearDown() {
    PoseLog.raise(camera, to: nil)
    PoseLog.raise(other, to: nil)
    PoseLog.setLevel(.off)
    super.tearDown()
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
}
