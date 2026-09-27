/** Runs the native log stream, which buffers every enabled entry, only while something holds it. */
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
