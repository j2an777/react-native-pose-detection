# Performance

*Both platforms implement all of this.*

## Profiles

```tsx
<PoseCamera profile="auto" />   // default
```

Every profile is one row of the same model. It sets a ceiling, how much of the time inference may
run (its duty), how it idles, and when heat counts:

| Profile | Ceiling | Duty, nominal / fair | Idle after 2 s / 20 s | Heat acts at |
| --- | --- | --- | --- | --- |
| **`auto`** *(default)* | the camera's rate | 85% / 70% | 12 / 5 fps | serious, critical |
| `quality` | the camera's rate | 95% / 85% | 15 / 8 fps | serious, critical |
| `balanced` | 24 fps | 70% / 60% | 12 / 5 fps | serious, critical |
| `efficient` | 15 fps | 50% / 40% | 8 / 3 fps | fair (×0.75), serious, critical |
| `unrestricted` | the camera's rate | 100% | off | critical only |

Every profile measures the device and budgets against what its inference costs; they differ only in
how much of that they spend. `auto` runs at the camera's rate whenever the device can do so with 15%
to spare, which is 30 fps on any recent phone.

**Inference never runs faster than the camera.** The camera is pinned to 30 fps where it allows,
so auto-exposure cannot halve the rate in a dim room, and nothing above 30 is offered: a phone asked
for 60 ran warm within minutes for a skeleton that looked identical at half that.

One axis stops lower than a spec sheet would suggest, and it is deliberate. **Analysis tops out at
480p.** MediaPipe resizes whatever it is handed to 256 by 256 before the detector sees it, so a
720p analysis buffer is close to a megapixel captured, converted and copied every frame in order
to be discarded inside the graph. A distant subject is the one case a larger buffer helps, and
`analysisResolution` is there to ask for it.

## The rate

Every inference reports what it cost, dispatch to result. The median of that cost, `p50`, sets the
rate at which inference is busy a given share of the time, and the profile's duty picks the share:

```text
capacity(duty) = duty × 1000 ÷ p50

nominal   min(camera, capacity(duty nominal))
fair      min(camera, capacity(duty fair))
serious   min(camera ÷ 2, capacity(50%))
critical  detection paused, preview kept
```

Why not 100%? MediaPipe's live mode keeps one frame in flight and one waiting. At 100% every frame
waits for the one before it, which adds a frame of latency and runs the GPU without a breath; the
spare 15% keeps that queue empty and the device cool, for at most a few frames a second less on a
slow phone. What that gives under `auto`:

| p50 | Nominal | Fair | Serious |
| --- | --- | --- | --- |
| 16 ms | 30 | 30 | 15 |
| 20 ms | 30 | 30 | 15 |
| 25 ms | 30 | 28 | 15 |
| 30 ms | 28 | 23 | 15 |
| 40 ms | 21 | 17 | 12 |
| 60 ms | 14 | 11 | 8 |

An iPhone 15 measures 16 to 18 ms for the full model on its GPU, the first row: the camera's 30 fps
with the GPU idle half the time.

A governed rate never drops below 10 fps for a slow device, because below that the skeleton reads
as broken; heat and idle may go lower. Low Power Mode on iOS and Battery Saver on Android cap it at
24.

**An explicit `targetFps`** is capped only by the camera and by what the device can finish at all,
`capacity(100%)`: feeding MediaPipe faster than it can finish only queues frames behind each other.
Duty and fair heat leave it alone; serious and critical heat still apply unless `thermalPolicy` says
otherwise.

## Why the rate is what it is

Every reading of the rate comes with the constraint that set it, `limitedBy`:

| `limitedBy` | Meaning |
| --- | --- |
| `camera` | The camera's own rate: nothing faster exists to run on |
| `device` | What this device finishes within its duty budget |
| `target` | Your `targetFps` |
| `profile` | The profile's ceiling, `balanced` or `efficient` |
| `thermal` | Heat, including detection paused at critical |
| `lowPower` | Low Power Mode or Battery Saver |
| `idle` | Nobody has been in frame for a while |
| `paused` | Detection is off, or the camera is not running |

