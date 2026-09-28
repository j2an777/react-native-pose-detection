import { getNativeModule } from './native';
import { logStreamGate } from './native/logStream';
import type { LogEntry, LogListener, LogLevelConfig, Subscription } from './types/logging';
import { assertValidLogLevel } from './validation/logLevel';

// A multiset: with identity dedupe, one remove() would unsubscribe a handler added twice.
const listeners: LogListener[] = [];

/** Batches the module sends itself, only while no camera is mounted to flush them via `onLog`. */
let moduleBatches: { remove(): void } | null = null;

const stream = logStreamGate(
  () => {
    const native = getNativeModule();
    moduleBatches = native.addListener('onLog', (event: { entries: LogEntry[] }) => {
      emitLogEntries(event.entries);
    });
    native.startLogStream();
  },
  () => {
    getNativeModule().stopLogStream();
    moduleBatches?.remove();
    moduleBatches = null;
  },
);

/** Sets the level app-wide, or per category. Throws `PoseConfigError` on an unknown one. */
export function setLogLevel(config: LogLevelConfig): void {
  assertValidLogLevel(config);
  getNativeModule().setLogLevel(config);
}

/** Batches about every 250 ms, camera or not. A function added twice needs two `remove()` calls. */
export function addLogListener(listener: LogListener): Subscription {
  listeners.push(listener);
  const release = stream.hold();

  let removed = false;
  return {
    remove() {
      if (removed) return;
      removed = true;

      const at = listeners.indexOf(listener);
      if (at >= 0) listeners.splice(at, 1);
      release();
    },
  };
}

/** Keeps the native stream running for a camera's `onLog` prop, which the registry never sees. */
export function holdLogStream(): () => void {
  return stream.hold();
}

/** Iterates a copy so unsubscribing during delivery cannot skip the next listener. */
export function emitLogEntries(entries: readonly LogEntry[]): void {
  for (const listener of [...listeners]) listener(entries);
}
