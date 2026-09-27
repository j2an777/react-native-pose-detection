import {
  hasLandmark,
  landmark,
  type Condition,
  type PoseFrame,
  type Trigger,
  type TriggerEvent,
} from 'react-native-pose-detection';

import { headAboveFeet, measure, requireCamera, sleep } from './runners';
import type { CameraProps, Scenario, ScenarioContext } from './types';

const STEP_MS = 45_000;
const STEPS = 12;
const SELECTED = ['nose', 'leftWrist', 'rightWrist'] as const;

// y grows downward, so a raised hand is `below` the nose.
const ARMS_UP: Condition = {
  all: [
    { landmarkY: 'leftWrist', below: 'nose' },
    { landmarkY: 'rightWrist', below: 'nose' },
  ],
};

const ARMS_DOWN: Condition = {
  all: [
    { landmarkY: 'leftWrist', above: 'leftShoulder' },
    { landmarkY: 'rightWrist', above: 'rightShoulder' },
  ],
};

const ELBOW_BENT: Condition = {
  any: [
    { angle: 'leftElbow', between: [70, 110] },
    { angle: 'rightElbow', between: [70, 110] },
  ],
};

const HAND_WAVED: Condition = {
  any: [
    { velocityX: 'leftWrist', above: 0.8 },
    { velocityX: 'leftWrist', below: -0.8 },
    { velocityX: 'rightWrist', above: 0.8 },
    { velocityX: 'rightWrist', below: -0.8 },
  ],
};

function personTriggers(squatDebounceMs: number): Trigger[] {
  return [
    {
      id: 'present',
      enter: {
        all: [
          { visibility: 'leftShoulder', above: 0.7 },
          { visibility: 'rightShoulder', above: 0.7 },
          { visibility: 'leftAnkle', above: 0.5 },
          { visibility: 'rightAnkle', above: 0.5 },
        ],
      },
      emit: 'enter',
    },
    // The squat counter from guides/triggers.md, as printed there.
    {
      id: 'rep',
      enter: { angle: 'leftKnee', below: 90 },
      exit: { angle: 'leftKnee', above: 160 },
      emit: 'cycle',
      debounceMs: 300,
      snapshot: true,
    },
    {
      id: 'squat',
      enter: { angle: 'leftKnee', below: 110 },
      exit: { angle: 'leftKnee', above: 150 },
      emit: 'cycle',
      debounceMs: squatDebounceMs,
    },
    { id: 'arms-up', enter: ARMS_UP, emit: 'enter', minDurationMs: 1_000, snapshot: true },
    { id: 'arms-held', enter: ARMS_UP, emit: 'while', throttleMs: 500 },
    { id: 'arms-down', enter: ARMS_UP, exit: ARMS_DOWN, emit: 'exit' },
    { id: 'elbow', enter: ELBOW_BENT, emit: 'enter', minDurationMs: 500, debounceMs: 2_000 },
    { id: 'wave', enter: HAND_WAVED, emit: 'enter', debounceMs: 1_000 },
    {
      id: 'jump',
      enter: { velocityY: 'centerOfMass', below: -0.4 },
      emit: 'enter',
      debounceMs: 1_500,
    },
  ];
}

function describe(event: TriggerEvent): string {
  return (
    `${event.id} ${event.phase} count ${event.count} at ${Math.round(event.timestamp)}` +
    (event.durationMs === undefined ? '' : ` after ${Math.round(event.durationMs)} ms`) +
    (event.snapshot ? ' with snapshot' : '')
  );
}

function inOrder(frames: readonly PoseFrame[]): boolean {
  return frames.every(
    (frame, index) => index === 0 || frame.timestamp > (frames[index - 1]?.timestamp ?? Infinity),
  );
}

/** Resolves with what arrived: `count` events, or fewer once the time is up. */
async function arrivals(
  heard: readonly TriggerEvent[],
  from: number,
  id: string,
  count = 1,
  timeoutMs = STEP_MS,
  progress?: (found: number) => void,
): Promise<TriggerEvent[]> {
  const deadline = Date.now() + timeoutMs;
  let shown = -1;
  for (;;) {
    const found = heard.slice(from).filter((event) => event.id === id);
    if (found.length !== shown) {
      shown = found.length;
      progress?.(found.length);
    }
    if (found.length >= count || Date.now() >= deadline) return found;
    await sleep(100);
  }
}

async function measuredFps(context: ScenarioContext): Promise<number> {
  return (await requireCamera(context).getProfile()).measuredFps;
}

