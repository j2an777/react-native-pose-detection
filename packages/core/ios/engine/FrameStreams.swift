import Foundation

/**
 The parts of one camera view that JavaScript reads synchronously: its frames and a live reading of
 its rate. Every one of them is thread-safe, which is what lets a read run on the JavaScript thread
 rather than queue behind everything else on main. See ADR 0008.
 */
final class FrameStream {
  let frames: FrameRingBuffer
  private let readLive: () -> [String: Any]
  private let readDetecting: () -> Bool

  init(
    frames: FrameRingBuffer,
    readDetecting: @escaping () -> Bool = { false },
    readLive: @escaping () -> [String: Any]
  ) {
    self.frames = frames
    self.readDetecting = readDetecting
    self.readLive = readLive
  }

  func live() -> [String: Any] {
    return readLive()
  }

  /// True while this camera runs inference, which is when a file job must stay off the GPU.
  var isDetecting: Bool {
    return readDetecting()
  }
}

/**
 Streams by the id `<PoseCamera>` gives each view.

 A view function would be simpler, but ExpoModulesCore runs every view function on the main queue,
 so each drain queued behind layout and the overlay twice per tick. A module function runs on the
 JavaScript thread that calls it; this is how it finds the view's frames without touching the view.
 Held weakly: the view owns its stream, and one that has gone reads as empty rather than stale.
 */
final class FrameStreams {
  static let shared = FrameStreams()

  private struct Entry {
    weak var stream: FrameStream?
  }

  private let lock = NSLock()
  private var streams = [Int: Entry]()

  func register(_ stream: FrameStream, id: Int) {
    lock.lock()
    defer { lock.unlock() }
    streams[id] = Entry(stream: stream)
  }

  /// Only removes the entry if it is still this stream's, so a remount reusing an id is safe.
  func unregister(_ stream: FrameStream, id: Int) {
    lock.lock()
    defer { lock.unlock() }
    if streams[id]?.stream === stream {
      streams[id] = nil
    }
  }

  func stream(_ id: Int) -> FrameStream? {
    lock.lock()
    defer { lock.unlock() }
    return streams[id]?.stream
  }

  func drain(_ id: Int) -> Data {
    return stream(id)?.frames.drain() ?? WireWriter.empty()
  }

  func snapshot(_ id: Int) -> Data {
    return stream(id)?.frames.snapshot() ?? WireWriter.empty()
  }

  func takeSnapshot(_ id: Int, ticket: Int) -> Data {
    return stream(id)?.frames.takeSnapshot(ticket) ?? WireWriter.empty()
  }

  func live(_ id: Int) -> [String: Any] {
    return stream(id)?.live() ?? [:]
  }

  /// Whether any mounted camera is running inference right now.
  func anyDetecting() -> Bool {
    lock.lock()
    let all = streams.values.compactMap { $0.stream }
    lock.unlock()
    return all.contains { $0.isDetecting }
  }
}
