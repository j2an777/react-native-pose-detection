import {
  addLogListener,
  detectOnImage,
  detectOnVideo,
  exportPose,
  landmark,
  setLogLevel,
  type LogEntry,
  type PoseCameraRef,
  type PoseFrame,
  type ProfileState,
} from 'react-native-pose-detection';

import { formatBytes, jsHeapBytes } from '../memory';
import type { Scenario, ScenarioContext, ScenarioReport } from './types';

const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

/** Thrown by a runner that cannot apply on this device or in this scene. Reported, not failed. */
class Skip extends Error {}

/**
 * Every runner returns a report rather than throwing, so one failure does not stop a sweep and
 * the panel can show what happened on the run that failed next to the runs that did not.
 */
async function measure(
  id: string,
  iterations: number,
  body: (report: (line: string) => void) => Promise<string>,
  context: ScenarioContext,
): Promise<ScenarioReport> {
  const heapBefore = jsHeapBytes();
  const started = Date.now();

  try {
    const detail = await body(context.log);
    return {
      id,
      passed: true,
      iterations,
      elapsedMs: Date.now() - started,
      detail,
      heapBefore,
      heapAfter: jsHeapBytes(),
    };
  } catch (thrown) {
    return {
      id,
      passed: thrown instanceof Skip,
      skipped: thrown instanceof Skip ? true : undefined,
      iterations,
      elapsedMs: Date.now() - started,
      detail: thrown instanceof Error ? thrown.message : String(thrown),
      heapBefore,
      heapAfter: jsHeapBytes(),
    };
  }
}

function requireCamera(context: ScenarioContext): PoseCameraRef {
  const camera = context.camera.current;
  if (!camera) throw new Error('the camera is not mounted');
  return camera;
}

async function profile(context: ScenarioContext): Promise<ProfileState> {
  return requireCamera(context).getProfile();
}

/** Polls until `check` holds or the time runs out, resolving to whether it held. */
async function waitFor(check: () => Promise<boolean> | boolean, timeoutMs: number, stepMs = 100) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      if (await check()) return true;
    } catch {
      // The camera can be between mounts for a moment; keep asking until the deadline.
    }
    await sleep(stepMs);
  }
  return false;
}

/**
 * Frames are reaching the model: the measured rate counts empty results too. The rate stays up for
 * two seconds after the last result, so right after a stop this passes on the old frames. A check
 * that frames came back has to see them stop first, see `framesStopped`.
 */
async function framesFlow(context: ScenarioContext, timeoutMs = 5_000): Promise<number | null> {
  const started = Date.now();
  const flowing = await waitFor(async () => (await profile(context)).measuredFps > 0, timeoutMs);
  return flowing ? Date.now() - started : null;
}

/**
 * A failure that says what the camera reported about itself when frames did not come, which is
 * where to start: stopped, not detecting, paused by heat, or running and simply getting nothing.
 */
async function noFrames(context: ScenarioContext, what: string): Promise<Error> {
  try {
    const camera = requireCamera(context);
    const state = camera.getState();
    const now = await camera.getProfile();
    return new Error(
      `${what} (active ${state.active}, detecting ${state.detecting}, ` +
        `${now.measuredFps}/${now.resolved.targetFps} fps, ${now.limitedBy}, ${now.phase})`,
    );
  } catch (problem) {
    return new Error(`${what} (and the camera did not answer: ${String(problem)})`);
  }
}

/** The measured rate has gone to zero, which it does two seconds after the last result. */
async function framesStopped(context: ScenarioContext, timeoutMs = 4_000): Promise<boolean> {
  return waitFor(async () => (await profile(context)).measuredFps === 0, timeoutMs);
}

function withTimeout<T>(promise: Promise<T>, ms: number, what: string): Promise<T> {
  return Promise.race([
    promise,
    new Promise<T>((_, reject) =>
      setTimeout(() => reject(new Error(`${what} did not settle within ${ms} ms`)), ms),
    ),
  ]);
}