It is on `getState()`, `getProfile()`, `onReady` and every `onPerformanceChange`.

## Measuring the device

**Before anything is measured, the rate is the camera's.** An unknown device is not a slow one, and
half a second at the camera's rate costs less than a start that looks slow.

**The first estimate lands after 15 frames with a pose**, about half a second. After that the median
is refreshed every 15 frames over the last 60, moves smaller than 2 fps are ignored, and a
three-second cooldown after each change stops a device sitting between two answers from
oscillating. Each move fires `onPerformanceChange({ reason: 'calibration' })`.

**The measurement is cached**, keyed by device model, model file, OS version and MediaPipe version,
so the second launch starts from it. It is kept across camera restarts in the same session: a
restart is not a new device. The GPU check is cached the same way, so it runs once per device and
model rather than on every mount.

The tier (`high`, `medium`, `low`) is a label read off the same median, `≤ 22 ms` high and `≤ 45 ms`
medium. It is reported so an app can reason about the device; it drives nothing. Before a
measurement it comes from installed memory.

### Inspecting it

```ts
await cam.current.getProfile();
// { profile: 'auto', phase: 'settled', source: 'measured', tier: 'high',
//   resolved: { delegate: 'GPU', targetFps: 30, preview: '1080p', analysis: '480p' },
//   p50InferenceMs: 16.2, measuredFps: 30, limitedBy: 'camera',
//   cameraFps: 30, thermalState: 'nominal', lowPower: false }

cam.current.getState();
// { ..., fps: 30, limitedBy: 'camera' }
```

`measuredFps` is completed inferences over the last second, zero once results stop, so it is the
number that exposes a device falling behind its target. `getState().fps` and `limitedBy` are read
live from native on the JavaScript thread, with no hop to native's main thread.

## Heat

Read from the OS once a second and whenever it notifies, never on the frame path:

| State | Response under `auto` |
| --- | --- |
| nominal | the rate above |
| fair | duty drops to 70% |
| serious | half the camera's rate at most |
| critical | **detection paused**, preview continues, event emitted |

Heat is adopted the moment it rises and cooling only after it has held for 30 seconds, at the
warmest level seen meanwhile, so a device hovering on a boundary does not flap the rate.

Android names more states than iOS: NONE and LIGHT count as nominal, MODERATE as fair, SEVERE as
serious, and CRITICAL and above as critical. On Android 11 and later the OS's forecast of where heat
is heading counts too, 85% as fair and 95% as serious, so the rate backs off before the device
starts throttling.

`thermalPolicy="critical-only"` acts only at critical, and `"off"` never. Neither stops the
reporting: `onPerformanceChange` still fires, so your app can decide for itself.

## Camera geometry

Preview and analysis sizes are fixed for a session. `auto` preview is 1080p on a device with at
least 5.5 GiB of memory and 720p otherwise, never 480p unless you ask. Nothing the governor learns
changes geometry mid-session, so no measurement or heat reading ever restarts the camera. Only a
`resolution`, `analysisResolution` or `profile` change does.

## Precedence

```text
1. profile        sets the ceiling, duty, idle and heat rows
2. targetFps      replaces the governed rate, capped by the camera and the device
3. heat           serious and critical apply to everything, unless thermalPolicy says otherwise
4. low power      caps the governed rate at 24
```

So this does exactly what it reads like:

```tsx
<PoseCamera
  profile="quality"          // high ceiling, 95% duty
  targetFps={24}             // pinned: the measured rate won't move it
  analysisResolution="auto"  // stays 480p
/>
```

## Optimizations

