import type {
  Delegate,
  DelegateRequest,
  DeviceTier,
  Facing,
  LimitedBy,
  ModelVariant,
  Resolution,
  ThermalState,
} from './camera';

const CODES = [
  'PERMISSION_DENIED',
  'MODEL_NOT_FOUND',
  'MODEL_LOAD_FAILED',
  'CAMERA_UNAVAILABLE',
  'CAMERA_START_FAILED',
  'DETECTOR_INIT_FAILED',
  'INVALID_CONFIG',
  'IMAGE_DECODE_FAILED',
  'VIDEO_DECODE_FAILED',
  'CAMERA_SWITCH_FAILED',
  'GPU_UNAVAILABLE',
  'DETECTION_FAILED',
  'EXPORT_FAILED',
  'EXPORT_CANCELLED',
  'CAPTURE_FAILED',
] as const;

/** The complete set: native sends nothing outside it, so a `switch` on it can be exhaustive. */
export type ErrorCode = (typeof CODES)[number];

export const ERROR_CODES: readonly ErrorCode[] = CODES;

export type ReadyEvent = {
  readonly model: ModelVariant;
  readonly delegate: Delegate;
  readonly delegateRequested: DelegateRequest;
  readonly targetFps: number;
  /** Why `targetFps` is what it is. */
  readonly limitedBy: LimitedBy;
  readonly deviceTier: DeviceTier;
  readonly resolution: Resolution;
  readonly analysisResolution: Resolution;
  readonly facing: Facing;
};

export type ErrorEvent = {
  readonly code: ErrorCode;
  readonly message: string;
  /** `false` means the pipeline recovered and is still running. Only `true` stops the camera. */
  readonly fatal: boolean;
};

export type CameraChangeEvent = {
  readonly facing: Facing;
};

export type PerformanceEvent = {
  /**
   * `delegate`: Android's `auto` moved from the CPU landmarker it starts on to the GPU one.
   * `gpu_fallback`: the GPU kept failing. `load` and `headroom` are reserved, not sent today.
   */
  readonly reason:
    | 'calibration'
    | 'thermal'
    | 'lowPower'
    | 'idle'
    | 'delegate'
    | 'load'
    | 'headroom'
    | 'gpu_fallback';
  readonly delegate: Delegate;
  readonly targetFps: number;
  /** Why `targetFps` is what it is. */
  readonly limitedBy: LimitedBy;
  readonly analysisResolution: Resolution;
  readonly actualFps: number;
  /** Reported on every change, whatever `thermalPolicy` lets the rate do about it. */
  readonly thermalState: ThermalState;
  /** Battery Saver or Low Power Mode. */
  readonly lowPower: boolean;
};
