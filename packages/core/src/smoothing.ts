import type { SmoothingConfig } from './types/camera';

/** Native never sees `'auto'`: it is resolved here, where `maxPoses` is. */
export function resolveSmoothing<T extends boolean | SmoothingConfig>(
  smoothing: 'auto' | T | undefined,
  maxPoses: number | undefined,
): T | boolean {
  if (smoothing === undefined || smoothing === 'auto') return (maxPoses ?? 1) > 1;
  return smoothing;
}
