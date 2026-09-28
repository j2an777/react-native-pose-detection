# Changelog

All notable changes to this package are documented here. Versions follow
[semantic versioning](https://semver.org), and every published version is an annotated `v*` tag
on the commit that was published.

## 0.2.1

The first 0.2 release on `latest`. 0.2.0 went out on `next` only, so an app coming from 0.1.0
gets everything under [0.2.0](#020); start with [Upgrading from 0.1.0](#upgrading-from-010).

### Added

- `doctor` and `fetch-model` check that the installed Expo SDK is the one built for the app's
  React Native, and name the version to install. npm cannot catch this, since `expo` accepts any
  React Native, and the mismatch only shows later: `expo@57` on React Native 0.85 fails to
  compile for Android.

### Changed

- README reorganized: requirements and supported versions up front, a bare setup that pins the
  Expo SDK and links the autolinking steps, the documentation in reading order, and every event
  and function linked to its reference.
- The API and CLI references are complete: every exported type, all 33 joint names, each option's
  default and range, and every `doctor` check.

### Fixed

- The `logLevel` prop is checked during render, as `setLogLevel()` is: an unknown level or
  category throws `PoseConfigError` instead of being ignored natively.
- `detectOnImage` and `detectOnVideo` check `select` and `angles` as `data` does. An unknown joint
  is a `PoseConfigError` instead of an error with no `code`, and a joint with no angle is refused
  instead of skipped.

## 0.2.0

Faster, cooler and steadier on both platforms. No API is removed and no signature changes, but
some defaults and behaviors do.

### Upgrading from 0.1.0

- **Minimums:** Expo SDK 56, React Native 0.85, iOS 16.4. 0.1.0's stated SDK 51 and RN 0.74 could
  never build. Bare apps set iOS 16.4 in both the `Podfile` and the Xcode target.
- **`onPose` / `onPoseBatch` follow `data.mode`:** `'batched'` goes to `onPoseBatch`, `'throttled'`
  and `'live'` to `onPose`. Before, whichever callback was set got the frames.
- **`smoothing` defaults to `'auto'`:** off for one pose, which MediaPipe already smooths, on for
  several. `smoothing: true` keeps it on.
- **Bad `data` config throws:** an unknown `data.mode`, `select` or `angles` name throws
  `PoseConfigError` at render instead of silently delivering nothing.
- **Triggers:** `emit: 'while'` fires only while `enter` holds, and a `minDurationMs` hold ends
  when frames stop (pause, detection off, background, camera switch).
- **`targetFps`** is capped by the camera and by what the device can finish.
- **Errors:** an unusable `delegate="gpu"` reports `DETECTOR_INIT_FAILED` at start; a pinned
  `facing` the device lacks reports `CAMERA_UNAVAILABLE`; file jobs reject with
  `IMAGE_DECODE_FAILED`, `VIDEO_DECODE_FAILED` or `MODEL_NOT_FOUND`, and `DETECTION_FAILED` only
  when inference fails.
- **`logLevel` prop** raises the level only while its camera is mounted, instead of overwriting
  the global level.
- **Android `delegate="auto"`** starts on the CPU and moves to the GPU once it has built, so
  `onReady` reports `'CPU'` and `onPerformanceChange` reports the move with reason `'delegate'`.

### Changed

- **Frame rate:** the live rate is what the device finishes with 15% to spare, capped at the
  camera's 30 fps. The camera is pinned at 30 fps, so a dim room no longer halves it.
- **Heat:** Android `SEVERE` halves the rate instead of pausing; only `CRITICAL` pauses. Both
  platforms take heat at once and give it back after 30 s cooler.
- **Idle and battery:** 12 fps after 2 s with nobody in frame, 5 fps after 20 s. Low Power Mode and
  Battery Saver cap the rate at 24 fps.
- **Detection off:** `stopDetection()`, `pause()` and `active={false}` park the landmarker and free
  it after a minute, so turning it back on is instant. Backgrounding frees it after 30 s.
- **Visibility** is smoothed over time, not frames, so joints appear and fade the same at 10 fps as
  at 30.
- **Photos and videos:** `detectOnVideo` returns the largest body per frame with its real
  timestamp and measured velocity, and reports progress in 2% steps; `detectOnImage` returns the
  largest body first. File jobs use the GPU when no camera is detecting.
- **`exportPose`** accepts `minConfidence` up to 1.
- **Android** reports the sizes the camera actually bound, such as 864x480 for a requested 854x480.

### Added

- `limitedBy` on `getState()`, `getProfile()`, `onReady` and `onPerformanceChange`: why the rate is
  what it is (`camera`, `device`, `target`, `profile`, `thermal`, `lowPower`, `idle`, `paused`).
- `thermalState` and `lowPower` on `onPerformanceChange` and `getProfile()`, plus `cameraFps` on
  `getProfile()`. `onPerformanceChange` also fires on every heat or Low Power change.
- `getState()` reads `fps` and `limitedBy` live on every call.
- `minConfidence` on `detectOnImage` and `detectOnVideo`.
- `http(s)` photo URLs on Android.
- `NaN` and `Infinity` in props and file options are rejected at the call site.

### Performance

- Frames are read on the JavaScript thread, never queued behind the main thread.
- iOS overlay drawn as GPU-composited shape layers; no overlay work at all while it is off.
- Android runs MediaPipe in VIDEO mode on the analysis thread: about a fifth less CPU and almost
  no garbage collection.
- Android first skeleton about twice as fast (1.1 s instead of 2.2 s on a Redmi Note 12), and 0.6 s
  when a camera screen reopens within a minute.
- Android opens the camera session once, with preview and analysis together.
- The GPU check runs once per device and model; the warm-up runs before the first frame.
- Videos are decoded once, in order, scaled inside the decoder; photos decode straight to 1920 px.
- File jobs run on their own thread instead of Expo's shared one, and slow down or wait under heat.

### Fixed

#### Camera and lifecycle

- Android: the live skeleton was drawn a quarter turn out.
- Android: a camera unmounting after its replacement started took the new session with it.
- iOS: detection stopped after the view left the window and came back; a pause during startup left
  the camera without a preview; overlapping switches left a promise pending.
- A ref method called right after mount was rejected; it now waits for the view.
- `snapshot()` returned a stale frame after detection stopped, paused or went to the background; it
  now returns `null`.
- `getState().active` turned false when only the landmarker failed to build.
- After a stall, such as a resume or switch, two frames ran inference back to back.
- Unrelated prop changes restarted the camera after the rate or heat had moved.

#### Detection, triggers and data

- Smoothing and velocity carried over when a different person became the largest body, and never
  applied below 5 fps.
- Android: a rep finishing while `triggers` was updated could be lost; a `NaN` duration became 0.
- iOS: a prop of exactly 0 or 1, such as `smoothing={{ beta: 0 }}`, was replaced by the default.
- Changing `data.select` or angles reported a spurious `DETECTION_FAILED`.
- `batched` frames buffered before someone left the frame waited for them to come back.
- `onPerformanceChange` never fired for heat under `thermalPolicy` `'off'` or `'critical-only'`.

#### Photos, videos and export

- iOS: portrait videos lost the body on about a third of their frames.
- Android: photos ignored EXIF rotation and decoded at full size.
- Android: exports broke on float frame rates, decode-order decoders, and `content://` videos
  without an extension, and painted a frozen skeleton after the person left.
- Android: a failed image export could leave a truncated `.jpg`.
- Re-exporting under an existing name deleted the earlier file before the new one finished.
- iOS: video jobs failed on a bare file path; an export dropping audio now says so.
- `cancel()` on a job still queued behind another was ignored.

#### Logging, CLI and config plugin

- iOS: mounting a camera without `logLevel` turned logging off app-wide.
- `onLog` and `addLogListener()` heard nothing in some setups, including with no camera mounted.
- `doctor` checked iOS 15.1 instead of 16.4, failed on projects with only one platform, and missed
  hand-copied models such as `pose_landmarker_full (1).task`.
- `fetch-model` ignored a custom Android directory in `react-native.config.js`.
- The bare iOS guide was missing the `AppDelegate` step, so apps built and then failed at launch.

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
