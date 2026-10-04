# Types

Everything on this page is exported from the package root, and the root is the only entry point.
The `exports` map blocks deep imports like `react-native-pose-detection/build/wire`, so anything
not re-exported from the root is internal and can change without a major version.

## `PoseFrame`

```ts
type PoseFrame = {
  landmarks: Float32Array;            // 33 × [x, y, z, visibility]; empty with data.landmarks off
  selection?: readonly JointName[];   // set when data.select narrowed the buffer
  worldLandmarks?: Float32Array;      // metric 3D, origin at hip center
  angles?: Partial<Record<AngleJointName, number>>;   // degrees
  centerOfMass: { x: number; y: number };   // visibility-weighted: hips 0.5, ankles 0.3, knees 0.2
  velocity: { x: number; y: number };       // of the center of mass, normalized units/sec
  bodySpan: number;                   // shoulder midpoint to ankle midpoint, normalized
  timestamp: number;
  processingMs: number;               // dispatch to result; 0 from photos and videos
};
```

**Both platforms produce these**, from one wire format written three times and guarded by a test
that reads all three. `onPose` delivers them when `data.mode` is `'throttled'` or `'live'`, and
`onPoseBatch` when it is `'batched'`. Divide a distance by `bodySpan` for a threshold that holds
whether the person stands near the camera or far from it.

Every field is `readonly` in the real declaration, dropped above for readability. The same is true
of the event types in [events](./events.md).

Only fields enabled in `data` are populated. `angles` is partial because only referenced angles
are computed, and exactly three things reference one: an `angle` condition in a trigger, an entry
in `overlay.angles`, and `data.angles`. Nothing else does. Naming a joint as a comparison bound
(`below: 'leftShoulder'`) or in `data.select` asks for its position, not its angle.

`timestamp` is milliseconds on a monotonic clock, not wall clock. It is the same clock
`LogEntry.timestamp` uses, so a log line can be matched to the frame that produced it. It marks
when the pose became known, not when the sensor exposed the frame. A frame from `detectOnVideo`
carries its position in the video instead, and one from `detectOnImage` carries 0.

**`NaN` means unknown, and it is never a substitute for a real value.** An angle with a
zero-length side (two of its points on top of each other), a `centerOfMass` with nothing visible
enough to weigh, and `velocity` on the first frame of a pose are all `NaN`, because `0` would read as a measurement: a folded joint, a
body at the origin, a body standing still. Comparisons against `NaN` are false, so a trigger built
on one simply does not fire, which is the behavior you want from a value nobody measured. Guard
with `Number.isNaN` if you display it.

### Wire format

Landmarks cross as a `Float32Array` over an ArrayBuffer, not JSON.

| Encoding | Bytes/frame | Parse cost |
| --- | --- | --- |
| JSON objects × 33 | ~3,000 | high |
| `Float32Array` | **528** | ~zero |

Layout is flat: `[x₀, y₀, z₀, v₀, x₁, y₁, z₁, v₁, …]`. The constants are exported, so you never
have to hard-code a stride:

| Constant | Value |
| --- | --- |
| `LANDMARK_COUNT` | `33` |
| `LANDMARK_STRIDE` | `4` |
| `LANDMARK_OFFSET` | `{ x: 0, y: 1, z: 2, visibility: 3 }` |
| `FULL_FRAME_FLOAT_COUNT` | `132` |
| `FULL_FRAME_BYTE_LENGTH` | `528` |

`frame.landmarks` is a `subarray` view into the buffer the drain returned, never a copy. Retaining
a frame retains that whole buffer, and the numbers are only stable while it lives, so copy with
`.slice()` if you keep anything past the callback.

Coordinates are normalized `0…1` relative to the **analysis frame**, origin top-left.
Front-camera `x` is un-mirrored so it matches the real world, not the preview. The overlay
compensates automatically.

