import type { SmoothingConfig } from './types/camera';

/**
 * `'auto'` is off for one pose, because MediaPipe already runs the same One Euro filter on a single
 * tracked body in LIVE_STREAM and VIDEO mode, and on for several, where it runs none. Resolved in
 * JavaScript, where `maxPoses` is, so native only ever receives an answer.
 */
export function resolveSmoothing<T extends boolean | SmoothingConfig>(
  smoothing: 'auto' | T | undefined,
  maxPoses: number | undefined,
): T | boolean {
  if (smoothing === undefined || smoothing === 'auto') return (maxPoses ?? 1) > 1;
  return smoothing;
}
