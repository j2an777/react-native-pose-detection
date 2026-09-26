import XCTest
@testable import PoseEngine

/// How a synchronous read on the JavaScript thread finds a view's frames, and what it gets when it cannot.
final class FrameStreamsTests: XCTestCase {
  private func makeStream() -> FrameStream {
    let frames = FrameRingBuffer()
    frames.setLayout(FrameShape(jointIndices: FrameShape.allJoints, worldLandmarks: false, angleJoints: []))
    return FrameStream(frames: frames) { ["fps": 30, "limitedBy": "camera"] }
  }

  func testARegisteredStreamIsFoundByItsId() {
    let streams = FrameStreams()
    let stream = makeStream()
    streams.register(stream, id: 7)
    XCTAssertTrue(streams.stream(7) === stream)
    XCTAssertEqual(streams.live(7)["fps"] as? Int, 30)
  }

  func testAnUnknownIdReadsAsAnEmptyBufferRatherThanFailing() {
    let streams = FrameStreams()
    XCTAssertEqual(streams.drain(99), WireWriter.empty())
    XCTAssertTrue(streams.live(99).isEmpty)
  }

  func testAStreamIsHeldWeaklySoAGoneViewReadsAsEmpty() throws {
    let streams = FrameStreams()
    var stream: FrameStream? = makeStream()
    streams.register(try XCTUnwrap(stream), id: 3)
    stream = nil
    XCTAssertNil(streams.stream(3))
  }

  func testUnregisteringLeavesAnotherViewThatReusedTheIdAlone() {
    let streams = FrameStreams()
    let first = makeStream()
    let second = makeStream()
    streams.register(first, id: 1)
    streams.register(second, id: 1)
    streams.unregister(first, id: 1)
    XCTAssertTrue(streams.stream(1) === second)
  }
}
