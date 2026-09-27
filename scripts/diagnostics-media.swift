// Makes the media for the example app's `files` diagnostics, run by scripts/device-diagnostics.sh:
//
//   xcrun swift scripts/diagnostics-media.swift ss/export-frame.png <output directory>
//
// Writes pose-photo.jpg; pose-photo-exif6.jpg, stored sideways with EXIF orientation 6 as a phone
// saves a portrait photo; and pose-clip.mp4, 3 s at 30 fps stored sideways with a track transform,
// the person drifting 3 px a frame. Each is checked to decode upright.

import AVFoundation
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

let arguments = CommandLine.arguments
guard arguments.count == 3 else {
  fatalError("usage: diagnostics-media.swift <source image> <output directory>")
}
let sourceURL = URL(fileURLWithPath: arguments[1])
let outputURL = URL(fileURLWithPath: arguments[2])

let uprightWidth = 1_400
let uprightHeight = 786
let frameCount = 90
let shiftPerFrame = 3
let colorSpace = CGColorSpaceCreateDeviceRGB()
let bitmapInfo = CGImageAlphaInfo.noneSkipFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue

struct Pixel: Equatable {
  let red: Int
  let green: Int
  let blue: Int

  func isClose(to other: Pixel) -> Bool {
    return abs(red - other.red) < 24 && abs(green - other.green) < 24 && abs(blue - other.blue) < 24
  }
}

struct Point {
  let x: Int
  let y: Int
}

/// Places whose colour tells the sky, the sea, the rock and the person apart.
let probes = [Point(x: 50, y: 40), Point(x: 700, y: 720), Point(x: 1_300, y: 400), Point(x: 690, y: 300)]

func context(width: Int, height: Int, data: UnsafeMutableRawPointer? = nil, bytesPerRow: Int? = nil) -> CGContext {
  guard let made = CGContext(
    data: data,
    width: width,
    height: height,
    bitsPerComponent: 8,
    bytesPerRow: bytesPerRow ?? width * 4,
    space: colorSpace,
    bitmapInfo: bitmapInfo
  ) else {
    fatalError("could not make a \(width)x\(height) context")
  }
  return made
}

func image(from drawing: CGContext) -> CGImage {
  guard let made = drawing.makeImage() else { fatalError("could not make an image") }
  return made
}

guard let source = CGImageSourceCreateWithURL(sourceURL as CFURL, nil),
      let original = CGImageSourceCreateImageAtIndex(source, 0, nil) else {
  fatalError("could not read \(sourceURL.path)")
}

/// The upright picture, shifted right by `shift` pixels over black.
func upright(shift: Int) -> CGImage {
  let drawing = context(width: uprightWidth, height: uprightHeight)
  drawing.setFillColor(CGColor(gray: 0, alpha: 1))
  drawing.fill(CGRect(x: 0, y: 0, width: uprightWidth, height: uprightHeight))
  drawing.draw(original, in: CGRect(x: shift, y: 0, width: uprightWidth, height: uprightHeight))
  return image(from: drawing)
}

/// Turned a quarter counter-clockwise, which EXIF 6 and a clockwise track transform undo.
func sideways(_ picture: CGImage) -> CGImage {
  let drawing = context(width: uprightHeight, height: uprightWidth)
  // Core Graphics counts up from the bottom: a positive rotation turns counter-clockwise on screen.
  drawing.translateBy(x: CGFloat(uprightHeight), y: 0)
  drawing.rotate(by: .pi / 2)
  drawing.draw(picture, in: CGRect(x: 0, y: 0, width: uprightWidth, height: uprightHeight))
  return image(from: drawing)
}

func writeJPEG(_ picture: CGImage, named name: String, orientation: Int?) {
  let url = outputURL.appendingPathComponent(name) as CFURL
  guard let destination = CGImageDestinationCreateWithURL(url, UTType.jpeg.identifier as CFString, 1, nil) else {
    fatalError("could not write \(name)")
  }
  var properties: [CFString: Any] = [kCGImageDestinationLossyCompressionQuality: 0.9]
  if let orientation {
    properties[kCGImagePropertyOrientation] = orientation
  }
  CGImageDestinationAddImage(destination, picture, properties as CFDictionary)
  guard CGImageDestinationFinalize(destination) else { fatalError("could not finish \(name)") }
}

