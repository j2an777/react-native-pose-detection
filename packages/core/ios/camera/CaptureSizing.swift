import AVFoundation

extension CameraSource {
  static func previewSize(for preset: String) -> CaptureSize {
    switch preset {
    case "480p": return CaptureSize(width: 640, height: 480)
    case "1080p": return CaptureSize(width: 1920, height: 1080)
    default: return CaptureSize(width: 1280, height: 720)
    }
  }

  static func preset(for size: CaptureSize) -> AVCaptureSession.Preset {
    switch size.longestSide {
    case ...640: return .vga640x480
    case ...1280: return .hd1280x720
    default: return .hd1920x1080
    }
  }

  /// Scaled from the preview, not a second capture: same aspect, and never larger than the preview.
  static func analysisSize(for preset: String, preview: CaptureSize) -> CaptureSize {
    let requested: Int
    switch preset {
    case "360p": requested = 360
    case "720p": requested = 720
    default: requested = 480
    }

    let previewShort = min(preview.width, preview.height)
    let shortSide = min(requested, previewShort)
    if shortSide < requested {
      PoseLog.debug(.camera, "analysis \(preset) clamped to the preview's \(shortSide)p")
    }

    let aspect = Double(max(preview.width, preview.height)) / Double(previewShort)
    // Even: subsampled chroma planes cannot express an odd width.
    let longSide = Int((Double(shortSide) * aspect / 2).rounded()) * 2
    return preview.width >= preview.height
      ? CaptureSize(width: longSide, height: shortSide)
      : CaptureSize(width: shortSide, height: longSide)
  }
}

struct CameraMissing: LocalizedError {
  let facing: Facing

  var errorDescription: String? {
    return "this device has no \(facing.nameForJs) camera"
  }
}

struct CameraError: LocalizedError {
  let message: String

  init(_ message: String) {
    self.message = message
  }

  var errorDescription: String? {
    return message
  }
}