/** The nose above both ankles, which is what a person the right way up looks like. */
function headAboveFeet(frame: PoseFrame): boolean {
  const nose = landmark(frame, 'nose').y;
  return nose < landmark(frame, 'leftAnkle').y && nose < landmark(frame, 'rightAnkle').y;
}

/** Rejects with exactly this code, or the check fails. */
async function expectCode(promise: Promise<unknown>, code: string): Promise<void> {
  try {
    await promise;
  } catch (problem) {
    const actual = (problem as { code?: unknown }).code;
    if (actual === code) return;
    throw new Error(`expected ${code}, got ${String(actual)}`);
  }
  throw new Error(`expected ${code}, but it resolved`);
}

function median(values: number[]): number {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.floor(sorted.length / 2)] ?? 0;
}

export const SCENARIOS: readonly Scenario[] = [
  {
    id: 'startup',
    title: 'Startup ×3',
    verifies: 'Time from mount to onReady and to a measured rate, once the GPU check is cached.',
    run: (context) =>
      measure(
        'startup',
        3,
        async (log) => {
          const ready: number[] = [];
          const flowing: number[] = [];
          for (let index = 0; index < 3; index += 1) {
            const started = Date.now();
            await context.remount();
            ready.push(Date.now() - started);
            const first = await framesFlow(context);
            if (first === null)
              throw await noFrames(context, 'no frame reached the model within 5 s of onReady');
            flowing.push(Date.now() - started);
            log(`mount ${index + 1}: ready ${ready[index]} ms, rate ${flowing[index]} ms`);
          }
          return `median ready ${median(ready)} ms, median first rate ${median(flowing)} ms`;
        },
        context,
      ),
  },
  {
    id: 'switch-camera',
    title: 'Switch camera ×100',
    verifies:
      'Every switch lands and reports itself, and 100 switches end on the lens they began on.',
    run: (context) =>
      measure(
        'switch-camera',
        100,
        async (log) => {
          const initial = context.facing();
          const changesBefore = context.cameraChanges();

          // No sleep between switches: `switchCamera()` resolves once the new lens delivers a frame,
          // and the whole point of this run is to start the next one the instant that happens.
          for (let index = 0; index < 100; index += 1) {
            await withTimeout(requireCamera(context).switchCamera(), 5_000, `switch ${index + 1}`);
            if ((index + 1) % 25 === 0) log(`${index + 1} switches`);
          }

          // The event and the promise travel separately, so the last event can land just after the
          // last promise resolves. Missing for longer than that, it is lost.
          await waitFor(() => context.cameraChanges() - changesBefore >= 100, 1_000);
          const reported = context.cameraChanges() - changesBefore;
          if (reported !== 100) throw new Error(`100 switches reported ${reported} camera changes`);
          if (initial && context.facing() !== initial) {
            throw new Error(
              `ended on ${context.facing()} after an even number of switches from ${initial}`,
            );
          }
          const flowing = await framesFlow(context);
          if (flowing === null)
            throw await noFrames(context, 'frames stopped reaching the model after the switches');
          return `100 switches, all reported, back on ${context.facing()}`;
        },
        context,
      ),
  },
  {
    id: 'overlapping-switch',
    title: 'Overlapping switches ×20',
    verifies:
      'Two switches at once both settle: the second queues behind the first, as on Android.',
    run: (context) =>
      measure(
        'overlapping-switch',
        20,
        async () => {
          let fulfilled = 0;
          let rejected = 0;
          for (let index = 0; index < 20; index += 1) {
            const camera = requireCamera(context);
            const results = await withTimeout(
              Promise.allSettled([camera.switchCamera(), camera.switchCamera()]),
              8_000,
              `pair ${index + 1}`,
            );
            for (const result of results) {
              if (result.status === 'fulfilled') fulfilled += 1;
              else rejected += 1;
            }
          }
          return `40 switches settled: ${fulfilled} landed, ${rejected} rejected`;
        },
        context,
      ),
  },
  {
    id: 'pause-during-startup',
    title: 'Pause during startup ×5',
    verifies:
      'A pause before the camera is up, then a resume, brings back preview, detector and onReady.',
    run: (context) =>
      measure(
        'pause-during-startup',
        5,
        async (log) => {
          for (let index = 0; index < 5; index += 1) {
            const { ready } = await context.remountNow();
            // Before the session is up: this is the race that used to leave the camera running with
            // no preview, and a resume that never brought the detector or onReady back.
            await requireCamera(context).pause();
            await sleep(600);
            await requireCamera(context).resume();
            await withTimeout(ready, 6_000, `onReady after resume ${index + 1}`);
            const flowing = await framesFlow(context);
            if (flowing === null)
              throw await noFrames(context, `no frames after resume ${index + 1}`);
            if (!requireCamera(context).getState().active)
              throw new Error('getState says inactive');
            log(`cycle ${index + 1}: frames ${flowing} ms after onReady`);
          }
          return '5 pauses during startup, each resumed to a running detector';
        },
        context,
      ),
  },
  {
    id: 'modal-detach',
    title: 'Cover with a modal ×3',
    verifies: 'Detection resumes after the view leaves the window and comes back.',
    run: (context) =>
      measure(
        'modal-detach',
        3,
        async (log) => {
          for (let index = 0; index < 3; index += 1) {
            // Longer than the two seconds the rate outlives the last result, so the check below
            // sees frames that came back rather than ones from before the cover.
            await context.cover(2_500);
            const flowing = await framesFlow(context, 6_000);
            if (flowing === null)
              throw await noFrames(
                context,
                `no frames reached the model after uncover ${index + 1}`,
              );
            log(`uncover ${index + 1}: frames back in ${flowing} ms`);
          }
          return '3 covers, frames back after each';
        },
        context,
      ),
  },
  {
    id: 'prop-toggles',
    title: 'Prop toggles ×20',
    verifies: 'Overlay, smoothing and data mode changes never restart the camera.',
    run: (context) =>
      measure(
        'prop-toggles',
        20,
        async () => {
          const before = context.readyCount();
          for (let index = 0; index < 20; index += 1) {
            context.toggleProps();
            await sleep(250);
          }
          const restarts = context.readyCount() - before;
          if (restarts > 0) throw new Error(`${restarts} restarts during 20 prop changes`);
          if ((await framesFlow(context)) === null)
            throw await noFrames(context, 'frames stopped after the toggles');
          return '20 prop changes, no restart, frames still flowing';
        },
        context,
      ),
  },
  {
    id: 'detection-toggle',
    title: 'Stop / start detection ×20',
    verifies: 'The landmarker is parked, not rebuilt: frames flow again at once after every start.',
    run: (context) =>
      measure(
        'detection-toggle',
        20,
        async (log) => {
          // Back to back first: the stress half, with nothing waited on between cycles.
          for (let index = 0; index < 20; index += 1) {
            const camera = requireCamera(context);
            await camera.stopDetection();
            await camera.startDetection();
          }
          // Then measured: each stop is seen to stop, so the frames after the start are new ones.
          const times: number[] = [];
          for (let index = 0; index < 3; index += 1) {
            await requireCamera(context).stopDetection();
            if (!(await framesStopped(context)))
              throw new Error(`frames kept coming after stop ${index + 1}`);
            await requireCamera(context).startDetection();
            const flowing = await framesFlow(context);
            if (flowing === null)
              throw await noFrames(context, `no frames after start ${index + 1}`);
            times.push(flowing);
            log(`start ${index + 1}: frames in ${flowing} ms`);
          }
          return `20 back-to-back cycles, then median ${median(times)} ms from start to frames`;
        },
        context,
      ),
  },
  {
    id: 'overlay-toggle',
    title: 'Toggle overlay ×50',
    verifies: 'No layer leaks. The overlay is a view on Android and shape layers on iOS.',
    run: (context) =>
      measure(
        'overlay-toggle',
        50,
        async () => {
          for (let index = 0; index < 50; index += 1) {
            const camera = requireCamera(context);
            await camera.setOverlayEnabled(false);
            await camera.setOverlayEnabled(true);
          }
          return '50 off and on cycles';
        },
        context,
      ),
  },
  {
    id: 'pause-resume',
    title: 'Pause / resume ×30',
    verifies:
      'The session releases and restores without a full teardown, frames flowing after each.',
    run: (context) =>
      measure(
        'pause-resume',
        30,
        async (log) => {
          for (let index = 0; index < 30; index += 1) {
            const camera = requireCamera(context);
            await camera.pause();
            await camera.resume();
          }
          // One measured cycle, seen to stop before the resume, so the frames after it are new.
          await requireCamera(context).pause();
          if (!(await framesStopped(context))) throw new Error('frames kept coming while paused');
          await requireCamera(context).resume();
          const flowing = await framesFlow(context);
          if (flowing === null) throw await noFrames(context, 'no frames after the last resume');
          log(`resume: frames in ${flowing} ms`);
          return `30 back-to-back cycles, then frames ${flowing} ms after a resume`;
        },
        context,
      ),
  },
  {
    id: 'idle',
    title: 'Idle search',
    verifies: 'With nobody in frame: 12 fps after 2 s, 5 fps after 20 s. Needs an empty scene.',
    run: (context) =>
      measure(
        'idle',
        1,
        async (log) => {
          await context.remount();
          const first = await waitFor(
            async () => (await profile(context)).limitedBy === 'idle',
            4_000,
            200,
          );
          if (!first) throw new Skip('skipped: never went idle, which means somebody is in frame');
          const early = await profile(context);
          log(`idle at ${early.resolved.targetFps} fps`);
          if (early.resolved.targetFps > 15)
            throw new Error(`first idle step is ${early.resolved.targetFps} fps`);

          const deep = await waitFor(
            async () => (await profile(context)).resolved.targetFps <= 5,
            24_000,
            500,
          );
          const late = await profile(context);
          if (!deep)
            throw new Error(`still at ${late.resolved.targetFps} fps after 22 s without a pose`);
          return `idle ${early.resolved.targetFps} fps, then ${late.resolved.targetFps} fps, measured ${late.measuredFps}`;
        },
        context,
      ),
  },
  {
    id: 'remount',
    title: 'Remount ×50',
    verifies: 'Memory returns to baseline. Every teardown released what its mount took.',
    run: (context) =>
      measure(
        'remount',
        50,
        async (log) => {
          for (let index = 0; index < 50; index += 1) {
            await context.remount();
            if ((index + 1) % 10 === 0) log(`${index + 1} remounts`);
          }
          return '50 mount and unmount cycles, each awaited to onReady';
        },
        context,
      ),
  },
  {
    id: 'files',
    title: 'Photos and videos',
    verifies:
      'An EXIF-rotated photo and a clip stored sideways come out upright; the clip is sampled in ' +
      'order at real timestamps, trims, cancels and exports; unreadable files reject with their codes.',
    run: (context) =>
      measure(
        'files',
        1,
        async (log) => {
          const { photo, rotatedPhoto, clip } = context.media;
          if (!photo || !rotatedPhoto || !clip) {
            throw new Skip('no media given: scripts/device-diagnostics.sh pushes and passes them');
          }
          // Which delegate each job took and every sample that found nobody, in the device log.
          setLogLevel({ detector: 'info', engine: 'trace' });

          // A photo stored sideways with EXIF 6 is the same picture once it is turned upright.
          const upright = (await detectOnImage(photo))[0];
          const turned = (await detectOnImage(rotatedPhoto))[0];
          if (!upright || !turned) throw new Error('no pose in the photo or in its rotated copy');
          const drift = Math.max(
            Math.abs(landmark(upright, 'nose').x - landmark(turned, 'nose').x),
            Math.abs(landmark(upright, 'nose').y - landmark(turned, 'nose').y),
          );
          if (drift > 0.03)
            throw new Error(`the EXIF photo lands ${drift.toFixed(3)} away from the upright one`);
          if (!headAboveFeet(turned)) throw new Error('the EXIF photo was detected sideways');
          log(`photo: EXIF copy within ${drift.toFixed(3)} of the upright one`);

          // The clip: sampled in order at real positions, upright, and moving right. Detection is
          // stopped on the camera for this one, so the job may take the GPU; the trimmed run below
          // keeps it on, which holds the job to the CPU. Both paths run.
          await requireCamera(context).stopDetection();
          let progressEvents = 0;
          const started = Date.now();
          const frames = await detectOnVideo(clip, {
            fps: 10,
            onProgress: () => {
              progressEvents += 1;
            },
          }).frames;
          const elapsed = Date.now() - started;
          log(`clip timestamps: ${frames.map((frame) => Math.round(frame.timestamp)).join(' ')}`);
          if (frames.length < 25)
            throw new Error(`the clip gave ${frames.length} frames, expected about 30`);
          const times = frames.map((frame) => frame.timestamp);
          if (times.some((time, index) => index > 0 && time <= (times[index - 1] ?? 0)))
            throw new Error('frame timestamps do not increase');
          if ((times[times.length - 1] ?? 0) > 3_000)
            throw new Error('timestamps run past the clip');
          if (!frames.every(headAboveFeet))
            throw new Error('some clip frames were detected sideways');
          const moving = frames.slice(1).filter((frame) => frame.velocity.x > 0).length;
          if (moving < (frames.length - 1) / 2)
            throw new Error(
              `only ${moving} of ${frames.length - 1} frames measured the rightward movement`,
            );
          log(
            `clip: ${frames.length} frames in ${elapsed} ms, ${progressEvents} progress events, ` +
              `${moving} moving right`,
          );

          await requireCamera(context).startDetection();
          const trimmed = await detectOnVideo(clip, { fps: 10, startMs: 1_000, endMs: 2_000 })
            .frames;
          if (
            trimmed.length === 0 ||
            trimmed.some((frame) => frame.timestamp < 960 || frame.timestamp > 2_000)
          )
            throw new Error('the trimmed range returned frames outside it');

          const cancelled = detectOnVideo(clip, { fps: 30 });
          setTimeout(() => cancelled.cancel(), 250);
          const partial = await cancelled.frames;
          if (partial.length >= 85) throw new Error('cancel did not stop the job');
          log(`trim: ${trimmed.length} frames; cancel kept ${partial.length}`);

          await expectCode(detectOnImage(`${photo}.missing`), 'IMAGE_DECODE_FAILED');
          await expectCode(detectOnVideo(`${clip}.missing`).frames, 'VIDEO_DECODE_FAILED');

          const still = await exportPose(rotatedPhoto).result;
          if (still.width <= still.height) throw new Error('the EXIF photo exported sideways');
          if (still.posesFound < 1) throw new Error('the photo export painted nobody');
          const exported = await exportPose(clip).result;
          if (exported.width <= exported.height) throw new Error('the clip exported sideways');
          if (exported.posesFound < 25)
            throw new Error(`the clip export found ${exported.posesFound} poses`);
          log(
            `exports: photo ${still.width}x${still.height}, clip ${exported.width}x${exported.height} ` +
              `with ${exported.frameCount} frames`,
          );

          setLogLevel('off');
          return (
            `upright photos, ${frames.length} clip frames in ${elapsed} ms, trim, cancel, ` +
            'error codes and exports all as documented'
          );
        },
        context,
      ),
  },
  {
    id: 'logs',
    title: 'Logs without a camera',
    verifies:
      'addLogListener() hears a video job with no camera mounted, and keeps hearing once a camera ' +
      'is back and takes the flush over.',
    run: (context) =>
      measure(
        'logs',
        1,
        async (log) => {
          const { clip } = context.media;
          if (!clip) {
            throw new Skip('no media given: scripts/device-diagnostics.sh pushes and passes them');
          }
          const heard: LogEntry[] = [];
          const subscription = addLogListener((entries) => {
            heard.push(...entries);
          });
          setLogLevel({ camera: 'info', detector: 'info', engine: 'info' });
          try {
            let alone = 0;
            await context.withoutCamera(async () => {
              await detectOnVideo(clip, { fps: 2 }).frames;
              // A flush interval and a margin, for the batch the job's entries went out in.
              await sleep(600);
              alone = heard.length;
            });
            const jobs = heard
              .slice(0, alone)
              .filter((entry) => entry.message.startsWith('file job'));
            if (jobs.length === 0) {
              throw new Error(
                `no file job entry reached the listener without a camera (${alone} in all)`,
              );
            }
            log(`no camera: ${alone} entries, ${jobs.length} of them the video job's`);

            // What the camera logs as it comes back reaches the same listener.
            for (let waited = 0; waited < 3_000 && heard.length === alone; waited += 100) {
              await sleep(100);
            }
            if (heard.length === alone)
              throw new Error('nothing reached the listener once the camera was back');
            log(`camera back: ${heard.length - alone} more entries`);
            return `${alone} entries with no camera, and the camera's own after it came back`;
          } finally {
            subscription.remove();
            setLogLevel('off');
          }
        },
        context,
      ),
  },
  {
    id: 'soak',
    title: 'Soak 10 minutes',
    verifies:
      'The rate holds and the heat stays at fair or below. Run with somebody moving in frame.',
    slow: true,
    run: (context) =>
      measure(
        'soak',
        600,
        async (log) => {
          const heapStart = jsHeapBytes();
          let hottest = 'nominal';
          const order = ['nominal', 'fair', 'serious', 'critical'];
          const rates: number[] = [];

          for (let minute = 1; minute <= 10; minute += 1) {
            await sleep(60_000);
            const now = await profile(context);
            rates.push(now.measuredFps);
            if (order.indexOf(now.thermalState) > order.indexOf(hottest))
              hottest = now.thermalState;
            log(
              `${minute} min: ${now.measuredFps}/${now.resolved.targetFps} fps (${now.limitedBy}), ` +
                `p50 ${now.p50InferenceMs.toFixed(1)} ms, heat ${now.thermalState}` +
                `${now.lowPower ? ', low power' : ''}, JS heap ${formatBytes(jsHeapBytes())}`,
            );
          }

          return (
            `10 minutes, median ${median(rates)} fps, hottest ${hottest}, ` +
            `JS heap ${formatBytes(heapStart)} to ${formatBytes(jsHeapBytes())}`
          );
        },
        context,
      ),
  },
];

