# Changelog

All notable changes to this package are documented here. Versions follow
[semantic versioning](https://semver.org), and every published version is an annotated `v*` tag
on the commit that was published.

## 0.2.0

Faster, cooler and steadier on both platforms, with the lifecycle bugs found by driving the example
app on a device fixed. Nothing is removed and no signature breaks, but several defaults changed;
the first list is what to check when upgrading.

### Behavior changes

- **The live rate follows the device.** It is the rate at which inference is busy 85% of the time,
  capped at the camera's 30 fps, instead of a fixed share of a tier's guess. A recent phone runs at
  30; a slow one at what it can finish with room to spare. Profiles are rows of the same model. See
  guides/performance.md.
- **The camera is pinned at 30 fps**, so auto-exposure no longer halves the rate in a dim room, and
  its geometry is fixed for the session: a rate change never rebinds the camera.
- **`smoothing` defaults to `'auto'`**: off with `maxPoses: 1`, because MediaPipe already smooths
  one pose with the same filter, and on for several. When on, it uses MediaPipe's constants with
  speed in body spans per second. `smoothing: true` keeps it on for one pose.
- **Heat on Android**: `SEVERE` halves the rate instead of pausing detection, and only `CRITICAL`
  and above pause it. Both platforms take heat at once and give it back only after 30 s cooler.
- **An explicit `targetFps`** is capped by the camera and by what the device can finish, rather
  than pinned beyond both.
- **`stopDetection()`, `pause()` and `active={false}` park the landmarker** and free it after a
  minute unused, so turning detection back on is instant. Backgrounding frees it after 30 s, and a
  memory warning at once.
- **`detectOnVideo` returns the subject**, the largest body in each frame as live, at its real
  position in the video: `timestamp` is the frame's time in the clip, and `velocity` is measured.
  `detectOnImage` returns the subject first.
- **Photo and video jobs use the GPU when no camera is detecting**, and the CPU when one is.
- **File jobs reject with the code that fits**: `IMAGE_DECODE_FAILED` or `VIDEO_DECODE_FAILED` when
  the file cannot be read, `MODEL_NOT_FOUND`, and `DETECTION_FAILED` only when inference fails.
- **`exportPose`'s `minConfidence` goes up to 1**, the documented range, instead of stopping at 0.9.
- **The `logLevel` prop raises the level while its camera is mounted**, on top of `setLogLevel()`,
  and gives it back on unmount, as documented. It used to overwrite the global level and keep it.

### Added

- `limitedBy` on `getState()`, `getProfile()`, `onReady` and `onPerformanceChange`: why the rate is
  what it is, one of `camera`, `device`, `target`, `profile`, `thermal`, `lowPower`, `idle` and
  `paused`.
- `getProfile()` also reports `cameraFps`, `thermalState` and `lowPower`.
- `getState()` reads `fps` and `limitedBy` live, synchronously, on every call.
- `minConfidence` on `detectOnImage` and `detectOnVideo`, following `maxPoses` as it does for
  exports.
- `http(s)` photos on Android, as on iOS.
- Idle search in two steps: 12 fps after 2 s with nobody in frame, 5 fps after 20 s.
- Low Power Mode and Battery Saver cap the rate at 24 fps.
- Non-finite numbers in props and file options are refused at the call site with a path, instead
  of crashing an iOS app when converted.

### Faster, cooler

- Frames and the live rate are read on the JavaScript thread, never queued behind the main thread.
- The iOS overlay is shape layers the GPU composites, not a full redraw per result, and neither
  platform does overlay work while the overlay is off.
- The GPU check runs once per device and model, not on every mount.
- The Android analysis stream never exceeds the size asked for.
- Videos are decoded once, in order, scaled down inside the decoder, and only sampled frames are
  converted. Photos are decoded straight to 1920 pixels.
- A video job slows to half speed when the device is `serious` and waits out `critical`.
- Android: a camera session opens once with the preview and the analysis stream together. It used
  to open with the analysis stream alone and rebuild at once to add the preview, on every start,
  switch and resume.
- Photo and video detection run on a thread of their own below the camera's. They used to run on
  the thread Expo shares between every module, which a long video held up for the whole app.
- Android: the live camera runs MediaPipe's VIDEO mode on the analysis thread instead of
  LIVE_STREAM, which copied every frame back into a new bitmap for a callback that only read its
  size: 17 MB a second at ten frames. The process uses about a fifth less CPU, the collector
  mostly goes quiet, and a frame never waits behind another.

### Fixed

- Android: the live skeleton was drawn a quarter turn out. The landmarks come back in the
  sensor's frame and were used as if upright. Photos and videos were not affected.
- iOS: a portrait video, which a phone stores sideways with a rotation, lost the body on about a
  third of its frames in `detectOnVideo` and in exports. Frames are now turned upright before
  MediaPipe sees them.
- Android: a camera unmounting after its replacement had started took the new camera's session
  with it, leaving a preview with no detection.
- Android: photos reached the model sideways when their EXIF orientation said to turn them, and
  were decoded at full size.
- Android: an export could throw on a video whose frame rate is stored as a float.
- Android: a decoder that returns video frames in decode order no longer skews sampling or writes
  an export backwards.
- Bare React Native on iOS: the installation guide left out the `AppDelegate` change that registers
  Expo modules, so an app set up from it built and then failed at launch. The step is documented,
  and the bare example does it.
- iOS: detection stopped after the view left the window and came back, a pause during startup left
  the camera running with no preview, overlapping switches left a promise pending forever, and a
  `NaN` or `Infinity` prop crashed the app.
- A ref method called in the frame or two after mount, before the native view existed, was
  rejected. It now waits for the view.
- Several poses: smoothing and velocity carried over when a different person became the largest
  body. Both start over now.
- Smoothing and velocity never applied below 5 fps, and the filter compared the first frame after
  a gap with the position from before it.
- `detectOnImage` and `detectOnVideo` ignored the lower confidence several poses need, and video
  smoothing ran on a fixed 0.1 s step that never reset.
- A `data.select` or angle change no longer reports a spurious `DETECTION_FAILED` for the frames
  already in flight.
- Unrelated prop changes no longer restart the camera after the rate or the heat moved.
- iOS: mounting a camera without a `logLevel` prop turned logging off for the whole app, undoing an
  earlier `setLogLevel()`, so the documented setup delivered nothing.
- A camera's `onLog` received nothing unless something had also called `addLogListener()`.

## 0.1.0

The first release.

- `<PoseCamera />`: live camera with 33-landmark detection and a native skeleton overlay, on
  CameraX and AVFoundation, MediaPipe Tasks Vision 0.10.35 on both platforms
- Self-tuning performance governor: measures inference cost, converges on the fastest
  sustainable frame rate, steps down with heat, caches the settled answer per device and model
- Native trigger engine: declarative conditions evaluated on the camera thread, one event per
  firing, with optional frame snapshots
- Data delivery: `off`, `throttled`, `batched` and `live` modes over one zero-copy binary
  buffer
- Static input: `detectOnImage` and `detectOnVideo` for files, no camera required
- `exportPose`: full-quality painted copies of photos and videos, cancellable, crash-safe
  staging, without slowing a live camera down
- One-Euro smoothing, angle overlays, camera switching, thermal ladder, GPU-to-CPU fallback
- Config plugin and CLI that download, verify and install the model at build time
