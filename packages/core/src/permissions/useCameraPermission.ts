import * as React from 'react';

import { getCameraPermission, requestCameraPermission } from './permissions';
import type { CameraPermission } from './permissions';

export type UseCameraPermission = CameraPermission & {
  /** True while the first read, or a prompt, is in flight. */
  readonly pending: boolean;
  /** Prompt again. Returns the outcome, and is also written to the hook's state. */
  readonly request: () => Promise<CameraPermission>;
  /**
   * Set when reading or asking fails, which means an incomplete install such as Expo Go. `status`
   * then stays `undetermined`, so `granted` stays false.
   */
  readonly error?: Error;
};

const UNDETERMINED: CameraPermission = {
  status: 'undetermined',
  granted: false,
  canAskAgain: true,
};

/** The camera permission as React state, asked for on mount unless `ask` is `false`. */
export function useCameraPermission(options?: { ask?: boolean }): UseCameraPermission {
  const ask = options?.ask ?? true;

  const [state, setState] = React.useState<CameraPermission>(UNDETERMINED);
  const [pending, setPending] = React.useState(true);
  const [error, setError] = React.useState<Error | undefined>(undefined);

  const mounted = React.useRef(true);
  // Shared, not restarted: the system rejects a second prompt, and StrictMode runs effects twice.
  const inFlight = React.useRef<Promise<CameraPermission> | null>(null);

  React.useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  const run = React.useCallback(
    (read: () => Promise<CameraPermission>): Promise<CameraPermission> => {
      const existing = inFlight.current;
      if (existing) return existing;

      setPending(true);
      const promise = read()
        .then((result) => {
          if (mounted.current) {
            setState(result);
            setError(undefined);
          }
          return result;
        })
        .catch((cause: unknown) => {
          const failure = cause instanceof Error ? cause : new Error(String(cause));
          if (mounted.current) setError(failure);
          return UNDETERMINED;
        })
        .finally(() => {
          inFlight.current = null;
          if (mounted.current) setPending(false);
        });

      inFlight.current = promise;
      return promise;
    },
    [],
  );

  const request = React.useCallback(() => run(requestCameraPermission), [run]);

  React.useEffect(() => {
    void run(ask ? requestCameraPermission : getCameraPermission);
  }, [ask, run]);

  return { ...state, pending, request, ...(error ? { error } : {}) };
}
