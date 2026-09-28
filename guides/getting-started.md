# Getting Started

**Pre-1.0.** Both platforms are complete and everything on this page runs on both, verified on
physical hardware: an iPhone 15 and a Redmi Note 12, a budget Android phone.

## Requirements

| | |
| --- | --- |
| React Native | 0.85+ |
| Expo SDK | 56+ (dev client or EAS Build) |
| iOS | 16.4+, which Expo's `ExpoModulesCore` requires from SDK 56 |
| Android | API 24+ |
| Architecture | new. React Native 0.82 removed the legacy one, so there is nothing to choose |

**Expo Go is not supported** and never will be. This package contains native code.
Use a [development build](https://docs.expo.dev/develop/development-builds/introduction/).

## Install

```bash
npm i react-native-pose-detection
```

### Expo

```json
{
  "expo": {
    "plugins": [
      ["react-native-pose-detection", {
        "model": "full",
        "cameraPermissionText": "We use the camera to analyze your movement."
      }]
    ]
  }
}
```

```bash
npx expo prebuild
```

The plugin downloads the selected model, verifies its checksum, caches it, and copies it into
both native projects. Nothing is committed to your repo.

```text
› model "full" not in cache
› downloading pose_landmarker_full.task (9.0 MB)…
› sha256 ✓
› copied → android/app/src/main/assets/pose_landmarker_full.task
› copied → ios/YourApp/Resources/pose_landmarker_full.task
```

### Bare React Native

```bash
npm i expo@56   # the Expo SDK built for React Native 0.85; expo@57 on 0.86
npx react-native-pose-detection fetch-model full
```

`expo` provides the autolinking that links this package's native code; it does not make your app
an Expo app. Wire that autolinking into both native projects once, as
[installation](./installation.md#wiring-expo-modules-into-an-existing-app) walks through, set the
iOS deployment target to 16.4, then run `pod install` in `ios/`.

Then add `NSCameraUsageDescription` to `Info.plist`. Android needs nothing for the permission:
this package declares `android.permission.CAMERA` in its own manifest and the merger adds it to
your app.

## Choosing a model

| Model | App size added | Best for |
| --- | --- | --- |
| `lite` | ~5.5 MB | budget Android, high frame rates |
| `full` *(default)* | ~9.0 MB | most apps |
| `heavy` | ~29.2 MB | accuracy-critical, flagship devices |

Changing it is one word in `app.json` plus `npx expo prebuild`, or
`npx react-native-pose-detection fetch-model lite` in a bare app.

## First camera

Declaring the permission is not the same as being granted it. One hook asks and reports:

```tsx
import { PoseCamera, useCameraPermission } from 'react-native-pose-detection';

export default function App() {
  const { granted } = useCameraPermission();
  return granted ? <PoseCamera style={{ flex: 1 }} /> : null;
}
```

That gives you a live camera with a skeleton overlay drawn natively, and **zero data crossing to
JavaScript**.

`useCameraPermission()` prompts on mount. Pass `{ ask: false }` to read the status without
prompting and call `request()` at a moment you choose. It also tells you when a refusal is
permanent, which is the case an "allow" button cannot fix: see
[camera permission](./reference/permissions.md).

## Getting data out

Nothing crosses the bridge until you ask. Three ways, cheapest first:

```tsx
// 1. Triggers: fires when something happens (~1 crossing per event)
<PoseCamera
  triggers={[{ id: 'rep',
               enter: { angle: 'leftKnee', below: 90 },
               exit:  { angle: 'leftKnee', above: 160 },
               emit: 'cycle' }]}
  onTrigger={(e) => setReps(e.count)}
/>

// 2. Batched: every frame, 4 crossings/sec
<PoseCamera data={{ mode: 'batched', flushMs: 500 }} onPoseBatch={handle} />

// 3. Throttled: latest frame at 10 Hz, 20 crossings/sec
<PoseCamera data={{ mode: 'throttled', throttleMs: 100 }} onPose={handle} />
```

Two crossings per emission rather than one, because native signals and JavaScript pulls. See
[data delivery](./data-delivery.md#modes) for why.

Prefer triggers. See [triggers](./triggers.md) and [what you can build](./recipes.md).

## Next

- [`<PoseCamera>` reference](./reference/pose-camera.md): every prop, and the [events](./reference/events.md)
- [Performance](./performance.md): profiles, calibration, app size
- [Troubleshooting](./troubleshooting.md): when something doesn't work
