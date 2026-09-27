/** How long a ref call waits for Fabric to mount a view whose ref React has already handed out. */
export const MOUNT_WAIT_MS = 1_000;

const RETRY_MS = 16;

/** Expo's error for a view function whose view is not mounted, on both platforms. */
export function isViewNotMounted(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error);
  return /Unable to find the .*view with tag/.test(message);
}

/**
 * Retries a call that was only early, until `MOUNT_WAIT_MS`; any other failure rejects at once.
 * Resolves to `undefined` once the component has unmounted.
 */
export async function callView<View, Result>(
  current: () => View | null,
  invoke: (view: View) => Promise<Result>,
  sleep: (ms: number) => Promise<void> = (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
  now: () => number = Date.now,
): Promise<Result | undefined> {
  const started = now();
  for (;;) {
    const view = current();
    if (!view) return undefined;
    try {
      return await invoke(view);
    } catch (error) {
      if (!isViewNotMounted(error) || now() - started >= MOUNT_WAIT_MS) throw error;
      await sleep(RETRY_MS);
    }
  }
}