Normalizing `x` by the frame width and `y` by the height independently makes the space
anisotropic, so one unit of `x` is not one unit of `y` on anything but a square frame. That does
not matter for a threshold on a single axis, which is what `landmarkX` and `landmarkY` are. It
matters enormously for anything angular, which is why angles are computed natively with an aspect
correction rather than read off these coordinates.

`worldLandmarks` uses the same stride and the same `selection`, in meters, with the origin at
the hip midpoint.

### `select` shrinks the buffer

With `data.select`, the buffer carries only the joints you named, in the order you named them,
and `frame.selection` lists them. Three joints is 12 floats, 48 bytes, instead of 528.

It is exactly those joints and nothing else. Angles are computed natively from the full 33
landmarks before the buffer is narrowed, so referencing an angle never widens the payload and
never adds a joint you did not ask for. A `PoseFrame` handed to you as a trigger snapshot is
narrowed the same way.

The accessors read `selection` for you, so `landmark(frame, 'leftKnee')` works the same either
way. Asking for a joint you did not select throws rather than returning a silent zero.

`frame.selection` is one frozen array instance, held for as long as the joint list is unchanged,
and the accessors cache their name-to-position map against that identity in a `WeakMap`. It is
held by content, not by prop identity, so passing `data` as an inline object literal (which every
example here does, and which produces a new array on every render) does not churn it. Changing
which joints you select does, and it should: it is a different buffer shape.

## Accessors

Reading the buffer by hand is easy to get wrong once `select` is in play, so read it with these:

```ts
import { landmark, landmarkInto, createLandmark, isVisible } from 'react-native-pose-detection';

const knee = landmark(frame, 'leftKnee');   // { x, y, z, visibility }
```

```ts
type Landmark = {
  x: number;            // 0 to 1 across the analysis frame, origin top-left
  y: number;
  z: number;            // depth relative to the hip midpoint, about the scale of x; noisier
  visibility: number;   // 0 to 1; below about 0.5 the point is a guess
};
```

`MutableLandmark` is the same shape with writable fields, the target `landmarkInto` fills.

| Function | Returns | Allocates |
| --- | --- | --- |
| `landmark(frame, joint)` | `Landmark` | one small object |
| `landmarkInto(frame, joint, out)` | the `out` you passed | nothing |
| `createLandmark()` | a reusable `out` target | once |
| `worldLandmark(frame, joint)` | `Landmark \| null` | one small object |
| `visibilityOf(frame, joint)` | `number`, `0` when absent | nothing |
| `isVisible(frame, joint, minVisibility?)` | `boolean`, `minVisibility` defaults to `0.5` | nothing |
| `hasLandmark(frame, joint)` | `boolean`: `data.landmarks` is on and `data.select` kept the joint | nothing |

Nothing here copies or parses the buffer. On a `live`-mode path where allocation matters, hoist
one target and reuse it:

```ts
const knee = createLandmark();

function handle(frame: PoseFrame) {
  landmarkInto(frame, 'leftKnee', knee);   // zero allocation
}
```

`landmark()` and `landmarkInto()` throw `PoseConfigError` when the joint is not in the frame,
either because `data.landmarks` is off or because `data.select` excluded it, and `worldLandmark()`
throws for the second. That is a config mistake, not a runtime condition, so it fails loudly with
the joint name in the message. `worldLandmark()` returns `null` for the different case of a whole
missing block rather than a joint you forgot to select: `data.worldLandmarks` off, or
`data.landmarks` off, which empties the world block too. Use `hasLandmark()` or `visibilityOf()`
when you would rather branch than catch.

## `JointName` / landmark indices

BlazePose, 33 points, in buffer order:

