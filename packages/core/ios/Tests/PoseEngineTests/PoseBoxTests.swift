import XCTest
@testable import PoseEngine

final class PoseBoxTests: XCTestCase {
  func testTheSameBodyMovingOneFrameOverlapsWell() {
    let before = PoseBox(minX: 0.30, minY: 0.10, maxX: 0.60, maxY: 0.90)
    let after = PoseBox(minX: 0.32, minY: 0.11, maxX: 0.62, maxY: 0.91)
    XCTAssertGreaterThan(after.overlap(before), 0.8)
  }

  func testSomeoneElseAcrossTheRoomDoesNotOverlapAtAll() {
    let left = PoseBox(minX: 0.05, minY: 0.2, maxX: 0.35, maxY: 0.95)
    let right = PoseBox(minX: 0.60, minY: 0.2, maxX: 0.90, maxY: 0.95)
    XCTAssertEqual(left.overlap(right), 0)
    XCTAssertLessThan(left.overlap(right), PoseBox.sameBodyOverlap)
  }

  func testTheBoxIsReadFromTheLandmarkBuffer() {
    var landmarks = [Float](repeating: 0.5, count: Skeleton.landmarkCount * Skeleton.landmarkStride)
    landmarks[Skeleton.nose * Skeleton.landmarkStride + Skeleton.offsetY] = 0.1
    landmarks[Skeleton.leftAnkle * Skeleton.landmarkStride + Skeleton.offsetY] = 0.9
    landmarks[Skeleton.leftWrist * Skeleton.landmarkStride + Skeleton.offsetX] = 0.2
    let box = PoseBox(landmarks)
    XCTAssertEqual(box, PoseBox(minX: 0.2, minY: 0.1, maxX: 0.5, maxY: 0.9))
  }

  func testThePrimaryIsTheLargestBody() {
    let near = PoseBox(minX: 0.5, minY: 0.1, maxX: 0.9, maxY: 0.95)
    let far = PoseBox(minX: 0.1, minY: 0.4, maxX: 0.2, maxY: 0.6)
    XCTAssertEqual(PoseBox.primary([far, near]), 1, "detection order means nothing about the subject")
  }

  func testATieGoesToWhoeverIsNearerTheCentre() {
    let edge = PoseBox(minX: 0.0, minY: 0.3, maxX: 0.2, maxY: 0.7)
    let centre = PoseBox(minX: 0.4, minY: 0.3, maxX: 0.6, maxY: 0.7)
    XCTAssertEqual(PoseBox.primary([edge, centre]), 1)
  }
}
