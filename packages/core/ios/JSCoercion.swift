import Foundation

/// Untyped bridge values into Swift ones. A number may arrive as `Int`, `Double` or `NSNumber`.
enum JS {
  /// JavaScript `null` crosses as `NSNull`, not nil.
  static func isNull(_ value: Any?) -> Bool {
    return value == nil || value is NSNull
  }

  /// By CF type, not `is Bool`: every `NSNumber` holding 0 or 1 passes `is Bool`.
  static func isBoolean(_ value: Any?) -> Bool {
    guard let number = value as? NSNumber else { return false }
    return CFGetTypeID(number) == CFBooleanGetTypeID()
  }

  static func number(_ value: Any?) -> Double? {
    // First: a bool bridges as NSNumber, so `smoothing: true` would read as 1.
    if isBoolean(value) { return nil }
    if let double = value as? Double { return double }
    if let int = value as? Int { return Double(int) }
    if let number = value as? NSNumber { return number.doubleValue }
    return nil
  }

  static func finite(_ value: Any?) -> Double? {
    guard let number = number(value), number.isFinite else { return nil }
    return number
  }

  /// Truncates like `Int64(_:)` but saturates instead of trapping, as Kotlin's `toLong()` does.
  static func int64(_ value: Any?) -> Int64? {
    guard let number = finite(value) else { return nil }
    // 2^63. Int64.max has no exact double, and this is the first one past it.
    let limit = 9_223_372_036_854_775_808.0
    if number >= limit { return .max }
    if number <= -limit { return .min }
    return Int64(number)
  }

  static func int(_ value: Any?) -> Int? {
    return int64(value).map { Int(clamping: $0) }
  }

  static func bool(_ value: Any?) -> Bool? {
    guard isBoolean(value), let number = value as? NSNumber else { return nil }
    return number.boolValue
  }

  static func string(_ value: Any?) -> String? {
    return value as? String
  }

  static func array(_ value: Any?) -> [Any]? {
    return value as? [Any]
  }

  static func dictionary(_ value: Any?) -> [String: Any]? {
    return value as? [String: Any]
  }

  static func at(_ value: [Any]?, _ index: Int) -> Any? {
    guard let value = value, index >= 0, index < value.count else { return nil }
    return value[index]
  }

  /// A URI with a scheme as given, a bare path as a file URL, as Android reads them.
  static func url(_ uri: String) -> URL {
    if let url = URL(string: uri), url.scheme != nil { return url }
    // Before iOS 17, URL(string:) refuses a file URI with raw spaces.
    if uri.hasPrefix("file://") {
      let path = String(uri.dropFirst("file://".count))
      return URL(fileURLWithPath: path.removingPercentEncoding ?? path)
    }
    return URL(fileURLWithPath: uri)
  }

  static func strings(_ value: Any?) -> [String]? {
    guard let raw = array(value) else { return nil }
    return raw.compactMap { $0 as? String }
  }
}
