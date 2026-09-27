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

/// A disabled call site formats nothing because messages are `@autoclosure`s; keep formatting
/// inside them. The mask shares the ring's lock since `Atomic` needs iOS 18. See docs/logging.md.
enum PoseLog {
  private static let logger = Logger(subsystem: "react-native-pose-detection", category: "pose")

  private static let bitsPerCategory = 3
  private static let categoryMask = 0x7

  /// Drop-oldest, so a stalled listener costs a fixed size.
  private static let capacity = 256

  private static let lock = NSLock()

  // Everything below is guarded by `lock`.
  /// `base` raised by every camera's `logLevel` prop; what `isEnabled` reads.
  private static var mask = 0
  /// What `setLogLevel()` asked for.
  private static var base = 0
  private static var raises = [ObjectIdentifier: Int]()
  private static var entries = [LogEntry?](repeating: nil, count: capacity)
  private static var head = 0
  private static var count = 0
  private static var dropped = 0
  private static var streaming = false

  /// The one camera that flushes, so several don't split the entries; with none, the module does.
  private static var owner: ObjectIdentifier?

  static let flushSeconds = 0.25

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

  static func claimStream(_ candidate: AnyObject) {
    lock.lock()
    defer { lock.unlock() }
    if owner == nil {
      owner = ObjectIdentifier(candidate)
    }
  }

  static func releaseStream(_ candidate: AnyObject) {
    lock.lock()
    defer { lock.unlock() }
    if owner == ObjectIdentifier(candidate) {
      owner = nil
    }
  }

  static func takeBatch(_ flusher: AnyObject?) -> [[String: Any]]? {
    lock.lock()
    defer { lock.unlock() }
    guard streaming else { return nil }
    if let flusher = flusher {
      let identifier = ObjectIdentifier(flusher)
      if owner == nil {
        owner = identifier
      }
      guard owner == identifier else { return nil }
    } else if owner != nil {
      return nil
    }

    let waiting = count
    guard waiting > 0 else { return nil }

    var batch = [[String: Any]]()
    batch.reserveCapacity(waiting + 1)
    let start = (head - waiting + capacity) % capacity
    // An entry, not a field, so a listener that reads only entries still sees the loss.
    if dropped > 0 {
      batch.append([
        "level": "warn",
        "category": "engine",
        "message": "\(dropped) log entries were dropped before this batch",
        "timestamp": entries[start]?.timestampMs ?? Double(Monotonic.nowMs()),
        "data": ["droppedCount": dropped]
      ])
    }
    for index in 0..<waiting {
      let slot = (start + index) % capacity
      guard let entry = entries[slot] else { continue }
      batch.append([
        "level": entry.level.name,
        "category": entry.category.name,
        "message": entry.message,
        "timestamp": entry.timestampMs
      ])
      entries[slot] = nil
    }

    head = 0
    count = 0
    dropped = 0
    return batch
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

  /// A camera's `logLevel` prop, on top of `setLogLevel()`. `nil` withdraws it and must not mean
  /// off: Expo sends every unset prop as nil on mount.
  static func raise(_ owner: AnyObject, to raised: Int?) {
    let identifier = ObjectIdentifier(owner)
    lock.lock()
    defer { lock.unlock() }
    raises[identifier] = raised
    mask = combined()
  }

  static func levelMask(for config: Any?) -> Int? {
    if let name = JS.string(config) { return packed(LogLevel.from(name)) }
    guard let map = config as? [String: String] else { return nil }
    return merged(0, levels(from: map))
  }

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

  /// Caller holds `lock`.
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
