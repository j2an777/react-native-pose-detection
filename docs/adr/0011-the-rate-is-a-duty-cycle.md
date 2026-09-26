# 0011: The rate is a duty cycle, the geometry is fixed, and one pose is not smoothed twice

**Status:** accepted
**Date:** 2026-09-26

## Context

0.1.0 picked the live rate from a device tier: a static probe guessed the tier from memory and
cores, a measurement moved it after 60 frames, and each tier mapped to a rate and a camera
geometry. Three things went wrong with that on real phones.

- **The rate never matched the device.** The target was a fixed share of what the device could do
  (55%), capped at 40 fps, so a phone that could run 30 comfortably was held near 20, and on a
  phone that could run 60 the target said 40 while the camera delivered 30. The rate on screen was
  the tier's, not the device's.
- **Every correction was a restart.** A tier carried a geometry, so a measurement that moved the
  tier, or a change in heat, rebound the camera: the preview blinked and the landmarker waited for
  frames again, each time the rate adjusted.
- **One pose was smoothed twice.** MediaPipe runs its own One Euro filter on a single tracked body.
  Ours ran on top of it by default, in frame units, which added lag without removing any jitter the
  first filter had left.

## Decision

**The rate is capacity at a duty.** Every inference reports what it cost, and the median cost
gives the rate at which inference is busy a chosen share of the time:
`capacity(duty) = duty × 1000 ÷ p50`. The camera's own rate is the ceiling, since nothing faster
exists. A profile is a row of duties and ceilings; heat is a row too, with serious halving the
rate and critical pausing detection. Low power caps it. An explicit `targetFps` replaces the
governed rate and is capped at `capacity(100%)`, because feeding MediaPipe faster than it
finishes only queues frames. Every rate carries the constraint that set it, `limitedBy`.

The default duty is 85% at nominal and 70% at fair. MediaPipe's live mode keeps a frame in flight
and one waiting, so at 100% every frame waits on the one before it: a frame of latency and a GPU
that never idles, for at most a few frames a second more on a slow phone.

**Geometry is fixed per session.** The preview is 1080p on a phone with at least 5.5 GiB of
memory and 720p otherwise, and analysis is 480p, whatever the rate. Changing the rate only changes
how many frames are handed to the landmarker, which is a counter, not a rebind. The camera is
pinned at 30 fps where it allows, so auto-exposure cannot halve the rate in a dim room.

**Heat is adopted at once and let go slowly.** A hotter reading takes effect immediately; a cooler
one only after it has held for 30 seconds, so the rate does not flap at a boundary. On Android the
thermal headroom forecast moves the rate before the status says the device is throttling.

**Smoothing is `'auto'`: off for one pose, on for several.** Ours uses MediaPipe's constants,
`minCutoff 0.05` and `beta 80`, with speed measured in body spans per second as MediaPipe measures
it, so a session that goes from one pose to several does not suddenly lag. It starts over when the
pose is lost, the camera switches, a different body becomes the largest, or frames stop for longer
than two and a half intervals at the current rate.

## Consequences

- A recent phone runs at the camera's 30 fps under `auto`, and a slow one at what it can finish
  with 15% to spare, with no restart as the measurement settles.
- The first half second runs at the camera's rate before anything is measured. An unknown device
  is not a slow one, and the first estimate lands after 15 frames.
- The tier is now a label reported for the app's benefit. It drives nothing.
- The smoothing default changed: a consumer who relied on `smoothing` being on for one pose sees
  MediaPipe's filter alone, which is the one that was doing the work. `smoothing: true` restores
  the old behavior, now in body-span units.
- A profile that wants a smaller geometry, `efficient`, gets it at the next mount rather than as a
  restart mid-session.
