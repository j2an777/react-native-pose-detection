# Camera control

Camera, detection, and overlay are **three independent switches**. Each has a different cost,
and each can be toggled at runtime without tearing anything down.

| Camera | Detection | Overlay | Use case | Cost |
| --- | --- | --- | --- | --- |
| on | on | on | live coaching | full |
| on | on | off | custom UI, headless analysis | no draw |
| on | off | off | plain camera preview | ~0 |
| off | n/a | n/a | screen backgrounded | 0 |

## Declarative

```tsx
<PoseCamera active={isFocused} detection={isRecording} overlay={showSkeleton} />
```

## Imperative

```tsx
const cam = useRef<PoseCameraRef>(null);

await cam.current?.pause();                   // camera off: lowest power short of unmounting
await cam.current?.stopDetection();           // preview stays, inference stops, GPU freed after a minute
await cam.current?.setOverlayEnabled(false);  // drawing stops, inference continues
```

`pause`, `resume`, `startDetection`, `stopDetection` and `setOverlayEnabled` all return
`Promise<void>`, because they reach native over the same asynchronous path as everything else.
Ignoring the promise is fine and common. Awaiting it is the only way to see a failure.
`getState()` is the exception: it reads state JavaScript already mirrors from the events, plus the
live rate read straight from native, so it stays synchronous.

`stopDetection()` stops inference at once and releases the landmarker's GPU resources after a
minute unused, so a `startDetection()` inside that minute is instant rather than a rebuild.

## Drawing angles

The overlay can draw an arc and degree label at any joint:

```tsx
<PoseCamera overlay={{ angles: [{ joint: 'leftElbow' }, { joint: 'leftKnee' }] }} />
```

Rendered natively alongside the skeleton, so no data crosses to JavaScript. Each arc adds its
joint to the set of angles computed per frame, exactly as an `angle` condition in a trigger or a
name in `data.angles` does. Nothing else turns an angle on. See
[overlay config](./reference/pose-camera.md#angle-overlay).

`decimals` on a label is capped at 3. The label is rebuilt on the draw path every frame, so a
larger value would only make that string longer.

## Switching cameras

```tsx
await cam.current?.switchCamera();     // resolves when the session is stable
await cam.current?.setFacing('back');
```

**Await it.** The promise resolves only after the capture session has been reconfigured and the
first frame from the new camera has arrived, or 1.5 seconds have passed without one.
`onCameraChange` is raised at the same point, but it is an event rather than a return value, so it
can reach JavaScript a moment after the promise resolves: sequence on the promise, and use the
event to keep state in step. A failed switch rejects the promise as well as raising `onError`.

A ref method called in the frame or two after `<PoseCamera>` mounts, before the native view
exists, waits for it rather than failing, for up to a second.

Preserved across a switch, because only the camera is rebound and the detector is never
recreated:

- detection on/off state
- overlay configuration
- calibration results, trigger counters and trigger phases

Rapid switching is safe by design: a switch is reported only once the new lens delivers a frame,
a second request made mid-switch queues behind the first so two quick switches go there and back,
every promise settles, and the path rolls back to the previous lens on a failed bind. The 100-switch stress scenario in the example app is the way to
hold it to that on your own hardware.

If the new camera can't be opened, the previous one is restored and you get
`onError({ code: 'CAMERA_SWITCH_FAILED', fatal: false })` plus a rejected promise. This is also
what you get from `switchCamera()` on a device with only one lens: `facing: 'auto'` falls back to
the other lens on the first bind, but an explicit switch to a lens that isn't there fails rather
than quietly succeeding on the lens already running.

## Lifecycle

Backgrounding stops the session automatically, and the landmarker is kept for 30 seconds, so a
quick trip to another app comes back to a skeleton at once. Foregrounding restores both, with the
calibration already measured, so a foreground is never a re-probe. Past 30 seconds the landmarker's
memory is given back and it is rebuilt on return, from the cached GPU check.

You don't need to wire `AppState` yourself. Do use `active` to stop the camera when the screen
is merely out of view. A screen pushed on top of the camera's, a tab you've navigated away from,
or a `FlatList` item scrolled offscreen all leave `<PoseCamera>` mounted, and a mounted camera
keeps capturing, running inference and warming the phone behind whatever is in front of it:

```tsx
import { useIsFocused } from '@react-navigation/native'; // expo-router re-exports it too

function WorkoutScreen() {
  const isFocused = useIsFocused();
  return <PoseCamera active={isFocused} style={{ flex: 1 }} />;
}
```

`active={false}` stops the capture session and parks the landmarker, so coming back within a
minute detects at once. `detection={false}` is the lighter switch: the preview keeps running and
only inference stops, which is the one to use while a paused workout still shows the camera.

## Mirroring

Front-camera landmark `x` is **un-mirrored**, coordinates describe the real world, not the
preview image. `leftWrist` is the subject's actual left wrist regardless of which camera is used.

The overlay mirrors internally so it still aligns with what's on screen. If you draw your own
overlay from `onPose` data, you must mirror it yourself for the front camera.