```text
 0 nose            11 leftShoulder    22 rightThumb
 1 leftEyeInner    12 rightShoulder   23 leftHip
 2 leftEye         13 leftElbow       24 rightHip
 3 leftEyeOuter    14 rightElbow      25 leftKnee
 4 rightEyeInner   15 leftWrist       26 rightKnee
 5 rightEye        16 rightWrist      27 leftAnkle
 6 rightEyeOuter   17 leftPinky       28 rightAnkle
 7 leftEar         18 rightPinky      29 leftHeel
 8 rightEar        19 leftIndex       30 rightHeel
 9 mouthLeft       20 rightIndex      31 leftFootIndex
10 mouthRight      21 leftThumb       32 rightFootIndex
```

`leftIndex` and `rightIndex` are the index fingers, the far point of the wrist angles, and
`leftFootIndex` and `rightFootIndex` are the tips of the feet.

All 33 names are exported as values, not only as a type. `JointName` is a union of those literals,
`JOINT_NAMES` is the ordered list, and `JOINT_INDEX` maps a name to its position in the full,
unselected buffer. `isJointName(value)` is a type guard for when a joint name arrives from outside
your code. It matches against a `Set`, not the `in` operator, so `'toString'` and `'constructor'`
are rejected like any other unknown string.

## `AngleJointName`

An angle needs two limb segments meeting at a vertex. `nose` has none, so only 12 joints have
one:

```text
leftShoulder  rightShoulder   leftElbow  rightElbow   leftWrist  rightWrist
leftHip       rightHip        leftKnee   rightKnee    leftAnkle  rightAnkle
```

