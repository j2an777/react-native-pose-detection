# Changelog

All notable changes to this package are documented here. Versions follow
[semantic versioning](https://semver.org), and every published version is an annotated `v*` tag
on the commit that was published.

## 0.2.0

Faster, cooler and steadier on both platforms, with the lifecycle bugs found by driving the example
app on a device fixed. Nothing is removed and no signature breaks, but several defaults changed;
the first list is what to check when upgrading.

### Behavior changes

- **The minimums are Expo SDK 56, React Native 0.85 and iOS 16.4**, and `peerDependencies` now
  says so. The native modules hand frames to JavaScript through Expo's native `ArrayBuffer`, which
  reached iOS in SDK 56, so the SDK 51 and React Native 0.74 stated since 0.1.0 could not build.
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
- **Android: `delegate="auto"` starts on the CPU and moves to the GPU** once the GPU landmarker has
  built, primed with a live frame so it takes over mid-track. `onReady` reports `'CPU'` on such a
  device, and `onPerformanceChange` reports the move with the new reason `'delegate'`.
- **Visibility follows time, not frames**, with one pose on both platforms. MediaPipe smooths it
  once per frame, so below 30 fps a joint took proportionally longer to appear or disappear: 0.7 s
  at 10 fps against 0.23 s at 30. The same smoothing now runs on elapsed time. At 30 fps nothing
  changes; below it, frames and the overlay carry the visibility a 30 fps session would.
- **`emit: 'while'` fires only while `enter` holds**, as the schema says. It used to keep firing
  between the two thresholds of a trigger with both.
- **A `minDurationMs` hold ends when frames stop**: a pause, detection off, the app in the
  background or a camera switch. It used to count the time across them.
- **An explicit `delegate="gpu"` that cannot run** reports `DETECTOR_INIT_FAILED` at start, where
  it used to build and then report `DETECTION_FAILED` on every frame.
- **A pinned `facing` the device does not have** reports `CAMERA_UNAVAILABLE`, as documented,
  rather than `CAMERA_START_FAILED`.
- **Android reports the sizes the camera bound** in `onReady` and `onPerformanceChange`, rather
  than the preset it asked for: a Redmi Note 12 asked for 854x480 analysis delivers 864x480.
- **`detectOnVideo` reports progress in steps of 2%**, as documented and as exports do, instead of
  once per sampled frame.
- **`onPose` and `onPoseBatch` follow `data.mode`**, as documented and as the development warnings
  said: `'batched'` delivers to `onPoseBatch`, `'throttled'` and `'live'` to `onPose`. Whichever
  callback was set used to receive the frames, so with both set, `onPose` never fired.

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
- An unknown `data.mode`, or a joint `data.select` or `data.angles` does not know, throws
  `PoseConfigError` during render. Untyped JavaScript used to get no frames at all, silently.
- The `'delegate'` reason on `onPerformanceChange`, and `thermalState` and `lowPower` on it.
- The README lists every event, ref method, function, trigger field and CLI command, each linked to
  its reference, and a new functions reference covers every export on one page.

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
- Android: the first skeleton comes about twice as fast. The landmarker builds while the camera
  opens instead of after it, and `auto` answers frames on the CPU, built in 0.7 s, while the GPU
  builds in 1.9 s. On a Redmi Note 12 the first skeleton came 2.2 s after mount and now comes at
  1.1 s.
- Android: a camera screen closed and opened again within a minute takes back the landmarker it
  left behind, and its skeleton is up in 0.6 s on the same phone.
- Android: the first launch on a device no longer builds a second GPU landmarker only to check that
  the GPU works; the warm-up of the one that is used is the check.
- The warm-up runs before the first camera frame on both platforms. It used to run after it, feed a
  blank frame into the track that frame had started, and make the model find the person twice.

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
- Android: an exported video kept painting the last skeleton after the person left, frozen where
  they were last seen, and frames before the first detection carried a pose from later in the clip.
- Android: exporting a video picked as a `content://` link without a file extension failed as an
  image that could not be read.
- Android: an image export that failed part-way could leave a truncated `.jpg` behind, and a JPEG
  encode that failed was not reported. Images are staged and renamed into place, as videos are.
- Android: a rep that finished while the `triggers` prop was being updated could be lost from the
  count, and a `NaN` or infinite trigger duration became 0 or forever instead of the default.
- iOS: a prop value of exactly 0 or 1, such as `smoothing={{ beta: 0 }}`, `overlay={{ lineWidth: 1 }}`
  or a trigger bound of 0, was read as missing and replaced by the default.
- `batched` frames buffered before somebody left the frame waited for somebody to come back before
  they were delivered.
- A hand-copied model such as `pose_landmarker_full (1).task` survived the plugin's cleanup and
  `doctor`, while the runtime could load it ahead of the installed model.
- A video export under a name already taken, such as a second export of the same clip, deleted the
  earlier file before it started, so a re-export that was cancelled or failed lost both. The
  earlier file now stays until the new one is finished and is replaced in one step. Both platforms.
- `addLogListener()` heard nothing unless a camera was mounted, so photo and video detection and
  exports could not be watched from JavaScript. With no camera on screen the module now hands the
  batches over itself.
- `doctor` failed on a project with only `android/` or only `ios/`. The missing platform is now
  skipped, and only a directory with neither fails.
- `fetch-model` always copied the model into `android/app`, so an app whose `react-native.config.js`
  names another module or Android directory shipped without it and failed with `MODEL_NOT_FOUND`,
  while `doctor` checked the same folder and passed. Both now follow that config as React Native's
  CLI does.
- `onPerformanceChange` fired only when the rate moved, so under `thermalPolicy` `'off'` or
  `'critical-only'` an app never heard that the device was heating, though the docs said it would.
  It now fires on every change of heat or Low Power Mode. Both platforms.
- After a stall, such as a resume or a camera switch, the first two frames both ran inference one
  sensor interval apart. The schedule now restarts one interval out. Both platforms.
- iOS: `detectOnVideo` and a video `exportPose` failed on a bare file path, which Android and
  `detectOnImage` accept.
- `cancel()` on a video job or an export still queued behind another was lost, and the job ran in
  full. A queued job is now cancelled before it starts. Both platforms.
- `snapshot()` returned the last frame after detection stopped, paused or went to the background,
  instead of `null`. Both platforms.
- `getState().active` turned false when only the landmarker failed to build
  (`DETECTOR_INIT_FAILED`), although the preview keeps running.
- iOS: an export that drops an audio track the MP4 writer cannot hold now says so on the
  `detector` channel, as Android does.
- `doctor` checked for iOS 15.1, so it passed an app below the 16.4 that Expo SDK 56 and later
  need, where autolinking silently leaves every Expo pod out. It checks for 16.4 now.
- The docs said a `snapshot: true` trigger arrives a microtask late and can be reordered: it
  arrives in firing order. They also called a `cycle` trigger's snapshot the bottom of the rep: it
  is the frame the rep finished on.

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
