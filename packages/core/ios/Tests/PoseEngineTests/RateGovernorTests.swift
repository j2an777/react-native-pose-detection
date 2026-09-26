import XCTest
@testable import PoseEngine

/**
 The rate model from `guides/performance.md`. The first test is its worked table, so a change that
 moves one of those numbers has to come here and say so.
 */
final class RateGovernorTests: XCTestCase {
  private func decide(
    profile: Profile = .auto,
    policy: ThermalPolicy = .adaptive,
    thermal: ThermalState = .nominal,
    lowPower: Bool = false,
    camera: Int = 30,
    p50: Float = 0,
    fps: Int? = nil
  ) -> RateDecision {
    return RateGovernor.decide(RateRequest(
      profile: profile,
      policy: policy,
      thermal: thermal,
      lowPower: lowPower,
      cameraFps: camera,
      p50Ms: p50,
      requestedFps: fps
    ))
  }

  private struct Row {
    let p50: Float
    let nominal: Int
    let fair: Int
    let serious: Int
  }

  func testTheWorkedTableHolds() {
    let rows = [
      Row(p50: 16, nominal: 30, fair: 30, serious: 15),
      Row(p50: 20, nominal: 30, fair: 30, serious: 15),
      Row(p50: 25, nominal: 30, fair: 28, serious: 15),
      Row(p50: 30, nominal: 28, fair: 23, serious: 15),
      Row(p50: 40, nominal: 21, fair: 17, serious: 12),
      Row(p50: 60, nominal: 14, fair: 11, serious: 8)
    ]
    for row in rows {
      XCTAssertEqual(decide(p50: row.p50).fps, row.nominal, "nominal at \(row.p50)ms")
      XCTAssertEqual(decide(thermal: .fair, p50: row.p50).fps, row.fair, "fair at \(row.p50)ms")
      XCTAssertEqual(decide(thermal: .serious, p50: row.p50).fps, row.serious, "serious at \(row.p50)ms")
    }
  }

  func testAnUnmeasuredDeviceRunsAtTheCamerasRateRatherThanAGuess() {
    XCTAssertEqual(decide(), RateDecision(fps: 30, limitedBy: .camera))
  }

  func testTheReasonNamesTheConstraintThatBound() {
    XCTAssertEqual(decide(p50: 16).limitedBy, .camera)
    XCTAssertEqual(decide(p50: 30).limitedBy, .device)
    XCTAssertEqual(decide(thermal: .fair, p50: 30).limitedBy, .thermal)
    XCTAssertEqual(decide(thermal: .serious, p50: 16).limitedBy, .thermal)
    XCTAssertEqual(decide(profile: .balanced, p50: 16).limitedBy, .profile)
  }

  func testCriticalHeatPausesDetection() {
    let paused = decide(thermal: .critical)
    XCTAssertTrue(paused.detectionPaused)
    XCTAssertEqual(paused.limitedBy, .thermal)
  }

  func testTheCameraIsTheCeilingEvenForAnExplicitTarget() {
    XCTAssertEqual(decide(fps: 60), RateDecision(fps: 30, limitedBy: .camera))
    XCTAssertEqual(decide(camera: 24, p50: 10), RateDecision(fps: 24, limitedBy: .camera))
  }

  func testAnExplicitTargetIsCappedAtWhatTheDeviceCanFinish() {
    XCTAssertEqual(decide(p50: 16, fps: 24), RateDecision(fps: 24, limitedBy: .target))
    // 1000 / 50 = 20: asking for 30 would only queue frames behind each other.
    XCTAssertEqual(decide(p50: 50, fps: 30), RateDecision(fps: 20, limitedBy: .device))
  }

  func testFairHeatLeavesAnExplicitTargetAloneAndSeriousHeatHalvesIt() {
    XCTAssertEqual(decide(thermal: .fair, p50: 16, fps: 30).fps, 30)
    XCTAssertEqual(decide(thermal: .serious, p50: 16, fps: 30), RateDecision(fps: 15, limitedBy: .thermal))
  }

  func testTheFloorHoldsASlowDeviceUpButNotAHotOne() {
    XCTAssertEqual(decide(p50: 200), RateDecision(fps: RateGovernor.floorFps, limitedBy: .device))
    XCTAssertEqual(decide(thermal: .fair, p50: 200), RateDecision(fps: 3, limitedBy: .thermal))
  }

  func testLowPowerCapsOnlyTheGovernedRate() {
    XCTAssertEqual(decide(lowPower: true, p50: 16), RateDecision(fps: 24, limitedBy: .lowPower))
    XCTAssertEqual(decide(lowPower: true, p50: 16, fps: 30).fps, 30, "an explicit target has already decided")
    XCTAssertEqual(decide(profile: .unrestricted, lowPower: true, p50: 16).fps, 30)
  }

