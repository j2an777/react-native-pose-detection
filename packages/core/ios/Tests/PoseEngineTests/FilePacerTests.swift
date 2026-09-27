import XCTest
@testable import PoseEngine

private final class Bench {
  var now: Int64 = 0
  var heat = ThermalState.nominal
  var slept: Int64 = 0
}

final class FilePacerTests: XCTestCase {
  private let bench = Bench()

  private var now: Int64 {
    get { bench.now }
    set { bench.now = newValue }
  }

  private var heat: ThermalState {
    get { bench.heat }
    set { bench.heat = newValue }
  }

  private var slept: Int64 {
    bench.slept
  }

  private func pacer() -> FilePacer {
    let bench = self.bench
    return FilePacer(
      readThermal: { bench.heat },
      nowMs: { bench.now },
      sleepMs: { duration in
        bench.slept += duration
        bench.now += duration
      }
    )
  }

  func testNoRestUpToFair() {
    let pacer = pacer()
    heat = .fair
    now += 100
    XCTAssertTrue(pacer.rest(isCancelled: { false }))
    XCTAssertEqual(slept, 0)
  }

  func testSeriousRestsAsLongAsTheWorkTook() {
    let pacer = pacer()
    heat = .serious
    now += 120
    XCTAssertTrue(pacer.rest(isCancelled: { false }))
    XCTAssertEqual(slept, 120, "half speed")
  }

  func testCriticalWaitsUntilTheHeatHasBeenLowerFor30Seconds() {
    let pacer = pacer()
    heat = .critical
    var polls = 0
    let done = pacer.rest(isCancelled: {
      polls += 1
      // Cools five seconds into the pause; hysteresis holds it for thirty more.
      if self.now >= 5_000 { self.heat = .fair }
      return false
    })
    XCTAssertTrue(done)
    XCTAssertGreaterThanOrEqual(now, 35_000)
    XCTAssertLessThan(now, 37_000)
    XCTAssertEqual(pacer.state, .fair)
    XCTAssertGreaterThan(polls, 0)
  }

  func testACancelDuringAPauseIsAnsweredWithinOnePoll() {
    let pacer = pacer()
    heat = .critical
    let done = pacer.rest(isCancelled: { self.now >= 1_000 })
    XCTAssertFalse(done)
    XCTAssertLessThanOrEqual(now, 1_000 + FilePacer.pollMs)
  }

  func testHeatIsReadAtMostOnceASecond() {
    var reads = 0
    let bench = self.bench
    let pacer = FilePacer(
      readThermal: {
        reads += 1
        return .nominal
      },
      nowMs: { bench.now },
      sleepMs: { _ in }
    )
    for _ in 0..<10 {
      now += 50
      _ = pacer.rest(isCancelled: { false })
    }
    XCTAssertEqual(reads, 1)
  }
}
