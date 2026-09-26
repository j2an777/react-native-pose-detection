import Foundation
import os

enum LogLevel: Int {
  case off = 0
  case error = 1
  case warn = 2
  case info = 3
  case debug = 4
  case trace = 5

  var name: String {
    switch self {
    case .off: return "off"
    case .error: return "error"
    case .warn: return "warn"
    case .info: return "info"
    case .debug: return "debug"
    case .trace: return "trace"
    }
  }

  static func from(_ name: String?) -> LogLevel {
    switch name?.lowercased() {
    case "error": return .error
    case "warn": return .warn
    case "info": return .info
    case "debug": return .debug
    case "trace": return .trace
    default: return .off
    }
  }
}

enum LogCategory: Int, CaseIterable {
  case camera = 0
  case detector = 1
  case engine = 2
  case triggers = 3
  case calibration = 4
  case overlay = 5

  var name: String {
    switch self {
    case .camera: return "camera"
    case .detector: return "detector"
    case .engine: return "engine"
    case .triggers: return "triggers"
    case .calibration: return "calibration"
    case .overlay: return "overlay"
    }
  }

  static func from(_ name: String?) -> LogCategory? {
    guard let name = name?.lowercased() else { return nil }
    return allCases.first { $0.name == name }
  }
}

private struct LogEntry {
  let level: LogLevel
  let category: LogCategory
  let message: String
  let timestampMs: Double
}

/**
 A disabled call site costs one lock and an integer compare, and the message is an `@autoclosure`
 that is never called, so nothing is built or formatted. Interpolating outside the closure turns
 that into a per-frame cost at 30 fps. See docs/logging.md.

 Android reads its level mask through an `AtomicInteger`; there is no dependency-free equivalent
 below iOS 18, so the mask is behind the same lock as the ring. Uncontended, that is tens of
 nanoseconds against a 33 ms frame.

 Entries always go to the unified log, so native-only debugging works with no JavaScript listener
 attached. They are additionally buffered for JavaScript while a listener is.
 */
enum PoseLog {
  private static let logger = Logger(subsystem: "react-native-pose-detection", category: "pose")

  private static let bitsPerCategory = 3
  private static let categoryMask = 0x7

  /// Bounded and drop-oldest, like the frame buffer: a listener that stalls costs a fixed size.
  private static let capacity = 256

  private static let lock = NSLock()

  // Everything below is guarded by `lock`.
  /// What `isEnabled` reads: `base`, raised by every camera's `logLevel` prop.
  private static var mask = 0
  /// What `setLogLevel()` asked for.
  private static var base = 0
  /// Each camera's `logLevel` prop, which raises the level for as long as that camera exists.
  private static var raises = [ObjectIdentifier: Int]()
  private static var entries = [LogEntry?](repeating: nil, count: capacity)
  private static var head = 0
  private static var count = 0
  private static var dropped = 0
  private static var streaming = false

  /**
   One view flushes, whoever attached first. Without this every camera on screen would drain the
   same buffer and each would receive an arbitrary share of the entries.
   */
  private static var owner: ObjectIdentifier?

  static func startStream() {
    lock.lock()
    defer { lock.unlock() }
    streaming = true
    head = 0
    count = 0
    dropped = 0
  }

  static func stopStream() {
    lock.lock()
    defer { lock.unlock() }
    streaming = false
    count = 0
    dropped = 0
  }

  static var isStreaming: Bool {
    lock.lock()
    defer { lock.unlock() }
    return streaming
  }

  static func claimStream(_ candidate: AnyObject) -> Bool {
    lock.lock()
    defer { lock.unlock() }
    let identifier = ObjectIdentifier(candidate)
    if owner == nil {
      owner = identifier
    }
    return owner == identifier
  }

  static func releaseStream(_ candidate: AnyObject) {
    lock.lock()
    defer { lock.unlock() }
    if owner == ObjectIdentifier(candidate) {
      owner = nil
    }
  }

  /**
   Everything buffered since the last call, oldest first, plus how many were dropped. The
   dictionaries are built here rather than at the call site: a disabled channel must not build
   anything.
   */
  static func drain(into sink: inout [[String: Any]]) -> Int {
    lock.lock()
    defer { lock.unlock() }

    let start = (head - count + capacity) % capacity
    for index in 0..<count {
      let slot = (start + index) % capacity
      guard let entry = entries[slot] else { continue }
      sink.append([
        "level": entry.level.name,
        "category": entry.category.name,
        "message": entry.message,
        "timestamp": entry.timestampMs
      ])
      entries[slot] = nil
    }

    let droppedCount = dropped
    head = 0
    count = 0
    dropped = 0
    return droppedCount
  }