function checkRate(frames: readonly PoseFrame[], fps: number, seconds: number): void {
  const expected = fps * seconds;
  if (frames.length < expected * 0.6 || frames.length > expected * 1.4) {
    throw new Error(`${frames.length} frames in ${seconds} s at ${fps} fps`);
  }
  if (!inOrder(frames)) throw new Error('frames out of order or repeated');
}

async function run(context: ScenarioContext, log: (line: string) => void): Promise<string> {
  const heard: TriggerEvent[] = [];
  const moves: string[] = [];
  let dropped = 0;

  let base: CameraProps = {
    triggers: personTriggers(300),
    onTrigger: (event) => {
      heard.push(event);
      log(`trigger ${describe(event)}`);
    },
    onFramesDropped: (count) => {
      dropped += count;
    },
    onPerformanceChange: (event) => {
      moves.push(`${event.reason} ${event.targetFps} fps`);
    },
  };
  context.setCameraProps(base);

  const results: string[] = [];
  let failures = 0;
  let index = 0;
  const say = (text: string) => context.prompt(`${index}/${STEPS}\n${text}`);
  const step = async (name: string, body: () => Promise<string>) => {
    index += 1;
    try {
      const detail = await body();
      results.push(`${name} ok`);
      log(`ok ${name}: ${detail}`);
    } catch (problem) {
      failures += 1;
      const detail = problem instanceof Error ? problem.message : String(problem);
      results.push(`${name} FAILED (${detail})`);
      log(`FAILED ${name}: ${detail}`);
    }
  };
  const lastCount = (id: string) => heard.filter((event) => event.id === id).pop()?.count ?? 0;

  try {
    await step('in view', async () => {
      say('Stand 2 to 3 m back,\nwhole body on screen');
      const [present] = await arrivals(heard, 0, 'present');
      if (!present) throw new Error('the presence trigger never fired');
      const camera = requireCamera(context);
      const frame = await camera.snapshot();
      if (!frame) throw new Error('snapshot() found nobody');
      if (frame.landmarks.length !== 33 * 4)
        throw new Error(`snapshot() carried ${frame.landmarks.length} floats`);
      if (!headAboveFeet(frame)) throw new Error('snapshot() is not upright');
      const state = camera.getState();
      if (!state.detecting || state.fps <= 0)
        throw new Error(`getState() says detecting ${state.detecting} at ${state.fps} fps`);
      return `presence fired, snapshot() upright, getState() ${state.fps} fps on ${state.delegate}`;
    });

    await step('throttled', async () => {
      say('Stand still for 20 seconds');
      const frames: PoseFrame[] = [];
      context.setCameraProps({
        ...base,
        data: {
          mode: 'throttled',
          throttleMs: 200,
          select: SELECTED,
          angles: ['leftKnee'],
          worldLandmarks: true,
        },
        onPose: (frame) => frames.push(frame),
      });
      await sleep(600);
      frames.length = 0;
      await sleep(3_000);
      const taken = [...frames];
      context.setCameraProps(base);

      if (taken.length < 8 || taken.length > 17)
        throw new Error(`${taken.length} frames in 3 s at throttleMs 200`);
      const gaps = taken.slice(1).map((frame, at) => frame.timestamp - (taken[at]?.timestamp ?? 0));
      const closest = Math.min(...gaps);
      if (closest < 150)
        throw new Error(`two frames ${Math.round(closest)} ms apart at throttleMs 200`);
      for (const frame of taken) {
        if (frame.landmarks.length !== SELECTED.length * 4)
          throw new Error(`${frame.landmarks.length} floats for ${SELECTED.length} joints`);
        if (!hasLandmark(frame, 'nose') || hasLandmark(frame, 'leftAnkle'))
          throw new Error(`the selection reads as ${String(frame.selection)}`);
        const knee = frame.angles?.leftKnee;
        if (knee === undefined || !(knee >= 0 && knee <= 180))
          throw new Error(`angles.leftKnee is ${String(knee)}`);
        const world = frame.worldLandmarks;
        if (
          world?.length !== SELECTED.length * 4 ||
          !world.every((value) => Number.isFinite(value) && Math.abs(value) < 3)
        )
          throw new Error('worldLandmarks missing or out of range');
        if (!(frame.bodySpan > 0.05) || !(frame.processingMs > 0))
          throw new Error(`bodySpan ${frame.bodySpan}, processingMs ${frame.processingMs}`);
      }
      const unmeasured = taken.filter((frame) => !Number.isFinite(frame.velocity.x)).length;
      if (unmeasured > 1) throw new Error(`${unmeasured} frames without a velocity`);
      const first = taken[0];
      const nose = first ? landmark(first, 'nose') : null;
      if (!nose || !(nose.visibility > 0.5))
        throw new Error(`nose visibility ${String(nose?.visibility)}`);
      return `${taken.length} frames at least ${Math.round(
        closest,
      )} ms apart, selection, angles and world landmarks as asked`;
    });

    await step('batched', async () => {
      const sizes: number[] = [];
      const frames: PoseFrame[] = [];
      context.setCameraProps({
        ...base,
        data: { mode: 'batched', flushMs: 500 },
        onPoseBatch: (batch) => {
          sizes.push(batch.length);
          frames.push(...batch);
        },
      });
      await sleep(1_100);
      sizes.length = 0;
      frames.length = 0;
      await sleep(3_000);
      const batches = [...sizes];
      const taken = [...frames];
      context.setCameraProps(base);
      const fps = await measuredFps(context);

      if (batches.length < 4 || batches.length > 8)
        throw new Error(`${batches.length} batches in 3 s at flushMs 500`);
      if (batches.includes(0)) throw new Error('an empty batch');
      if (taken.some((frame) => frame.landmarks.length !== 33 * 4))
        throw new Error('a batched frame without all 33 landmarks');
      checkRate(taken, fps, 3);
      return `${batches.length} batches (${batches.join('/')}), ${
        taken.length
      } frames at ${fps} fps, in order`;
    });

    await step('live', async () => {
      const frames: PoseFrame[] = [];
      context.setCameraProps({
        ...base,
        data: { mode: 'live' },
        onPose: (frame) => frames.push(frame),
      });
      await sleep(600);
      frames.length = 0;
      await sleep(3_000);
      const taken = [...frames];
      context.setCameraProps(base);
      const fps = await measuredFps(context);
      checkRate(taken, fps, 3);
      return `${taken.length} frames in 3 s at ${fps} fps, in order`;
    });

    await step('dropped', async () => {
      const fps = Math.max(await measuredFps(context), 1);
      // The ring buffer holds 64 frames; stall long enough to overrun it.
      const stallMs = Math.round((64 / fps) * 1_000) + 1_500;
      context.setCameraProps({ ...base, data: { mode: 'live' }, onPose: () => undefined });
      say(`Stand still: the app freezes for ${Math.round(stallMs / 1_000)} s on purpose`);
      await sleep(800);
      dropped = 0;
      const until = Date.now() + stallMs;
      while (Date.now() < until) {
        // Busy on purpose: nothing may drain the buffer.
      }
      await sleep(1_500);
      context.setCameraProps(base);
      if (dropped === 0)
        throw new Error(`nothing reported dropped after a ${stallMs} ms stall at ${fps} fps`);
      return `${dropped} frames reported dropped after a ${stallMs} ms stall at ${fps} fps`;
    });

    await step('hold', async () => {
      const from = heard.length;
      say('Raise both hands above your head\nand hold them there');
      const [up] = await arrivals(heard, from, 'arms-up');
      if (!up) throw new Error('arms-up, held 1 s, never fired');
      say('Keep holding');
      await sleep(2_500);
      say('Lower your arms');
      const [down] = await arrivals(heard, from, 'arms-down', 1, 20_000);
      const since = heard.slice(from);
      const held = since.filter(
        (event) => event.id === 'arms-held' && (!down || event.timestamp <= down.timestamp),
      );
      const ups = since.filter((event) => event.id === 'arms-up').length;

      if (!down) throw new Error('arms-down never fired');
      if (down.phase !== 'exit' || down.count < 1)
        throw new Error(`arms-down came as ${describe(down)}`);
      if (ups !== 1) throw new Error(`arms-up fired ${ups} times in one hold`);
      if (held.length < 3) throw new Error(`${held.length} while events in a hold of 3 s or more`);
      if (held.some((event) => event.phase !== 'enter'))
        throw new Error('a while event not in phase enter');
      const gaps = held.slice(1).map((event, at) => event.timestamp - (held[at]?.timestamp ?? 0));
      if (Math.min(...gaps) < 499)
        throw new Error(`while events ${Math.round(Math.min(...gaps))} ms apart at throttleMs 500`);
      const waited = up.timestamp - (held[0]?.timestamp ?? up.timestamp);
      if (waited < 800)
        throw new Error(`arms-up fired ${Math.round(waited)} ms into a 1000 ms hold`);
      const snap = up.snapshot;
      if (!snap) throw new Error('arms-up came without its snapshot');
      const wristsUp =
        landmark(snap, 'leftWrist').y < landmark(snap, 'nose').y &&
        landmark(snap, 'rightWrist').y < landmark(snap, 'nose').y;
      if (!wristsUp) throw new Error('the arms-up snapshot does not show raised hands');
      return (
        `arms-up ${Math.round(waited)} ms into the hold, ${held.length} while events ` +
        `${Math.round(Math.min(...gaps))} ms or more apart, exit count ${
          down.count
        }, snapshot shows raised hands`
      );
    });

    await step('between', async () => {
      const from = heard.length;
      say('Muscle pose: upper arm out to the side,\nforearm straight up. Hold it');
      const [bent] = await arrivals(heard, from, 'elbow');
      if (!bent) throw new Error('the elbow between 70 and 110 degrees never fired');
      if (bent.phase !== 'enter' || bent.durationMs !== undefined)
        throw new Error(`the elbow came as ${describe(bent)}`);
      return 'any of two elbows between 70 and 110 degrees, held 0.5 s';
    });

    await step('velocity', async () => {
      const from = heard.length;
      say('Wave one hand fast,\nside to side');
      const [wave] = await arrivals(heard, from, 'wave');
      if (!wave) throw new Error('no wrist moved faster than 0.8 widths a second');
      return 'a wrist past 0.8 frame widths a second';
    });

    await step('squats', async () => {
      const from = heard.length;
      const ask = 'Turn side-on, left side to the phone.\nSquat deep, 3 times';
      const reps = await arrivals(heard, from, 'squat', 3, 60_000, (found) =>
        say(found === 0 ? ask : `${ask}: ${found} done`),
      );
      if (reps.length < 3) throw new Error(`${reps.length} of 3 squats counted`);
      const counts = reps.map((event) => event.count);
      if (counts.some((count, at) => at > 0 && count !== (counts[at - 1] ?? 0) + 1))
        throw new Error(`counts went ${counts.join(', ')}`);
      if (reps.some((event) => event.phase !== 'cycle'))
        throw new Error('a squat not in phase cycle');
      const durations = reps.map((event) => Math.round(event.durationMs ?? -1));
      if (durations.some((ms) => ms < 300 || ms > 15_000))
        throw new Error(`rep durations ${durations.join(', ')} ms`);
      const guide = heard.slice(from).filter((event) => event.id === 'rep');
      const knees = guide.map((event) => event.snapshot?.angles?.leftKnee ?? Number.NaN);
      if (knees.some((knee) => !(knee > 160)))
        throw new Error(`guide snapshots at knee angles ${knees.map(Math.round).join(', ')}`);
      return (
        `counts ${counts.join(', ')}, ${durations.join('/')} ms each; ` +
        `the guide's 90/160 trigger counted ${guide.length}, its snapshots at knee ` +
        `${knees.map(Math.round).join('/') || 'none'} degrees`
      );
    });

    await step('switch', async () => {
      say('Stay there:\nswitching cameras');
      const camera = requireCamera(context);
      await camera.switchCamera();
      await camera.switchCamera();
      const before = lastCount('squat');
      const from = heard.length;
      say('Squat once more');
      const [next] = await arrivals(heard, from, 'squat');
      if (!next) throw new Error('no squat after the switches');
      if (next.count !== before + 1)
        throw new Error(`count went from ${before} to ${next.count} across two switches`);
      return `count ${before} to ${next.count} across two camera switches`;
    });

    await step('props', async () => {
      base = { ...base, triggers: personTriggers(400) };
      context.setCameraProps(base);
      const before = lastCount('squat');
      const from = heard.length;
      say('Squat once more');
      const [next] = await arrivals(heard, from, 'squat');
      if (!next) throw new Error('no squat after the triggers were replaced');
      if (next.count !== before + 1)
        throw new Error(`count went from ${before} to ${next.count} across a props update`);
      return `count ${before} to ${next.count} across new trigger objects`;
    });

    await step('jump', async () => {
      const from = heard.length;
      say('Face the phone\nand jump once');
      const [jump] = await arrivals(heard, from, 'jump');
      if (!jump) throw new Error('the center of mass never rose faster than 0.4 a second');
      return 'the center of mass rose faster than 0.4 frame heights a second';
    });

    say('Done, thank you');
    await sleep(2_000);
  } finally {
    context.prompt(null);
    context.setCameraProps(null);
  }

  log(`performance changes: ${moves.join(', ') || 'none'}`);
  const summary = `${STEPS - failures}/${STEPS} steps · ${results.join(' · ')}`;
  if (failures > 0) throw new Error(summary);
  return summary;
}

export const PERSON: Scenario = {
  id: 'person',
  title: 'Somebody in frame',
  verifies:
    'Every trigger kind, the data modes, drops and snapshots, acted out by a person following the ' +
    'prompts on screen.',
  manual: true,
  run: (context) => measure('person', STEPS, (log) => run(context, log), context),
};
