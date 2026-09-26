import XCTest
@testable import PoseEngine

final class JSCoercionTests: XCTestCase {
  func testAFiniteNumberComesThroughWhateverTypeItCrossedAs() {
    XCTAssertEqual(JS.finite(3), 3)
    XCTAssertEqual(JS.finite(2.5), 2.5)
    XCTAssertEqual(JS.finite(NSNumber(value: 7)), 7)
  }

  func testNaNAndBothInfinitiesAreRefused() {
    XCTAssertNil(JS.finite(Double.nan))
    XCTAssertNil(JS.finite(Double.infinity))
    XCTAssertNil(JS.finite(-Double.infinity))
    XCTAssertNil(JS.finite(NSNumber(value: Double.nan)))
  }

  func testABooleanIsNotANumberEvenThoughItBridgesToOne() {
    XCTAssertNil(JS.finite(true))
    XCTAssertNil(JS.int(true))
  }

  func testIntegersTruncateTowardZeroLikeTheInitializerTheyReplace() {
    XCTAssertEqual(JS.int(40.7), 40)
    XCTAssertEqual(JS.int(-40.7), -40)
    XCTAssertEqual(JS.int64(1_500), 1_500)
  }

  func testIntegersThatWouldTrapAreRefusedOrSaturatedInstead() {
    XCTAssertNil(JS.int(Double.nan))
    XCTAssertNil(JS.int64(Double.infinity))
    XCTAssertEqual(JS.int64(1e300), .max)
    XCTAssertEqual(JS.int64(-1e300), .min)
    // The first double past Int64.max, which `Int64(_:)` rejects with a trap.
    XCTAssertEqual(JS.int64(9_223_372_036_854_775_808.0), .max)
  }

  func testAbsentAndNonNumericValuesAreNil() {
    XCTAssertNil(JS.int(nil))
    XCTAssertNil(JS.int(NSNull()))
    XCTAssertNil(JS.int("12"))
  }
}
