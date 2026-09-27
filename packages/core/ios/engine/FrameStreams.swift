import Foundation

/// One view's synchronous reads, all thread-safe so they run on the JS thread. See ADR 0010.
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

/// Streams by view id, for module functions: ExpoModulesCore runs view functions on main.
/// Held weakly: the view owns its stream, and a gone one reads as empty rather than stale.
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

  /// Identity-checked, so a remount that reused the id keeps its entry.
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

  func anyDetecting() -> Bool {
    lock.lock()
    let all = streams.values.compactMap { $0.stream }
    lock.unlock()
    return all.contains { $0.isDetecting }
  }
}
