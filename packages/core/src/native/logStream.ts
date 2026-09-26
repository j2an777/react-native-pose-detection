/**
 * Runs the native log stream while anything wants it, and only then: the stream is what batches
 * entries to JavaScript, and while it runs every enabled entry is buffered for a flush.
 *
 * Two kinds of holder share it. A listener in the `addLogListener()` registry is one; a camera with
 * an `onLog` prop is the other, and the registry never sees it. When only the registry started the
 * stream, a camera given `onLog` and no listener received nothing.
 */
export function logStreamGate(start: () => void, stop: () => void) {
  let holders = 0;
  return {
    /** Starts the stream if nothing held it. The returned release is safe to call twice. */
    hold(): () => void {
      holders += 1;
      if (holders === 1) start();
      let released = false;
      return () => {
        if (released) return;
        released = true;
        holders -= 1;
        if (holders === 0) stop();
      };
    },
  };
}
