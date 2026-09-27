import ImageIO
import UIKit

/// ImageIO rather than `UIImage`: it decodes upright and straight to the size asked for.
enum StillImage {
  /// At 1920 the model's 256-pixel body crop is sampled down for anyone taller than about a seventh
  /// of the picture; a larger decode costs memory and finds nobody new.
  static let detectionMaxPixels = 1920

  static func source(uri: String) -> CGImageSource? {
    guard let url = URL(string: uri), url.scheme != nil else {
      return CGImageSourceCreateWithURL(URL(fileURLWithPath: uri) as CFURL, nil)
    }
    if url.isFileURL {
      return CGImageSourceCreateWithURL(url as CFURL, nil)
    }
    guard let data = try? Data(contentsOf: url) else { return nil }
    return CGImageSourceCreateWithData(data as CFData, nil)
  }

  /// Nil `maxPixels` is full size. ImageIO never upscales.
  static func decode(_ source: CGImageSource, maxPixels: Int?) -> CGImage? {
    var options: [CFString: Any] = [
      // Not the embedded thumbnail: it is tiny and ignores the size asked for.
      kCGImageSourceCreateThumbnailFromImageAlways: true,
      kCGImageSourceCreateThumbnailWithTransform: true,
      // Decode now, on the job's thread, not lazily wherever it is drawn.
      kCGImageSourceShouldCacheImmediately: true
    ]
    if let maxPixels = maxPixels {
      options[kCGImageSourceThumbnailMaxPixelSize] = maxPixels
    }
    return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
  }
}
