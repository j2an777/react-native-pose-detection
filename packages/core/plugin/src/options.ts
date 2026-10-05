import { DEFAULT_CACHE_DIR, ensureModel } from './download';
import type { ModelEntry } from './manifest';
import { DEFAULT_MODEL, resolveModel } from './manifest';

export type PoseDetectionPluginOptions = {
  /** Exactly one variant is installed. Default `'full'`. */
  model?: string;
  /** `NSCameraUsageDescription`. */
  cameraPermissionText?: string;
  /**
   * `NSMicrophoneUsageDescription` and Android's `RECORD_AUDIO`. Opt-in: set this only if the app
   * calls `startRecording({ audio: true })`. Declaring a microphone permission an app never uses is
   * a review question nobody wants to answer.
   */
  microphonePermissionText?: string;
  cacheDir?: string;
  /** Never touch the network. For CI where the model is restored from a cache or vendored. */
  skipDownload?: boolean;
};

export type ResolvedOptions = {
  model: ModelEntry;
  cameraPermissionText: string;
  /** Whether the app author asked for that text, or it is our fallback. Decides who wins. */
  cameraPermissionTextExplicit: boolean;
  /** Null when the app did not ask for audio recording, which is the default. */
  microphonePermissionText: string | null;
  cacheDir: string;
  skipDownload: boolean;
};

const DEFAULT_PERMISSION_TEXT = 'This app uses the camera to analyse your movement.';

export function resolveOptions(options: PoseDetectionPluginOptions | undefined): ResolvedOptions {
  const model = resolveModel(options?.model ?? DEFAULT_MODEL);

  return {
    model,
    cameraPermissionText: options?.cameraPermissionText ?? DEFAULT_PERMISSION_TEXT,
    cameraPermissionTextExplicit: typeof options?.cameraPermissionText === 'string',
    microphonePermissionText:
      typeof options?.microphonePermissionText === 'string'
        ? options.microphonePermissionText
        : null,
    cacheDir: options?.cacheDir ?? DEFAULT_CACHE_DIR,
    skipDownload: options?.skipDownload === true,
  };
}

// The Android and iOS mods both need the model, and prebuild runs them in the same process.
// Without this the file would be verified, and on a cold cache downloaded, twice.
let inFlight: { key: string; promise: Promise<string | null> } | null = null;

export function ensureModelOnce(options: ResolvedOptions): Promise<string | null> {
  const key = `${options.model.variant}|${options.cacheDir}|${options.skipDownload}`;

  if (!inFlight || inFlight.key !== key) {
    inFlight = {
      key,
      promise: ensureModel(options.model.variant, {
        cacheDir: options.cacheDir,
        skipDownload: options.skipDownload,
      }),
    };
  }
  return inFlight.promise;
}
