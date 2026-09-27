# Troubleshooting

## The app dies the moment the camera opens, in a simulator

It is not your code. MediaPipe converts each frame to a tensor through Metal, and on a simulator
that conversion fails inside an `absl` check, which calls `abort()`. The process is gone before
anything can catch it, and because it happens on the first camera frame rather than at setup, every
step before it looks like it worked.

**This package forces the CPU delegate in a simulator**, even under `delegate="gpu"`, so it should
not reach you. If you see it anyway, look for `the simulator has no usable GPU for MediaPipe` on
the `detector` log channel, which is printed whenever the request is overridden.

Nothing is lost by it: a simulator has no real GPU to measure, so a GPU reading there could never
have told you anything true about a phone.

## "Native module not found" / blank screen in Expo Go

**Expo Go cannot run this package.** It contains native code. Build a development build:

```bash
npx expo prebuild && npx expo run:ios     # or run:android
```

## `MODEL_NOT_FOUND`

The plugin didn't run or prebuild didn't happen.

```bash
npx expo prebuild --clean
```

Bare RN:

```bash
npx react-native-pose-detection fetch-model full
```

Verify it landed:

```text
android/app/src/main/assets/pose_landmarker_*.task
ios/<YourApp>/Resources/pose_landmarker_*.task
```

## `PERMISSION_DENIED`

`<PoseCamera>` never prompts. It reports this, and does not start, when it mounts before the camera
permission is granted. Ask first, with `useCameraPermission()`, and render the camera once
`granted` is true, see [camera permission](./reference/permissions.md).

Declaring the permission is a separate step. Expo: the config plugin writes it into both native
projects on prebuild. Bare: add `NSCameraUsageDescription` to `Info.plist` yourself; Android's
comes from this package's own manifest.

## `GPU_UNAVAILABLE` (non-fatal)

Not a bug. The device's GPU delegate failed and it fell back to CPU. Expect lower frame rates.
Check `getState().delegate` to confirm which one is running. On Android, `onReady` says `'CPU'` on
most devices under `delegate="auto"`, because the session starts on the CPU and the GPU takes over
a moment later; that move is an `onPerformanceChange` with `reason: 'delegate'`, not this error.

## `UnsatisfiedLinkError` on an emulator

This package pins MediaPipe **0.10.35**, which ships all four ABIs including `x86_64`. If you
have overridden the version down to `0.10.21`, that is the cause: 0.10.21 ships `arm64-v8a`,
`armeabi-v7a` and 32-bit `x86` and **no `x86_64`**, so on an Intel host the package manager picks
`x86_64` as the primary ABI and never extracts MediaPipe's library at all. It fails when the
landmarker is constructed. Undo the override, see
[ADR 0007](../docs/adr/0007-pin-mediapipe-0-10-35.md).

The other way to cause it is `abiFilters` on a debug build. Filter release builds only.

On Apple Silicon, use an arm64 emulator image, which is what Android Studio gives you by default.

## iOS build fails

**`Unable to find a specification for ExpoModulesCore`** almost always means the deployment
target, not the dependency. Expo SDK 56 and later require iOS 16.4, and autolinking silently skips every
Expo pod in an app that targets lower, so the first thing to fail is the one that resolves this
package. Raise `platform :ios` in the Podfile and `IPHONEOS_DEPLOYMENT_TARGET` in the project to
`16.4`. The podspec itself declares 15.1, which is this package's own floor; Expo raises it during
`pod install` and prints that it did.

**`compiling for iOS 15.1, but module 'Expo' has a minimum deployment target of iOS 16.4`** is the
app target still at React Native's 15.1 while the Podfile says 16.4. Raise **General → Minimum
Deployments** on the app target to 16.4; `npx react-native-pose-detection doctor` checks it.

