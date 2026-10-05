import AVFoundation

struct RecordingError: LocalizedError {
  let code: String
  let message: String
  init(_ code: String, _ message: String) {
    self.code = code
    self.message = message
  }
  var errorDescription: String? { return message }
}

/// Recording, added to the running session rather than replacing it.
///
/// `AVCaptureMovieFileOutput` next to the `AVCaptureVideoDataOutput` the detector lives on is a
/// combination some devices refuse — `canAddOutput` is the only honest way to ask. A refusal is
/// reported as `RECORDING_UNAVAILABLE` rather than shutting the analysis down to make room:
/// a recording with no detection is not what this package is for.
///
/// The upgrade path, when that refusal turns out to be common on real hardware, is an
/// `AVAssetWriter` fed from the sample buffers the detector already receives. That writes the
/// movie without a second output at all, at the cost of owning the encoder settings here.
extension CameraSource {
  // MARK: - Main thread only

  var isRecording: Bool { movieOutput?.isRecording == true }

  /// `settle` runs on main, exactly once, when the file has finished writing — not when
  /// `stopRecording()` is called. The encoder decides when the last frame lands.
  func startRecording(
    audio: Bool,
    settle: @escaping (Result<RecordedVideo, Error>) -> Void
  ) throws {
    guard isBound else { throw RecordingError("RECORDING_FAILED", "the camera is not running") }
    guard !isRecording else { throw RecordingError("RECORDING_IN_PROGRESS", "a recording is already running") }
    if audio, AVCaptureDevice.authorizationStatus(for: .audio) != .authorized {
      throw RecordingError("MICROPHONE_DENIED", "audio was asked for but the microphone permission is not granted")
    }

    let url = MovieFiles.create()
    let current = token.value

    sessionQueue.async { [weak self] in
      guard let self = self, self.isCurrent(current), let session = self.session else {
        DispatchQueue.main.async {
          settle(.failure(RecordingError("RECORDING_FAILED", "the camera stopped before the recording started")))
        }
        return
      }
      do {
        let output = try self.attachMovieOutput(to: session, audio: audio)
        self.recorder = MovieCapture.record(with: output, to: url, hasAudio: audio) { [weak self] result in
          // Main: `settle` is documented to run there, and the teardown touches session state.
          self?.detachMovieOutput()
          settle(result)
        }
      } catch {
        DispatchQueue.main.async { settle(.failure(error)) }
      }
    }
  }

  func stopRecording() throws {
    guard let output = movieOutput, output.isRecording else {
      throw RecordingError("NOT_RECORDING", "no recording is running")
    }
    output.stopRecording()
  }

  // MARK: - Session queue only

  private func attachMovieOutput(to session: AVCaptureSession, audio: Bool) throws -> AVCaptureMovieFileOutput {
    let output = AVCaptureMovieFileOutput()
    session.beginConfiguration()
    defer { session.commitConfiguration() }

    guard session.canAddOutput(output) else {
      throw RecordingError("RECORDING_UNAVAILABLE", "this device will not record while detection is running")
    }
    session.addOutput(output)
    movieOutput = output

    if audio {
      // Added per recording, not at start: holding the microphone open shows the recording
      // indicator the whole time the camera is up, which a measuring app has no business doing.
      if let microphone = AVCaptureDevice.default(for: .audio),
        let micInput = try? AVCaptureDeviceInput(device: microphone),
        session.canAddInput(micInput) {
        session.addInput(micInput)
        audioInput = micInput
      } else {
        PoseLog.warn(.camera, "the microphone could not be added; recording without audio")
      }
    }
    return output
  }

  /// Takes the movie output and the microphone back out, so the session returns to what it was.
  private func detachMovieOutput() {
    sessionQueue.async { [weak self] in
      guard let self = self, let session = self.session else { return }
      session.beginConfiguration()
      if let output = self.movieOutput { session.removeOutput(output) }
      if let micInput = self.audioInput { session.removeInput(micInput) }
      session.commitConfiguration()
      self.movieOutput = nil
      self.audioInput = nil
      self.recorder = nil
    }
  }
}
