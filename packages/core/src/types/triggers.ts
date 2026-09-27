import type { PoseFrame } from './frame';
import type { AngleJointName, JointName } from './joints';

/** Degrees, 0 to 180. `below` and `above` are strict, `between` is inclusive. */
export type AngleCondition = {
  angle: AngleJointName;
  below?: number;
  above?: number;
  /** `[min, max]`, inclusive. `min` must be less than `max`. */
  between?: readonly [number, number];
};

/** Normalized 0 to 1, origin top-left. A `JointName` bound compares against that joint. */
export type LandmarkXCondition = {
  landmarkX: JointName;
  below?: number | JointName;
  above?: number | JointName;
};

export type LandmarkYCondition = {
  landmarkY: JointName;
  below?: number | JointName;
  above?: number | JointName;
};

/** Normalized units per second. */
export type VelocityXCondition = {
  velocityX: 'centerOfMass' | JointName;
  below?: number;
  above?: number;
};

export type VelocityYCondition = {
  velocityY: 'centerOfMass' | JointName;
  below?: number;
  above?: number;
};

/** Gate the rest of a condition on tracking quality before trusting a coordinate. */
export type VisibilityCondition = {
  visibility: JointName;
  above: number;
};

export type AllCondition = { all: readonly Condition[] };
export type AnyCondition = { any: readonly Condition[] };

/** Exactly one key per condition. `all` and `any` nest up to 8 levels deep. */
export type Condition =
  | AngleCondition
  | LandmarkXCondition
  | LandmarkYCondition
  | VelocityXCondition
  | VelocityYCondition
  | VisibilityCondition
  | AllCondition
  | AnyCondition;

export type TriggerEmit = 'enter' | 'exit' | 'cycle' | 'while';

export type Trigger = {
  /** Unique within one camera. Comes back on every `TriggerEvent`. */
  id: string;
  enter: Condition;
  /** Required for `emit: 'cycle'` and `emit: 'exit'`. */
  exit?: Condition;
  emit: TriggerEmit;
  /** Suppress re-entry for this long after a fire. Default 0. */
  debounceMs?: number;
  /** How long the condition must hold, unbroken, before either transition counts. Default 0. */
  minDurationMs?: number;
  /** Attach the `PoseFrame` from the moment the trigger fired. */
  snapshot?: boolean;
  /** `emit: 'while'` only. Default 250. */
  throttleMs?: number;
};

export type TriggerEvent = {
  readonly id: string;
  /** `emit: 'while'` fires as repeated `'enter'`. */
  readonly phase: 'enter' | 'exit' | 'cycle';
  /** Completed cycles since mount. Survives a camera switch, resets on unmount. */
  readonly count: number;
  /** Same monotonic clock as `PoseFrame.timestamp`. */
  readonly timestamp: number;
  /** Enter to exit, on `'cycle'` only. */
  readonly durationMs?: number;
  readonly snapshot?: PoseFrame;
};