**A Swift compile error inside `expo-modules-jsi` or `expo-modules-core`** is a toolchain that is
too old, not anything in this package. Expo SDK 57 ships `ExpoModulesCore` precompiled with Swift
6.3.1, and an older compiler rejects it; the errors it produces while falling back to Expo's
sources (`abs` ambiguous under C++ interop, `sending 'emitter' risks causing data races`) name the
symptom rather than the cause. Xcode 26.6 or newer builds it.

Check which Xcode is actually selected before concluding anything: `xcode-select -p` can point at
an old copy while a current one sits in `/Applications`. `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`
overrides it for one command, and `sudo xcode-select -s /Applications/Xcode.app` makes it the default.

**`Unable to find a destination matching the provided destination specifier`** means the iOS
platform is not installed, which is separate from the SDK and is the one thing `xcodebuild
-showsdks` will happily list as present while builds fail. `xcodebuild -downloadPlatform iOS`
installs it, about 8.5 GB.

The MediaPipe pin is settled: `MediaPipeTasksVision 0.10.35`, the same version Android uses, and
it resolves from CocoaPods trunk. See [ADR 0007](../docs/adr/0007-pin-mediapipe-0-10-35.md).

## `minSdkVersion` error on Android

MediaPipe requires **API 24+**. In `android/build.gradle`:

```groovy
minSdkVersion = 24
```

## App size much larger than documented

You're shipping a universal APK. It carries all four MediaPipe ABI slices, 45.9 MB of native
library where a phone loads 10.5 MB of it. Ship an AAB, or set this on the release build only:

```groovy
ndk { abiFilters "arm64-v8a" }
```

## Landmarks mirrored or rotated wrong

- **Mirrored:** front-camera landmark `x` is un-mirrored by default so coordinates match the
  real world, not the preview. The overlay compensates automatically.
- **Rotated on Android:** usually a missed `targetRotation` update. File an issue with your
  device model and orientation config.

## Frame rate lower than expected

Check `await getProfile()` first: `phase` tells you whether calibration has settled, and
`p50InferenceMs` is the cost the rate was derived from. Under `profile="auto"` a low number **is**
the calibrated answer: the governor already runs the highest rate that cost sustains, so a low
rate with `limitedBy: 'device'` means expensive inference, not a stuck setting. What you can change:

- the model variant, which is a **build-time** choice and not a prop. Set `"model": "lite"` in
  the plugin config and re-run `npx expo prebuild`, or run
  `npx react-native-pose-detection fetch-model lite` on bare RN. See the
  [config plugin reference](./reference/config-plugin.md)
- `analysisResolution`, which is what the model actually sees
- check `getState().delegate`: on CPU, a lower frame rate is expected
- `maxPoses`: above 1, MediaPipe runs its person detector on every frame in which fewer people are
  in view than `maxPoses` allows, which on a slow phone costs about as much again

