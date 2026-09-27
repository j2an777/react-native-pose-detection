import XCTest

@testable import PoseEngine

final class CancelRegistryTests: XCTestCase {

  func testATaskIsNotCancelledUntilItIsAskedToBe() {
    let registry = CancelRegistry()
    registry.begin(1)
    XCTAssertFalse(registry.isCancelled(1))
    registry.cancel(1)
    XCTAssertTrue(registry.isCancelled(1))
  }

  func testCancellingATaskThatNeverStartedIsForgottenRatherThanKept() {
    let registry = CancelRegistry()
    registry.cancel(99)
    XCTAssertFalse(registry.isCancelled(99))

    // And it must not poison the id if it is used later.
    registry.begin(99)
    XCTAssertFalse(registry.isCancelled(99))
  }

  func testACancelWhileQueuedIsKeptWhenTheJobStarts() {
    let registry = CancelRegistry()
    registry.begin(5)
    registry.cancel(5)
    registry.begin(5)
    XCTAssertTrue(registry.isCancelled(5))
    registry.end(5)
    XCTAssertFalse(registry.isCancelled(5))
  }

  func testEndingATaskClearsItsCancellation() {
    let registry = CancelRegistry()
    registry.begin(7)
    registry.cancel(7)
    registry.end(7)
    XCTAssertFalse(registry.isCancelled(7))
  }

  func testTasksDoNotCancelEachOther() {
    let registry = CancelRegistry()
    registry.begin(1)
    registry.begin(2)
    registry.cancel(2)
    XCTAssertFalse(registry.isCancelled(1))
    XCTAssertTrue(registry.isCancelled(2))
  }

  func testConcurrentBeginsAndCancelsDoNotCorruptTheRegistry() {
    let registry = CancelRegistry()
    let group = DispatchGroup()
    for taskId in 0..<200 {
      DispatchQueue.global().async(group: group) {
        registry.begin(taskId)
        registry.cancel(taskId)
        XCTAssertTrue(registry.isCancelled(taskId))
        registry.end(taskId)
      }
    }
    group.wait()
    for taskId in 0..<200 {
      XCTAssertFalse(registry.isCancelled(taskId))
    }
  }
}
