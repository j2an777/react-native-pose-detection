import type { AngleJointName, JointName } from './joints';
import { LANDMARK_COUNT } from './joints';

/** Floats per landmark: `[x, y, z, visibility]`. */
export const LANDMARK_STRIDE = 4;

/** Offsets within one landmark's stride. Native writes in this order. */
export const LANDMARK_OFFSET = {
  x: 0,
  y: 1,
  z: 2,
  visibility: 3,
} as const;

/** 33 x 4. What a frame carries when no `data.select` narrows it. */
export const FULL_FRAME_FLOAT_COUNT = LANDMARK_COUNT * LANDMARK_STRIDE;

/** 528 bytes, against roughly 3 KB for the same landmarks as JSON objects. */
export const FULL_FRAME_BYTE_LENGTH = FULL_FRAME_FLOAT_COUNT * Float32Array.BYTES_PER_ELEMENT;

export type Landmark = {
  /** Normalized 0 to 1 across the analysis frame, origin top-left. Front camera is un-mirrored. */
  readonly x: number;
  readonly y: number;
  /** Depth relative to the hip midpoint, in roughly the same scale as `x`. Noisier than x and y. */
  readonly z: number;
  /** 0 to 1. Below about 0.5 the point is a guess. */
  readonly visibility: number;
};

/** The target of the allocation-free reader. Same fields as `Landmark`, writable. */
export type MutableLandmark = {
  -readonly [K in keyof Landmark]: Landmark[K];
};

export type Vec2 = { readonly x: number; readonly y: number };

export type PoseFrame = {
  /**
   * Flat `[x, y, z, visibility, ...]`, read with `landmark()`. A view into the drained buffer, so
   * `.slice()` anything kept past the callback. Empty when `data.landmarks` is false.
   */
  readonly landmarks: Float32Array;

  /** Set by `data.select`, in buffer order. The same frozen instance across frames. */
  readonly selection?: readonly JointName[];

  /** Metric 3D in meters, origin at the hip midpoint. Same stride and same `selection` as above. */
  readonly worldLandmarks?: Float32Array;

  /** Degrees, 0 to 180, only for referenced joints. `NaN` when a limb segment has zero length. */
  readonly angles?: Readonly<Partial<Record<AngleJointName, number>>>;

  /** Visibility-weighted hips 0.5, ankles 0.3, knees 0.2, normalized. `NaN` with none visible. */
  readonly centerOfMass: Vec2;

  /** Center-of-mass movement in normalized units per second. `NaN` on a pose's first frame. */
  readonly velocity: Vec2;

  /** Shoulder midpoint to ankle midpoint, normalized. Divide by it for scale-free thresholds. */
  readonly bodySpan: number;

  /** Monotonic ms, as `LogEntry.timestamp`. From `detectOnVideo`, the position in the video. */
  readonly timestamp: number;

  /** Dispatch to result for this frame, in ms. 0 from `detectOnImage` and `detectOnVideo`. */
  readonly processingMs: number;
};