A budget Android phone runs `full` at about 10 fps and `lite` at about 15; the measured table is in
[budget Android phones](./performance.md#budget-android-phones).

## The skeleton takes a moment to appear on Android

On a phone with a slow GPU the GPU landmarker takes a couple of seconds to build, so `auto` answers
the first frames on the CPU landmarker, which builds in well under a second, and moves to the GPU
once it is ready. The first skeleton comes about a second after mount on a Redmi Note 12. A camera
screen closed and opened again within a minute takes back the landmarker it left, and is faster
still. `delegate="gpu"` waits for the GPU build instead.

## The screen locks during a workout

The package leaves the screen timeout alone: it is app-wide state, and only your app knows when a
workout starts and ends. Somebody standing back from the phone is not touching it, so the screen
locks after the usual timeout and the camera stops with it. Keep the screen on while the camera is
up, for example with [`expo-keep-awake`](https://docs.expo.dev/versions/latest/sdk/keep-awake/),
which a bare app can use too, since this package already needs Expo modules:

```tsx
import { useKeepAwake } from 'expo-keep-awake';
import { PoseCamera } from 'react-native-pose-detection';

export function Workout() {
  useKeepAwake();
  return <PoseCamera style={{ flex: 1 }} />;
}
```

The example app does this on its camera screens.

## Triggers fire twice / not at all

Firing twice per rep usually means `enter` and `exit` thresholds sit too close together: widen
the gap and add `debounceMs`. Never firing usually means the joint the condition reads is not
visible; gate on `{ visibility: joint, above: 0.6 }` to see. The tuning table in
[what you can build](./recipes.md) covers the rest. A malformed config never fails silently: it throws
at validation with the path to the problem.

## Memory grows over time

The usual cause is retained frames. `frame.landmarks` is a view into the buffer a drain
returned, so keeping one frame keeps the whole batch alive. Copy with `.slice()` if you retain,
see [data delivery](./data-delivery.md#retaining-frames).

Otherwise report it. Include `getState()` output, `data.mode`, `maxPoses`, and what your
`onPose` or `onPoseBatch` handler keeps.

## Watching it work: the log channel

The library ships a diagnostic channel that is **completely off by default** and costs nothing
until you turn it on. Entries reach Logcat on Android and `os.Logger` on iOS whatever is
attached, so `adb logcat` and Console.app work with no listener, and are batched to JavaScript
roughly every 250 ms while one is, with or without a camera on screen: `detectOnImage`,
`detectOnVideo` and `exportPose` log through the same channel.

```ts
import { setLogLevel, addLogListener } from 'react-native-pose-detection';

setLogLevel('debug');

const sub = addLogListener((entries) => {
  entries.forEach((e) => console.log(`[${e.category}] ${e.message}`));
});

// later
sub.remove();
setLogLevel('off');
```

Or raise it only while one camera is mounted, with the `logLevel` prop, and read it with that
camera's `onLog`. An unknown level or category **throws** `PoseConfigError` rather than doing
nothing quietly: a level that silently failed to apply looks exactly like the bug you were trying
to diagnose.

| Level | Shows |
| --- | --- |
| `off` *(default)* | nothing |
| `error` | failures |
| `warn` | degraded but running: GPU fallback, dropped frames, a config native could not read |
| `info` | lifecycle: camera opened, model loaded, calibration settled, heat and Low Power changes |
| `debug` | state transitions: camera switches, rotation, idle search |
| `trace` | per-frame detail, such as frames discarded after a camera switch |

Turn up only what you are investigating, per category:

```ts
setLogLevel({ triggers: 'trace', camera: 'debug', engine: 'off' });
```

Categories: `camera` · `detector` · `engine` · `triggers` · `calibration` · `overlay`.
`LOG_LEVELS` and `LOG_CATEGORIES` are exported if you are building a level picker.

| Problem | Category | Level |
| --- | --- | --- |
| A trigger condition native could not read | `triggers` | `warn` |
| Frame rate lower than expected | `calibration` | `debug` |
| Crash or freeze on camera switch | `camera` | `debug` |
| Model won't load | `detector` | `info` |
| Overlay misaligned | `camera` | `debug` |
| Phone gets hot | `engine` | `info` |

Entries arrive **batched**, an array every ~250 ms rather than one call per line. If more than 256
pile up between two batches, the oldest are dropped rather than growing memory, and the next batch
opens with a `warn` entry carrying the count. `LogEntry.timestamp` uses the same monotonic
clock as `PoseFrame.timestamp`, so a log line can be matched to the exact frame that produced
it.

In production leave it `off`. While off the cost is a single integer comparison: no strings are
built and nothing crosses to JavaScript.

## Before filing a bug

```ts
setLogLevel('debug');
console.log(cam.current?.getState());
console.log(await cam.current?.getProfile());
```

Include both outputs, the log entries around the failure, your device model and OS version.
`getProfile()` carries the useful half: the resolved delegate, the rate, the resolutions, and
the measured inference cost the governor derived the rate from.
