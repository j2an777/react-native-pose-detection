/**
 * How long a ref call waits for the native view to exist. The gap it covers is a frame or two:
 * React has committed the component and handed out its ref, but Fabric mounts the native view on
 * the UI thread a moment later, and a view function called in between finds no view.
 */
export const MOUNT_WAIT_MS = 1_000;

const RETRY_MS = 16;

/** Expo's error for a view function whose view is not mounted, on both platforms. */
export function isViewNotMounted(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error);
  return /Unable to find the .*view with tag/.test(message);
}

/**
 * Calls a native view function, retrying while the view has not been mounted yet rather than
 * rejecting a call that was only early. Any other failure rejects at once, and so does this one
 * once `MOUNT_WAIT_MS` has passed. Resolves to `undefined` when the component has unmounted.
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