| | What it does |
| --- | --- |
| **Pre-warm** | One dummy inference during camera setup, so the first real frame is never the slow one |
| **GPU check, once** | The GPU probe runs once per device and model and is remembered. A GPU that fails at runtime is swapped for the CPU and the answer flips |
| **Parked landmarker** | Turning detection or the camera off, a trip to the background, or a screen pushed on top keeps the landmarker built for 30–60 s, so coming back is instant |
| **Idle search** | No person for 2 s drops to the profile's first idle rate, 20 s to its deep one; the frame that finds a pose ends it |
| **Smoothing `'auto'`** | Off for one pose, which MediaPipe already smooths; on for several, with MediaPipe's own constants |
| **Lazy angles** | Computes only the angles an `angle` condition, `overlay.angles` or `data.angles` asked for |
| **Analysis ≠ preview** | Model sees a small frame; preview stays sharp |
| **GPU-composited overlay** | Shape layers on iOS and a hardware canvas on Android: no full-screen redraw per result |
| **Frames read on the JavaScript thread** | A drain reads the ring buffer directly, never queued behind native's main thread |

## Resource budgets

**Targets, not enforced ceilings.** The zero-allocation claims below are held by the code and
its reviews; the memory and 10-minute sustained-run numbers are measured on one device so far
and harden as the device matrix grows. They are the numbers a bug report should be filed
against.

| State | Target above app baseline |
| --- | --- |
| Camera on, detection off | < 40 MB |
| `lite` @ 480p analysis | < 120 MB |
| `full` @ 480p analysis | < 180 MB |
| Steady-state allocations per frame | **0**, except the MPImage floor below |
| Return to idle after `stopDetection()` | immediate: frames stop at once, the landmarker is freed after 60 s unused |
| 10-minute sustained run | thermal ≤ fair on mid-tier |

**The zero-allocation claim has one floor, and it is honest to name it.** Handing a frame to
MediaPipe requires an `MPImage`, and building one allocates about seven objects: the builder, the
container, the image, its properties, and a small map inside the image. There is no API that takes
a reusable one. Everything this package controls, the landmark buffers, the geometry, the filter,
the evaluators and the ring buffer, allocates nothing per frame at the default configuration. The
iOS overlay builds a few small paths per frame for its shape layers, a few hundred bytes, which is
what replaced rasterizing a full-screen bitmap on the CPU.

Two configurations do allocate beyond that floor, both by choice: `data.mode: 'live'` allocates one
direct buffer per drain, which is what carrying frames to JavaScript costs, and an angle overlay
with `decimals` above zero formats a string per label per draw.

## App size

Exactly one model ships, whichever `model` your config selects.

Two things are measured, both read out of an assembled APK on the pinned 0.10.35. MediaPipe's
native libraries come to **10.08 MB** for `arm64-v8a`, 7.09 MB for `armeabi-v7a`, 14.31 MB for
`x86` and 12.48 MB for `x86_64`. The model files are 5.5 MB (`lite`), **8.96 MB** (`full`) and
29.2 MB (`heavy`).

The native libraries are already compressed and do not shrink again inside the APK, so what is on
disk is what is downloaded. The model compresses by about a tenth, 8.96 MB down to 8.03 MB for
`full`, because float16 weights are close to incompressible.

The JavaScript is the part that rounds to nothing: **62.5 KB** of built output, and no runtime
dependencies to pull in behind it.

Everything else is an estimate:

| Model | Android install / Play download | iOS |
| --- | --- | --- |
| `lite` | ~19.7 MB / ~10.9 MB | ~26–41 MB |
| `full` | ~23.2 MB / ~14.2 MB | ~29–44 MB |
| `heavy` | ~43.4 MB / ~33.0 MB | ~49–64 MB |

No release archive has been built and weighed yet. Phase 6 replaces this table with numbers from
one, per model and per platform.

**Android requires an AAB.** A universal APK carries all four ABI slices, 45.9 MB of native
library where a phone loads 10.5 MB of it. Set `abiFilters` on your release build if you must
ship an APK, and only there: dropping `x86_64` from a debug build is what breaks the standard
emulator on an Intel host.

Model files are ~93% incompressible (float16 weights), so they cost nearly full price on download.