  func testProfilesAreRowsOfTheSameModel() {
    XCTAssertEqual(decide(profile: .balanced, p50: 16).fps, 24)
    XCTAssertEqual(decide(profile: .efficient, p50: 16).fps, 15)
    XCTAssertEqual(decide(profile: .quality, p50: 30).fps, 30, "0.95 of a 30ms device is still 31")
    XCTAssertEqual(decide(profile: .unrestricted, p50: 30).fps, 30)
    XCTAssertEqual(
      decide(profile: .efficient, thermal: .fair, p50: 16),
      RateDecision(fps: 11, limitedBy: .thermal),
      "efficient is the one profile that treats warmth as a reason"
    )
  }

  func testThePolicyAndTheProfileDecideWhichHeatCounts() {
    XCTAssertEqual(decide(profile: .unrestricted, thermal: .serious, p50: 16).fps, 30)
    XCTAssertTrue(decide(profile: .unrestricted, thermal: .critical).detectionPaused)
    XCTAssertEqual(decide(policy: .criticalOnly, thermal: .serious, p50: 16).fps, 30)
    XCTAssertTrue(decide(policy: .criticalOnly, thermal: .critical).detectionPaused)
    XCTAssertFalse(decide(policy: .off, thermal: .critical).detectionPaused)
  }

  func testAutoPreviewFollowsMemoryAndNeverOpensAt480p() {
    func auto(_ memory: Float) -> CameraGeometry {
      GeometryResolver.resolve(profile: .auto, requestedPreview: "auto", requestedAnalysis: "auto", memoryGiB: memory)
    }
    XCTAssertEqual(auto(5.6), CameraGeometry(preview: "1080p", analysis: "480p"), "a phone sold as 6 GB")
    XCTAssertEqual(auto(3.6).preview, "720p")
    XCTAssertEqual(auto(1.9).preview, "720p")
  }

  func testExplicitPresetsWinAndProfilesSetTheirOwn() {
    XCTAssertEqual(
      GeometryResolver.resolve(profile: .efficient, requestedPreview: "auto", requestedAnalysis: "auto", memoryGiB: 8),
      CameraGeometry(preview: "720p", analysis: "360p")
    )
    let quality = GeometryResolver.resolve(
      profile: .quality, requestedPreview: "auto", requestedAnalysis: "auto", memoryGiB: 2
    )
    XCTAssertEqual(quality.preview, "1080p")
    XCTAssertEqual(
      GeometryResolver.resolve(profile: .efficient, requestedPreview: "1080p", requestedAnalysis: "720p", memoryGiB: 2),
      CameraGeometry(preview: "1080p", analysis: "720p")
    )
  }

  func testIdleComesInTwoStepsAndUnrestrictedHasNone() {
    let idle = IdleRates(first: 12, deep: 5)
    XCTAssertNil(idle.rate(sinceLastPoseMs: 1_500))
    XCTAssertEqual(idle.rate(sinceLastPoseMs: 2_500), 12)
    XCTAssertEqual(idle.rate(sinceLastPoseMs: 25_000), 5)
    XCTAssertNil(Budgets.of(.unrestricted).idle)
  }

  func testHeatIsAdoptedAtOnceAndCoolingOnlyOnceItHasHeld() {
    var heat = ThermalHysteresis()
    XCTAssertTrue(heat.update(.serious, nowMs: 0))
    XCTAssertEqual(heat.state, .serious)

    XCTAssertFalse(heat.update(.fair, nowMs: 1_000))
    XCTAssertFalse(heat.update(.nominal, nowMs: 20_000))
    XCTAssertEqual(heat.state, .serious, "cooler readings are not trusted yet")

    XCTAssertTrue(heat.update(.nominal, nowMs: 31_000))
    XCTAssertEqual(heat.state, .fair, "the warmest reading seen while cooling")
  }

  func testAReadingBackAtTheCurrentLevelRestartsTheCoolingClock() {
    var heat = ThermalHysteresis()
    _ = heat.update(.fair, nowMs: 0)
    _ = heat.update(.nominal, nowMs: 1_000)
    XCTAssertFalse(heat.update(.fair, nowMs: 20_000))
    XCTAssertFalse(heat.update(.nominal, nowMs: 32_000))
    XCTAssertTrue(heat.update(.nominal, nowMs: 62_000))
    XCTAssertEqual(heat.state, .nominal)
  }
}