`Condition.angle`, `AngleOverlay.joint`, the elements of `data.angles`, and the keys of
`PoseFrame.angles` are all `AngleJointName` rather than `JointName`. Writing `{ angle: 'nose' }`
is a type error, and it is caught by [validation](#validation) at runtime too, instead of becoming
a trigger that never fires.

`ANGLE_JOINT_NAMES` is the list and `isAngleJointName(value)` is the type guard, with the same
prototype-key handling as `isJointName`. `ANGLE_JOINTS` gives the triple each angle is measured
from as `[proximal, vertex, distal]`, so `leftKnee` is `['leftHip', 'leftKnee', 'leftAnkle']`.

Angles are degrees, 0 to 180, always. They come out of an `acos`, so 180 is the ceiling and a
bound above it can never be met.

## Skeleton connections

`POSE_CONNECTIONS` is the 35-pair skeleton, as joint-name pairs. `POSE_CONNECTION_INDICES` is the
same list as landmark indices in the full, unselected order, the form the native renderers
iterate, and `CONNECTION_COUNT` is `35`. Both platforms draw this same table, restated pair for pair in Kotlin and Swift.

## `Profile` / `ProfileState`

```ts
type Profile = 'auto' | 'efficient' | 'balanced' | 'quality' | 'unrestricted';

type LimitedBy =
  | 'camera' | 'device' | 'target' | 'profile'
  | 'thermal' | 'lowPower' | 'idle' | 'paused';

type ProfileState = {
  profile: Profile;
  phase: 'calibrating' | 'settled' | 'cached';
  source: 'measured' | 'static' | 'cache';
  tier: 'high' | 'medium' | 'low';
  resolved: { delegate: 'GPU' | 'CPU'; targetFps: number;
              preview: '480p' | '720p' | '1080p';
              analysis: '360p' | '480p' | '720p' };
  p50InferenceMs: number;
  measuredFps: number;
  limitedBy: LimitedBy;        // why resolved.targetFps is what it is
  cameraFps: number;           // what the camera delivers, normally 30
  thermalState: 'nominal' | 'fair' | 'serious' | 'critical';
  lowPower: boolean;           // Low Power Mode or Battery Saver
};
```

`measuredFps` is completed inferences over the last second, zero once results stop. How the rest
is produced is [the performance guide](../performance.md#measuring-the-device)'s subject.

## `CameraState`

```ts
type CameraState = {
  facing: 'front' | 'back';
  active: boolean;
  detecting: boolean;
  fps: number;          // read live on each call, 0 once results stop
  delegate: 'GPU' | 'CPU';
  deviceTier: 'high' | 'medium' | 'low';   // as onReady reported it
  limitedBy: LimitedBy; // read live, like fps
};
```

Until `onReady` arrives, `facing`, `delegate` and `deviceTier` are placeholders, `'front'`, `'CPU'`
and `'medium'`, whatever the camera turns out to be.

## Validation

Trigger configs are checked in JavaScript before they reach native:

```ts
import { validateTriggers, assertValidTriggers } from 'react-native-pose-detection';

validateTriggers(triggers);        // → ValidationIssue[], empty when the config is fine
assertValidTriggers(triggers);     // → throws PoseConfigError listing every problem
```

```ts
type ValidationIssue = { path: string; message: string };
```

`path` points at the exact field, for example `triggers[0].enter.angle`. `<PoseCamera>` runs
`assertValidTriggers` during render, not in an effect, so a bad config fails at the call site
before anything walks the conditions. You only need to call these yourself when you build trigger
configs dynamically and want to check one before rendering.

`PoseConfigError` carries every problem it found on `.issues`, not just the first, so a generated
config can be fixed in one pass. The rest of the package throws the same error type for its own
configuration mistakes, listed under [functions → errors](./functions.md#errors).

The full rule list is in [trigger schema → validation](./trigger-schema.md#validation).

## Events

See [events](./events.md) for `ReadyEvent`, `ErrorEvent`, `TriggerEvent`, `PerformanceEvent`.

## Every exported type

Each one is importable from the package root, and documented where it is used:

| Area | Types | Where |
| --- | --- | --- |
| The component | `PoseCameraProps`, `PoseCameraRef` | [props](./pose-camera.md), [ref methods](./ref-methods.md) |
| Camera settings | `Profile`, `FacingRequest`, `Facing`, `DelegateRequest`, `Delegate`, `ResolutionPreset`, `AnalysisResolutionPreset`, `Resolution`, `ThermalPolicy`, `ThermalState`, `DeviceTier`, `ModelVariant`, `SmoothingConfig` | [props](./pose-camera.md#configuration) |
| Drawing | `OverlayConfig`, `AngleOverlay` | [props → switches](./pose-camera.md#switches) |
| Frames | `DataConfig`, `DataMode`, `PoseFrame`, `Landmark`, `MutableLandmark`, `Vec2`, `JointName`, `AngleJointName` | [props → data](./pose-camera.md#data), this page |
| State | `CameraState`, `ProfileState`, `LimitedBy` | this page |
| Stills | `TakePhotoOptions`, `Photo` | [ref methods](./ref-methods.md) |
| Events | `ReadyEvent`, `ErrorEvent`, `ErrorCode`, `CameraChangeEvent`, `PerformanceEvent`, `TriggerEvent` | [events](./events.md) |
| Triggers | `Trigger`, `TriggerEmit`, `Condition`, `AngleCondition`, `LandmarkXCondition`, `LandmarkYCondition`, `VelocityXCondition`, `VelocityYCondition`, `VisibilityCondition`, `AllCondition`, `AnyCondition`, `ValidationIssue` | [trigger schema](./trigger-schema.md), [validation](#validation) |
| Files | `StaticOptions`, `VideoOptions`, `VideoTask`, `ExportOptions`, `ExportTask`, `ExportResult` | [functions → files](./functions.md#files) |
| Permission | `CameraPermission`, `CameraPermissionStatus`, `UseCameraPermission` | [camera permission](./permissions.md) |
| Logging | `LogLevel`, `LogCategory`, `LogLevelConfig`, `LogEntry`, `LogListener`, `Subscription` | [functions → diagnostics](./functions.md#diagnostics) |
| Native contract | `NativePoseModule`, `NativePoseCameraView` | the native module's and native view's own surface, which `<PoseCamera>` and the functions wrap. App code has no need for them |

`Vec2` is `{ x, y }`, the shape of `centerOfMass` and `velocity`. `Resolution` is
`{ width, height }` in pixels, as `ReadyEvent` and `PerformanceEvent` report sizes.
