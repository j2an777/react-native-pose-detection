# `<PoseCamera>`: props

```tsx
import { PoseCamera, type PoseCameraRef } from 'react-native-pose-detection';

<PoseCamera ref={cam} style={{ flex: 1 }} />
```

## What runs today

**Every prop on this page is implemented on both platforms**, and the reference-parity test
fails the build if one of them goes missing from this page.

## Layout

| Prop | Type | Notes |
| --- | --- | --- |
| `style` | `StyleProp<ViewStyle>` | An ordinary React Native view style. The preview fills the view and the overlay is drawn inside it, so `{ flex: 1 }` is the usual answer and a fixed height is the other one. |

The preview's aspect ratio comes from the camera rather than from this style, so a view whose
shape does not match it is filled edge to edge and the camera frame is cropped evenly on the long
side, never stretched. The skeleton is projected the same way, so it stays on the body whatever the
view's shape. Landmarks are normalized against the analysis frame either way, so nothing about the
layout moves them.

## Configuration

| Prop | Type | Default | Notes |
| --- | --- | --- | --- |
| `profile` | `'auto' \| 'efficient' \| 'balanced' \| 'quality' \| 'unrestricted'` | `'auto'` | [performance](../performance.md) |
| `facing` | `'auto' \| 'front' \| 'back'` | `'auto'` | auto prefers front, falls back to the other lens on the first bind |
| `delegate` | `'auto' \| 'gpu' \| 'cpu'` | `'auto'` | auto verifies GPU, falls back to CPU. On Android it starts on the CPU and moves to the GPU once that has built |
| `targetFps` | `'auto' \| number` | `'auto'` | a number replaces the governed rate, capped by the camera and by what the device can finish. [performance](../performance.md) |
| `resolution` | `'auto' \| '480p' \| '720p' \| '1080p'` | `'auto'` | preview |
| `analysisResolution` | `'auto' \| '360p' \| '480p' \| '720p'` | `'auto'` | what the model sees |
| `thermalPolicy` | `'adaptive' \| 'critical-only' \| 'off'` | `'adaptive'` | `off` stops the response, not the reporting |
| `maxPoses` | `number` (1 to 5) | `1` | above 1, triggers and frames use the primary pose: largest box, ties by distance from center. A ceiling, not a promise: pair it with `minConfidence` |
| `minConfidence` | `number` (0.1 to 1) | from `maxPoses` | how sure the model has to be before it calls something a body. Rebuilds the landmarker when it changes |
| `smoothing` | `'auto' \| boolean \| { minCutoff, beta }` | `'auto'` | One Euro filter over x, y and z. `'auto'` is off for one pose, which MediaPipe already smooths, and on for several. See [below](#smoothing) |

Any explicit value pins that axis. The rest stay automatic.

## Switches

| Prop | Type | Default |
| --- | --- | --- |
| `active` | `boolean` | `true`: camera on/off |
| `detection` | `boolean` | `true`: inference on/off |
| `overlay` | `boolean \| OverlayConfig` | `true` |

```ts
type OverlayConfig = {
  landmarks?: boolean;
  connections?: boolean;
  color?: string;
  lineWidth?: number;        // points, default 3
  pointRadius?: number;      // points, default 4
  minVisibility?: number;    // hide low-confidence joints, default 0.5
  only?: readonly JointName[];          // draw a subset
  angles?: readonly AngleOverlay[];     // draw angle arcs + degree labels
};

type AngleOverlay = {
  joint: AngleJointName;    // vertex of the angle
  label?: boolean;          // show the degree value, default true
  radius?: number;          // arc radius in points, default 40
  color?: string;           // defaults to the overlay color
  decimals?: number;        // 0 to 3, default 0
  minVisibility?: number;   // hide when tracking is poor, default 0.5
};
```

Native clamps every number here rather than trusting it, because an overlay config is the kind of
thing that gets built from app state and skips the type checker. `lineWidth` and `pointRadius`
cannot go below 0, `radius` cannot go below 1, `minVisibility` is clamped to 0 to 1, and
`decimals` is capped at 3: the degree label is formatted into a fixed 16-character buffer on the
draw path, so a larger value would build a longer string every frame only to have it truncated.

### Angle overlay

```tsx
<PoseCamera
  overlay={{
    angles: [
      { joint: 'leftElbow' },                    // arc + "46°"
      { joint: 'leftKnee', label: false },       // arc only
      { joint: 'rightKnee', color: '#ff5252' },
    ],
  }}
/>
```

Drawn natively, nothing crosses to JavaScript. Declaring a joint here is one of the three things
that turn its angle on, along with an `angle` condition in a trigger and naming it in
`data.angles`. Nothing else does: a joint used as a comparison bound, or listed in `data.select`,
is a position, and computing its angle would be work nobody asked for.

`AngleJointName` is the 12 joints where two limb segments meet, see
[types](./types.md#anglejointname). `{ joint: 'nose' }` does not compile.

See [camera control](../camera-control.md) for how the three switches combine.

## Data

The whole `data` surface is validated and shaped in JavaScript; frames are encoded natively
into a ring buffer and drained in one zero-copy read per emission.

```ts
data?: {
  mode?: 'off' | 'throttled' | 'batched' | 'live';   // default 'off'
  throttleMs?: number;      // 'throttled' only, default 100
  flushMs?: number;         // 'batched' only, default 500
  landmarks?: boolean;
  worldLandmarks?: boolean;
  angles?: boolean | readonly AngleJointName[];
  select?: readonly JointName[];
};
```

`mode` is optional. Leaving `data` off entirely and leaving `data.mode` unset are the same thing,
zero crossings per second.

`angles: true` computes all 12. An array computes only those. Triggers and `overlay.angles` add
to whatever this asks for, so you do not have to repeat a joint you already referenced there.

`select` narrows the landmark buffer to exactly the joints you name, in the order you name them,
and nothing widens it. Angles are computed natively from the full 33 landmarks before the buffer
is narrowed, so asking for an angle on a joint you did not select costs nothing extra and does not
smuggle that joint into the payload. Reading one that `select` left out throws from the accessor.
See [data delivery](../data-delivery.md).

## Triggers

```ts
triggers?: readonly Trigger[];
```

Validated during render, so a bad config throws `PoseConfigError` at the call site rather than
becoming a trigger that silently never fires. The evaluator runs on both platforms. See
[trigger schema](./trigger-schema.md).

## Diagnostics

```ts
// LogLevelConfig
logLevel?: LogLevel | Readonly<Partial<Record<LogCategory, LogLevel>>>;   // default 'off'
onLog?: (entries: readonly LogEntry[]) => void;
```

`logLevel` raises the level on top of `setLogLevel()` while this camera is mounted, and gives it
back when the camera unmounts or the prop is removed. The level itself is global, so the raise
covers everything that logs meanwhile, not only this camera; without the prop the camera leaves
it alone. `setLogLevel()` sets the level for the whole app, and throws `PoseConfigError` on an
unknown level or category rather than doing nothing, because a silently ignored level looks
exactly like a bug in whatever you were trying to diagnose.

Entries reach Logcat, or `os.Logger` on iOS, whatever is attached, and are batched to JavaScript
while a listener is: `onLog` on a mounted camera, or `addLogListener()`. `addLogListener()` is a multiset rather than a set, so the same function
registered twice needs two `remove()` calls. See [troubleshooting](../troubleshooting.md#watching-it-work-the-log-channel).

## Callbacks

See [events](./events.md).

### Smoothing

Landmarks jitter a little from frame to frame, even on a body that is standing still. A One Euro
filter trades that jitter against lag by moving its cutoff with speed: a still body is filtered
hard, where jitter shows and lag does not, and a fast one is barely filtered, where lag shows and
jitter does not.

With `maxPoses: 1`, MediaPipe already runs exactly this filter inside the landmarker, so a
second one on top would only add lag. It skips its own filter when it tracks more than one body.
That is what `'auto'` follows: off for one pose, on for several, so the two feel the same.

| Value | Means |
| --- | --- |
| `'auto'` (default) | Off at `maxPoses: 1`, on above it |
| `true` / `false` | On or off whatever `maxPoses` is |
| `{ minCutoff, beta }` | On, with your constants |

The defaults are MediaPipe's own for pose landmarks, `minCutoff: 0.05` and `beta: 80`, with speed
measured in body spans per second rather than in frame widths, so a distant subject is smoothed
like a near one. Lower `minCutoff` smooths a still body harder; higher `beta` gets out of the way
of fast movement sooner. The filter starts over when the pose is lost, the camera switches, a
different person becomes the largest body, or frames stop for longer than two and a half
intervals at the current rate. Visibility is never smoothed: it is a confidence, not a position.

### maxPoses and minConfidence

MediaPipe's landmarker is built around one primary subject, and the confidence a single subject
wants is higher than the one that lets a second person be found at all. They are one decision, so
leaving `minConfidence` out takes it from `maxPoses`:

| `maxPoses` | Threshold used | Why |
| --- | --- | --- |
| `1` | `0.6` | one subject, tracked cleanly, with scenery not offered as a body |
| `2` to `5` | `0.3` | the point where a second person appears rather than the first one twice |

```tsx
<PoseCamera />                                   {/* maxPoses 1, so 0.6 */}
<PoseCamera maxPoses={5} />                      {/* 0.3, because more than one was asked for */}
<PoseCamera maxPoses={5} minConfidence={0.4} />  {/* your number wins */}
```

Measured against `pose_landmarker_full` on a photo of two separated, mostly whole people:

| `maxPoses` | `minConfidence` | Poses returned |
| --- | --- | --- |
| 1 | anything | 1 |
| 5 | 0.5, 0.4 | 1 |
| 5 | 0.3 | 2, one per person |
| 5 | 0.2 | 3, the third a duplicate of the first |
| 5 | 0.1 | 4, two of them duplicates |

So 0.3 is where the default stops: below it the model starts returning the same body twice rather
than finding anybody new. A person cropped by the frame with no torso or head in view is not found at
any setting, because the detector that feeds the landmarker anchors on a torso.

The exact crossing point moves with the device. The same photo through the same model on an Android
emulator needed 0.2 before the second person appeared, where a desktop found them at 0.3, because a
body sitting near the threshold falls either side of it on small numeric differences between builds.
Treat 0.3 as a starting point rather than a constant: if a body you can see is not being found, lower
it and watch for the same skeleton appearing twice, which is the sign you have gone too far.

Both values are baked into the landmarker when it is built, so changing either rebuilds it. Put them
in state that settles rather than state that moves.
