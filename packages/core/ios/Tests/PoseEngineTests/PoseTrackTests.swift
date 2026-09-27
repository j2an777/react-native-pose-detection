import XCTest
@testable import PoseEngine

final class PoseTrackTests: XCTestCase {
  private let body = PoseBox(minX: 0.3, minY: 0.1, maxX: 0.6, maxY: 0.9)
  private let someoneElse = PoseBox(minX: 0.7, minY: 0.1, maxX: 0.95, maxY: 0.9)

  func testTheGapFollowsTheRateAndNeverDropsUnder200Ms() {
    XCTAssertEqual(Continuity.maxGapMs(fps: 30), 200)
    XCTAssertEqual(Continuity.maxGapMs(fps: 10), 250)
    XCTAssertEqual(Continuity.maxGapMs(fps: 2), 1_250)
    XCTAssertEqual(Continuity.maxGapMs(fps: 0), 200, "an unknown rate falls back to the floor")
  }

  func testTheFirstFrameStartsTheTrack() {
    var track = PoseTrack(sampleFps: 10)
    XCTAssertNil(track.advance(body, atMs: 0))
    XCTAssertEqual(track.advance(body, atMs: 100) ?? 0, 0.1, accuracy: 1e-6)
  }

  func testTheRealIntervalIsReportedNotTheNominalOne() {
    var track = PoseTrack(sampleFps: 10)
    _ = track.advance(body, atMs: 0)
    XCTAssertEqual(track.advance(body, atMs: 125) ?? 0, 0.125, accuracy: 1e-6, "a 24 fps clip sampled at 10")
  }

  func testAGapLongerThanTwoAndAHalfSamplesStartsOver() {
    var track = PoseTrack(sampleFps: 10)
    _ = track.advance(body, atMs: 0)
    XCTAssertNil(track.advance(body, atMs: 400))
  }

  func testAFrameWithNobodyInItStartsOver() {
    var track = PoseTrack(sampleFps: 10)
    _ = track.advance(body, atMs: 0)
    track.lose()
    XCTAssertNil(track.advance(body, atMs: 100))
  }

  func testSomebodyElseStartsOver() {
    var track = PoseTrack(sampleFps: 10)
    _ = track.advance(body, atMs: 0)
    XCTAssertNil(track.advance(someoneElse, atMs: 100))
  }

  func testVelocityIsUnknownOnTheFirstFrameAndMeasuredAfter() {
    var track = PoseTrack(sampleFps: 10)
    let first = track.velocity(comX: 0.5, comY: 0.5, elapsed: track.advance(body, atMs: 0))
    XCTAssertTrue(first.x.isNaN && first.y.isNaN, "unknown, not zero")

    let second = track.velocity(comX: 0.6, comY: 0.4, elapsed: track.advance(body, atMs: 100))
    XCTAssertEqual(second.x, 1, accuracy: 1e-5)
    XCTAssertEqual(second.y, -1, accuracy: 1e-5)
  }

  func testVelocityIsNotMeasuredAcrossALostPose() {
    var track = PoseTrack(sampleFps: 10)
    _ = track.velocity(comX: 0.5, comY: 0.5, elapsed: track.advance(body, atMs: 0))
    track.lose()
    _ = track.velocity(comX: 0.9, comY: 0.9, elapsed: track.advance(body, atMs: 100))
    let after = track.velocity(comX: 0.9, comY: 0.9, elapsed: track.advance(body, atMs: 200))
    XCTAssertEqual(after.x, 0, accuracy: 1e-6, "measured from the frame after the gap")
  }
}
