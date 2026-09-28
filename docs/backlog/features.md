# Features

New capabilities and new API. These change what apps can call, so each one starts with an issue
that settles its shape before any code is written. [ADRs](../adr/README.md) record the decisions.

## Next minor (0.3.0)

- [ ] **IDEA-8 · P1 · Above 30 fps on fast phones, opt-in first.**
  - **Today:** the camera is pinned at 30 fps. An iPhone 15 takes 16.7 ms a frame, with the GPU
    about half busy at 30, so a faster phone gains only headroom.
  - **Opt-in first,** as `targetFps={60}` or a profile. It opens a camera mode with a
    frame-duration range of 1/60 to 1/30 s, so in dim light auto-exposure falls back to 30
    rather than darkening frames.
  - **The heat budget decides the rate.** Go above 30 only while all of these hold:
    - the thermal state is nominal or fair;
    - Low Power Mode is off;
    - the GPU is busy no more than an iPhone 15's is at 30, about 50%.

    Step back to 30 at the first change in heat.
  - **Expected:** the Redmi Note 12 and the iPhone 15 stay at 30, and a phone twice as fast as the
    iPhone 15 reaches about 60.
  - **Measure** battery drain and heat on an iPhone over a 20-minute session, at 30 and at the
    higher rate, then decide whether it becomes automatic. Android needs a phone fast enough to
    reach it. The Redmi Note 12 tops out at 11 fps with the `full` model.
  - **Changes on both platforms:**
    - iOS format selection and frame durations.
    - Android sets `CONTROL_AE_TARGET_FPS_RANGE` through `Camera2CameraControl`, without a rebind.
    - `cameraFps` is no longer a constant in the governor.

    `live` data mode doubles its crossings at 60. Triggers and velocity are already time-based.
- [ ] **FEAT-1 · P2 · A VisionCamera frame-processor adapter.**
  - An extra entry point, not a replacement
    ([ADR 0001](../adr/0001-own-camera-not-visioncamera.md)). Apps that already use VisionCamera
    cannot adopt this package until it exists, because running two capture sessions on one
    device is not viable.
  - `PoseEngine` never imports camera code, which keeps the adapter a small addition rather than a
    fork ([architecture](../architecture.md)).
- [ ] **FEAT-2 · P2 · Worklets.**
  - Worklets run JavaScript on the frame thread. The adapter brings the mechanism for calling
    JavaScript there, so worklets come with it.
  - The custom JSI binding they need would also remove the tick that frames are currently drained
    on ([ADR 0008](../adr/0008-frames-are-drained-not-pushed.md)).

## Later (P3)

- [ ] **IDEA-1 · `model: 'auto'`.**
  - Install both the lite and full models, and switch to lite when full's p50 is over 40 ms. It
    costs about 5.5 MB of app size.
  - On a Redmi Note 12 tracking a person, full takes 89 ms a frame on the GPU and 122 ms on the CPU,
    and lite takes 63 ms and 80 ms. That is about 15 fps for lite against 10 for full.
- [ ] **IDEA-2 · A synchronized overlay mode.** Hold each preview frame until its landmarks arrive
  (`AVSampleBufferDisplayLayer`, or GL on Android). The skeleton and the video then never disagree,
  at the cost of about one frame of video delay.
- [ ] **IDEA-3 · Latency compensation, off by default.** Move the drawn skeleton forward by the
  measured latency, using the filtered velocity.
- [ ] **IDEA-4 · A single-pass Android export.** Detect, draw and encode in one decode, as iOS
  does. It also removes the second decode and the dependence on the decoder's frame order.
- [ ] **Not designed yet.** Open an issue before writing any code for these:
  - **FEAT-3** · a formula DSL
  - **FEAT-4** · `delegate="benchmark"`
  - **FEAT-5** · segmentation masks
  - **FEAT-6** · remote model delivery
  - **FEAT-7** · web
  - **FEAT-8** · a native analyzer protocol
