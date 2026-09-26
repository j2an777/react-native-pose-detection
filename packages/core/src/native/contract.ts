import type { ExportResult } from '../exportPose';
import type { LimitedBy } from '../types/camera';
import type { LogLevelConfig } from '../types/logging';
import type { TriggerEvent } from '../types/triggers';

/** Exactly what Android reports. The four-state public status is derived from these two. */
export type NativeCameraPermission = {
  readonly status: 'granted' | 'denied' | 'undetermined';
  readonly canAskAgain: boolean;
};

/** Module-level surface. View props, ref methods and events are declared on the view. */
export type NativePoseModule = {
  setLogLevel(config: LogLevelConfig): void;
  getCameraPermission(): Promise<NativeCameraPermission>;
  detectOnImage(uri: string, options: Record<string, unknown>): Promise<ArrayBuffer>;
  detectOnVideo(
    uri: string,
    options: Record<string, unknown>,
    taskId: number,
  ): Promise<ArrayBuffer>;
  cancelDetectOnVideo(taskId: number): void;
  /** Resolves with an `ExportResult`. Rejects with `EXPORT_CANCELLED` or `EXPORT_FAILED`. */
  exportPose(uri: string, options: Record<string, unknown>, taskId: number): Promise<ExportResult>;
  cancelExportPose(taskId: number): void;
  /** Expo's module event subscription, used for video and export progress. */
  addListener(event: string, listener: (payload: never) => void): { remove(): void };
  /** Prompts when the system still will, and resolves with the outcome either way. */
  requestCameraPermission(): Promise<NativeCameraPermission>;
  /** Called when the first JS listener attaches, so the native ring buffer stays idle until then. */
  startLogStream(): void;
  /** Called when the last listener detaches. */
  stopLogStream(): void;
  /**
   * The frame reads, synchronous and on the JavaScript thread: each reaches a view's ring buffer
   * through the `streamId` prop `<PoseCamera>` gave it, never through the view, whose functions run
   * on native's main queue. An id with no view behind it reads as an empty buffer. See
   * [ADR 0008](../../../../docs/adr/0008-frames-are-drained-not-pushed.md).
   */
  drainFrames(streamId: number): ArrayBuffer;
  /** The current frame on demand, regardless of `data.mode`. Empty when no pose is present. */
  snapshotFrame(streamId: number): ArrayBuffer;
  /** Redeems a trigger's ticket. An unknown or spent ticket returns an empty buffer. */
  takeTriggerSnapshot(streamId: number, snapshotId: number): ArrayBuffer;
  /** The measured rate and the reason for the current one, read without a hop to main. */
  readLiveState(streamId: number): { readonly fps?: number; readonly limitedBy?: LimitedBy };
};

/**
 * A frame cannot ride an event, so native holds it and sends a claim ticket that
 * `<PoseCamera>` redeems. See
 * [ADR 0009](../../../../docs/adr/0009-trigger-snapshots-are-claimed.md).
 */
export type NativeTriggerEvent = Omit<TriggerEvent, 'snapshot'> & {
  readonly snapshotId?: number;
};

/**
 * Imperative surface behind `<PoseCamera>`'s ref. The frame reads are not here: they are module
 * functions keyed by `streamId`, see `NativePoseModule.drainFrames`.
 */
export type NativePoseCameraView = {
  switchCamera(): Promise<void>;
  setFacing(facing: 'front' | 'back'): Promise<void>;
  pause(): Promise<void>;
  resume(): Promise<void>;
  startDetection(): Promise<void>;
  stopDetection(): Promise<void>;
  setOverlayEnabled(enabled: boolean): Promise<void>;
  getState(): Promise<Record<string, unknown>>;
  getProfile(): Promise<Record<string, unknown>>;
  setProfile(profile: string): Promise<void>;
};