func pixel(_ picture: CGImage, at point: Point) -> Pixel {
  let drawing = context(width: picture.width, height: picture.height)
  drawing.draw(picture, in: CGRect(x: 0, y: 0, width: picture.width, height: picture.height))
  guard let data = drawing.data?.assumingMemoryBound(to: UInt8.self) else { fatalError("no pixels") }
  // Counted from the top, as a person reads a picture.
  let offset = point.y * picture.width * 4 + point.x * 4
  return Pixel(red: Int(data[offset + 2]), green: Int(data[offset + 1]), blue: Int(data[offset]))
}

func expectUpright(_ picture: CGImage, _ what: String, against reference: CGImage) {
  guard picture.width == uprightWidth, picture.height == uprightHeight else {
    fatalError("\(what) is \(picture.width)x\(picture.height), not upright")
  }
  for point in probes where !pixel(picture, at: point).isClose(to: pixel(reference, at: point)) {
    fatalError("\(what) differs from the upright picture at \(point.x),\(point.y)")
  }
}

let reference = upright(shift: 0)
writeJPEG(reference, named: "pose-photo.jpg", orientation: nil)
writeJPEG(sideways(reference), named: "pose-photo-exif6.jpg", orientation: 6)

let exifURL = outputURL.appendingPathComponent("pose-photo-exif6.jpg") as CFURL
let thumbnailOptions: [CFString: Any] = [
  kCGImageSourceCreateThumbnailFromImageAlways: true,
  kCGImageSourceCreateThumbnailWithTransform: true
]
guard let exifSource = CGImageSourceCreateWithURL(exifURL, nil),
      let turned = CGImageSourceCreateThumbnailAtIndex(exifSource, 0, thumbnailOptions as CFDictionary) else {
  fatalError("could not read the EXIF photo back")
}
expectUpright(turned, "the EXIF photo", against: reference)

let clipURL = outputURL.appendingPathComponent("pose-clip.mp4")
try? FileManager.default.removeItem(at: clipURL)
let writer: AVAssetWriter
do {
  writer = try AVAssetWriter(outputURL: clipURL, fileType: .mp4)
} catch {
  fatalError("could not start the clip: \(error)")
}
let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
  AVVideoCodecKey: AVVideoCodecType.h264,
  AVVideoWidthKey: uprightHeight,
  AVVideoHeightKey: uprightWidth
])
input.transform = CGAffineTransform(a: 0, b: 1, c: -1, d: 0, tx: CGFloat(uprightWidth), ty: 0)
input.expectsMediaDataInRealTime = false
let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
  kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
  kCVPixelBufferWidthKey as String: uprightHeight,
  kCVPixelBufferHeightKey as String: uprightWidth
])
writer.add(input)
writer.startWriting()
writer.startSession(atSourceTime: .zero)

for frame in 0..<frameCount {
  while !input.isReadyForMoreMediaData {
    usleep(1_000)
  }
  var made: CVPixelBuffer?
  guard let pool = adaptor.pixelBufferPool,
        CVPixelBufferPoolCreatePixelBuffer(nil, pool, &made) == kCVReturnSuccess,
        let buffer = made else {
    fatalError("no buffer for frame \(frame)")
  }
  CVPixelBufferLockBaseAddress(buffer, [])
  let drawing = context(
    width: uprightHeight,
    height: uprightWidth,
    data: CVPixelBufferGetBaseAddress(buffer),
    bytesPerRow: CVPixelBufferGetBytesPerRow(buffer)
  )
  let picture = sideways(upright(shift: frame * shiftPerFrame))
  drawing.draw(picture, in: CGRect(x: 0, y: 0, width: uprightHeight, height: uprightWidth))
  CVPixelBufferUnlockBaseAddress(buffer, [])
  adaptor.append(buffer, withPresentationTime: CMTime(value: CMTimeValue(frame), timescale: 30))
}
input.markAsFinished()
let finished = DispatchSemaphore(value: 0)
writer.finishWriting { finished.signal() }
finished.wait()
guard writer.status == .completed else {
  fatalError("the clip was not written: \(String(describing: writer.error))")
}

let generator = AVAssetImageGenerator(asset: AVURLAsset(url: clipURL))
generator.appliesPreferredTrackTransform = true
generator.requestedTimeToleranceBefore = .zero
generator.requestedTimeToleranceAfter = .zero
do {
  let first = try generator.copyCGImage(at: .zero, actualTime: nil)
  expectUpright(first, "the clip's first frame", against: reference)
} catch {
  fatalError("could not read the clip back: \(error)")
}
print("wrote pose-photo.jpg, pose-photo-exif6.jpg and pose-clip.mp4 to \(outputURL.path)")
