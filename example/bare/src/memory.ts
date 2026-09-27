/** JS heap only (camera and model memory is native), or null where Hermes does not expose it. */
export function jsHeapBytes(): number | null {
  // A cast of `globalThis`: only one of the two apps' base tsconfigs declares `performance`.
  const root = globalThis as { performance?: { memory?: { usedJSHeapSize?: unknown } } };
  const used = root.performance?.memory?.usedJSHeapSize;
  return typeof used === 'number' ? used : null;
}

export function formatBytes(bytes: number | null): string {
  if (bytes === null) return 'n/a';
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}
