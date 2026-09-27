import AVFoundation
import CoreMedia

/// The asset reads iOS 16 deprecated: their async replacements need 16 and the floor is 15.1, so
/// the warnings are kept to this one file. See docs/native-modules.md.
enum AssetCompat {
  static func durationSeconds(_ asset: AVAsset) -> Double {
    return CMTimeGetSeconds(asset.duration)
  }

  static func tracks(_ asset: AVAsset, of type: AVMediaType) -> [AVAssetTrack] {
    return asset.tracks(withMediaType: type)
  }

  static func preferredTransform(_ track: AVAssetTrack) -> CGAffineTransform {
    return track.preferredTransform
  }

  static func naturalSize(_ track: AVAssetTrack) -> CGSize {
    return track.naturalSize
  }

  /// Cast the whole `[Any]`: `as?` on a single CF value can never fail, which is a compile error.
  static func formatDescription(_ track: AVAssetTrack) -> CMFormatDescription? {
    return (track.formatDescriptions as? [CMFormatDescription])?.first
  }
}
