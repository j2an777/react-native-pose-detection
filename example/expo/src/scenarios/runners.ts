import type { PoseCameraRef, ProfileState } from 'react-native-pose-detection';

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

/** Frames are reaching the model again: the measured rate counts empty results too. */
async function framesFlow(context: ScenarioContext, timeoutMs = 5_000): Promise<number | null> {
  const started = Date.now();
  const flowing = await waitFor(async () => (await profile(context)).measuredFps > 0, timeoutMs);
  return flowing ? Date.now() - started : null;
}

function withTimeout<T>(promise: Promise<T>, ms: number, what: string): Promise<T> {
  return Promise.race([
    promise,
    new Promise<T>((_, reject) =>
      setTimeout(() => reject(new Error(`${what} did not settle within ${ms} ms`)), ms),
    ),
  ]);
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
            if (first === null) throw new Error('no frame reached the model within 5 s of onReady');
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

          const reported = context.cameraChanges() - changesBefore;
          if (reported !== 100) throw new Error(`100 switches reported ${reported} camera changes`);
          if (initial && context.facing() !== initial) {
            throw new Error(
              `ended on ${context.facing()} after an even number of switches from ${initial}`,
            );
          }
          const flowing = await framesFlow(context);
          if (flowing === null)
            throw new Error('frames stopped reaching the model after the switches');
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
            if (flowing === null) throw new Error(`no frames after resume ${index + 1}`);
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
            await context.cover(1_500);
            const flowing = await framesFlow(context, 6_000);
            if (flowing === null)
              throw new Error(`no frames reached the model after uncover ${index + 1}`);
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
            throw new Error('frames stopped after the toggles');
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
          const times: number[] = [];
          for (let index = 0; index < 20; index += 1) {
            const camera = requireCamera(context);
            await camera.stopDetection();
            await camera.startDetection();
            const flowing = await framesFlow(context);
            if (flowing === null) throw new Error(`no frames after start ${index + 1}`);
            times.push(flowing);
            if ((index + 1) % 5 === 0) log(`${index + 1} cycles`);
          }
          return `20 stop and start cycles, median ${median(times)} ms to frames`;
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
        async () => {
          for (let index = 0; index < 30; index += 1) {
            const camera = requireCamera(context);
            await camera.pause();
            await camera.resume();
          }
          if ((await framesFlow(context)) === null)
            throw new Error('no frames after the last resume');
          return '30 pause and resume cycles';
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
