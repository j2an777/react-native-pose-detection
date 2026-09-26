# 0010: Frames are read on the JavaScript thread

**Status:** accepted, amends [0008](./0008-frames-are-drained-not-pushed.md)
**Date:** 2026-09-26

## Context

[ADR 0008](./0008-frames-are-drained-not-pushed.md) moved frames off events: native emits a tick
with no landmarks, and JavaScript answers it by calling a drain that returns an ArrayBuffer. The
drain was a function on the view, which was the natural place for it, since the buffer belongs to
one camera.

ExpoModulesCore runs every view function on the main queue. So every drain queued behind layout,
the preview and the overlay, and an ordinary `throttled` session made two main-queue round trips
per tick for work that touches nothing on main. On a busy screen the drain waited longer than it
ran, and the delay landed exactly where a skeleton lags behind the body.

`getState()` had the matching problem the other way round. It stayed synchronous by reading a
mirror of the last events, so its `fps` was whatever the last `onPerformanceChange` said, which
could be seconds old.

## Decision

Every read that JavaScript makes of a camera's frames is a **module** function rather than a view
function, and runs synchronously on the JavaScript thread that calls it: `drainFrames`,
`snapshotFrame`, `takeTriggerSnapshot` and `readLiveState`.

A module function has no view, so it finds the camera by an id. `<PoseCamera>` mints one per
mount and passes it as the `streamId` prop; the view registers its ring buffer and a live-state
reader under that id, weakly, and a read by an id that has gone returns an empty buffer rather
than failing. Everything a read touches was already thread-safe, because the inference thread
writes the ring buffer and the rate under a lock.

`getState()` merges the event mirror with `readLiveState()`, so `fps` and `limitedBy` are read
live on every call.

## Consequences

- A drain costs a lock and a copy on the JavaScript thread, and never waits on main.
- `snapshot()` keeps its `Promise` signature, because changing a public type would break callers
  for no benefit, but the promise is already settled when it is returned.
- Polling `getState()` for a readout is cheap and current, so the advice to poll `getProfile()` for
  a live rate is gone. `getProfile()` still reads the calibration on main.
- The view functions that remain are commands: switching, pausing, detection, the overlay and the
  profile. Those belong on main, next to the capture session they change.
- 0008's decision stands: frames are still drained, not pushed, and the wire format is unchanged.
  Only the thread a drain runs on moved.
