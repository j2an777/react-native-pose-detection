# Ref methods

```tsx
const cam = useRef<PoseCameraRef>(null);
<PoseCamera ref={cam} />
```

```ts
type PoseCameraRef = {
  switchCamera(): Promise<void>;
  setFacing(facing: 'front' | 'back'): Promise<void>;

  pause(): Promise<void>;
  resume(): Promise<void>;
  startDetection(): Promise<void>;
  stopDetection(): Promise<void>;
  setOverlayEnabled(enabled: boolean): Promise<void>;

  setProfile(profile: Profile): void;
  getProfile(): Promise<ProfileState>;
  getState(): CameraState;

  snapshot(): Promise<PoseFrame | null>;
  takePhoto(options?: TakePhotoOptions): Promise<Photo>;
};
```

Everything except `getState` and `setProfile` returns a promise, because it reaches native over
the same asynchronous path every other call takes (`snapshot` excepted, see below). Ignoring the
promise is fine and common. Awaiting it is how you see a failure instead of losing it.

A method called right after mount, before the native view is ready, waits up to a second for it
rather than failing. Once the camera has unmounted, the camera and detection methods and
`setProfile()` resolve without doing anything, and `getProfile()` rejects with a plain `Error`
that carries no `code`, as it does when the camera unmounts before answering.

`getState()` stays synchronous: it merges a local mirror of the events that carry camera state
with `fps` and `limitedBy`, which move between events and which it reads from native directly on
the JavaScript thread. None of it is a trip across the bridge. Its `deviceTier` is the one
`onReady` reported; `getProfile()` has the tier calibration has refined since. Until `onReady`
arrives, `facing`, `delegate` and `deviceTier` are placeholders: `'front'`, `'CPU'` and
`'medium'`.

## Camera

| Method | Notes |
| --- | --- |
| `switchCamera()` | Toggles front/back. **Resolves only when the session is stable again**, meaning the new camera has delivered a frame (or 1.5 seconds passed without one), not when the rebind returns. Detection state and trigger counters are preserved. `onCameraChange` is raised at the same moment, but as an event it can reach JavaScript just after the promise resolves, so sequence on the promise. |
| `setFacing(f)` | Same guarantees, explicit target. Already on that lens, nothing is rebound, but it still settles on the next frame and raises `onCameraChange`. |
| `pause()` / `resume()` | Stops the capture session entirely and parks the landmarker, so a resume within a minute detects at once. Lowest power state short of unmounting. |

A switch to a lens the device does not have fails with `CAMERA_SWITCH_FAILED` and rolls back to
the camera you were on. That is deliberately different from `facing: 'auto'`, which falls back to
the other lens on the first bind: an explicit request that cannot be honored must not report
success.

## Detection

| Method | Notes |
| --- | --- |
| `startDetection()` / `stopDetection()` | Preview keeps running. `stopDetection()` stops inference at once and **frees the landmarker's memory after a minute unused**, so a `startDetection()` inside that minute is instant rather than a rebuild. |
| `setOverlayEnabled(b)` | Drawing only. Inference continues: use when you draw your own UI. Off, the overlay does no work at all. |
| `snapshot()` | Current `PoseFrame` on demand, regardless of `data.mode`. Resolves to `null` if no pose is present. Read synchronously on the JavaScript thread, so the promise is already settled when it is returned, see [ADR 0008](../../docs/adr/0008-frames-are-drained-not-pushed.md). A buffer that cannot be decoded rejects it with a plain `Error`. |
| `takePhoto(o)` | A still from the running session, written to the cache directory. Detection and the preview keep going. Front-camera stills match the mirrored preview unless `mirrorFront: false`. Rejects with `CAPTURE_FAILED` when the camera cannot add a capture output beside the analysis one, which an entry-level Android camera cannot; detection still runs there. Nothing prunes the files, so move or delete what you keep. |
| `setProfile(p)` | Applies a performance profile at once, rather than at the next render. It returns nothing, so there is no failure to await; `getProfile()` shows what took effect. See [performance](../performance.md). |

## Introspection

```ts
cam.current?.getState();
// { facing: 'front', active: true, detecting: true, fps: 24,
//   delegate: 'GPU', deviceTier: 'medium', limitedBy: 'device' }
```

`fps` counts completed inferences over the last second, so it is what actually ran rather than
what was asked for, and it reads 0 two seconds after results stop. `limitedBy` says why the rate
is what it is: `camera`, `device`, `target`, `profile`, `thermal`, `lowPower`, `idle` or
`paused`. Both are read live on every call, so polling `getState()` for a readout is cheap and
current.

### `getProfile` is async, `getState` is not

```ts
await cam.current?.getProfile();
```

`getProfile()` reads the calibration, whose phase, source and measured p50 are on no event, so
JavaScript has nothing to mirror them from. Its output belongs in any performance bug report: it
says what tier was chosen, how it was chosen, what the inference actually cost, and what the
camera, the heat and Low Power Mode allowed.
