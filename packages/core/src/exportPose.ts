import { getNativeModule } from './native';
import type { OverlayConfig } from './types/camera';
import { assertValidFileOptions } from './validation';

export type ExportOptions = {
  /** The shape `<PoseCamera overlay>` takes. `false` writes an unpainted, size-capped copy. */
  overlay?: boolean | OverlayConfig;
  /** 1 to 5, default 1. Every pose found is painted, but this is a ceiling, not a promise. */
  maxPoses?: number;
  /**
   * How sure the model must be to call something a body, 0.1 to 1. Default 0.5 at `maxPoses: 1`,
   * 0.3 above it.
   */
  minConfidence?: number;
  /**
   * Detections per second of video, not its frame rate. Default 10. Frames in between are painted
   * with the latest pose, and the cost scales roughly linearly with this.
   */
  fps?: number;
  /** Long edge of the output. Default 1920. `0` keeps the source's own size. */
  maxSize?: number;
  /** `'cache'` (default), `'documents'`, or a path or `file://` URI, created if missing. */
  directory?: 'cache' | 'documents' | (string & {});
  /** Without an extension. Defaults to the source's name with `-pose` appended. */
  fileName?: string;
  /** JPEG quality for images, 0.1 to 1. Default 0.9. Ignored for video. */
  quality?: number;
  /** 0 to 1, throttled to about every two percent. */
  onProgress?: (progress: number) => void;
};

export type ExportResult = {
  /** A `file://` URI inside the app's sandbox. */
  readonly uri: string;
  readonly width: number;
  readonly height: number;
  /** 0 for a still image. */
  readonly durationMs: number;
  /** 1 for a still image, the encoded frame count for a video. */
  readonly frameCount: number;
  /** Poses found in a photo, or sampled frames with a pose in a video. 0: nothing was painted. */
  readonly posesFound: number;
};

export type ExportTask = {
  /**
   * `EXPORT_CANCELLED` after `cancel()` (a photo already being painted finishes), `EXPORT_FAILED`
   * if the file cannot be read, painted or written. Either way the partial file is deleted.
   */
  readonly result: Promise<ExportResult>;
  cancel(): void;
};

let nextTaskId = 1;

/**
 * Paints the skeleton into a copy of an image or video with the live camera's renderer. Its own
 * detector runs below the camera's priority, so a live preview keeps its frames.
 */
export function exportPose(uri: string, options?: ExportOptions): ExportTask {
  assertValidFileOptions(options);
  const module = getNativeModule();
  const taskId = nextTaskId;
  nextTaskId += 1;

  const { onProgress, ...rest } = options ?? {};
  // A callback cannot cross to native, so progress comes back as an event keyed by task id.
  const subscription = onProgress
    ? module.addListener('onExportProgress', (event: { taskId: number; progress: number }) => {
        if (event.taskId === taskId) onProgress(event.progress);
      })
    : null;

  const result = module
    .exportPose(uri, rest as Record<string, unknown>, taskId)
    .finally(() => subscription?.remove());

  return {
    result,
    cancel: () => module.cancelExportPose(taskId),
  };
}
