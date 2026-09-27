<div align="center">

# react-native-pose-detection

**Real-time human pose detection for React Native and Expo.**

33 body landmarks per frame, detected and drawn entirely in the native layer, powered by
MediaPipe. Pose estimation, body tracking, joint angles and rep counting for fitness, physio and
sports apps, on the device, with a native skeleton overlay. Works in Expo and bare React Native
projects alike. Nothing crosses the bridge until you ask.

[![CI](https://github.com/khalid999devs/react-native-pose-detection/actions/workflows/ci.yml/badge.svg)](https://github.com/khalid999devs/react-native-pose-detection/actions/workflows/ci.yml)
[![npm](https://img.shields.io/npm/v/react-native-pose-detection)](https://www.npmjs.com/package/react-native-pose-detection)
[![license](https://img.shields.io/badge/license-MIT-blue.svg)](https://github.com/khalid999devs/react-native-pose-detection/blob/main/LICENSE)
![platforms](https://img.shields.io/badge/platforms-iOS%20%7C%20Android-black)

[Installation](#installation) · [Quick start](#quick-start) · [Do more](#do-more) · [API](#api) · [Docs](#documentation) · [Example](https://github.com/khalid999devs/react-native-pose-detection/tree/main/example) · [Docs site](https://khalid999devs.github.io/react-native-pose-detection/)

![A frame of an exported video with the skeleton painted in](https://raw.githubusercontent.com/khalid999devs/react-native-pose-detection/main/ss/export-frame.png)

<img alt="Live camera with the skeleton tracking a person" src="https://raw.githubusercontent.com/khalid999devs/react-native-pose-detection/main/ss/live-camera.png" width="30%" /> <img alt="Studio screen painting an uploaded video" src="https://raw.githubusercontent.com/khalid999devs/react-native-pose-detection/main/ss/studio-video.png" width="30%" /> <img alt="Studio screen painting an uploaded photo" src="https://raw.githubusercontent.com/khalid999devs/react-native-pose-detection/main/ss/studio-photo.png" width="30%" />

*Snaps from [the example app](https://github.com/khalid999devs/react-native-pose-detection/tree/main/example)*

</div>

## Why this one

- **One component.** `<PoseCamera />` opens the camera, finds the body, draws the skeleton.
  Every default overridable, none required.
- **Both install paths, first class.** The Expo config plugin and a CLI for bare React Native
  do the same job; both are built and tested in CI on every commit.
- **Zero bridge traffic by default.** Detection, smoothing, drawing and trigger logic run
  natively. Landmarks cross to JavaScript only when you opt in, as one zero-copy buffer.
- **Tunes itself to the phone.** Measures what inference costs on each phone and runs at the
  camera's 30 fps whenever the phone can with room to spare, slows with heat, idles when nobody is
  in frame, and remembers the answer for the next launch.
- **Native triggers.** Declare "knee bent past 90 degrees for 300 ms", get one event when it
  happens. Thirty reps is thirty bridge crossings, not nine hundred.
- **Files too.** Landmarks from any photo or video on disk, or a full-quality painted copy,
  without slowing the live camera.
- **Zero runtime dependencies.** Peers are `expo`, `react`, `react-native`. No VisionCamera,
  no Reanimated, no worklets.
- **Models handled for you.** Downloaded, checksum-verified and installed at build time. A
  mismatch fails the build, never warns.

## Installation

One package, two setups. Both end in the same place: the model inside your native projects and
the camera permission declared.

### Expo

```bash
npx expo install react-native-pose-detection
```

In **`app.json`**, add the config plugin:

```jsonc
{
  "expo": {
    "plugins": [
      [
        "react-native-pose-detection",
        {
          "model": "full", // 'lite' | 'full' | 'heavy'
          "cameraPermissionText": "We use the camera to analyze your movement."
        }
      ]
    ]
  }
}
```

```bash
npx expo prebuild
```

The plugin installs the model into both native projects and writes the camera permission into
`Info.plist` and `AndroidManifest.xml` for you. Nothing downloads at runtime.

Expo Go is not supported: this package contains native code, so use a development build.

### Bare React Native

```bash
npm i react-native-pose-detection
npx react-native-pose-detection fetch-model full
```

The CLI installs the model into both native projects. Declare the camera permission yourself,
in **`ios/<YourApp>/Info.plist`**:

```xml
<key>NSCameraUsageDescription</key>
<string>We use the camera to analyze your movement.</string>
```

Android needs nothing, since this package's own manifest declares the permission, but repeating it
in **`android/app/src/main/AndroidManifest.xml`** is harmless:

```xml
<uses-permission android:name="android.permission.CAMERA" />
```

Either setup can be verified with `npx react-native-pose-detection doctor`, which checks the
install and names anything missing. Full detail, including EAS and release builds:
[installation guide](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/installation.md).

### Choosing a model

Exactly one ships, whichever you pick. Changing it is one word in the config plus a rebuild.

| Model | Best for |
| --- | --- |
| `lite` | budget devices, the highest frame rates |
| `full` *(default)* | most apps: the accuracy and cost balance |
| `heavy` | accuracy-critical work on flagship hardware |

`full` is the default on both platforms and the right call for most phones. **On a budget
Android phone, ship `lite` instead**: a Redmi Note 12 runs `full` at about 10 fps and `lite` at
about 15. It is one word in the plugin config, `"model": "lite"`, or
`npx react-native-pose-detection fetch-model lite` on bare React Native.

Sizes and the full trade-off table: [app size](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/performance.md#app-size). Measured
budget-phone numbers: [budget Android phones](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/performance.md#budget-android-phones).

## Quick start

**`App.tsx`**

```tsx
import { PoseCamera, useCameraPermission } from 'react-native-pose-detection';

export default function App() {
  const permission = useCameraPermission();
  if (!permission.granted) return null;

  return <PoseCamera style={{ flex: 1 }} />;
}
```

That is a live camera with a tracked skeleton, tuned to the device, zero bridge traffic.

## Do more

**Count reps without streaming a single coordinate.** The condition runs on the camera thread;
you hear about it once per rep:

```tsx
<PoseCamera
  triggers={[
    {
      id: 'squat',
      enter: { angle: 'leftKnee', below: 90 },
      exit: { angle: 'leftKnee', above: 160 },
      emit: 'cycle',
      debounceMs: 300,
    },
  ]}
  onTrigger={(e) => setReps(e.count)}
/>
```

**Read landmarks when you actually want them**, as typed arrays from one shared buffer:

```tsx
<PoseCamera
  data={{ mode: 'throttled', throttleMs: 100, angles: ['leftKnee'] }}
  onPose={(frame) => {
    // frame.landmarks is a Float32Array of [x, y, z, visibility] per joint
    setKneeAngle(frame.angles?.leftKnee);
  }}
/>
```

**Paint a photo or video** into a full-quality copy, without slowing the live camera:

```ts
import { exportPose } from 'react-native-pose-detection';

const { uri } = await exportPose(videoUri, { directory: 'documents' }).result;
```

## It tunes itself

No frame-rate table to maintain. The package measures what inference costs on each phone and
runs as fast as the camera delivers whenever the phone can keep that up with 15% to spare, which
is 30 fps on any recent phone. Heat halves it and then pauses it, Low Power Mode caps it, and
nobody in frame drops it to an idle search. Every rate says why it is what it is:

```ts
await cam.current.getProfile();
// { phase: 'settled', tier: 'high',
//   resolved: { delegate: 'GPU', targetFps: 30, preview: '1080p', analysis: '480p' },
//   p50InferenceMs: 16.2, measuredFps: 30, limitedBy: 'camera', thermalState: 'nominal' }
```

Every axis is still yours: `profile`, `targetFps`, `resolution`, `analysisResolution`,
`delegate`, `thermalPolicy`.

## API

Everything the package exports, each linked to its full reference: the component's
[props](#posecamera-props), [events](#events) and [ref methods](#ref-methods), then the
[functions](#functions), the [trigger](#triggers) format and the [CLI](#cli).

### `<PoseCamera>` props

Every prop on one component. All of them optional; an explicit value pins that axis and the
rest stay automatic.

```tsx
<PoseCamera
  ref={cam}
  style={{ flex: 1 }}
  // camera
  facing="front"                    // 'auto' | 'front' | 'back'
  active={isFocused}                // the whole session on/off
  detection={true}                  // inference on/off; off parks the model
  resolution="auto"                 // preview: '480p' | '720p' | '1080p'
  // detection
  maxPoses={1}                      // 1 to 5
  minConfidence={0.6}               // what counts as a body
  smoothing="auto"                  // off for one pose, which MediaPipe smooths already
  // performance
  profile="auto"                    // 'efficient' | 'balanced' | 'quality' | 'unrestricted'
  targetFps="auto"                  // a number replaces the governed rate
  analysisResolution="auto"         // what the model sees: '360p' | '480p' | '720p'
  delegate="auto"                   // 'gpu' | 'cpu'
  thermalPolicy="adaptive"          // 'critical-only' | 'off'
  // drawing, all native
  overlay={{
    color: '#00E5FF',
    lineWidth: 3,
    pointRadius: 4,
    angles: [{ joint: 'leftKnee' }, { joint: 'rightKnee' }],
  }}
  // data out, off unless asked
  data={{ mode: 'throttled', throttleMs: 100, select: ['leftKnee', 'rightKnee'] }}
  triggers={[squatTrigger]}
  logLevel="off"
  // events
  onReady={(e) => console.log(e.delegate, e.targetFps)}
  onError={(e) => console.warn(e.code, e.message)}
  onCameraChange={(e) => setFacing(e.facing)}
  onPerformanceChange={(e) => console.log(e.reason, e.targetFps)}
  onTrigger={(e) => setReps(e.count)}
  onPose={(frame) => setFrame(frame)}
  onLog={(entries) => entries.forEach((e) => console.log(e.message))}
/>
```

| Prop | Default | What it does |
| --- | --- | --- |
| `style` | none | View style; `{ flex: 1 }` is the usual answer |
| `facing` | `'auto'` | Which lens, `'front'` or `'back'`; auto prefers front |
| `active` | `true` | Camera session on/off |
| `detection` | `true` | Inference on/off; `false` stops it at once and frees the model after a minute |
| `overlay` | `true` | The skeleton; boolean or a config object |
| `smoothing` | `'auto'` | One Euro filter: off for one pose, on for several; boolean or `{ minCutoff, beta }` |
| `maxPoses` | `1` | Detection ceiling, `1` to `5` |
| `minConfidence` | unset = auto | What counts as a body, `0.1` to `1`; unset follows `maxPoses`: 0.6 for one person, 0.3 above |
| `profile` | `'auto'` | Performance envelope: `'efficient'` `'balanced'` `'quality'` `'unrestricted'` |
| `targetFps` | `'auto'` | Inference rate; a number replaces the governed rate, capped by the camera |
| `resolution` | `'auto'` | Preview: `'480p'` `'720p'` `'1080p'` |
| `analysisResolution` | `'auto'` | What the model sees: `'360p'` `'480p'` `'720p'` |
| `delegate` | `'auto'` | Inference engine, `'gpu'` or `'cpu'`; auto probes and falls back |
| `thermalPolicy` | `'adaptive'` | Heat response: `'critical-only'` or `'off'`; off never stops reporting |
| `data` | `{ mode: 'off' }` | What crosses to JavaScript: `'throttled'` `'batched'` `'live'` |
| `triggers` | `[]` | Native conditions, validated at render |
| `logLevel` | `'off'` | Diagnostics, `'error'` through `'trace'`, global or per category |
| `onReady` … `onLog` | none | Callbacks: lifecycle, errors, performance, triggers, frames, logs |

Exact types, clamping rules and edge behavior: [`<PoseCamera>` reference](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/reference/pose-camera.md).

### Events

| Callback | Fires | Carries |
| --- | --- | --- |
| [`onReady`](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/reference/events.md#onready) | the camera is up and the model running or failed to start, once per session | `delegate`, `model`, `targetFps`, sizes |
| [`onError`](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/reference/events.md#onerror) | something failed; `fatal` says whether the camera or detection stopped | `code`, `message`, `fatal` |
| [`onCameraChange`](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/reference/events.md#oncamerachange) | a lens switch finished | `facing` |
| [`onPerformanceChange`](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/reference/events.md#onperformancechange) | the rate or the delegate moved, or heat or Low Power Mode changed | `reason`, `targetFps`, `delegate`, `limitedBy` |
| [`onTrigger`](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/reference/events.md#ontrigger) | a trigger entered, exited or completed a cycle | `id`, `phase`, `count`, `durationMs` |
| [`onPose`](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/reference/events.md#onpose-and-onposebatch) | a frame, with `data.mode` `'throttled'` or `'live'` | a `PoseFrame` |
| [`onPoseBatch`](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/reference/events.md#onpose-and-onposebatch) | frames, with `data.mode: 'batched'` | `PoseFrame[]` |
| [`onFramesDropped`](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/reference/events.md#onframesdropped) | your frame handler fell behind | a count |
| [`onLog`](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/reference/events.md#onlog) | diagnostic entries, while logging is on | `LogEntry[]` |

Every error code, and which ones stop the camera: [error codes](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/reference/events.md#error-codes).

### Ref methods

```tsx
const cam = useRef<PoseCameraRef>(null);
// <PoseCamera ref={cam} />
await cam.current?.switchCamera();
```

| Method | Does |
| --- | --- |
| `switchCamera()`, `setFacing(facing)` | flips the lens or picks one, resolving once the new one delivers |
| `pause()`, `resume()` | stops and restarts the camera session |
| `startDetection()`, `stopDetection()` | turns inference on and off with the preview still running |
| `setOverlayEnabled(enabled)` | shows or hides the skeleton |
| `setProfile(profile)`, `getProfile()` | picks a performance profile; reads what was measured and why |
| `getState()` | facing, rate, delegate and more, synchronously |
| `snapshot()` | the current frame, whatever `data.mode` is |

Guarantees and edge cases: [ref methods reference](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/reference/ref-methods.md).

### Functions

```ts
import { detectOnImage, exportPose, useCameraPermission } from 'react-native-pose-detection';
```

| Function | Does |
| --- | --- |
| [`detectOnImage(uri, options?)`](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/reference/functions.md#detectonimage) | landmarks from a photo |
| [`detectOnVideo(uri, options?)`](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/reference/functions.md#detectonvideo) | landmarks from a video, sampled and cancellable |
| [`exportPose(uri, options?)`](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/reference/functions.md#exportpose) | a copy of a photo or video with the skeleton painted in |
| [`useCameraPermission(options?)`](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/reference/functions.md#usecamerapermission) | the camera permission as React state |
| [`getCameraPermission()`, `requestCameraPermission()`](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/reference/functions.md#camera-permission) | reads the permission, or asks for it |
| [`validateTriggers()`, `assertValidTriggers()`](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/reference/functions.md#triggers) | checks trigger configs before they render |
| [`landmark()`, `isVisible()` and the other accessors](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/reference/functions.md#reading-a-frame) | read one joint out of a `PoseFrame` |
| [`setLogLevel()`, `addLogListener()`](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/reference/functions.md#diagnostics) | the diagnostic log channel |

Every export on one page, constants included: [functions reference](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/reference/functions.md).

### Triggers

A trigger is a condition on the body, checked natively on every frame, that sends one
`onTrigger` event when it starts or stops holding:

```ts
type Trigger = {
  id: string;
  enter: Condition;          // when it starts holding
  exit?: Condition;          // when it stops; required for 'cycle' and 'exit'
  emit: 'enter' | 'exit' | 'cycle' | 'while';
  debounceMs?: number;       // ignore re-fires this soon after one
  minDurationMs?: number;    // must hold this long first
  snapshot?: boolean;        // attach the frame it fired on
  throttleMs?: number;       // 'while' only
};
```

A `Condition` is one of `angle`, `landmarkX`, `landmarkY`, `velocityX`, `velocityY` or
`visibility`, with `below` and `above` bounds (`visibility` takes `above` only), or `between` for
an angle. `all` and `any` combine them. Writing them: [triggers guide](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/triggers.md). Every field and validation
rule: [trigger schema](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/reference/trigger-schema.md). Worked ones for squats, holds and
jumps: [what you can build](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/recipes.md).

### CLI

| Command | Does |
| --- | --- |
| `npx react-native-pose-detection fetch-model <lite\|full\|heavy>` | downloads, verifies and installs a model, for bare React Native |
| `npx react-native-pose-detection doctor` | checks the install and names anything missing |
| `npx react-native-pose-detection clear-cache` | deletes the downloaded models |

Flags and output: [CLI reference](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/reference/cli.md). Expo's equivalent is the
[config plugin](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/reference/config-plugin.md).

## Alternatives, honestly

- **VisionCamera with an ML Kit pose plugin**, such as `react-native-vision-camera-v3-pose-detection`:
  the natural choice for an app already built on VisionCamera frame processors. You add VisionCamera
  and a worklets runtime, and draw the skeleton yourself. The pose plugins were last published in
  2024.
- **`@thinksys/react-native-mediapipe`**: the closest in approach to this one, MediaPipe in a native
  view, MIT licensed.
- **TensorFlow.js** with `@tensorflow/tfjs-react-native`: runs MoveNet or BlazePose from JavaScript
  over WebGL, with `expo-gl` and `expo-camera`. Its React Native adapter has not had a release
  since November 2023.
- **QuickPose**: a commercial SDK with ready-made exercises and rep counting. Free for up to 10
  monthly active devices, and from $25 a month above that.

This package is MIT licensed, adds no camera library, keeps detection and drawing native, lets you
declare your own exercises as triggers, and does photos and videos, painted copies included.

## Requirements

| | Minimum |
| --- | --- |
| React Native | 0.85 |
| Expo SDK | 56 |
| iOS | 16.4 |
| Android | API 24 |

Expo Go cannot run native code, so use a development build. The JavaScript itself is about
70 KB with zero runtime dependencies.

## Documentation

Everything below is also on the **[documentation site](https://khalid999devs.github.io/react-native-pose-detection/)**,
searchable, with a page per guide.

| Guide | Covers |
| --- | --- |
| [Getting started](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/getting-started.md) | Install, first camera, first data |
| [Installation](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/installation.md) | Expo, bare RN, EAS, release builds |
| [Camera control](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/camera-control.md) | Lenses, switching, pausing, lifecycle |
| [Data delivery](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/data-delivery.md) | Modes, the wire format, retention |
| [Triggers](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/triggers.md) | Conditions, phases, snapshots |
| [Photos and video files](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/files.md) | Landmarks from files, painted copies |
| [Performance](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/performance.md) | Profiles, the governor, thermal, app size |
| [What you can build](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/recipes.md) | Trigger syntax, feasibility, limits |
| [Troubleshooting](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/troubleshooting.md) | Real problems, and the log channel |

| Reference | Covers |
| --- | --- |
| [`<PoseCamera>` props](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/reference/pose-camera.md) | Every prop, its default and its range |
| [Events](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/reference/events.md) | Every callback, its payload, every error code |
| [Ref methods](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/reference/ref-methods.md) | `switchCamera`, `snapshot`, `getProfile`, … |
| [Functions](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/reference/functions.md) | Files, permission, validation, accessors, logging, constants |
| [Types](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/reference/types.md) | `PoseFrame`, the wire format, joint names |
| [Trigger schema](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/reference/trigger-schema.md) | Conditions, emit modes, validation rules |
| [Camera permission](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/reference/permissions.md) | The four states, and why blocked is not denied |
| [Config plugin](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/reference/config-plugin.md) | `app.json` options |
| [CLI](https://github.com/khalid999devs/react-native-pose-detection/blob/main/guides/reference/cli.md) | `fetch-model`, `doctor`, `clear-cache` |

The [example app](https://github.com/khalid999devs/react-native-pose-detection/tree/main/example) shows all of it running: a live camera with every prop on a
panel, a studio that paints picked files, and a diagnostics screen with stress scenarios. It
exists twice, once per install path, so both stay proven end to end.

## Contributing

Issues and PRs are welcome, especially device reports from hardware we have not measured. Start
with [contributing](https://github.com/khalid999devs/react-native-pose-detection/blob/main/docs/contributing.md).

## License

MIT © [khalid999devs](https://github.com/khalid999devs)
