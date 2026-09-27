// Build-time output, the `plugin` category in docs/logging.md, never the runtime channel.

const CLEAR_LINE = '\u001b[K';

function isInteractive(): boolean {
  return process.stdout.isTTY === true && !process.env['CI'];
}

export function line(message: string): void {
  process.stdout.write(`› ${message}\n`);
}

export function warn(message: string): void {
  process.stderr.write(`› warning: ${message}\n`);
}

/** Silent off a terminal, where every carriage return would become a log line of its own. */
export function progress(message: string): void {
  if (!isInteractive()) return;
  process.stdout.write(`\r› ${message}${CLEAR_LINE}`);
}

export function clearProgress(): void {
  if (!isInteractive()) return;
  process.stdout.write(`\r${CLEAR_LINE}`);
}
