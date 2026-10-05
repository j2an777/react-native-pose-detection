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
  /** Rejects with `EXPORT_CANCELLED` or `EXPORT_FAILED`. */
  exportPose(uri: string, options: Record<string, unknown>, taskId: number): Promise<ExportResult>;
  cancelExportPose(taskId: number): void;
  /** Expo's module events: video and export progress, and log batches. */
  addListener(event: string, listener: (payload: never) => void): { remove(): void };
  /** Prompts when the system still will, and resolves with the outcome either way. */
  requestCameraPermission(): Promise<NativeCameraPermission>;
  /** Called for the first holder, so native buffers no entries until something listens. */
  startLogStream(): void;
  /** Called when the last holder lets go. */
  stopLogStream(): void;
  /**
   * Synchronous on the JavaScript thread, by the `streamId` prop rather than the view, whose
   * functions run on native's main queue. An unknown id reads as empty. See ADR 0010.
   */
  drainFrames(streamId: number): ArrayBuffer;
  /** The current frame on demand, regardless of `data.mode`. Empty when no pose is present. */
  snapshotFrame(streamId: number): ArrayBuffer;
  /** Redeems a trigger's ticket. An unknown or spent ticket returns an empty buffer. */
  takeTriggerSnapshot(streamId: number, snapshotId: number): ArrayBuffer;
  /** The measured rate and the reason for the current one, read without a hop to main. */
  readLiveState(streamId: number): { readonly fps?: number; readonly limitedBy?: LimitedBy };
};

/** A frame cannot ride an event, so native sends a ticket `<PoseCamera>` redeems. See ADR 0009. */
export type NativeTriggerEvent = Omit<TriggerEvent, 'snapshot'> & {
  readonly snapshotId?: number;
};

/** The view functions behind `<PoseCamera>`'s ref. Frame reads are module functions instead. */
export type NativePoseCameraView = {
  switchCamera(): Promise<void>;
  setFacing(facing: 'front' | 'back'): Promise<void>;
  pause(): Promise<void>;
  resume(): Promise<void>;
  startDetection(): Promise<void>;
  stopDetection(): Promise<void>;
  setOverlayEnabled(enabled: boolean): Promise<void>;
  setTorch(on: boolean): Promise<void>;
  getState(): Promise<Record<string, unknown>>;
  getProfile(): Promise<Record<string, unknown>>;
  setProfile(profile: string): Promise<void>;
  takePhoto(options: Record<string, unknown>): Promise<Record<string, unknown>>;
};
