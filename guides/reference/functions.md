# Functions

Everything the package exports besides the `<PoseCamera>` component, on one page. The component
has pages of its own: [props](./pose-camera.md), [ref methods](./ref-methods.md) and
[events](./events.md).

```ts
import {
  detectOnImage, detectOnVideo, exportPose,
  useCameraPermission, getCameraPermission, requestCameraPermission,
  validateTriggers, assertValidTriggers,
  landmark, landmarkInto, createLandmark, worldLandmark, visibilityOf, isVisible, hasLandmark,
  isJointName, isAngleJointName,
  setLogLevel, addLogListener,
  PoseConfigError,
} from 'react-native-pose-detection';
```

| Function | Returns | What it does |
| --- | --- | --- |
| [`detectOnImage(uri, options?)`](#detectonimage) | `Promise<PoseFrame[]>` | Landmarks from a photo |
| [`detectOnVideo(uri, options?)`](#detectonvideo) | `VideoTask` | Landmarks from a video, sampled, cancellable |
| [`exportPose(uri, options?)`](#exportpose) | `ExportTask` | A copy of a photo or video with the skeleton painted in |
| [`useCameraPermission(options?)`](#usecamerapermission) | `UseCameraPermission` | The camera permission as React state, asked for on mount |
| [`getCameraPermission()`](#getcamerapermission) | `Promise<CameraPermission>` | Reads the permission, never prompts |
| [`requestCameraPermission()`](#requestcamerapermission) | `Promise<CameraPermission>` | Prompts when the system still will |
| [`validateTriggers(triggers)`](#validatetriggers) | `ValidationIssue[]` | Checks trigger configs without rendering |
| [`assertValidTriggers(triggers)`](#assertvalidtriggers) | `void` | The same check, throwing `PoseConfigError` |
| [`landmark(frame, joint)`](#reading-a-frame) | `Landmark` | One joint out of a `PoseFrame` |
| [`landmarkInto(frame, joint, out)`](#reading-a-frame) | `out` | The same, allocation-free |
| [`createLandmark()`](#reading-a-frame) | `MutableLandmark` | A reusable target for `landmarkInto` |
| [`worldLandmark(frame, joint)`](#reading-a-frame) | `Landmark \| null` | One joint in meters, hip-centered |
| [`visibilityOf(frame, joint)`](#reading-a-frame) | `number` | One joint's visibility, `0` when absent |
| [`isVisible(frame, joint, min?)`](#reading-a-frame) | `boolean` | Visibility at or above `min`, `0.5` by default |
| [`hasLandmark(frame, joint)`](#reading-a-frame) | `boolean` | Whether `data.select` kept the joint |
| [`isJointName(value)`](#joint-names) | `boolean` | Type guard for the 33 `JointName`s |
| [`isAngleJointName(value)`](#joint-names) | `boolean` | Type guard for the 12 joints that have an angle |
| [`setLogLevel(config)`](#setloglevel) | `void` | Turns the diagnostic channel on, up or off |
| [`addLogListener(listener)`](#addloglistener) | `Subscription` | Log entries, batched |

`PoseConfigError` is the one error class, and the package's [constants](#constants) close the
page.

## Files

The same detector, no camera. The [photos and video files guide](../files.md) has the full story:
what each option costs, where exports land, cancelling, and backgrounding.

### `detectOnImage`

```ts
detectOnImage(uri: string, options?: StaticOptions): Promise<PoseFrame[]>
```

One `PoseFrame` per pose found, the largest body first. `uri` is a local file, a `content://` URI
on Android, or an `http(s)` URL, fetched whole before it is decoded.

| Option | Default |
| --- | --- |
| `maxPoses` | `1`, up to 5 |
| `minConfidence` | `0.5` at `maxPoses: 1`, `0.3` above it |
| `angles` | `true`, all twelve; or a list of `AngleJointName`s |
| `worldLandmarks` | `false` |
| `select` | all 33 joints |

Rejects with `IMAGE_DECODE_FAILED`, `MODEL_NOT_FOUND` or `DETECTION_FAILED`. See
[landmarks from an image](../files.md#landmarks-from-an-image).

### `detectOnVideo`

```ts
detectOnVideo(uri: string, options?: VideoOptions): VideoTask

type VideoTask = {
  readonly frames: Promise<PoseFrame[]>;
  cancel(): void;   // frames then resolves with what was decoded so far
};
```

`VideoOptions` is everything `detectOnImage` takes, plus:

| Option | Default |
| --- | --- |
| `fps` | `10`, samples a second, not the video's own rate |
| `startMs` / `endMs` | the whole clip |
| `smoothing` | `'auto'`: off for one pose, on for several |
| `onProgress` | `(progress: number) => void`, 0 to 1 |

Each frame's `timestamp` is its position in the video in milliseconds. Rejects with
`VIDEO_DECODE_FAILED`, `MODEL_NOT_FOUND` or `DETECTION_FAILED`, and resolves rather than rejects
after `cancel()`. See [landmarks from a video](../files.md#landmarks-from-a-video).

### `exportPose`

```ts
exportPose(uri: string, options?: ExportOptions): ExportTask

type ExportTask = {
  readonly result: Promise<ExportResult>;
  cancel(): void;
};

type ExportResult = {
  readonly uri: string;          // file:// inside your app's sandbox
  readonly width: number;
  readonly height: number;
  readonly durationMs: number;   // 0 for a photo
  readonly frameCount: number;   // 1 for a photo
  readonly posesFound: number;   // frames a pose was painted on
};
```

Paints the skeleton into a copy of a photo or a video with the renderer the live camera uses. The
options are `overlay`, `maxPoses`, `minConfidence`, `fps`, `maxSize`, `directory`, `fileName`,
`quality` and `onProgress`; their defaults are in [export options](../files.md#export-options).
Rejects with `EXPORT_CANCELLED` after `cancel()` and `EXPORT_FAILED` when the file cannot be read,
painted or written. A cancelled or failed export deletes its own partial file and leaves any
earlier export under the same name as it was. See [painting a copy](../files.md#painting-a-copy).

## Camera permission

Nothing else in the package prompts: `<PoseCamera>` reports `PERMISSION_DENIED` and stops, so when
to ask stays your decision. The [camera permission reference](./permissions.md) explains the four
states and why `blocked` is not `denied`.

```ts
type CameraPermission = {
  readonly status: 'granted' | 'denied' | 'blocked' | 'undetermined';
  readonly granted: boolean;
  readonly canAskAgain: boolean;   // false: send the user to Linking.openSettings()
};
```

### `useCameraPermission`

```ts
useCameraPermission(options?: { ask?: boolean }): UseCameraPermission
```

The permission as React state, asked for on mount unless `ask` is `false`. It adds `pending`,
`request()` and `error` to `CameraPermission`; `error` is set when the app was built without the
native module, an Expo Go session for instance. See
[camera permission](./permissions.md).

### `getCameraPermission`

```ts
getCameraPermission(): Promise<CameraPermission>
```

Reads the current status. Never prompts, so it is safe anywhere.

### `requestCameraPermission`

```ts
requestCameraPermission(): Promise<CameraPermission>
```

Prompts when the system still will, and resolves with the outcome either way: at once, without a
dialog, when the status is already `granted` or `blocked`.

## Triggers

`<PoseCamera>` checks its `triggers` during render and throws on a bad one, so these are only
needed for configs you build at runtime and want to check first. The rules are in the
[trigger schema](./trigger-schema.md#validation).

### `validateTriggers`

```ts
validateTriggers(triggers: readonly Trigger[]): ValidationIssue[]

type ValidationIssue = { path: string; message: string };   // path: 'triggers[0].enter.angle'
```

Every problem found, not just the first. Empty when the config is fine.

### `assertValidTriggers`

```ts
assertValidTriggers(triggers: readonly Trigger[]): void
```

Throws `PoseConfigError` listing every problem, which is what `<PoseCamera>` does.

## Reading a frame

A `PoseFrame` holds its landmarks in one `Float32Array`. These read a joint out of it without
copying or parsing anything, and account for `data.select`:

```ts
const knee = landmark(frame, 'leftKnee');   // { x, y, z, visibility }
if (isVisible(frame, 'leftWrist')) { /* trust its coordinates */ }
```

`landmark`, `landmarkInto` and `worldLandmark` throw `PoseConfigError` for a joint `data.select`
left out; `hasLandmark` and `visibilityOf` let you branch instead. The allocation of each, and the
buffer layout underneath, are in [types → accessors](./types.md#accessors).

## Joint names

```ts
isJointName(value: unknown): value is JointName
isAngleJointName(value: unknown): value is AngleJointName
```

Type guards for values that arrive as plain strings, from storage or a server. The 33 names are
in [types](./types.md), and the twelve that have an angle under
[`AngleJointName`](./types.md#anglejointname).

## Diagnostics

A log channel that is off by default and costs nothing until you turn it on. Entries always reach
Logcat on Android and `os.Logger` on iOS; the functions below bring them into JavaScript. See
[the log channel](../troubleshooting.md#watching-it-work-the-log-channel).

### `setLogLevel`

```ts
setLogLevel(config: LogLevel | Partial<Record<LogCategory, LogLevel>>): void
// LogLevel: 'off' | 'error' | 'warn' | 'info' | 'debug' | 'trace'
// LogCategory: 'camera' | 'detector' | 'engine' | 'triggers' | 'calibration' | 'overlay'
```

Sets the level for the whole app, or per category with a map. Throws `PoseConfigError` on an
unknown level or category, because a level that silently failed to apply looks like the bug you
were trying to find. A camera's `logLevel` prop raises it while that camera is mounted.

### `addLogListener`

```ts
addLogListener(listener: (entries: readonly LogEntry[]) => void): Subscription

type LogEntry = {
  readonly level: 'error' | 'warn' | 'info' | 'debug' | 'trace';
  readonly category: LogCategory;
  readonly message: string;
  readonly timestamp: number;   // the clock PoseFrame.timestamp uses
  readonly data?: Readonly<Record<string, number | string | boolean>>;
};
```

Entries arrive in batches about every 250 ms, with or without a camera on screen, so a photo
detection or an export can be watched too. The native stream runs only while a listener is
attached. Call `remove()` on the returned subscription to stop; the same function added twice
needs two.

## Errors

```ts
class PoseConfigError extends Error {
  readonly issues: readonly ValidationIssue[];
}
```

Thrown for a configuration mistake: a bad trigger, a prop or file option out of range, an unknown
log level, or a joint read that `data.select` excluded. It carries every problem it found on `issues`. Runtime
failures are not thrown: they arrive as `onError` codes on the camera and as rejections with a
`code` from the file functions, all listed in [error codes](./events.md#onerror).

## Constants

| Constant | Value |
| --- | --- |
| `JOINT_NAMES` | the 33 `JointName`s, in landmark order |
| `JOINT_INDEX` | `JointName` → landmark index |
| `LANDMARK_COUNT` | `33` |
| `ANGLE_JOINT_NAMES` | the 12 `AngleJointName`s |
| `ANGLE_JOINTS` | each angle joint's `[proximal, vertex, distal]` triple |
| `POSE_CONNECTIONS` | the 35 skeleton bones as `[JointName, JointName]` pairs |
| `POSE_CONNECTION_INDICES` | the same bones as index pairs |
| `CONNECTION_COUNT` | `35` |
| `LANDMARK_STRIDE` | `4` floats per landmark |
| `LANDMARK_OFFSET` | `{ x: 0, y: 1, z: 2, visibility: 3 }` |
| `FULL_FRAME_FLOAT_COUNT` | `132`, floats in an unselected frame |
| `FULL_FRAME_BYTE_LENGTH` | `528`, bytes in an unselected frame |
| `ERROR_CODES` | every `ErrorCode` |
| `LOG_LEVELS` | every `LogLevel` |
| `LOG_CATEGORIES` | every `LogCategory` |

Every type is exported too: [types](./types.md) covers `PoseFrame` and the wire format, and each
page above names the types its functions take.
