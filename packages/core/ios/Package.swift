// swift-tools-version: 5.9
import PackageDescription

/// A test harness for the sources that need no UIKit, AVFoundation, MediaPipe or Expo. The podspec
/// is what ships; package.json's `files` leaves this manifest out.
let package = Package(
  name: "PoseEngine",
  platforms: [.macOS(.v12)],
  targets: [
    .target(
      name: "PoseEngine",
      path: ".",
      exclude: [
        "Tests",
        "view/OverlayParsing.swift",
        "view/OverlayView.swift",
        "view/OverlayRenderer.swift",
        "view/OverlayRenderer+Angles.swift",
        "view/PoseCameraView.swift",
        "view/PoseCameraView+Capture.swift",
        "view/PoseCameraView+Delivery.swift",
        "view/PoseCameraView+Frames.swift",
        "view/PoseCameraView+Lifecycle.swift",
        "view/PoseCameraView+Props.swift",
        "view/PoseCameraView+Ref.swift",
        "view/PoseCameraView+Session.swift",
        "camera",
        "detector/PoseDetector.swift",
        "detector/StaticDetection.swift",
        "detector/StillImage.swift",
        "detector/VideoFrameSampler.swift",
        "detector/FileDetector.swift",
        "detector/UprightFrames.swift",
        "export/ExportOptions.swift",
        "export/PoseExport.swift",
        "export/VideoExporter.swift",
        "export/VideoExporter+Encode.swift",
        "PoseDetectionModule.swift",
        "Permissions.swift",
        "ReactNativePoseDetection.podspec"
      ],
      sources: [
        "Monotonic.swift",
        "CancelRegistry.swift",
        "Guarded.swift",
        "ErrorCode.swift",
        "Skeleton.swift",
        "PoseLog.swift",
        "JSCoercion.swift",
        "engine",
        "performance",
        "view/OverlayProjection.swift",
        "export/ExportCanvas.swift",
        "detector/StaticOptions.swift"
      ]
    ),
    .testTarget(
      name: "PoseEngineTests",
      dependencies: ["PoseEngine"],
      path: "Tests/PoseEngineTests"
    )
  ]
)
