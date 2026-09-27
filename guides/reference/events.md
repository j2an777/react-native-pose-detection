# Events

| Callback | Fires | Rate |
| --- | --- | --- |
| [`onReady`](#onready) | camera up, and the landmarker running or failed to start | once per session start |
| [`onError`](#onerror) | a failure occurred; every code is in [error codes](#error-codes) | rare |
| [`onCameraChange`](#oncamerachange) | switch complete and stable | per switch |
| [`onPerformanceChange`](#onperformancechange) | the rate, the delegate or the reason for either changed | rare |
| [`onTrigger`](#ontrigger) | a trigger transitioned | ~1 per event |
| [`onPose`](#onpose-and-onposebatch) | frame delivered | 10/s or 30/s |
| [`onPoseBatch`](#onpose-and-onposebatch) | buffer flushed | 2/s |
| [`onFramesDropped`](#onframesdropped) | the ring buffer dropped frames | per delivery |
| [`onLog`](#onlog) | a batch of log entries | ~4/s while logging |

Every one is implemented on both platforms. Three of them are not native events at all:
`onPose`, `onPoseBatch` and `onFramesDropped` are called by `<PoseCamera>` after it drains the
native ring buffer, because an event cannot carry an ArrayBuffer and a function return can. See
[ADR 0008](../../docs/adr/0008-frames-are-drained-not-pushed.md).

With the defaults (`data.mode` unset, no triggers) only `onReady` fires.

## `onReady`

```ts
type ReadyEvent = {
  model: 'lite' | 'full' | 'heavy';
  delegate: 'GPU' | 'CPU';              // what was actually used
  delegateRequested: 'auto' | 'gpu' | 'cpu';
  targetFps: number;
  limitedBy: LimitedBy;                 // why targetFps is what it is
  deviceTier: 'high' | 'medium' | 'low';
  resolution: { width: number; height: number };
  analysisResolution: { width: number; height: number };
  facing: 'front' | 'back';
};
```

A session starts on mount, and again when `active` goes back to `true` or when a `resolution`,
`analysisResolution` or `profile` change moves the camera's sizes and restarts it, so each of those
fires one more `onReady`. When the landmarker cannot be built, `onError` reports `DETECTOR_INIT_FAILED` first and
`onReady` still follows, because the camera did come up.

`targetFps` and `deviceTier` are what the session opened with: the cached calibration when this
device has run before, the static probe's guess when it has not. The governor refines both within
a couple of seconds and reports each move through `onPerformanceChange`. `limitedBy` takes the
values listed in [performance](../performance.md#why-the-rate-is-what-it-is).

On Android, `delegate="auto"` reports `'CPU'` here on a device whose GPU works: the session starts
on the CPU landmarker, which builds in a fraction of the time, and the GPU one takes over once it
has built, reported by `onPerformanceChange` with `reason: 'delegate'`.

## `onError`

```ts
type ErrorEvent = {
  code: ErrorCode;
  message: string;
  fatal: boolean;
};
```

### Error codes

This is the complete list. Native emits nothing outside it, so a `switch` on `code` can be
exhaustive and a new failure mode has to be added here rather than appearing as a new string.
`ERROR_CODES` is exported if you need to iterate them.

| Code | Fatal | Meaning |
| --- | --- | --- |
| `PERMISSION_DENIED` | ✅ | Camera permission refused |
| `MODEL_NOT_FOUND` | ✅ | Plugin didn't run, or prebuild was skipped |
| `MODEL_LOAD_FAILED` | ✅ | Reserved, not sent today: a model that is present but will not load reports `DETECTOR_INIT_FAILED` |
| `CAMERA_UNAVAILABLE` | ✅ | No camera for the requested facing, including a pinned `facing` the device does not have |
| `CAMERA_START_FAILED` | ✅ | The capture session could not be started |
| `DETECTOR_INIT_FAILED` | ✅ | Landmarker could not be created on either delegate |
| `INVALID_CONFIG` | ✅ | Native rejected a prop or trigger config |
| `IMAGE_DECODE_FAILED` | ✅ | `detectOnImage` could not read the source |
| `VIDEO_DECODE_FAILED` | ✅ | `detectOnVideo` could not read the source |
| `CAMERA_SWITCH_FAILED` | ❌ | Rolled back to the previous camera |
| `GPU_UNAVAILABLE` | ❌ | Fell back to CPU: expect lower frame rates |
| `DETECTION_FAILED` | ❌ | One frame, or one drained batch, failed; the pipeline continues |
| `EXPORT_FAILED` | ❌ | `exportPose` could not read, paint or write the file |
| `EXPORT_CANCELLED` | ❌ | `exportPose` was cancelled; the partial file was deleted |

The last two never arrive on `onError`. They are the codes `exportPose` rejects with, and they
are in the same set so that one exhaustive switch covers every failure this package reports.

`fatal: false` is normal operation, not a bug. Only `fatal: true` means the camera stopped.

`IMAGE_DECODE_FAILED` and `VIDEO_DECODE_FAILED` never arrive on `onError` either: `detectOnImage`
and `detectOnVideo` reject with them when the file cannot be read, with `MODEL_NOT_FOUND` when no
model is bundled, and with `DETECTION_FAILED` when the file was read but inference failed. The set
is closed on purpose, so a new failure mode is a deliberate addition rather than a surprise for
anyone switching exhaustively.

`DETECTION_FAILED` also covers a frame buffer that could not be decoded. `decodeFrames` never
throws, because it runs inside the drain loop and a throw there would stall the loop permanently.
It returns the problem instead and `<PoseCamera>` reports it here, non-fatally. A batch whose
joint count or angle count disagrees with the current props is dropped rather than relabelled:
attaching the wrong joint names would silently hand you another joint's numbers, and dropping one
drain is self-healing.

`INVALID_CONFIG` should be unreachable from a typed call site. Trigger configs are
[validated in JavaScript](./trigger-schema.md#validation) during render, so reaching native with a
bad one means the config was built dynamically and skipped that check.

## `onCameraChange`

```ts
type CameraChangeEvent = { facing: 'front' | 'back' };
```

Fires **after** the session is stable, not when the switch begins.

## `onPerformanceChange`

```ts
type PerformanceEvent = {
  reason:
    | 'calibration' | 'thermal' | 'lowPower' | 'idle'
    | 'delegate' | 'gpu_fallback' | 'load' | 'headroom';
  delegate: 'GPU' | 'CPU';
  targetFps: number;
  limitedBy: LimitedBy;   // why targetFps is what it is
  analysisResolution: { width: number; height: number };
  actualFps: number;
};
```

Fires on every automatic adjustment. Still fires under `thermalPolicy="off"`, the library
stops acting, never stops reporting.

| `reason` | When |
| --- | --- |
| `calibration` | The measured cost of inference moved the rate |
| `thermal` | Heat moved the rate, or paused detection |
| `lowPower` | Battery Saver or Low Power Mode came on or went off |
| `idle` | Nobody in frame for a while, or somebody back |
| `delegate` | Android, `delegate="auto"`: the GPU took over from the CPU the session started on |
| `gpu_fallback` | The GPU kept failing and detection moved to the CPU |
| `load`, `headroom` | Reserved, not sent today |

## `onTrigger`

```ts
type TriggerEvent = {
  id: string;
  phase: 'enter' | 'exit' | 'cycle';
  count: number;          // completed cycles since mount
  timestamp: number;      // ms, monotonic
  durationMs?: number;    // enter → exit, on 'cycle'
  snapshot?: PoseFrame;   // if the trigger set snapshot: true
};
```

### How `snapshot` actually arrives

A `PoseFrame` cannot ride an event: an event payload cannot carry an ArrayBuffer through Expo
Modules. So native holds the captured frame and puts a claim ticket on the event instead, and
`<PoseCamera>` redeems it over the function-return path before it calls you. See
[ADR 0009](../../docs/adr/0009-trigger-snapshots-are-claimed.md).

The consequence you can observe: a trigger with `snapshot: true` is delivered at least one
microtask later than a plain one, because the redemption is an awaited call. Snapshot triggers are
therefore not ordered against plain ones, and `timestamp` is what you should sort or compare on,
not arrival order. If the redemption fails, or the ticket was already spent, `onTrigger` still
fires with `snapshot` absent rather than not firing at all.

## `onPose` and `onPoseBatch`

```ts
onPose?: (frame: PoseFrame) => void;
onPoseBatch?: (frames: readonly PoseFrame[]) => void;
```

Mutually exclusive, `data.mode` decides which fires. Passing the wrong one is a no-op and
warns in development.

A frame's `landmarks` is a `subarray` view into the ArrayBuffer that drain returned, not a copy.
Nothing is parsed and nothing is allocated per landmark, which is the point. Two things follow.
Retaining a frame past the callback retains the entire drained buffer, and the values are only
guaranteed stable for as long as that buffer lives. If you keep anything beyond the call, copy it:

```ts
const history: Float32Array[] = [];

function onPose(frame: PoseFrame) {
  history.push(frame.landmarks.slice());   // a copy, safe to keep
}
```

## `onFramesDropped`

```ts
onFramesDropped?: (count: number) => void;
```

Frames the native ring buffer threw away because this consumer could not keep up. The buffer is
bounded and drops oldest-first, which is the right behavior for live pose data, but a drop is
still information and it used to be decoded on every drain and discarded.

It is reported per delivery, not cumulatively. A single spike is normal, for instance a slow first
render. A steady trickle means your `onPose` or `onPoseBatch` handler is doing too much work, and
the fix is to do less in the callback rather than to raise `flushMs`.

## `onLog`

```ts
onLog?: (entries: readonly LogEntry[]) => void;
```

Diagnostic entries in batches of about every 250 ms, for as long as the level set by
`setLogLevel()` or this camera's `logLevel` prop lets any through. At the default `'off'` nothing
arrives. The batches are the ones `addLogListener()` receives, which also hears photo detections
and exports with no camera on screen; with two cameras mounted, the first one's `onLog` receives
them. `LogEntry` and the levels are under [functions → diagnostics](./functions.md#diagnostics),
and what each level shows in [the log channel](../troubleshooting.md#watching-it-work-the-log-channel).
