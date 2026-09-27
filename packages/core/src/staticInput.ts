import { decodeFrames } from './frames/decodeFrames';
import { getNativeModule } from './native';
import type { AngleJointName, JointName } from './types/joints';
import { ANGLE_JOINT_NAMES } from './types/joints';
import type { PoseFrame } from './types/frame';
import { resolveAngleJoints } from './frames/wire';
import { resolveSmoothing } from './smoothing';
import { assertValidFileOptions } from './validation';

export type StaticOptions = {
  /** 1 to 5. Default 1. The subject, the largest body, is always the first frame. */
  maxPoses?: number;
  /**
   * How sure the model must be to call something a body, 0.1 to 1. Default 0.5 at `maxPoses: 1`,
   * 0.3 above it.
   */
  minConfidence?: number;
  /** `true` computes all twelve, a list only those. Default `true`, unlike the live camera. */
  angles?: boolean | readonly AngleJointName[];
  worldLandmarks?: boolean;
  /** Narrows the landmark buffer, exactly as `data.select` does. */
  select?: readonly JointName[];
};

export type VideoOptions = StaticOptions & {
  /** Samples per second, not the video's own frame rate. Default 10. */
  fps?: number;
  startMs?: number;
  endMs?: number;
  /** `'auto'` (default): off for one pose, which MediaPipe already smooths, on for several. */
  smoothing?: 'auto' | boolean;
  /** 0 to 1. Never receives frames. */
  onProgress?: (progress: number) => void;
};

export type VideoTask = {
  /**
   * Resolves with everything decoded, including after `cancel()`. Rejects with
   * `VIDEO_DECODE_FAILED`, `MODEL_NOT_FOUND` or `DETECTION_FAILED`.
   */
  readonly frames: Promise<PoseFrame[]>;
  /** Stops sampling. `frames` then resolves with what was decoded up to that point. */
  cancel(): void;
};

function angleJointsFor(angles: StaticOptions['angles']): readonly AngleJointName[] {
  if (angles === false) return [];
  if (angles === undefined || angles === true) return ANGLE_JOINT_NAMES;
  return resolveAngleJoints(new Set(angles));
}

function nativeOptions(
  options: StaticOptions | VideoOptions | undefined,
  angleJoints: readonly AngleJointName[],
): Record<string, unknown> {
  // onProgress is a JavaScript callback and cannot cross. Progress arrives as an event instead.
  const rest = { ...((options ?? {}) as VideoOptions) };
  delete rest.onProgress;
  const smoothing = resolveSmoothing(rest.smoothing, rest.maxPoses);
  return { ...rest, smoothing, angles: angleJoints.length > 0, angleJoints: [...angleJoints] };
}

function decode(
  buffer: ArrayBuffer,
  angleJoints: readonly AngleJointName[],
  select: readonly JointName[] | undefined,
): PoseFrame[] {
  const { frames, error } = decodeFrames(buffer, {
    angleJoints,
    ...(select && select.length > 0 ? { selection: select } : {}),
  });
  if (error) throw new Error(error);
  return frames;
}

/**
 * One `PoseFrame` per pose found, largest body first, EXIF orientation applied. Rejects with
 * `IMAGE_DECODE_FAILED`, `MODEL_NOT_FOUND` or `DETECTION_FAILED`.
 */
export async function detectOnImage(uri: string, options?: StaticOptions): Promise<PoseFrame[]> {
  assertValidFileOptions(options);
  const angleJoints = angleJointsFor(options?.angles);
  const buffer = await getNativeModule().detectOnImage(uri, nativeOptions(options, angleJoints));
  return decode(buffer, angleJoints, options?.select);
}

let nextTaskId = 1;

/**
 * One frame per sample with a body in it, the largest body's, stamped with its position in the
 * video. Cancelling resolves with the frames so far, since those are real.
 */
export function detectOnVideo(uri: string, options?: VideoOptions): VideoTask {
  assertValidFileOptions(options);
  const angleJoints = angleJointsFor(options?.angles);
  const module = getNativeModule();
  const taskId = nextTaskId;
  nextTaskId += 1;

  const { onProgress } = options ?? {};
  const subscription = onProgress
    ? module.addListener('onVideoProgress', (event: { taskId: number; progress: number }) => {
        if (event.taskId === taskId) onProgress(event.progress);
      })
    : null;

  const frames = module
    .detectOnVideo(uri, nativeOptions(options, angleJoints), taskId)
    .then((buffer) => decode(buffer, angleJoints, options?.select))
    .finally(() => subscription?.remove());

  return {
    frames,
    cancel: () => module.cancelDetectOnVideo(taskId),
  };
}
