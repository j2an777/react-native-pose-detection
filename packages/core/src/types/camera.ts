import type { AngleJointName, JointName } from './joints';

export type ModelVariant = 'lite' | 'full' | 'heavy';

/** What actually ran. `DelegateRequest` is what you asked for. */
export type Delegate = 'GPU' | 'CPU';
export type DelegateRequest = 'auto' | 'gpu' | 'cpu';

export type DeviceTier = 'high' | 'medium' | 'low';

export type Facing = 'front' | 'back';
export type FacingRequest = 'auto' | Facing;

export type ResolutionPreset = '480p' | '720p' | '1080p';
export type AnalysisResolutionPreset = '360p' | '480p' | '720p';

export type Resolution = { readonly width: number; readonly height: number };

export type ThermalPolicy = 'adaptive' | 'critical-only' | 'off';

export type Profile = 'auto' | 'efficient' | 'balanced' | 'quality' | 'unrestricted';

/**
 * Why the rate is what it is: `camera` rate, measured `device` budget, `target` or `profile`
 * ceiling, `thermal`, `lowPower`, `idle` (nobody in frame), `paused` (camera or detection off).
 */
export type LimitedBy =
  | 'camera'
  | 'device'
  | 'target'
  | 'profile'
  | 'thermal'
  | 'lowPower'
  | 'idle'
  | 'paused';

/** The heat the governor acts on: it rises at once and cools only once the lower state has held. */
export type ThermalState = 'nominal' | 'fair' | 'serious' | 'critical';

export type ProfileState = {
  readonly profile: Profile;
  readonly phase: 'calibrating' | 'settled' | 'cached';
  readonly source: 'measured' | 'static' | 'cache';
  readonly tier: DeviceTier;
  readonly resolved: {
    readonly delegate: Delegate;
    readonly targetFps: number;
    readonly preview: ResolutionPreset;
    readonly analysis: AnalysisResolutionPreset;
  };
  readonly p50InferenceMs: number;
  /**
   * Completed inferences over the last second, 0 once results stop. Below `resolved.targetFps`
   * exactly when the device cannot hold that rate.
   */
  readonly measuredFps: number;
  /** Why `resolved.targetFps` is what it is. */
  readonly limitedBy: LimitedBy;
  /** What the camera delivers once pinned: the ceiling every rate sits under. Normally 30. */
  readonly cameraFps: number;
  readonly thermalState: ThermalState;
  /** Low Power Mode on iOS, Battery Saver on Android. */
  readonly lowPower: boolean;
};

export type CameraState = {
  readonly facing: Facing;
  readonly active: boolean;
  readonly detecting: boolean;
  /** Completed inferences over the last second, read live on each call; 0 once results stop. */
  readonly fps: number;
  readonly delegate: Delegate;
  readonly deviceTier: DeviceTier;
  /** Why the rate is what it is, read live like `fps`. */
  readonly limitedBy: LimitedBy;
  /** False on every front camera and on back cameras with no flash unit. */
  readonly hasTorch: boolean;
  /** Lit right now. A lens without a flash reads `false` however `torch` was set. */
  readonly torch: boolean;
  /** True while a recording is running. Stills are refused meanwhile; see `startRecording()`. */
  readonly recording: boolean;
  /** Applied right now. 1 is the whole sensor, not "wide". */
  readonly zoom: number;
  /** The bound lens's own range. Both 1 means this camera does not zoom. */
  readonly minZoom: number;
  readonly maxZoom: number;
};

/**
 * One Euro filter, MediaPipe's constants by default: `minCutoff` 0.05, `beta` 80. Lower
 * `minCutoff` smooths a still body harder; higher `beta` follows fast movement sooner.
 */
export type SmoothingConfig = {
  minCutoff?: number;
  beta?: number;
};

export type AngleOverlay = {
  joint: AngleJointName;
  /** Draw the degree value next to the arc. Default true. */
  label?: boolean;
  /** Arc radius in points. Default 40. */
  radius?: number;
  /** Defaults to the overlay color. */
  color?: string;
  /** Decimal places on the label, 0 to 3; larger values are capped. Default 0. */
  decimals?: number;
  /** Hide the arc when the vertex is tracked below this. Default 0.5. */
  minVisibility?: number;
};

export type OverlayConfig = {
  landmarks?: boolean;
  connections?: boolean;
  color?: string;
  /** Points. Default 3. */
  lineWidth?: number;
  /** Points. Default 4. */
  pointRadius?: number;
  /** Hide joints tracked below this. Default 0.5. */
  minVisibility?: number;
  /** Draw a subset of the skeleton. Connections with an excluded endpoint are skipped. */
  only?: readonly JointName[];
  angles?: readonly AngleOverlay[];
};

/** How often frames cross to JavaScript. Triggers fire regardless, even at `'off'`. */
export type DataMode = 'off' | 'throttled' | 'batched' | 'live';

export type DataConfig = {
  /** Default `'off'`, which is zero crossings per second. */
  mode?: DataMode;
  /** `'throttled'` only. Default 100. */
  throttleMs?: number;
  /** `'batched'` only. Default 500. */
  flushMs?: number;
  landmarks?: boolean;
  worldLandmarks?: boolean;
  /**
   * `true` computes all 12, a list only those. The angles triggers and `overlay.angles` reference
   * are added either way.
   */
  angles?: boolean | readonly AngleJointName[];
  /**
   * Narrows the landmark buffer to exactly these joints, in this order, listed on
   * `PoseFrame.selection`. Angles are computed from the full set first, so they never widen it.
   */
  select?: readonly JointName[];
};

/** What `takePhoto()` accepts. Everything optional; the defaults suit a framing shot. */
export type TakePhotoOptions = {
  /** JPEG quality, 0 to 1. Default 0.95. */
  quality?: number;
  /**
   * Front-camera photos match the preview by default, which is what the subject framed. `false`
   * writes the un-mirrored frame instead, the way the rest of the world saw it. No effect on the
   * back camera.
   */
  mirrorFront?: boolean;
};

/** A still written to the app's cache directory. Nothing prunes it; move or delete what you keep. */
export type Photo = {
  /** `file://` URI. */
  uri: string;
  width: number;
  height: number;
  /** Bytes on disk. */
  size: number;
  /** True when the pixels were mirrored to match a front-camera preview. */
  mirrored: boolean;
};

/** What `startRecording()` accepts. */
export type RecordingOptions = {
  /**
   * Default `false`, which needs no microphone permission at all. `true` rejects with
   * `MICROPHONE_DENIED` unless the permission is already granted — this package does not prompt,
   * because the prompt belongs to the screen that asked for sound.
   */
  audio?: boolean;
};

/** A recording written to the app's cache directory. Nothing prunes it; move or delete what you keep. */
export type Video = {
  /** `file://` URI. */
  uri: string;
  /** Read back from the written file, not timed in native: the encoder decides where it ends. */
  durationMs: number;
  /** Bytes on disk. */
  size: number;
  hasAudio: boolean;
};
