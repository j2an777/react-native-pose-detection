import type { StyleProp, ViewStyle } from 'react-native';

import type {
  AnalysisResolutionPreset,
  DataConfig,
  DelegateRequest,
  Facing,
  FacingRequest,
  OverlayConfig,
  Profile,
  ProfileState,
  ResolutionPreset,
  SmoothingConfig,
  ThermalPolicy,
  CameraState,
} from './camera';
import type { Photo, TakePhotoOptions } from './camera';
import type { PoseFrame } from './frame';
import type { CameraChangeEvent, ErrorEvent, PerformanceEvent, ReadyEvent } from './events';
import type { LogEntry, LogLevelConfig } from './logging';
import type { Trigger, TriggerEvent } from './triggers';

/** Each axis you set is pinned; those left `'auto'` keep adapting. See guides/performance.md. */
export type PoseCameraProps = {
  style?: StyleProp<ViewStyle>;

  profile?: Profile;
  facing?: FacingRequest;
  /**
   * Keeps the light on, rather than firing it at the shutter — there is no flash mode, because the
   * session runs continuously for detection and a strobe would blind it mid-frame. A lens with no
   * flash ignores this; watch `hasTorch` on `onCameraChange` to know whether to offer the control.
   * A lens switch puts the request back, so the light returns when the back camera does.
   */
  torch?: boolean;
  /**
   * Factor, not a step: 1 is the whole sensor, and a phone whose back camera starts wider accepts
   * below 1. Clamped into the bound lens's range, which a switch re-clamps — read `zoom` back from
   * `onCameraChange` rather than assuming the number landed. Prefer `setZoom()` for a pinch: a prop
   * waits for a render, and a zoom that lags the fingers feels broken.
   */
  zoom?: number;
  delegate?: DelegateRequest;
  targetFps?: 'auto' | number;
  resolution?: 'auto' | ResolutionPreset;
  /** What the model sees. Independent of `resolution`, which only sizes the preview. */
  analysisResolution?: 'auto' | AnalysisResolutionPreset;
  thermalPolicy?: ThermalPolicy;
  /**
   * 1 to 5, default 1. Frames and triggers use the primary pose, the largest body found. Above 1,
   * `minConfidence` defaults to 0.3, low enough for a second body to be found at all.
   */
  maxPoses?: number;
  /**
   * How sure the model must be to call something a body, 0.1 to 1. Default 0.6 at `maxPoses: 1`,
   * 0.3 above it. A change rebuilds the landmarker, so keep it in state that settles.
   */
  minConfidence?: number;
  /** `'auto'` (default): off for one pose, which MediaPipe already smooths, on for several. */
  smoothing?: 'auto' | boolean | SmoothingConfig;

  /** Camera on or off. The lowest power state short of unmounting. */
  active?: boolean;
  /**
   * Inference on or off; the preview keeps running. `false` stops inference at once and frees the
   * landmarker after a minute unused, so turning it back on within that minute is instant.
   */
  detection?: boolean;
  overlay?: boolean | OverlayConfig;

  data?: DataConfig;
  triggers?: readonly Trigger[];

  /**
   * Raises the level on top of `setLogLevel()` while this camera is mounted. The level is global,
   * so the raise covers everything that logs meanwhile.
   */
  logLevel?: LogLevelConfig;

  onReady?: (event: ReadyEvent) => void;
  onError?: (event: ErrorEvent) => void;
  onCameraChange?: (event: CameraChangeEvent) => void;
  onPerformanceChange?: (event: PerformanceEvent) => void;
  onTrigger?: (event: TriggerEvent) => void;
  /** Fires when `data.mode` is `'throttled'` or `'live'`. */
  onPose?: (frame: PoseFrame) => void;
  /** Fires when `data.mode` is `'batched'`. */
  onPoseBatch?: (frames: readonly PoseFrame[]) => void;
  /**
   * Frames the native ring buffer dropped since the last delivery. A steady trickle means the frame
   * callback is doing too much work.
   */
  onFramesDropped?: (count: number) => void;
  /** Log batches while this camera is mounted, at whatever level is in force. */
  onLog?: (entries: readonly LogEntry[]) => void;
};

/** Everything but `getState` and `setProfile` returns a promise; await it to see a failure. */
export type PoseCameraRef = {
  /** Resolves once the session is stable again, not when the switch begins. */
  switchCamera(): Promise<void>;
  setFacing(facing: Facing): Promise<void>;

  /** Stops the capture session and parks the landmarker; `resume()` within a minute is instant. */
  pause(): Promise<void>;
  resume(): Promise<void>;
  /** Instant within a minute of `stopDetection()`; after that the landmarker is rebuilt. */
  startDetection(): Promise<void>;
  /** Stops inference at once and frees the landmarker after a minute unused. Preview unaffected. */
  stopDetection(): Promise<void>;
  /** Drawing only; inference continues. */
  setOverlayEnabled(enabled: boolean): Promise<void>;
  /**
   * Applies now rather than at the next render — a torch button should light on the press, not a
   * commit later. A lens with no flash takes the request and stays dark; see `torch`.
   */
  setTorch(on: boolean): Promise<void>;
  /**
   * Applies now rather than at the next render, which is what a pinch needs. The factor is clamped
   * into the bound lens's range; `getState().zoom` says where it landed.
   */
  setZoom(factor: number): Promise<void>;

  /** Applies a profile now, rather than at the next render. See guides/performance.md. */
  setProfile(profile: Profile): void;
  /** Async because it reads the calibration on native's main thread, which no event mirrors. */
  getProfile(): Promise<ProfileState>;
  /** Synchronous: the state the events carry, with `fps` and `limitedBy` read live from native. */
  getState(): CameraState;

  /**
   * The current frame regardless of `data.mode`, or `null` with no pose. Read synchronously, so the
   * promise is already settled when returned. See ADR 0008.
   */
  snapshot(): Promise<PoseFrame | null>;
  /**
   * A still from the running session, written to the cache directory. Detection and the preview
   * keep going. Rejects with `CAPTURE_FAILED` when the device cannot add a capture output
   * alongside the analysis one, which some entry-level cameras cannot.
   */
  takePhoto(options?: TakePhotoOptions): Promise<Photo>;
};
