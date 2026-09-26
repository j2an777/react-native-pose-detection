# 0012: Files are decoded once, and stay off the camera's GPU and thread

**Status:** accepted
**Date:** 2026-09-26

## Context

`detectOnImage`, `detectOnVideo` and `exportPose` shipped in 0.1.0 correct on the happy path and
expensive everywhere else.

- **Videos were decoded many times over.** Each sample seeked: `getFrameAtTime` on Android and an
  image generator on iOS decode forward from the keyframe before the sample, at full size. Ten
  samples a second from a clip with two-second keyframes decoded most frames several times.
- **Photos were decoded whole and, on Android, sideways.** A 12-megapixel photo became a 48 MB
  bitmap for a model that looks at 256 pixels, and `BitmapFactory` ignores EXIF orientation, so a
  portrait photo reached MediaPipe on its side.
- **Everything ran on the CPU, on Expo's thread.** File jobs never used the GPU even with no camera
  on screen, ignored heat although the guide said otherwise, and ran on the one thread Expo gives
  every module's async functions, so a two-minute video job held up every other module in the app.
- **Video smoothing was fed a constant interval** and never started over, so its velocity and its
  filtering were measured against time that had not passed.

## Decision

**Decode once, in order, small.** iOS reads a video with `AVAssetReader`, scaled to 960 pixels on
the long side inside the decoder's output path. Android decodes with `MediaCodec` onto a
`SurfaceTexture`, and only sampled frames are drawn, by GL, into a 960-pixel offscreen surface that
turns, scales and converts them in one pass. Frames between samples cost the decode and nothing
else. Each sample is the earliest frame in its slot of the sampling grid. Some decoders return
frames in decode order rather than display order, the Android emulator's among them, so a slot is
held until the stream is 300 ms past it, a late earlier frame replaces the one it holds, and
samples leave in time order either way.

**MediaPipe is handed upright pixels.** Told a clip is stored sideways, MediaPipe's VIDEO mode lost
the body on about a third of the frames of a portrait video, where the same clip stored upright was
tracked on every one. iOS turns each sampled frame upright and scales it in one Core Image pass on
the GPU and hands it over as `.up`, as the live camera does; Android turns it in the GL pass that
already scales it.

**Photos are decoded upright and capped.** ImageIO on iOS and `ImageDecoder` on Android from API 28
decode straight to 1920 pixels with the EXIF orientation applied; below API 28, `BitmapFactory`
samples by a power of two and the orientation is applied from the file's EXIF. An export decodes
at the size it paints, and decodes a second, smaller picture for detection only when the first is
larger than detection needs.

**The GPU when no camera is detecting.** A video job or export uses the GPU when this device's GPU
check passed and no `<PoseCamera>` is running inference, and the CPU otherwise, so it never
competes with a live preview. A device the check has never run on tries the GPU and treats the
first frame as the check. The verdict is shared with the camera's calibration cache. A photo is one
inference, which the CPU finishes before the GPU has compiled its shaders, so photos always run on
the CPU.

**Heat costs time, never frames.** A job rests as long as it worked at `serious`, and waits out
`critical` until the device has been cooler for 30 seconds, through the same hysteresis as the live
rate.

**Their own thread.** Photo and video detection run on a serial queue of this package's, below the
camera's priority, and exports on another.

**Real time.** Each video frame carries its real position in the video, and smoothing and velocity
are measured against it. The subject is the largest body, as live, and both start over when the
subject is lost, changes, or more than two and a half samples pass.

## Consequences

- A video job decodes each frame exactly once, and converts only the ones it samples.
- A 48-megapixel photo costs what a 4-megapixel one does, and portrait photos are found upright.
- An export started while a camera is detecting is slower than it could be, on purpose. One started
  with no camera running is as fast as the GPU allows.
- A decoder that returns frames out of order costs an export the frames that arrive late: they are
  dropped, with a warning, rather than written backwards into a file a player cannot play. Decoders
  that return frames in display order, which is what the platform asks of them, lose nothing.
- The rejection codes mean something: `IMAGE_DECODE_FAILED` and `VIDEO_DECODE_FAILED` for a file
  that cannot be read, `MODEL_NOT_FOUND`, and `DETECTION_FAILED` only for inference itself.
