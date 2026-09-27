import { PoseConfigError } from './errors';
import type { ValidationIssue } from './errors';
import { getNativeModule } from './native';
import { logStreamGate } from './native/logStream';
import { LOG_CATEGORIES, LOG_LEVELS } from './types/logging';
import type {
  LogEntry,
  LogListener,
  LogLevel,
  LogLevelConfig,
  Subscription,
} from './types/logging';

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

function isLogLevel(value: unknown): value is LogLevel {
  return typeof value === 'string' && (LOG_LEVELS as readonly string[]).includes(value);
}

function validate(config: LogLevelConfig): ValidationIssue[] {
  if (isLogLevel(config)) return [];

  if (typeof config !== 'object' || config === null || Array.isArray(config)) {
    return [
      { path: 'logLevel', message: `must be a level or a map of categories to levels` },
      { path: 'logLevel', message: `levels are: ${LOG_LEVELS.join(', ')}` },
    ];
  }

  const issues: ValidationIssue[] = [];
  for (const [category, level] of Object.entries(config)) {
    if (!(LOG_CATEGORIES as readonly string[]).includes(category)) {
      issues.push({
        path: `logLevel.${category}`,
        message: `unknown category, expected one of: ${LOG_CATEGORIES.join(', ')}`,
      });
      continue;
    }
    if (!isLogLevel(level)) {
      issues.push({
        path: `logLevel.${category}`,
        message: `must be one of: ${LOG_LEVELS.join(', ')}`,
      });
    }
  }
  return issues;
}

/** Sets the level app-wide, or per category. Throws `PoseConfigError` on an unknown one. */
export function setLogLevel(config: LogLevelConfig): void {
  const issues = validate(config);
  if (issues.length > 0) throw new PoseConfigError(issues);

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