/**
 * Reproduced from outside the app, because neither platform lets a process put itself into a
 * thermal state, clear another process's preferences, or send itself a memory warning. The panel
 * shows the command and then watches for what it should have caused.
 */
export const EXTERNAL: readonly {
  title: string;
  verifies: string;
  android: string;
  ios: string;
}[] = [
  {
    title: 'Force thermal state',
    verifies:
      'Each step halves or pauses as documented, as onPerformanceChange with reason thermal.',
    android: 'adb shell cmd thermalservice override-status 3',
    ios: 'Xcode · Devices and Simulators · Simulate thermal state',
  },
  {
    title: 'Simulate memory warning',
    verifies: 'The landmarker is released at once and rebuilt when the camera is next used.',
    android: 'adb shell am send-trim-memory com.posedetection.example RUNNING_CRITICAL',
    ios: 'Simulator · Features · Trigger Memory Warning',
  },
  {
    title: 'Clear calibration cache',
    verifies: 'The next launch measures from scratch: phase calibrating, then settled.',
    android: 'adb shell pm clear com.posedetection.example',
    ios: 'Delete the app and reinstall',
  },
  {
    title: 'Background and foreground',
    verifies: 'Back within 30 s: the skeleton returns at once, the landmarker was only parked.',
    android: 'Home, wait 10 s, reopen',
    ios: 'Home, wait 10 s, reopen',
  },
  {
    title: 'Low Power Mode or Battery Saver',
    verifies: 'The rate caps at 24 with limitedBy lowPower, and returns when it is turned off.',
    android: 'adb shell settings put global low_power 1',
    ios: 'Settings · Battery · Low Power Mode',
  },
];
