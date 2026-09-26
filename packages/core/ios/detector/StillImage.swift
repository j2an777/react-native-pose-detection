import ImageIO
import UIKit

/**
 A photo decoded upright and no larger than it needs to be.

 `UIImage(contentsOfFile:)` decodes the whole file at full size, so a 48-megapixel photo becomes
 190 MB of pixels, most of which inference throws away. ImageIO decodes straight to the size asked
 for, and a JPEG decoder does much of that downscale inside the decode itself. The EXIF orientation
 is applied while decoding, so the pixels are upright and the landmarks describe the picture as it
 is shown.
 */
enum StillImage {
  /**
   The long side a photo is decoded to for detection. The detector sees 224 pixels of the whole
   frame and the landmark model a 256-pixel crop around the body, so at 1920 that crop is sampled
   down rather than stretched for anybody taller than about a seventh of the picture. A larger
   decode costs memory and finds nobody new.
   */
  static let detectionMaxPixels = 1920

  /// A file path, a `file://` URI, or anything `Data(contentsOf:)` can fetch.
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

  /// Upright, with the long side at most `maxPixels`, or at full size when that is nil. Never upscaled.
  static func decode(_ source: CGImageSource, maxPixels: Int?) -> CGImage? {
    var options: [CFString: Any] = [
      // The embedded thumbnail is a few hundred pixels at best, and ignores the size asked for.
      kCGImageSourceCreateThumbnailFromImageAlways: true,
      kCGImageSourceCreateThumbnailWithTransform: true,
      // Decoded here, on the job's own thread, rather than lazily on whichever thread draws it.
      kCGImageSourceShouldCacheImmediately: true
    ]
    if let maxPixels = maxPixels {
      options[kCGImageSourceThumbnailMaxPixelSize] = maxPixels
    }
    return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
  }
}
