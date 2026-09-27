import AVFoundation

/// iOS prompts once, so only `notDetermined` can ask again; a refusal is final until Settings.
func permissionResult(_ status: AVAuthorizationStatus) -> [String: Any] {
  switch status {
  case .authorized:
    return ["status": "granted", "canAskAgain": false]
  case .notDetermined:
    return ["status": "undetermined", "canAskAgain": true]
  default:
    return ["status": "denied", "canAskAgain": false]
  }
}

func currentCameraPermission() -> [String: Any] {
  return permissionResult(AVCaptureDevice.authorizationStatus(for: .video))
}
