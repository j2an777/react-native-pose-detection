# Photos and video files

The same detector, no camera. Two jobs live here: getting the **numbers** out of a file, and
writing a **painted copy** of one. Reach for the numbers when you are counting reps or measuring
angles; reach for the copy when someone is meant to see their form.

```ts
import { detectOnImage, detectOnVideo, exportPose } from 'react-native-pose-detection';
```

## Landmarks from an image

```ts
const poses = await detectOnImage(uri, { maxPoses: 1 });
// → PoseFrame[]   (one entry per detected pose)
```

| Option | Default | Notes |
| --- | --- | --- |
| `maxPoses` | `1` | 1–5. The subject, the largest body, is always the first frame |
| `minConfidence` | follows `maxPoses` | `0.5` at `maxPoses: 1`, `0.3` above it, as for [exports](#finding-bodies-the-defaults-miss) |
| `angles` | `true` | |
| `worldLandmarks` | `false` | |
| `select` | all 33 | narrows the landmark buffer, as `data.select` does |

Accepts a local file URI, a `content://` URI on Android, or an `http(s)` URL, which is fetched
whole before it is decoded.

The photo is decoded upright, with its EXIF orientation applied, and no larger than 1920 pixels
on its long side. That is already more than the model looks at: the detector sees 224 pixels of
the whole picture and the landmarks come from a 256-pixel crop around the body. A 48-megapixel
photo therefore costs what a 4-megapixel one does, and a portrait photo stored sideways is found
the right way up.

## Landmarks from a video

A video job can run for minutes, so it is not a bare promise. `detectOnVideo` returns a task you
can cancel, and the frames arrive on the task's promise:

```ts
const task = detectOnVideo(uri, {
  fps: 10,
  onProgress: (p) => setProgress(p),   // 0…1, on the JS thread, throttled
});

const frames = await task.frames;      // PoseFrame[]
task.cancel();                         // resolves task.frames with what was decoded so far
```

| Option | Default | Notes |
| --- | --- | --- |
| `fps` | `10` | sampling rate, not the video's own frame rate |
| `maxPoses` | `1` | each frame is the subject's, the largest body in it |
| `minConfidence` | follows `maxPoses` | as for photos |
| `startMs` / `endMs` | full clip | trim a range, `endMs` excluded. Only the range is decoded |
| `smoothing` | `'auto'` | off for one pose, which MediaPipe already smooths, on for several |
| `onProgress` | n/a | `(progress: number) => void`, never frames |

Runs `VIDEO` mode with monotonic timestamps, so temporal tracking and smoothing behave the same
as they do live. Each frame's `timestamp` is its real position in the video in milliseconds, not
the monotonic clock the camera stamps frames with, and smoothing and `velocity` are measured
against those real positions. Both start over when the subject is lost, when a different person
becomes the largest body, and across a gap of more than two and a half samples, or 200 ms when
that is longer.

The video is decoded once, in order, and scaled down to 960 pixels on its long side as it
decodes. Frames between samples are decoded and dropped without being converted, so sampling a
30 fps clip at 10 costs a third of the conversion work, and nothing is decoded twice.

**Memory.** The task holds every frame it has decoded so it can resolve with them, so a long
clip at a high `fps` holds a lot: 10 minutes at 30 fps is 18,000 `PoseFrame`s. Prefer a low
`fps` and trim with `startMs`/`endMs`.

**When it fails.** A file that cannot be read rejects with `IMAGE_DECODE_FAILED` or
`VIDEO_DECODE_FAILED`, an app with no model bundled with `MODEL_NOT_FOUND`, and a file that was
read but could not be processed with `DETECTION_FAILED`, so each can be told apart and explained
to the person who picked the file.

**No calibration, but heat still counts.** Static input runs at full quality: there is no live
frame budget to hit, so profiles don't apply. Heat does. A video or export slows to half speed
when the device reaches `serious`, and pauses at `critical` until it has been cooler for 30
seconds. Heat costs a job time and never frames: the same samples are detected, only later.

**The GPU when it is free.** A video job, or a video export, runs inference on the GPU when this
device's GPU check has passed and no `<PoseCamera>` is detecting, and on the CPU otherwise, so it
never competes with a live preview. A device the check has never run on tries the GPU, and if the
first frame fails there the job carries on on the CPU and the answer is kept for next time. A
photo is one inference, which the CPU runs faster than the GPU compiles its shaders, so photos
always run on the CPU.

**Off the app's other work.** Photo and video jobs run on a thread of their own, below the
camera's, so a long one never holds up other modules' calls or the preview.

## Painting a copy

`exportPose()` paints the skeleton into a copy of an image or a video and writes it into your
app's sandbox. It comes back as an ordinary `file://` URI, so everything you already do with files
works on it unchanged: upload it, move it, hand it to a share sheet, delete it.

```tsx
const task = exportPose(pickedUri, {
  overlay: { color: '#4da3ff', lineWidth: 4 },
  directory: 'documents',
  onProgress: setProgress,
});

const { uri, width, height, posesFound } = await task.result;
```

The painting is the same native renderer the live camera uses. There is no JavaScript drawing
layer and nothing for your app to implement: you pass the colors and thicknesses you would pass
to `<PoseCamera overlay>`, and the skeleton lands in the same place it would land live.

## It will not slow the camera down

This is the constraint the export was built around. The live camera is the main thing this
package does, and an export is something extra that must never cost it frames. Four things buy
that, and all four are structural rather than best effort:

| | What | Why |
| --- | --- | --- |
| 1 | Its own detector | An export never touches the camera's landmarker. It builds one, uses it, releases it |
| 2 | **Never the GPU while a camera detects** | Two MediaPipe graphs contending for the GPU is exactly how a preview starts stuttering, so an export started while a `<PoseCamera>` is detecting runs inference on the CPU. With no camera running a video export takes the GPU, as a video job does |
| 3 | A `utility` background queue | Below the camera's analysis queue, so under load the scheduler starves the export rather than sharing evenly. Serial, so two exports queue rather than gang up. Heat slows it further, as above |
| 4 | Bounded memory | One frame decoded at a time, one pooled buffer encoded at a time. The only thing that grows is the list of landmarks Android keeps between its detection and painting passes, about 300 KB a minute at the default `fps` |

On Android the **pixels** of a video export go through the GPU either way, because decoder to GL
to encoder is the only path the platform offers, and the alternative, converting every frame in
Kotlin, would take far more CPU from the camera than that takes GPU. Inference is the heavy,
sustained load that would cost a preview its frame rate, and that is what rule 2 keeps off the
GPU while a camera is live.

The choice is made when the export starts. A camera started halfway through a long export shares
the GPU with it until the export ends.

## What an export costs

A 30 second 1080p clip takes roughly 10 to 30 seconds on a recent phone, most of it inference.
The levers, in the order they matter:

- **`fps`** (default 10) is how often detection runs, not the output's frame rate. Every frame
  in between is painted with the most recent pose, which is what the live overlay does between
  inferences. Halving it roughly halves the time.
- **`maxSize`** (default 1920) caps the long edge. A 4K source painted at 1920 encodes a quarter
  of the pixels.
- The output is re-encoded H.264, so it is a second copy on disk and one generation of quality
  down from the source. Nothing about the original is changed.

## Where the file goes

`directory` decides, and the default is the app's caches directory because an export is derived
data. A package that wrote into Documents by default would be putting files the user never asked
for into their iCloud backup.

| `directory` | Where |
| --- | --- |
| `'cache'` (default) | Caches. The system may reclaim it under pressure |
| `'documents'` | Documents. Kept until you delete it |
| an absolute path or `file://` URI | Exactly there, created if missing |

Nothing is written to the photo library, and no permission is asked for. Saving to the camera
roll is one call in `expo-media-library` on a path you already have, and it is your app's
decision rather than this package's.

## Cancelling

```ts
const task = exportPose(uri, { onProgress: setProgress });
// ...
task.cancel();
```

`result` then rejects with `EXPORT_CANCELLED`, and the partial file is deleted rather than left
behind as something that looks like a finished export. A failure rejects with `EXPORT_FAILED`
and cleans up the same way. A photo export already running is one short step that `cancel()`
does not stop: it finishes and resolves as usual. Any job still waiting behind another, photo or
video, is cancelled before it starts.

## Backgrounding mid-export

The file is written under a staging name and only renamed into place once it is complete, so
nothing that dies mid-write, the app included, can leave behind something that looks like a
finished export. Stale staging files are swept automatically on the next export.

On iOS the export also holds a background task, so leaving the app lets it keep running for the
window the system grants, usually around half a minute. An export that outlives that window is
cancelled cleanly and rejects with `EXPORT_CANCELLED` rather than freezing half-finished. On
Android the process typically keeps running in the background; if the system reclaims it, the
staging file is what gets left, and the sweep removes it.

Long exports are best started when the user is going to stay: kick one off from a results
screen, not on the way out the door.

## Export options

| Option | Default | Notes |
| --- | --- | --- |
| `overlay` | `true` | The same shape `<PoseCamera overlay>` takes. `false` writes an unpainted, size-capped copy |
| `maxPoses` | `1` | Up to 5. Every pose found is painted |
| `minConfidence` | follows `maxPoses` | `0.5` at `maxPoses: 1`, `0.3` above it. See below |
| `fps` | `10` | Detection samples a second, not output frame rate |
| `maxSize` | `1920` | Long edge cap. `0` keeps the source's size |
| `directory` | `'cache'` | See above |
| `fileName` | source name + `-pose` | No extension; sanitized before it reaches the filesystem. A finished export replaces one already under the name; a cancelled or failed one leaves it as it was |
| `quality` | `0.9` | JPEG quality. Images only |
| `onProgress` | none | 0 to 1, throttled to about every two percent |

## Finding bodies the defaults miss

`maxPoses` and `minConfidence` decide who gets painted, and they are one decision rather than
two, so leaving `minConfidence` out takes it from `maxPoses`: 0.5 for a single subject, 0.3
above that. Every pose the model returns is painted.

```ts
exportPose(uri, { maxPoses: 5 });                    // 0.3, because more than one was asked for
exportPose(uri, { maxPoses: 5, minConfidence: 0.4 }); // your number wins
```

Somebody cropped by the frame edge, standing at the back or half behind equipment often sits
under MediaPipe's default 0.5 and is simply not found. Dropping to 0.3 finds a good number of
them. The cost is that furniture and shadows start being offered as people, so it is a number to
tune against your own footage rather than one this package can pick for you.

**Several people at once works, within limits.** MediaPipe's landmarker is built around one
primary subject, so `maxPoses` above one raises a ceiling rather than making a promise, and on
its own it changes nothing: the threshold has to come down with it. Measured against
`pose_landmarker_full` on a photo of two separated, mostly whole people:

| `maxPoses` | `minConfidence` | Poses returned |
| --- | --- | --- |
| 1 | anything | 1 |
| 5 | 0.5, 0.4 | 1 |
| 5 | 0.3 | 2, one per person |
| 5 | 0.2 | 3, the third a duplicate of the first |
| 5 | 0.1 | 4, two of them duplicates |

So 0.3 is where the default stops. Below it the model returns the same body twice rather than
finding anybody new, and a photo's `posesFound` counts those duplicates because they are what the
model returned. The exact crossing point moves with the device: treat 0.3 as a starting point, and if
a body you can see is not being found, lower it and watch for the same skeleton appearing twice,
which is the sign you have gone too far.

Two things it will not do. A person cropped by the frame with no torso or head in view is not
found at any setting, because the detector that feeds the landmarker anchors on a torso and legs
alone do not give it one. And a body small and blurred in the background is never found however
low the threshold goes. If your app needs a reliable skeleton per player in a crowded frame,
this model is the wrong tool and no option here changes that.

## What comes back

```ts
type ExportResult = {
  uri: string;         // file:// inside your sandbox
  width: number;
  height: number;
  durationMs: number;  // 0 for a still
  frameCount: number;  // 1 for a still
  posesFound: number;  // a photo: poses found; a video: frames with one. 0: nothing painted
};
```

`posesFound: 0` is worth handling. It means the export succeeded and painted nothing, which is a
different thing from a failure and usually means the body was out of frame or too small.

Images are written as JPEG and videos as H.264 in an MP4, with the original audio copied through
rather than re-encoded. Rotation is baked into the output rather than carried as a track
transform, so a clip shot in portrait plays upright everywhere, including in the players and
server side transcoders that ignore the transform. An audio codec an MP4 cannot hold is dropped
rather than failing the export, with a warning on the `detector` log channel: a painted video
with no sound beats no video at all.
