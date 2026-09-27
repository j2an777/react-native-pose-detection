export type LogLevel = 'off' | 'error' | 'warn' | 'info' | 'debug' | 'trace';

/** Runtime categories. The config plugin's `plugin` output is build-time and never arrives here. */
export type LogCategory = 'camera' | 'detector' | 'engine' | 'triggers' | 'calibration' | 'overlay';

export const LOG_LEVELS: readonly LogLevel[] = [
  'off',
  'error',
  'warn',
  'info',
  'debug',
  'trace',
] as const;

export const LOG_CATEGORIES: readonly LogCategory[] = [
  'camera',
  'detector',
  'engine',
  'triggers',
  'calibration',
  'overlay',
] as const;

/** One level for everything, or per category so `trace` on one does not drown you in the rest. */
export type LogLevelConfig = LogLevel | Readonly<Partial<Record<LogCategory, LogLevel>>>;

export type LogEntry = {
  readonly level: Exclude<LogLevel, 'off'>;
  readonly category: LogCategory;
  readonly message: string;
  /** The monotonic clock `PoseFrame.timestamp` uses, so a line maps to the frame that caused it. */
  readonly timestamp: number;
  readonly data?: Readonly<Record<string, number | string | boolean>>;
};

/**
 * Receives a batch about every 250 ms. After the native buffer overflowed, the batch opens with a
 * `warn` entry carrying `data.droppedCount`.
 */
export type LogListener = (entries: readonly LogEntry[]) => void;

export type Subscription = {
  remove(): void;
};
