# Testing

Three layers, each saying plainly what it proves: a JavaScript suite on every push, native unit
suites on every push, and a device sweep that drives the example app on a real phone or an
emulator with nobody tapping. What none of them covers is listed at the end, because a testing
document that blurs that is worse than none.

## JavaScript

```bash
npm test
```

That is `tsc -p packages/core/tsconfig.test.json && node --test ".test-build/tests/**/*.test.js"`.
It is part of `npm run check`, and CI runs it on Node 22.22.1 and 24, the floor declared in
`engines` and the version `.nvmrc` pins for development.

**There is no test framework.** Assertions come from `node:assert/strict` and the runner is
`node --test`, both built in. A pose library that ships zero runtime dependencies should not need
a hundred development ones to prove it works, and the runner has been stable since Node 20.

**Tests are compiled rather than run off disk.** The package is `"type": "commonjs"`, so Node's
type stripping loads a `.ts` test as CommonJS and rejects its `import` statements, while the ESM
path would demand a `.ts` suffix on every relative import in the sources. Compiling costs about a
second and has a second payoff: the tests are typechecked with the same strict settings as the
code they exercise, so a test that lies about a type fails before it runs.

Output goes to `.test-build/` at the repository root, outside the package. Test artifacts that
land inside `packages/core` are artifacts that can reach the tarball.

**Tests live in `packages/core/tests/`**, mirroring the layout of `src/`, so the published `files`
list needs no exclusion rule to keep them out of the tarball: `src` ships, `tests` does not.

| Area | File | What is asserted |
| --- | --- | --- |
| Wire format | `tests/frames/wire.test.ts` | The header has a slot for every field the decoder reads, `expectedByteLength` accounts for header, per-frame meta and body, and angles resolve in table order rather than mention order |
| Decoding | `tests/frames/decodeFrames.test.ts` | A round trip through an encoded buffer, every rejection path, and that a layout the props have since changed is dropped as stale rather than reported as an error |
| Accessors | `tests/frames/accessors.test.ts` | Reading landmarks out of a full buffer and out of one `data.select` narrowed, including that a joint `select` left out throws instead of returning another joint's numbers |
| Trigger validation | `tests/validation/triggers.test.ts` | Every rejection the validator promises: unknown keys, `between` outside an angle condition, out-of-range bounds, contradictions that can never fire, an explicitly `undefined` bound, and a cyclic or BigInt config |
| Number validation | `tests/validation/numbers.test.ts` | `NaN` and the infinities are refused with a path, on the camera's props and on every file option, before anything crosses to native |
| Smoothing | `tests/smoothing.test.ts` | `'auto'` is off for one pose and on for several, and a config object means on |
| Joint tables | `tests/types/joints.test.ts` | 33 landmarks, 35 skeleton connections, 12 angle joints, and that the type guards reject `Object.prototype` keys such as `toString` |
| Wire parity | `tests/frames/wireParity.test.ts` | That the Kotlin and the Swift agree with `wire.ts` on every header slot, every flag, the landmark count and stride, the twelve angles, and the pinned MediaPipe version |
| Reference parity | `tests/docs/referenceParity.test.ts` | That every prop and ref method the types declare appears in `guides/reference/`, that the events table lists exactly the callbacks the props declare, and that the `ErrorCode` union and its documented table are the same set |

The wire format and the joint tables are shared with native code that cannot be imported here,
so what is testable in JavaScript is the contract, and it is worth testing precisely because the
other side of it is not. The two parity tests extend that idea past the code: the Kotlin, the
Swift and the reference guides are all restatements of one contract, and none of them fails on
its own when it drifts.

## Native unit suites

```bash
npm run test:kotlin                        # JUnit, through the bare example's Gradle build
swift test --package-path packages/core/ios   # XCTest, no simulator needed
```

Both run the half of the native code with no platform in it, and assert the same behavior on
each side:

- **The engine**: the wire encoder and the ring buffer, the trigger conditions, geometry, the
  One Euro filter including its reset across a gap, telling one body from the next, and the frame
  streams JavaScript reads synchronously.
- **The rate**: the governor's table, row by row (duty per heat state, the camera's ceiling,
  explicit targets, low power, idle, every profile, and why each rate is what it is), the heat
  hysteresis, and the calibrator, including what survives a relaunch.
- **Files**: the options a photo or video job runs with, its heat pacing, the subject followed
  from sample to sample with real timestamps, and, on Android, the sampling grid fed frames in the
  order a decoder that does not reorder B-frames hands them back.

`FrameRingBuffer` and the wire encoder are deliberately free of JNI and of MediaPipe, which is
what lets them run on a plain JVM and in a Swift package. A byte-order or block-offset mistake is
cheap to find there and expensive to find on a device. The Swift package manifest lists the files
it compiles one by one for the same reason: anything that needs UIKit, AVFoundation or MediaPipe
is compiled by the pod instead.

Tests build their landmark buffers in code, from the joint tables, rather than from recorded
fixtures: every case says in the test itself which joints it moved and why.

## The device sweep

The example app's Diagnostics screen holds a scenario per crash or regression that has happened
once, and it runs them with nobody tapping when a launch asks it to:

```bash
scripts/device-diagnostics.sh android          # an emulator, or a phone over adb
scripts/device-diagnostics.sh ios              # an iPhone paired with this Mac
scripts/device-diagnostics.sh android files    # one scenario, by id
```

The script makes a photo and a clip for the file scenario (see
`scripts/diagnostics-media.swift`), copies them onto the device, launches the sweep, prints each
result as it lands and saves the whole report as `diagnostics-<platform>.json`. The app has to be
installed first; `APP_ID` picks the bare example over the Expo one. Every run is also saved on
the device as `diagnostics.json` in the app's documents directory.

| Scenario | What has to hold |
| --- | --- |
| `startup` | Three mounts each reach `onReady` and a measured rate |
| `switch-camera` | 100 back-to-back switches all settle, all report `onCameraChange`, and end on the lens they began on |
| `overlapping-switch` | Two switches at once both settle, the second queued behind the first |
| `pause-during-startup` | A pause before the session is up, then a resume, brings back preview, detector and `onReady` |
| `modal-detach` | Detection comes back after the view leaves the window and returns |
| `prop-toggles` | Overlay, smoothing and data-mode changes never restart the camera |
| `detection-toggle` | Detection seen to stop, then back within a fraction of a second: the landmarker was parked, not rebuilt |
| `overlay-toggle` | 50 off and on cycles leave nothing behind |
| `pause-resume` | 30 back-to-back cycles, then frames seen to stop and come back |
| `idle` | With nobody in frame, 12 fps after 2 s and 5 fps after 20 s |
| `remount` | 50 mount and unmount cycles, each awaited to `onReady` |
| `files` | An EXIF-rotated photo and a clip stored sideways come out upright; the clip is sampled in time order at real positions with velocity measured; trimming, cancelling, both exports and the decode error codes behave |
| `soak` | Ten minutes: the rate holds and the heat stays at `fair` or below. Only when asked for by name |

A check that frames came back always waits to see them stop first, because the measured rate
stays up for two seconds after the last result and would otherwise pass on the frames from before.

**Where it has run.** The full sweep passes on the Android emulator (Pixel 8a, Android 16 image).
The emulator is where it found three of the bugs 0.2.0 fixes: a view unmounting after its
replacement had bound took the new camera with it, `onCameraChange` could reach JavaScript a
moment after `switchCamera()` resolved, and the emulator's decoder hands frames back in decode
order, which the sampler now reorders. On iOS the same sweep runs through the same script on a
paired iPhone.

The emulator proves lifecycle and correctness, not speed or heat: its camera is a rendered scene,
it runs MediaPipe on emulated hardware, and its timings move with whatever else the host is doing.
Rates, heat and the ten-minute soak are for a real phone.

### Driven from the host

Some things the app cannot do to itself. The Diagnostics screen lists them with the command for
each platform: forcing a thermal state, sending a memory warning, clearing the calibration cache,
and a trip to the background and back.

## What is not tested

- **Rendering is checked by eye.** Nothing compares a painted overlay or an exported frame against
  a reference image. The file scenario asserts where the landmarks land and which way up an export
  comes out, not how it looks.
- **The GPU is only as tested as the device the sweep ran on.** A delegate that fails is caught
  and replaced by the CPU, and that path has unit coverage, but a GPU that returns wrong answers
  rather than failing would not be noticed.
- **No profiler runs in CI.** Allocation-free steady state is a property of how the frame path is
  written and was checked with a profiler once, not on every push.

## CI matrix

| Axis | Values |
| --- | --- |
| Platform | iOS, Android |
| Install | Expo prebuild, bare |

**All four cells are wired**, and there are four rather than the eight this was drawn for: React
Native 0.82 removed the legacy architecture, so the architecture axis has one value and is gone
from the table rather than pinned at one.

| Cell | Builds | Also asserts |
| --- | --- | --- |
| `android-expo` | Debug APK after `expo prebuild` | four ABIs, exactly one model, the camera permission in the merged manifest |
| `android-bare` | Debug APK after the CLI install | `doctor`, the committed Xcode project unchanged, the module autolinked, and the JUnit suite |
| `ios-expo` | Simulator Debug after `pod install` | the model registered in the target, and exactly one in the app bundle |
| `ios-bare` | Simulator **Release** after `pod install` | `Podfile.lock` unchanged, the pod autolinked, and `PoseDetectionModule` still in the binary after dead-stripping |

Only `ios-bare` builds Release, and it is the one that answers the iOS half of the ProGuard
question: Android ships consumer keep rules because R8 can strip a class reached only from JNI,
and the Swift equivalent is a symbol reached only from Expo's generated module registry. Release
is where the linker's dead-stripping runs, so a Debug build would never have shown it.

The device sweep runs by hand before a release rather than in CI, since it needs a camera a
runner does not have.

## Reporting a failure

Include the device model, the OS version, `data.mode`, the model variant, and whether it
reproduces on the other platform. `getProfile()` output is the single most useful thing to attach:
it says what the device was measured to cost, what rate that allowed, and why. If the example app
reproduces it, a `diagnostics.json` from the sweep is the next most useful.