  static func setLevel(_ level: LogLevel) {
    let every = packed(level)
    lock.lock()
    defer { lock.unlock() }
    base = every
    mask = combined()
  }

  static func setLevels(_ levels: [LogCategory: LogLevel]) {
    lock.lock()
    defer { lock.unlock() }
    base = merged(base, levels)
    mask = combined()
  }

  /**
   A camera's `logLevel` prop: raises the level on top of `setLogLevel()` while the camera exists,
   and gives it back when the prop goes or the camera does. `nil` withdraws the raise and leaves the
   global level alone. It must not turn logging off: Expo hands every prop to its setter on a view's
   first update, set or not, so an absent prop arrives as `nil` with every mount, and treating that
   as `off` undid `setLogLevel()` each time a camera appeared.
   */
  static func raise(_ owner: AnyObject, to raised: Int?) {
    let identifier = ObjectIdentifier(owner)
    lock.lock()
    defer { lock.unlock() }
    raises[identifier] = raised
    mask = combined()
  }

  /// A level config as JavaScript sends it, a level or a map of categories to levels, as a mask.
  static func levelMask(for config: Any?) -> Int? {
    if let name = JS.string(config) { return packed(LogLevel.from(name)) }
    guard let map = config as? [String: String] else { return nil }
    return merged(0, levels(from: map))
  }

  /// A map of category names to level names; a name this version does not know is skipped.
  static func levels(from map: [String: String]) -> [LogCategory: LogLevel] {
    var parsed = [LogCategory: LogLevel]()
    for (key, value) in map {
      guard let category = LogCategory.from(key) else { continue }
      parsed[category] = LogLevel.from(value)
    }
    return parsed
  }

  private static func packed(_ level: LogLevel) -> Int {
    var bits = 0
    for category in LogCategory.allCases {
      bits |= level.rawValue << (category.rawValue * bitsPerCategory)
    }
    return bits
  }

  private static func merged(_ start: Int, _ levels: [LogCategory: LogLevel]) -> Int {
    var bits = start
    for (category, level) in levels {
      let shift = category.rawValue * bitsPerCategory
      bits = (bits & ~(categoryMask << shift)) | (level.rawValue << shift)
    }
    return bits
  }

  /// `base` with each category taken up to the highest level any camera raised it to. Under `lock`.
  private static func combined() -> Int {
    var bits = base
    for raised in raises.values {
      for category in LogCategory.allCases {
        let shift = category.rawValue * bitsPerCategory
        let level = (raised >> shift) & categoryMask
        if level > (bits >> shift) & categoryMask {
          bits = (bits & ~(categoryMask << shift)) | (level << shift)
        }
      }
    }
    return bits
  }

  static func isEnabled(_ level: LogLevel, _ category: LogCategory) -> Bool {
    lock.lock()
    defer { lock.unlock() }
    let shift = category.rawValue * bitsPerCategory
    return ((mask >> shift) & categoryMask) >= level.rawValue
  }

  static func log(_ level: LogLevel, _ category: LogCategory, _ message: @autoclosure () -> String) {
    guard isEnabled(level, category) else { return }
    emit(level, category, message())
  }

  static func error(_ category: LogCategory, _ message: @autoclosure () -> String) {
    log(.error, category, message())
  }

  static func warn(_ category: LogCategory, _ message: @autoclosure () -> String) {
    log(.warn, category, message())
  }

  static func info(_ category: LogCategory, _ message: @autoclosure () -> String) {
    log(.info, category, message())
  }

  static func debug(_ category: LogCategory, _ message: @autoclosure () -> String) {
    log(.debug, category, message())
  }

  static func trace(_ category: LogCategory, _ message: @autoclosure () -> String) {
    log(.trace, category, message())
  }

  private static func emit(_ level: LogLevel, _ category: LogCategory, _ message: String) {
    record(level, category, message)

    let line = "[\(category.name)] \(message)"
    switch level {
    case .error: logger.error("\(line, privacy: .public)")
    case .warn: logger.warning("\(line, privacy: .public)")
    case .info: logger.info("\(line, privacy: .public)")
    case .debug: logger.debug("\(line, privacy: .public)")
    case .trace: logger.trace("\(line, privacy: .public)")
    case .off: break
    }
  }

  private static func record(_ level: LogLevel, _ category: LogCategory, _ message: String) {
    lock.lock()
    defer { lock.unlock() }
    guard streaming else { return }

    entries[head] = LogEntry(
      level: level,
      category: category,
      message: message,
      timestampMs: Double(Monotonic.nowMs())
    )
    head = (head + 1) % capacity
    if count == capacity {
      dropped += 1
    } else {
      count += 1
    }
  }
}
