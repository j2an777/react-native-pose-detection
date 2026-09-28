# Native work

Upgrades and changes that only a phone can prove. CI proves that everything still builds, but not
that detection still works the same. So each of these ships in a release of its own, after a device
run on an iPhone and on an Android phone: the sweep in `scripts/device-diagnostics.sh`, plus the
`person` scenario with somebody in frame.

## Upgrades

- [ ] **UP-1 · P1 · MediaPipe 0.10.35 to 1.0.0 on both platforms.**
  - **Why:** 1.0.0 came out on 2026-07-28, on Google Maven (`com.google.mediapipe:tasks-vision`) and
    CocoaPods (`MediaPipeTasksVision`). [ADR 0007](../adr/0007-pin-mediapipe-0-10-35.md) pinned
    0.10.35 two weeks later and does not mention it.
  - **What changed:** its release notes say "Bump MediaPipe version to 0.10.36", and nothing in them
    concerns the pose landmarker. The relevant changes are Android GPU fixes:
    - a null-pointer read in `Tensor::GetOpenGlTexture2dReadView()` for AHWB-backed tensors;
    - `AhwbGpuResources` now cleans up its OpenGL and EGL resources;
    - a data race on `GlContext::profiling_helper_` is fixed.

    It also updates Abseil and drops the LLM inference engine from the mobile builds.
  - **Where:** [build.gradle:29](../../packages/core/android/build.gradle#L29),
    [ReactNativePoseDetection.podspec:25](../../packages/core/ios/ReactNativePoseDetection.podspec#L25),
    and the reason for the pin in [.github/dependabot.yml](../../.github/dependabot.yml).
  - **Check, following ADR 0007:**
    - `tasks-core`, which holds the native code, resolves at the same version as `tasks-vision`.
    - The APK still carries `arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64` (CI asserts this). Its
      64-bit libraries must stay 16 KB aligned (CI-3 in [next-patch.md](./next-patch.md)).
    - Its podspec asks for iOS 16.4 or lower, and its Android `minSdk` is 24 or lower.
    - It links on iOS in both example apps. From 0.10.33 on, CocoaPods sometimes failed to link it
      (google-ai-edge/mediapipe#6258).
    - The app-size table in `guides/performance.md` is measured again.
    - On both phones, compare it against 0.2.1, focusing on the GPU delegate:
      - time to the first skeleton;
      - p50 per frame, which in 0.2.1 is about 16.7 ms on an iPhone 15, and about 89 ms on a Redmi
        Note 12 running the full model on the GPU while tracking.
  - **Then:** write a new ADR that supersedes 0007, and name the engine change in the changelog.
- [ ] **UP-2 · P2 · CameraX 1.6.1 to 1.6.2.**
  - Released 2026-08-26. Its only fix is "a compilation crash on newer JDK versions when resolving
    transitively imported JSpecify type annotations". Nothing changes at runtime.
  - **Where:** `cameraxVersion` in [build.gradle:31](../../packages/core/android/build.gradle#L31).
  - Ships with UP-1, so one device run covers both.
- [ ] **UP-3 · P2 · The example apps on the newest Expo 57 patch.**
  - Both examples are on `expo` 57.0.12, and 57.0.25 is the newest as of 2026-09-28. Run
    `npx expo install --fix` in each, keeping React Native on the SDK's pairing, 0.86.
  - This changes the native runtime the examples run, so run the device sweep again. Then check
    the dev-only advisories (SEC-1 in [waiting.md](./waiting.md)).

- [ ] **IOS-1 · P3 · iOS: move file jobs to AVFoundation's async asset loading.**
  - **Today:** the synchronous track and duration reads that iOS 16 deprecated, each one warning,
    all kept in [AssetCompat.swift](../../packages/core/ios/AssetCompat.swift). Since 0.2.2 the
    podspec declares iOS 16.4, so the async `load(_:)` and `loadTracks(withMediaType:)` replacements
    are available.
  - **Fix:** load the properties asynchronously before a video or export job starts, and delete
    `AssetCompat`.
  - **Check:** the sweep's `files` scenario and a video export on an iPhone, since this changes how
    every video job opens its media.

## Performance

- [ ] **PERF-14 · P2 · Android: one copy fewer per frame.**
  - **Today:** each frame goes from the RGBA plane into our bitmap, and MediaPipe then copies it
    again, in
    [FrameConverter.kt](../../packages/core/android/src/main/java/com/posedetection/camera/FrameConverter.kt).
  - **Fix:** use `ByteBufferImageBuilder` straight over the plane when
    `rowStride == width × 4`, and keep the bitmap path for padded rows.
  - **Why it waits:** it saves one copy of about 1.6 MB at 480p, well under a millisecond. The risk
    is MediaPipe holding on to a buffer that CameraX recycles, and only a device can show that it is
    safe.
- [ ] **PERF-15 · P2 · The `efficient` profile drops to 10 fps while the body is still.**
  - "Still" means no joint has moved more than 2% of the body's span for 1 s. The full rate returns
    on the first movement.
  - **Why it waits:** the idle search already slows down when nobody is in frame. Measure
    `efficient` on a phone before adding a second slowdown.
- [ ] **IDEA-7 · P2 · Draw the Android overlay on its own surface.**
  - **Why:** each overlay redraw costs about 5 ms of RenderThread time. It also slows inference by
    about 9 ms a frame, because the window's GPU composition competes with the model.
  - **Measured on a Redmi Note 12,** detection p50 after warm-up:

    | Overlay | Detection p50 |
    | --- | --- |
    | View (today) | 87.5 ms |
    | `SurfaceView` drawn with `lockHardwareCanvas` | 79.5 ms |
    | `SurfaceView` drawn with `lockCanvas` | 86 ms, plus 11 to 12 ms of software drawing |
    | No overlay | 78.5 ms |

    The hardware-canvas surface recovers 8 of the 9 ms.
  - **Needs:**
    - The View overlay kept as a fallback for when PreviewView falls back to a TextureView (API 24
      and some quirky devices). A surface behind the window would punch a hole through a
      TextureView preview.
    - Clearing the overlay when the pose is lost, and redrawing it on config changes.
    - A visual check that it lines up with the View overlay.
    - Memory for a full-screen buffer.
  - Planned alongside IDEA-8 in [features.md](./features.md), which redraws more often.

## Measure on a device

Assumptions in the code that nobody has measured yet.

- [ ] **INV-2 · iOS video HDR and stabilization defaults for the chosen preset.** Turn them off if
  they cost power without helping detection.
- [ ] **INV-3 · Android: what CameraX's RGBA conversion costs for frames the pacing drops.** It
  matters most at idle. If the cost is significant, switch to YUV output and convert only the
  frames that run.
- [ ] **INV-4 · The RAM each device reports.** The `auto` preview opens at 1080p when the device
  reports at least 5.5 GiB of RAM, and at 720p otherwise. Check that phones sold as 6 GB clear
  that threshold.
- [ ] **INV-6 · File jobs on the GPU in VIDEO mode, on both platforms.** Since 0.2.0, photo and
  video jobs use the GPU when no camera is detecting. The sweep's `files` scenario proves that they
  work, but not which delegate ran or how much time it saved.
- [ ] **INV-8 · Thermal simulation.** Check that every step of the heat ladder fires and recovers.
- [ ] **INV-9 · Real app size.** Measure a release archive with and without the package, per model
  and per platform, and replace the iOS estimates in `guides/performance.md`.
- [ ] **INV-10 · Frame rates on three or four more phones,** from low end to high end, beyond the
  iPhone 15 and the Redmi Note 12.
