# Changelog

All notable changes to this package are documented here. Versions follow
[semantic versioning](https://semver.org), and every published version is an annotated `v*` tag
on the commit that was published.

## Unreleased

### Changed

- `doctor` and `fetch-model` name a pairing that works when React Native is newer than any Expo
  SDK this package pairs with. React Native 0.87 has no SDK, so they suggest React Native 0.86
  with `expo@57` instead of a placeholder.

### Documentation

- A new bare app starts from Expo's bare template, which is always on a React Native an Expo SDK is
  built for, with Expo modules already wired. The React Native CLI's default can have no SDK, as
  0.87 has none.
- The installation guide no longer says `install-expo-modules` stops at React Native 0.78: it wires
  a React Native 0.85 app completely, and stops only on 0.86.

## 0.2.1

A maintenance release on top of 0.2.0: install checks for the Expo SDK, stricter validation, one
Android fix and complete documentation. No API changes.

Coming from 0.1.0? 0.2.0 was only published under the `next` tag, so its notes below apply to you
too. Start with its **Upgrading from 0.1.0** section.

### Upgrading from 0.2.0

Nothing to change, unless your app passes values that were silently ignored before. These now fail
with `PoseConfigError`:

- an unknown level or category in the `logLevel` prop
- an `angles` entry for a joint that has no angle, such as `'nose'`, in `detectOnImage` or
  `detectOnVideo`

### Added

- `doctor` checks that your Expo SDK matches your React Native, SDK 56 for React Native 0.85 and
  SDK 57 for 0.86, and names the version to install; `fetch-model` warns about a mismatch. npm
  cannot catch it, because `expo` accepts any React Native, and the build fails later: `expo@57`
  on React Native 0.85 does not compile for Android.
- Both also catch versions below the minimum, Expo SDK 56 and React Native 0.85, which yarn, pnpm
  and `--legacy-peer-deps` install with only a warning.

### Fixed

- Android: with `delegate="auto"`, the switch from CPU to GPU a moment after start ended idle mode
  even with nobody in frame. `onPerformanceChange` reported it, and the drop to 5 fps came late.
- The `logLevel` prop is validated at render, like `setLogLevel()`. An unknown level or category
  used to be ignored.
- `detectOnImage` and `detectOnVideo` validate `select` and `angles` like the `data` prop. An
  unknown joint used to fail without an error `code`, and an angle for a joint without one was
  skipped.

### Documentation

- The README puts requirements and supported versions first, gives a bare React Native setup that
  pins the Expo SDK, and links every event and function to its reference.
- The API and CLI references are complete: every exported type, all 33 joints, each option's
  default and range, and every `doctor` check.

## 0.2.0

Faster, cooler and steadier on both platforms: the rate follows what the device can do, it slows
down when nobody is in frame or the phone runs hot, and Android shows the first skeleton twice as
fast. No API was removed and no signature changed, but some defaults and behaviors did. Check
these first.

### Upgrading from 0.1.0

- **Minimum versions:** Expo SDK 56, React Native 0.85 and iOS 16.4. The SDK 51 and React Native
  0.74 that 0.1.0 listed could never build. Bare apps set iOS 16.4 in both the `Podfile` and the
  Xcode target.
- **`onPose` and `onPoseBatch` follow `data.mode`:** `'batched'` frames go to `onPoseBatch`,
  `'throttled'` and `'live'` frames to `onPose`. Before, whichever callback was set got them.
- **`smoothing` defaults to `'auto'`:** off for one person, whom MediaPipe already smooths, and on
  for several. Set `smoothing: true` to keep it on.
- **A bad `data` config throws:** an unknown `data.mode`, or an unknown joint in `select` or
  `angles`, throws `PoseConfigError` at render instead of silently delivering nothing.
- **Triggers:** `emit: 'while'` fires only while `enter` holds. A `minDurationMs` hold ends when
  frames stop: a pause, detection turned off, the app in the background, a camera switch.
- **`targetFps`** is capped by the camera and by what the device can keep up with.
- **Clearer errors:** a `delegate="gpu"` that cannot run reports `DETECTOR_INIT_FAILED` at start,
  and a `facing` the device lacks reports `CAMERA_UNAVAILABLE`. Photo and video jobs fail with
  `IMAGE_DECODE_FAILED`, `VIDEO_DECODE_FAILED` or `MODEL_NOT_FOUND`, and with `DETECTION_FAILED`
  only when the model itself fails.
- **The `logLevel` prop** raises the log level only while its camera is mounted, instead of
  replacing the global level.
- **Android `delegate="auto"`** starts on the CPU and moves to the GPU once it is ready, so
  `onReady` reports `'CPU'` and `onPerformanceChange` reports the move with reason `'delegate'`.

### Changed

- **Frame rate:** as fast as the device can sustain with 15% headroom, up to the camera's 30 fps.
  The camera stays at 30 fps, so a dim room no longer halves the rate.
- **Heat:** on Android, `SEVERE` halves the rate instead of pausing, and only `CRITICAL` pauses.
  Both platforms slow down as soon as the device heats up, and speed up again after 30 s cooler.
- **Nobody in frame:** 12 fps after 2 s and 5 fps after 20 s. Low Power Mode and Battery Saver cap
  the rate at 24 fps.
- **Detection off:** `stopDetection()`, `pause()` and `active={false}` keep the model loaded for
  a minute, so turning detection back on is instant. In the background it is freed after 30 s.
- **Visibility** is smoothed over time instead of frames, so joints appear and fade at the same
  speed at 10 fps as at 30.
- **Photos and videos:** `detectOnVideo` returns the largest body in each frame, with its real
  timestamp and measured velocity, and reports progress in 2% steps. `detectOnImage` lists the
  largest body first. File jobs use the GPU when no camera is detecting.
- **`exportPose`** accepts `minConfidence` up to 1.
- **Android** reports the size the camera actually delivers, such as 864x480 when 854x480 was
  requested.

### Added

- `limitedBy` on `getState()`, `getProfile()`, `onReady` and `onPerformanceChange`, saying what
  limits the rate: `camera`, `device`, `target`, `profile`, `thermal`, `lowPower`, `idle` or
  `paused`.
- `thermalState` and `lowPower` on `onPerformanceChange` and `getProfile()`, and `cameraFps` on
  `getProfile()`. `onPerformanceChange` also fires on every heat or Low Power change.
- `getState()` returns live `fps` and `limitedBy` on every call.
- `minConfidence` on `detectOnImage` and `detectOnVideo`.
- `http(s)` photo URLs on Android.
- `NaN` and `Infinity` in props and file options are rejected where they are passed.

### Performance

- Frames reach JavaScript on the JavaScript thread, never waiting behind the main thread.
- iOS draws the skeleton as GPU-composited shape layers, and does no overlay work while it is off.
- Android runs MediaPipe in VIDEO mode on the analysis thread: about a fifth less CPU and almost
  no garbage collection.
- Android shows the first skeleton about twice as fast: 1.1 s instead of 2.2 s on a Redmi Note 12,
  and 0.6 s when a camera screen reopens within a minute.
- Android opens the camera once, with preview and analysis together.
- The GPU check runs once per device and model, and the warm-up runs before the first frame.
- Videos are decoded once, in order, and scaled inside the decoder. Photos decode straight to
  1920 px.
- Photo and video jobs run on their own thread instead of Expo's shared one, and slow down or wait
  when the device is hot.

### Fixed

#### Camera and lifecycle

- Android: the live skeleton was drawn a quarter turn out.
- Android: a camera unmounting after its replacement had started took the new camera's session
  with it.
- iOS: detection stopped after the view left the window and came back, a pause during startup left
  the camera without a preview, and overlapping camera switches left a promise pending.
- A ref method called right after mount was rejected. It now waits for the view.
- `snapshot()` returned an old frame after detection stopped, paused or went to the background. It
  now returns `null`.
- `getState().active` turned false when only the model failed to load.
- After a stall, such as a resume or a camera switch, two frames ran inference back to back.
- Unrelated prop changes restarted the camera once the rate or heat had changed.

#### Detection, triggers and data

- Smoothing and velocity carried over when a different person became the largest body, and never
  applied below 5 fps.
- Android: a rep that finished while `triggers` was being updated could be lost, and a `NaN`
  duration became 0.
- iOS: a prop of exactly 0 or 1, such as `smoothing={{ beta: 0 }}`, was replaced by its default.
- Changing `data.select` or the angles reported a spurious `DETECTION_FAILED`.
- `batched` frames buffered just before somebody left the frame waited until they came back.
- `onPerformanceChange` never reported heat under `thermalPolicy` `'off'` or `'critical-only'`.

#### Photos, videos and export

- iOS: portrait videos lost the body on about a third of their frames.
- Android: photos ignored their EXIF rotation and were decoded at full size.
- Android: exports broke on fractional frame rates, on decoders that return frames out of order,
  and on `content://` videos without a file extension, and kept painting a frozen skeleton after
  the person left.
- Android: a failed image export could leave a truncated `.jpg`.
- Re-exporting under an existing name deleted the earlier file before the new one was finished.
- iOS: video jobs failed on a bare file path, and an export that dropped the audio track did not
  say so.
- `cancel()` on a job still waiting behind another was ignored.

#### Logging, CLI and config plugin

- iOS: mounting a camera without `logLevel` turned logging off for the whole app.
- `onLog` and `addLogListener()` heard nothing in some setups, including with no camera mounted.
- `doctor` checked for iOS 15.1 instead of 16.4, failed on projects with only one platform, and
  missed hand-copied models such as `pose_landmarker_full (1).task`.
- `fetch-model` ignored a custom Android directory in `react-native.config.js`.
- The bare iOS setup guide was missing the `AppDelegate` step, so apps built and then failed at
  launch.

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
