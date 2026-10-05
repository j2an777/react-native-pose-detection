import AVFoundation

/// What a finished recording hands back to JavaScript.
struct RecordedVideo {
  let uri: String
  let durationMs: Int
  let size: Int
  let hasAudio: Bool

  var payload: [String: Any] {
    return ["uri": uri, "durationMs": durationMs, "size": size, "hasAudio": hasAudio]
  }
}

/// One recording, one delegate.
///
/// `AVCaptureMovieFileOutput` holds its delegate weakly, so a delegate that was only a local would
/// be gone before the file finished writing. This keeps itself alive until it settles, the same way
/// `PhotoCapture` does.
final class MovieCapture: NSObject, AVCaptureFileOutputRecordingDelegate {
  private let hasAudio: Bool
  private var settle: ((Result<RecordedVideo, Error>) -> Void)?
  private var retained: MovieCapture?

  private init(hasAudio: Bool, settle: @escaping (Result<RecordedVideo, Error>) -> Void) {
    self.hasAudio = hasAudio
    self.settle = settle
    super.init()
    retained = self
  }

  /// Session queue. `settle` runs on main, exactly once.
  static func record(
    with output: AVCaptureMovieFileOutput,
    to url: URL,
    hasAudio: Bool,
    settle: @escaping (Result<RecordedVideo, Error>) -> Void
  ) -> MovieCapture {
    let capture = MovieCapture(hasAudio: hasAudio, settle: settle)
    output.startRecording(to: url, recordingDelegate: capture)
    return capture
  }

  func fileOutput(
    _ output: AVCaptureFileOutput,
    didFinishRecordingTo outputFileURL: URL,
    from connections: [AVCaptureConnection],
    error: Error?
  ) {
    defer { retained = nil }
    guard let settle = settle else { return }
    self.settle = nil

    // A partial clip reported as a success is worse than none, so a failure takes the file with it.
    if let error = error {
      try? FileManager.default.removeItem(at: outputFileURL)
      DispatchQueue.main.async { settle(.failure(error)) }
      return
    }

    // Asked of the written file rather than timed in Swift: the encoder decides where the last
    // frame lands, and a stopwatch would be off by whatever the flush took.
    let asset = AVURLAsset(url: outputFileURL)
    let seconds = CMTimeGetSeconds(asset.duration)
    let bytes = (try? FileManager.default.attributesOfItem(atPath: outputFileURL.path)[.size]) as? Int ?? 0

    let video = RecordedVideo(
      uri: outputFileURL.absoluteString,
      durationMs: seconds.isFinite ? Int(seconds * 1000) : 0,
      size: bytes,
      hasAudio: hasAudio
    )
    DispatchQueue.main.async { settle(.success(video)) }
  }
}

/// Where recordings land. The temporary directory, like stills: this package never asks for a
/// photo-library permission, and nothing prunes these — the app owns the cleanup.
enum MovieFiles {
  static func create() -> URL {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent("pose-videos", isDirectory: true)
    try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    return directory.appendingPathComponent("\(UUID().uuidString).mov")
  }
}
