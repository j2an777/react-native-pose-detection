<div align="center">

# react-native-pose-detection

**Real-time pose detection for React Native and Expo.**

33 body landmarks per person, a native skeleton overlay and rep counting, on iOS and Android.
Powered by MediaPipe, running entirely on the device.

[![CI](https://github.com/khalid999devs/react-native-pose-detection/actions/workflows/ci.yml/badge.svg)](https://github.com/khalid999devs/react-native-pose-detection/actions/workflows/ci.yml)
[![npm](https://img.shields.io/npm/v/react-native-pose-detection)](https://www.npmjs.com/package/react-native-pose-detection)
[![license](https://img.shields.io/badge/license-MIT-blue.svg)](https://github.com/khalid999devs/react-native-pose-detection/blob/main/LICENSE)
![platforms](https://img.shields.io/badge/platforms-iOS%20%7C%20Android-black)

[Documentation](https://khalid999devs.github.io/react-native-pose-detection/) · [Installation](#installation) · [Quick start](#quick-start) · [Usage](#usage) · [API](#api-at-a-glance) · [Example app](https://github.com/khalid999devs/react-native-pose-detection/tree/main/example)

![A video frame with the detected pose painted in](https://raw.githubusercontent.com/khalid999devs/react-native-pose-detection/main/ss/export-frame.png)

<img alt="Live pose detection with a skeleton overlay in a React Native app" src="https://raw.githubusercontent.com/khalid999devs/react-native-pose-detection/main/ss/live-camera.png" width="30%" /> <img alt="Pose landmarks painted onto an uploaded video" src="https://raw.githubusercontent.com/khalid999devs/react-native-pose-detection/main/ss/studio-video.png" width="30%" /> <img alt="Pose landmarks painted onto an uploaded photo" src="https://raw.githubusercontent.com/khalid999devs/react-native-pose-detection/main/ss/studio-photo.png" width="30%" />

*Screens from [the example app](https://github.com/khalid999devs/react-native-pose-detection/tree/main/example)*

</div>

## Features

- **One component.** `<PoseCamera />` opens the camera, tracks the body and draws the skeleton.
- **Native triggers.** Count reps and check positions on the camera thread, one event per rep.
- **Landmarks on request.** 33 points per person as typed arrays, only when you ask for them.
- **Tuned to each phone.** 30 fps when the phone keeps up, backing off for heat and battery.
- **Photos and videos.** Landmarks from files, or a copy with the skeleton painted in.
- **Expo and bare React Native.** Models verified and bundled at build time, no runtime dependencies.

## Requirements

| | Minimum |
| --- | --- |
| Expo SDK | 56, in a [development build](https://docs.expo.dev/develop/development-builds/introduction/). Expo Go cannot load native code |
| React Native | 0.85 |
| iOS | 16.4 |
| Android | 7.0 (API 24) |

## Installation

### Expo

```bash
npx expo install react-native-pose-detection
```

Add the config plugin to `app.json`:

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

Then build with `npx expo prebuild`, or `npx expo run:ios` / `npx expo run:android`. The plugin
downloads the model, checks its checksum and adds it to both native projects with the camera
permission. Nothing is downloaded at runtime.

### Bare React Native

```bash
npm i react-native-pose-detection expo@56   # expo@57 on React Native 0.86
npx react-native-pose-detection fetch-model full
```

This package is an Expo module, so a bare app also needs Expo's autolinking, iOS 16.4 in both the
`Podfile` and the Xcode target, and `NSCameraUsageDescription` in `Info.plist`. The
[installation guide](https://khalid999devs.github.io/react-native-pose-detection/installation#bare-react-native)
walks through each step.

Either way, `npx react-native-pose-detection doctor` checks the setup and names anything missing.

### Choosing a model

| Model | Adds to the app | Best for |
| --- | --- | --- |
| `lite` | 5.5 MB | budget phones and the highest frame rates |
| `full` (default) | 9.0 MB | most apps |
| `heavy` | 29.2 MB | accuracy-critical work on flagship phones |

On a budget Android phone, ship `lite`: a Redmi Note 12 runs `full` at about 10 fps and `lite` at
about 15. Changing the model is one word in the config and a rebuild.

## Quick start

```tsx
import { PoseCamera, useCameraPermission } from 'react-native-pose-detection';

export default function App() {
  const { granted } = useCameraPermission();
  return granted ? <PoseCamera style={{ flex: 1 }} /> : null;
}
```

That is a live camera with a tracked skeleton, and nothing crosses to JavaScript.
`useCameraPermission()` asks for the camera when it mounts.

## Usage

### Count reps with native triggers

Describe the movement once. The check runs natively on every frame and calls you once per rep:

```tsx
<PoseCamera
  triggers={[
    {
      id: 'squat',
      enter: { angle: 'leftKnee', below: 90 },
      exit: { angle: 'leftKnee', above: 160 },
      emit: 'cycle',
    },
  ]}
  onTrigger={(e) => setReps(e.count)}
/>
```

Conditions read joint angles, positions, velocities and visibility, and combine with `all` and
`any`. The [triggers guide](https://khalid999devs.github.io/react-native-pose-detection/triggers)
explains them, and [what you can build](https://khalid999devs.github.io/react-native-pose-detection/recipes)
has worked triggers for squats, holds and jumps.

### Read pose landmarks in JavaScript

Frames cross to JavaScript only when you ask, as typed arrays from one shared buffer:

```tsx
<PoseCamera
  data={{ mode: 'throttled', throttleMs: 100, angles: ['leftKnee'] }}
  onPose={(frame) => setKneeAngle(frame.angles?.leftKnee)}
/>
```

`landmark(frame, 'leftWrist')` reads one joint as `{ x, y, z, visibility }`. The other modes,
batching and trimming the payload are in
[data delivery](https://khalid999devs.github.io/react-native-pose-detection/data-delivery).

### Detect poses in photos and videos

```ts
import { detectOnImage, exportPose } from 'react-native-pose-detection';

const poses = await detectOnImage(photoUri);
const { uri } = await exportPose(videoUri, { directory: 'documents' }).result;
```

`detectOnVideo` samples a clip, and `exportPose` writes a full-quality copy with the skeleton
painted in, without slowing a live camera. See
[photos and video files](https://khalid999devs.github.io/react-native-pose-detection/files).

### Control the camera

```tsx
const cam = useRef<PoseCameraRef>(null);
// <PoseCamera ref={cam} active={isFocused} />

await cam.current?.switchCamera(); // resolves once the other lens delivers
const frame = await cam.current?.snapshot(); // the current frame, or null
```

`active={false}` stops the camera while its screen is hidden, and `pause()`, `stopDetection()` and
`setOverlayEnabled(false)` switch parts of it off without unmounting. See
[camera control](https://khalid999devs.github.io/react-native-pose-detection/camera-control).

### Performance

Nothing needs tuning. The package measures inference on each phone, runs at the camera's 30 fps
whenever the phone can sustain it, and slows down for heat, Low Power Mode or an empty frame.
`profile`, `targetFps`, `delegate` and the resolutions override any of it, and `getProfile()`
reports why the rate is what it is. See
[performance](https://khalid999devs.github.io/react-native-pose-detection/performance).

## API at a glance

| | |
| --- | --- |
| [`<PoseCamera>` props](https://khalid999devs.github.io/react-native-pose-detection/reference/pose-camera) | `style` · **camera** `facing` `active` `resolution` · **detection** `detection` `maxPoses` `minConfidence` `smoothing` · **performance** `profile` `targetFps` `analysisResolution` `delegate` `thermalPolicy` · **output** `overlay` `data` `triggers` `logLevel` |
| [Events](https://khalid999devs.github.io/react-native-pose-detection/reference/events) | `onReady` `onError` `onCameraChange` `onPerformanceChange` `onTrigger` `onPose` `onPoseBatch` `onFramesDropped` `onLog` |
| [Ref methods](https://khalid999devs.github.io/react-native-pose-detection/reference/ref-methods) | `switchCamera` `setFacing` `pause` `resume` `startDetection` `stopDetection` `setOverlayEnabled` `setProfile` `getProfile` `getState` `snapshot` |
| [Functions](https://khalid999devs.github.io/react-native-pose-detection/reference/functions) | `detectOnImage` `detectOnVideo` `exportPose` · `useCameraPermission` `getCameraPermission` `requestCameraPermission` · `validateTriggers` `assertValidTriggers` · `landmark` `isVisible` and the other accessors · `setLogLevel` `addLogListener` |
| [Types](https://khalid999devs.github.io/react-native-pose-detection/reference/types) | `PoseFrame`, `JointName`, `Trigger`, `Condition` and every other exported type |
| [Trigger schema](https://khalid999devs.github.io/react-native-pose-detection/reference/trigger-schema) | every condition, `emit` mode and validation rule |
| [Camera permission](https://khalid999devs.github.io/react-native-pose-detection/reference/permissions) | the four states, and why blocked is not denied |
| [Config plugin](https://khalid999devs.github.io/react-native-pose-detection/reference/config-plugin) | `model` `cameraPermissionText` `cacheDir` `skipDownload` |
| [CLI](https://khalid999devs.github.io/react-native-pose-detection/reference/cli) | `fetch-model <lite\|full\|heavy>` `doctor` `clear-cache` |

## Documentation

The [documentation site](https://khalid999devs.github.io/react-native-pose-detection/) is
searchable and reads in this order:

1. [Getting started](https://khalid999devs.github.io/react-native-pose-detection/getting-started): install, first camera, first data
2. [Installation](https://khalid999devs.github.io/react-native-pose-detection/installation): Expo, bare React Native, EAS and release builds
3. [Camera control](https://khalid999devs.github.io/react-native-pose-detection/camera-control): lenses, switching, pausing, lifecycle
4. [Data delivery](https://khalid999devs.github.io/react-native-pose-detection/data-delivery): the four modes and what each costs
5. [Triggers](https://khalid999devs.github.io/react-native-pose-detection/triggers): conditions, phases and snapshots
6. [Photos and video files](https://khalid999devs.github.io/react-native-pose-detection/files): landmarks from files, and painted copies
7. [Performance](https://khalid999devs.github.io/react-native-pose-detection/performance): profiles, heat, battery and app size
8. [What you can build](https://khalid999devs.github.io/react-native-pose-detection/recipes): rep counters, form checks, holds and jumps
9. [Troubleshooting](https://khalid999devs.github.io/react-native-pose-detection/troubleshooting): common problems and the log channel

The [example app](https://github.com/khalid999devs/react-native-pose-detection/tree/main/example) runs all of it, once as an Expo app and once as a bare one.

## Alternatives

- **VisionCamera with an ML Kit pose plugin**, such as `react-native-vision-camera-v3-pose-detection`:
  a fit for an app already built on VisionCamera frame processors. You add a worklets runtime and
  draw the skeleton yourself, and the pose plugins were last published in 2024.
- **`@thinksys/react-native-mediapipe`**: MediaPipe in a native view, MIT licensed, the closest in
  approach to this one.
- **TensorFlow.js** with `@tensorflow/tfjs-react-native`: MoveNet or BlazePose from JavaScript over
  WebGL. Its React Native adapter has had no release since November 2023.
- **QuickPose**: a commercial SDK with ready-made exercises and rep counting.

## Contributing

Issues and pull requests are welcome, especially device reports from phones we have not measured.
Start with [contributing](https://github.com/khalid999devs/react-native-pose-detection/blob/main/docs/contributing.md).

## License

MIT © [khalid999devs](https://github.com/khalid999devs)
