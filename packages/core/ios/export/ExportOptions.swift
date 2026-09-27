import Foundation
import UIKit

/// Defaults must match guides/files.md.
struct ExportOptions {
  let overlay: OverlayConfig
  let drawOverlay: Bool
  let maxPoses: Int
  let minConfidence: Float
  let sampleFps: Int
  /// Long edge of the output, or 0 for the source's own size.
  let maxSize: Int
  let directory: URL
  let fileName: String
  /// Still images only.
  let quality: CGFloat

  static let defaultMaxSize = 1920
  static let defaultSampleFps = 10

  static func parse(_ raw: [String: Any]?, sourceName: String) throws -> ExportOptions {
    let maxPoses = clampedCount(raw?["maxPoses"], fallback: 1, limit: 5)
    var drawOverlay = true
    var overlay = OverlayConfig()
    if let value = JS.bool(raw?["overlay"]) {
      drawOverlay = value
    } else if let map = JS.dictionary(raw?["overlay"]) {
      overlay = parseOverlay(map)
    }

    return ExportOptions(
      overlay: overlay,
      drawOverlay: drawOverlay,
      maxPoses: maxPoses,
      minConfidence: minConfidence(raw?["minConfidence"], maxPoses: maxPoses),
      sampleFps: clampedCount(raw?["fps"], fallback: defaultSampleFps, limit: 60),
      maxSize: maxSize(raw?["maxSize"]),
      directory: try directory(JS.string(raw?["directory"])),
      fileName: fileName(JS.string(raw?["fileName"]), sourceName: sourceName),
      quality: CGFloat(clamped(JS.number(raw?["quality"]) ?? 0.9, 0.1, 1, 0.9))
    )
  }

  private static func minConfidence(_ raw: Any?, maxPoses: Int) -> Float {
    let auto = Double(StillConfidence.forMaxPoses(maxPoses))
    return Float(clamped(JS.number(raw) ?? auto, 0.1, 1, auto))
  }

  /// Caches by default: an export is derived data, and Documents would put it in iCloud backups.
  private static func directory(_ raw: String?) throws -> URL {
    let base: URL
    switch raw {
    case nil, "cache":
      base = try FileManager.default.url(
        for: .cachesDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
    case "documents":
      base = try FileManager.default.url(
        for: .documentDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
    case let value?:
      guard let url = URL(string: value), url.isFileURL else {
        guard value.hasPrefix("/") else {
          throw ExportError("directory must be 'cache', 'documents' or a file:// URI, got \(value)")
        }
        base = URL(fileURLWithPath: value)
        try FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        sweepStaging(base)
        return base
      }
      base = url
    }
    try FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
    sweepStaging(base)
    return base
  }

  /// What a dead process left mid-write; exports run serially, so none belongs to a running one.
  private static func sweepStaging(_ base: URL) {
    let contents = (try? FileManager.default.contentsOfDirectory(atPath: base.path)) ?? []
    for name in contents where name.hasSuffix(".partial.mp4") {
      try? FileManager.default.removeItem(at: base.appendingPathComponent(name))
    }
  }

  /// Sanitized: a slash would write outside the directory the caller chose.
  private static func fileName(_ raw: String?, sourceName: String) -> String {
    let candidate = raw.flatMap { $0.isEmpty ? nil : $0 } ?? "\(sourceName)-pose"
    let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_ ."))
    let cleaned = candidate.unicodeScalars.filter { allowed.contains($0) }.map(String.init).joined()
    let trimmed = cleaned.trimmingCharacters(in: .whitespaces)
    return trimmed.isEmpty ? "pose-export" : trimmed
  }

  private static func maxSize(_ value: Any?) -> Int {
    guard let size = JS.int(value) else { return defaultMaxSize }
    return size <= 0 ? 0 : max(120, size)
  }

  private static func clampedCount(_ value: Any?, fallback: Int, limit: Int) -> Int {
    guard let number = JS.int(value) else { return fallback }
    return min(max(1, number), limit)
  }
}

struct ExportError: LocalizedError {
  let message: String

  init(_ message: String) {
    self.message = message
  }

  var errorDescription: String? {
    return message
  }
}

struct ExportSummary {
  let url: URL
  let width: Int
  let height: Int
  let durationMs: Int
  let frameCount: Int
  let posesFound: Int

  var payload: [String: Any] {
    return [
      "uri": url.absoluteString,
      "width": width,
      "height": height,
      "durationMs": durationMs,
      "frameCount": frameCount,
      "posesFound": posesFound
    ]
  }
}
