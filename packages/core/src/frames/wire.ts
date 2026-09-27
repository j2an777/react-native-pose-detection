import type { AngleJointName } from '../types/joints';
import { ANGLE_JOINT_NAMES } from '../types/joints';

/**
 * `drainFrames()` buffer: Float64 header, Float64 timestamp and processingMs per frame (Float32
 * loses whole milliseconds after 4.6 hours up), then the Float32 body. See ADR 0008.
 */
export const HEADER_FLOAT64S = 6;

export const HEADER_INDEX = {
  frameCount: 0,
  droppedCount: 1,
  floatsPerFrame: 2,
  jointCount: 3,
  angleCount: 4,
  flags: 5,
} as const;

export const FRAME_META_FLOAT64S = 2;

export const WIRE_FLAG_WORLD_LANDMARKS = 1 << 0;
export const WIRE_FLAG_ANGLES = 1 << 1;

/** com.x, com.y, velocity.x, velocity.y, bodySpan. */
export const SCALARS_PER_FRAME = 5;

export function expectedByteLength(frameCount: number, floatsPerFrame: number): number {
  return (
    (HEADER_FLOAT64S + frameCount * FRAME_META_FLOAT64S) * Float64Array.BYTES_PER_ELEMENT +
    frameCount * floatsPerFrame * Float32Array.BYTES_PER_ELEMENT
  );
}

/** Angles a frame carries, in `ANGLE_JOINT_NAMES` order. Native takes this list as given. */
export function resolveAngleJoints(referenced: ReadonlySet<string>): AngleJointName[] {
  return ANGLE_JOINT_NAMES.filter((joint) => referenced.has(joint));
}
