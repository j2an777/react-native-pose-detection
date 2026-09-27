import CoreGraphics
import XCTest

@testable import PoseEngine

final class ExportCanvasTests: XCTestCase {

  func testACapSmallerThanTheSourceScalesBothAxesTogether() {
    let canvas = exportCanvasSize(display: CGSize(width: 3840, height: 2160), maxSize: 1920)
    XCTAssertEqual(canvas.width, 1920)
    XCTAssertEqual(canvas.height, 1080)
  }

  func testTheAspectRatioSurvivesTheCap() {
    let display = CGSize(width: 1080, height: 1920)
    let canvas = exportCanvasSize(display: display, maxSize: 720)
    XCTAssertEqual(canvas.width / canvas.height, display.width / display.height, accuracy: 0.01)
  }

  func testASourceSmallerThanTheCapIsLeftAlone() {
    let canvas = exportCanvasSize(display: CGSize(width: 640, height: 480), maxSize: 1920)
    XCTAssertEqual(canvas, CGSize(width: 640, height: 480))
  }

  func testZeroMeansTheSourcesOwnSize() {
    let canvas = exportCanvasSize(display: CGSize(width: 3840, height: 2160), maxSize: 0)
    XCTAssertEqual(canvas, CGSize(width: 3840, height: 2160))
  }

  func testBothAxesComeBackEven() {
    for width in 1...64 {
      for height in [1, 3, 17, 99] {
        let canvas = exportCanvasSize(display: CGSize(width: width, height: height), maxSize: 0)
        XCTAssertEqual(canvas.width.truncatingRemainder(dividingBy: 2), 0, "width \(width)")
        XCTAssertEqual(canvas.height.truncatingRemainder(dividingBy: 2), 0, "height \(height)")
        XCTAssertGreaterThanOrEqual(canvas.width, 2)
        XCTAssertGreaterThanOrEqual(canvas.height, 2)
      }
    }
  }

  func testADegenerateSourceStillProducesAnEncodableSize() {
    XCTAssertEqual(exportCanvasSize(display: .zero, maxSize: 1920), CGSize(width: 2, height: 2))
    XCTAssertEqual(
      exportCanvasSize(display: CGSize(width: 100, height: 0), maxSize: 1920),
      CGSize(width: 2, height: 2)
    )
  }

  func testThePortraitLongEdgeIsTheOneThatIsCapped() {
    let canvas = exportCanvasSize(display: CGSize(width: 1080, height: 1920), maxSize: 960)
    XCTAssertEqual(canvas.height, 960)
    XCTAssertEqual(canvas.width, 540)
  }
}

final class OverlayScaleTests: XCTestCase {

  func testAFullHdCanvasScalesRoughlyLikeAPhoneScreen() {
    let scale = overlayScale(canvas: CGSize(width: 1080, height: 1920))
    XCTAssertEqual(scale, 2.7, accuracy: 0.01)
  }

  func testOrientationDoesNotChangeTheScale() {
    XCTAssertEqual(
      overlayScale(canvas: CGSize(width: 1920, height: 1080)),
      overlayScale(canvas: CGSize(width: 1080, height: 1920))
    )
  }

  func testASmallCanvasNeverThinsTheSkeleton() {
    XCTAssertEqual(overlayScale(canvas: CGSize(width: 240, height: 320)), 1)
    XCTAssertEqual(overlayScale(canvas: .zero), 1)
  }

  func testAFourKCanvasScalesUpRatherThanStayingHairThin() {
    XCTAssertEqual(overlayScale(canvas: CGSize(width: 3840, height: 2160)), 5.4, accuracy: 0.01)
  }
}
